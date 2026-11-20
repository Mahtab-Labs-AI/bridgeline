package com.wifiproxy.core

import com.wifiproxy.core.clients.ClientRegistry
import com.wifiproxy.core.clients.Slot
import com.wifiproxy.core.egress.DirectEgress
import com.wifiproxy.core.egress.Egress
import com.wifiproxy.core.egress.EgressUnavailableException
import com.wifiproxy.core.net.Cidr
import com.wifiproxy.core.net.DestinationFilter
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.UnknownHostException
import java.nio.channels.SocketChannel
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class ProxyServerTest {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private var registry = ClientRegistry(maxClients = 3)
    private lateinit var server: ProxyServer
    private val closeables = mutableListOf<AutoCloseable>()

    private fun start(
        egress: Egress = DirectEgress(),
        filter: DestinationFilter = DestinationFilter(emptyList()),
        idleMs: Long = 60_000,
        wifi: WifiCredentials? = null,
        peerSubnet: Cidr = Cidr.parse("127.0.0.0/8"),
        onError: (Throwable) -> Unit = {},
    ) {
        val config = ProxyConfig(bindAddress = loopback, port = 0, peerSubnet = peerSubnet, wifi = wifi, idleTimeoutMs = idleMs)
        server = ProxyServer(config, registry, egress, filter, onError).also { it.start() }
    }

    @AfterTest
    fun tearDown() {
        closeables.forEach { runCatching { it.close() } }
        if (::server.isInitialized) server.stop()
    }

    private fun rawRequest(text: String): String = Socket(loopback, server.localPort).use { s ->
        s.soTimeout = 5000
        s.getOutputStream().write(text.toByteArray(Charsets.ISO_8859_1))
        s.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
    }

    private fun admit(): Slot {
        val slot = registry.addSlot("test")!!
        val response = rawRequest("GET /${slot.token}.pac HTTP/1.1\r\nHost: x\r\n\r\n")
        assertTrue(response.startsWith("HTTP/1.1 200"), response)
        return slot
    }

    private fun startEcho(): ServerSocket {
        val ss = ServerSocket(0, 50, loopback)
        closeables += ss
        thread(isDaemon = true) {
            while (!ss.isClosed) {
                val s = try { ss.accept() } catch (e: IOException) { break }
                thread(isDaemon = true) { s.use { runCatching { it.getInputStream().copyTo(it.getOutputStream()) } } }
            }
        }
        return ss
    }

    private fun readHeadBlocking(input: InputStream): String {
        val sb = StringBuilder()
        while (!sb.endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b < 0) break
            sb.append(b.toChar())
        }
        return sb.toString()
    }

    private fun openConnect(port: Int): Pair<Socket, String> {
        val s = Socket(loopback, server.localPort)
        closeables += s
        s.soTimeout = 5000
        s.getOutputStream().write("CONNECT 127.0.0.1:$port HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n\r\n".toByteArray())
        return s to readHeadBlocking(s.getInputStream())
    }

    private fun assertClosed(s: Socket) {
        val r = try { s.getInputStream().read() } catch (e: SocketException) { -1 }
        assertEquals(-1, r)
    }

    private fun eventually(check: () -> Boolean) {
        repeat(100) {
            if (check()) return
            Thread.sleep(20)
        }
        fail("condition not met within 2s")
    }

    private val failingDns = object : Egress by DirectEgress() {
        override suspend fun resolve(host: String): List<InetAddress> = throw UnknownHostException(host)
    }

    @Test
    fun `pac fetch serves the pac and admits the client`() {
        start()
        val slot = registry.addSlot("test")!!
        val response = rawRequest("GET /${slot.token}.pac HTTP/1.1\r\nHost: x\r\n\r\n")
        assertTrue(response.startsWith("HTTP/1.1 200"), response)
        assertTrue("PROXY 127.0.0.1:${server.localPort}" in response, response)
        assertEquals(slot.id, registry.admittedSlot(loopback)?.id)
    }

    @Test
    fun `mobileconfig is served when wifi is configured`() {
        start(wifi = WifiCredentials("DIRECT-ab-Test", "password123"))
        val slot = registry.addSlot("iphone")!!
        val response = rawRequest("GET /${slot.token}.mobileconfig HTTP/1.1\r\nHost: x\r\n\r\n")
        assertTrue("application/x-apple-aspen-config" in response, response)
        assertTrue("DIRECT-ab-Test" in response, response)
    }

    @Test
    fun `malformed request is 400 and unknown path is 404`() {
        start()
        assertTrue(rawRequest("hello\r\n\r\n").startsWith("HTTP/1.1 400"))
        assertTrue(rawRequest("GET /nope HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 404"))
    }

    @Test
    fun `peer outside the subnet is dropped without a response`() {
        start(peerSubnet = Cidr.parse("10.0.0.0/8"))
        // Closing with unread input sends RST on Windows, so a reset counts as "dropped" too.
        val response = try { rawRequest("GET /x HTTP/1.1\r\n\r\n") } catch (e: SocketException) { "" }
        assertEquals("", response)
    }

    @Test
    fun `unadmitted client gets 403`() {
        start()
        assertTrue(rawRequest("CONNECT example.com:443 HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 403"))
    }

    @Test
    fun `connect tunnels raw bytes and counts them`() {
        start()
        val slot = admit()
        val echo = startEcho()
        val (s, head) = openConnect(echo.localPort)
        assertTrue(head.startsWith("HTTP/1.1 200 Connection Established"), head)
        s.getOutputStream().write("ping".toByteArray())
        assertEquals("ping", String(s.getInputStream().readNBytes(4)))
        eventually {
            val current = registry.slots().first { it.id == slot.id }
            current.bytesUp >= 4 && current.bytesDown >= 4
        }
    }

    @Test
    fun `plain http is forwarded with hop-by-hop headers stripped`() {
        start()
        admit()
        val origin = MockWebServer()
        closeables += origin
        origin.enqueue(MockResponse().setBody("hello"))
        origin.start(loopback, 0)
        val client = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(loopback, server.localPort)))
            .retryOnConnectionFailure(false)
            .build()
        val body = client.newCall(Request.Builder().url(origin.url("/x")).build()).execute().use { it.body!!.string() }
        assertEquals("hello", body)
        val recorded = origin.takeRequest()
        assertEquals("/x", recorded.path)
        assertEquals("close", recorded.getHeader("Connection"))
    }

    @Test
    fun `blocked destination gets 403`() {
        start(filter = DestinationFilter.default(Cidr.parse("192.168.49.0/24")))
        admit()
        assertTrue(rawRequest("CONNECT 127.0.0.1:1 HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 403"))
    }

    @Test
    fun `no egress gets 503`() {
        start(egress = DirectEgress(isAvailable = false))
        admit()
        assertTrue(rawRequest("CONNECT example.com:443 HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 503"))
    }

    @Test
    fun `unresolvable host gets 502`() {
        start(egress = failingDns)
        admit()
        assertTrue(rawRequest("CONNECT example.com:443 HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 502"))
    }

    @Test
    fun `probe is answered locally only while egress is validated`() {
        val probe = "GET http://connectivitycheck.gstatic.com/generate_204 HTTP/1.1\r\nHost: connectivitycheck.gstatic.com\r\n\r\n"
        start(egress = failingDns)
        admit()
        assertTrue(rawRequest(probe).startsWith("HTTP/1.1 204"))
        server.stop()

        val unvalidated = object : Egress by DirectEgress(isValidated = false) {
            override suspend fun resolve(host: String): List<InetAddress> = throw UnknownHostException(host)
        }
        start(egress = unvalidated)
        assertTrue(rawRequest(probe).startsWith("HTTP/1.1 502"))
    }

    @Test
    fun `tunnel cap per slot returns 503`() {
        registry = ClientRegistry(maxClients = 3, tunnelCapPerSlot = 1)
        start()
        admit()
        val echo = startEcho()
        val (_, first) = openConnect(echo.localPort)
        assertTrue(first.startsWith("HTTP/1.1 200"), first)
        assertTrue(rawRequest("CONNECT 127.0.0.1:${echo.localPort} HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 503"))
    }

    @Test
    fun `revoke closes live tunnels and blocks the client`() {
        start()
        val slot = admit()
        val echo = startEcho()
        val (s, head) = openConnect(echo.localPort)
        assertTrue(head.startsWith("HTTP/1.1 200"), head)
        assertTrue(server.revoke(slot.id))
        assertClosed(s)
        assertTrue(rawRequest("CONNECT 127.0.0.1:${echo.localPort} HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 403"))
    }

    @Test
    fun `idle tunnel is closed`() {
        start(idleMs = 300)
        admit()
        val echo = startEcho()
        val (s, head) = openConnect(echo.localPort)
        assertTrue(head.startsWith("HTTP/1.1 200"), head)
        assertClosed(s)
    }

    @Test
    fun `egress lost mid-dial gets 503`() {
        val egressLostMidDial = object : Egress by DirectEgress() {
            override suspend fun newChannel(): SocketChannel = throw EgressUnavailableException("gone")
        }
        start(egress = egressLostMidDial)
        admit()
        val echo = startEcho()
        assertTrue(rawRequest("CONNECT 127.0.0.1:${echo.localPort} HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 503"))
    }

    @Test
    fun `truncated forward response is aborted, not cleanly closed`() {
        start()
        admit()
        val origin = ServerSocket(0, 50, loopback)
        closeables += origin
        thread(isDaemon = true) {
            val s = try { origin.accept() } catch (e: IOException) { return@thread }
            s.use { socket ->
                runCatching { readHeadBlocking(socket.getInputStream()) }
                runCatching {
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\n\r\npartial".toByteArray(Charsets.ISO_8859_1))
                    socket.getOutputStream().flush()
                }
                // Give the written bytes time to actually reach the peer before the abortive
                // close below; SO_LINGER(0) can otherwise discard unacknowledged send-buffer data
                // instead of delivering it, turning this into a 0-byte reset.
                Thread.sleep(100)
                socket.setSoLinger(true, 0)
            }
        }
        val port = origin.localPort
        val s = Socket(loopback, server.localPort)
        closeables += s
        s.soTimeout = 5000
        s.getOutputStream().write("GET http://127.0.0.1:$port/ HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
        val input = s.getInputStream()
        var reset = false
        try {
            while (input.read() >= 0) {
                // drain until EOF or an exception
            }
        } catch (e: SocketException) {
            reset = true
        }
        assertTrue(reset, "expected the client read to end with a connection reset, not a clean EOF")
    }

    @Test
    fun `routine client reset is not reported as an error`() {
        val errors = CopyOnWriteArrayList<Throwable>()
        start(onError = { errors += it })
        admit()
        val echo = startEcho()
        val (s, head) = openConnect(echo.localPort)
        assertTrue(head.startsWith("HTTP/1.1 200"), head)
        s.setSoLinger(true, 0)
        s.close()
        Thread.sleep(300)
        assertTrue(errors.isEmpty(), errors.toString())
    }

    @Test
    fun `updateFilter applies to new requests`() {
        start()
        admit()
        val echo = startEcho()
        val (_, firstHead) = openConnect(echo.localPort)
        assertTrue(firstHead.startsWith("HTTP/1.1 200"), firstHead)
        server.updateFilter(DestinationFilter.default(Cidr.parse("192.168.49.0/24")))
        assertTrue(rawRequest("CONNECT 127.0.0.1:${echo.localPort} HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 403"))
    }
}
