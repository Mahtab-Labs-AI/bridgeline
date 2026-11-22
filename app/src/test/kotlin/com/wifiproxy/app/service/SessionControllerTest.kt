package com.wifiproxy.app.service

import com.wifiproxy.app.egress.SessionEgress
import com.wifiproxy.app.logs.VERBOSE_AUTO_OFF_MESSAGE
import com.wifiproxy.app.logs.VERBOSE_PREFIX
import com.wifiproxy.app.session.ClientPlatform
import com.wifiproxy.app.session.SessionPhase
import com.wifiproxy.app.wifi.LinkConfig
import com.wifiproxy.app.wifi.LinkManager
import com.wifiproxy.app.wifi.LinkState
import com.wifiproxy.app.wifi.PersistedSlot
import com.wifiproxy.app.wifi.REASON_CREATING
import com.wifiproxy.app.wifi.REASON_LOST
import com.wifiproxy.app.wifi.SlotPersistence
import com.wifiproxy.core.ProxyConfig
import com.wifiproxy.core.ProxyEvent
import com.wifiproxy.core.io.TunnelEnd
import com.wifiproxy.core.ProxyServer
import com.wifiproxy.core.WifiCredentials
import com.wifiproxy.core.clients.SlotRecord
import com.wifiproxy.core.net.Cidr
import com.wifiproxy.core.net.DestinationFilter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A controllable [SessionEgress]: tests flip [setAvailable] to drive the Running<->Degraded path,
 *  and [bumpNetworkGeneration] to drive the I5 rebuild-on-network-change path. */
private class FakeSessionEgress(available: Boolean = true) : SessionEgress {
    private val _available = MutableStateFlow(available)
    override val available: StateFlow<Boolean> = _available.asStateFlow()
    override var onLinkChange: (() -> Unit)? = null

    private val _networkGeneration = MutableStateFlow(0L)
    override val networkGeneration: StateFlow<Long> = _networkGeneration.asStateFlow()

    override val isAvailable: Boolean get() = _available.value
    override val isValidated: Boolean get() = _available.value

    var startCalls = 0
        private set
    var stopCalls = 0
        private set
    var hostAddressesCalls = 0
        private set

    /** Set by a test to make the next (and every subsequent) [stop] throw, exercising I2's
     *  "doStop reaches IDLE even if a step throws" contract. */
    var stopThrows: Throwable? = null

    override fun start() {
        startCalls++
    }

    override fun stop() {
        stopCalls++
        stopThrows?.let { throw it }
    }

    override fun hostAddresses(): List<Cidr> {
        hostAddressesCalls++
        return emptyList()
    }

    override suspend fun resolve(host: String) = emptyList<InetAddress>()
    override suspend fun newChannel() = throw UnsupportedOperationException("not used in these tests")

    fun setAvailable(value: Boolean) {
        _available.value = value
    }

    fun bumpNetworkGeneration() {
        _networkGeneration.value += 1
    }
}

/** A controllable [SessionServer]: [isAccepting] can be forced false to drive the watchdog,
 *  [startThrows]/[stopThrows] drive the C1/I2 rollback paths, and [onStart] is a synchronous hook
 *  invoked from inside [start] - used to deterministically interleave a concurrent [stop] call
 *  exactly while a (virtual-time) start is "in flight" (C2). [onRevoke] does the same for [revoke]. */
private class FakeSessionServer(
    private val onStart: (() -> Unit)? = null,
    private val onRevoke: (() -> Unit)? = null,
) : SessionServer {
    override var isAccepting: Boolean = true
        private set

    var startCalls = 0
        private set
    var stopCalls = 0
        private set
    var startThrows: Throwable? = null
    var stopThrows: Throwable? = null
    val revokedIds = mutableListOf<String>()
    val filters = mutableListOf<DestinationFilter>()

    override fun start() {
        startCalls++
        onStart?.invoke()
        val err = startThrows
        if (err != null) {
            isAccepting = false
            throw err
        }
        isAccepting = true
    }

    override fun stop() {
        stopCalls++
        isAccepting = false
        stopThrows?.let { throw it }
    }

    override fun revoke(slotId: String): Boolean {
        revokedIds.add(slotId)
        onRevoke?.invoke()
        return true
    }

    override fun updateFilter(filter: DestinationFilter) {
        filters.add(filter)
    }

    fun killAcceptLoop() {
        isAccepting = false
    }
}

/** A controllable [LinkManager]: [start] moves to "creating" like the real link does, and tests
 *  drive [state] directly (or through [up]) to exercise every link transition. */
private class FakeLink : LinkManager {
    override val state = MutableStateFlow<LinkState>(LinkState.Idle)
    val starts = mutableListOf<LinkConfig>()
    var stops = 0
    var healthChecks = 0

    /** Opt-in: when set, [stop] really suspends until it completes, like the real link's stop()
     *  waiting for its group removal. Null (the default) keeps [stop] non-suspending. */
    var stopGate: CompletableDeferred<Unit>? = null

    override fun start(config: LinkConfig) {
        starts += config
        state.value = LinkState.Down(REASON_CREATING)
    }

    override suspend fun stop() {
        stops++
        stopGate?.await()
        state.value = LinkState.Idle
    }

    override fun checkHealth() {
        healthChecks++
    }

    fun up(owner: String = "192.168.49.1") {
        state.value = LinkState.Up(InetAddress.getByName(owner) as Inet4Address, WIFI.credentials)
    }
}

private class MemorySlots(var saved: List<PersistedSlot> = emptyList()) : SlotPersistence {
    var saves = 0
    override fun load() = saved
    override fun save(slots: List<PersistedSlot>) {
        saved = slots
        saves++
    }
}

private val WIFI = LinkConfig(WifiCredentials("DIRECT-k3-HostPhone", "correct horse battery"), prefer5Ghz = false)
private val USB_ONLY = SessionConfig(usbEnabled = true, wifi = null, wifiPort = 8080, maxClients = 3)
private val WIFI_ONLY = SessionConfig(usbEnabled = false, wifi = WIFI, wifiPort = 8080, maxClients = 3)
private val BOTH = SessionConfig(usbEnabled = true, wifi = WIFI, wifiPort = 8080, maxClients = 3)

// Code mode: saved devices are only restored into the registry when codes are required (D8).
private val CODED_WIFI_ONLY = WIFI_ONLY.copy(requireDeviceCodes = true)
private val CODED_BOTH = BOTH.copy(requireDeviceCodes = true)

@OptIn(ExperimentalCoroutinesApi::class)
class SessionControllerTest {

    private fun controller(
        egress: FakeSessionEgress = FakeSessionEgress(),
        server: FakeSessionServer = FakeSessionServer(),
        scope: CoroutineScope,
        watchdogIntervalMs: Long = 30_000L,
        now: () -> Long = { 0L },
        link: FakeLink = FakeLink(),
        slots: MemorySlots = MemorySlots(),
        config: SessionConfig = USB_ONLY,
        servers: MutableList<Pair<ProxyConfig, FakeSessionServer>>? = null,
        verbose: () -> Boolean = { false },
        /** Collects each server's `onEvent`, in creation order (USB first). */
        eventSinks: MutableList<(ProxyEvent) -> Unit>? = null,
    ) = SessionController(
        egress = egress,
        link = link,
        slots = slots,
        config = { config },
        serverFactory = SessionServerFactory { cfg, _, _, _, _, _, onEvent ->
            eventSinks?.add(onEvent)
            if (servers == null) server else FakeSessionServer().also { servers += cfg to it }
        },
        scope = scope,
        watchdogIntervalMs = watchdogIntervalMs,
        now = now,
        verbose = verbose,
    )

    private val peer = InetAddress.getByName("192.168.49.87")

    @Test
    fun `proxy events from both listeners are logged as verbose lines only while verbose is on`() = runTest {
        var verboseOn = false
        val sinks = mutableListOf<(ProxyEvent) -> Unit>()
        val link = FakeLink()
        val servers = mutableListOf<Pair<ProxyConfig, FakeSessionServer>>()
        val c = controller(
            scope = backgroundScope, link = link, config = BOTH, servers = servers,
            verbose = { verboseOn }, eventSinks = sinks,
        )
        c.start(); runCurrent(); link.up(); runCurrent()
        assertEquals(2, sinks.size)
        val closed = ProxyEvent.TunnelClosed(peer, 443, ProxyEvent.TunnelKind.CONNECT, 1229, 49_152, 3_100, TunnelEnd.BOTH_EOF)

        sinks[0](closed)
        assertTrue(c.log.value.none { it.message.startsWith(VERBOSE_PREFIX) })

        verboseOn = true
        sinks[0](closed)
        sinks[1](ProxyEvent.Refused(InetAddress.getByName("192.168.49.12"), ProxyEvent.RefusalReason.REGISTRY_FULL))
        val verboseLines = c.log.value.map { it.message }.filter { it.startsWith(VERBOSE_PREFIX) }
        assertTrue("[v] 192.168.49.87 CONNECT :443 closed (BOTH_EOF) ↑1.2 KB ↓48.0 KB 3.1 s" in verboseLines, verboseLines.toString())
        assertTrue("[v] 192.168.49.12 refused: registry full" in verboseLines, verboseLines.toString())

        // Turned off mid-session: the very next event is no longer logged.
        verboseOn = false
        val before = c.log.value.size
        sinks[1](closed)
        assertEquals(before, c.log.value.size)
    }

    @Test
    fun `link transitions are logged with their reasons while verbose is on`() = runTest {
        val link = FakeLink()
        val c = controller(scope = backgroundScope, link = link, config = WIFI_ONLY, verbose = { true })
        c.start(); runCurrent()
        link.up(); runCurrent()
        link.state.value = LinkState.Down(REASON_LOST); runCurrent()
        val lines = c.log.value.map { it.message }
        assertTrue("[v] Wi-Fi Direct link down: $REASON_CREATING" in lines, lines.toString())
        assertTrue("[v] Wi-Fi Direct link up (group owner 192.168.49.1)" in lines, lines.toString())
        assertTrue("[v] Wi-Fi Direct link down: $REASON_LOST" in lines, lines.toString())
        // Never the Wi-Fi name or password.
        assertTrue(lines.none { WIFI.credentials.ssid in it || WIFI.credentials.password in it }, lines.toString())
    }

    @Test
    fun `logVerbose is a no-op while verbose is off`() = runTest {
        val c = controller(scope = backgroundScope)
        c.logVerbose("detail")
        assertTrue(c.log.value.isEmpty())
    }

    @Test
    fun `the log keeps 3000 entries while verbose is on and trims to 500 with one line at auto-off`() = runTest {
        var verboseOn = true
        val c = controller(scope = backgroundScope, verbose = { verboseOn })
        repeat(3_200) { c.logVerbose("line $it") }
        assertEquals(3_000, c.log.value.size)
        assertEquals("[v] line 3199", c.log.value.last().message)

        verboseOn = false
        c.onVerboseEnded(autoOff = true)
        assertEquals(500, c.log.value.size)
        assertEquals(VERBOSE_AUTO_OFF_MESSAGE, c.log.value.last().message)
        assertEquals("[v] line 3199", c.log.value[498].message)
    }

    @Test
    fun `switching verbose off early trims without the auto-off line`() = runTest {
        var verboseOn = true
        val c = controller(scope = backgroundScope, verbose = { verboseOn })
        repeat(800) { c.logVerbose("line $it") }
        verboseOn = false
        c.onVerboseEnded(autoOff = false)
        assertEquals(500, c.log.value.size)
        assertEquals("[v] line 799", c.log.value.last().message)
    }

    @Test
    fun `plain lines keep the normal 500 cap while verbose is off`() = runTest {
        val c = controller(scope = backgroundScope)
        repeat(300) {
            c.start(); runCurrent()
            c.stop(); runCurrent()
        }
        assertEquals(500, c.log.value.size)
    }

    @Test
    fun `start reaches RUNNING when egress is available`() = runTest {
        val server = FakeSessionServer()
        val c = controller(server = server, scope = backgroundScope)

        c.start()
        runCurrent()

        assertEquals(SessionPhase.RUNNING, c.state.value)
        assertNull(c.degradedReason.value)
        assertEquals(1, server.startCalls)
        assertEquals(1, c.registry?.slots()?.size)
        assertTrue(c.registry!!.slots().single().pinned)
    }

    @Test
    fun `start reaches DEGRADED when egress is already unavailable`() = runTest {
        val egress = FakeSessionEgress(available = false)
        val c = controller(egress = egress, scope = backgroundScope)

        c.start()
        runCurrent()

        assertEquals(SessionPhase.DEGRADED, c.state.value)
        assertEquals("No mobile data", c.degradedReason.value)
    }

    @Test
    fun `egress loss degrades a running session, regain recovers it`() = runTest {
        val egress = FakeSessionEgress(available = true)
        val c = controller(egress = egress, scope = backgroundScope)
        c.start()
        runCurrent()
        assertEquals(SessionPhase.RUNNING, c.state.value)

        egress.setAvailable(false)
        runCurrent()
        assertEquals(SessionPhase.DEGRADED, c.state.value)
        assertEquals("No mobile data", c.degradedReason.value)

        egress.setAvailable(true)
        runCurrent()
        assertEquals(SessionPhase.RUNNING, c.state.value)
        assertNull(c.degradedReason.value)
    }

    @Test
    fun `stop tears the session down and leaves it restartable`() = runTest {
        val egress = FakeSessionEgress()
        val server = FakeSessionServer()
        val c = controller(egress = egress, server = server, scope = backgroundScope)
        c.start()
        runCurrent()

        c.stop()
        runCurrent()

        assertEquals(SessionPhase.IDLE, c.state.value)
        assertEquals(1, server.stopCalls)
        assertEquals(1, egress.stopCalls)
        assertNull(c.registry)

        c.start()
        runCurrent()
        assertEquals(SessionPhase.RUNNING, c.state.value)
        assertEquals(2, server.startCalls)
    }

    @Test
    fun `watchdog rebuilds the server when it stops accepting`() = runTest {
        val server = FakeSessionServer()
        val c = controller(server = server, scope = backgroundScope, watchdogIntervalMs = 30_000L, now = { currentTime })
        c.start()
        runCurrent()
        assertEquals(1, server.startCalls)

        server.killAcceptLoop()
        advanceTimeBy(30_000L)
        runCurrent()

        assertEquals(1, server.stopCalls)
        assertEquals(2, server.startCalls)
        assertTrue(server.isAccepting)
        assertEquals(SessionPhase.RUNNING, c.state.value)
    }

    @Test
    fun `watchdog leaves a healthy server alone`() = runTest {
        val server = FakeSessionServer()
        val c = controller(server = server, scope = backgroundScope)
        c.start()
        runCurrent()

        advanceTimeBy(30_000L)
        runCurrent()

        assertEquals(1, server.startCalls)
        assertEquals(0, server.stopCalls)
    }

    @Test
    fun `watchdog does nothing once stopped back to idle`() = runTest {
        val server = FakeSessionServer()
        val c = controller(server = server, scope = backgroundScope)
        c.start()
        runCurrent()
        c.stop()
        runCurrent()
        server.killAcceptLoop() // a dead run's server would never get rebuilt once idle

        advanceTimeBy(60_000L)
        runCurrent()

        assertEquals(SessionPhase.IDLE, c.state.value)
        assertEquals(1, server.startCalls)
        assertEquals(1, server.stopCalls)
    }

    @Test
    fun `watchdog rebuild failure is caught and degrades rather than crashing`() = runTest {
        val server = FakeSessionServer()
        val c = controller(server = server, scope = backgroundScope, watchdogIntervalMs = 30_000L)
        c.start()
        runCurrent()

        server.killAcceptLoop()
        server.startThrows = IllegalStateException("rebuild failed")
        advanceTimeBy(30_000L)
        runCurrent()

        assertTrue(c.state.value == SessionPhase.DEGRADED || c.state.value == SessionPhase.IDLE)
    }

    @Test
    fun `watchdog refreshes the filter on every tick`() = runTest {
        val server = FakeSessionServer()
        val c = controller(server = server, scope = backgroundScope, watchdogIntervalMs = 30_000L)
        c.start()
        runCurrent()
        val before = server.filters.size

        advanceTimeBy(30_000L)
        runCurrent()

        assertTrue(server.filters.size > before)
    }

    @Test
    fun `link change refreshes the destination filter`() = runTest {
        val egress = FakeSessionEgress()
        val server = FakeSessionServer()
        val c = controller(egress = egress, server = server, scope = backgroundScope)
        c.start()
        runCurrent()
        val before = server.filters.size

        egress.onLinkChange?.invoke()
        runCurrent()

        assertEquals(before + 1, server.filters.size)
    }

    @Test
    fun `network generation change rebuilds the server once, not on the first value`() = runTest {
        val egress = FakeSessionEgress()
        val server = FakeSessionServer()
        val c = controller(egress = egress, server = server, scope = backgroundScope)
        c.start()
        runCurrent()

        // Subscribing to networkGeneration replays its current value (0); that must not itself
        // count as a change.
        assertEquals(1, server.startCalls)
        assertEquals(0, server.stopCalls)

        egress.bumpNetworkGeneration()
        runCurrent()

        assertEquals(2, server.startCalls)
        assertEquals(1, server.stopCalls)
        assertEquals(SessionPhase.RUNNING, c.state.value)
    }

    @Test
    fun `network generation change is ignored while idle`() = runTest {
        val egress = FakeSessionEgress()
        val server = FakeSessionServer()
        val c = controller(egress = egress, server = server, scope = backgroundScope)
        c.start()
        runCurrent()
        c.stop()
        runCurrent()

        egress.bumpNetworkGeneration()
        runCurrent()

        assertEquals(1, server.startCalls)
        assertEquals(1, server.stopCalls)
    }

    @Test
    fun `revoke forwards to the server, not the registry`() = runTest {
        val server = FakeSessionServer()
        val c = controller(server = server, scope = backgroundScope)
        c.start()
        runCurrent()

        val revoked = c.revoke("some-slot-id")

        assertTrue(revoked)
        assertEquals(listOf("some-slot-id"), server.revokedIds)
    }

    @Test
    fun `revoke before start returns false`() = runTest {
        val c = controller(scope = backgroundScope)
        assertFalse(c.revoke("whatever"))
    }

    @Test
    fun `log never exceeds 500 entries`() = runTest {
        val egress = FakeSessionEgress()
        val c = controller(egress = egress, scope = backgroundScope, now = { currentTime })
        c.start()
        runCurrent()
        repeat(600) { egress.setAvailable(it % 2 == 0) }
        runCurrent()

        assertTrue(c.log.value.size <= 500)
    }

    @Test
    fun `second start is ignored while already running`() = runTest {
        val server = FakeSessionServer()
        val c = controller(server = server, scope = backgroundScope)
        c.start()
        runCurrent()

        c.start()
        runCurrent()

        assertEquals(1, server.startCalls)
    }

    // --- C1: a failed start rolls back and leaves the session retryable -------------------------

    @Test
    fun `server start failure rolls back the egress and leaves IDLE with an error`() = runTest {
        val egress = FakeSessionEgress()
        val server = FakeSessionServer()
        server.startThrows = IllegalStateException("bind failed")
        val c = controller(egress = egress, server = server, scope = backgroundScope)

        c.start()
        runCurrent()

        assertEquals(SessionPhase.IDLE, c.state.value)
        assertEquals(1, egress.stopCalls)
        assertEquals("bind failed", c.lastError.value)
        assertNull(c.registry)
    }

    @Test
    fun `a later start works after a start failure`() = runTest {
        val egress = FakeSessionEgress()
        val server = FakeSessionServer()
        server.startThrows = IllegalStateException("bind failed")
        val c = controller(egress = egress, server = server, scope = backgroundScope)
        c.start()
        runCurrent()
        assertEquals(SessionPhase.IDLE, c.state.value)

        server.startThrows = null
        c.start()
        runCurrent()

        assertEquals(SessionPhase.RUNNING, c.state.value)
        assertNull(c.lastError.value)
    }

    @Test
    fun `egress start failure rolls back cleanly`() = runTest {
        val egress = object : SessionEgress {
            override val available: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()
            override var onLinkChange: (() -> Unit)? = null
            override val networkGeneration: StateFlow<Long> = MutableStateFlow(0L).asStateFlow()
            override val isAvailable: Boolean = true
            override val isValidated: Boolean = true
            var stopCalls = 0
            override fun start() = throw IllegalStateException("egress start failed")
            override fun stop() {
                stopCalls++
            }

            override fun hostAddresses() = emptyList<Cidr>()
            override suspend fun resolve(host: String) = emptyList<InetAddress>()
            override suspend fun newChannel() = throw UnsupportedOperationException()
        }
        val c = SessionController(
            egress = egress,
            link = FakeLink(),
            slots = MemorySlots(),
            config = { USB_ONLY },
            serverFactory = SessionServerFactory { _, _, _, _, _, _, _ -> FakeSessionServer() },
            scope = backgroundScope,
        )

        c.start()
        runCurrent()

        assertEquals(SessionPhase.IDLE, c.state.value)
        assertEquals(1, egress.stopCalls)
        assertEquals("egress start failed", c.lastError.value)
    }

    // --- C2: stop() racing a concurrent start() ---------------------------------------------------

    @Test
    fun `stop arriving mid-start rolls back and leaves no server running (deterministic)`() = runTest {
        lateinit var c: SessionController
        val egress = FakeSessionEgress()
        val server = FakeSessionServer(onStart = { c.stop() })
        c = controller(egress = egress, server = server, scope = backgroundScope)

        c.start()
        runCurrent()

        assertEquals(SessionPhase.IDLE, c.state.value)
        assertEquals(1, server.startCalls)
        assertEquals(1, server.stopCalls)
        assertEquals(1, egress.stopCalls)
    }

    // (R2) Hits the exact race the bug report describes: a stop() landing after doStart's last
    // `checkNotInterruptedByStop()` guard has already passed, but before the final phase write that
    // used to be a plain `_state.value = RUNNING/DEGRADED` (clobbering a concurrent STOPPING and
    // losing the stop). Here `egress.available.value` - read by doStart only once, immediately
    // before that write - is the hook: reading it calls stop() synchronously, landing exactly in
    // that gap. Asserts the fix (a compareAndSet there) correctly throws StartInterruptedByStop,
    // rolls back, and reaches IDLE with both the server and the egress stopped, instead of
    // finishing the start as RUNNING with the stop silently lost.
    // Implementing StateFlow directly (to hook its `value` getter) requires this opt-in: kotlinx
    // coroutines reserves the right to add members to Flow/StateFlow/SharedFlow in the future,
    // which would be a breaking change for anyone implementing them outside the library. Accepted
    // here deliberately, for a test-only fake.
    @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
    @Test
    fun `stop landing right before the final RUNNING-DEGRADED write is not clobbered`() = runTest {
        lateinit var c: SessionController
        val server = FakeSessionServer()
        val egress = object : SessionEgress {
            private val backing = MutableStateFlow(true)
            override val available: StateFlow<Boolean> = object : StateFlow<Boolean> by backing.asStateFlow() {
                override val value: Boolean
                    get() {
                        c.stop()
                        return backing.value
                    }
            }
            override var onLinkChange: (() -> Unit)? = null
            override val networkGeneration: StateFlow<Long> = MutableStateFlow(0L).asStateFlow()
            override val isAvailable: Boolean get() = backing.value
            override val isValidated: Boolean get() = backing.value
            var stopCalls = 0
                private set

            override fun start() = Unit
            override fun stop() {
                stopCalls++
            }

            override fun hostAddresses() = emptyList<Cidr>()
            override suspend fun resolve(host: String) = emptyList<InetAddress>()
            override suspend fun newChannel() = throw UnsupportedOperationException("not used in this test")
        }
        c = SessionController(
            egress = egress,
            link = FakeLink(),
            slots = MemorySlots(),
            config = { USB_ONLY },
            serverFactory = SessionServerFactory { _, _, _, _, _, _, _ -> server },
            scope = backgroundScope,
        )

        c.start()
        runCurrent()

        assertEquals(SessionPhase.IDLE, c.state.value)
        assertEquals(1, server.startCalls)
        assertEquals(1, server.stopCalls)
        assertEquals(1, egress.stopCalls)
    }

    // A stop() landing mid-start is honoured by doStart's rollback, but its doStop is still queued.
    // If a new start() (e.g. the service's pending-start restart from onIdle) gets in before that
    // queued doStop runs, the stale doStop must leave the new session alone.
    @Test
    fun `a stale doStop does not tear down a session restarted behind it`() = runTest {
        lateinit var c: SessionController
        var firstStart = true
        val egress = FakeSessionEgress()
        val server = FakeSessionServer(onStart = {
            if (firstStart) {
                firstStart = false
                c.stop()
            }
        })
        c = controller(egress = egress, server = server, scope = backgroundScope)
        var restarted = false
        c.onIdle = {
            if (!restarted) {
                restarted = true
                c.start()
            }
        }

        c.start()
        runCurrent()

        assertEquals(SessionPhase.RUNNING, c.state.value)
        assertEquals(2, server.startCalls)
        assertEquals(1, server.stopCalls)
        assertEquals(1, egress.stopCalls)
    }

    @Test
    fun `stop during a real concurrent start leaves no server running`() {
        val startedLatch = CountDownLatch(1)
        val releaseLatch = CountDownLatch(1)
        val server = FakeSessionServer(
            onStart = {
                startedLatch.countDown()
                releaseLatch.await(2, TimeUnit.SECONDS)
            },
        )
        val realScope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
        try {
            val egress = FakeSessionEgress()
            val c = SessionController(
                egress = egress,
                link = FakeLink(),
                slots = MemorySlots(),
                config = { USB_ONLY },
                serverFactory = SessionServerFactory { _, _, _, _, _, _, _ -> server },
                scope = realScope,
            )

            c.start()
            assertTrue(startedLatch.await(2, TimeUnit.SECONDS), "server.start() should have begun")
            c.stop()
            releaseLatch.countDown()

            val deadline = System.currentTimeMillis() + 2_000
            while (c.state.value != SessionPhase.IDLE && System.currentTimeMillis() < deadline) {
                Thread.sleep(10)
            }

            assertEquals(SessionPhase.IDLE, c.state.value)
            assertEquals(1, server.startCalls)
            assertEquals(1, server.stopCalls)
        } finally {
            realScope.cancel()
        }
    }

    // --- I2: doStop reaches IDLE even if a step throws --------------------------------------------

    @Test
    fun `egress stop failure during doStop still reaches IDLE`() = runTest {
        val egress = FakeSessionEgress()
        val server = FakeSessionServer()
        val c = controller(egress = egress, server = server, scope = backgroundScope)
        c.start()
        runCurrent()

        egress.stopThrows = IllegalStateException("unregister failed")
        c.stop()
        runCurrent()

        assertEquals(SessionPhase.IDLE, c.state.value)
        assertEquals(1, server.stopCalls)
        assertEquals(1, egress.stopCalls)
    }

    @Test
    fun `server stop failure during doStop still reaches IDLE`() = runTest {
        val egress = FakeSessionEgress()
        val server = FakeSessionServer()
        val c = controller(egress = egress, server = server, scope = backgroundScope)
        c.start()
        runCurrent()

        server.stopThrows = IllegalStateException("server stop failed")
        c.stop()
        runCurrent()

        assertEquals(SessionPhase.IDLE, c.state.value)
        assertEquals(1, egress.stopCalls)
    }

    // --- C1: onIdle fires on every non-IDLE -> IDLE transition, set up before start() ever races -

    @Test
    fun `onIdle fires on a deliberate stop`() = runTest {
        val c = controller(scope = backgroundScope)
        var idleCalls = 0
        c.onIdle = { idleCalls++ }

        c.start()
        runCurrent()
        assertEquals(0, idleCalls)

        c.stop()
        runCurrent()
        assertEquals(1, idleCalls)
    }

    @Test
    fun `onIdle fires once when a failed start rolls back, even though it was set before start()`() = runTest {
        val server = FakeSessionServer()
        server.startThrows = IllegalStateException("bind failed")
        val c = controller(server = server, scope = backgroundScope)
        var idleCalls = 0
        // Set immediately after construction, exactly as ProxySessionService.buildController does
        // - before start() is ever called, so there is no collector-subscription race to miss.
        c.onIdle = { idleCalls++ }

        c.start()
        runCurrent()

        assertEquals(SessionPhase.IDLE, c.state.value)
        assertEquals(1, idleCalls)
    }

    @Test
    fun `onIdle does not fire merely from being set on an already-idle controller`() = runTest {
        val c = controller(scope = backgroundScope)
        var idleCalls = 0
        c.onIdle = { idleCalls++ }

        runCurrent()

        assertEquals(0, idleCalls)
    }

    // --- Wi-Fi Direct listener (Plan 2b Task 6) ---------------------------------------------------

    @Test
    fun `wifi session is degraded until the group is up, then serves on the owner address`() = runTest {
        val link = FakeLink()
        val servers = mutableListOf<Pair<ProxyConfig, FakeSessionServer>>()
        val c = controller(scope = backgroundScope, link = link, config = WIFI_ONLY, servers = servers)
        c.start(); runCurrent()
        assertEquals(SessionPhase.DEGRADED, c.state.value)
        assertEquals(REASON_CREATING, c.degradedReason.value)
        assertEquals(listOf(WIFI), link.starts)
        assertTrue(servers.isEmpty())

        link.up(); runCurrent()
        assertEquals(SessionPhase.RUNNING, c.state.value)
        val (cfg, server) = servers.single()
        assertEquals(InetAddress.getByName("192.168.49.1"), cfg.bindAddress)
        assertEquals(8080, cfg.port)
        assertEquals(WIFI.credentials, cfg.wifi)
        assertTrue(cfg.peerSubnet.contains(InetAddress.getByName("192.168.49.77")))
        assertEquals(1, server.startCalls)
    }

    @Test
    fun `group loss stops the wifi listener, clears bindings and degrades with the link reason`() = runTest {
        val link = FakeLink()
        val servers = mutableListOf<Pair<ProxyConfig, FakeSessionServer>>()
        val c = controller(scope = backgroundScope, link = link, config = WIFI_ONLY, servers = servers)
        c.start(); runCurrent(); link.up(); runCurrent()
        val slot = c.addDevice("Pixel 7", ClientPlatform.ANDROID)!!
        c.registry!!.onPacFetch(slot.token, InetAddress.getByName("192.168.49.5"))

        link.state.value = LinkState.Down(REASON_LOST); runCurrent()
        assertEquals(SessionPhase.DEGRADED, c.state.value)
        assertEquals(REASON_LOST, c.degradedReason.value)
        assertEquals(1, servers.single().second.stopCalls)
        assertNull(c.registry!!.slots().single { it.id == slot.id }.boundIp)

        link.up(); runCurrent()
        assertEquals(SessionPhase.RUNNING, c.state.value)
        assertEquals(2, servers.size)   // a fresh server for the recreated group
    }

    @Test
    fun `a new owner address rebuilds the listener`() = runTest {
        val link = FakeLink()
        val servers = mutableListOf<Pair<ProxyConfig, FakeSessionServer>>()
        val c = controller(scope = backgroundScope, link = link, config = WIFI_ONLY, servers = servers)
        c.start(); runCurrent(); link.up("192.168.49.1"); runCurrent()
        link.up("192.168.50.1"); runCurrent()
        assertEquals(2, servers.size)
        assertEquals(1, servers[0].second.stopCalls)
        assertEquals(InetAddress.getByName("192.168.50.1"), servers[1].first.bindAddress)
    }

    @Test
    fun `link failure degrades with its message and keeps usb serving`() = runTest {
        val link = FakeLink()
        val servers = mutableListOf<Pair<ProxyConfig, FakeSessionServer>>()
        val c = controller(scope = backgroundScope, link = link, config = BOTH, servers = servers)
        c.start(); runCurrent()
        link.state.value = LinkState.Failed("This phone doesn't support Wi-Fi Direct."); runCurrent()
        assertEquals(SessionPhase.DEGRADED, c.state.value)
        assertEquals("This phone doesn't support Wi-Fi Direct.", c.degradedReason.value)
        val usb = servers.single { it.first.bindAddress.isLoopbackAddress }.second
        assertEquals(0, usb.stopCalls)
    }

    @Test
    fun `no mobile data outranks the link reason`() = runTest {
        val egress = FakeSessionEgress(available = false)
        val c = controller(egress = egress, scope = backgroundScope, config = WIFI_ONLY)
        c.start(); runCurrent()
        assertEquals("No mobile data", c.degradedReason.value)
    }

    @Test
    fun `neither usb nor wifi refuses to start`() = runTest {
        val c = controller(scope = backgroundScope, config = SessionConfig(false, null, 8080, 3))
        c.start(); runCurrent()
        assertEquals(SessionPhase.IDLE, c.state.value)
        assertEquals("Set a Wi-Fi password in Settings or turn on USB sharing", c.lastError.value)
    }

    @Test
    fun `usb disabled means no pinned slot and no loopback listener`() = runTest {
        val servers = mutableListOf<Pair<ProxyConfig, FakeSessionServer>>()
        val c = controller(scope = backgroundScope, config = WIFI_ONLY, servers = servers)
        c.start(); runCurrent()
        assertTrue(c.registry!!.slots().none { it.pinned })
        assertTrue(servers.none { it.first.bindAddress.isLoopbackAddress })
    }

    @Test
    fun `devices persist with their platform and are restored on the next start`() = runTest {
        val link = FakeLink()
        val slots = MemorySlots()
        val c = controller(scope = backgroundScope, link = link, slots = slots, config = CODED_WIFI_ONLY)
        c.start(); runCurrent(); link.up(); runCurrent()
        val added = c.addDevice("Ben's iPhone", ClientPlatform.IPHONE)!!
        assertEquals(listOf(PersistedSlot(c.registry!!.snapshot().single(), ClientPlatform.IPHONE)), slots.saved)

        c.stop(); runCurrent(); c.start(); runCurrent()
        val restored = c.registry!!.slots().single()
        assertEquals(added.id, restored.id)
        assertEquals(added.token, restored.token)
        assertEquals(ClientPlatform.IPHONE, c.platformOf(added.id))
    }

    @Test
    fun `addDevice needs the group up and room`() = runTest {
        val link = FakeLink()
        val c = controller(scope = backgroundScope, link = link, config = BOTH.copy(maxClients = 2))
        c.start(); runCurrent()
        assertNull(c.addDevice("Too early", ClientPlatform.ANDROID))
        link.up(); runCurrent()
        assertNotNull(c.addDevice("Pixel", ClientPlatform.ANDROID))      // USB pin + 1 = 2
        assertNull(c.addDevice("No room", ClientPlatform.ANDROID))
    }

    @Test
    fun `revoke goes through a server and is persisted, even while the group is down`() = runTest {
        val link = FakeLink()
        val slots = MemorySlots()
        val servers = mutableListOf<Pair<ProxyConfig, FakeSessionServer>>()
        val c = controller(scope = backgroundScope, link = link, slots = slots, config = WIFI_ONLY, servers = servers)
        c.start(); runCurrent(); link.up(); runCurrent()
        val slot = c.addDevice("Pixel", ClientPlatform.ANDROID)!!
        link.state.value = LinkState.Down(REASON_LOST); runCurrent()
        val savesBefore = slots.saves

        assertTrue(c.revoke(slot.id))
        // The stopped Wi-Fi server is reused for the revoke. The fake can't remove the slot from the
        // registry (ClientRegistry.revoke is internal to :proxy-core), so assert the routing and that
        // persistence ran; the real ProxyServer.revoke removes it, and Task 9 checks that on a device.
        assertEquals(listOf(slot.id), servers.single().second.revokedIds)
        assertEquals(savesBefore + 1, slots.saves)
    }

    @Test
    fun `stop stops the link and every server`() = runTest {
        val link = FakeLink()
        val servers = mutableListOf<Pair<ProxyConfig, FakeSessionServer>>()
        val c = controller(scope = backgroundScope, link = link, config = BOTH, servers = servers)
        c.start(); runCurrent(); link.up(); runCurrent()
        c.stop(); runCurrent()
        assertEquals(SessionPhase.IDLE, c.state.value)
        assertEquals(1, link.stops)
        assertTrue(servers.all { it.second.stopCalls == 1 })
    }

    @Test
    fun `watchdog checks the link and rebuilds a dead wifi listener`() = runTest {
        val link = FakeLink()
        val servers = mutableListOf<Pair<ProxyConfig, FakeSessionServer>>()
        val c = controller(scope = backgroundScope, link = link, config = WIFI_ONLY, servers = servers)
        c.start(); runCurrent(); link.up(); runCurrent()
        val slot = c.addDevice("Pixel", ClientPlatform.ANDROID)!!
        val phoneIp = InetAddress.getByName("192.168.49.5")
        c.registry!!.onPacFetch(slot.token, phoneIp)
        servers.single().second.killAcceptLoop()
        advanceTimeBy(30_000); runCurrent()
        assertEquals(1, link.healthChecks)
        assertEquals(2, servers.size)
        assertEquals(1, servers[0].second.stopCalls)
        assertEquals(SessionPhase.RUNNING, c.state.value)
        // Same group, so the device keeps its binding across the listener rebuild.
        assertEquals(phoneIp, c.registry!!.slots().single { it.id == slot.id }.boundIp)
    }

    // --- Fix round 1 ------------------------------------------------------------------------------

    @Test
    fun `with usb on the laptop keeps its seat and saved devices without a seat are never dropped`() = runTest {
        val link = FakeLink()
        val stored = (1..3).map {
            PersistedSlot(SlotRecord("id-$it", "Phone $it", "token-$it".padEnd(22, 'x'), it.toLong()), ClientPlatform.ANDROID)
        }
        val slots = MemorySlots(stored)
        val servers = mutableListOf<Pair<ProxyConfig, FakeSessionServer>>()
        var config = CODED_BOTH
        val c = SessionController(
            egress = FakeSessionEgress(),
            link = link,
            slots = slots,
            config = { config },
            serverFactory = SessionServerFactory { cfg, _, _, _, _, _, _ -> FakeSessionServer().also { servers += cfg to it } },
            scope = backgroundScope,
            now = { 0L },
        )
        c.start(); runCurrent(); link.up(); runCurrent()

        assertEquals(1, c.registry!!.slots().count { it.pinned })
        val usb = servers.single { it.first.bindAddress.isLoopbackAddress }.second
        assertEquals(1, usb.startCalls)
        assertTrue(usb.isAccepting)
        assertEquals(listOf("id-1", "id-2"), c.registry!!.slots().filterNot { it.pinned }.map { it.id })
        assertTrue(c.log.value.any { it.message == "1 saved device(s) inactive: no free slot" })

        assertNull(c.addDevice("No room", ClientPlatform.ANDROID))
        assertTrue(c.revoke("id-2"))
        assertTrue(slots.saved.any { it.record.id == "id-3" })

        // FakeSessionServer.revoke can't remove id-2 from the registry (ClientRegistry.revoke is
        // internal), so all three are still saved; the point is that id-3 was never dropped.
        c.stop(); runCurrent()
        config = CODED_WIFI_ONLY
        c.start(); runCurrent()
        assertEquals(listOf("id-1", "id-2", "id-3"), c.registry!!.slots().map { it.id })
    }

    // A stop() landing mid-start makes the rollback suspend in link.stop() (gated here, as the real
    // link's stop() suspends). The queued doStop must not run teardown meanwhile: the lifecycle
    // Mutex holds it until the rollback is done, by which point onIdle has restarted the session.
    @Test
    fun `a queued doStop waits for a rollback suspended in link stop`() = runTest {
        lateinit var c: SessionController
        var firstStart = true
        val gate = CompletableDeferred<Unit>()
        val link = FakeLink().apply { stopGate = gate }
        val egress = FakeSessionEgress()
        val server = FakeSessionServer(onStart = {
            if (firstStart) {
                firstStart = false
                c.stop()
            }
        })
        c = controller(egress = egress, server = server, scope = backgroundScope, link = link)
        var idleCalls = 0
        c.onIdle = {
            idleCalls++
            if (idleCalls == 1) c.start()
        }

        c.start(); runCurrent()
        // The rollback is suspended in link.stop(); the queued doStop has not torn anything down.
        assertEquals(SessionPhase.STOPPING, c.state.value)
        assertEquals(1, link.stops)
        assertEquals(0, egress.stopCalls)
        assertEquals(0, idleCalls)

        gate.complete(Unit); runCurrent()
        assertEquals(1, idleCalls)
        assertEquals(SessionPhase.RUNNING, c.state.value)
        assertEquals(1, link.stops)
        assertEquals(1, egress.stopCalls)
        assertEquals(2, server.startCalls)
        assertEquals(1, server.stopCalls)
    }
    // --- Task 7: revoking an inactive saved device; revoke racing teardown -------------------------

    private fun storedSlots(n: Int) = (1..n).map {
        PersistedSlot(SlotRecord("id-$it", "Phone $it", "token-$it".padEnd(22, 'x'), it.toLong()), ClientPlatform.ANDROID)
    }

    @Test
    fun `revoking an inactive saved device removes it from storage without going through a server`() = runTest {
        val link = FakeLink()
        val slots = MemorySlots(storedSlots(4))
        val servers = mutableListOf<Pair<ProxyConfig, FakeSessionServer>>()
        val c = controller(scope = backgroundScope, link = link, slots = slots, config = CODED_WIFI_ONLY, servers = servers)
        c.start(); runCurrent(); link.up(); runCurrent()
        assertEquals(listOf("id-1", "id-2", "id-3"), c.registry!!.slots().map { it.id })

        assertTrue(c.revoke("id-4"))
        assertEquals(listOf("id-1", "id-2", "id-3"), slots.saved.map { it.record.id })
        assertTrue(servers.all { it.second.revokedIds.isEmpty() })

        // It stays gone across a restart: three saved devices, three seats, none inactive.
        c.stop(); runCurrent(); c.start(); runCurrent()
        assertEquals(listOf("id-1", "id-2", "id-3"), c.registry!!.slots().map { it.id })
    }

    // The revoke runs on the caller's thread while a stop's teardown runs on the scope; teardown
    // clears the inactive list. The persist that follows the server revoke must still write the
    // inactive devices captured at the top of revoke(), never drop them.
    @Test
    fun `a revoke racing teardown never drops the inactive saved devices`() = runTest {
        val link = FakeLink()
        val slots = MemorySlots(storedSlots(4))
        lateinit var c: SessionController
        var stopDuringRevoke = false
        c = SessionController(
            egress = FakeSessionEgress(),
            link = link,
            slots = slots,
            config = { CODED_WIFI_ONLY },
            serverFactory = SessionServerFactory { _, _, _, _, _, _, _ ->
                FakeSessionServer(onRevoke = {
                    if (stopDuringRevoke) {
                        c.stop()
                        testScheduler.runCurrent()
                    }
                })
            },
            scope = backgroundScope,
            now = { 0L },
        )
        c.start(); runCurrent(); link.up(); runCurrent()

        stopDuringRevoke = true
        assertTrue(c.revoke("id-1"))
        assertEquals(SessionPhase.IDLE, c.state.value)
        assertTrue(slots.saved.any { it.record.id == "id-4" })
    }

    // --- Final fix round: D7 holes, a dead Wi-Fi listener -----------------------------------------

    /** Revokes for real, through an unstarted real ProxyServer (ClientRegistry.revoke is internal
     *  to :proxy-core); start/stop only flip [isAccepting], nothing binds. */
    private class RegistryRevokingServer(private val delegate: ProxyServer) : SessionServer {
        override var isAccepting = false
            private set
        override fun start() { isAccepting = true }
        override fun stop() { isAccepting = false }
        override fun revoke(slotId: String) = delegate.revoke(slotId)
        override fun updateFilter(filter: DestinationFilter) = Unit
    }

    private fun TestScope.revokingController(link: FakeLink, slots: MemorySlots, config: SessionConfig, created: MutableList<ProxyConfig>) =
        SessionController(
            egress = FakeSessionEgress(),
            link = link,
            slots = slots,
            config = { config },
            serverFactory = SessionServerFactory { cfg, registry, egress, filter, _, _, _ ->
                created += cfg
                RegistryRevokingServer(ProxyServer(cfg, registry, egress, filter))
            },
            scope = backgroundScope,
            now = { 0L },
        )

    @Test
    fun `the pinned USB laptop slot can't be revoked`() = runTest {
        val link = FakeLink()
        val servers = mutableListOf<Pair<ProxyConfig, FakeSessionServer>>()
        val c = controller(scope = backgroundScope, link = link, config = BOTH, servers = servers)
        c.start(); runCurrent(); link.up(); runCurrent()
        val pinned = c.registry!!.slots().single { it.pinned }

        assertFalse(c.revoke(pinned.id))
        assertTrue(c.registry!!.slots().any { it.id == pinned.id && it.pinned })
        assertTrue(servers.all { it.second.revokedIds.isEmpty() })
    }

    @Test
    fun `addDevice counts inactive saved devices, so a revoke can't hand their seat to a new one`() = runTest {
        val link = FakeLink()
        val slots = MemorySlots(storedSlots(3))
        val c = revokingController(link, slots, CODED_BOTH, mutableListOf())
        c.start(); runCurrent(); link.up(); runCurrent()
        // Laptop + id-1 + id-2 hold the three seats; id-3 is saved but inactive.
        assertTrue(c.revoke("id-2"))
        assertEquals(listOf("id-1"), c.registry!!.slots().filterNot { it.pinned }.map { it.id })

        // The registry has a free seat again, but id-3 is still waiting for it.
        assertNull(c.addDevice("New phone", ClientPlatform.ANDROID))
        assertEquals(listOf("id-1", "id-3"), slots.saved.map { it.record.id })
    }

    @Test
    fun `revoking a Wi-Fi slot with no server ever started still works`() = runTest {
        val link = FakeLink() // never goes Up: no Wi-Fi server, and USB is off
        val slots = MemorySlots(storedSlots(1))
        val created = mutableListOf<ProxyConfig>()
        val c = revokingController(link, slots, CODED_WIFI_ONLY, created)
        c.start(); runCurrent()
        assertTrue(created.isEmpty())

        assertTrue(c.revoke("id-1"))
        assertEquals(1, created.size) // the never-started fallback server
        assertTrue(c.registry!!.slots().isEmpty())
        assertTrue(slots.saved.isEmpty())
    }

    // --- D8: open admission --------------------------------------------------------------------------

    @Test
    fun `the wifi listener admits openly by default and the usb listener never does`() = runTest {
        val link = FakeLink()
        val servers = mutableListOf<Pair<ProxyConfig, FakeSessionServer>>()
        val c = controller(scope = backgroundScope, link = link, config = BOTH, servers = servers)
        c.start(); runCurrent(); link.up(); runCurrent()
        val (wifi, _) = servers.single { !it.first.bindAddress.isLoopbackAddress }
        val (usb, _) = servers.single { it.first.bindAddress.isLoopbackAddress }
        assertTrue(wifi.openAdmission)
        assertFalse(usb.openAdmission)
        assertEquals(true, c.openAdmission)
    }

    @Test
    fun `requiring device codes closes admission on the wifi listener`() = runTest {
        val link = FakeLink()
        val servers = mutableListOf<Pair<ProxyConfig, FakeSessionServer>>()
        val config = BOTH.copy(requireDeviceCodes = true)
        val c = controller(scope = backgroundScope, link = link, config = config, servers = servers)
        c.start(); runCurrent(); link.up(); runCurrent()
        assertTrue(servers.none { it.first.openAdmission })
        assertEquals(false, c.openAdmission)
        c.stop(); runCurrent()
        assertNull(c.openAdmission)
    }

    @Test
    fun `D7 - auto slots never take the pinned USB laptop's seat`() = runTest {
        val link = FakeLink()
        val c = controller(scope = backgroundScope, link = link, config = BOTH.copy(maxClients = 2), servers = mutableListOf())
        c.start(); runCurrent(); link.up(); runCurrent()
        val registry = c.registry!!
        val pinned = registry.slots().single { it.pinned }

        assertNotNull(registry.admitOrCreate(InetAddress.getByName("192.168.49.20")))
        assertNull(registry.admitOrCreate(InetAddress.getByName("192.168.49.21")))
        assertEquals(listOf(pinned.id), registry.slots().filter { it.pinned }.map { it.id })
        assertTrue(registry.snapshot().isEmpty())

        // A group teardown removes the auto slot; the laptop keeps its seat throughout.
        link.state.value = LinkState.Down(REASON_LOST); runCurrent()
        assertEquals(listOf(pinned.id), registry.slots().map { it.id })
        link.up(); runCurrent()
        assertNotNull(registry.admitOrCreate(InetAddress.getByName("192.168.49.21")))
        assertEquals(pinned.id, registry.admittedSlot(InetAddress.getByName("127.0.0.1"))?.id)
    }

    // Fix round 1: the user's real state - limit 3, USB on, two saved coded devices.
    @Test
    fun `open mode restores no saved device, so both Wi-Fi seats are free, and code mode brings them back`() = runTest {
        val link = FakeLink()
        val stored = storedSlots(2)
        val slots = MemorySlots(stored)
        var config = BOTH
        val c = SessionController(
            egress = FakeSessionEgress(),
            link = link,
            slots = slots,
            config = { config },
            serverFactory = SessionServerFactory { cfg, registry, egress, filter, _, _, _ ->
                RegistryRevokingServer(ProxyServer(cfg, registry, egress, filter))
            },
            scope = backgroundScope,
            now = { 0L },
        )
        c.start(); runCurrent(); link.up(); runCurrent()

        val registry = c.registry!!
        assertEquals(1, registry.slots().size)
        assertTrue(registry.slots().single().pinned)
        assertTrue(c.log.value.any { it.message == "2 saved device(s) kept for code mode" })
        val first = registry.admitOrCreate(InetAddress.getByName("192.168.49.30"))!!
        assertNotNull(registry.admitOrCreate(InetAddress.getByName("192.168.49.31")))
        assertNull(registry.admitOrCreate(InetAddress.getByName("192.168.49.32")))
        assertEquals(ClientPlatform.UNKNOWN, c.platformOf(first.id))

        // Revoking an auto slot persists, and must keep both stored devices exactly as they were.
        assertTrue(c.revoke(first.id))
        assertEquals(stored, slots.saved)

        c.stop(); runCurrent()
        config = BOTH.copy(requireDeviceCodes = true)
        c.start(); runCurrent()
        assertEquals(listOf("id-1", "id-2"), c.registry!!.slots().filterNot { it.pinned }.map { it.id })
        assertEquals(stored, slots.saved)
    }

    @Test
    fun `a Wi-Fi listener that can't start degrades the session and blocks invites until it recovers`() = runTest {
        val link = FakeLink()
        var portInUse = true
        val c = SessionController(
            egress = FakeSessionEgress(),
            link = link,
            slots = MemorySlots(),
            config = { WIFI_ONLY },
            serverFactory = SessionServerFactory { cfg, _, _, _, _, _, _ ->
                FakeSessionServer().also {
                    if (!cfg.bindAddress.isLoopbackAddress && portInUse) it.startThrows = java.net.BindException("in use")
                }
            },
            scope = backgroundScope,
            now = { 0L },
        )
        c.start(); runCurrent(); link.up(); runCurrent()

        assertEquals(SessionPhase.DEGRADED, c.state.value)
        assertEquals("Wi-Fi listener failed (port 8080 in use?)", c.degradedReason.value)
        assertNull(c.wifiEndpoint)
        assertNull(c.addDevice("Pixel", ClientPlatform.ANDROID))

        // The watchdog retries the listener; once the port is free the session recovers.
        portInUse = false
        advanceTimeBy(30_000); runCurrent()
        assertEquals(SessionPhase.RUNNING, c.state.value)
        assertEquals(8080, c.wifiEndpoint?.port)
        assertNotNull(c.addDevice("Pixel", ClientPlatform.ANDROID))
    }
}
