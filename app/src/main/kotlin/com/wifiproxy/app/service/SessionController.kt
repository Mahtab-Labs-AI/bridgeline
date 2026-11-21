package com.wifiproxy.app.service

import com.wifiproxy.app.egress.SessionEgress
import com.wifiproxy.app.session.ClientPlatform
import com.wifiproxy.app.session.LogEntry
import com.wifiproxy.app.session.SessionPhase
import com.wifiproxy.app.wifi.LinkConfig
import com.wifiproxy.app.wifi.LinkManager
import com.wifiproxy.app.wifi.LinkState
import com.wifiproxy.app.wifi.PersistedSlot
import com.wifiproxy.app.wifi.REASON_CREATING
import com.wifiproxy.app.wifi.SlotPersistence
import com.wifiproxy.core.ProxyConfig
import com.wifiproxy.core.ProxyServer
import com.wifiproxy.core.clients.ClientRegistry
import com.wifiproxy.core.clients.Slot
import com.wifiproxy.core.egress.Egress
import com.wifiproxy.core.net.Cidr
import com.wifiproxy.core.net.DestinationFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

private const val LOG_CAPACITY = 500
private const val PINNED_SLOT_NAME = "Laptop (USB)"
private const val DEFAULT_WATCHDOG_INTERVAL_MS = 30_000L
private const val DEFAULT_PORT = 8081
private const val REASON_NO_MOBILE_DATA = "No mobile data"
private const val NO_LISTENER_MESSAGE = "Set a Wi-Fi password in Settings or turn on USB sharing"

/** Who may reach the USB listener: it binds loopback, reached through `adb forward`. */
private val USB_PEER_SUBNET = Cidr.parse("127.0.0.0/8")

/** The subnet Android uses for Wi-Fi Direct groups; blocked as a destination while the group's
 *  real subnet isn't known (the link isn't Up). */
private val DEFAULT_WIFI_DIRECT_SUBNET = Cidr.parse("192.168.49.0/24")

/** What one session runs with; [SessionController] reads it once per start. */
data class SessionConfig(
    val usbEnabled: Boolean,
    /** null = no Wi-Fi credentials set: the session is USB-only. */
    val wifi: LinkConfig?,
    val wifiPort: Int,
    val maxClients: Int,
    /** D8: false (the default) means open admission on the Wi-Fi listener - any device in the
     *  group may use the proxy, up to [maxClients]; true keeps per-device invites. Never affects
     *  the USB listener, which only ever admits its pinned laptop. */
    val requireDeviceCodes: Boolean = false,
)

/** See [SessionController.wifiEndpoint]. */
data class WifiEndpoint(val group: LinkState.Up, val port: Int)

/**
 * What [SessionController] needs from the HTTP proxy, abstracted so a JVM test can fake
 * [isAccepting] instead of standing up (and killing) a real NIO accept loop to exercise the
 * watchdog. [RealSessionServer] is the only non-test implementation.
 */
interface SessionServer {
    val isAccepting: Boolean
    fun start()
    fun stop()

    /** Must go through `ProxyServer.revoke`, never `ClientRegistry.revoke` directly (the latter
     *  wouldn't cancel the slot's live tunnels). [RealSessionServer] honours this. */
    fun revoke(slotId: String): Boolean
    fun updateFilter(filter: DestinationFilter)
}

/** Forwards every call to a real [ProxyServer]. */
class RealSessionServer(private val delegate: ProxyServer) : SessionServer {
    override val isAccepting: Boolean get() = delegate.isAccepting
    override fun start() = delegate.start()
    override fun stop() = delegate.stop()
    override fun revoke(slotId: String): Boolean = delegate.revoke(slotId)
    override fun updateFilter(filter: DestinationFilter) = delegate.updateFilter(filter)
}

fun interface SessionServerFactory {
    fun create(
        config: ProxyConfig,
        registry: ClientRegistry,
        egress: Egress,
        filter: DestinationFilter,
        onError: (Throwable) -> Unit,
    ): SessionServer
}

private val defaultServerFactory = SessionServerFactory { config, registry, egress, filter, onError ->
    RealSessionServer(ProxyServer(config, registry, egress, filter, onError))
}

/** Raised internally when [SessionController] notices, after a blocking/suspending start step,
 *  that a concurrent [SessionController.stop] has already moved the phase away from STARTING.
 *  Never escapes [SessionController.doStart]. */
private class StartInterruptedByStopException : Exception()

/**
 * Owns the session state machine (design spec §8a):
 * `Idle -> Starting -> Running <-> Degraded -> Stopping -> Idle`.
 *
 * A session runs up to two [ProxyServer]s over one [ClientRegistry]: a loopback listener for a
 * laptop over USB (`adb forward`, with a pinned slot) when [SessionConfig.usbEnabled], and a
 * Wi-Fi Direct listener on the group-owner address whenever [link] is [LinkState.Up], when
 * [SessionConfig.wifi] is set. Degraded means no mobile data, or (Wi-Fi sessions) a link that
 * isn't Up; [degradedReason] says which.
 *
 * [egress], [link], [slots], [config], [serverFactory], [now] and [scope] are all injected so the
 * state transitions, the degraded/recover path and the watchdog rebuild can be unit-tested on the
 * JVM with fakes; [ProxySessionService] supplies the real ones.
 *
 * **Serialization (C2):** [start], [stop], the watchdog body, the network-generation rebuild,
 * `onLinkChange`'s filter refresh and the link-state collector all run as coroutines launched on
 * [scope] and nowhere else, never synchronously on a caller's thread. In production,
 * [ProxySessionService] builds [scope] on `Dispatchers.Default.limitedParallelism(1)`, so those
 * coroutines are strictly serialized against each other - the watchdog can never rebuild a server
 * that a concurrent `stop()` just tore down, and a `stop()` that arrives mid-`start()` is never
 * lost, only queued or (if `start()` is already blocked inside a synchronous call like
 * `ProxyServer.start()`) observed by `doStart()` re-checking [state] once that call returns. A JVM
 * test can pass a non-serial scope (e.g. `backgroundScope` from `runTest`) since the virtual-time
 * test dispatcher is itself effectively single-threaded.
 *
 * Teardown suspends in [LinkManager.stop], and a serial dispatcher only serializes coroutines
 * between suspension points, so [doStart] (including its rollback), [doStop] and [shutdown] also
 * hold [lifecycle] for their whole run: a queued `doStop` can never run in the middle of a
 * suspended rollback (and tear down twice, or kill the session a rollback's `onIdle` restarted).
 */
class SessionController(
    private val egress: SessionEgress,
    private val link: LinkManager,
    private val slots: SlotPersistence,
    private val config: () -> SessionConfig,
    private val usbBindAddress: InetAddress = InetAddress.getByName("127.0.0.1"),
    private val usbPort: Int = DEFAULT_PORT,
    private val serverFactory: SessionServerFactory = defaultServerFactory,
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

    /** The Wi-Fi Direct link's own state, independent of [state]. */
    val linkState: StateFlow<LinkState> get() = link.state

    private val _log = MutableStateFlow<List<LogEntry>>(emptyList())
    val log: StateFlow<List<LogEntry>> = _log.asStateFlow()

    @Volatile private var usbServer: SessionServer? = null

    @Volatile private var wifiServer: SessionServer? = null

    /** The Wi-Fi server most recently stopped this session, kept so [revoke] still goes through a
     *  `ProxyServer` while the group is down. */
    @Volatile private var lastWifiServer: SessionServer? = null

    /** The exact group [wifiServer] serves; null while no Wi-Fi server is running. */
    @Volatile private var wifiServerFor: LinkState.Up? = null

    @Volatile private var activeRegistry: ClientRegistry? = null

    /** [config] as read once by [doStart]; null while idle. */
    @Volatile private var sessionConfig: SessionConfig? = null

    /** Platform per non-pinned slot id, for persistence and [platformOf]. Written from both
     *  [scope] ([doStart]/[teardown]) and the caller's thread ([addDevice]/[revoke]). */
    private val platforms: MutableMap<String, ClientPlatform> = ConcurrentHashMap()

    /** Saved devices that didn't fit this session's registry (no free seat). Never in the
     *  registry, but always written back by [persist], so they survive until a later session
     *  has room for them. */
    @Volatile private var inactiveSlots: List<PersistedSlot> = emptyList()

    /** Held by [doStart], [doStop] and [shutdown] for their whole run; see the class KDoc. */
    private val lifecycle = Mutex()

    /** Guards reading [activeRegistry], [inactiveSlots] and [platforms] together ([captureSeats])
     *  against [teardown] clearing them: [addDevice] and [revoke] run on the caller's thread, so a
     *  teardown on [scope] can land between their own reads and their [persist]. */
    private val seatsLock = Any()

    /** The registry backing the live session, or null while not running. [ServiceSessionRepository]
     *  polls [ClientRegistry.slots] on this for live per-device counters. */
    val registry: ClientRegistry? get() = activeRegistry

    /** The Wi-Fi listener's port while a session with Wi-Fi Direct runs, else null. */
    val wifiPort: Int?
        get() {
            val phase = _state.value
            if (phase != SessionPhase.RUNNING && phase != SessionPhase.DEGRADED) return null
            val cfg = sessionConfig ?: return null
            return if (cfg.wifi != null) cfg.wifiPort else null
        }

    /** The group the Wi-Fi listener is serving and its port, while one really runs; null while the
     *  link isn't Up or the listener couldn't start. This, not the link state alone, is what a
     *  device invite may point at. */
    val wifiEndpoint: WifiEndpoint?
        get() {
            if (wifiServer == null) return null
            val up = wifiServerFor ?: return null
            val port = wifiPort ?: return null
            return WifiEndpoint(up, port)
        }

    /** True while this session's USB (loopback) listener exists. */
    val usbListening: Boolean get() = usbServer != null

    /** Whether the current session has Wi-Fi Direct configured; false while idle. */
    val wifiConfigured: Boolean get() = sessionConfig?.wifi != null

    /** Whether this session's Wi-Fi listener admits any device in the group (D8); null while
     *  idle, when the next start's setting is what counts. */
    val openAdmission: Boolean? get() = sessionConfig?.let { !it.requireDeviceCodes }

    private var availabilityJob: Job? = null
    private var networkGenerationJob: Job? = null
    private var linkJob: Job? = null
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
        appendLog("Starting session")
        ensureWatchdog()
        scope.launch { doStart() }
    }

    private suspend fun doStart() {
        lifecycle.withLock {
            var usb: SessionServer? = null
            try {
                val cfg = config()
                sessionConfig = cfg
                val wifi = cfg.wifi
                check(cfg.usbEnabled || wifi != null) { NO_LISTENER_MESSAGE }

                egress.start()
                checkNotInterruptedByStop()

                // While USB sharing is on, the laptop always keeps its seat: saved Wi-Fi devices
                // only get the rest. Those that don't fit stay saved (see persist) but inactive
                // for this session, so a restore never deletes a stored device.
                //
                // (D8) Under open admission no saved device is restored at all: each would hold a
                // seat while unbound, filling the registry before anyone connects. They all stay
                // saved (inactive, written back by persist) for the next code-mode session; their
                // old per-device URLs still work meanwhile, as auto slots (LocalContent).
                val maxClients = cfg.maxClients.coerceIn(1, ClientRegistry.MAX_CLIENTS_LIMIT)
                val seats = if (!cfg.requireDeviceCodes) 0 else maxClients - (if (cfg.usbEnabled) 1 else 0)
                val persisted = slots.load()
                val restored = persisted.take(seats)
                inactiveSlots = persisted.drop(seats)
                if (inactiveSlots.isNotEmpty()) {
                    appendLog(
                        if (cfg.requireDeviceCodes) {
                            "${inactiveSlots.size} saved device(s) inactive: no free slot"
                        } else {
                            "${inactiveSlots.size} saved device(s) kept for code mode"
                        },
                    )
                }
                platforms.clear()
                restored.forEach { platforms[it.record.id] = it.platform }
                val registry = ClientRegistry(maxClients = maxClients, initial = restored.map { it.record })

                if (cfg.usbEnabled) {
                    if (registry.addPinnedSlot(PINNED_SLOT_NAME, usbBindAddress) == null) {
                        appendLog("Could not pin the USB slot (no room)")
                    }
                    val usbConfig = ProxyConfig(bindAddress = usbBindAddress, port = usbPort, peerSubnet = USB_PEER_SUBNET)
                    val newServer = serverFactory.create(usbConfig, registry, egress, buildFilter(), ::onServerError)
                    usb = newServer
                    newServer.start()
                    checkNotInterruptedByStop()
                }

                usbServer = usb
                activeRegistry = registry
                // Returns immediately; the link-state collector below follows it from here.
                if (wifi != null) link.start(wifi)

                // Set the initial Running/Degraded phase directly from the current health, rather
                // than through refreshHealth's RUNNING/DEGRADED-only transitions below: at this
                // point the phase is still (expected to be) STARTING, which neither of those
                // transitions matches.
                //
                // CAS rather than a plain write (R2): a concurrent stop() can move the phase to
                // STOPPING in the gap between the checkNotInterruptedByStop() above and this line
                // (no suspension point separates them, but healthReason() reads
                // egress.available.value, a plain property read on a StateFlow backed by another
                // thread, so nothing prevents a real stop() landing here). A plain
                // `_state.value = target` would silently clobber that STOPPING back to
                // RUNNING/DEGRADED; doStop would then see a phase that isn't what it set, return
                // early without tearing anything down, and the stop would be lost for good. CAS
                // instead leaves STOPPING alone and routes this exactly like any other interrupted
                // start.
                val reason = healthReason()
                val target = if (reason == null) SessionPhase.RUNNING else SessionPhase.DEGRADED
                if (!_state.compareAndSet(SessionPhase.STARTING, target)) throw StartInterruptedByStopException()
                if (reason == null) {
                    appendLog("Session running")
                } else {
                    _degradedReason.value = reason
                    appendLog("Degraded: $reason")
                }
                _lastError.value = null
                observeAvailability()
                observeNetworkGeneration()
                if (wifi != null) observeLink()
            } catch (e: StartInterruptedByStopException) {
                appendLog("Stop arrived while starting; rolling back")
                rollbackStart(usb)
            } catch (e: Exception) {
                appendLog("Start failed: ${e::class.simpleName}")
                _lastError.value = e.message ?: e::class.simpleName ?: "Start failed"
                rollbackStart(usb)
            }
        }
    }

    /** (C2) Re-checked after every step in [doStart] that can block/suspend for a real amount of
     *  time (`egress.start()`, `server.start()`): a concurrent [stop] moves [state] away from
     *  STARTING directly (not through [scope]), so this is always safe to read. */
    private fun checkNotInterruptedByStop() {
        if (_state.value != SessionPhase.STARTING) throw StartInterruptedByStopException()
    }

    /** (C1) Rolls back whatever [doStart] managed to start before it failed or was interrupted by
     *  a concurrent [stop]: the servers first (never leave a listener bound), then the link, then
     *  the egress, each tolerant of throwing, then back to IDLE. [usb] is passed in because a
     *  server whose start failed was never published to [usbServer]. */
    private suspend fun rollbackStart(usb: SessionServer?) {
        stopAll(usb)
        runCatching { egress.stop() }
        teardown()
    }

    /** Shared by [rollbackStart], [doStop] and [shutdown]: stops the link collector (so nothing
     *  reacts to the link going down below), both servers, then the link. [LinkManager.stop] runs
     *  under [NonCancellable]: a teardown that has begun must finish even if [scope] is cancelled
     *  meanwhile (e.g. by the service's onDestroy). */
    private suspend fun stopAll(usb: SessionServer?) {
        linkJob?.cancel()
        linkJob = null
        runCatching { usb?.stop() }
        stopWifiServer()
        withContext(NonCancellable) {
            runCatching { link.stop() }
        }
    }

    private fun observeAvailability() {
        availabilityJob?.cancel()
        availabilityJob = scope.launch {
            egress.available.collect { refreshHealth() }
        }
    }

    /** (I5) Rebuilds the proxy servers once per *distinct* cellular network, skipping the first
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

    /** Follows the Wi-Fi Direct link: starts, rebuilds or stops the Wi-Fi listener to match it,
     *  then re-derives the Running/Degraded phase. */
    private fun observeLink() {
        linkJob?.cancel()
        linkJob = scope.launch {
            link.state.collect { state ->
                onLinkState(state)
                refreshHealth()
            }
        }
    }

    private fun rebuildForNetworkChange() {
        val phase = _state.value
        if (phase != SessionPhase.RUNNING && phase != SessionPhase.DEGRADED) return
        val servers = listOfNotNull(usbServer, wifiServer)
        if (servers.isEmpty()) return
        appendLog("Cellular network changed; rebuilding the proxy listeners")
        try {
            for (server in servers) {
                server.stop()
                server.updateFilter(buildFilter())
                server.start()
            }
        } catch (e: Exception) {
            handleRecoveryFailure("Network rebuild failed: ${e::class.simpleName}", e)
        }
    }

    /** Why the session is degraded right now, or null when it's healthy. No mobile data outranks
     *  everything; a Wi-Fi session is also degraded whenever its link isn't Up. */
    private fun healthReason(): String? {
        if (!egress.available.value) return REASON_NO_MOBILE_DATA
        val cfg = sessionConfig
        if (cfg?.wifi == null) return null
        return when (val s = link.state.value) {
            // The group is up but its listener couldn't start (startWifiServer failed): invites
            // would point at a dead port, so this is degraded until the watchdog's retry works.
            is LinkState.Up -> if (wifiServer == null) "Wi-Fi listener failed (port ${cfg.wifiPort} in use?)" else null
            is LinkState.Down -> s.reason
            is LinkState.Failed -> s.message
            LinkState.Idle -> REASON_CREATING
        }
    }

    /** Only switches Running <-> Degraded; a no-op in every other phase (including the replayed
     *  value a freshly-collected [StateFlow] always delivers immediately on subscribe). While
     *  DEGRADED, [degradedReason] follows [healthReason] even if the phase stays put (e.g. "Wi-Fi
     *  is off" -> "recreating"). CAS rather than a plain write (R2): guards against a concurrent
     *  [stop] having already moved the phase on (e.g. to STOPPING) between the `current` read and
     *  the write below - a plain write would otherwise clobber that transition and lose the stop. */
    private fun refreshHealth() {
        val current = _state.value
        if (current != SessionPhase.RUNNING && current != SessionPhase.DEGRADED) return
        val reason = healthReason()
        when {
            reason == null && current == SessionPhase.DEGRADED -> {
                if (_state.compareAndSet(SessionPhase.DEGRADED, SessionPhase.RUNNING)) {
                    _degradedReason.value = null
                    appendLog("Recovered: session running")
                }
            }

            reason != null && current == SessionPhase.RUNNING -> {
                if (_state.compareAndSet(SessionPhase.RUNNING, SessionPhase.DEGRADED)) {
                    _degradedReason.value = reason
                    appendLog("Degraded: $reason")
                }
            }

            reason != null && current == SessionPhase.DEGRADED -> _degradedReason.value = reason
        }
    }

    /** Runs on [scope], from the link-state collector. Bindings reset on every state that isn't
     *  Up (spec §4: every group teardown), whether or not a Wi-Fi listener was running. */
    private fun onLinkState(state: LinkState) {
        if (state is LinkState.Up) {
            if (wifiServerFor != state) startWifiServer(state, clearBindings = true)
        } else {
            stopWifiServer(clearBindings = false)
            activeRegistry?.clearBindings()
        }
    }

    /** Replaces any Wi-Fi listener with one serving [up]. [clearBindings] is false only for a
     *  watchdog rebuild of a dead listener for the very group it served: the group and its DHCP
     *  leases are unchanged, so dropping every device's binding would only lock them out until
     *  they re-fetch their PAC. If the start fails, [wifiServerFor] stays null so the watchdog
     *  retries. */
    private fun startWifiServer(up: LinkState.Up, clearBindings: Boolean) {
        val phase = _state.value
        if (phase != SessionPhase.RUNNING && phase != SessionPhase.DEGRADED) return
        val registry = activeRegistry ?: return
        val cfg = sessionConfig ?: return
        stopWifiServer(clearBindings = false)
        if (clearBindings) registry.clearBindings()
        val owner = up.ownerAddress
        // (D8) Open admission is a property of this listener only. (D7) The USB laptop's pinned
        // slot was added to the registry in doStart, before any Wi-Fi listener existed, so it
        // already holds its seat against every auto slot this listener creates.
        val proxyConfig = ProxyConfig(
            bindAddress = owner,
            port = cfg.wifiPort,
            peerSubnet = subnetOf(owner),
            wifi = up.credentials,
            openAdmission = !cfg.requireDeviceCodes,
        )
        var server: SessionServer? = null
        try {
            val newServer = serverFactory.create(proxyConfig, registry, egress, buildFilter(), ::onServerError)
            server = newServer
            newServer.start()
            wifiServer = newServer
            wifiServerFor = up
            appendLog("Wi-Fi listener running")
        } catch (e: Exception) {
            appendLog("Wi-Fi listener failed: ${e::class.simpleName}")
            runCatching { server?.stop() }
        }
    }

    /** Stops the Wi-Fi listener, if one is running, and clears the Wi-Fi bindings (the group it
     *  served is gone or being replaced). Keeps it in [lastWifiServer] for [revoke]. */
    private fun stopWifiServer(clearBindings: Boolean = true) {
        val server = wifiServer ?: return
        wifiServer = null
        wifiServerFor = null
        lastWifiServer = server
        runCatching { server.stop() }
        if (clearBindings) activeRegistry?.clearBindings()
        appendLog("Wi-Fi listener stopped")
    }

    /** (M10) Hops onto [scope] - never runs on the connectivity callback thread that invokes it -
     *  since [buildFilter] does IPC (`ConnectivityManager.getAllNetworks`/`getLinkProperties`) and
     *  `NetworkInterface` enumeration. Also keeps it serialized against start/stop/watchdog (C2). */
    private fun onLinkChange() {
        scope.launch {
            val servers = listOfNotNull(usbServer, wifiServer)
            if (servers.isEmpty()) return@launch
            val filter = buildFilter()
            servers.forEach { it.updateFilter(filter) }
        }
    }

    private fun buildFilter(): DestinationFilter {
        val subnet = (link.state.value as? LinkState.Up)?.let { subnetOf(it.ownerAddress) } ?: DEFAULT_WIFI_DIRECT_SUBNET
        return DestinationFilter.default(subnet, egress.hostAddresses())
    }

    private fun subnetOf(owner: Inet4Address): Cidr =
        Cidr(InetAddress.getByAddress(owner.address.copyOf().also { it[3] = 0 }), 24)

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
        appendLog("Stopping session")
        scope.launch { doStop() }
    }

    private suspend fun doStop() {
        lifecycle.withLock {
            // Anything but STOPPING means this stop() has already been honoured: a concurrent
            // doStart() noticed it and rolled itself back to IDLE (see
            // checkNotInterruptedByStop/rollbackStart). The phase may even have moved on to
            // STARTING again by now, for a *new* session whose doStart is queued behind this
            // doStop - tearing down here would kill that session. Since every serial-side
            // transition is a CAS (R2), nothing but teardown() can move the phase off STOPPING,
            // so this check can no longer lose a stop the way it did before R2.
            if (_state.value != SessionPhase.STOPPING) return
            try {
                stopAll(usbServer)
                runCatching { egress.stop() }
            } finally {
                teardown()
                appendLog("Session stopped")
            }
        }
    }

    /** Always called from a phase other than IDLE (every call site above guards that), so
     *  unconditionally notifying [onIdle] here is always a real non-idle -> IDLE transition. */
    private fun teardown() {
        availabilityJob?.cancel()
        availabilityJob = null
        networkGenerationJob?.cancel()
        networkGenerationJob = null
        linkJob?.cancel()
        linkJob = null
        wifiServerFor = null
        wifiServer = null
        lastWifiServer = null
        usbServer = null
        synchronized(seatsLock) {
            activeRegistry = null
            platforms.clear()
            inactiveSlots = emptyList()
        }
        sessionConfig = null
        _degradedReason.value = null
        _state.value = SessionPhase.IDLE
        onIdle?.invoke()
    }

    /** Best-effort shutdown for [ProxySessionService.onDestroy] when the controller never reached
     *  IDLE through the normal [stop] path (I1) - e.g. the process is being torn down mid-session.
     *  Stops both servers, the link and the egress. Waits for any start/stop already in progress
     *  (see [lifecycle]) and does nothing if that left the controller IDLE. Blocks for as long as
     *  the servers' NIO loop joins take (~1s each), so it must only ever run on a background
     *  dispatcher, never main. */
    suspend fun shutdown() {
        lifecycle.withLock {
            if (_state.value == SessionPhase.IDLE) return
            try {
                stopAll(usbServer)
                runCatching { egress.stop() }
            } finally {
                teardown()
            }
        }
    }

    /**
     * Adds a Wi-Fi device slot and persists it. Null when the session isn't RUNNING/DEGRADED, the
     * link isn't Up (an invite needs the group-owner address), the Wi-Fi listener isn't running
     * (the invite would point at a dead port) or there's no seat.
     *
     * (D7) The seats are counted against every saved Wi-Fi device, inactive ones included, plus
     * the laptop's seat while USB is on - not just the registry's free room, which a revoke can
     * open up while saved devices are still waiting for a seat.
     */
    fun addDevice(name: String, platform: ClientPlatform): Slot? {
        val phase = _state.value
        if (phase != SessionPhase.RUNNING && phase != SessionPhase.DEGRADED) return null
        val snap = captureSeats() ?: return null
        val cfg = sessionConfig ?: return null
        if (link.state.value !is LinkState.Up) return null
        if (wifiServer == null) return null
        val maxClients = cfg.maxClients.coerceIn(1, ClientRegistry.MAX_CLIENTS_LIMIT)
        val wifiSeats = maxClients - (if (cfg.usbEnabled) 1 else 0)
        val savedWifi = snap.registry.slots().count { !it.pinned } + snap.inactive.size
        if (savedWifi >= wifiSeats) return null
        val slot = snap.registry.addSlot(name) ?: return null
        platforms[slot.id] = platform
        persist(snap.registry, snap.inactive, snap.platforms + (slot.id to platform))
        appendLog("Device added")
        return slot
    }

    /** [ClientPlatform.LAPTOP] for the pinned USB slot, [ClientPlatform.UNKNOWN] for an auto slot
     *  (D8: nothing says what joined), the stored platform otherwise. */
    fun platformOf(slotId: String): ClientPlatform {
        val slot = activeRegistry?.slots()?.firstOrNull { it.id == slotId }
        if (slot?.pinned == true) return ClientPlatform.LAPTOP
        if (slot?.auto == true) return ClientPlatform.UNKNOWN
        return platforms[slotId] ?: ClientPlatform.ANDROID
    }

    /** Goes through [SessionServer.revoke] (in production, `ProxyServer.revoke`), never the
     *  registry directly, so the slot's live tunnels are cancelled too. A Wi-Fi slot goes through
     *  the Wi-Fi server - the running one, else the last stopped one, else the USB one; with none
     *  at all, through a never-started server, which has no tunnels and just removes the slot.
     *
     *  A saved device that is inactive this session (no free seat, so never in the registry and
     *  without tunnels) is simply dropped from the inactive list and from storage.
     *
     *  (D7) The pinned USB laptop slot can't be revoked: it always keeps its seat while USB
     *  sharing is on. Returns false for it.
     *
     *  Racing teardown: the registry, the inactive list and the platforms are captured together
     *  at the top ([captureSeats]) and that capture is what gets persisted, so a teardown landing
     *  mid-revoke can't make the persist drop the inactive devices or forget their platforms. */
    fun revoke(slotId: String): Boolean {
        val snap = captureSeats() ?: return false
        val registry = snap.registry
        if (snap.inactive.any { it.record.id == slotId }) {
            val remaining = snap.inactive.filterNot { it.record.id == slotId }
            synchronized(seatsLock) { if (activeRegistry === registry) inactiveSlots = remaining }
            platforms.remove(slotId)
            persist(registry, remaining, snap.platforms - slotId)
            appendLog("Inactive device removed")
            return true
        }
        if (registry.slots().any { it.id == slotId && it.pinned }) return false
        // The never-started server's filter is never consulted, so it gets the static default
        // rather than buildFilter(), which does IPC on this (caller's) thread.
        val server = wifiServer ?: lastWifiServer ?: usbServer
            ?: serverFactory.create(
                ProxyConfig(usbBindAddress, 0, USB_PEER_SUBNET),
                registry,
                egress,
                DestinationFilter.default(DEFAULT_WIFI_DIRECT_SUBNET),
            ) {}
        val revoked = server.revoke(slotId)
        if (revoked) {
            platforms.remove(slotId)
            persist(registry, snap.inactive, snap.platforms - slotId)
        }
        return revoked
    }

    /** What [persist] writes alongside the registry, read together under [seatsLock]. */
    private class SeatsSnapshot(
        val registry: ClientRegistry,
        val inactive: List<PersistedSlot>,
        val platforms: Map<String, ClientPlatform>,
    )

    /** Null while no session is running (or [teardown] already cleared it). */
    private fun captureSeats(): SeatsSnapshot? = synchronized(seatsLock) {
        activeRegistry?.let { SeatsSnapshot(it, inactiveSlots, platforms.toMap()) }
    }

    private fun persist(registry: ClientRegistry, inactive: List<PersistedSlot>, platforms: Map<String, ClientPlatform>) {
        try {
            val active = registry.snapshot().map { PersistedSlot(it, platforms[it.id] ?: ClientPlatform.ANDROID) }
            slots.save(active + inactive)
        } catch (e: Exception) {
            appendLog("Couldn't save devices: ${e::class.simpleName}")
        }
    }

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
        if (sessionConfig?.wifi != null) link.checkHealth()

        // M3: refresh the filter on every tick, not just on a link change.
        val usb = usbServer
        val wifi = wifiServer
        if (usb != null || wifi != null) {
            val filter = buildFilter()
            usb?.updateFilter(filter)
            wifi?.updateFilter(filter)
        }

        val up = link.state.value as? LinkState.Up
        if (up != null && (wifi == null || !wifi.isAccepting)) {
            appendLog("Watchdog: rebuilding the Wi-Fi listener")
            startWifiServer(up, clearBindings = wifiServerFor != up)
            // A listener that failed (or now works again) changes healthReason().
            refreshHealth()
        }

        if (usb != null && !usb.isAccepting) {
            appendLog("Watchdog: rebuilding the USB listener")
            usb.stop()
            usb.start()
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
