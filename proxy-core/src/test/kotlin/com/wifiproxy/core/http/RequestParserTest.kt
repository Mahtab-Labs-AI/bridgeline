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
}
