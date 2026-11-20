package com.wifiproxy.core.content

import com.wifiproxy.core.ProxyConfig
import com.wifiproxy.core.WifiCredentials
import com.wifiproxy.core.clients.ClientRegistry
import com.wifiproxy.core.http.ProxyRequest.Local
import com.wifiproxy.core.net.Cidr
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalContentTest {
    private val registry = ClientRegistry(maxClients = 3)
    private val peer = InetAddress.getByName("192.168.49.10")
    private fun content(wifi: WifiCredentials? = null) = LocalContent(registry, "192.168.49.1", 8080, wifi)

    @Test
    fun `pac for a valid token admits the peer`() {
        val slot = registry.addSlot("a")!!
        val r = content().serve(Local("GET", "/${slot.token}.pac"), peer)
        assertEquals(200, r.status)
        assertEquals(PacFile.CONTENT_TYPE, r.contentType)
        assertTrue("PROXY 192.168.49.1:8080" in r.body.toString(Charsets.UTF_8))
        assertEquals(slot.id, registry.admittedSlot(peer)?.id)
    }

    @Test
    fun `unknown token is 404 and admits nobody`() {
        registry.addSlot("a")
        assertEquals(404, content().serve(Local("GET", "/AAAAAAAAAAAAAAAAAAAAAA.pac"), peer).status)
        assertNull(registry.admittedSlot(peer))
    }

    @Test
    fun `non-token paths are 404`() {
        listOf("/", "/favicon.ico", "/short.pac").forEach {
            assertEquals(404, content().serve(Local("GET", it), peer).status, it)
        }
    }

    @Test
    fun `non-GET is 405`() {
        val slot = registry.addSlot("a")!!
        assertEquals(405, content().serve(Local("POST", "/${slot.token}.pac"), peer).status)
        assertNull(registry.admittedSlot(peer))
    }

    @Test
    fun `mobileconfig requires wifi credentials`() {
        val slot = registry.addSlot("a")!!
        assertEquals(404, content(wifi = null).serve(Local("GET", "/${slot.token}.mobileconfig"), peer).status)
        assertNull(registry.admittedSlot(peer))

        val r = content(WifiCredentials("DIRECT-ab-Test", "password1")).serve(Local("GET", "/${slot.token}.mobileconfig"), peer)
        assertEquals(200, r.status)
        assertEquals(MobileConfig.CONTENT_TYPE, r.contentType)
        assertTrue("http://192.168.49.1:8080/${slot.token}.pac" in r.body.toString(Charsets.UTF_8))
        assertEquals(slot.id, registry.admittedSlot(peer)?.id)
    }

    @Test
    fun `wifi credentials never print the password`() {
        assertFalse("password1" in WifiCredentials("s", "password1").toString())
    }

    @Test
    fun `config refuses a wildcard bind address`() {
        assertFailsWith<IllegalArgumentException> {
            ProxyConfig(bindAddress = InetAddress.getByName("0.0.0.0"), peerSubnet = Cidr.parse("192.168.49.0/24"))
        }
        assertFailsWith<IllegalArgumentException> {
            ProxyConfig(bindAddress = InetAddress.getByName("::"), peerSubnet = Cidr.parse("192.168.49.0/24"))
        }
    }

    @Test
    fun `config requires an IPv4 bind address`() {
        assertFailsWith<IllegalArgumentException> {
            ProxyConfig(bindAddress = InetAddress.getByName("fe80::1"), peerSubnet = Cidr.parse("192.168.49.0/24"))
        }
    }

    @Test
    fun `config rejects non-positive timeouts`() {
        val bindAddress = InetAddress.getByName("192.168.49.1")
        val peerSubnet = Cidr.parse("192.168.49.0/24")
        assertFailsWith<IllegalArgumentException> {
            ProxyConfig(bindAddress = bindAddress, peerSubnet = peerSubnet, idleTimeoutMs = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            ProxyConfig(bindAddress = bindAddress, peerSubnet = peerSubnet, pushIdleTimeoutMs = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            ProxyConfig(bindAddress = bindAddress, peerSubnet = peerSubnet, headTimeoutMs = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            ProxyConfig(bindAddress = bindAddress, peerSubnet = peerSubnet, connectTimeoutMs = 0)
        }
    }

    @Test
    fun `config toString never prints the wifi password`() {
        val config = ProxyConfig(
            bindAddress = InetAddress.getByName("192.168.49.1"),
            peerSubnet = Cidr.parse("192.168.49.0/24"),
            wifi = WifiCredentials("s", "password1"),
        )
        assertFalse("password1" in config.toString())
    }
}
