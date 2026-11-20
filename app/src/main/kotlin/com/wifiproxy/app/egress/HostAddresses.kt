package com.wifiproxy.app.egress

import com.wifiproxy.core.net.Cidr
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Converts raw interface addresses into deduplicated /32 (IPv4) or /128 (IPv6) [Cidr]s.
 *
 * Every address is round-tripped through [InetAddress.getByAddress], which keeps only the raw
 * bytes and drops any IPv6 zone/scope id (and hostname), so the same link-local address seen on
 * two different interfaces (`fe80::1%wlan0` vs `fe80::1%rmnet0`) collapses into one entry instead
 * of two. Order of first appearance is preserved; everything else is a plain set union.
 */
internal fun hostCidrsFrom(addresses: Iterable<InetAddress>): List<Cidr> {
    val scopeFree = LinkedHashSet<InetAddress>()
    for (address in addresses) {
        scopeFree.add(InetAddress.getByAddress(address.address))
    }
    return scopeFree.map { address -> Cidr(address, if (address is Inet4Address) 32 else 128) }
}
