package com.wifiproxy.app.logs

import com.wifiproxy.core.ProxyConfig
import com.wifiproxy.core.ProxyEvent
import com.wifiproxy.core.ProxyServer
import com.wifiproxy.core.WifiCredentials
import com.wifiproxy.core.clients.ClientRegistry
import com.wifiproxy.core.egress.DirectEgress
import com.wifiproxy.core.egress.Egress
import com.wifiproxy.core.net.Cidr
import com.wifiproxy.core.net.DestinationFilter
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Spec §6 end to end: real `ProxyServer` events from requests that carry a recognisable hostname,
 * path and token, run through [formatProxyEvent] - the text that actually reaches the log.
 */
class VerbosePrivacyTest {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val guest = InetAddress.getByName("127.0.0.2")
    private val host = "very-recognisable-host.example"
    private val wifi = WifiCredentials("DIRECT-ab-SecretName", "secret-password-123")
    private val registry = ClientRegistry(maxClients = 3)
    private val events = CopyOnWriteArrayList<ProxyEvent>()
    private val closeables = mutableListOf<AutoCloseable>()
    private lateinit var server: ProxyServer

    @AfterTest
    fun tearDown() {
        closeables.forEach { runCatching { it.close() } }
        if (::server.isInitialized) server.stop()
    }

    private fun request(text: String) {
        try {
            Socket(loopback, server.localPort, guest, 0).use { s ->
                s.soTimeout = 5000
                s.getOutputStream().write(text.toByteArray(Charsets.ISO_8859_1))
                s.getInputStream().readBytes()
            }
        } catch (e: SocketException) {
            // A reset is a refusal too; its event is what matters here.
        }
    }

    @Test
    fun `formatted lines never carry a hostname, path, token, Wi-Fi name or password`() {
        // Only the loopback literal resolves; the recognisable host fails with an exception whose
        // message is that host.
        val egress = object : Egress by DirectEgress() {
            override suspend fun resolve(host: String): List<InetAddress> =
                if (host == "127.0.0.1") listOf(loopback) else throw UnknownHostException(host)
        }
        val config = ProxyConfig(
            bindAddress = loopback, port = 0, peerSubnet = Cidr.parse("127.0.0.0/8"), wifi = wifi, openAdmission = true,
        )
        server = ProxyServer(config, registry, egress, DestinationFilter(emptyList()), onEvent = { events += it })
            .also { it.start() }
        val token = registry.addSlot("secret-device")!!.token

        val echo = ServerSocket(0, 50, loopback)
        closeables += echo
        thread(isDaemon = true) {
            while (!echo.isClosed) {
                val s = try { echo.accept() } catch (e: IOException) { break }
                thread(isDaemon = true) { s.use { runCatching { it.getInputStream().copyTo(it.getOutputStream()) } } }
            }
        }

        request("GET /$token.pac HTTP/1.1\r\nHost: $host\r\n\r\n")
        request("GET /$token.mobileconfig HTTP/1.1\r\nHost: $host\r\n\r\n")
        request("POST /$token.pac HTTP/1.1\r\nHost: $host\r\nContent-Length: 0\r\n\r\n")
        request("GET /secret-path-$token HTTP/1.1\r\nHost: $host\r\n\r\n")
        request("CONNECT $host:443 HTTP/1.1\r\nHost: $host\r\n\r\n")
        request("GET http://$host/path?token=$token HTTP/1.1\r\nHost: $host\r\n\r\n")
        request("GET http://connectivitycheck.gstatic.com/generate_204 HTTP/1.1\r\nHost: connectivitycheck.gstatic.com\r\n\r\n")
        Socket(loopback, server.localPort, guest, 0).use { s ->
            s.soTimeout = 5000
            s.getOutputStream().write("CONNECT 127.0.0.1:${echo.localPort} HTTP/1.1\r\n\r\n".toByteArray())
            val input = s.getInputStream()
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) head.append(input.read().toChar())
            s.getOutputStream().write("ping".toByteArray())
            input.readNBytes(4)
        }
        repeat(150) {
            if (events.any { it is ProxyEvent.TunnelClosed }) return@repeat
            Thread.sleep(20)
        }
        if (events.none { it is ProxyEvent.TunnelClosed }) fail("no TunnelClosed; events=$events")

        // PAC, mobileconfig, 405, 404, two upstream failures, probe, tunnel opened and closed.
        assertTrue(events.size >= 9, events.toString())
        for (event in events) {
            val line = formatProxyEvent(event)
            for (secret in listOf(host, "example", token, "secret", "path", wifi.ssid, wifi.password, "gstatic")) {
                assertFalse(secret in line, "\"$secret\" in: $line")
            }
            assertTrue(line.startsWith("127.0.0.2 "), line)
        }
    }
}
