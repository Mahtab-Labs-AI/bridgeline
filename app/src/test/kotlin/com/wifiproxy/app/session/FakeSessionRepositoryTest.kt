package com.wifiproxy.app.session

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FakeSessionRepositoryTest {

    @Test
    fun `start reaches RUNNING after 1point5s`() = runTest {
        val repo = FakeSessionRepository(scope = backgroundScope, now = { currentTime })

        repo.start()
        runCurrent()
        assertEquals(SessionPhase.STARTING, repo.session.value.phase)

        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(SessionPhase.RUNNING, repo.session.value.phase)
    }

    @Test
    fun `counters grow while running`() = runTest {
        val repo = FakeSessionRepository(scope = backgroundScope, now = { currentTime })
        repo.start()
        advanceTimeBy(1_500)
        runCurrent()

        val before = repo.session.value.slots.map { it.bytesUp to it.bytesDown }

        advanceTimeBy(3_000)
        runCurrent()

        val after = repo.session.value.slots.map { it.bytesUp to it.bytesDown }
        before.zip(after).forEach { (b, a) ->
            assertTrue(a.first > b.first, "bytesUp should grow")
            assertTrue(a.second > b.second, "bytesDown should grow")
        }
    }

    @Test
    fun `degrades after 30 ticks then recovers`() = runTest {
        val repo = FakeSessionRepository(scope = backgroundScope, now = { currentTime })
        repo.start()
        advanceTimeBy(1_500)
        runCurrent()

        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(SessionPhase.DEGRADED, repo.session.value.phase)
        assertEquals("No mobile data", repo.session.value.degradedReason)

        advanceTimeBy(4_000)
        runCurrent()
        assertEquals(SessionPhase.RUNNING, repo.session.value.phase)
        assertNull(repo.session.value.degradedReason)
    }

    @Test
    fun `addDevice returns null when slots are full`() = runTest {
        val repo = FakeSessionRepository(scope = backgroundScope)
        // Seed has 2 slots, default maxClients is 3.
        val invite = repo.addDevice("Third Phone", ClientPlatform.ANDROID)
        assertNotNull(invite)
        assertEquals(3, repo.session.value.slots.size)

        val rejected = repo.addDevice("Fourth Phone", ClientPlatform.ANDROID)
        assertNull(rejected)
        assertEquals(3, repo.session.value.slots.size)
    }

    @Test
    fun `updateSettings is rejected below the active slot count`() = runTest {
        val repo = FakeSessionRepository(scope = backgroundScope)
        val current = repo.settings.value
        assertEquals(2, repo.session.value.slots.size)

        val ok = repo.updateSettings(current.copy(maxClients = 1))
        assertFalse(ok)
        assertEquals(current, repo.settings.value)
    }

    @Test
    fun `revoke removes the slot and logs it`() = runTest {
        val repo = FakeSessionRepository(scope = backgroundScope)
        val slotId = repo.session.value.slots.first().id
        val slotName = repo.session.value.slots.first().name

        repo.revoke(slotId)

        assertTrue(repo.session.value.slots.none { it.id == slotId })
        assertTrue(repo.log.value.any { it.message.contains(slotName) })
    }
}
