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
import com.wifiproxy.core.io.HeadTooLargeException
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
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
import java.util.concurrent.atomic.AtomicInteger

/** `acceptLoop` failed to `accept()` a connection but the server socket itself is still usable. */
class AcceptFailedException(cause: IOException) : IOException(cause)

/**
 * HTTP proxy bound to the group-owner address. One request head per connection decides:
 * local content, connectivity probe, CONNECT tunnel, or a single plain-HTTP forward.
 */
class ProxyServer(
    private val config: ProxyConfig,
    private val registry: ClientRegistry,
    private val egress: Egress,
    initialFilter: DestinationFilter,
    private val onError: (Throwable) -> Unit = {},
) {
    private class ProxyFailure(val response: HttpResponse) : Exception()

    /** Swappable so the Android host can refresh it when the device's interface addresses change. */
    @Volatile
    private var filter: DestinationFilter = initialFilter

    @Volatile
    private var accepting = false

    /** True while the accept loop is running; false once it has exited, for any reason. */
    val isAccepting: Boolean
        get() = accepting

    private val tunnels = ConcurrentHashMap<String, MutableSet<Job>>()

    /** Total connections accepted but not yet fully handled; a pre-admission DoS guard. */
    private val inFlight = AtomicInteger(0)

    /** Per-peer count of connections still reading their request head. */
    private val pendingHeadsPerPeer = ConcurrentHashMap<InetAddress, AtomicInteger>()

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
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> safeOnError(e) })
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
        inFlight.set(0)
        pendingHeadsPerPeer.clear()
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

    private fun safeOnError(e: Throwable) {
        // A throwing callback must not bring down the accept loop or the Android process.
        runCatching { onError(e) }
    }

    private suspend fun acceptLoop(scope: CoroutineScope, nio: NioLoop, ch: ServerSocketChannel) {
        accepting = true
        try {
            var backoffMs = ACCEPT_BACKOFF_INITIAL_MS
            while (currentCoroutineContext().isActive) {
                val client = try {
                    ch.accept()
                } catch (e: ClosedChannelException) {
                    break
                } catch (e: IOException) {
                    safeOnError(AcceptFailedException(e))
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(ACCEPT_BACKOFF_MAX_MS)
                    continue
                }
                if (client == null) {
                    try {
                        nio.await(ch, SelectionKey.OP_ACCEPT)
                    } catch (e: ClosedChannelException) {
                        if (ch.isOpen) {
                            safeOnError(IllegalStateException("NIO loop died; proxy no longer accepting", e))
                        }
                        break
                    }
                    continue
                }
                backoffMs = ACCEPT_BACKOFF_INITIAL_MS
                admitOrReject(scope, nio, client)
            }
        } finally {
            accepting = false
        }
    }

    /** Pre-admission caps: reject before ever launching a handler, so a slotless peer can't DoS us. */
    private fun admitOrReject(scope: CoroutineScope, nio: NioLoop, client: SocketChannel) {
        val peer = try {
            (client.remoteAddress as InetSocketAddress).address
        } catch (e: IOException) {
            nio.closeChannel(client)
            return
        }
        val overGlobalCap = inFlight.get() >= config.maxConnections
        val overPeerCap = (pendingHeadsPerPeer[peer]?.get() ?: 0) >= config.maxPendingHeadsPerPeer
        if (overGlobalCap || overPeerCap) {
            runCatching { client.socket().setSoLinger(true, 0) }
            nio.closeChannel(client)
            return
        }
        inFlight.incrementAndGet()
        pendingHeadsPerPeer.computeIfAbsent(peer) { AtomicInteger(0) }.incrementAndGet()
        scope.launch(start = CoroutineStart.ATOMIC) { handle(nio, client, peer) }
    }

    private fun releasePendingHead(peer: InetAddress) {
        pendingHeadsPerPeer.computeIfPresent(peer) { _, counter ->
            if (counter.decrementAndGet() <= 0) null else counter
        }
    }

    private suspend fun handle(nio: NioLoop, client: SocketChannel, peer: InetAddress) {
        try {
            client.configureBlocking(false)
            if (!config.peerSubnet.contains(peer)) {
                releasePendingHead(peer)
                return
            }
            val head = try {
                withTimeoutOrNull(config.headTimeoutMs) { readHead(nio, client) }
            } finally {
                releasePendingHead(peer)
            }
            val h = head ?: return
            when (val request = RequestParser.parse(h.text)) {
                is ProxyRequest.Malformed -> respond(nio, client, HttpResponse.BAD_REQUEST)
                is ProxyRequest.Local -> respond(nio, client, local.serve(request, peer))
                is ProxyRequest.Connect ->
                    proxy(nio, client, peer, request.host, request.port, h.leftover, forward = null)
                is ProxyRequest.Forward ->
                    proxy(nio, client, peer, request.host, request.port, h.leftover, forward = request)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: HeadTooLargeException) {
            runCatching { respond(nio, client, HttpResponse.BAD_REQUEST) }
        } catch (e: IOException) {
            // Routine peer reset/abort: expected, not reported.
        } catch (e: Exception) {
            safeOnError(e)
        } finally {
            inFlight.decrementAndGet()
            // A direct client.close() can defer the fd release (and the RST of an abortive close)
            // until the loop's selector wakes up and processes the cancelled key; closeChannel
            // nudges it so a revoked or aborted peer is never left waiting on a close that
            // already happened.
            nio.closeChannel(client)
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
        tunnels.compute(slot.id) { _, existing -> (existing ?: ConcurrentHashMap.newKeySet()).apply { add(job) } }
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
            val result = try {
                Tunnel(
                    nio, client, up, idleTimeoutFor(up, port),
                    onUp = { registry.addBytes(slot.id, it.toLong(), 0) },
                    onDown = { registry.addBytes(slot.id, 0, it.toLong()) },
                ).run(closeWhenResponseEnds = forward != null)
            } catch (e: CancellationException) {
                // A revoke or stop() can cut a forward response off mid-stream; closing normally
                // here would make a connection-close-delimited response look complete when it
                // wasn't. Abort instead, then let the cancellation keep propagating.
                if (forward != null) {
                    runCatching { client.socket().setSoLinger(true, 0) }
                }
                throw e
            }

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
            tunnels.computeIfPresent(slot.id) { _, s -> s.remove(job); s.ifEmpty { null } }
            // See the comment in handle()'s finally: closeChannel nudges the loop so a deferred
            // close (and the abort above, if any) is visible to the upstream peer promptly.
            nio.closeChannel(upstream)
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
        const val ACCEPT_BACKOFF_INITIAL_MS = 50L
        const val ACCEPT_BACKOFF_MAX_MS = 1000L
    }
}
