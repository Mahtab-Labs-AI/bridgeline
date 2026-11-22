package com.wifiproxy.app.logs

import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Caps verbose proxy-event lines so a flood (a peer hammering the caps, say) can't push
 * everything else out of the log: at most [perKeyPerWindow] lines per (peer, event type) and
 * [globalPerWindow] in total per [windowMs]. A suppressed event costs a clock read, one map
 * lookup and two increments - [admit] is called before any formatting.
 *
 * Lock-free; counts are approximate under races, which is fine for diagnostics. At most
 * [maxKeys] keys get a bucket of their own; any further key shares one overflow bucket.
 */
class VerboseRateLimiter(
    private val clock: () -> Long,
    private val perKeyPerWindow: Int = 10,
    private val globalPerWindow: Int = 100,
    private val windowMs: Long = 1_000L,
    private val maxKeys: Int = 512,
) {
    private data class Key(val peer: InetAddress?, val type: Class<*>)

    private class Bucket(start: Long) {
        val windowStart = AtomicLong(start)
        val count = AtomicInteger()

        /** Suppressed in the current window. */
        val suppressed = AtomicInteger()

        /** Suppressed in earlier windows and not yet reported on an admitted line. */
        val carried = AtomicInteger()
    }

    private val buckets = ConcurrentHashMap<Key, Bucket>()
    private val overflow = Bucket(Long.MIN_VALUE)
    private val global = Bucket(Long.MIN_VALUE)

    /**
     * Whether an event of [type] about [peer] may be logged now: [SUPPRESSED] if not (it is
     * counted), else how many similar events were suppressed before it and not yet reported -
     * the admitted line should mention them.
     */
    fun admit(peer: InetAddress?, type: Class<*>): Int {
        val now = clock()
        val key = Key(peer, type)
        val bucket = buckets[key]
            ?: if (buckets.size < maxKeys) buckets.computeIfAbsent(key) { Bucket(now) } else overflow
        roll(bucket, now)
        roll(global, now)
        if (bucket.count.incrementAndGet() > perKeyPerWindow || global.count.incrementAndGet() > globalPerWindow) {
            bucket.suppressed.incrementAndGet()
            return SUPPRESSED
        }
        return bucket.carried.getAndSet(0)
    }

    /** Every suppressed event not yet reported, then forgets all keys (verbose turned off). */
    fun drain(): Int {
        var total = 0
        for (bucket in buckets.values + overflow) {
            total += bucket.suppressed.getAndSet(0) + bucket.carried.getAndSet(0)
            bucket.count.set(0)
        }
        buckets.clear()
        global.count.set(0)
        return total
    }

    /** Starts a new window once the current one is over; only the CAS winner resets it. */
    private fun roll(bucket: Bucket, now: Long) {
        val start = bucket.windowStart.get()
        if (start != Long.MIN_VALUE && now - start < windowMs) return
        if (!bucket.windowStart.compareAndSet(start, now)) return
        bucket.carried.addAndGet(bucket.suppressed.getAndSet(0))
        bucket.count.set(0)
    }

    companion object {
        const val SUPPRESSED = -1
    }
}
