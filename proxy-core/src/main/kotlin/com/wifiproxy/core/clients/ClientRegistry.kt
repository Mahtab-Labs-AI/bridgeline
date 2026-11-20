package com.wifiproxy.core.clients

import java.net.InetAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** Point-in-time view of one client slot. */
data class Slot(
    val id: String,
    val name: String,
    val token: String,
    val boundIp: InetAddress?,
    val createdAtMs: Long,
    val lastSeenMs: Long?,
    val bytesUp: Long,
    val bytesDown: Long,
    val activeTunnels: Int,
)

/** The persistent part of a slot. The Android layer stores these and passes them back as `initial`. */
data class SlotRecord(val id: String, val name: String, val token: String, val createdAtMs: Long)

fun interface TokenSource {
    fun next(): String
}

class SecureTokenSource(private val random: SecureRandom = SecureRandom()) : TokenSource {
    override fun next(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

/**
 * Client slots: each slot is identified by its token, not its IP. Fetching the slot's PAC
 * (or mobileconfig) binds the slot to the fetching IP; only bound IPs may use the proxy.
 */
class ClientRegistry(
    maxClients: Int = 1,
    private val tunnelCapPerSlot: Int = DEFAULT_TUNNEL_CAP,
    private val tokens: TokenSource = SecureTokenSource(),
    private val clock: () -> Long = System::currentTimeMillis,
    initial: List<SlotRecord> = emptyList(),
) {
    private class Entry(val record: SlotRecord) {
        var boundIp: InetAddress? = null
        var lastSeenMs: Long? = null
        var activeTunnels = 0
        val bytesUp = AtomicLong()
        val bytesDown = AtomicLong()

        fun toSlot() = Slot(
            record.id, record.name, record.token, boundIp, record.createdAtMs,
            lastSeenMs, bytesUp.get(), bytesDown.get(), activeTunnels,
        )
    }

    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>()

    var maxClients: Int = maxClients
        private set

    init {
        requireValidMax(maxClients)
        require(initial.size <= maxClients) { "${initial.size} slots exceed maxClients $maxClients" }
        initial.forEach { entries[it.id] = Entry(it) }
    }

    fun addSlot(name: String): Slot? = synchronized(lock) {
        if (entries.size >= maxClients) return null
        val record = SlotRecord(UUID.randomUUID().toString(), name, tokens.next(), clock())
        Entry(record).also { entries[record.id] = it }.toSlot()
    }

    fun revoke(id: String): Boolean = synchronized(lock) { entries.remove(id) != null }

    /** Returns false (and changes nothing) if more slots are active than [n]; revoke first. */
    fun setMaxClients(n: Int): Boolean = synchronized(lock) {
        requireValidMax(n)
        if (entries.size > n) return false
        maxClients = n
        true
    }

    fun onPacFetch(token: String, ip: InetAddress): Slot? = synchronized(lock) {
        val presented = token.toByteArray()
        val entry = entries.values.firstOrNull { MessageDigest.isEqual(it.record.token.toByteArray(), presented) }
            ?: return null
        entries.values.forEach { if (it !== entry && it.boundIp == ip) it.boundIp = null }
        entry.boundIp = ip
        entry.lastSeenMs = clock()
        entry.toSlot()
    }

    fun admittedSlot(ip: InetAddress): Slot? = synchronized(lock) {
        val entry = entries.values.firstOrNull { it.boundIp == ip } ?: return null
        entry.lastSeenMs = clock()
        entry.toSlot()
    }

    fun tryAcquireTunnel(id: String): Boolean = synchronized(lock) {
        val entry = entries[id] ?: return false
        if (entry.activeTunnels >= tunnelCapPerSlot) return false
        entry.activeTunnels++
        true
    }

    fun releaseTunnel(id: String) {
        synchronized(lock) {
            val entry = entries[id] ?: return
            if (entry.activeTunnels > 0) entry.activeTunnels--
        }
    }

    fun addBytes(id: String, up: Long, down: Long) {
        val entry = synchronized(lock) { entries[id] } ?: return
        entry.bytesUp.addAndGet(up)
        entry.bytesDown.addAndGet(down)
    }

    fun slots(): List<Slot> = synchronized(lock) { entries.values.map { it.toSlot() } }

    fun snapshot(): List<SlotRecord> = synchronized(lock) { entries.values.map { it.record } }

    companion object {
        const val MAX_CLIENTS_LIMIT = 3
        const val DEFAULT_TUNNEL_CAP = 256

        private fun requireValidMax(n: Int) =
            require(n in 1..MAX_CLIENTS_LIMIT) { "maxClients must be 1..$MAX_CLIENTS_LIMIT, was $n" }
    }
}
