package com.wifiproxy.app.service

import com.wifiproxy.app.egress.SessionEgress
import com.wifiproxy.app.session.SessionPhase
import com.wifiproxy.core.net.Cidr
import com.wifiproxy.core.net.DestinationFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A controllable [SessionEgress]: tests flip [setAvailable] to drive the Running<->Degraded path. */
private class FakeSessionEgress(available: Boolean = true) : SessionEgress {
    private val _available = MutableStateFlow(available)
    override val available: StateFlow<Boolean> = _available.asStateFlow()
    override var onLinkChange: (() -> Unit)? = null

    override val isAvailable: Boolean get() = _available.value
    override val isValidated: Boolean get() = _available.value

    var startCalls = 0
        private set
    var stopCalls = 0
        private set
    var hostAddressesCalls = 0
        private set

    override fun start() {
        startCalls++
    }

    override fun stop() {
        stopCalls++
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
}

/** A controllable [UsbProxyServer]: [isAccepting] can be forced false to drive the watchdog. */
private class FakeUsbProxyServer : UsbProxyServer {
    override var isAccepting: Boolean = true
        private set

    var startCalls = 0
        private set
    var stopCalls = 0
        private set
    val revokedIds = mutableListOf<String>()
    val filters = mutableListOf<DestinationFilter>()

    override fun start() {
        startCalls++
        isAccepting = true
    }

    override fun stop() {
        stopCalls++
        isAccepting = false
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
class UsbSessionControllerTest {

    private fun controller(
        egress: FakeSessionEgress = FakeSessionEgress(),
        server: FakeUsbProxyServer = FakeUsbProxyServer(),
        scope: CoroutineScope,
        watchdogIntervalMs: Long = 30_000L,
        now: () -> Long = { 0L },
    ) = UsbSessionController(
        egress = egress,
        serverFactory = UsbProxyServerFactory { _, _, _, _, _ -> server },
        scope = scope,
        watchdogIntervalMs = watchdogIntervalMs,
        now = now,
    )

    @Test
    fun `start reaches RUNNING when egress is available`() = runTest {
        val server = FakeUsbProxyServer()
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
        val server = FakeUsbProxyServer()
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
        val server = FakeUsbProxyServer()
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
        val server = FakeUsbProxyServer()
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
        val server = FakeUsbProxyServer()
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
    fun `link change refreshes the destination filter`() = runTest {
        val egress = FakeSessionEgress()
        val server = FakeUsbProxyServer()
        val c = controller(egress = egress, server = server, scope = backgroundScope)
        c.start()
        runCurrent()
        val before = server.filters.size

        egress.onLinkChange?.invoke()

        assertEquals(before + 1, server.filters.size)
    }

    @Test
    fun `revoke forwards to the server, not the registry`() = runTest {
        val server = FakeUsbProxyServer()
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
        val server = FakeUsbProxyServer()
        val c = controller(server = server, scope = backgroundScope)
        c.start()
        runCurrent()

        c.start()
        runCurrent()

        assertEquals(1, server.startCalls)
    }
}
