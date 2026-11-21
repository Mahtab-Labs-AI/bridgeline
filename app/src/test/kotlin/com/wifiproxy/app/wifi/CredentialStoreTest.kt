package com.wifiproxy.app.wifi

import com.wifiproxy.app.settings.KeyValueStore
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import java.util.Random
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class MapStore : KeyValueStore {
    val map = mutableMapOf<String, String>()
    override fun getString(key: String) = map[key]
    override fun putString(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }
}

/** The credentials when [CredentialStore.read] finds them; null when unset or unreadable. Tests
 *  only: production code must tell those two apart. */
private fun CredentialStore.get(): StoredCredentials? = (read() as? CredentialsRead.Present)?.credentials

class CredentialStoreTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private fun box() = AesGcmSecretBox { key }

    @Test
    fun `nothing stored reads as null`() {
        assertNull(CredentialStore(MapStore(), box()).get())
    }

    @Test
    fun `set then get round-trips and the stored value is not plaintext`() {
        val kv = MapStore()
        val saved = CredentialStore(kv, box(), Random(1)).set("HostPhone", "correct horse battery")
        val loaded = CredentialStore(kv, box()).get()
        assertEquals(saved, loaded)
        assertFalse(kv.map.values.single().contains("correct horse"))
    }

    @Test
    fun `changing suffix or password keeps the ssid code`() {
        val store = CredentialStore(MapStore(), box(), Random(1))
        val first = store.set("HostPhone", "password-one")
        val second = store.set("OtherName", "password-two")
        assertEquals(first.ssidCode, second.ssidCode)
        assertEquals("DIRECT-${first.ssidCode}-OtherName", second.ssid)
    }

    @Test
    fun `undecryptable data reads as null and is cleared`() {
        val kv = MapStore()
        CredentialStore(kv, box()).set("HostPhone", "password-one")
        val otherKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertNull(CredentialStore(kv, AesGcmSecretBox { otherKey }).get())
        assertTrue(kv.map.isEmpty())
    }

    /** Opens nothing: every open() throws [error]. */
    private class ThrowingBox(private val error: Exception) : SecretBox {
        override fun seal(plain: ByteArray) = plain
        override fun open(sealed: ByteArray): ByteArray = throw error
    }

    @Test
    fun `a transient keystore error reads as null but keeps the blob, and reports only the class name`() {
        for (error in listOf(GeneralSecurityException("keystore busy at 10.0.0.1"), ProviderException("daemon restarting"))) {
            val kv = MapStore()
            CredentialStore(kv, box()).set("HostPhone", "password-one")
            val blob = kv.map.values.single()
            val reported = mutableListOf<String>()
            assertNull(CredentialStore(kv, ThrowingBox(error), onTransientError = { reported += it }).get())
            assertEquals(blob, kv.map.values.single())
            assertEquals(listOf(error::class.simpleName!!), reported)
            // Once the keystore recovers, the very same credentials are back.
            assertEquals("password-one", CredentialStore(kv, box()).get()?.password)
        }
    }

    @Test
    fun `an unreadable blob reads as Unreadable, never Unset, and still counts as set`() {
        val kv = MapStore()
        val store = CredentialStore(kv, box())
        assertFalse(store.isSet())
        store.set("HostPhone", "password-one")
        val flaky = CredentialStore(kv, ThrowingBox(GeneralSecurityException("busy")))
        assertEquals(CredentialsRead.Unreadable, flaky.read())
        assertTrue(flaky.isSet())
        // set() with the default code refuses rather than pick a new SSID code over the old blob.
        kotlin.test.assertFailsWith<CredentialsUnavailableException> { flaky.set("HostPhone", "password-two") }
        assertEquals("password-one", store.get()?.password)
        assertEquals(CREDENTIALS_UNREADABLE_MESSAGE, CredentialsUnavailableException().message)
    }

    @Test
    fun `a permanently unopenable blob is wiped`() {
        for (error in listOf(AEADBadTagException(), UnrecoverableKeyException(), IllegalArgumentException())) {
            val kv = MapStore()
            CredentialStore(kv, box()).set("HostPhone", "password-one")
            assertNull(CredentialStore(kv, ThrowingBox(error)).get())
            assertTrue(kv.map.isEmpty())
        }
    }

    @Test
    fun `a malformed blob is wiped`() {
        val kv = MapStore().apply { map["wifi_credentials"] = "not base64 !!" }
        assertNull(CredentialStore(kv, box()).get())
        assertTrue(kv.map.isEmpty())
    }

    @Test
    fun `set rejects invalid input`() {
        val store = CredentialStore(MapStore(), box())
        kotlin.test.assertFailsWith<IllegalArgumentException> { store.set("bad name", "password-one") }
        kotlin.test.assertFailsWith<IllegalArgumentException> { store.set("HostPhone", "short") }
    }
}
