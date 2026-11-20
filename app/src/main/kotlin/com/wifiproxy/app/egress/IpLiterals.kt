package com.wifiproxy.app.egress

import java.net.InetAddress

/**
 * Strict, pure (never performs a DNS lookup, never calls [InetAddress.getByName]) IPv4/IPv6
 * literal parser, used as [DnsResolutionEngine]'s default `parseLiteral` so JVM unit tests never
 * depend on Android's `android.net.InetAddresses.isNumericAddress`/`parseNumericAddress` (API 29),
 * which don't exist on the plain JVM. [com.wifiproxy.app.egress.CellularEgress] injects those real
 * Android calls instead in production (I6).
 *
 * Deliberately stricter than [InetAddress.getByName]: a string that merely *looks* like a dotted
 * quad or has a colon in it isn't necessarily a valid numeric literal (`999.1.1.1`, `010.1.1.1`,
 * `foo:bar`), and hand-checked, out-of-range or malformed input is never allowed to reach
 * `getByName` - on Android that call falls through to a blocking `getaddrinfo` on the process
 * default network (Wi-Fi) for anything it doesn't recognise as a clean literal, which is exactly
 * the "never fall back to the default network" rule this exists to uphold. The only Java calls
 * this makes are to [InetAddress.getByAddress], which just wraps raw bytes and never touches the
 * network.
 */
internal fun strictParseIpLiteral(host: String): InetAddress? = parseIpv4Literal(host) ?: parseIpv6Literal(host)

private val IPV4_SHAPE = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

private fun parseIpv4Literal(host: String): InetAddress? {
    val match = IPV4_SHAPE.matchEntire(host) ?: return null
    val bytes = ByteArray(4)
    for (i in 1..4) {
        val group = match.groupValues[i]
        // "010" would be read as octal by some resolvers; a real dotted-quad octet is never
        // written with a leading zero, so treat one as disqualifying rather than guessing a base.
        if (group.length > 1 && group[0] == '0') return null
        val value = group.toIntOrNull() ?: return null
        if (value !in 0..255) return null
        bytes[i - 1] = value.toByte()
    }
    return InetAddress.getByAddress(bytes)
}

private fun parseIpv6Literal(host: String): InetAddress? {
    if (!host.contains(':')) return null
    var body = host
    if (body.startsWith('[') && body.endsWith(']')) body = body.substring(1, body.length - 1)
    // A zone/scope id ("%eth0") only matters for link-local use; this engine treats the address
    // part like any other literal and simply ignores it.
    val percentIndex = body.indexOf('%')
    if (percentIndex >= 0) body = body.substring(0, percentIndex)

    val groups = splitIpv6Groups(body) ?: return null
    if (groups.size != 8) return null

    val bytes = ByteArray(16)
    for ((index, group) in groups.withIndex()) {
        // An embedded IPv4 tail ("::ffff:192.0.2.1") is legal IPv6 syntax but out of scope for this
        // parser; reject it as "not a literal we parse" rather than mis-parsing it.
        if (group.contains('.') || group.isEmpty() || group.length > 4) return null
        // toIntOrNull(16) itself accepts a leading '+' or '-' (e.g. "+1", "-1" both parse), neither
        // of which is valid hextet syntax (M-c) - reject them explicitly rather than let a group
        // like "-1" silently parse as a valid, merely-negative-looking group.
        if (group[0] == '+' || group[0] == '-') return null
        val value = group.toIntOrNull(16) ?: return null
        if (value !in 0..0xFFFF) return null
        bytes[index * 2] = (value shr 8).toByte()
        bytes[index * 2 + 1] = (value and 0xFF).toByte()
    }
    return InetAddress.getByAddress(bytes)
}

/** Expands a `::`-elided IPv6 address body into exactly 8 hex groups, or returns null for
 *  anything that isn't unambiguously well-formed (more than one `::`, too many groups, etc). */
private fun splitIpv6Groups(body: String): List<String>? {
    val doubleColonCount = Regex("::").findAll(body).count()
    if (doubleColonCount > 1) return null
    if (!body.contains("::")) {
        val groups = body.split(':')
        return if (groups.size == 8) groups else null
    }
    val left = body.substringBefore("::")
    val right = body.substringAfter("::")
    val leftGroups = if (left.isEmpty()) emptyList() else left.split(':')
    val rightGroups = if (right.isEmpty()) emptyList() else right.split(':')
    val missing = 8 - leftGroups.size - rightGroups.size
    if (missing < 1) return null
    return leftGroups + List(missing) { "0" } + rightGroups
}
