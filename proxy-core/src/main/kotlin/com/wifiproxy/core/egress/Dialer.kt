package com.wifiproxy.core.egress

import com.wifiproxy.core.io.NioLoop
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.channels.SelectionKey
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference

/**
 * Happy-eyeballs-style dialer: attempts start [staggerMs] apart in [order], the first success wins
 * and the rest are cancelled. Throws IOException if every attempt fails, SocketTimeoutException
 * after [timeoutMs].
 */
class Dialer(
    private val staggerMs: Long = 250,
    private val timeoutMs: Long = 10_000,
    private val close: (SocketChannel) -> Unit = { runCatching { it.close() } },
    private val connect: suspend (InetAddress, Int) -> SocketChannel,
) {
    suspend fun dial(addresses: List<InetAddress>, port: Int): SocketChannel {
        require(addresses.isNotEmpty()) { "no addresses to dial" }
        val winner = CompletableDeferred<SocketChannel>()
        val winnerChannel = AtomicReference<SocketChannel?>()
        try {
            return withTimeoutOrNull(timeoutMs) { race(order(addresses), port, winner, winnerChannel) }
                ?: throw SocketTimeoutException("connect timed out after ${timeoutMs}ms")
        } catch (t: Throwable) {
            // An attempt can hand the winner a connected channel after withTimeoutOrNull has
            // already given up on (or race() otherwise failed) the result; never leak it.
            winnerChannel.get()?.let(close)
            throw t
        }
    }

    private suspend fun race(
        addresses: List<InetAddress>,
        port: Int,
        winner: CompletableDeferred<SocketChannel>,
        winnerChannel: AtomicReference<SocketChannel?>,
    ): SocketChannel = coroutineScope {
        val causes = ConcurrentLinkedQueue<Throwable>()
        val attempts = addresses.mapIndexed { i, address ->
            launch {
                delay(i * staggerMs)
                val ch = try {
                    connect(address, port)
                } catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    causes.add(e)
                    if (causes.size == addresses.size) {
                        winner.completeExceptionally(aggregateFailures(causes.toList(), addresses.size))
                    }
                    return@launch
                }
                if (winner.complete(ch)) {
                    winnerChannel.set(ch)
                } else {
                    close(ch)
                }
            }
        }
        try {
            winner.await()
        } finally {
            attempts.forEach { it.cancel() }
        }
    }

    companion object {
        fun order(addresses: List<InetAddress>): List<InetAddress> {
            val v6 = addresses.filterIsInstance<Inet6Address>()
            val v4 = addresses.filterIsInstance<Inet4Address>()
            val out = ArrayList<InetAddress>(addresses.size)
            for (i in 0 until maxOf(v6.size, v4.size)) {
                v6.getOrNull(i)?.let(out::add)
                v4.getOrNull(i)?.let(out::add)
            }
            return out
        }

        fun forEgress(loop: NioLoop, egress: Egress, staggerMs: Long = 250, timeoutMs: Long = 10_000) =
            Dialer(staggerMs, timeoutMs, close = loop::closeChannel) { address, port ->
                connectChannel(loop, egress.newChannel(), InetSocketAddress(address, port))
            }
    }
}

/** Builds the exception `dial()` throws once every attempt has failed, keeping every cause. */
private fun aggregateFailures(causes: List<Throwable>, attemptCount: Int): Throwable {
    val first = causes.first()
    if (causes.all { it is EgressUnavailableException }) return first
    val aggregate = IOException("all $attemptCount attempts failed", first)
    causes.drop(1).forEach(aggregate::addSuppressed)
    return aggregate
}

/** Non-blocking connect of an already-bound channel; closes the channel on any failure. */
suspend fun connectChannel(loop: NioLoop, ch: SocketChannel, target: InetSocketAddress): SocketChannel {
    try {
        ch.configureBlocking(false)
        if (!ch.connect(target)) {
            while (!ch.finishConnect()) loop.await(ch, SelectionKey.OP_CONNECT)
        }
        ch.socket().tcpNoDelay = true
        return ch
    } catch (t: Throwable) {
        loop.closeChannel(ch)
        throw t
    }
}
