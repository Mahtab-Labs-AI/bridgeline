package com.wifiproxy.core

import com.wifiproxy.core.clients.ClientRegistry
import com.wifiproxy.core.content.LocalContent
import com.wifiproxy.core.content.Probes
import com.wifiproxy.core.egress.Dialer
import com.wifiproxy.core.egress.Egress
import com.wifiproxy.core.egress.EgressUnavailableException
import com.wifiproxy.core.http.HttpResponse
import com.wifiproxy.core.http.ProxyRequest
import com.wifiproxy.core.http.RequestParser
import com.wifiproxy.core.io.NioLoop
import com.wifiproxy.core.io.Tunnel
import com.wifiproxy.core.io.TunnelEnd
import com.wifiproxy.core.io.readHead
import com.wifiproxy.core.io.writeFully
import com.wifiproxy.core.net.Cidr
import com.wifiproxy.core.net.DestinationFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.channels.ClosedChannelException
import java.nio.channels.SelectionKey
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap

/**
 * HTTP proxy bound to the group-owner address. One request head per connection decides:
 * local content, connectivity probe, CONNECT tunnel, or a single plain-HTTP forward.
 */
class ProxyServer(
    private val config: ProxyConfig,
    private val registry: ClientRegistry,
    private val egress: Egress,
    initialFilter: DestinationFilter = DestinationFilter.default(config.peerSubnet),
    private val onError: (Throwable) -> Unit = {},
) {
    private class ProxyFailure(val response: HttpResponse) : Exception()

    /** Swappable so the Android host can refresh it when the device's interface addresses change. */
    @Volatile
    private var filter: DestinationFilter = initialFilter

    private val tunnels = ConcurrentHashMap<String, MutableSet<Job>>()
    private var scope: CoroutineScope? = null
    private var server: ServerSocketChannel? = null
    private var loop: NioLoop? = null
    private lateinit var dialer: Dialer
    private lateinit var local: LocalContent

    val localPort: Int
        get() = (checkNotNull(server) { "not started" }.localAddress as InetSocketAddress).port

    @Synchronized
    fun start() {
        check(server == null) { "already started" }
        val nio = NioLoop()
        val ch = ServerSocketChannel.open()
        try {
            ch.bind(InetSocketAddress(config.bindAddress, config.port))
            ch.configureBlocking(false)
        } catch (e: IOException) {
            ch.close()
            nio.close()
            throw e
        }
        loop = nio
        server = ch
        dialer = Dialer.forEgress(nio, egress, timeoutMs = config.connectTimeoutMs)
        local = LocalContent(registry, config.bindAddress.hostAddress, localPort, config.wifi)
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> onError(e) })
        scope = s
        s.launch { acceptLoop(s, nio, ch) }
    }

    @Synchronized
    fun stop() {
        scope?.cancel()
        scope = null
        server?.close()
        server = null
        loop?.close()
        loop = null
        tunnels.clear()
    }

    /** Revokes the slot and closes every tunnel it owns. */
    fun revoke(slotId: String): Boolean {
        val removed = registry.revoke(slotId)
        tunnels.remove(slotId)?.forEach { it.cancel() }
        return removed
    }

    /** Replaces the destination filter; already-open tunnels keep running, new requests use it. */
    fun updateFilter(newFilter: DestinationFilter) {
        filter = newFilter
    }

    private suspend fun acceptLoop(scope: CoroutineScope, nio: NioLoop, ch: ServerSocketChannel) {
        try {
            while (currentCoroutineContext().isActive) {
                val client = ch.accept()
                if (client == null) {
                    nio.await(ch, SelectionKey.OP_ACCEPT)
                    continue
                }
                scope.launch { handle(nio, client) }
            }
        } catch (e: ClosedChannelException) {
            // stopped
        }
    }

    private suspend fun handle(nio: NioLoop, client: SocketChannel) {
        try {
            client.configureBlocking(false)
            val peer = (client.remoteAddress as InetSocketAddress).address
            if (!config.peerSubnet.contains(peer)) return
            val head = withTimeoutOrNull(config.headTimeoutMs) { readHead(nio, client) } ?: return
            when (val request = RequestParser.parse(head.text)) {
                is ProxyRequest.Malformed -> respond(nio, client, HttpResponse.BAD_REQUEST)
                is ProxyRequest.Local -> respond(nio, client, local.serve(request, peer))
                is ProxyRequest.Connect ->
                    proxy(nio, client, peer, request.host, request.port, head.leftover, forward = null)
                is ProxyRequest.Forward ->
                    proxy(nio, client, peer, request.host, request.port, head.leftover, forward = request)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            // Routine peer reset/abort: expected, not reported.
        } catch (e: Exception) {
            onError(e)
        } finally {
            // client.close() can defer the actual socket close (and the FIN/RST the peer sees)
            // until the loop's selector wakes up and processes the cancelled key; nudge it so a
            // revoked or aborted peer is never left waiting on a close that already happened.
            runCatching { client.close() }
            nio.nudge()
        }
    }

    private suspend fun proxy(
        nio: NioLoop,
        client: SocketChannel,
        peer: InetAddress,
        host: String,
        port: Int,
        leftover: ByteArray,
        forward: ProxyRequest.Forward?,
    ) {
        val slot = registry.admittedSlot(peer) ?: return respond(nio, client, HttpResponse.FORBIDDEN)
        if (forward != null && egress.isValidated) {
            Probes.match(forward)?.let { return respond(nio, client, it) }
        }
        if (!registry.tryAcquireTunnel(slot.id)) return respond(nio, client, HttpResponse.SERVICE_UNAVAILABLE)

        val job = currentCoroutineContext().job
        tunnels.computeIfAbsent(slot.id) { ConcurrentHashMap.newKeySet() }.add(job)
        var upstream: SocketChannel? = null
        try {
            // A revoke that raced with the admission check above must still win.
            if (registry.admittedSlot(peer)?.id != slot.id) return respond(nio, client, HttpResponse.FORBIDDEN)
            val up = try {
                open(host, port)
            } catch (e: ProxyFailure) {
                return respond(nio, client, e.response)
            }
            upstream = up
            if (forward == null) {
                writeFully(nio, client, HttpResponse.CONNECTION_ESTABLISHED)
            } else {
                writeFully(nio, up, forward.upstreamHead())
            }
            if (leftover.isNotEmpty()) {
                writeFully(nio, up, leftover)
                registry.addBytes(slot.id, leftover.size.toLong(), 0)
            }
            val result = Tunnel(
                nio, client, up, idleTimeoutFor(up, port),
                onUp = { registry.addBytes(slot.id, it.toLong(), 0) },
                onDown = { registry.addBytes(slot.id, 0, it.toLong()) },
            ).run(closeWhenResponseEnds = forward != null)

            // Abort (RST) only when the response to the client was cut off mid-stream: a clean
            // close here would make a truncated, connection-close-delimited response look
            // complete. Every other outcome closes normally in the `finally` below.
            val mustAbort = !result.responseComplete &&
                (result.end == TunnelEnd.UPSTREAM_FAILED || (forward != null && result.end == TunnelEnd.IDLE))
            if (mustAbort) {
                runCatching { client.socket().setSoLinger(true, 0) }
            }
        } finally {
            registry.releaseTunnel(slot.id)
            tunnels[slot.id]?.remove(job)
            // See the comment in handle()'s finally: nudge the loop so a deferred close (and the
            // abort above, if any) is visible to the upstream peer without waiting on it.
            runCatching { upstream?.close() }
            nio.nudge()
        }
    }

    private suspend fun open(host: String, port: Int): SocketChannel {
        if (!egress.isAvailable) throw ProxyFailure(HttpResponse.SERVICE_UNAVAILABLE)
        val currentFilter = filter
        try {
            val resolved = egress.resolve(host)
            if (resolved.isEmpty()) throw ProxyFailure(HttpResponse.BAD_GATEWAY)
            val allowed = resolved.filter(currentFilter::isAllowed)
            if (allowed.isEmpty()) throw ProxyFailure(HttpResponse.FORBIDDEN)
            return dialer.dial(allowed, port)
        } catch (e: EgressUnavailableException) {
            throw ProxyFailure(HttpResponse.SERVICE_UNAVAILABLE)
        } catch (e: SocketTimeoutException) {
            throw ProxyFailure(HttpResponse.GATEWAY_TIMEOUT)
        } catch (e: IOException) {
            throw ProxyFailure(HttpResponse.BAD_GATEWAY)
        }
    }

    private fun idleTimeoutFor(upstream: SocketChannel, port: Int): Long {
        val address = (upstream.remoteAddress as InetSocketAddress).address
        return if (port == APPLE_PUSH_PORT || APPLE_PUSH_RANGE.contains(address)) config.pushIdleTimeoutMs else config.idleTimeoutMs
    }

    private suspend fun respond(nio: NioLoop, client: SocketChannel, response: HttpResponse) {
        writeFully(nio, client, response.toBytes())
        runCatching { client.shutdownOutput() }
    }

    private companion object {
        const val APPLE_PUSH_PORT = 5223
        val APPLE_PUSH_RANGE = Cidr.parse("17.0.0.0/8")
    }
}
