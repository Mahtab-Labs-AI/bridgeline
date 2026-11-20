package com.wifiproxy.core.net

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CidrTest {
    private fun ip(s: String) = InetAddress.getByName(s)

    @Test
    fun `ipv4 prefix match`() {
        val c = Cidr.parse("192.168.49.0/24")
        assertTrue(c.contains(ip("192.168.49.1")))
        assertTrue(c.contains(ip("192.168.49.255")))
        assertFalse(c.contains(ip("192.168.50.1")))
    }

    @Test
    fun `non byte aligned prefix`() {
        val c = Cidr.parse("100.64.0.0/10")
        assertTrue(c.contains(ip("100.127.255.255")))
        assertFalse(c.contains(ip("100.128.0.0")))
    }

    @Test
    fun `ipv6 prefix match`() {
        val c = Cidr.parse("fe80::/10")
        assertTrue(c.contains(ip("fe80::1")))
        assertTrue(c.contains(ip("febf::1")))
        assertFalse(c.contains(ip("fec0::1")))
    }

    @Test
    fun `families never match each other`() {
        assertFalse(Cidr.parse("0.0.0.0/0").contains(ip("::1")))
        assertFalse(Cidr.parse("::/0").contains(ip("127.0.0.1")))
    }

    @Test
    fun `rejects prefix longer than address`() {
        assertFailsWith<IllegalArgumentException> { Cidr.parse("10.0.0.0/33") }
    }

    @Test
    fun `rejects missing prefix`() {
        assertFailsWith<IllegalArgumentException> { Cidr.parse("10.0.0.0") }
    }

    @Test
    fun `rejects hostnames`() {
        assertFailsWith<IllegalArgumentException> { Cidr.parse("localhost/8") }
    }
}
