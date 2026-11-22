package com.wifiproxy.core

import com.wifiproxy.core.clients.ClientRegistry
import com.wifiproxy.core.egress.DirectEgress
import com.wifiproxy.core.egress.Egress
import com.wifiproxy.core.io.TunnelEnd
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/** `ProxyServer.onEvent`: the verbose-diagnostics stream. Never a hostname, path or token. */
class ProxyEventTest {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val guest = InetAddress.getByName("127.0.0.2")
    private var registry = ClientRegistry(maxClients = 3)
    private lateinit var server: ProxyServer
    private val events = CopyOnWriteArrayList<ProxyEvent>()
    private val closeables = mutableListOf<AutoCloseable>()

    private fun start(
        egress: Egress = DirectEgress(),
        filter: DestinationFilter = DestinationFilter(emptyList()),
        openAdmission: Boolean = false,
        wifi: WifiCredentials? = null,
        onEvent: (ProxyEvent) -> Unit = { events += it },
    ) {
        val config = ProxyConfig(
            bindAddress = loopback, port = 0, peerSubnet = Cidr.parse("127.0.0.0/8"), wifi = wifi,
            openAdmission = openAdmission,
        )
        server = ProxyServer(config, registry, egress, filter, onEvent = onEvent).also { it.start() }
    }

    @AfterTest
    fun tearDown() {
        closeables.forEach { runCatching { it.close() } }
        if (::server.isInitialized) server.stop()
    }

    private fun connect(from: InetAddress?): Socket =
        if (from == null) Socket(loopback, server.localPort) else Socket(loopback, server.localPort, from, 0)

    private fun rawRequest(text: String, from: InetAddress? = null): String = try {
        connect(from).use { s ->
            s.soTimeout = 5000
            s.getOutputStream().write(text.toByteArray(Charsets.ISO_8859_1))
            s.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
        }
    } catch (e: SocketException) {
        // A reset (e.g. an outside-subnet drop on Windows) is a refusal too.
        ""
    }

    private fun admit() {
        val slot = registry.addSlot("test")!!
        assertTrue(rawRequest("GET /${slot.token}.pac HTTP/1.1\r\nHost: x\r\n\r\n").startsWith("HTTP/1.1 200"))
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

    /** Opens a CONNECT tunnel to [port], echoes "ping" through it, then closes the client side. */
    private fun pingThroughTunnel(port: Int, from: InetAddress? = null) {
        connect(from).use { s ->
            s.soTimeout = 5000
            s.getOutputStream().write("CONNECT 127.0.0.1:$port HTTP/1.1\r\n\r\n".toByteArray())
            val input = s.getInputStream()
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) head.append(input.read().toChar())
            assertTrue(head.startsWith("HTTP/1.1 200"), head.toString())
            s.getOutputStream().write("ping".toByteArray())
            assertEquals("ping", String(input.readNBytes(4)))
        }
    }

    private fun eventually(check: () -> Boolean) {
        repeat(150) {
            if (check()) return
            Thread.sleep(20)
        }
        fail("condition not met within 3s; events=$events")
    }

    private inline fun <reified T : ProxyEvent> awaitEvent(): T {
        eventually { events.any { it is T } }
        return events.filterIsInstance<T>().first()
    }

    @Test
    fun `a CONNECT tunnel emits opened then closed with its port and byte counts`() {
        start()
        admit()
        val echo = startEcho()
        pingThroughTunnel(echo.localPort)
        val closed = awaitEvent<ProxyEvent.TunnelClosed>()
        val opened = events.filterIsInstance<ProxyEvent.TunnelOpened>().single()
        assertTrue(events.indexOf(opened) < events.indexOf(closed))
        assertEquals(echo.localPort, opened.port)
        assertEquals(ProxyEvent.TunnelKind.CONNECT, opened.kind)
        assertEquals(loopback, opened.peer)
        assertEquals(echo.localPort, closed.port)
        assertEquals(ProxyEvent.TunnelKind.CONNECT, closed.kind)
        assertEquals(4L, closed.upBytes)
        assertEquals(4L, closed.downBytes)
        assertTrue(closed.durationMs >= 0)
        assertEquals(TunnelEnd.BOTH_EOF, closed.end)
    }

    @Test
    fun `an unadmitted request is refused as not admitted`() {
        start()
        assertTrue(rawRequest("CONNECT example.com:443 HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 403"))
        val refused = awaitEvent<ProxyEvent.Refused>()
        assertEquals(ProxyEvent.RefusalReason.NOT_ADMITTED, refused.reason)
        assertEquals(loopback, refused.peer)
    }

    @Test
    fun `a blocked destination is refused as blocked`() {
        start(filter = DestinationFilter.default(Cidr.parse("192.168.49.0/24")))
        admit()
        assertTrue(rawRequest("CONNECT 127.0.0.1:1 HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 403"))
        assertEquals(ProxyEvent.RefusalReason.DESTINATION_BLOCKED, awaitEvent<ProxyEvent.Refused>().reason)
    }

    @Test
    fun `a full registry under open admission is refused as registry full`() {
        registry = ClientRegistry(maxClients = 1)
        registry.admitOrCreate(InetAddress.getByName("127.0.0.3"))!!
        start(openAdmission = true)
        assertTrue(rawRequest("CONNECT 127.0.0.1:1 HTTP/1.1\r\n\r\n", from = guest).startsWith("HTTP/1.1 403"))
        val refused = awaitEvent<ProxyEvent.Refused>()
        assertEquals(ProxyEvent.RefusalReason.REGISTRY_FULL, refused.reason)
        assertEquals(guest, refused.peer)
    }

    @Test
    fun `a full registry on the fixed pac path is refused as registry full`() {
        registry = ClientRegistry(maxClients = 1)
        registry.admitOrCreate(InetAddress.getByName("127.0.0.3"))!!
        start(openAdmission = true)
        assertTrue(rawRequest("GET /proxy.pac HTTP/1.1\r\n\r\n", from = guest).startsWith("HTTP/1.1 403"))
        assertEquals(ProxyEvent.RefusalReason.REGISTRY_FULL, awaitEvent<ProxyEvent.Refused>().reason)
    }

    @Test
    fun `malformed and unknown local requests are refused with their reasons`() {
        start()
        assertTrue(rawRequest("hello\r\n\r\n").startsWith("HTTP/1.1 400"))
        assertEquals(ProxyEvent.RefusalReason.MALFORMED, awaitEvent<ProxyEvent.Refused>().reason)
        events.clear()
        assertTrue(rawRequest("GET /nope HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 404"))
        assertEquals(ProxyEvent.RefusalReason.UNKNOWN_LOCAL_PATH, awaitEvent<ProxyEvent.Refused>().reason)
    }

    @Test
    fun `a peer outside the subnet is refused as outside subnet`() {
        val config = ProxyConfig(bindAddress = loopback, port = 0, peerSubnet = Cidr.parse("10.0.0.0/8"))
        server = ProxyServer(config, registry, DirectEgress(), DestinationFilter(emptyList()), onEvent = { events += it })
            .also { it.start() }
        rawRequest("GET /x HTTP/1.1\r\n\r\n")
        assertEquals(ProxyEvent.RefusalReason.OUTSIDE_SUBNET, awaitEvent<ProxyEvent.Refused>().reason)
    }

    @Test
    fun `the tunnel cap is refused as tunnel cap`() {
        registry = ClientRegistry(maxClients = 3, tunnelCapPerSlot = 1)
        start()
        admit()
        val echo = startEcho()
        val first = connect(null)
        closeables += first
        first.soTimeout = 5000
        first.getOutputStream().write("CONNECT 127.0.0.1:${echo.localPort} HTTP/1.1\r\n\r\n".toByteArray())
        awaitEvent<ProxyEvent.TunnelOpened>()
        assertTrue(rawRequest("CONNECT 127.0.0.1:${echo.localPort} HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 503"))
        assertEquals(ProxyEvent.RefusalReason.TUNNEL_CAP, awaitEvent<ProxyEvent.Refused>().reason)
    }

    @Test
    fun `a pac fetch emits LocalServed PAC`() {
        start()
        admit()
        val served = awaitEvent<ProxyEvent.LocalServed>()
        assertEquals(ProxyEvent.LocalKind.PAC, served.kind)
        assertEquals(loopback, served.peer)
    }

    @Test
    fun `a fixed mobileconfig fetch emits LocalServed MOBILECONFIG`() {
        start(openAdmission = true, wifi = WifiCredentials("DIRECT-ab-Test", "password123"))
        assertTrue(rawRequest("GET /proxy.mobileconfig HTTP/1.1\r\n\r\n", from = guest).startsWith("HTTP/1.1 200"))
        assertEquals(ProxyEvent.LocalKind.MOBILECONFIG, awaitEvent<ProxyEvent.LocalServed>().kind)
    }

    @Test
    fun `a probe answered locally emits LocalServed PROBE`() {
        start()
        admit()
        events.clear()
        val probe = "GET http://connectivitycheck.gstatic.com/generate_204 HTTP/1.1\r\nHost: connectivitycheck.gstatic.com\r\n\r\n"
        assertTrue(rawRequest(probe).startsWith("HTTP/1.1 204"))
        assertEquals(ProxyEvent.LocalKind.PROBE, awaitEvent<ProxyEvent.LocalServed>().kind)
    }

    @Test
    fun `upstream failures are classified like their 502 503 and 504`() {
        val failingDns = object : Egress by DirectEgress() {
            override suspend fun resolve(host: String): List<InetAddress> = throw UnknownHostException(host)
        }
        start(egress = failingDns)
        admit()
        assertTrue(rawRequest("CONNECT example.com:443 HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 502"))
        val dns = awaitEvent<ProxyEvent.UpstreamFailed>()
        assertEquals(ProxyEvent.UpstreamFailure.DNS, dns.failure)
        assertEquals(443, dns.port)
        assertEquals("UnknownHostException", dns.errorClass)
        server.stop()

        events.clear()
        start(egress = DirectEgress(isAvailable = false))
        admit()
        assertTrue(rawRequest("CONNECT example.com:443 HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 503"))
        assertEquals(ProxyEvent.UpstreamFailure.NO_EGRESS, awaitEvent<ProxyEvent.UpstreamFailed>().failure)
        server.stop()

        events.clear()
        start()
        admit()
        // Nothing listens on a just-closed ephemeral port: the dial is refused.
        val port = ServerSocket(0, 1, loopback).use { it.localPort }
        assertTrue(rawRequest("CONNECT 127.0.0.1:$port HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 502"))
        val refused = awaitEvent<ProxyEvent.UpstreamFailed>()
        assertEquals(ProxyEvent.UpstreamFailure.CONNECT_REFUSED, refused.failure)
        assertEquals(port, refused.port)
    }

    @Test
    fun `an eviction emits Evicted for the newcomer`() {
        var now = 0L
        registry = ClientRegistry(maxClients = 1, clock = { now })
        val config = ProxyConfig(
            bindAddress = loopback, port = 0, peerSubnet = Cidr.parse("127.0.0.0/8"), openAdmission = true, idleSeatMs = 1_000,
        )
        registry.admitOrCreate(InetAddress.getByName("127.0.0.3"))!!
        now = 1_005L
        server = ProxyServer(config, registry, DirectEgress(), DestinationFilter(emptyList()), onEvent = { events += it })
            .also { it.start() }
        assertTrue(rawRequest("GET /proxy.pac HTTP/1.1\r\n\r\n", from = guest).startsWith("HTTP/1.1 200"))
        assertEquals(guest, awaitEvent<ProxyEvent.Evicted>().peer)
    }

    @Test
    fun `no event's toString carries a hostname, path or token`() {
        val wifi = WifiCredentials("DIRECT-ab-Test", "password123")
        // Only the loopback literal resolves; the recognisable host fails with an exception whose
        // message is that host, which must not reach any event.
        val egress = object : Egress by DirectEgress() {
            override suspend fun resolve(host: String): List<InetAddress> =
                if (host == "127.0.0.1") listOf(loopback) else throw UnknownHostException(host)
        }
        start(egress = egress, openAdmission = true, wifi = wifi)
        val slot = registry.addSlot("secret-device")!!
        val token = slot.token
        val host = "very-recognisable-host.example"
        rawRequest("GET /$token.pac HTTP/1.1\r\nHost: $host\r\n\r\n", from = guest)
        rawRequest("GET /$token.mobileconfig HTTP/1.1\r\nHost: $host\r\n\r\n", from = guest)
        rawRequest("GET /secret-path-$token HTTP/1.1\r\nHost: $host\r\n\r\n", from = guest)
        rawRequest("CONNECT $host:443 HTTP/1.1\r\nHost: $host\r\n\r\n", from = guest)
        rawRequest("GET http://$host/path?token=$token HTTP/1.1\r\nHost: $host\r\n\r\n", from = guest)
        pingThroughTunnel(startEcho().localPort, from = guest)
        awaitEvent<ProxyEvent.TunnelClosed>()
        // PAC, mobileconfig, unknown path, two upstream failures, tunnel opened and closed.
        assertEquals(7, events.size, events.toString())

        val synthetic = listOf(
            ProxyEvent.Refused(guest, ProxyEvent.RefusalReason.REGISTRY_FULL),
            ProxyEvent.LocalServed(guest, ProxyEvent.LocalKind.PAC),
            ProxyEvent.TunnelOpened(guest, 443, ProxyEvent.TunnelKind.FORWARD),
            ProxyEvent.TunnelClosed(guest, 443, ProxyEvent.TunnelKind.CONNECT, 1, 2, 3, TunnelEnd.IDLE),
            ProxyEvent.TunnelClosed(guest, 443, ProxyEvent.TunnelKind.CONNECT, 1, 2, 3, null),
            ProxyEvent.UpstreamFailed(guest, 443, ProxyEvent.UpstreamFailure.TIMEOUT, "SocketTimeoutException"),
            ProxyEvent.Evicted(guest),
        )
        // A peer whose InetAddress has picked up a hostname must still print only its address.
        val named = InetAddress.getByAddress(host, byteArrayOf(127, 0, 0, 2))
        val all = events + synthetic + listOf(ProxyEvent.Evicted(named), ProxyEvent.Refused(named, ProxyEvent.RefusalReason.MALFORMED))
        for (event in all) {
            val text = event.toString()
            assertFalse(host in text, text)
            assertFalse("example" in text, text)
            assertFalse(token in text, text)
            assertFalse("secret" in text, text)
            assertFalse("path" in text, text)
            assertFalse(wifi.password in text, text)
            assertTrue("127.0.0.2" in text, text)
        }
    }

    @Test
    fun `a throwing onEvent does not break a request`() {
        start(onEvent = { throw IllegalStateException("boom") })
        val slot = registry.addSlot("test")!!
        assertTrue(rawRequest("GET /${slot.token}.pac HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 200"))
        pingThroughTunnel(startEcho().localPort)
        assertTrue(rawRequest("CONNECT example.com:443 HTTP/1.1\r\n\r\n", from = guest).startsWith("HTTP/1.1 403"))
        assertTrue(server.isAccepting)
    }
}
