package com.wifiproxy.core.net

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DestinationFilterTest {
    private val filter = DestinationFilter.default(Cidr.parse("192.168.49.0/24"))
    private fun ip(s: String) = InetAddress.getByName(s)

    @Test
    fun `public addresses are allowed`() {
        assertTrue(filter.isAllowed(ip("8.8.8.8")))
        assertTrue(filter.isAllowed(ip("17.57.146.52")))
        assertTrue(filter.isAllowed(ip("2001:4860:4860::8888")))
    }

    @Test
    fun `private, loopback, link-local, cgnat and multicast are blocked`() {
        listOf(
            "127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.1.1",
            "100.64.0.1", "0.0.0.0", "224.0.0.1", "255.255.255.255",
            "::1", "::", "fd00::1", "fe80::1", "ff02::1",
        ).forEach { assertFalse(filter.isAllowed(ip(it)), it) }
    }

    @Test
    fun `p2p subnet is blocked even outside the default list`() {
        val f = DestinationFilter.default(Cidr.parse("203.0.113.0/24"))
        assertFalse(f.isAllowed(ip("203.0.113.7")))
    }

    @Test
    fun `ipv4-mapped ipv6 literal is treated as ipv4`() {
        assertFalse(filter.isAllowed(ip("::ffff:127.0.0.1")))
    }

    @Test
    fun `empty filter allows everything`() {
        assertTrue(DestinationFilter(emptyList()).isAllowed(ip("127.0.0.1")))
    }
}
