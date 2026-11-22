package com.wifiproxy.app.logs

import com.wifiproxy.app.wifi.LinkState
import com.wifiproxy.core.ProxyEvent
import com.wifiproxy.core.WifiCredentials
import com.wifiproxy.core.io.TunnelEnd
import java.net.Inet4Address
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VerboseFormatTest {
    private val peer = InetAddress.getByName("192.168.49.87")

    @Test
    fun `a closed tunnel reads like the spec's example`() {
        val event = ProxyEvent.TunnelClosed(peer, 443, ProxyEvent.TunnelKind.CONNECT, 1229, 49_152, 3_100, TunnelEnd.BOTH_EOF)
        assertEquals("192.168.49.87 CONNECT :443 closed (BOTH_EOF) ↑1.2 KB ↓48.0 KB 3.1 s", formatProxyEvent(event))
    }

    @Test
    fun `a cancelled tunnel says so`() {
        val event = ProxyEvent.TunnelClosed(peer, 80, ProxyEvent.TunnelKind.FORWARD, 10, 20, 50, null)
        assertEquals("192.168.49.87 FORWARD :80 closed (CANCELLED) ↑10 B ↓20 B 0.1 s", formatProxyEvent(event))
    }

    @Test
    fun `a tunnel cut off by an I-O error says so, not CANCELLED`() {
        val event = ProxyEvent.TunnelClosed(
            peer, 443, ProxyEvent.TunnelKind.CONNECT, 0, 0, 10, null, ProxyEvent.TunnelAbort.IO_ERROR,
        )
        assertEquals("192.168.49.87 CONNECT :443 closed (IO_ERROR) ↑0 B ↓0 B 0.0 s", formatProxyEvent(event))
    }

    @Test
    fun `an off-link peer is never printed as an address`() {
        assertEquals(
            "off-link peer refused: too many connections",
            formatProxyEvent(ProxyEvent.Refused(null, ProxyEvent.RefusalReason.CONNECTION_CAP)),
        )
    }

    @Test
    fun `suppressed counts are appended only when there are some`() {
        assertEquals("x", withSuppressed("x", 0))
        assertEquals("x (4 similar suppressed)", withSuppressed("x", 4))
    }

    @Test
    fun `every other event formats to one line`() {
        val p = InetAddress.getByName("192.168.49.12")
        assertEquals("192.168.49.12 refused: registry full", formatProxyEvent(ProxyEvent.Refused(p, ProxyEvent.RefusalReason.REGISTRY_FULL)))
        assertEquals("192.168.49.12 served proxy setup (PAC)", formatProxyEvent(ProxyEvent.LocalServed(p, ProxyEvent.LocalKind.PAC)))
        assertEquals("192.168.49.12 CONNECT :443 opened", formatProxyEvent(ProxyEvent.TunnelOpened(p, 443, ProxyEvent.TunnelKind.CONNECT)))
        assertEquals(
            "192.168.49.12 :443 upstream failed: DNS (UnknownHostException)",
            formatProxyEvent(ProxyEvent.UpstreamFailed(p, 443, ProxyEvent.UpstreamFailure.DNS, "UnknownHostException")),
        )
        assertEquals("192.168.49.12 took the seat of an idle device", formatProxyEvent(ProxyEvent.Evicted(p)))
    }

    @Test
    fun `every refusal reason has its own text`() {
        val texts = ProxyEvent.RefusalReason.entries.map { formatProxyEvent(ProxyEvent.Refused(peer, it)) }
        assertEquals(texts.size, texts.toSet().size)
    }

    @Test
    fun `a peer address that cached a hostname still prints only the address`() {
        val named = InetAddress.getByAddress("someones-laptop.example", byteArrayOf(192.toByte(), 168.toByte(), 49, 5))
        val line = formatProxyEvent(ProxyEvent.Evicted(named))
        assertFalse("example" in line, line)
        assertTrue(line.startsWith("192.168.49.5 "), line)
    }

    @Test
    fun `a link Up names the group owner, never the Wi-Fi name or password`() {
        val up = LinkState.Up(
            InetAddress.getByName("192.168.49.1") as Inet4Address,
            WifiCredentials("DIRECT-k3-HostPhone", "correct horse battery"),
        )
        val text = describeLink(up)
        assertEquals("up (group owner 192.168.49.1)", text)
        assertEquals("down: Wi-Fi is off", describeLink(LinkState.Down("Wi-Fi is off")))
        assertEquals("idle", describeLink(LinkState.Idle))
        assertEquals("failed: nope", describeLink(LinkState.Failed("nope")))
    }

    @Test
    fun `seconds always use a dot`() {
        assertEquals("0.0 s", formatSeconds(0))
        assertEquals("12.3 s", formatSeconds(12_345))
    }

    @Test
    fun `the export keeps every line when it fits`() {
        assertEquals("H\na\nb", buildExport("H", listOf("a", "b")))
    }

    @Test
    fun `an oversized export drops the oldest lines and says how many`() {
        val lines = listOf("newest", "middle", "oldest")
        val text = buildExport("H", lines, maxChars = 16)
        assertEquals("H\nnewest\nmiddle\n… 1 older lines not included (too long to share)", text)
    }
}
