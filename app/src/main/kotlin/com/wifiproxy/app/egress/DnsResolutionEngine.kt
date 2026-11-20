package com.wifiproxy.app.egress

import com.wifiproxy.core.egress.EgressUnavailableException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** One answer delivered by a [DnsQuery]: a (possibly multi-address) success, or a failure. */
sealed interface DnsOutcome {
    data class Answer(val addresses: List<InetAddress>) : DnsOutcome
    data class Failure(val cause: Throwable) : DnsOutcome
}

/**
 * Seam over `android.net.DnsResolver.query`, parameterised over the opaque network handle [N]
 * (`android.net.Network` in production, a plain fake in tests) so [DnsResolutionEngine] can run
 * on the JVM without Android.
 *
 * An implementation must call [onResult] exactly once for a query it does not cancel first, and
 * never after its own returned cancel hook has been invoked. The returned function cancels the
 * in-flight query — in production it forwards to `CancellationSignal.cancel()` — and must be
 * safe to call more than once.
 */
fun interface DnsQuery<N : Any> {
    fun query(network: N, host: String, onResult: (DnsOutcome) -> Unit): () -> Unit
}

/**
 * Resolves hostnames one network at a time: an IP-literal short-circuit, a 5 s timeout that
 * surfaces as [SocketTimeoutException] (never a coroutine `TimeoutCancellationException`), and a
 * TTL cache keyed by (network, host), bounded to [maxCacheEntries] entries (I6, M2).
 *
 * `DnsResolver`'s answer callback doesn't expose the record TTL, so every entry is cached for a
 * fixed [ttlMillis] (60 s by default) rather than honouring the real one.
 *
 * [parseLiteral] decides what counts as an IP literal (and is handed [host] without ever touching
 * [network] or the cache): it defaults to [strictParseIpLiteral], a pure JVM-only parser, so plain
 * unit tests never depend on Android. [com.wifiproxy.app.egress.CellularEgress] overrides it in
 * production with `android.net.InetAddresses.isNumericAddress`/`parseNumericAddress` (I6).
 */
class DnsResolutionEngine<N : Any>(
    private val query: DnsQuery<N>,
    private val clock: () -> Long = System::currentTimeMillis,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val maxCacheEntries: Int = DEFAULT_MAX_CACHE_ENTRIES,
    private val parseLiteral: (String) -> InetAddress? = ::strictParseIpLiteral,
) {
    private data class CacheKey<N>(val network: N, val host: String)
    private class CacheEntry(val addresses: List<InetAddress>, val expiresAt: Long)

    // (M-a) A LinkedHashMap in access order, so the comment on [recordCacheEntry] is actually true:
    // a ConcurrentHashMap has no notion of access or insertion order at all, so the previous
    // "sweep expired, then drop the overflow" fallback evicted in whatever order the map's internal
    // hash bucket iteration happened to produce - not oldest-first, despite the comment. LinkedHashMap
    // isn't thread-safe on its own, so every access below goes through [cacheLock]; `accessOrder =
    // true` plus the [removeEldestEntry] override turns a plain `put`/`get` into true LRU eviction.
    private val cacheLock = Any()
    private val cache = object : LinkedHashMap<CacheKey<N>, CacheEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey<N>, CacheEntry>): Boolean =
            size > maxCacheEntries
    }

    /** Test-only visibility into the cache's current size, to assert [maxCacheEntries] is honoured. */
    internal val cacheSizeForTesting: Int get() = synchronized(cacheLock) { cache.size }

    /**
     * Resolves [host] on [network].
     *
     * Throws [EgressUnavailableException] when [network] is `null`, [SocketTimeoutException] on
     * a timeout, [UnknownHostException] when the query succeeds with no addresses, and an
     * [IOException] for any other query failure. Literal addresses are parsed directly and never
     * touch [network] or the cache.
     */
    suspend fun resolve(network: N?, host: String): List<InetAddress> {
        parseLiteral(host)?.let { return listOf(it) }
        if (network == null) throw EgressUnavailableException("no cellular network")

        val key = CacheKey(network, host)
        synchronized(cacheLock) {
            cache[key]?.let { entry ->
                if (entry.expiresAt > clock()) return entry.addresses
                cache.remove(key)
            }
        }

        val addresses = queryAndAwait(network, host)
        recordCacheEntry(key, CacheEntry(addresses, clock() + ttlMillis))
        return addresses
    }

    /** Bounds the cache to [maxCacheEntries] (M2, M-a): [cache] is a `LinkedHashMap` in access
     *  order with [LinkedHashMap.removeEldestEntry] overridden above to cap at [maxCacheEntries],
     *  so inserting past the cap evicts the true least-recently-used entry - oldest by last
     *  read-or-write, exactly as the eviction is documented to behave - rather than an arbitrary
     *  hash-bucket order. */
    private fun recordCacheEntry(key: CacheKey<N>, entry: CacheEntry) {
        synchronized(cacheLock) { cache[key] = entry }
    }

    private suspend fun queryAndAwait(network: N, host: String): List<InetAddress> {
        val result = withTimeoutOrNull(timeoutMillis) {
            suspendCancellableCoroutine<List<InetAddress>> { continuation ->
                // Register the cancel hook before issuing the query, so a query implementation
                // that answers synchronously can never race ahead of cancellation wiring.
                var cancel: (() -> Unit)? = null
                continuation.invokeOnCancellation { cancel?.invoke() }
                cancel = query.query(network, host) { outcome ->
                    // Goes through the one method every Continuation must implement, rather than
                    // a resume()/resumeWithException() convenience overload, so this isn't
                    // sensitive to which of those happen to be available on CancellableContinuation
                    // in a given coroutines version.
                    when (outcome) {
                        is DnsOutcome.Answer -> continuation.resumeWith(Result.success(outcome.addresses))
                        is DnsOutcome.Failure -> continuation.resumeWith(
                            Result.failure(outcome.cause as? IOException ?: IOException("DNS query failed", outcome.cause)),
                        )
                    }
                }
            }
        } ?: throw SocketTimeoutException("DNS query timed out after ${timeoutMillis}ms")
        if (result.isEmpty()) throw UnknownHostException(host)
        return result
    }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 5_000L
        const val DEFAULT_TTL_MILLIS = 60_000L
        const val DEFAULT_MAX_CACHE_ENTRIES = 512
    }
}
