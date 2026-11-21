package com.wifiproxy.app.wifi

import com.wifiproxy.app.session.ClientPlatform
import com.wifiproxy.app.settings.KeyValueStore
import com.wifiproxy.core.clients.SlotRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SlotStoreTest {
    private val iphone = PersistedSlot(
        SlotRecord(id = "0b9f2c1e-0000-4000-8000-000000000001", name = "Ben's iPhone | 2", token = "A".repeat(22), createdAtMs = 1_700_000_000_000),
        ClientPlatform.IPHONE,
    )
    private val pixel = PersistedSlot(
        SlotRecord(id = "0b9f2c1e-0000-4000-8000-000000000002", name = "Pixel 7\nline", token = "b_-".repeat(7) + "c", createdAtMs = 5),
        ClientPlatform.ANDROID,
    )

    @Test
    fun `encode then decode round-trips names with separators and newlines`() {
        assertEquals(listOf(iphone, pixel), PersistedSlotsCodec.decode(PersistedSlotsCodec.encode(listOf(iphone, pixel))))
    }

    @Test
    fun `null or empty decodes to nothing`() {
        assertTrue(PersistedSlotsCodec.decode(null).isEmpty())
        assertTrue(PersistedSlotsCodec.decode("").isEmpty())
    }

    @Test
    fun `malformed lines and invalid tokens are skipped, not fatal`() {
        val good = PersistedSlotsCodec.encode(listOf(iphone))
        val text = listOf("garbage", "v1|id|bmFtZQ|short-token|5|ANDROID", "v1|id|!!!|${"A".repeat(22)}|5|ANDROID", good, "v1|id|bmFtZQ|${"A".repeat(22)}|5|TOASTER").joinToString("\n")
        assertEquals(listOf(iphone), PersistedSlotsCodec.decode(text))
    }

    @Test
    fun `laptop and unknown (auto) platforms are never loaded`() {
        val text = listOf("LAPTOP", "UNKNOWN").joinToString("\n") { "v1|id-$it|bmFtZQ|${"A".repeat(22)}|5|$it" }
        assertTrue(PersistedSlotsCodec.decode(text).isEmpty())
    }

    @Test
    fun `store saves, loads and clears`() {
        val kv = object : KeyValueStore {
            var value: String? = null
            override fun getString(key: String) = value
            override fun putString(key: String, value: String?) { this.value = value }
        }
        val store = SlotStore(kv)
        store.save(listOf(pixel))
        assertEquals(listOf(pixel), SlotStore(kv).load())
        store.clear()
        assertTrue(store.load().isEmpty())
    }
}
