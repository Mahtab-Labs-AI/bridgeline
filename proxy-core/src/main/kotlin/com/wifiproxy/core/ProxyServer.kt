package com.wifiproxy.core

import com.wifiproxy.core.clients.ClientRegistry
import com.wifiproxy.core.clients.Slot
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
import com.wifiproxy.core.io.monotonicMs
import com.wifiproxy.core.io.readHead
import com.wifiproxy.core.io.writeFully
import com.wifiproxy.core.net.Cidr
import com.wifiproxy.core.net.DestinationFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
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
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.SelectionKey
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

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
    /** Called (on whichever thread triggered it, after [admitOpen] has released its internal
     *  eviction lock - never while holding it) every time an idle auto slot is actually evicted.
     *  Diagnostics only - carries no slot id, name or address. Must not block: nothing here waits
     *  on it, but a slow callback still delays the thread that just admitted a peer. Exceptions
     *  are caught, same as [onError]. */
    private val onEvicted: () -> Unit = {},
    /** Verbose diagnostics: one [ProxyEvent] per refusal, local fetch, tunnel open/close, upstream
     *  failure and eviction. Never a hostname, path, token or destination address (see
     *  [ProxyEvent]). Called synchronously on whichever thread produced the event (the accept loop
     *  or a connection handler) and never while [evictLock] is held. Must be quick and must not
     *  block: admission and the data path both wait for it to return. Exceptions are caught, same
     *  as [onError]. */
    private val onEvent: (ProxyEvent) -> Unit = {},
) {
    /** An upstream open that failed with [response]: either a refusal ([refusal], e.g. a blocked
     *  destination) or an upstream failure ([failure], with the exception class behind it). */
    private class ProxyFailure(
        val response: HttpResponse,
        val failure: ProxyEvent.UpstreamFailure? = null,
        val refusal: ProxyEvent.RefusalReason? = null,
        val error: Throwable? = null,
    ) : Exception() {
        fun event(peer: InetAddress, port: Int): ProxyEvent = if (refusal != null) {
            ProxyEvent.Refused(peer, refusal)
        } else {
            ProxyEvent.UpstreamFailed(peer, port, checkNotNull(failure), error?.javaClass?.simpleName ?: "none")
        }
    }

    /** Releases one admitted connection's share of the per-peer and total pending-head caps,
     *  exactly once no matter how many times (or from where) release() is called. Held for the
     *  whole time a connection is unadmitted - including while its response is being drained
     *  before close - so a slotless peer can't dodge the caps by sending a quick head and then
     *  just sitting on the (rejected) response. */
    private class PendingHead(private val r: Run, private val peer: InetAddress) {
        private val released = AtomicBoolean(false)

        fun release() {
            if (!released.compareAndSet(false, true)) return
            r.pendingHeads.computeIfPresent(peer) { _, count -> if (count <= 1) null else count - 1 }
            r.pendingHeadsTotal.decrementAndGet()
        }
    }

    /** Everything that belongs to a single start()/stop() lifetime. A fresh instance per start()
     *  so a handler left over from a stopped run can only ever touch its own run's counters and
     *  flags, never a later run's. */
    private class Run(
        val nio: NioLoop,
        val server: ServerSocketChannel,
        val dialer: Dialer,
        val local: LocalContent,
        val scope: CoroutineScope,
    ) {
        /** Total connections accepted but not yet fully handled; a pre-admission DoS guard. */
        val inFlight = AtomicInteger()

        /** Per-peer count of connections still reading their request head. */
        val pendingHeads = ConcurrentHashMap<InetAddress, Int>()

        /** Total connections (across all peers) still reading their request head. */
        val pendingHeadsTotal = AtomicInteger()

        /** True before the accept loop is even launched, so [isAccepting] reads true as soon as
         *  start() returns; false once the accept loop has exited, for any reason. */
        @Volatile
        var accepting = true
    }

    /** Swappable so the Android host can refresh it when the device's interface addresses change. */
    @Volatile
    private var filter: DestinationFilter = initialFilter

    @Volatile
    private var run: Run? = null

    /** True while the accept loop is running; false once it has exited, for any reason. */
    val isAccepting: Boolean
        get() = run?.accepting == true

    /** Jobs are added/removed atomically per slot regardless of which run opened the tunnel, so
     *  this can stay shared across restarts: revoke() only ever acts on live jobs. */
    private val tunnels = ConcurrentHashMap<String, MutableSet<Job>>()

    val localPort: Int
        get() = (checkNotNull(run) { "not started" }.server.localAddress as InetSocketAddress).port

    @Synchronized
    fun start() {
        check(run == null) { "already started" }
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
        val dialer = Dialer.forEgress(nio, egress, timeoutMs = config.connectTimeoutMs)
        val boundPort = (ch.localAddress as InetSocketAddress).port
        val local = LocalContent(
            registry, config.bindAddress.hostAddress, boundPort, config.wifi, config.openAdmission,
            owner = config.bindAddress, admit = ::admitOpen,
        )
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> safeOnError(e) })
        val r = Run(nio, ch, dialer, local, s)
        run = r
        s.launch { acceptLoop(r) }
    }

    @Synchronized
    fun stop() {
        val r = run ?: return
        run = null
        r.scope.cancel()
        r.nio.closeChannel(r.server)
        r.nio.close()
    }

    /**
     * Revokes the slot and closes every tunnel it owns. This is the only revoke API: a host
     * must never call [ClientRegistry.revoke] directly, which would drop the slot's admission
     * without cancelling its tunnels.
     */
    fun revoke(slotId: String): Boolean {
        val removed = registry.revoke(slotId)
        tunnels.remove(slotId)?.forEach { it.cancel() }
        return removed
    }

    /** Replaces the destination filter; already-open tunnels keep running, new requests use it. */
    fun updateFilter(newFilter: DestinationFilter) {
        filter = newFilter
    }

    /** Serializes only the slow (eviction) path below, so a burst of newcomers hitting a full
     *  registry at once can't each pick the same idle victim and evict several slots for what
     *  should cost at most one. The registry's own lock is never held while this one is: every
     *  call into [registry] here still takes (and releases) that lock on its own. */
    private val evictLock = Any()

    /**
     * Open admission's one admission call, used on every path (manual proxy, fixed `/proxy.pac`,
     * token-fallback): never seats [config.bindAddress] (the host phone's own apps), and is a
     * plain [ClientRegistry.admittedSlot] lookup - never an admission, never an eviction - whenever
     * [ProxyConfig.openAdmission] is off. Otherwise: [ClientRegistry.admitOrCreate], or - only when
     * the registry is full - one on-demand eviction of the least-recently-active idle auto slot
     * (through [revoke], which also cancels its tunnels) followed by a single retry inside
     * [evictLock]. Nothing is evicted while there's room, and there's no background sweeper: a
     * full registry with no idle auto slot just stays full (403/null). Never evicts a pinned or
     * coded slot; see [ClientRegistry.idleAutoSlotToEvict].
     *
     * **Pick-to-revoke window.** [ClientRegistry.idleAutoSlotToEvict] picks its candidate and
     * returns its id; [revoke] then removes that id a moment later. A victim that receives fresh
     * traffic in that in-between window (microseconds, not milliseconds) is still evicted even
     * though it's no longer idle at the instant of revocation - acceptable, since it only gets
     * here after sitting idle for a full [ProxyConfig.idleSeatMs] (3 minutes by default).
     */
    private fun admitOpen(peer: InetAddress): Slot? {
        if (!config.openAdmission || peer == config.bindAddress) return registry.admittedSlot(peer)
        registry.admitOrCreate(peer)?.let { return it }
        var evicted = false
        val admitted = synchronized(evictLock) {
            registry.admitOrCreate(peer) ?: registry.idleAutoSlotToEvict(config.idleSeatMs)?.let { victim ->
                // Only count it as an eviction if this call actually removed the slot: a concurrent
                // revoke() of the very same victim (e.g. the user tapping Disconnect) could have
                // already done so, outside evictLock entirely.
                evicted = revoke(victim)
                registry.admitOrCreate(peer)
            }
        }
        // Outside evictLock, not before: onEvicted and onEvent are diagnostics-only and must never
        // run while this holds the lock (see their KDoc).
        if (evicted) {
            safeOnEvicted()
            safeOnEvent(ProxyEvent.Evicted(peer))
        }
        return admitted
    }

    private fun safeOnEvicted() {
        // Same guard as safeOnError: a throwing callback must not affect admission or crash the
        // caller's thread.
        runCatching { onEvicted() }
    }

    private fun safeOnEvent(event: ProxyEvent) {
        // Same guard again: verbose diagnostics must never throw into admission or the data path.
        runCatching { onEvent(event) }
    }

    /** Why [admitOpen] just returned null for [peer]: only a peer that open admission would seat
     *  (so never the owner) can have been turned away by a full registry. */
    private fun noSeatReason(peer: InetAddress) =
        if (config.openAdmission && peer != config.bindAddress) {
            ProxyEvent.RefusalReason.REGISTRY_FULL
        } else {
            ProxyEvent.RefusalReason.NOT_ADMITTED
        }

    private fun safeOnError(e: Throwable) {
        // A throwing callback must not bring down the accept loop or the Android process.
        runCatching { onError(e) }
    }

    private suspend fun acceptLoop(r: Run) {
        try {
            var backoffMs = ACCEPT_BACKOFF_INITIAL_MS
            var lastFailureReportMs: Long? = null
            while (currentCoroutineContext().isActive) {
                val client = try {
                    r.server.accept()
                } catch (e: ClosedChannelException) {
                    break
                } catch (e: IOException) {
                    val now = monotonicMs()
                    if (lastFailureReportMs == null || now - lastFailureReportMs!! >= ACCEPT_FAILURE_REPORT_INTERVAL_MS) {
                        safeOnError(AcceptFailedException(e))
                        lastFailureReportMs = now
                    }
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(ACCEPT_BACKOFF_MAX_MS)
                    continue
                }
                if (client == null) {
                    try {
                        r.nio.await(r.server, SelectionKey.OP_ACCEPT)
                    } catch (e: ClosedChannelException) {
                        if (r.server.isOpen) {
                            safeOnError(IllegalStateException("NIO loop died; proxy no longer accepting", e))
                        }
                        break
                    }
                    continue
                }
                backoffMs = ACCEPT_BACKOFF_INITIAL_MS
                lastFailureReportMs = null
                admitOrReject(r, client)
            }
        } finally {
            r.accepting = false
        }
    }

    /** Pre-admission caps: reject before ever launching a handler, so a slotless peer can't DoS us. */
    private fun admitOrReject(r: Run, client: SocketChannel) {
        val peer = try {
            (client.remoteAddress as InetSocketAddress).address
        } catch (e: IOException) {
            r.nio.closeChannel(client)
            return
        }
        try {
            client.configureBlocking(false)
        } catch (e: IOException) {
            r.nio.closeChannel(client)
            return
        }
        val overGlobalCap = r.inFlight.get() >= config.maxConnections
        val overPendingHeadsTotalCap = r.pendingHeadsTotal.get() >= config.maxPendingHeads
        if (overGlobalCap || overPendingHeadsTotalCap) {
            rejectPreAdmission(r, client)
            safeOnEvent(
                ProxyEvent.Refused(
                    peer,
                    if (overGlobalCap) ProxyEvent.RefusalReason.CONNECTION_CAP else ProxyEvent.RefusalReason.PENDING_HEADS_CAP,
                ),
            )
            return
        }
        var admittedPeer = false
        r.pendingHeads.compute(peer) { _, c ->
            val n = c ?: 0
            if (n >= config.maxPendingHeadsPerPeer) c else { admittedPeer = true; n + 1 }
        }
        if (!admittedPeer) {
            rejectPreAdmission(r, client)
            safeOnEvent(ProxyEvent.Refused(peer, ProxyEvent.RefusalReason.PER_PEER_CAP))
            return
        }
        r.pendingHeadsTotal.incrementAndGet()
        r.inFlight.incrementAndGet()
        startHandler(r, client, peer)
    }

    // ATOMIC guarantees handle()'s finally runs, so the counters and channel are always released.
    @OptIn(DelicateCoroutinesApi::class)
    private fun startHandler(r: Run, client: SocketChannel, peer: InetAddress) {
        r.scope.launch(start = CoroutineStart.ATOMIC) { handle(r, client, peer) }
    }

    private fun rejectPreAdmission(r: Run, client: SocketChannel) {
        abortOnClose(client)
        r.nio.closeChannel(client)
    }

    /** Aborts (RST) on the next close instead of a clean FIN: used whenever closing a connection
     *  whose unread or undelivered bytes would otherwise make a truncated exchange look complete. */
    private fun abortOnClose(ch: SocketChannel) {
        runCatching { ch.socket().setSoLinger(true, 0) }
    }

    private suspend fun handle(r: Run, client: SocketChannel, peer: InetAddress) {
        // Held for the whole time this connection is unadmitted - released early in proxy() only
        // once admission actually succeeds; every other path (including a drained rejection
        // response) releases it here, in the outer finally, exactly once via the CAS in release().
        val pendingHead = PendingHead(r, peer)
        try {
            if (!config.peerSubnet.contains(peer)) {
                safeOnEvent(ProxyEvent.Refused(peer, ProxyEvent.RefusalReason.OUTSIDE_SUBNET))
                return
            }
            // Tells a head timeout (a refusal) apart from a peer that just closed before sending
            // one (nothing to report): both come back from withTimeoutOrNull as null.
            var headTimedOut = true
            val head = withTimeoutOrNull(config.headTimeoutMs) { readHead(r.nio, client).also { headTimedOut = false } }
            val h = head ?: run {
                if (headTimedOut) safeOnEvent(ProxyEvent.Refused(peer, ProxyEvent.RefusalReason.HEAD_TIMEOUT))
                return
            }
            when (val request = RequestParser.parse(h.text)) {
                is ProxyRequest.Malformed -> {
                    safeOnEvent(ProxyEvent.Refused(peer, ProxyEvent.RefusalReason.MALFORMED))
                    respond(r.nio, client, HttpResponse.BAD_REQUEST)
                }
                is ProxyRequest.Local -> {
                    val response = r.local.serve(request, peer)
                    safeOnEvent(localEvent(peer, request, response))
                    respond(r.nio, client, response)
                }
                is ProxyRequest.Connect ->
                    proxy(r, client, peer, pendingHead, request.host, request.port, h.leftover, forward = null)
                is ProxyRequest.Forward ->
                    proxy(r, client, peer, pendingHead, request.host, request.port, h.leftover, forward = request)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: HeadTooLargeException) {
            safeOnEvent(ProxyEvent.Refused(peer, ProxyEvent.RefusalReason.HEAD_TOO_LARGE))
            try {
                respond(r.nio, client, HttpResponse.BAD_REQUEST)
            } catch (e: IOException) {
                // Routine peer reset/abort while sending the 400: expected, not reported.
            }
        } catch (e: IOException) {
            // Routine peer reset/abort: expected, not reported.
        } catch (e: Exception) {
            safeOnError(e)
        } finally {
            pendingHead.release()
            r.inFlight.decrementAndGet()
            // A direct client.close() can defer the fd release (and the RST of an abortive close)
            // until the loop's selector wakes up and processes the cancelled key; closeChannel
            // nudges it so a revoked or aborted peer is never left waiting on a close that
            // already happened.
            r.nio.closeChannel(client)
        }
    }

    /** What [LocalContent.serve] did, as an event: the kind of file served (from the path's
     *  extension - never the path itself, which can carry a token) or why it refused. */
    private fun localEvent(peer: InetAddress, request: ProxyRequest.Local, response: HttpResponse): ProxyEvent =
        when (response.status) {
            200 -> ProxyEvent.LocalServed(
                peer,
                if (request.path.endsWith(".mobileconfig")) ProxyEvent.LocalKind.MOBILECONFIG else ProxyEvent.LocalKind.PAC,
            )
            HttpResponse.FORBIDDEN.status -> ProxyEvent.Refused(peer, noSeatReason(peer))
            HttpResponse.METHOD_NOT_ALLOWED.status -> ProxyEvent.Refused(peer, ProxyEvent.RefusalReason.METHOD_NOT_ALLOWED)
            else -> ProxyEvent.Refused(peer, ProxyEvent.RefusalReason.UNKNOWN_LOCAL_PATH)
        }

    private suspend fun proxy(
        r: Run,
        client: SocketChannel,
        peer: InetAddress,
        pendingHead: PendingHead,
        host: String,
        port: Int,
        leftover: ByteArray,
        forward: ProxyRequest.Forward?,
    ) {
        // Open admission is this listener's property, not the (shared) registry's: the USB
        // listener over the same registry never auto-creates a slot. A full registry is a 403. The
        // group owner's own address (the host phone's apps) never gets an auto slot either -
        // admitOpen itself enforces both of those (see its KDoc), so there's exactly one place
        // that owner/open-admission rule lives.
        val slot = admitOpen(peer) ?: run {
            safeOnEvent(ProxyEvent.Refused(peer, noSeatReason(peer)))
            return respond(r.nio, client, HttpResponse.FORBIDDEN)
        }
        // The peer is genuinely admitted now; it's no longer part of the pre-admission DoS surface
        // the pending-head caps exist to bound, even though its response (or tunnel) isn't done yet.
        pendingHead.release()
        if (forward != null && egress.isValidated) {
            Probes.match(forward)?.let {
                safeOnEvent(ProxyEvent.LocalServed(peer, ProxyEvent.LocalKind.PROBE))
                return respond(r.nio, client, it)
            }
        }
        if (!registry.tryAcquireTunnel(slot.id)) {
            safeOnEvent(ProxyEvent.Refused(peer, ProxyEvent.RefusalReason.TUNNEL_CAP))
            return respond(r.nio, client, HttpResponse.SERVICE_UNAVAILABLE)
        }

        val job = currentCoroutineContext().job
        tunnels.compute(slot.id) { _, existing -> (existing ?: ConcurrentHashMap.newKeySet()).apply { add(job) } }
        var upstream: SocketChannel? = null
        // Verbose diagnostics for this one connection; registry.addBytes keeps the slot's totals.
        val kind = if (forward == null) ProxyEvent.TunnelKind.CONNECT else ProxyEvent.TunnelKind.FORWARD
        val upBytes = AtomicLong()
        val downBytes = AtomicLong()
        var openedAtMs = -1L
        var end: TunnelEnd? = null
        try {
            // A revoke that raced with the admission check above must still win. Never creates a
            // slot, even under open admission: a revoked auto slot's replacement is a new slot,
            // so its id differs and this connection is refused.
            if (registry.admittedSlot(peer)?.id != slot.id) {
                safeOnEvent(ProxyEvent.Refused(peer, ProxyEvent.RefusalReason.SLOT_CHANGED))
                return respond(r.nio, client, HttpResponse.FORBIDDEN)
            }
            val up = try {
                open(r, host, port)
            } catch (e: ProxyFailure) {
                safeOnEvent(e.event(peer, port))
                return respond(r.nio, client, e.response)
            }
            upstream = up
            openedAtMs = monotonicMs()
            safeOnEvent(ProxyEvent.TunnelOpened(peer, port, kind))
            if (forward == null) {
                writeFully(r.nio, client, HttpResponse.CONNECTION_ESTABLISHED)
            } else {
                writeFully(r.nio, up, forward.upstreamHead())
            }
            if (leftover.isNotEmpty()) {
                writeFully(r.nio, up, leftover)
                registry.addBytes(slot.id, leftover.size.toLong(), 0)
                upBytes.addAndGet(leftover.size.toLong())
            }
            val result = try {
                Tunnel(
                    r.nio, client, up, idleTimeoutFor(up, port),
                    onUp = {
                        registry.addBytes(slot.id, it.toLong(), 0)
                        upBytes.addAndGet(it.toLong())
                    },
                    onDown = {
                        registry.addBytes(slot.id, 0, it.toLong())
                        downBytes.addAndGet(it.toLong())
                    },
                ).run(closeWhenResponseEnds = forward != null)
            } catch (e: CancellationException) {
                // A revoke or stop() can cut a forward response off mid-stream; closing normally
                // here would make a connection-close-delimited response look complete when it
                // wasn't. Abort instead, then let the cancellation keep propagating.
                if (forward != null) {
                    abortOnClose(client)
                }
                throw e
            }
            end = result.end

            // An early response (e.g. a 401/413) cancels the upload pump mid-stream, which can
            // leave unread request bytes sitting in the client's receive buffer. Draining them
            // now, before handle()'s finally closes the client, turns what would otherwise be an
            // RST - discarding the tail of the response we just delivered - into a clean close.
            if (forward != null && result.end == TunnelEnd.RESPONSE_ENDED) {
                try {
                    drainBeforeClose(r.nio, client)
                } catch (e: IOException) {
                    // Best-effort: the peer may already be gone, which is fine - we're about to close anyway.
                }
            }

            // Abort (RST) only when the response to the client was cut off mid-stream: a clean
            // close here would make a truncated, connection-close-delimited response look
            // complete. Every other outcome closes normally in the `finally` below.
            val mustAbort = !result.responseComplete &&
                (result.end == TunnelEnd.UPSTREAM_FAILED || (forward != null && result.end == TunnelEnd.IDLE))
            if (mustAbort) {
                abortOnClose(client)
            }
        } finally {
            registry.releaseTunnel(slot.id)
            tunnels.computeIfPresent(slot.id) { _, s -> s.remove(job); s.ifEmpty { null } }
            // See the comment in handle()'s finally: closeChannel nudges the loop so a deferred
            // close (and the abort above, if any) is visible to the upstream peer promptly.
            r.nio.closeChannel(upstream)
            if (openedAtMs >= 0) {
                safeOnEvent(
                    ProxyEvent.TunnelClosed(
                        peer, port, kind, upBytes.get(), downBytes.get(), monotonicMs() - openedAtMs, end,
                    ),
                )
            }
        }
    }

    /** Resolves and dials [host]:[port]. Every failure is a [ProxyFailure] carrying the response
     *  and, for diagnostics, what went wrong: a 502 is [ProxyEvent.UpstreamFailure.DNS] if the
     *  lookup failed and [ProxyEvent.UpstreamFailure.CONNECT_REFUSED] if the dial did. */
    private suspend fun open(r: Run, host: String, port: Int): SocketChannel {
        if (!egress.isAvailable) {
            throw ProxyFailure(HttpResponse.SERVICE_UNAVAILABLE, failure = ProxyEvent.UpstreamFailure.NO_EGRESS)
        }
        val currentFilter = filter
        var resolving = true
        try {
            val resolved = withTimeoutOrNull(config.connectTimeoutMs) { egress.resolve(host) }
                ?: throw ProxyFailure(HttpResponse.GATEWAY_TIMEOUT, failure = ProxyEvent.UpstreamFailure.TIMEOUT)
            if (resolved.isEmpty()) throw ProxyFailure(HttpResponse.BAD_GATEWAY, failure = ProxyEvent.UpstreamFailure.DNS)
            val allowed = resolved.filter(currentFilter::isAllowed)
            if (allowed.isEmpty()) {
                throw ProxyFailure(HttpResponse.FORBIDDEN, refusal = ProxyEvent.RefusalReason.DESTINATION_BLOCKED)
            }
            resolving = false
            return r.dialer.dial(allowed, port)
        } catch (e: EgressUnavailableException) {
            throw ProxyFailure(HttpResponse.SERVICE_UNAVAILABLE, failure = ProxyEvent.UpstreamFailure.NO_EGRESS, error = e)
        } catch (e: SocketTimeoutException) {
            throw ProxyFailure(HttpResponse.GATEWAY_TIMEOUT, failure = ProxyEvent.UpstreamFailure.TIMEOUT, error = e)
        } catch (e: IOException) {
            val failure = if (resolving) ProxyEvent.UpstreamFailure.DNS else ProxyEvent.UpstreamFailure.CONNECT_REFUSED
            // The dialer wraps per-address failures in one IOException; its first cause names the
            // real one (e.g. ConnectException), which is what a troubleshooter needs.
            throw ProxyFailure(HttpResponse.BAD_GATEWAY, failure = failure, error = if (resolving) e else e.cause ?: e)
        }
    }

    private fun idleTimeoutFor(upstream: SocketChannel, port: Int): Long {
        val address = (upstream.remoteAddress as InetSocketAddress).address
        return if (port == APPLE_PUSH_PORT || APPLE_PUSH_RANGES.any { it.contains(address) }) {
            config.pushIdleTimeoutMs
        } else {
            config.idleTimeoutMs
        }
    }

    private suspend fun respond(nio: NioLoop, client: SocketChannel, response: HttpResponse) {
        writeFully(nio, client, response.toBytes())
        runCatching { client.shutdownOutput() }
        // Unread request bytes left in the kernel's receive buffer make the next close() send an
        // RST instead of a clean FIN; on Windows that can discard the response we just wrote
        // before the client has read it. Drain whatever the peer already sent (or will send in
        // the next moment) so the eventual close in handle()'s finally is always clean.
        drainBeforeClose(nio, client)
    }

    private suspend fun drainBeforeClose(nio: NioLoop, client: SocketChannel) {
        try {
            withTimeoutOrNull(DRAIN_TIMEOUT_MS) {
                val buf = ByteBuffer.allocate(DRAIN_BUFFER_SIZE)
                var total = 0
                while (total < DRAIN_MAX_BYTES) {
                    buf.clear()
                    val n = client.read(buf)
                    if (n < 0) break
                    if (n == 0) {
                        nio.await(client, SelectionKey.OP_READ)
                        continue
                    }
                    total += n
                }
            }
        } catch (e: IOException) {
            // Best-effort: the peer may already be gone, which is fine - we're about to close anyway.
        }
    }

    private companion object {
        const val APPLE_PUSH_PORT = 5223
        // Apple's push infrastructure: the classic 17.0.0.0/8 plus its IPv6 ranges.
        val APPLE_PUSH_RANGES = listOf(
            Cidr.parse("17.0.0.0/8"),
            Cidr.parse("2620:149::/32"),
            Cidr.parse("2403:300::/32"),
            Cidr.parse("2a01:b740::/32"),
        )
        const val ACCEPT_BACKOFF_INITIAL_MS = 50L
        const val ACCEPT_BACKOFF_MAX_MS = 1000L
        const val ACCEPT_FAILURE_REPORT_INTERVAL_MS = 60_000L
        const val DRAIN_TIMEOUT_MS = 250L
        const val DRAIN_MAX_BYTES = 64 * 1024
        const val DRAIN_BUFFER_SIZE = 4096
    }
}
