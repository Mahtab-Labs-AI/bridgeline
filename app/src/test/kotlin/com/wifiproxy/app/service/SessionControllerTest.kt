package com.wifiproxy.app.service

import com.wifiproxy.app.egress.SessionEgress
import com.wifiproxy.app.session.SessionPhase
import com.wifiproxy.core.net.Cidr
import com.wifiproxy.core.net.DestinationFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
 *  exactly while a (virtual-time) start is "in flight" (C2). */
private class FakeSessionServer(
    private val onStart: (() -> Unit)? = null,
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
        return true
    }

    override fun updateFilter(filter: DestinationFilter) {
        filters.add(filter)
    }

    fun killAcceptLoop() {
        isAccepting = false
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SessionControllerTest {

    private fun controller(
        egress: FakeSessionEgress = FakeSessionEgress(),
        server: FakeSessionServer = FakeSessionServer(),
        scope: CoroutineScope,
        watchdogIntervalMs: Long = 30_000L,
        now: () -> Long = { 0L },
    ) = SessionController(
        egress = egress,
        serverFactory = SessionServerFactory { _, _, _, _, _ -> server },
        scope = scope,
        watchdogIntervalMs = watchdogIntervalMs,
        now = now,
    )

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
            serverFactory = SessionServerFactory { _, _, _, _, _ -> FakeSessionServer() },
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
            serverFactory = SessionServerFactory { _, _, _, _, _ -> server },
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
                serverFactory = SessionServerFactory { _, _, _, _, _ -> server },
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
}
