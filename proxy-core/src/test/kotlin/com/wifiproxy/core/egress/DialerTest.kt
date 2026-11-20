package com.wifiproxy.core.egress

import com.wifiproxy.core.io.NioLoop
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
        assertFailsWith<TimeoutCancellationException> { dialer.dial(listOf(v4a), 443) }
    }

    @Test
    fun `connects to a real loopback listener`() = runBlocking {
        ServerSocketChannel.open().use { server ->
            server.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
            val port = (server.localAddress as InetSocketAddress).port
            NioLoop().use { loop ->
                Dialer.forEgress(loop, DirectEgress()).dial(listOf(InetAddress.getLoopbackAddress()), port).use {
                    assertTrue(it.isConnected)
                }
            }
        }
    }
}
