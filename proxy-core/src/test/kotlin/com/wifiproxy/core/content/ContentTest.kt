package com.wifiproxy.core.content

import com.wifiproxy.core.http.ProxyRequest
import com.wifiproxy.core.http.RequestParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class ContentTest {
    private fun request(line: String) = RequestParser.parse(line) as ProxyRequest.Forward

    @Test
    fun `pac returns a single proxy directive`() {
        assertEquals(
            "function FindProxyForURL(url, host) {\n  return \"PROXY 192.168.49.1:8080\";\n}\n",
            PacFile.render("192.168.49.1", 8080),
        )
    }

    @Test
    fun `mobileconfig contains escaped wifi and auto proxy payload`() {
        val xml = MobileConfig.render("DIRECT-cv-Ben", "p&ss<word>", "http://192.168.49.1:8080/tok.pac", "tok")
        assertTrue(xml.startsWith("<?xml"))
        assertTrue("<key>SSID_STR</key><string>DIRECT-cv-Ben</string>" in xml)
        assertTrue("<key>Password</key><string>p&amp;ss&lt;word&gt;</string>" in xml)
        assertTrue("<key>ProxyType</key><string>Auto</string>" in xml)
        assertTrue("<key>ProxyPACURL</key><string>http://192.168.49.1:8080/tok.pac</string>" in xml)
    }

    @Test
    fun `mobileconfig payload uuids are stable per token`() {
        val a = MobileConfig.render("s", "password1", "http://h/t.pac", "tok")
        assertEquals(a, MobileConfig.render("s", "password1", "http://h/t.pac", "tok"))
        assertNotEquals(a, MobileConfig.render("s", "password1", "http://h/t.pac", "tok2"))
    }

    @Test
    fun `android probes get 204`() {
        assertEquals(204, Probes.match(request("GET http://connectivitycheck.gstatic.com/generate_204 HTTP/1.1"))?.status)
        assertEquals(204, Probes.match(request("GET http://www.google.com/gen_204 HTTP/1.1"))?.status)
    }

    @Test
    fun `apple probe gets the success page`() {
        val r = Probes.match(request("GET http://captive.apple.com/hotspot-detect.html HTTP/1.1"))!!
        assertEquals(200, r.status)
        assertTrue("Success" in r.body.toString(Charsets.UTF_8))
    }

    @Test
    fun `other requests are not probes`() {
        assertNull(Probes.match(request("GET http://example.com/generate_204 HTTP/1.1")))
        assertNull(Probes.match(request("POST http://connectivitycheck.gstatic.com/generate_204 HTTP/1.1")))
    }

    @Test
    fun `head probe has no body`() {
        val r = Probes.match(request("HEAD http://captive.apple.com/hotspot-detect.html HTTP/1.1"))!!
        assertEquals(200, r.status)
        assertEquals(0, r.body.size)
    }

    @Test
    fun `probe on a non-default port is not answered locally`() {
        assertNull(Probes.match(request("GET http://captive.apple.com:8080/hotspot-detect.html HTTP/1.1")))
    }

    @Test
    fun `mobileconfig escapes newlines so trimMargin cannot touch data`() {
        val xml = MobileConfig.render("a\n   |b", "password1", "http://h/t.pac", "tok")
        assertTrue("<string>a&#10;   |b</string>" in xml)
    }

    @Test
    fun `mobileconfig rejects control characters`() {
        assertFailsWith<IllegalArgumentException> { MobileConfig.render("bad\u0001ssid", "password1", "http://h/t.pac", "tok") }
    }
}
