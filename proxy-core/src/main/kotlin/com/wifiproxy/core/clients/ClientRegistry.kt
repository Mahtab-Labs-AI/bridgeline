package com.wifiproxy.core.clients

import java.net.Inet4Address
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
    val pinned: Boolean = false,
    /** Created by [ClientRegistry.admitOrCreate] for an uninvited peer (open admission). */
    val auto: Boolean = false,
    /** The last admission/request ([lastSeenMs]) or [ClientRegistry.addBytes] call for this slot.
     *  Used by [ClientRegistry.idleAutoSlotToEvict]; an open tunnel with no fresh bytes doesn't
     *  count as activity. */
    val lastActivityMs: Long = 0L,
) {
    override fun toString(): String =
        "Slot(id=$id, name=$name, token=<redacted>, boundIp=$boundIp, createdAtMs=$createdAtMs, lastSeenMs=$lastSeenMs, bytesUp=$bytesUp, bytesDown=$bytesDown, activeTunnels=$activeTunnels, pinned=$pinned, auto=$auto, lastActivityMs=$lastActivityMs)"
}

/** The persistent part of a slot. The Android layer stores these and passes them back as `initial`. */
data class SlotRecord(val id: String, val name: String, val token: String, val createdAtMs: Long) {
    override fun toString(): String =
        "SlotRecord(id=$id, name=$name, token=<redacted>, createdAtMs=$createdAtMs)"
}

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
 * Under open admission a listener may also create *auto* slots, identified by their IP alone
 * (see [admitOrCreate]).
 */
class ClientRegistry(
    maxClients: Int = 1,
    private val tunnelCapPerSlot: Int = DEFAULT_TUNNEL_CAP,
    private val tokens: TokenSource = SecureTokenSource(),
    private val clock: () -> Long = System::currentTimeMillis,
    initial: List<SlotRecord> = emptyList(),
) {
    private class Entry(val record: SlotRecord, val pinned: Boolean = false, val auto: Boolean = false) {
        var boundIp: InetAddress? = null
        var lastSeenMs: Long? = null
        var activeTunnels = 0
        val bytesUp = AtomicLong()
        val bytesDown = AtomicLong()
        // Starts at the slot's creation time so a never-touched slot isn't mistaken for one idle
        // since the epoch; addBytes updates it without the lock, as the byte counters already do.
        val lastActivityMs = AtomicLong(record.createdAtMs)

        fun toSlot() = Slot(
            record.id, record.name, record.token, boundIp, record.createdAtMs,
            lastSeenMs, bytesUp.get(), bytesDown.get(), activeTunnels, pinned, auto, lastActivityMs.get(),
        )
    }

    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>()

    @Volatile var maxClients: Int = maxClients
        private set

    init {
        requireValidMax(maxClients)
        require(initial.size <= maxClients) { "${initial.size} slots exceed maxClients $maxClients" }
        initial.forEach {
            require(TOKEN_PATTERN.matches(it.token)) { "restored slot ${it.id} has an invalid token format" }
            entries[it.id] = Entry(it)
        }
    }

    fun addSlot(name: String): Slot? = synchronized(lock) {
        if (entries.size >= maxClients) return null
        val token = tokens.next()
        require(TOKEN_PATTERN.matches(token)) { "generated token has an invalid format" }
        val record = SlotRecord(UUID.randomUUID().toString(), name, token, clock())
        Entry(record).also { entries[record.id] = it }.toSlot()
    }

    /**
     * Creates a slot pinned to [ip]: it needs no token or PAC fetch to be admitted, counts
     * toward [maxClients], and is never persisted (excluded from [snapshot]), cleared by
     * [clearBindings]/[unbind], or rebound by [onPacFetch]. A pinned slot still carries a valid
     * token for format consistency, but the token is never used for admission.
     */
    fun addPinnedSlot(name: String, ip: InetAddress): Slot? = synchronized(lock) {
        if (entries.values.any { it.pinned && it.boundIp == ip }) return null
        // An auto slot on this IP is that same device, so its seat is the one being taken over.
        val autoHere = entries.values.count { it.auto && it.boundIp == ip }
        if (entries.size - autoHere >= maxClients) return null
        detach(ip, keep = null)
        val token = tokens.next()
        require(TOKEN_PATTERN.matches(token)) { "generated token has an invalid format" }
        val record = SlotRecord(UUID.randomUUID().toString(), name, token, clock())
        Entry(record, pinned = true).also {
            it.boundIp = ip
            entries[record.id] = it
        }.toSlot()
    }

    /** Package-internal: revoking must go through `ProxyServer.revoke`, which also cancels the
     *  slot's live tunnels. The Android app is a different Gradle module, so it can't bypass that. */
    internal fun revoke(id: String): Boolean = synchronized(lock) { entries.remove(id) != null }

    /** Returns false (and changes nothing) if more slots are active than [n]; revoke first. */
    fun setMaxClients(n: Int): Boolean = synchronized(lock) {
        requireValidMax(n)
        if (entries.size > n) return false
        maxClients = n
        true
    }

    fun onPacFetch(token: String, ip: InetAddress): Slot? = synchronized(lock) {
        // A pinned slot's IP (and the slot itself) can never be moved by a PAC fetch.
        if (entries.values.any { it.pinned && it.boundIp == ip }) return null
        val presented = token.toByteArray()
        val entry = entries.values.firstOrNull { MessageDigest.isEqual(it.record.token.toByteArray(), presented) }
            ?: return null
        // An auto slot's identity is its IP; its token only exists for format consistency.
        if (entry.pinned || entry.auto) return null
        detach(ip, keep = entry)
        entry.boundIp = ip
        val now = clock()
        entry.lastSeenMs = now
        entry.lastActivityMs.set(now)
        entry.toSlot()
    }

    fun admittedSlot(ip: InetAddress): Slot? = synchronized(lock) {
        val entry = entries.values.firstOrNull { it.boundIp == ip } ?: return null
        val now = clock()
        entry.lastSeenMs = now
        entry.lastActivityMs.set(now)
        entry.toSlot()
    }

    /**
     * Open admission: the slot bound to [ip] if there is one; otherwise, while a seat is free, a
     * new *auto* slot bound to [ip]; otherwise null (full). An auto slot counts toward
     * [maxClients] like any other, so a pinned slot added first keeps its seat. It is never
     * persisted ([snapshot]), never rebound by [onPacFetch], and removed outright - not just
     * unbound - by [clearBindings], by [unbind] for its IP, and whenever another slot takes its
     * IP, because its identity *is* the IP. Like a pinned slot, it carries a valid token that is
     * never used for admission.
     */
    fun admitOrCreate(ip: InetAddress): Slot? = synchronized(lock) {
        entries.values.firstOrNull { it.boundIp == ip }?.let {
            val now = clock()
            it.lastSeenMs = now
            it.lastActivityMs.set(now)
            return it.toSlot()
        }
        if (entries.size >= maxClients) return null
        val token = tokens.next()
        require(TOKEN_PATTERN.matches(token)) { "generated token has an invalid format" }
        val name = if (ip is Inet4Address) "Device ${ip.address[3].toInt() and 0xff}" else "Device"
        val now = clock()
        val record = SlotRecord(UUID.randomUUID().toString(), name, token, now)
        Entry(record, auto = true).also {
            it.boundIp = ip
            it.lastSeenMs = now
            it.lastActivityMs.set(now)
            entries[record.id] = it
        }.toSlot()
    }

    /**
     * Under [lock], the id of the auto slot with the smallest [Slot.lastActivityMs] that is at
     * least [idleMs] old (`lastActivityMs <= clock() - idleMs`), or null if there is none. Pinned
     * and coded slots are never candidates - only an auto slot's seat ever frees itself. An open
     * tunnel with no fresh bytes doesn't keep a slot out of consideration (see
     * [ClientRegistry.addBytes]).
     */
    fun idleAutoSlotToEvict(idleMs: Long): String? = synchronized(lock) {
        val threshold = clock() - idleMs
        // Read each entry's lastActivityMs exactly once: addBytes updates it without the lock, so
        // reading it again for minByOrNull after the filter already read it could rank a slot by
        // a value that isn't the one that qualified it (or vice versa) if a byte arrived in between.
        entries.values.asSequence()
            .filter { it.auto }
            .map { it.record.id to it.lastActivityMs.get() }
            .filter { (_, lastActivityMs) -> lastActivityMs <= threshold }
            .minByOrNull { (_, lastActivityMs) -> lastActivityMs }
            ?.first
    }

    /** Frees [ip] from every non-pinned slot but [keep]: an auto slot is removed, a coded one
     *  unbound. Caller holds [lock]. */
    private fun detach(ip: InetAddress, keep: Entry?) {
        val it = entries.values.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e === keep || e.pinned || e.boundIp != ip) continue
            // Removing it without ProxyServer.revoke is safe: on a teardown the server is already
            // stopped (its tunnels with it), and on a takeover the new slot is the same device.
            if (e.auto) it.remove() else e.boundIp = null
        }
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
        // accumulateAndGet(::maxOf), not set(): two concurrent addBytes calls (or one racing a
        // lastSeenMs update) must never move lastActivityMs backwards, whichever clock() reading
        // happens to land last.
        entry.lastActivityMs.accumulateAndGet(clock(), ::maxOf)
    }

    /**
     * Call when the Wi-Fi Direct group is torn down or recreated, or the password changes.
     * DHCP addresses may be reassigned, and clients re-fetch their PAC on reconnect. Auto slots
     * are removed (their identity is the IP); pinned slots are never touched.
     */
    fun clearBindings() = synchronized(lock) {
        entries.values.removeAll { it.auto }
        entries.values.forEach { if (!it.pinned) it.boundIp = null }
    }

    /** Clear boundIp on the entry bound to that IP, if any, or remove it if it is an auto slot.
     *  Pinned slots are never touched. */
    fun unbind(ip: InetAddress) = synchronized(lock) { detach(ip, keep = null) }

    fun slots(): List<Slot> = synchronized(lock) { entries.values.map { it.toSlot() } }

    /** Pinned and auto slots are never persisted: pinned ones are recreated from app settings,
     *  auto ones by the device's next request. */
    fun snapshot(): List<SlotRecord> = synchronized(lock) {
        entries.values.filterNot { it.pinned || it.auto }.map { it.record }
    }

    companion object {
        const val MAX_CLIENTS_LIMIT = 3
        const val DEFAULT_TUNNEL_CAP = 256

        /** The one token format: 22 base64url characters, as produced by [SecureTokenSource]. */
        val TOKEN_PATTERN = Regex("[A-Za-z0-9_-]{22}")

        private fun requireValidMax(n: Int) =
            require(n in 1..MAX_CLIENTS_LIMIT) { "maxClients must be 1..$MAX_CLIENTS_LIMIT, was $n" }
    }
}
