package com.wifiproxy.app.wifi

import com.wifiproxy.core.WifiCredentials
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private val OWNER = InetAddress.getByName("192.168.49.1") as Inet4Address
private val CREDS = WifiCredentials("DIRECT-k3-HostPhone", "correct horse battery")
private val CREDS2 = WifiCredentials("DIRECT-zz-OtherPhone", "another correct horse")

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

    /** When set, createGroup() suspends here until the test completes it - uncancellably, like a
     *  real in-flight Android call that can't be aborted, so tests can force a genuine window
     *  between a cancellation being requested and the old run actually finishing. */
    var createGroupGate: CompletableDeferred<Unit>? = null

    override suspend fun createGroup(request: GroupRequest): CreateResult {
        createGroupGate?.let { gate -> withContext(NonCancellable) { gate.await() } }
        requests += request
        val result = results.removeFirstOrNull() ?: CreateResult.Success
        if (result == CreateResult.Success) group = report(request)
        return result
    }

    /** Fired every time removeGroup() actually runs, so tests can record relative ordering. */
    var onRemoveGroup: () -> Unit = {}

    override suspend fun removeGroup() {
        removeCalls++
        group = null
        onRemoveGroup()
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

        // Gate the recreate's createGroup() so REASON_LOST is observable before it completes,
        // instead of being clobbered by REASON_CREATING at the top of the next loop iteration.
        val gate = CompletableDeferred<Unit>()
        api.createGroupGate = gate
        api.loseGroup()
        runCurrent()
        assertEquals(LinkState.Down(REASON_LOST), link.state.value)
        assertEquals(1, api.requests.size)

        gate.complete(Unit)
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

    @Test
    fun `start racing a pending stop leaves the new run alone, and a later stop still works`() = runTest {
        val api = FakeP2pApi()
        val gate = CompletableDeferred<Unit>()
        api.createGroupGate = gate
        val link = newLink(api)

        link.start(LinkConfig(CREDS, prefer5Ghz = false))
        runCurrent()
        // The first run is stuck inside an uncancellable createGroup() call; nothing created yet.
        assertTrue(api.requests.isEmpty())

        val stopJob = launch { link.stop() }
        runCurrent()
        // stop() is now suspended in cancelAndJoin(), waiting for the first run to actually finish.
        // Start a second run before that happens.
        link.start(LinkConfig(CREDS2, prefer5Ghz = false))
        gate.complete(Unit)
        advanceUntilIdle()
        stopJob.join()

        // The final state follows the second start, not the racing stop().
        assertEquals(LinkState.Up(OWNER, CREDS2), link.state.value)

        // A later stop() still stops the (second) run cleanly.
        link.stop()
        assertEquals(LinkState.Idle, link.state.value)

        // Nothing is left alive to react: a later "loss" signal causes no new request.
        val requestsAfterStop = api.requests.size
        api.loseGroup()
        runCurrent()
        assertEquals(requestsAfterStop, api.requests.size)
    }

    @Test
    fun `case a - start just before stop leaves no orphan run once the dust settles`() = runTest {
        // Round-1 bug: start(B) is cancelled before it's ever dispatched (so B's body, which would
        // have cancelled A, never runs); stop() then tears down on its own (stale) notion of "the
        // current run", while A - still in awaitLoss - sees the removal as a loss and recreates,
        // orphaned, with nothing left referencing it.
        val api = FakeP2pApi()
        val link = newLink(api)
        link.start(LinkConfig(CREDS, prefer5Ghz = false))
        runCurrent()

        link.start(LinkConfig(CREDS2, prefer5Ghz = false))
        link.stop() // suspend, called directly - deliberately no runCurrent() before this
        runCurrent()

        assertEquals(LinkState.Idle, link.state.value)
        val requestsAfterStop = api.requests.size

        api.loseGroup()
        runCurrent()
        assertEquals(LinkState.Idle, link.state.value)
        assertEquals(requestsAfterStop, api.requests.size)
    }

    @Test
    fun `case b - a stop landing while the old run is mid create still removes the group it makes`() = runTest {
        // Round-1 bug: B's cancellable join on A throws right away (without actually waiting for A
        // to finish), so stop() tears down while A is still in flight; A's createGroup() then
        // completes *after* the removeGroup, leaving a real group behind while state says Idle.
        val api = FakeP2pApi()
        val gate = CompletableDeferred<Unit>()
        api.createGroupGate = gate
        val link = newLink(api)

        link.start(LinkConfig(CREDS, prefer5Ghz = false))
        runCurrent()
        link.start(LinkConfig(CREDS2, prefer5Ghz = false))
        runCurrent()

        val stopJob = launch { link.stop() }
        // Let stop() run as far as it can before the gate opens: on the old (round-1) code, B's
        // own cancellation interrupts its join on A immediately (it doesn't actually wait for A to
        // finish), so stop() tears down right here, believing it's done.
        runCurrent()
        // Only now does A's gated createGroup() resolve - too late, after the teardown already
        // ran.
        gate.complete(Unit)
        runCurrent()

        assertEquals(LinkState.Idle, link.state.value)
        assertEquals(null, api.group)
        assertTrue(stopJob.isCompleted)
    }

    @Test
    fun `two concurrent stop calls both complete, each only after its own removeGroup`() = runTest {
        val api = FakeP2pApi()
        val link = newLink(api)
        link.start(LinkConfig(CREDS, prefer5Ghz = false))
        runCurrent()

        val order = mutableListOf<String>()
        api.onRemoveGroup = { order += "removeGroup" }

        val stop1 = launch { link.stop(); order += "stop1" }
        val stop2 = launch { link.stop(); order += "stop2" }
        runCurrent()

        assertTrue(stop1.isCompleted)
        assertTrue(stop2.isCompleted)
        // The supervisor processes both Stop commands - including both removeGroup() calls -
        // before either waiting stop() caller is resumed (completing a Deferred doesn't preempt
        // the completer). Both removeGroup() calls precede both completions, and - since commands
        // are processed strictly in submission order - stop1 completes before stop2.
        assertEquals(listOf("removeGroup", "removeGroup", "stop1", "stop2"), order)
    }

    @Test
    fun `start called twice in a row leaves only the second run live`() = runTest {
        val api = FakeP2pApi()
        val link = newLink(api)
        link.start(LinkConfig(CREDS, prefer5Ghz = false))
        link.start(LinkConfig(CREDS2, prefer5Ghz = false))
        runCurrent()
        assertEquals(LinkState.Up(OWNER, CREDS2), link.state.value)

        // From here on, exactly one run should be driving the link: a later event causes exactly
        // one new request, for the second run's credentials - not two, and not zero.
        val requestsAfterSettling = api.requests.size
        api.loseGroup()
        runCurrent()
        assertIs<LinkState.Up>(link.state.value)
        assertEquals(requestsAfterSettling + 1, api.requests.size)
        assertEquals(CREDS2.ssid, api.requests.last().ssid)
    }

    @Test
    fun `wifi going off during a failing create is not counted toward the failure limit`() = runTest {
        val api = FakeP2pApi().apply { results += CreateResult.Failure(CreateResult.BUSY) }
        val gate = CompletableDeferred<Unit>()
        api.createGroupGate = gate
        val link = newLink(api)
        link.start(LinkConfig(CREDS, prefer5Ghz = false))
        runCurrent()

        // The create is in flight; Wi-Fi drops before it resolves (with a failure).
        api.p2pEnabled.value = false
        gate.complete(Unit)
        runCurrent()

        // Waiting for Wi-Fi, not retrying with backoff - and so not counted as a failure.
        assertEquals(LinkState.Down(REASON_WIFI_OFF), link.state.value)
        assertEquals(1, api.requests.size)

        api.p2pEnabled.value = true
        runCurrent()
        assertIs<LinkState.Up>(link.state.value)
        assertEquals(2, api.requests.size)
    }

    @Test
    fun `backoff caps at exactly 60 seconds between attempts`() = runTest {
        val api = FakeP2pApi().apply { repeat(10) { results += CreateResult.Failure(CreateResult.BUSY) } }
        val link = P2pGroupLink(api, backgroundScope, deviceModel = "Pixel 11", maxConsecutiveFailures = 20)
        link.start(LinkConfig(CREDS, prefer5Ghz = false))
        runCurrent()

        // Walk the 1 s, 2 s, 4 s, 8 s, 16 s, 32 s steps up to the 7th failure, where the next gap
        // (nominally 64 s) must be capped at 60 s instead.
        for (stepMs in listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L)) {
            advanceTimeBy(stepMs)
            runCurrent()
        }
        assertEquals(7, api.requests.size)

        advanceTimeBy(59_999); runCurrent()
        assertEquals(7, api.requests.size) // not yet - would still be 7 at 64 s too
        advanceTimeBy(1); runCurrent()
        assertEquals(8, api.requests.size) // exactly at the 60 s cap
    }

    @Test
    fun `a BUSY failure at 5 GHz does not fall back to AUTO in the same attempt`() = runTest {
        val api = FakeP2pApi().apply { results += CreateResult.Failure(CreateResult.BUSY) }
        val link = newLink(api)
        link.start(LinkConfig(CREDS, prefer5Ghz = true))
        runCurrent()
        assertEquals(listOf(GroupBand.GHZ_5), api.requests.map { it.band })
        assertEquals(LinkState.Down(REASON_RETRYING), link.state.value)
    }
}
