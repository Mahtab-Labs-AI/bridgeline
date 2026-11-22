package com.wifiproxy.core

import com.wifiproxy.core.io.TunnelEnd
import java.net.InetAddress

/**
 * Per-connection detail for verbose troubleshooting (`ProxyServer.onEvent`).
 *
 * Privacy (spec §6): an event never carries a URL, hostname, request path, slot token, Wi-Fi
 * password or destination address - only the peer (a client on the local link), the destination
 * *port*, byte counts, durations, reason codes and exception class names. A peer outside the
 * listener's subnet is not on the local link, so its address is never carried at all ([peer] is
 * null). Every [toString] prints the peer through [describePeer]: [InetAddress.getHostAddress],
 * never [InetAddress.toString], which would also print a hostname the address may have cached.
 */
sealed interface ProxyEvent {
    /** The client this is about: its address on the local link (the Wi-Fi Direct group, or
     *  loopback for USB), or null for an off-link peer (outside the listener's subnet) - only
     *  ever on a [Refused] for [RefusalReason.OUTSIDE_SUBNET] or a pre-admission cap. */
    val peer: InetAddress?

    /** Why a connection got no service. One per refusal path in `ProxyServer`. */
    enum class RefusalReason {
        /** The peer isn't in the listener's subnet; dropped without a response. */
        OUTSIDE_SUBNET,

        /** Pre-admission cap: too many connections in flight in total. */
        CONNECTION_CAP,

        /** Pre-admission cap: too many connections reading their request head, across all peers. */
        PENDING_HEADS_CAP,

        /** Pre-admission cap: too many connections reading their request head from this peer. */
        PER_PEER_CAP,

        /** No complete request head within the head timeout. */
        HEAD_TIMEOUT,

        /** The request head was too large (400). */
        HEAD_TOO_LARGE,

        /** The request couldn't be parsed (400). */
        MALFORMED,

        /** A request to the proxy itself for a path it doesn't serve, or an unknown token (404). */
        UNKNOWN_LOCAL_PATH,

        /** A request to the proxy itself with a method other than GET (405). */
        METHOD_NOT_ALLOWED,

        /** The peer has no slot and this listener doesn't admit open (403). */
        NOT_ADMITTED,

        /** Open admission, but every seat is taken and none is idle (403). */
        REGISTRY_FULL,

        /** The peer's slot was revoked or replaced while it was being admitted (403). */
        SLOT_CHANGED,

        /** The slot already has its maximum number of tunnels open (503). */
        TUNNEL_CAP,

        /** Every resolved address of the destination is blocked by the filter (403). */
        DESTINATION_BLOCKED,
    }

    /** What the proxy served itself, never the path (a token may be in it). */
    enum class LocalKind { PAC, MOBILECONFIG, PROBE }

    enum class TunnelKind { CONNECT, FORWARD }

    /** Why a tunnel stopped before it could end on its own ([TunnelClosed.end] is null then). */
    enum class TunnelAbort {
        /** Revoked (including an idle-seat eviction) or the server stopped. */
        CANCELLED,

        /** An I/O error outside the pumps, e.g. writing the 200 or the request head. */
        IO_ERROR,

        /** Anything else: a bug, reported through `onError` too. */
        ERROR,
    }

    /** Mirrors the 502/503/504 a client gets: [DNS] and [CONNECT_REFUSED] are 502, [NO_EGRESS] is
     *  503, [TIMEOUT] (resolve or connect) is 504. */
    enum class UpstreamFailure { DNS, CONNECT_REFUSED, TIMEOUT, NO_EGRESS }

    class Refused(override val peer: InetAddress?, val reason: RefusalReason) : ProxyEvent {
        override fun toString() = "Refused(peer=${describePeer(peer)}, reason=$reason)"
    }

    class LocalServed(override val peer: InetAddress?, val kind: LocalKind) : ProxyEvent {
        override fun toString() = "LocalServed(peer=${describePeer(peer)}, kind=$kind)"
    }

    class TunnelOpened(override val peer: InetAddress?, val port: Int, val kind: TunnelKind) : ProxyEvent {
        override fun toString() = "TunnelOpened(peer=${describePeer(peer)}, port=$port, kind=$kind)"
    }

    /**
     * A tunnel opened by [TunnelOpened] has closed. [upBytes]/[downBytes] are what this one
     * connection moved (client to upstream, and back). [end] is why the tunnel stopped pumping;
     * when it was cut off before it could end on its own, [end] is null and [abort] says why.
     */
    class TunnelClosed(
        override val peer: InetAddress?,
        val port: Int,
        val kind: TunnelKind,
        val upBytes: Long,
        val downBytes: Long,
        val durationMs: Long,
        val end: TunnelEnd?,
        val abort: TunnelAbort? = if (end == null) TunnelAbort.CANCELLED else null,
    ) : ProxyEvent {
        /** [end], or [abort] when it didn't end on its own. */
        val outcome: String get() = end?.name ?: abort?.name ?: TunnelAbort.CANCELLED.name

        override fun toString() =
            "TunnelClosed(peer=${describePeer(peer)}, port=$port, kind=$kind, upBytes=$upBytes, " +
                "downBytes=$downBytes, durationMs=$durationMs, end=$outcome)"
    }

    /** [errorClass] is the simple class name of the exception behind the failure (never its
     *  message, which can name the host), or "none" when no exception was involved. */
    class UpstreamFailed(
        override val peer: InetAddress?,
        val port: Int,
        val failure: UpstreamFailure,
        val errorClass: String,
    ) : ProxyEvent {
        override fun toString() = "UpstreamFailed(peer=${describePeer(peer)}, port=$port, failure=$failure, errorClass=$errorClass)"
    }

    /** An idle auto slot was evicted to seat [peer], the newcomer. */
    class Evicted(override val peer: InetAddress?) : ProxyEvent {
        override fun toString() = "Evicted(peer=${describePeer(peer)})"
    }

    companion object {
        /** How every event prints its peer: the bare address, or "off-link peer" for null. */
        fun describePeer(peer: InetAddress?): String = peer?.hostAddress ?: "off-link peer"
    }
}
