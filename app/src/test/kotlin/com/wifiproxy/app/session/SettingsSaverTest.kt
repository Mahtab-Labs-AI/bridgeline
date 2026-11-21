package com.wifiproxy.app.session

import com.wifiproxy.app.settings.HostConfigSource
import com.wifiproxy.app.settings.HostPrefs
import com.wifiproxy.app.settings.KeyValueStore
import com.wifiproxy.app.settings.SaveResult
import com.wifiproxy.app.wifi.AesGcmSecretBox
import com.wifiproxy.app.wifi.CredentialStore
import com.wifiproxy.app.wifi.PersistedSlot
import com.wifiproxy.app.wifi.SecretBox
import com.wifiproxy.app.wifi.SlotStore
import com.wifiproxy.core.clients.SlotRecord
import java.security.KeyStoreException
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
    override var requireDeviceCodes = false
}

private class SaverKv : KeyValueStore {
    val map = mutableMapOf<String, String>()
    override fun getString(key: String) = map[key]
    override fun putString(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
}

/** A real box whose open() can be made to fail transiently, like a busy Keystore. */
private class FlakyBox(private val inner: SecretBox) : SecretBox {
    var failing = false
    override fun seal(plain: ByteArray) = inner.seal(plain)
    override fun open(sealed: ByteArray): ByteArray = if (failing) throw KeyStoreException("busy") else inner.open(sealed)
}

/** The [ServiceSessionRepository.updateSettings] wiring (final fix round, item 11). */
class SettingsSaverTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val prefs = SaverPrefs()
    private val kv = SaverKv()
    private val box = FlakyBox(AesGcmSecretBox { key })
    private val hostConfig = HostConfigSource(prefs, CredentialStore(kv, box, Random(3)))
    private val slotStore = SlotStore(kv)
    private var active = false
    private var restarts = 0
    private val saver = SettingsSaver(hostConfig, slotStore, { prefs.usbSharingEnabled }, { active }, { restarts++ })

    private val valid = HostSettings(ssidSuffix = "HostPhone", password = "correct horse battery", maxClients = 3, port = 8080, prefer5Ghz = false)

    /** The saved snapshot, or null when the save was refused. */
    private fun saved(settings: HostSettings) = saver.save(settings).snapshot

    private fun storeSlots(n: Int) = slotStore.save(
        (1..n).map { PersistedSlot(SlotRecord("id-$it", "Phone $it", "token-$it".padEnd(22, 'x'), it.toLong()), ClientPlatform.ANDROID) },
    )

    @Test
    fun `D7 refuses a limit that leaves the USB laptop no seat next to the stored devices`() {
        assertNotNull(saved(valid))
        storeSlots(2)
        // USB on: 2 saved Wi-Fi devices + the laptop need 3 seats.
        assertNull(saved(valid.copy(maxClients = 2)))
        assertEquals(3, prefs.maxClients)
        assertEquals(2, slotStore.load().size)
        // A name-only change keeps the devices, so they still count.
        assertNull(saved(valid.copy(ssidSuffix = "OtherName", maxClients = 2)))
        assertEquals("HostPhone", hostConfig.snapshot().settings.ssidSuffix)
        // With USB off the Wi-Fi devices may use every seat.
        prefs.usbSharingEnabled = false
        assertNotNull(saved(valid.copy(maxClients = 2)))
        assertEquals(2, prefs.maxClients)
    }

    @Test
    fun `a password change deletes the stored devices, so D7 counts none of them`() {
        assertNotNull(saved(valid))
        storeSlots(2)
        val snapshot = assertNotNull(saved(valid.copy(password = "brand new secret", maxClients = 1)))
        assertEquals("brand new secret", snapshot.settings.password)
        assertEquals(emptyList(), slotStore.load())
    }

    @Test
    fun `a name-only change keeps the stored devices`() {
        assertNotNull(saved(valid))
        storeSlots(2)
        val snapshot = assertNotNull(saved(valid.copy(ssidSuffix = "OtherName")))
        assertEquals("OtherName", snapshot.settings.ssidSuffix)
        assertEquals(listOf("id-1", "id-2"), slotStore.load().map { it.record.id })
    }

    @Test
    fun `a name or password change restarts the session only while it is active`() {
        assertNotNull(saved(valid)) // idle: the first password is set, nothing to restart
        assertEquals(0, restarts)

        active = true
        assertNotNull(saved(valid.copy(maxClients = 2))) // plain setting: applies next start
        assertEquals(0, restarts)
        assertNotNull(saved(valid.copy(ssidSuffix = "OtherName")))
        assertEquals(1, restarts)
        assertNotNull(saved(valid.copy(ssidSuffix = "OtherName", password = "brand new secret")))
        assertEquals(2, restarts)

        active = false
        assertNotNull(saved(valid.copy(password = "yet another secret")))
        assertEquals(2, restarts)
    }

    // --- Round 2: unreadable credentials are not "unset" ------------------------------------------

    @Test
    fun `a transient keystore error during a save changes nothing and reports UNAVAILABLE`() {
        val before = assertNotNull(saved(valid))
        storeSlots(2)
        val blob = kv.map["wifi_credentials"]
        active = true

        box.failing = true
        // A plain Max-clients change, and a real password change: both must leave everything alone.
        for (attempt in listOf(valid.copy(maxClients = 2), valid.copy(password = "brand new secret", maxClients = 1))) {
            val outcome = saver.save(attempt)
            assertEquals(SaveResult.UNAVAILABLE, outcome.result)
            assertNull(outcome.snapshot)
        }
        assertEquals(listOf("id-1", "id-2"), slotStore.load().map { it.record.id })
        assertEquals(blob, kv.map["wifi_credentials"])
        assertEquals(3, prefs.maxClients)
        assertEquals(0, restarts)

        // Once the keystore recovers, the same SSID (code included) and password are there.
        box.failing = false
        val after = hostConfig.snapshot()
        assertEquals(before.ssid, after.ssid)
        assertEquals("correct horse battery", after.settings.password)
    }
}
