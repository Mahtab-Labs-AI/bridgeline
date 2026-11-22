package com.wifiproxy.core

import com.wifiproxy.core.clients.ClientRegistry
import com.wifiproxy.core.egress.DirectEgress
import com.wifiproxy.core.egress.Egress
import com.wifiproxy.core.io.TunnelEnd
import com.wifiproxy.core.net.Cidr
import com.wifiproxy.core.net.DestinationFilter
import kotlinx.coroutines.awaitCancellation
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
        peerSubnet: Cidr = Cidr.parse("127.0.0.0/8"),
        maxConnections: Int = 832,
        maxPendingHeads: Int = 64,
        maxPendingHeadsPerPeer: Int = 16,
        headTimeoutMs: Long = 10_000,
        connectTimeoutMs: Long = 10_000,
        onEvent: (ProxyEvent) -> Unit = { events += it },
    ) {
        val config = ProxyConfig(
            bindAddress = loopback, port = 0, peerSubnet = peerSubnet, wifi = wifi,
            openAdmission = openAdmission, maxConnections = maxConnections, maxPendingHeads = maxPendingHeads,
            maxPendingHeadsPerPeer = maxPendingHeadsPerPeer, headTimeoutMs = headTimeoutMs, connectTimeoutMs = connectTimeoutMs,
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
        val refused = awaitEvent<ProxyEvent.Refused>()
        assertEquals(ProxyEvent.RefusalReason.OUTSIDE_SUBNET, refused.reason)
        // Off the local link: never its address.
        assertNull(refused.peer)
        assertEquals("Refused(peer=off-link peer, reason=OUTSIDE_SUBNET)", refused.toString())
    }

    /** A connection that sends nothing, so it sits in the pending-head caps; kept open by the
     *  test until [tearDown]. Returns once the server has counted it (its handler is reading). */
    private fun holdSilent(from: InetAddress? = null): Socket {
        val s = connect(from)
        closeables += s
        // A refusal would close it at once; still open after a short read timeout means it got
        // to the head-reading stage, so the caps now count it.
        s.soTimeout = 200
        val r = try { s.getInputStream().read() } catch (e: SocketTimeoutException) { -2 }
        assertEquals(-2, r, "the held connection was refused")
        return s
    }

    /** Connects and expects a pre-admission reject (RST or EOF, no response). */
    private fun expectRejected(from: InetAddress? = null) {
        connect(from).use { s ->
            s.soTimeout = 5000
            val r = try { s.getInputStream().read() } catch (e: SocketException) { -1 }
            assertEquals(-1, r)
        }
    }

    @Test
    fun `the global connection cap is refused as connection cap`() {
        start(maxConnections = 1)
        holdSilent()
        expectRejected()
        val refused = awaitEvent<ProxyEvent.Refused>()
        assertEquals(ProxyEvent.RefusalReason.CONNECTION_CAP, refused.reason)
        assertEquals(loopback, refused.peer)
    }

    @Test
    fun `the pending-heads total cap is refused as pending heads cap`() {
        start(maxPendingHeads = 1)
        holdSilent()
        expectRejected(from = guest)
        val refused = awaitEvent<ProxyEvent.Refused>()
        assertEquals(ProxyEvent.RefusalReason.PENDING_HEADS_CAP, refused.reason)
        assertEquals(guest, refused.peer)
    }

    @Test
    fun `the per-peer pending-heads cap is refused as per peer cap`() {
        start(maxPendingHeadsPerPeer = 1)
        holdSilent()
        expectRejected()
        assertEquals(ProxyEvent.RefusalReason.PER_PEER_CAP, awaitEvent<ProxyEvent.Refused>().reason)
    }

    @Test
    fun `a cap refusal of an off-link peer never carries its address`() {
        // Only 127.0.0.2 is on the "link": it holds the only pending-head seat, and loopback
        // (off-link here) is turned away by the cap before the subnet check ever runs.
        start(peerSubnet = Cidr.parse("127.0.0.2/32"), maxPendingHeads = 1)
        holdSilent(from = guest)
        expectRejected()
        val refused = awaitEvent<ProxyEvent.Refused>()
        assertEquals(ProxyEvent.RefusalReason.PENDING_HEADS_CAP, refused.reason)
        assertNull(refused.peer)
        assertFalse("127.0.0.1" in refused.toString(), refused.toString())
    }

    @Test
    fun `no head within the head timeout is refused as head timeout`() {
        start(headTimeoutMs = 200)
        val s = connect(null)
        closeables += s
        val refused = awaitEvent<ProxyEvent.Refused>()
        assertEquals(ProxyEvent.RefusalReason.HEAD_TIMEOUT, refused.reason)
        assertEquals(loopback, refused.peer)
    }

    @Test
    fun `closing before sending a head emits nothing`() {
        start(headTimeoutMs = 2_000)
        connect(null).close()
        // Let that handler see the EOF, then mark the stream with a known event.
        Thread.sleep(300)
        assertTrue(rawRequest("hello\r\n\r\n").startsWith("HTTP/1.1 400"))
        awaitEvent<ProxyEvent.Refused>()
        Thread.sleep(100)
        assertEquals(listOf(ProxyEvent.RefusalReason.MALFORMED), events.map { (it as ProxyEvent.Refused).reason })
    }

    @Test
    fun `an oversized head is refused as head too large`() {
        start()
        val response = rawRequest("a".repeat(65 * 1024))
        assertTrue(response.isEmpty() || response.startsWith("HTTP/1.1 400"), response)
        assertEquals(ProxyEvent.RefusalReason.HEAD_TOO_LARGE, awaitEvent<ProxyEvent.Refused>().reason)
    }

    @Test
    fun `a POST for a pac is refused as method not allowed`() {
        start()
        val slot = registry.addSlot("test")!!
        assertTrue(rawRequest("POST /${slot.token}.pac HTTP/1.1\r\nContent-Length: 0\r\n\r\n").startsWith("HTTP/1.1 405"))
        assertEquals(ProxyEvent.RefusalReason.METHOD_NOT_ALLOWED, awaitEvent<ProxyEvent.Refused>().reason)
    }

    @Test
    fun `a slot replaced while admitting is refused as slot changed`() {
        // The registry reads its clock under its lock, during admission's first lookup. Armed, that
        // one read binds a second slot to the peer (unbinding the first): a slot change landing
        // between admission and the re-check.
        var armed = false
        var swap: () -> Unit = {}
        registry = ClientRegistry(maxClients = 3, clock = {
            if (armed) {
                armed = false
                swap()
            }
            System.currentTimeMillis()
        })
        start()
        admit()
        val replacement = registry.addSlot("replacement")!!
        swap = { registry.onPacFetch(replacement.token, loopback) }
        events.clear()
        armed = true
        assertTrue(rawRequest("CONNECT 127.0.0.1:1 HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 403"))
        assertEquals(ProxyEvent.RefusalReason.SLOT_CHANGED, awaitEvent<ProxyEvent.Refused>().reason)
    }

    @Test
    fun `a resolve that never returns is an upstream timeout`() {
        val hanging = object : Egress by DirectEgress() {
            override suspend fun resolve(host: String): List<InetAddress> = awaitCancellation()
        }
        start(egress = hanging, connectTimeoutMs = 200)
        admit()
        assertTrue(rawRequest("CONNECT example.com:443 HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 504"))
        val failed = awaitEvent<ProxyEvent.UpstreamFailed>()
        assertEquals(ProxyEvent.UpstreamFailure.TIMEOUT, failed.failure)
        assertEquals("none", failed.errorClass)
    }

    @Test
    fun `a plain http forward closes with RESPONSE_ENDED and its byte counts`() {
        val origin = ServerSocket(0, 50, loopback)
        closeables += origin
        thread(isDaemon = true) {
            origin.accept().use { s ->
                val input = s.getInputStream()
                val head = StringBuilder()
                while (!head.endsWith("\r\n\r\n")) head.append(input.read().toChar())
                s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 5\r\nConnection: close\r\n\r\nhello".toByteArray())
            }
        }
        start()
        admit()
        events.clear()
        val response = rawRequest("GET http://127.0.0.1:${origin.localPort}/x HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
        assertTrue(response.startsWith("HTTP/1.1 200") && response.endsWith("hello"), response)
        val closed = awaitEvent<ProxyEvent.TunnelClosed>()
        assertEquals(ProxyEvent.TunnelKind.FORWARD, closed.kind)
        assertEquals(origin.localPort, closed.port)
        assertEquals(TunnelEnd.RESPONSE_ENDED, closed.end)
        assertNull(closed.abort)
        assertTrue(closed.downBytes > 5, closed.toString())
    }

    @Test
    fun `a revoked tunnel closes as CANCELLED`() {
        start()
        admit()
        val slot = registry.slots().single()
        val echo = startEcho()
        val s = connect(null)
        closeables += s
        s.soTimeout = 5000
        s.getOutputStream().write("CONNECT 127.0.0.1:${echo.localPort} HTTP/1.1\r\n\r\n".toByteArray())
        awaitEvent<ProxyEvent.TunnelOpened>()
        server.revoke(slot.id)
        val closed = awaitEvent<ProxyEvent.TunnelClosed>()
        assertNull(closed.end)
        assertEquals(ProxyEvent.TunnelAbort.CANCELLED, closed.abort)
        assertTrue("end=CANCELLED" in closed.toString(), closed.toString())
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
            ProxyEvent.TunnelClosed(guest, 443, ProxyEvent.TunnelKind.CONNECT, 1, 2, 3, null, ProxyEvent.TunnelAbort.IO_ERROR),
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
