package com.wifiproxy.core.egress

import com.wifiproxy.core.io.NioLoop
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DialerTest {
    private val v6a = InetAddress.getByName("2001:db8::1")
    private val v6b = InetAddress.getByName("2001:db8::2")
    private val v4a = InetAddress.getByName("192.0.2.1")
    private val v4b = InetAddress.getByName("192.0.2.2")

    @Test
    fun `order interleaves families starting with ipv6`() {
        assertEquals(listOf(v6a, v4a, v6b, v4b), Dialer.order(listOf(v4a, v4b, v6a, v6b)))
        assertEquals(listOf(v4a, v4b), Dialer.order(listOf(v4a, v4b)))
    }

    @Test
    fun `first success wins without trying later addresses`() = runTest {
        val attempted = mutableListOf<InetAddress>()
        val winner = SocketChannel.open()
        val dialer = Dialer(staggerMs = 250, timeoutMs = 10_000) { addr, _ -> attempted += addr; winner }
        assertSame(winner, dialer.dial(listOf(v4a, v6a), 443))
        assertEquals(listOf(v6a), attempted)
        winner.close()
    }

    @Test
    fun `slow ipv6 loses to ipv4 after the stagger`() = runTest {
        val fast = SocketChannel.open()
        val dialer = Dialer(staggerMs = 250, timeoutMs = 10_000) { addr, _ ->
            if (addr == v6a) { delay(5_000); SocketChannel.open() } else fast
        }
        assertSame(fast, dialer.dial(listOf(v6a, v4a), 443))
        assertEquals(250L, currentTime)
        fast.close()
    }

    @Test
    fun `falls back when the first address fails`() = runTest {
        val ok = SocketChannel.open()
        val dialer = Dialer(staggerMs = 250, timeoutMs = 10_000) { addr, _ ->
            if (addr == v6a) throw ConnectException("refused") else ok
        }
        assertSame(ok, dialer.dial(listOf(v6a, v4a), 443))
        ok.close()
    }

    @Test
    fun `all failures surface as IOException`() = runTest {
        val dialer = Dialer(staggerMs = 250, timeoutMs = 10_000) { _, _ -> throw ConnectException("refused") }
        assertFailsWith<IOException> { dialer.dial(listOf(v6a, v4a), 443) }
    }

    @Test
    fun `overall timeout`() = runTest {
        val dialer = Dialer(staggerMs = 250, timeoutMs = 1_000) { _, _ -> delay(60_000); SocketChannel.open() }
        assertFailsWith<SocketTimeoutException> { dialer.dial(listOf(v4a), 443) }
    }

    @Test
    fun `winner completing at the timeout boundary is closed`() = runTest {
        val ch = SocketChannel.open()
        val dialer = Dialer(staggerMs = 250, timeoutMs = 1_000) { _, _ ->
            withContext(NonCancellable) { delay(1_000) }
            ch
        }
        assertFailsWith<SocketTimeoutException> { dialer.dial(listOf(v4a), 443) }
        assertFalse(ch.isOpen)
    }

    @Test
    fun `loser that connects after the winner is closed`() = runTest {
        val winnerCh = SocketChannel.open()
        val loserCh = SocketChannel.open()
        val dialer = Dialer(staggerMs = 0, timeoutMs = 10_000) { addr, _ ->
            if (addr == v6a) {
                winnerCh
            } else {
                withContext(NonCancellable) { delay(500) }
                loserCh
            }
        }
        assertSame(winnerCh, dialer.dial(listOf(v6a, v4a), 443))
        advanceUntilIdle()
        assertFalse(loserCh.isOpen)
        winnerCh.close()
    }

    @Test
    fun `non-IO failure on one address does not abort the race`() = runTest {
        val ok = SocketChannel.open()
        val dialer = Dialer(staggerMs = 250, timeoutMs = 10_000) { addr, _ ->
            if (addr == v6a) throw IllegalStateException("boom") else ok
        }
        assertSame(ok, dialer.dial(listOf(v6a, v4a), 443))
        ok.close()
    }

    @Test
    fun `all attempts failing keeps every cause`() = runTest {
        val dialer = Dialer(staggerMs = 250, timeoutMs = 10_000) { addr, _ ->
            if (addr == v6a) throw ConnectException("v6 refused") else throw ConnectException("v4 refused")
        }
        val error = assertFailsWith<IOException> { dialer.dial(listOf(v6a, v4a), 443) }
        // kotlinx-coroutines recovers the stack trace by wrapping the exception that crosses the
        // CompletableDeferred.await() suspension boundary in a copy; the copy chains the original
        // as its cause but does not replicate the original's suppressed array. Walk the whole
        // cause chain (and each level's suppressed) so the check isn't tied to nesting depth.
        val messages = mutableListOf<String>()
        var cause: Throwable? = error
        while (cause != null) {
            cause.message?.let(messages::add)
            messages += cause.suppressed.mapNotNull { it.message }
            cause = cause.cause
        }
        assertTrue("v6 refused" in messages)
        assertTrue("v4 refused" in messages)
    }

    @Test
    fun `egress unavailable surfaces as EgressUnavailableException`() = runTest {
        val dialer = Dialer(staggerMs = 250, timeoutMs = 10_000) { _, _ ->
            throw EgressUnavailableException("no network")
        }
        assertFailsWith<EgressUnavailableException> { dialer.dial(listOf(v6a, v4a), 443) }
    }

    @Test
    fun `connects to a real loopback listener`() = runBlocking {
        ServerSocketChannel.open().use { server ->
            server.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
            val port = (server.localAddress as InetSocketAddress).port
            NioLoop().use { loop ->
                Dialer.forEgress(loop, DirectEgress()).dial(listOf(InetAddress.getLoopbackAddress()), port).use {
                    assertTrue(it.isConnected)
                    assertTrue(it.socket().tcpNoDelay)
                }
            }
        }
    }
}
