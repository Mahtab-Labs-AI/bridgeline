package com.wifiproxy.app.settings

import com.wifiproxy.app.session.HostSettings
import com.wifiproxy.app.wifi.AesGcmSecretBox
import com.wifiproxy.app.wifi.CredentialStore
import java.util.Random
import javax.crypto.KeyGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class MemoryPrefs : HostPrefs {
    override var usbSharingEnabled = true
    override var maxClients = 1
    override var wifiPort = 8080
    override var prefer5Ghz = true
}

private class MapKv : KeyValueStore {
    val map = mutableMapOf<String, String>()
    override fun getString(key: String) = map[key]
    override fun putString(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
}

class HostConfigSourceTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val prefs = MemoryPrefs()
    private val source = HostConfigSource(prefs, CredentialStore(MapKv(), AesGcmSecretBox { key }, Random(7)))
    private val valid = HostSettings(ssidSuffix = "HostPhone", password = "correct horse battery", maxClients = 3, port = 8080, prefer5Ghz = false)

    @Test
    fun `defaults are usb only`() {
        val cfg = source.sessionConfig()
        assertTrue(cfg.usbEnabled)
        assertNull(cfg.wifi)
        assertEquals(1, cfg.maxClients)
        assertEquals("", source.hostSettings().password)
    }

    @Test
    fun `saving credentials enables wifi and reports the change once`() {
        assertEquals(SaveResult.SAVED_CREDENTIALS_CHANGED, source.save(valid))
        val wifi = source.sessionConfig().wifi!!
        assertTrue(Regex("DIRECT-[a-z0-9]{2}-HostPhone").matches(wifi.credentials.ssid))
        assertEquals("correct horse battery", wifi.credentials.password)
        assertEquals(false, wifi.prefer5Ghz)
        assertEquals(3, source.sessionConfig().maxClients)
        assertEquals(SaveResult.SAVED, source.save(valid))
        assertEquals(SaveResult.SAVED, source.save(valid.copy(maxClients = 2)))
    }

    @Test
    fun `invalid settings change nothing`() {
        source.save(valid)
        for (bad in listOf(valid.copy(password = "short"), valid.copy(port = 8081), valid.copy(port = 80), valid.copy(maxClients = 4), valid.copy(ssidSuffix = "has space"))) {
            assertEquals(SaveResult.INVALID, source.save(bad))
        }
        assertEquals(valid, source.hostSettings())
    }

    @Test
    fun `blank name and password clears the credentials`() {
        source.save(valid)
        assertEquals(SaveResult.SAVED_CREDENTIALS_CHANGED, source.save(valid.copy(ssidSuffix = "", password = "")))
        assertNull(source.sessionConfig().wifi)
    }
}
