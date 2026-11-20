package com.wifiproxy.app.egress

import com.wifiproxy.core.egress.EgressUnavailableException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap

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
 * TTL cache keyed by (network, host).
 *
 * `DnsResolver`'s answer callback doesn't expose the record TTL, so every entry is cached for a
 * fixed [ttlMillis] (60 s by default) rather than honouring the real one.
 */
class DnsResolutionEngine<N : Any>(
    private val query: DnsQuery<N>,
    private val clock: () -> Long = System::currentTimeMillis,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
) {
    private data class CacheKey<N>(val network: N, val host: String)
    private class CacheEntry(val addresses: List<InetAddress>, val expiresAt: Long)

    private val cache = ConcurrentHashMap<CacheKey<N>, CacheEntry>()

    /**
     * Resolves [host] on [network].
     *
     * Throws [EgressUnavailableException] when [network] is `null`, [SocketTimeoutException] on
     * a timeout, [UnknownHostException] when the query succeeds with no addresses, and an
     * [IOException] for any other query failure. Literal addresses are parsed directly and never
     * touch [network] or the cache.
     */
    suspend fun resolve(network: N?, host: String): List<InetAddress> {
        parseIpLiteral(host)?.let { return listOf(it) }
        if (network == null) throw EgressUnavailableException("no cellular network")

        val key = CacheKey(network, host)
        cache[key]?.let { entry ->
            if (entry.expiresAt > clock()) return entry.addresses
            cache.remove(key, entry)
        }

        val addresses = queryAndAwait(network, host)
        cache[key] = CacheEntry(addresses, clock() + ttlMillis)
        return addresses
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
    }
}
