package com.wifiproxy.app.logs

import com.wifiproxy.app.ui.formatBytes
import com.wifiproxy.app.wifi.LinkState
import com.wifiproxy.core.ProxyEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One verbose log line (without [VERBOSE_PREFIX]) for [event], e.g.
 * `192.168.49.87 CONNECT :443 closed (BOTH_EOF) ↑1.2 KB ↓48.0 KB 3.1 s`.
 *
 * Privacy (spec §6): only what [ProxyEvent] carries - the client's local-link address, the
 * destination port, counts, durations, reason codes and exception class names.
 */
fun formatProxyEvent(event: ProxyEvent): String {
    val peer = event.peer.hostAddress
    return when (event) {
        is ProxyEvent.Refused -> "$peer refused: ${refusalText(event.reason)}"
        is ProxyEvent.LocalServed -> "$peer served ${localText(event.kind)}"
        is ProxyEvent.TunnelOpened -> "$peer ${event.kind} :${event.port} opened"
        is ProxyEvent.TunnelClosed ->
            "$peer ${event.kind} :${event.port} closed (${event.end ?: "CANCELLED"}) " +
                "↑${formatBytes(event.upBytes)} ↓${formatBytes(event.downBytes)} ${formatSeconds(event.durationMs)}"
        is ProxyEvent.UpstreamFailed -> "$peer :${event.port} upstream failed: ${event.failure} (${event.errorClass})"
        is ProxyEvent.Evicted -> "$peer took the seat of an idle device"
    }
}

private fun refusalText(reason: ProxyEvent.RefusalReason): String = when (reason) {
    ProxyEvent.RefusalReason.OUTSIDE_SUBNET -> "outside the group's subnet"
    ProxyEvent.RefusalReason.CONNECTION_CAP -> "too many connections"
    ProxyEvent.RefusalReason.PENDING_HEADS_CAP -> "too many pending requests"
    ProxyEvent.RefusalReason.PER_PEER_CAP -> "too many pending requests from this device"
    ProxyEvent.RefusalReason.HEAD_TIMEOUT -> "request timed out"
    ProxyEvent.RefusalReason.HEAD_TOO_LARGE -> "request too large"
    ProxyEvent.RefusalReason.MALFORMED -> "malformed request"
    ProxyEvent.RefusalReason.UNKNOWN_LOCAL_PATH -> "unknown proxy address or code"
    ProxyEvent.RefusalReason.METHOD_NOT_ALLOWED -> "method not allowed"
    ProxyEvent.RefusalReason.NOT_ADMITTED -> "no seat (not invited)"
    ProxyEvent.RefusalReason.REGISTRY_FULL -> "registry full"
    ProxyEvent.RefusalReason.SLOT_CHANGED -> "seat revoked while connecting"
    ProxyEvent.RefusalReason.TUNNEL_CAP -> "too many open connections for this device"
    ProxyEvent.RefusalReason.DESTINATION_BLOCKED -> "destination blocked (private or local address)"
}

private fun localText(kind: ProxyEvent.LocalKind): String = when (kind) {
    ProxyEvent.LocalKind.PAC -> "proxy setup (PAC)"
    ProxyEvent.LocalKind.MOBILECONFIG -> "iPhone profile (mobileconfig)"
    ProxyEvent.LocalKind.PROBE -> "connectivity check"
}

/** "3.1 s"; always a dot, whatever the locale, so exported logs read the same everywhere. */
fun formatSeconds(durationMs: Long): String = String.format(Locale.US, "%.1f s", durationMs / 1000.0)

/** A link state for a verbose line: the reason or message, the group owner's address while Up,
 *  never the Wi-Fi name or password. */
fun describeLink(state: LinkState): String = when (state) {
    LinkState.Idle -> "idle"
    is LinkState.Down -> "down: ${state.reason}"
    is LinkState.Up -> "up (group owner ${state.ownerAddress.hostAddress})"
    is LinkState.Failed -> "failed: ${state.message}"
}

/** "14:05", for "On until …" and the Diagnostics banner. */
fun formatClockTime(timeMs: Long): String = SimpleDateFormat("HH:mm", Locale.US).format(Date(timeMs))

/** Above this the share intent risks Android's binder transaction limit (~1 MB, UTF-16). */
const val EXPORT_MAX_CHARS = 150_000

/**
 * The Diagnostics export: [header], then [lines] (newest first). If that would exceed
 * [maxChars], the oldest lines are left out and a final note says how many.
 */
fun buildExport(header: String, lines: List<String>, maxChars: Int = EXPORT_MAX_CHARS): String {
    val out = StringBuilder(header)
    var kept = 0
    for (line in lines) {
        if (out.length + 1 + line.length > maxChars) break
        out.append('\n').append(line)
        kept++
    }
    val omitted = lines.size - kept
    if (omitted > 0) out.append('\n').append("… $omitted older lines not included (too long to share)")
    return out.toString()
}
