package com.wifiproxy.app.egress

import java.net.InetAddress
import java.net.UnknownHostException

private val IPV4_LITERAL = Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$""")

/**
 * Parses [host] as an IPv4 or IPv6 address literal, returning `null` for anything else
 * (including every valid hostname).
 *
 * `InetAddress.getByName` is documented (on both the JDK and Android's libcore) to check only
 * the validity of the address format when given a literal, performing no DNS query; it falls
 * back to a real resolver only for input that isn't recognised as numeric. DNS hostnames can
 * never contain a colon, and a dotted-quad shape is never a legal hostname either, so gating the
 * call on those two shapes guarantees we only ever hand it unambiguous literal candidates and
 * never a real hostname. That keeps this a pure, non-blocking parse rather than a lookup.
 */
internal fun parseIpLiteral(host: String): InetAddress? {
    if (!(host.contains(':') || IPV4_LITERAL.matches(host))) return null
    return try {
        InetAddress.getByName(host)
    } catch (_: UnknownHostException) {
        null
    }
}
