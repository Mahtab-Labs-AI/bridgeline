package com.wifiproxy.app.service

import com.wifiproxy.app.egress.SessionEgress
import com.wifiproxy.app.session.LogEntry
import com.wifiproxy.app.session.SessionPhase
import com.wifiproxy.core.ProxyConfig
import com.wifiproxy.core.ProxyServer
import com.wifiproxy.core.clients.ClientRegistry
import com.wifiproxy.core.egress.Egress
import com.wifiproxy.core.net.Cidr
import com.wifiproxy.core.net.DestinationFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetAddress

private const val LOG_CAPACITY = 500
private const val PINNED_SLOT_NAME = "Laptop (USB)"
private const val DEFAULT_WATCHDOG_INTERVAL_MS = 30_000L
private const val DEFAULT_PORT = 8081

/**
 * What [UsbSessionController] needs from the HTTP proxy, abstracted so a JVM test can fake
 * [isAccepting] instead of standing up (and killing) a real NIO accept loop to exercise the
 * watchdog. [RealUsbProxyServer] is the only non-test implementation.
 */
interface UsbProxyServer {
    val isAccepting: Boolean
    fun start()
    fun stop()

    /** Must go through `ProxyServer.revoke`, never `ClientRegistry.revoke` directly (the latter
     *  wouldn't cancel the slot's live tunnels). [RealUsbProxyServer] honours this. */
    fun revoke(slotId: String): Boolean
    fun updateFilter(filter: DestinationFilter)
}

/** Forwards every call to a real [ProxyServer]. */
class RealUsbProxyServer(private val delegate: ProxyServer) : UsbProxyServer {
    override val isAccepting: Boolean get() = delegate.isAccepting
    override fun start() = delegate.start()
    override fun stop() = delegate.stop()
    override fun revoke(slotId: String): Boolean = delegate.revoke(slotId)
    override fun updateFilter(filter: DestinationFilter) = delegate.updateFilter(filter)
}

fun interface UsbProxyServerFactory {
    fun create(
        config: ProxyConfig,
        registry: ClientRegistry,
        egress: Egress,
        filter: DestinationFilter,
        onError: (Throwable) -> Unit,
    ): UsbProxyServer
}

private val defaultServerFactory = UsbProxyServerFactory { config, registry, egress, filter, onError ->
    RealUsbProxyServer(ProxyServer(config, registry, egress, filter, onError))
}

/**
 * Owns the session state machine for the USB (`adb forward`) path (design spec §8a):
 * `Idle -> Starting -> Running <-> Degraded(no mobile data) -> Stopping -> Idle`.
 *
 * [egress], [maxClients], [serverFactory], [now] and [scope] are all injected so the state
 * transitions, the degraded/recover path and the watchdog rebuild can be unit-tested on the JVM
 * with fakes; [ProxySessionService] supplies the real [com.wifiproxy.app.egress.CellularEgress]
 * and [defaultServerFactory].
 *
 * [start] and [stop] only ever launch work on [scope]; production wiring builds that scope on
 * `Dispatchers.Default` so the blocking parts of [ProxyServer.stop] (it joins the NIO loop
 * thread, up to ~1s) and `CellularEgress.stop` never run on the main thread.
 */
class UsbSessionController(
    private val egress: SessionEgress,
    private val maxClients: () -> Int = { 1 },
    private val bindAddress: InetAddress = InetAddress.getByName("127.0.0.1"),
    private val port: Int = DEFAULT_PORT,
    private val peerSubnet: Cidr = Cidr.parse("127.0.0.0/8"),
    private val wifiDirectSubnet: Cidr = Cidr.parse("192.168.49.0/24"),
    private val serverFactory: UsbProxyServerFactory = defaultServerFactory,
    private val now: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope,
    private val watchdogIntervalMs: Long = DEFAULT_WATCHDOG_INTERVAL_MS,
) {
    private val _state = MutableStateFlow(SessionPhase.IDLE)
    val state: StateFlow<SessionPhase> = _state.asStateFlow()

    private val _degradedReason = MutableStateFlow<String?>(null)
    val degradedReason: StateFlow<String?> = _degradedReason.asStateFlow()

    /** Mirrors the egress's own availability, independent of [state] (e.g. still meaningful while
     *  [SessionPhase.STARTING] or [SessionPhase.STOPPING]). */
    val cellularAvailable: StateFlow<Boolean> get() = egress.available

    private val _log = MutableStateFlow<List<LogEntry>>(emptyList())
    val log: StateFlow<List<LogEntry>> = _log.asStateFlow()

    @Volatile private var activeServer: UsbProxyServer? = null

    @Volatile private var activeRegistry: ClientRegistry? = null

    /** The registry backing the live session, or null while not running. [ServiceSessionRepository]
     *  polls [ClientRegistry.slots] on this for live per-device counters. */
    val registry: ClientRegistry? get() = activeRegistry

    private var availabilityJob: Job? = null
    private var watchdogStarted = false

    init {
        egress.onLinkChange = ::onLinkChange
    }

    fun start() {
        if (_state.value != SessionPhase.IDLE) return
        _state.value = SessionPhase.STARTING
        appendLog("Starting USB session")
        ensureWatchdog()
        scope.launch {
            try {
                doStart()
            } catch (e: Exception) {
                appendLog("Start failed: ${e::class.simpleName}")
                teardown()
            }
        }
    }

    private fun doStart() {
        egress.start()
        val registry = ClientRegistry(maxClients = maxClients().coerceIn(1, ClientRegistry.MAX_CLIENTS_LIMIT))
        activeRegistry = registry
        if (registry.addPinnedSlot(PINNED_SLOT_NAME, bindAddress) == null) {
            appendLog("Could not pin the USB slot (no room)")
        }
        val config = ProxyConfig(bindAddress = bindAddress, port = port, peerSubnet = peerSubnet)
        val server = serverFactory.create(config, registry, egress, buildFilter(), ::onServerError)
        server.start()
        activeServer = server

        // Set the initial Running/Degraded phase directly from the current value, rather than
        // through applyAvailability's RUNNING/DEGRADED-only transitions below: at this point the
        // phase is still STARTING, which neither of those transitions matches.
        val available = egress.available.value
        if (available) {
            _state.value = SessionPhase.RUNNING
            appendLog("USB session running")
        } else {
            _degradedReason.value = "No mobile data"
            _state.value = SessionPhase.DEGRADED
            appendLog("Degraded: no mobile data")
        }

        observeAvailability()
    }

    private fun observeAvailability() {
        availabilityJob?.cancel()
        availabilityJob = scope.launch {
            egress.available.collect(::applyAvailability)
        }
    }

    /** Only switches Running <-> Degraded; a no-op in every other phase (including the replayed
     *  value a freshly-collected [StateFlow] always delivers immediately on subscribe). */
    private fun applyAvailability(available: Boolean) {
        val current = _state.value
        when {
            current == SessionPhase.RUNNING && !available -> {
                _degradedReason.value = "No mobile data"
                _state.value = SessionPhase.DEGRADED
                appendLog("Degraded: no mobile data")
            }

            current == SessionPhase.DEGRADED && available -> {
                _degradedReason.value = null
                _state.value = SessionPhase.RUNNING
                appendLog("Recovered: USB session running")
            }
        }
    }

    private fun onLinkChange() {
        activeServer?.updateFilter(buildFilter())
    }

    private fun buildFilter(): DestinationFilter = DestinationFilter.default(wifiDirectSubnet, egress.hostAddresses())

    fun stop() {
        if (_state.value == SessionPhase.IDLE || _state.value == SessionPhase.STOPPING) return
        _state.value = SessionPhase.STOPPING
        appendLog("Stopping USB session")
        scope.launch { doStop() }
    }

    private fun doStop() {
        activeServer?.stop()
        egress.stop()
        teardown()
        appendLog("USB session stopped")
    }

    private fun teardown() {
        availabilityJob?.cancel()
        availabilityJob = null
        activeServer = null
        activeRegistry = null
        _degradedReason.value = null
        _state.value = SessionPhase.IDLE
    }

    /** Goes through [UsbProxyServer.revoke] (in production, `ProxyServer.revoke`), never the
     *  registry directly, so the slot's live tunnels are cancelled too. */
    fun revoke(slotId: String): Boolean = activeServer?.revoke(slotId) ?: false

    private fun ensureWatchdog() {
        if (watchdogStarted) return
        watchdogStarted = true
        scope.launch { watchdogLoop() }
    }

    private suspend fun watchdogLoop() {
        while (currentCoroutineContext().isActive) {
            delay(watchdogIntervalMs)
            val phase = _state.value
            if (phase != SessionPhase.RUNNING && phase != SessionPhase.DEGRADED) continue
            val server = activeServer ?: continue
            if (!server.isAccepting) {
                appendLog("Watchdog: rebuilding the proxy server")
                server.stop()
                server.start()
            }
        }
    }

    private fun onServerError(e: Throwable) {
        // Non-I/O bugs and accept-loop failures only (see ProxyServer's onError contract) - never
        // a hostname or URL, so the exception's class name is all that goes in the log.
        appendLog("Proxy error: ${e::class.simpleName}")
    }

    private fun appendLog(message: String) {
        _log.update { (it + LogEntry(now(), message)).takeLast(LOG_CAPACITY) }
    }
}
