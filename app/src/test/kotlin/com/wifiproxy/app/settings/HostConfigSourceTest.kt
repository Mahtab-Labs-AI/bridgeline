package com.wifiproxy.app.settings

import com.wifiproxy.app.session.HostSettings
import com.wifiproxy.app.wifi.AesGcmSecretBox
import com.wifiproxy.app.wifi.CredentialStore
import com.wifiproxy.app.wifi.CredentialsUnavailableException
import com.wifiproxy.app.wifi.SecretBox
import java.security.ProviderException
import java.util.Random
import javax.crypto.KeyGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class MemoryPrefs : HostPrefs {
    override var usbSharingEnabled = true
    override var maxClients = 1
    override var wifiPort = 8080
    override var prefer5Ghz = true
    override var requireDeviceCodes = false
}

private class MapKv : KeyValueStore {
    val map = mutableMapOf<String, String>()
    override fun getString(key: String) = map[key]
    override fun putString(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
}

/** Counts Keystore-backed operations: each one is a Keystore round trip on a device. */
private class CountingBox(private val inner: SecretBox) : SecretBox {
    var opens = 0
    var seals = 0
    override fun seal(plain: ByteArray): ByteArray = inner.seal(plain).also { seals++ }
    override fun open(sealed: ByteArray): ByteArray = inner.open(sealed).also { opens++ }
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
        assertEquals("", source.snapshot().settings.password)
    }

    @Test
    fun `device codes are off by default and the flag is persisted into the session config`() {
        assertFalse(source.requireDeviceCodes)
        assertFalse(source.sessionConfig().requireDeviceCodes)
        source.requireDeviceCodes = true
        assertTrue(prefs.requireDeviceCodes)
        assertTrue(source.sessionConfig().requireDeviceCodes)
        // A settings save never touches it.
        source.save(valid)
        assertTrue(source.sessionConfig().requireDeviceCodes)
        source.requireDeviceCodes = false
        assertFalse(source.sessionConfig().requireDeviceCodes)
    }

    @Test
    fun `saving credentials enables wifi and reports the change once`() {
        assertEquals(SaveResult.SAVED_PASSWORD_CHANGED, source.save(valid))
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
        assertEquals(valid, source.snapshot().settings)
    }

    @Test
    fun `blank name and password clears the credentials`() {
        source.save(valid)
        assertEquals(SaveResult.SAVED_PASSWORD_CHANGED, source.save(valid.copy(ssidSuffix = "", password = "")))
        assertNull(source.sessionConfig().wifi)
    }

    // --- Fix round 1 ------------------------------------------------------------------------------

    private val countingBox = CountingBox(AesGcmSecretBox { key })
    private val counted = HostConfigSource(MemoryPrefs(), CredentialStore(MapKv(), countingBox, Random(7)))

    @Test
    fun `snapshot reads settings and ssid with one decrypt`() {
        counted.save(valid)
        countingBox.opens = 0
        val snapshot = counted.snapshot()
        assertEquals(1, countingBox.opens)
        assertEquals(valid, snapshot.settings)
        assertTrue(Regex("DIRECT-[a-z0-9]{2}-HostPhone").matches(snapshot.ssid!!))
    }

    @Test
    fun `a credentials change decrypts once and returns the new snapshot`() {
        val first = counted.update(valid).snapshot!!
        countingBox.opens = 0
        countingBox.seals = 0
        val outcome = counted.update(valid.copy(password = "another passphrase"))
        assertEquals(SaveResult.SAVED_PASSWORD_CHANGED, outcome.result)
        assertEquals(1, countingBox.opens)
        assertEquals(1, countingBox.seals)
        // Same SSID code is kept, so clients auto-rejoin under the same SSID.
        assertEquals(first.ssid, outcome.snapshot!!.ssid)
        assertEquals("another passphrase", outcome.snapshot.settings.password)
    }

    @Test
    fun `allow sees whether the credentials change, and a refusal changes nothing`() {
        source.save(valid)
        val seen = mutableListOf<Boolean>()
        assertEquals(SaveResult.INVALID, source.update(valid.copy(maxClients = 1)) { changed -> seen += changed; false }.result)
        assertEquals(SaveResult.INVALID, source.update(valid.copy(password = "brand new secret")) { changed -> seen += changed; false }.result)
        assertEquals(listOf(false, true), seen)
        assertEquals(valid, source.snapshot().settings)
    }

    @Test
    fun `clearing whitespace-only credentials reports blank fields and no ssid`() {
        source.save(valid)
        val outcome = source.update(valid.copy(ssidSuffix = " ", password = " "))
        assertEquals(SaveResult.SAVED_PASSWORD_CHANGED, outcome.result)
        assertEquals("", outcome.snapshot!!.settings.password)
        assertNull(outcome.snapshot.ssid)
        assertFalse(outcome.snapshot.settings.ssidSuffix.isNotEmpty())
    }

    // --- Final fix round: a rename is not a password change ---------------------------------------

    @Test
    fun `a name-only change reports the SSID change, keeps the code, and is not a password change`() {
        val first = source.update(valid).snapshot!!
        val seen = mutableListOf<Boolean>()
        val outcome = source.update(valid.copy(ssidSuffix = "OtherName")) { passwordChanged -> seen += passwordChanged; true }
        assertEquals(SaveResult.SAVED_SSID_CHANGED, outcome.result)
        assertEquals(listOf(false), seen)
        assertEquals(first.ssid!!.removeSuffix("HostPhone") + "OtherName", outcome.snapshot!!.ssid)
        assertEquals("correct horse battery", outcome.snapshot.settings.password)
    }

    @Test
    fun `changing name and password together is a password change`() {
        source.save(valid)
        assertEquals(SaveResult.SAVED_PASSWORD_CHANGED, source.save(valid.copy(ssidSuffix = "OtherName", password = "brand new secret")))
    }

    @Test
    fun `the port rule rejects out-of-range ports and the USB port`() {
        assertNull(HostConfigSource.portError(8080))
        assertNull(HostConfigSource.portError(1024))
        assertNull(HostConfigSource.portError(65535))
        assertNotNull(HostConfigSource.portError(8081))
        assertNotNull(HostConfigSource.portError(1023))
        assertNotNull(HostConfigSource.portError(65536))
    }

    // --- Round 2: unreadable credentials are not "unset" ------------------------------------------

    private class SwitchableBox(private val inner: SecretBox) : SecretBox {
        var failing = false
        override fun seal(plain: ByteArray) = inner.seal(plain)
        override fun open(sealed: ByteArray): ByteArray = if (failing) throw ProviderException("busy") else inner.open(sealed)
    }

    private val switchable = SwitchableBox(AesGcmSecretBox { key })
    private val flakyKv = MapKv()
    private val flakyPrefs = MemoryPrefs()
    private val flaky = HostConfigSource(flakyPrefs, CredentialStore(flakyKv, switchable, Random(7)))

    @Test
    fun `unreadable credentials make sessionConfig throw, never a silent USB-only session`() {
        flaky.save(valid)
        switchable.failing = true
        val e = assertFailsWith<CredentialsUnavailableException> { flaky.sessionConfig() }
        assertFalse("correct horse" in e.message.orEmpty())
        assertTrue(flaky.isCredentialsSet())
    }

    @Test
    fun `an unreadable snapshot says so, and an update then changes nothing`() {
        val before = flaky.update(valid).snapshot!!
        val blob = flakyKv.map.toMap()
        switchable.failing = true

        val snapshot = flaky.snapshot()
        assertTrue(snapshot.credentialsUnreadable)
        assertEquals("", snapshot.settings.password)

        val outcome = flaky.update(valid.copy(maxClients = 2, port = 9090, prefer5Ghz = true))
        assertEquals(SaveOutcome(SaveResult.UNAVAILABLE, null), outcome)
        assertEquals(3, flakyPrefs.maxClients)
        assertEquals(8080, flakyPrefs.wifiPort)
        assertEquals(false, flakyPrefs.prefer5Ghz)
        assertEquals(blob, flakyKv.map)

        switchable.failing = false
        assertEquals(before.ssid, flaky.snapshot().ssid)
        assertFalse(flaky.snapshot().credentialsUnreadable)
    }

    @Test
    fun `isCredentialsSet follows the stored blob without decrypting`() {
        val counting = CountingBox(AesGcmSecretBox { key })
        val kv = MapKv()
        val source = HostConfigSource(MemoryPrefs(), CredentialStore(kv, counting, Random(7)))
        assertFalse(source.isCredentialsSet())
        source.save(valid)
        counting.opens = 0
        assertTrue(source.isCredentialsSet())
        assertEquals(0, counting.opens)
    }
}
