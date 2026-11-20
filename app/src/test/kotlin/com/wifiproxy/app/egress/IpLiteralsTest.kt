package com.wifiproxy.app.egress

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [strictParseIpLiteral] is a pure, hand-rolled parser (I6): it must never call
 * [InetAddress.getByName] itself, so these assertions only ever go through [InetAddress.getByAddress]
 * (which never touches the network) for the inputs that are genuinely numeric literals, and return
 * `null` without calling into `InetAddress` at all for everything else - in particular the three
 * malformed-but-literal-shaped inputs the design review called out: `999.1.1.1`, `010.1.1.1` and
 * `foo:bar`, which `InetAddress.getByName` would otherwise hand off to a blocking resolver.
 */
class IpLiteralsTest {

    @Test
    fun `accepts valid IPv4 literals`() {
        assertEquals(InetAddress.getByName("127.0.0.1"), strictParseIpLiteral("127.0.0.1"))
        assertEquals(InetAddress.getByName("0.0.0.0"), strictParseIpLiteral("0.0.0.0"))
        assertEquals(InetAddress.getByName("255.255.255.255"), strictParseIpLiteral("255.255.255.255"))
        assertEquals(InetAddress.getByName("1.2.3.4"), strictParseIpLiteral("1.2.3.4"))
    }

    @Test
    fun `accepts valid IPv6 literals`() {
        assertEquals(InetAddress.getByName("::1"), strictParseIpLiteral("::1"))
        assertEquals(InetAddress.getByName("::"), strictParseIpLiteral("::"))
        assertEquals(InetAddress.getByName("2001:db8::1"), strictParseIpLiteral("2001:db8::1"))
        assertEquals(
            InetAddress.getByName("fe80::1"),
            strictParseIpLiteral("fe80::1%eth0"),
            "a zone id is stripped, not treated as part of the address",
        )
        assertEquals(
            InetAddress.getByName("::1"),
            strictParseIpLiteral("[::1]"),
            "bracketed literals are unwrapped",
        )
        assertEquals(
            InetAddress.getByName("1:2:3:4:5:6:7:8"),
            strictParseIpLiteral("1:2:3:4:5:6:7:8"),
        )
    }

    @Test
    fun `rejects out-of-range and ambiguous-looking IPv4 shapes`() {
        assertNull(strictParseIpLiteral("999.1.1.1"), "999 is out of range for an octet")
        assertNull(strictParseIpLiteral("010.1.1.1"), "a leading zero is ambiguous (octal-looking)")
        assertNull(strictParseIpLiteral("1.2.3"))
        assertNull(strictParseIpLiteral("1.2.3.4.5"))
    }

    @Test
    fun `rejects malformed IPv6-shaped input`() {
        assertNull(strictParseIpLiteral("foo:bar"))
        assertNull(strictParseIpLiteral("1:2:3::4:5::6"), "more than one :: is never legal")
        assertNull(strictParseIpLiteral("1:2:3:4:5:6:7:8:9"), "too many groups")
    }

    @Test
    fun `rejects real hostnames and blanks`() {
        assertNull(strictParseIpLiteral("example.com"))
        assertNull(strictParseIpLiteral(""))
        assertNull(strictParseIpLiteral("localhost"))
    }
}
