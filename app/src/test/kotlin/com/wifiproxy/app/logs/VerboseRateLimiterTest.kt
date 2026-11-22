package com.wifiproxy.app.logs

import com.wifiproxy.app.logs.VerboseRateLimiter.Companion.SUPPRESSED
import com.wifiproxy.core.ProxyEvent
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals

class VerboseRateLimiterTest {
    private var now = 0L
    private val limiter = VerboseRateLimiter(clock = { now })
    private val a = InetAddress.getByName("192.168.49.12")
    private val b = InetAddress.getByName("192.168.49.13")
    private val refused = ProxyEvent.Refused::class.java
    private val closed = ProxyEvent.TunnelClosed::class.java

    private fun admitted(peer: InetAddress?, type: Class<*>, times: Int) =
        (1..times).count { limiter.admit(peer, type) != SUPPRESSED }

    @Test
    fun `ten lines per peer and type per second, then suppressed`() {
        assertEquals(10, admitted(a, refused, 25))
    }

    @Test
    fun `peers and event types each get their own ten`() {
        assertEquals(10, admitted(a, refused, 20))
        assertEquals(10, admitted(b, refused, 20))
        assertEquals(10, admitted(a, closed, 20))
    }

    @Test
    fun `the first line of the next window reports what was suppressed`() {
        admitted(a, refused, 25)
        now = 1_000
        assertEquals(15, limiter.admit(a, refused))
        assertEquals(0, limiter.admit(a, refused))
    }

    @Test
    fun `a window that hasn't rolled over keeps suppressing`() {
        admitted(a, refused, 10)
        now = 999
        assertEquals(SUPPRESSED, limiter.admit(a, refused))
    }

    @Test
    fun `a global cap of 100 per second across all keys`() {
        val peers = (1..20).map { InetAddress.getByName("192.168.49.${100 + it}") }
        val total = peers.sumOf { admitted(it, refused, 10) }
        assertEquals(100, total)
        now = 1_000
        assertEquals(10, admitted(peers.last(), refused, 10))
    }

    @Test
    fun `off-link peers share one key per type`() {
        assertEquals(10, admitted(null, refused, 50))
    }

    @Test
    fun `beyond the key cap new keys share one overflow bucket`() {
        val small = VerboseRateLimiter(clock = { now }, maxKeys = 2, globalPerWindow = 1_000)
        small.admit(a, refused)
        small.admit(b, refused)
        val others = (1..30).map { InetAddress.getByName("10.0.0.$it") }
        // 30 different keys, but they all share the overflow bucket's ten.
        assertEquals(10, others.count { small.admit(it, refused) != SUPPRESSED })
    }

    @Test
    fun `drain returns everything not yet reported and forgets it`() {
        admitted(a, refused, 15) // 5 suppressed in this window
        now = 1_000
        admitted(b, closed, 12) // a's 5 carried; b's 2 suppressed now
        assertEquals(7, limiter.drain())
        assertEquals(0, limiter.drain())
        // A clean slate: a full ten again, with nothing carried.
        assertEquals(0, limiter.admit(a, refused))
        assertEquals(10, admitted(a, refused, 9) + 1)
    }
}
