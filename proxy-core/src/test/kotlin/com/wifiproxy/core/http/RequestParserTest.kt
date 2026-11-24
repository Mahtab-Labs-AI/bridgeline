package com.wifiproxy.core.http

import com.wifiproxy.core.http.ProxyRequest.Connect
import com.wifiproxy.core.http.ProxyRequest.Forward
import com.wifiproxy.core.http.ProxyRequest.Local
import com.wifiproxy.core.http.ProxyRequest.Malformed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RequestParserTest {
    private fun parse(head: String) = RequestParser.parse(head)
    private fun ByteArray.text() = toString(Charsets.ISO_8859_1)

    @Test
    fun `connect with host and port`() {
        assertEquals(Connect("example.com", 443), parse("CONNECT example.com:443 HTTP/1.1\r\nHost: example.com:443"))
    }

    @Test
    fun `connect with bracketed ipv6`() {
        assertEquals(Connect("2001:db8::1", 443), parse("CONNECT [2001:db8::1]:443 HTTP/1.1"))
    }

    @Test
    fun `connect without port is malformed`() {
        assertIs<Malformed>(parse("CONNECT example.com HTTP/1.1"))
    }

    @Test
    fun `connect accepts any destination port, including non-standard ones apps use for raw traffic`() {
        // github.com/Mahtab-Labs-AI/bridgeline/issues/11: IBKR's Android app uses unproxied TCP
        // 4000/4001. The parser has no port allowlist/denylist at all (DestinationFilter only
        // blocks by IP range), so CONNECT to any of these must parse identically.
        for (port in listOf(443, 4000, 4001)) {
            assertEquals(Connect("example.com", port), parse("CONNECT example.com:$port HTTP/1.1\r\nHost: example.com:$port"))
        }
    }

    @Test
    fun `absolute http uri becomes forward`() {
        val r = assertIs<Forward>(parse("GET http://example.com:8081/a/b?q=1 HTTP/1.1\r\nHost: example.com:8081\r\nAccept: */*"))
        assertEquals("GET", r.method)
        assertEquals("example.com", r.host)
        assertEquals(8081, r.port)
        assertEquals("/a/b?q=1", r.target)
        assertEquals("HTTP/1.1", r.version)
    }

    @Test
    fun `absolute uri without path defaults to slash and port 80`() {
        val r = assertIs<Forward>(parse("GET http://example.com HTTP/1.1"))
        assertEquals("/", r.target)
        assertEquals(80, r.port)
    }

    @Test
    fun `origin form path is local and drops the query`() {
        assertEquals(Local("GET", "/abc.pac"), parse("GET /abc.pac?x=1 HTTP/1.1\r\nHost: 192.168.49.1:8080"))
    }

    @Test
    fun `unsupported or broken requests are malformed`() {
        listOf(
            "GET https://example.com/ HTTP/1.1",
            "GET http://user@example.com/ HTTP/1.1",
            "hello",
            "GET / HTTP/2.0",
            "GET /\tHTTP/1.1",
            "GET / HTTP/1.1\r\nNoColonHeader",
            "CONNECT example.com:99999 HTTP/1.1",
            "CONNECT example.com:+80 HTTP/1.1",
        ).forEach { assertIs<Malformed>(parse(it), it) }
    }

    @Test
    fun `upstream head strips hop-by-hop headers and forces close`() {
        val r = parse(
            "POST http://example.com/x HTTP/1.1\r\nHost: example.com\r\nProxy-Connection: keep-alive\r\n" +
                "Proxy-Authorization: Basic abc\r\nConnection: keep-alive, X-Foo\r\nX-Foo: 1\r\nKeep-Alive: 300\r\n" +
                "Content-Length: 3"
        ) as Forward
        assertEquals(
            "POST /x HTTP/1.1\r\nHost: example.com\r\nContent-Length: 3\r\nConnection: close\r\n\r\n",
            r.upstreamHead().text(),
        )
    }

    @Test
    fun `upstream head strips a plain-HTTP (not TLS) websocket upgrade attempt`() {
        // github.com/Mahtab-Labs-AI/bridgeline/issues/11: `upgrade` is hop-by-hop (HOP_BY_HOP
        // above), so a plain ws:// upgrade sent through the Forward path is always stripped and
        // Connection is always forced to close - a plain WebSocket can never establish this way.
        // This does NOT apply to wss://, which almost every real app uses: that goes through
        // ProxyRequest.Connect (a raw byte tunnel, see ProxyServerTest's websocket-over-CONNECT
        // test), not this Forward path, and is unaffected.
        val r = parse(
            "GET http://example.com/chat HTTP/1.1\r\nHost: example.com\r\nUpgrade: websocket\r\n" +
                "Connection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13"
        ) as Forward
        val head = r.upstreamHead().text()
        assertTrue("Upgrade" !in head, head)
        assertTrue("websocket" !in head, head)
        assertTrue(head.endsWith("Connection: close\r\n\r\n"), head)
    }

    @Test
    fun `upstream head adds a missing host and keeps the version`() {
        val r = parse("GET http://example.com:8081/ HTTP/1.0") as Forward
        assertEquals("GET / HTTP/1.0\r\nHost: example.com:8081\r\nConnection: close\r\n\r\n", r.upstreamHead().text())
    }

    @Test
    fun `responses serialize with length and close`() {
        val text = HttpResponse(200, "OK", "text/plain", "hi".toByteArray()).toBytes().text()
        assertEquals(
            "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 2\r\nCache-Control: no-store\r\nConnection: close\r\n\r\nhi",
            text,
        )
        assertTrue(!HttpResponse(204, "No Content").toBytes().text().contains("Content-Length"))
    }

    @Test
    fun `upstream head replaces a mismatched or duplicate host`() {
        val r = parse("GET http://a.com/ HTTP/1.1\r\nHost: b.com\r\nHost: c.com") as Forward
        assertEquals("GET / HTTP/1.1\r\nHost: a.com\r\nConnection: close\r\n\r\n", r.upstreamHead().text())
    }

    @Test
    fun `connection cannot strip framing headers`() {
        val r = parse("POST http://a.com/ HTTP/1.1\r\nConnection: content-length, transfer-encoding\r\nContent-Length: 3") as Forward
        assertTrue(r.upstreamHead().text().contains("Content-Length: 3"))
    }

    @Test
    fun `strict character and version validation`() {
        listOf(
            "GET http://a/ HTTP/1.1\nX: y",  // bare LF
            "GET http://a/ HTTP/1.1\r\nX: a\u0000b",  // NUL in header value
            "GET http://a/ HTTP/1.10",  // HTTP/1.10 not allowed
            "GET http://a/ HTTP/1.1\r\n Folded: x",  // obs-fold (line starts with space)
            "GET http://a/ HTTP/1.1\r\nBad Name: x",  // header name contains space (before colon)
            "GET http://a/ HTTP/1.1\r\n\r\nX: y",  // internal blank line
            "CONNECT [fe80::1%wlan0]:443 HTTP/1.1",  // zone ID
            "CONNECT [example.com]:443 HTTP/1.1",  // non-IPv6 in brackets
        ).forEach { assertIs<Malformed>(parse(it), it) }
    }
}
