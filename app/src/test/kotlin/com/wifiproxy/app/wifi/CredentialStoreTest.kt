package com.wifiproxy.app.wifi

import com.wifiproxy.app.settings.KeyValueStore
import java.util.Random
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

    @Test
    fun `set rejects invalid input`() {
        val store = CredentialStore(MapStore(), box())
        kotlin.test.assertFailsWith<IllegalArgumentException> { store.set("bad name", "password-one") }
        kotlin.test.assertFailsWith<IllegalArgumentException> { store.set("HostPhone", "short") }
    }
}
