package com.wifiproxy.app.wifi

import com.wifiproxy.app.session.ClientPlatform
import com.wifiproxy.app.settings.KeyValueStore
import com.wifiproxy.core.clients.ClientRegistry
import com.wifiproxy.core.clients.SlotRecord
import java.util.Base64

/** A Wi-Fi device slot as persisted between sessions (design spec §4: id, name, token,
 *  createdAt; never the IP binding), plus the platform the invite was made for. */
data class PersistedSlot(val record: SlotRecord, val platform: ClientPlatform)

interface SlotPersistence {
    fun load(): List<PersistedSlot>
    fun save(slots: List<PersistedSlot>)
}

/** One line per slot: `v1|id|base64url(name)|token|createdAtMs|PLATFORM`. Ids are UUIDs and
 *  tokens match [ClientRegistry.TOKEN_PATTERN], so neither can contain `|`; the free-text name is
 *  base64url-encoded. A line that doesn't parse is dropped rather than failing the whole load -
 *  that slot's device just needs a new invite. */
object PersistedSlotsCodec {
    fun encode(slots: List<PersistedSlot>): String = slots.joinToString("\n") { s ->
        val name = Base64.getUrlEncoder().withoutPadding().encodeToString(s.record.name.toByteArray(Charsets.UTF_8))
        listOf("v1", s.record.id, name, s.record.token, s.record.createdAtMs.toString(), s.platform.name).joinToString("|")
    }

    fun decode(text: String?): List<PersistedSlot> =
        text.orEmpty().lineSequence().mapNotNull(::decodeLine).toList()

    private fun decodeLine(line: String): PersistedSlot? {
        val f = line.split('|')
        if (f.size != 6 || f[0] != "v1" || f[1].isEmpty()) return null
        if (!ClientRegistry.TOKEN_PATTERN.matches(f[3])) return null
        val name = runCatching { String(Base64.getUrlDecoder().decode(f[2]), Charsets.UTF_8) }.getOrNull() ?: return null
        val createdAt = f[4].toLongOrNull() ?: return null
        val platform = ClientPlatform.entries.firstOrNull { it.name == f[5] && it != ClientPlatform.LAPTOP } ?: return null
        return PersistedSlot(SlotRecord(f[1], name, f[3], createdAt), platform)
    }
}

/** Tokens are stored in app-private preferences (`allowBackup=false`); they only admit a device
 *  that also knows the Wi-Fi password (spec §4 Layer 1 is the real trust boundary). */
class SlotStore(private val store: KeyValueStore) : SlotPersistence {
    override fun load(): List<PersistedSlot> = PersistedSlotsCodec.decode(store.getString(KEY))
    override fun save(slots: List<PersistedSlot>) = store.putString(KEY, PersistedSlotsCodec.encode(slots))
    fun clear() = store.putString(KEY, null)

    private companion object {
        const val KEY = "wifi_slots"
    }
}
