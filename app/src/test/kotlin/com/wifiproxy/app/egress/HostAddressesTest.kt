package com.wifiproxy.app.egress

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HostAddressesTest {

    @Test
    fun `duplicates are removed`() {
        val a = InetAddress.getByName("10.0.0.5")
        val b = InetAddress.getByName("10.0.0.5")
        val c = InetAddress.getByName("10.0.0.6")

        val cidrs = hostCidrsFrom(listOf(a, b, c))

        assertEquals(listOf("10.0.0.5/32", "10.0.0.6/32"), cidrs.map { it.toString() })
    }

    @Test
    fun `IPv6 scope ids are dropped so the same address on two interfaces collapses to one`() {
        // Numeric scope ids (rather than symbolic names like "%wlan0") parse without requiring a
        // matching real interface to exist on the machine running the test.
        val firstInterface = InetAddress.getByName("fe80::1%1") as Inet6Address
        val secondInterface = InetAddress.getByName("fe80::1%2") as Inet6Address

        val cidrs = hostCidrsFrom(listOf(firstInterface, secondInterface))

        assertEquals(1, cidrs.size)
        val expectedAddress = InetAddress.getByAddress(firstInterface.address).hostAddress
        assertEquals("$expectedAddress/128", cidrs.single().toString())
    }

    @Test
    fun `prefix is 32 for IPv4 and 128 for IPv6`() {
        val v4 = InetAddress.getByName("192.168.1.1")
        val v6 = InetAddress.getByName("2001:db8::1")

        val cidrs = hostCidrsFrom(listOf(v4, v6))

        assertEquals(32, cidrs.first { it.toString().startsWith("192.") }.prefix)
        assertEquals(128, cidrs.first { it.toString().startsWith("2001:") }.prefix)
    }

    @Test
    fun `order of first appearance is preserved`() {
        val first = InetAddress.getByName("10.0.0.2")
        val second = InetAddress.getByName("10.0.0.1")

        val cidrs = hostCidrsFrom(listOf(first, second, first))

        assertEquals(listOf("10.0.0.2/32", "10.0.0.1/32"), cidrs.map { it.toString() })
    }

    @Test
    fun `empty input yields empty output`() {
        assertEquals(emptyList(), hostCidrsFrom(emptyList<InetAddress>()))
    }

    @Test
    fun `result addresses are actually Inet4Address or Inet6Address as appropriate`() {
        val v4 = InetAddress.getByName("172.16.0.1")
        val cidrs = hostCidrsFrom(listOf(v4))
        assertEquals(32, cidrs.single().prefix)
        // Re-parsing via getByAddress must still yield an Inet4Address, not merely drop to a
        // generic InetAddress that `is Inet4Address` would fail to match.
        assertTrue(InetAddress.getByAddress(v4.address) is Inet4Address)
    }
}
