package com.wifiproxy.core.egress

import com.wifiproxy.core.io.NioLoop
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.channels.SelectionKey
import java.nio.channels.SocketChannel
import java.util.concurrent.atomic.AtomicInteger

/**
 * Happy-eyeballs-style dialer: attempts start [staggerMs] apart in [order], the first success wins
 * and the rest are cancelled. Throws IOException if every attempt fails, TimeoutCancellationException
 * after [timeoutMs].
 */
class Dialer(
    private val staggerMs: Long = 250,
    private val timeoutMs: Long = 10_000,
    private val connect: suspend (InetAddress, Int) -> SocketChannel,
) {
    suspend fun dial(addresses: List<InetAddress>, port: Int): SocketChannel =
        withTimeout(timeoutMs) { race(order(addresses), port) }

    private suspend fun race(addresses: List<InetAddress>, port: Int): SocketChannel = coroutineScope {
        require(addresses.isNotEmpty()) { "no addresses to dial" }
        val winner = CompletableDeferred<SocketChannel>()
        val failures = AtomicInteger()
        val attempts = addresses.mapIndexed { i, address ->
            launch {
                delay(i * staggerMs)
                val ch = try {
                    connect(address, port)
                } catch (e: IOException) {
                    if (failures.incrementAndGet() == addresses.size) winner.completeExceptionally(e)
                    return@launch
                }
                if (!winner.complete(ch)) ch.close()
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
            Dialer(staggerMs, timeoutMs) { address, port ->
                connectChannel(loop, egress.newChannel(), InetSocketAddress(address, port))
            }
    }
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
        ch.close()
        throw t
    }
}
