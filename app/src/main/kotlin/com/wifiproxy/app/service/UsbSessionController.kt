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

/** Raised internally when [UsbSessionController] notices, after a blocking/suspending start step,
 *  that a concurrent [UsbSessionController.stop] has already moved the phase away from STARTING.
 *  Never escapes [UsbSessionController.doStart]. */
private class StartInterruptedByStopException : Exception()

/**
 * Owns the session state machine for the USB (`adb forward`) path (design spec §8a):
 * `Idle -> Starting -> Running <-> Degraded(no mobile data) -> Stopping -> Idle`.
 *
 * [egress], [maxClients], [serverFactory], [now] and [scope] are all injected so the state
 * transitions, the degraded/recover path and the watchdog rebuild can be unit-tested on the JVM
 * with fakes; [ProxySessionService] supplies the real [com.wifiproxy.app.egress.CellularEgress]
 * and [defaultServerFactory].
 *
 * **Serialization (C2):** [start], [stop], the watchdog body, the network-generation rebuild and
 * `onLinkChange`'s filter refresh all run as coroutines launched on [scope] and nowhere else, never
 * synchronously on a caller's thread. In production, [ProxySessionService] builds [scope] on
 * `Dispatchers.Default.limitedParallelism(1)`, so those coroutines are strictly serialized against
 * each other - the watchdog can never rebuild a server that a concurrent `stop()` just tore down,
 * and a `stop()` that arrives mid-`start()` is never lost, only queued or (if `start()` is already
 * blocked inside a synchronous call like `ProxyServer.start()`) observed by `doStart()` re-checking
 * [state] once that call returns. A JVM test can pass a non-serial scope (e.g. `backgroundScope`
 * from `runTest`) since the virtual-time test dispatcher is itself effectively single-threaded.
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

    /** Set when the controller falls back to IDLE because `start()` itself failed (C1) - cleared
     *  on the next successful start. Distinct from [degradedReason], which only ever applies while
     *  RUNNING/DEGRADED; this is what the UI shows for "Start" having failed outright. */
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

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
    private var networkGenerationJob: Job? = null
    private var watchdogStarted = false

    /**
     * Invoked synchronously (on [scope]) every time [teardown] runs - i.e. every time the
     * controller reaches IDLE from any other phase, however it got there (a deliberate [stop], or
     * [doStart] rolling back a failed/interrupted start). [ProxySessionService] sets this
     * *before* ever calling [start] and uses it to call `stopSelf(startId)` (C1).
     *
     * Deliberately a plain callback rather than something [ProxySessionService] subscribes to by
     * collecting [state]: a `collectLatest` on [state] only sees the *current* value at whatever
     * moment it happens to start collecting, not necessarily the transition into it, so a fast
     * failure (`doStart` throwing and rolling back to IDLE within the same dispatch as `start()`)
     * can complete before such a collector has even subscribed - at which point its first-ever
     * observed value is already IDLE, indistinguishable from "was always idle". That subscription
     * race doesn't exist here: this field is a plain property write on the calling (main) thread,
     * already in place before [start] is ever invoked, with no Flow replay semantics to race.
     */
    @Volatile var onIdle: (() -> Unit)? = null

    init {
        egress.onLinkChange = ::onLinkChange
    }

    /** Atomically moves IDLE -> STARTING; a no-op (not queued) from any other phase, so two
     *  concurrent callers can never both schedule a [doStart]. */
    fun start() {
        if (!_state.compareAndSet(SessionPhase.IDLE, SessionPhase.STARTING)) return
        appendLog("Starting USB session")
        ensureWatchdog()
        scope.launch { doStart() }
    }

    private suspend fun doStart() {
        var server: UsbProxyServer? = null
        try {
            egress.start()
            checkNotInterruptedByStop()

            val registry = ClientRegistry(maxClients = maxClients().coerceIn(1, ClientRegistry.MAX_CLIENTS_LIMIT))
            if (registry.addPinnedSlot(PINNED_SLOT_NAME, bindAddress) == null) {
                appendLog("Could not pin the USB slot (no room)")
            }
            val config = ProxyConfig(bindAddress = bindAddress, port = port, peerSubnet = peerSubnet)
            val newServer = serverFactory.create(config, registry, egress, buildFilter(), ::onServerError)
            server = newServer
            newServer.start()
            checkNotInterruptedByStop()

            activeServer = newServer
            activeRegistry = registry

            // Set the initial Running/Degraded phase directly from the current value, rather than
            // through applyAvailability's RUNNING/DEGRADED-only transitions below: at this point
            // the phase is still (expected to be) STARTING, which neither of those transitions
            // matches.
            //
            // CAS rather than a plain write (R2): a concurrent stop() can move the phase to
            // STOPPING in the gap between the checkNotInterruptedByStop() above and this line (no
            // suspension point separates them, but egress.available.value is a plain property read
            // on a StateFlow backed by another thread, so nothing prevents a real stop() landing
            // here). A plain `_state.value = target` would silently clobber that STOPPING back to
            // RUNNING/DEGRADED; doStop would then see a phase that isn't what it set, return early
            // without tearing anything down, and the stop would be lost for good. CAS instead
            // leaves STOPPING alone and routes this exactly like any other interrupted start.
            val available = egress.available.value
            val target = if (available) SessionPhase.RUNNING else SessionPhase.DEGRADED
            if (!_state.compareAndSet(SessionPhase.STARTING, target)) throw StartInterruptedByStopException()
            if (available) {
                appendLog("USB session running")
            } else {
                _degradedReason.value = "No mobile data"
                appendLog("Degraded: no mobile data")
            }
            _lastError.value = null
            observeAvailability()
            observeNetworkGeneration()
        } catch (e: StartInterruptedByStopException) {
            appendLog("Stop arrived while starting; rolling back")
            rollbackStart(server)
        } catch (e: Exception) {
            appendLog("Start failed: ${e::class.simpleName}")
            _lastError.value = e.message ?: e::class.simpleName ?: "Start failed"
            rollbackStart(server)
        }
    }

    /** (C2) Re-checked after every step in [doStart] that can block/suspend for a real amount of
     *  time (`egress.start()`, `server.start()`): a concurrent [stop] moves [state] away from
     *  STARTING directly (not through [scope]), so this is always safe to read. */
    private fun checkNotInterruptedByStop() {
        if (_state.value != SessionPhase.STARTING) throw StartInterruptedByStopException()
    }

    /** (C1) Rolls back whatever [doStart] managed to start before it failed or was interrupted by
     *  a concurrent [stop]: the server first (never leave a listener bound), then the egress, each
     *  tolerant of throwing, then back to IDLE. */
    private fun rollbackStart(server: UsbProxyServer?) {
        runCatching { server?.stop() }
        runCatching { egress.stop() }
        teardown()
    }

    private fun observeAvailability() {
        availabilityJob?.cancel()
        availabilityJob = scope.launch {
            egress.available.collect(::applyAvailability)
        }
    }

    /** (I5) Rebuilds the proxy server once per *distinct* cellular network, skipping the first
     *  value this collector ever sees (that one is just the network already in place when we
     *  subscribed, not a change). */
    private fun observeNetworkGeneration() {
        networkGenerationJob?.cancel()
        networkGenerationJob = scope.launch {
            var first = true
            egress.networkGeneration.collect {
                if (first) {
                    first = false
                    return@collect
                }
                rebuildForNetworkChange()
            }
        }
    }

    private fun rebuildForNetworkChange() {
        val phase = _state.value
        if (phase != SessionPhase.RUNNING && phase != SessionPhase.DEGRADED) return
        val server = activeServer ?: return
        appendLog("Cellular network changed; rebuilding the proxy server")
        try {
            server.stop()
            server.updateFilter(buildFilter())
            server.start()
        } catch (e: Exception) {
            handleRecoveryFailure("Network rebuild failed: ${e::class.simpleName}", e)
        }
    }

    /** Only switches Running <-> Degraded; a no-op in every other phase (including the replayed
     *  value a freshly-collected [StateFlow] always delivers immediately on subscribe). CAS rather
     *  than a plain write (R2): guards against a concurrent [stop] having already moved the phase
     *  on (e.g. to STOPPING) between the `when`'s `current` read and the write below - a plain
     *  write would otherwise clobber that transition and lose the stop. */
    private fun applyAvailability(available: Boolean) {
        val current = _state.value
        when {
            current == SessionPhase.RUNNING && !available -> {
                if (_state.compareAndSet(SessionPhase.RUNNING, SessionPhase.DEGRADED)) {
                    _degradedReason.value = "No mobile data"
                    appendLog("Degraded: no mobile data")
                }
            }

            current == SessionPhase.DEGRADED && available -> {
                if (_state.compareAndSet(SessionPhase.DEGRADED, SessionPhase.RUNNING)) {
                    _degradedReason.value = null
                    appendLog("Recovered: USB session running")
                }
            }
        }
    }

    /** (M10) Hops onto [scope] - never runs on the connectivity callback thread that invokes it -
     *  since [buildFilter] does IPC (`ConnectivityManager.getAllNetworks`/`getLinkProperties`) and
     *  `NetworkInterface` enumeration. Also keeps it serialized against start/stop/watchdog (C2). */
    private fun onLinkChange() {
        scope.launch {
            activeServer?.updateFilter(buildFilter())
        }
    }

    private fun buildFilter(): DestinationFilter = DestinationFilter.default(wifiDirectSubnet, egress.hostAddresses())

    /** Atomically moves any phase except IDLE/STOPPING to STOPPING, so two concurrent callers
     *  never both schedule a [doStop]. */
    fun stop() {
        var shouldStop = false
        _state.update { current ->
            if (current == SessionPhase.IDLE || current == SessionPhase.STOPPING) {
                current
            } else {
                shouldStop = true
                SessionPhase.STOPPING
            }
        }
        if (!shouldStop) return
        appendLog("Stopping USB session")
        scope.launch { doStop() }
    }

    private suspend fun doStop() {
        // Anything but STOPPING means this stop() has already been honoured: a concurrent doStart()
        // noticed it and rolled itself back to IDLE (see checkNotInterruptedByStop/rollbackStart).
        // The phase may even have moved on to STARTING again by now, for a *new* session whose
        // doStart is queued behind this doStop - tearing down here would kill that session. Since
        // every serial-side transition is a CAS (R2), nothing but teardown() can move the phase
        // off STOPPING, so this check can no longer lose a stop the way it did before R2.
        if (_state.value != SessionPhase.STOPPING) return
        try {
            runCatching { activeServer?.stop() }
            runCatching { egress.stop() }
        } finally {
            teardown()
            appendLog("USB session stopped")
        }
    }

    /** Always called from a phase other than IDLE (every call site above guards that), so
     *  unconditionally notifying [onIdle] here is always a real non-idle -> IDLE transition. */
    private fun teardown() {
        availabilityJob?.cancel()
        availabilityJob = null
        networkGenerationJob?.cancel()
        networkGenerationJob = null
        activeServer = null
        activeRegistry = null
        _degradedReason.value = null
        _state.value = SessionPhase.IDLE
        onIdle?.invoke()
    }

    /** Synchronous, best-effort shutdown for [ProxySessionService.onDestroy] when the controller
     *  never reached IDLE through the normal [stop] path (I1) - e.g. the process is being torn
     *  down mid-session. Bypasses [scope] entirely (which may already be cancelled/cancelling) and
     *  blocks for as long as the server's NIO loop join takes (~1s) plus the egress unregister, so
     *  it must only ever be called from a background thread, never main. */
    fun shutdownBlocking() {
        runCatching { activeServer?.stop() }
        runCatching { egress.stop() }
        teardown()
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
            try {
                watchdogTick()
            } catch (e: Exception) {
                handleRecoveryFailure("Watchdog error: ${e::class.simpleName}", e)
            }
        }
    }

    /** (C2) Runs inside the same [scope]-launched coroutine as the rest of the watchdog loop, so
     *  it's already serialized against start/stop/network-change work; re-reads [state] itself
     *  (I2) rather than trusting a value captured before the preceding `delay`. */
    private fun watchdogTick() {
        val phase = _state.value
        if (phase != SessionPhase.RUNNING && phase != SessionPhase.DEGRADED) return
        val server = activeServer ?: return
        server.updateFilter(buildFilter()) // M3: refresh on every tick, not just on a link change.
        if (!server.isAccepting) {
            appendLog("Watchdog: rebuilding the proxy server")
            server.stop()
            server.start()
        }
    }

    /** (I2) A watchdog or network-change rebuild that itself throws must never crash the process:
     *  log it and fall back to DEGRADED while a session still exists to recover from, or leave the
     *  phase alone if there's nothing left to degrade (already on the way to IDLE). */
    private fun handleRecoveryFailure(message: String, e: Throwable) {
        appendLog(message)
        val phase = _state.value
        if (phase == SessionPhase.RUNNING || phase == SessionPhase.DEGRADED) {
            // CAS rather than a plain write (R2): a concurrent stop() may have already moved the
            // phase away from `phase` by the time this runs.
            if (_state.compareAndSet(phase, SessionPhase.DEGRADED)) {
                _degradedReason.value = "Recovery failed: ${e::class.simpleName}"
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
