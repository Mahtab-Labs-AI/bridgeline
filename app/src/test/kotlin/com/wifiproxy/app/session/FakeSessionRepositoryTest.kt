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
    fun `wifiUp follows RUNNING and DEGRADED only`() = runTest {
        val repo = FakeSessionRepository(scope = backgroundScope, now = { currentTime })
        assertFalse(repo.session.value.wifiUp)

        repo.start(); runCurrent()
        assertFalse(repo.session.value.wifiUp) // STARTING

        advanceTimeBy(1_500); runCurrent()
        assertTrue(repo.session.value.wifiUp) // RUNNING

        advanceTimeBy(30_000); runCurrent()
        assertEquals(SessionPhase.DEGRADED, repo.session.value.phase)
        assertTrue(repo.session.value.wifiUp)

        repo.stop(); runCurrent()
        assertFalse(repo.session.value.wifiUp) // STOPPING
        advanceTimeBy(500); runCurrent()
        assertEquals(SessionPhase.IDLE, repo.session.value.phase)
        assertFalse(repo.session.value.wifiUp)
    }

    @Test
    fun `the proxy address shows only while Wi-Fi is up`() = runTest {
        val repo = FakeSessionRepository(scope = backgroundScope, now = { currentTime })
        assertNull(repo.session.value.proxyAddress)

        repo.start(); advanceTimeBy(1_500); runCurrent()
        assertEquals("192.168.49.1:8080", repo.session.value.proxyAddress)

        repo.stop(); runCurrent()
        assertNull(repo.session.value.proxyAddress)
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

        val result = repo.updateSettings(current.copy(maxClients = 1))
        assertEquals(SettingsUpdate.REJECTED, result)
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

    @Test
    fun `active capacity snapshots demo inputs until restart`() = runTest {
        var usbOn = true
        val repo = FakeSessionRepository(scope = backgroundScope, now = { currentTime }, usbEnabled = { usbOn })
        repo.session.value.slots.forEach { repo.revoke(it.id) }
        assertEquals(SettingsUpdate.SAVED, repo.updateSettings(repo.settings.value.copy(maxClients = 1)))
        assertNull(repo.session.value.activeWifiSeatCapacity)

        repo.start(); runCurrent()
        assertEquals(SessionPhase.STARTING, repo.session.value.phase)
        assertEquals(0, repo.session.value.activeWifiSeatCapacity)
        advanceTimeBy(1_500); runCurrent()
        assertEquals(SettingsUpdate.SAVED, repo.updateSettings(repo.settings.value.copy(maxClients = 2)))
        assertEquals(0, repo.session.value.activeWifiSeatCapacity)
        assertEquals(2, repo.session.value.maxClients)

        repo.stop(); runCurrent()
        assertEquals(0, repo.session.value.activeWifiSeatCapacity) // retained through STOPPING
        advanceTimeBy(500); runCurrent()
        assertNull(repo.session.value.activeWifiSeatCapacity)
        repo.start(); advanceTimeBy(1_500); runCurrent()
        assertEquals(1, repo.session.value.activeWifiSeatCapacity)

        usbOn = false
        assertEquals(1, repo.session.value.activeWifiSeatCapacity)
        repo.stop(); advanceTimeBy(500); runCurrent()
        assertNull(repo.session.value.activeWifiSeatCapacity)
        repo.start(); advanceTimeBy(1_500); runCurrent()
        assertEquals(2, repo.session.value.activeWifiSeatCapacity)
        repo.close()

        val reverse = FakeSessionRepository(scope = backgroundScope, now = { currentTime }, usbEnabled = { true })
        reverse.session.value.slots.forEach { reverse.revoke(it.id) }
        assertEquals(SettingsUpdate.SAVED, reverse.updateSettings(reverse.settings.value.copy(maxClients = 2)))
        reverse.start(); advanceTimeBy(1_500); runCurrent()
        assertEquals(1, reverse.session.value.activeWifiSeatCapacity)
        assertEquals(SettingsUpdate.SAVED, reverse.updateSettings(reverse.settings.value.copy(maxClients = 1)))
        assertEquals(1, reverse.session.value.maxClients)
        assertEquals(1, reverse.session.value.activeWifiSeatCapacity)
        reverse.stop(); advanceTimeBy(500); runCurrent()
        assertNull(reverse.session.value.activeWifiSeatCapacity)
        reverse.start(); advanceTimeBy(1_500); runCurrent()
        assertEquals(0, reverse.session.value.activeWifiSeatCapacity)
        reverse.close()
    }

    @Test
    fun `rejected demo settings leave active capacity unchanged`() = runTest {
        val repo = FakeSessionRepository(scope = backgroundScope, now = { currentTime }, usbEnabled = { true })
        val saved = repo.settings.value
        repo.start(); advanceTimeBy(1_500); runCurrent()
        assertEquals(2, repo.session.value.activeWifiSeatCapacity)
        assertEquals(SettingsUpdate.REJECTED, repo.updateSettings(saved.copy(maxClients = 1)))
        assertEquals(saved, repo.settings.value)
        assertEquals(3, repo.session.value.maxClients)
        assertEquals(2, repo.session.value.activeWifiSeatCapacity)
        repo.close()
    }

    @Test
    fun `demo capacity survives degradation and clears on stop`() = runTest {
        val repo = FakeSessionRepository(scope = backgroundScope, now = { currentTime }, usbEnabled = { true })
        repo.session.value.slots.forEach { repo.revoke(it.id) }
        assertEquals(SettingsUpdate.SAVED, repo.updateSettings(repo.settings.value.copy(maxClients = 1)))
        assertNull(repo.session.value.activeWifiSeatCapacity)
        repo.start(); advanceTimeBy(1_500); runCurrent()
        assertEquals(0, repo.session.value.activeWifiSeatCapacity)
        advanceTimeBy(30_000); runCurrent()
        assertEquals(SessionPhase.DEGRADED, repo.session.value.phase)
        assertEquals(0, repo.session.value.activeWifiSeatCapacity)
        advanceTimeBy(4_000); runCurrent()
        assertEquals(SessionPhase.RUNNING, repo.session.value.phase)
        assertEquals(0, repo.session.value.activeWifiSeatCapacity)
        repo.stop(); runCurrent()
        assertEquals(SessionPhase.STOPPING, repo.session.value.phase)
        assertEquals(0, repo.session.value.activeWifiSeatCapacity)
        advanceTimeBy(500); runCurrent()
        assertEquals(SessionPhase.IDLE, repo.session.value.phase)
        assertNull(repo.session.value.activeWifiSeatCapacity)
        repo.close()
    }
}
