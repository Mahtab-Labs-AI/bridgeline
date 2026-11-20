package com.wifiproxy.app.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** A minimal [SessionRepository] that only tracks whether [close] was called, so
 *  [RepositoryProviderTest] can assert the outgoing repository is closed on a swap (I7) without
 *  pulling in [FakeSessionRepository] or [ServiceSessionRepository] and their Android/coroutine
 *  dependencies. */
private class TrackingRepository(val label: String) : SessionRepository {
    override val session: StateFlow<SessionUi> = MutableStateFlow(
        SessionUi(
            phase = SessionPhase.IDLE,
            degradedReason = null,
            cellularUp = false,
            ssid = label,
            proxyHost = "127.0.0.1",
            port = 8081,
            slots = emptyList(),
            maxClients = 1,
        ),
    )
    override val settings: StateFlow<HostSettings> =
        MutableStateFlow(HostSettings(ssidSuffix = "", password = "", maxClients = 1, port = 8081, prefer5Ghz = false))
    override val checks: StateFlow<List<SetupCheck>> = MutableStateFlow(emptyList())
    override val log: StateFlow<List<LogEntry>> = MutableStateFlow(emptyList())

    var closed = false
        private set

    override fun start() {}
    override fun stop() {}
    override fun addDevice(name: String, platform: ClientPlatform): DeviceInvite? = null
    override fun inviteFor(slotId: String): DeviceInvite? = null
    override fun revoke(slotId: String) {}
    override fun updateSettings(settings: HostSettings): Boolean = true
    override fun close() {
        closed = true
    }
}

class RepositoryProviderTest {

    @Test
    fun `closes the outgoing repository on a demo-mode swap`() {
        val realRepo = TrackingRepository("real")
        val fakeRepo = TrackingRepository("fake")
        val provider = RepositoryProvider(
            createReal = { realRepo },
            createFake = { fakeRepo },
            initialDemoMode = false,
        )

        assertSame(realRepo, provider.repository.value)
        assertFalse(realRepo.closed)

        provider.setDemoMode(true)

        assertSame(fakeRepo, provider.repository.value)
        assertTrue(realRepo.closed, "the outgoing (real) repository should be closed on swap")
        assertFalse(fakeRepo.closed)
    }

    @Test
    fun `swapping back closes the repository being left behind`() {
        var realBuilds = 0
        var fakeBuilds = 0
        val repos = mutableListOf<TrackingRepository>()
        val provider = RepositoryProvider(
            createReal = { TrackingRepository("real-${realBuilds++}").also(repos::add) },
            createFake = { TrackingRepository("fake-${fakeBuilds++}").also(repos::add) },
            initialDemoMode = true,
        )

        provider.setDemoMode(false)
        provider.setDemoMode(true)

        assertEquals(3, repos.size)
        // Every repository except the one currently live should have been closed.
        val live = provider.repository.value
        repos.filter { it !== live }.forEach { assertTrue(it.closed, "${it.label} should be closed") }
        assertFalse((live as TrackingRepository).closed)
    }
}
