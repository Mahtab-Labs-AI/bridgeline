package com.wifiproxy.app.session

import com.wifiproxy.app.settings.HostConfigSource
import com.wifiproxy.app.settings.HostPrefs
import com.wifiproxy.app.settings.KeyValueStore
import com.wifiproxy.app.wifi.AesGcmSecretBox
import com.wifiproxy.app.wifi.CredentialStore
import com.wifiproxy.app.wifi.PersistedSlot
import com.wifiproxy.app.wifi.SlotStore
import com.wifiproxy.core.clients.SlotRecord
import java.util.Random
import javax.crypto.KeyGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private class SaverPrefs : HostPrefs {
    override var usbSharingEnabled = true
    override var maxClients = 3
    override var wifiPort = 8080
    override var prefer5Ghz = false
}

private class SaverKv : KeyValueStore {
    val map = mutableMapOf<String, String>()
    override fun getString(key: String) = map[key]
    override fun putString(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
}

/** The [ServiceSessionRepository.updateSettings] wiring (final fix round, item 11). */
class SettingsSaverTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val prefs = SaverPrefs()
    private val kv = SaverKv()
    private val hostConfig = HostConfigSource(prefs, CredentialStore(kv, AesGcmSecretBox { key }, Random(3)))
    private val slotStore = SlotStore(kv)
    private var active = false
    private var restarts = 0
    private val saver = SettingsSaver(hostConfig, slotStore, { prefs.usbSharingEnabled }, { active }, { restarts++ })

    private val valid = HostSettings(ssidSuffix = "HostPhone", password = "correct horse battery", maxClients = 3, port = 8080, prefer5Ghz = false)

    private fun storeSlots(n: Int) = slotStore.save(
        (1..n).map { PersistedSlot(SlotRecord("id-$it", "Phone $it", "token-$it".padEnd(22, 'x'), it.toLong()), ClientPlatform.ANDROID) },
    )

    @Test
    fun `D7 refuses a limit that leaves the USB laptop no seat next to the stored devices`() {
        assertNotNull(saver.save(valid))
        storeSlots(2)
        // USB on: 2 saved Wi-Fi devices + the laptop need 3 seats.
        assertNull(saver.save(valid.copy(maxClients = 2)))
        assertEquals(3, prefs.maxClients)
        assertEquals(2, slotStore.load().size)
        // A name-only change keeps the devices, so they still count.
        assertNull(saver.save(valid.copy(ssidSuffix = "OtherName", maxClients = 2)))
        assertEquals("HostPhone", hostConfig.hostSettings().ssidSuffix)
        // With USB off the Wi-Fi devices may use every seat.
        prefs.usbSharingEnabled = false
        assertNotNull(saver.save(valid.copy(maxClients = 2)))
        assertEquals(2, prefs.maxClients)
    }

    @Test
    fun `a password change deletes the stored devices, so D7 counts none of them`() {
        assertNotNull(saver.save(valid))
        storeSlots(2)
        val snapshot = assertNotNull(saver.save(valid.copy(password = "brand new secret", maxClients = 1)))
        assertEquals("brand new secret", snapshot.settings.password)
        assertEquals(emptyList(), slotStore.load())
    }

    @Test
    fun `a name-only change keeps the stored devices`() {
        assertNotNull(saver.save(valid))
        storeSlots(2)
        val snapshot = assertNotNull(saver.save(valid.copy(ssidSuffix = "OtherName")))
        assertEquals("OtherName", snapshot.settings.ssidSuffix)
        assertEquals(listOf("id-1", "id-2"), slotStore.load().map { it.record.id })
    }

    @Test
    fun `a name or password change restarts the session only while it is active`() {
        assertNotNull(saver.save(valid)) // idle: the first password is set, nothing to restart
        assertEquals(0, restarts)

        active = true
        assertNotNull(saver.save(valid.copy(maxClients = 2))) // plain setting: applies next start
        assertEquals(0, restarts)
        assertNotNull(saver.save(valid.copy(ssidSuffix = "OtherName")))
        assertEquals(1, restarts)
        assertNotNull(saver.save(valid.copy(ssidSuffix = "OtherName", password = "brand new secret")))
        assertEquals(2, restarts)

        active = false
        assertNotNull(saver.save(valid.copy(password = "yet another secret")))
        assertEquals(2, restarts)
    }
}
