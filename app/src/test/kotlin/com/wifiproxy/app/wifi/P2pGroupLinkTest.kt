package com.wifiproxy.app.wifi

import com.wifiproxy.core.WifiCredentials
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.net.Inet4Address
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private val OWNER = InetAddress.getByName("192.168.49.1") as Inet4Address
private val CREDS = WifiCredentials("DIRECT-k3-HostPhone", "correct horse battery")

private class FakeP2pApi : P2pApi {
    override val p2pEnabled = MutableStateFlow(true)
    private val connection = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    override val connectionChanged = connection
    val requests = mutableListOf<GroupRequest>()
    /** Consumed one per createGroup call; empty means Success. */
    val results = ArrayDeque<CreateResult>()
    /** What the "firmware" reports back for a created group. */
    var report: (GroupRequest) -> GroupInfo = { GroupInfo(it.ssid, it.passphrase, OWNER) }
    var group: GroupInfo? = null
    var removeCalls = 0

    override suspend fun createGroup(request: GroupRequest): CreateResult {
        requests += request
        val result = results.removeFirstOrNull() ?: CreateResult.Success
        if (result == CreateResult.Success) group = report(request)
        return result
    }

    override suspend fun removeGroup() {
        removeCalls++
        group = null
    }

    override suspend fun groupInfo(): GroupInfo? = group

    fun loseGroup() {
        group = null
        connection.tryEmit(Unit)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class P2pGroupLinkTest {
    private fun TestScope.newLink(api: FakeP2pApi) = P2pGroupLink(api, backgroundScope, deviceModel = "Pixel 11")

    @Test
    fun `start creates a 5 GHz group with the requested credentials and goes Up`() = runTest {
        val api = FakeP2pApi()
        val link = newLink(api)
        link.start(LinkConfig(CREDS, prefer5Ghz = true))
        runCurrent()
        assertEquals(LinkState.Up(OWNER, CREDS), link.state.value)
        assertEquals(listOf(GroupRequest(CREDS.ssid, CREDS.password, GroupBand.GHZ_5)), api.requests)
    }

    @Test
    fun `5 GHz failure falls back to AUTO in the same attempt`() = runTest {
        val api = FakeP2pApi().apply { results += CreateResult.Failure(CreateResult.ERROR) }
        val link = newLink(api)
        link.start(LinkConfig(CREDS, prefer5Ghz = true))
        runCurrent()
        assertIs<LinkState.Up>(link.state.value)
        assertEquals(listOf(GroupBand.GHZ_5, GroupBand.AUTO), api.requests.map { it.band })
    }

    @Test
    fun `firmware that ignores the credentials fails and removes the group`() = runTest {
        val api = FakeP2pApi().apply { report = { GroupInfo("DIRECT-zz-Android_1234", "randompass", OWNER) } }
        val link = newLink(api)
        link.start(LinkConfig(CREDS, prefer5Ghz = false))
        runCurrent()
        advanceTimeBy(120_000)
        val state = assertIs<LinkState.Failed>(link.state.value)
        assertTrue("Pixel 11" in state.message)
        assertEquals(1, api.requests.size)
        assertEquals(null, api.group)
    }

    @Test
    fun `wifi off waits without creating, then creates when wifi comes back`() = runTest {
        val api = FakeP2pApi().apply { p2pEnabled.value = false }
        val link = newLink(api)
        link.start(LinkConfig(CREDS, prefer5Ghz = false))
        runCurrent()
        assertEquals(LinkState.Down(REASON_WIFI_OFF), link.state.value)
        assertTrue(api.requests.isEmpty())
        api.p2pEnabled.value = true
        runCurrent()
        assertIs<LinkState.Up>(link.state.value)
    }

    @Test
    fun `a lost group is recreated with the same credentials`() = runTest {
        val api = FakeP2pApi()
        val link = newLink(api)
        link.start(LinkConfig(CREDS, prefer5Ghz = false))
        runCurrent()
        api.loseGroup()
        runCurrent()
        assertIs<LinkState.Up>(link.state.value)
        assertEquals(2, api.requests.size)
        assertEquals(api.requests[0], api.requests[1])
    }

    @Test
    fun `wifi turning off while up goes Down then recovers`() = runTest {
        val api = FakeP2pApi()
        val link = newLink(api)
        link.start(LinkConfig(CREDS, prefer5Ghz = false))
        runCurrent()
        api.p2pEnabled.value = false
        runCurrent()
        assertEquals(LinkState.Down(REASON_WIFI_OFF), link.state.value)
        api.p2pEnabled.value = true
        runCurrent()
        assertIs<LinkState.Up>(link.state.value)
    }

    @Test
    fun `health check notices a group that vanished without an event`() = runTest {
        val api = FakeP2pApi()
        val link = newLink(api)
        link.start(LinkConfig(CREDS, prefer5Ghz = false))
        runCurrent()
        api.group = null
        link.checkHealth()
        runCurrent()
        assertEquals(2, api.requests.size)
        assertIs<LinkState.Up>(link.state.value)
    }

    @Test
    fun `failures back off exponentially and give up after 10`() = runTest {
        val api = FakeP2pApi().apply { repeat(10) { results += CreateResult.Failure(CreateResult.BUSY) } }
        val link = newLink(api)
        link.start(LinkConfig(CREDS, prefer5Ghz = false))
        runCurrent()
        assertEquals(1, api.requests.size)
        assertEquals(LinkState.Down(REASON_RETRYING), link.state.value)
        advanceTimeBy(999); runCurrent(); assertEquals(1, api.requests.size)
        advanceTimeBy(1); runCurrent(); assertEquals(2, api.requests.size)   // after 1 s
        advanceTimeBy(2_000); runCurrent(); assertEquals(3, api.requests.size) // after 2 s more
        advanceTimeBy(600_000); runCurrent()
        assertEquals(10, api.requests.size)
        assertIs<LinkState.Failed>(link.state.value)
    }

    @Test
    fun `unsupported and missing permission fail immediately`() = runTest {
        for (reason in listOf(CreateResult.P2P_UNSUPPORTED, CreateResult.MISSING_PERMISSION)) {
            val api = FakeP2pApi().apply { results += CreateResult.Failure(reason) }
            val link = newLink(api)
            link.start(LinkConfig(CREDS, prefer5Ghz = false))
            runCurrent()
            assertIs<LinkState.Failed>(link.state.value)
            assertEquals(1, api.requests.size)
        }
    }

    @Test
    fun `stop removes the group and goes Idle for good`() = runTest {
        val api = FakeP2pApi()
        val link = newLink(api)
        link.start(LinkConfig(CREDS, prefer5Ghz = false))
        runCurrent()
        val removesBefore = api.removeCalls
        link.stop()
        api.loseGroup()
        runCurrent()
        assertEquals(LinkState.Idle, link.state.value)
        assertEquals(removesBefore + 1, api.removeCalls)
        assertEquals(1, api.requests.size)
    }
}
