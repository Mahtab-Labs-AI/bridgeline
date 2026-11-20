package com.wifiproxy.core.net

import java.net.InetAddress

/** Decides which resolved upstream addresses clients may reach (stops SSRF into the host or LAN). */
class DestinationFilter(private val blocked: List<Cidr>) {
    fun isAllowed(address: InetAddress): Boolean = blocked.none { it.contains(address) }

    companion object {
        private val DEFAULT_BLOCKED = listOf(
            "0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8", "169.254.0.0/16",
            "172.16.0.0/12", "192.168.0.0/16", "224.0.0.0/4", "255.255.255.255/32",
            "::/128", "::1/128", "fc00::/7", "fe80::/10", "ff00::/8",
        ).map(Cidr::parse)

        fun default(p2pSubnet: Cidr) = DestinationFilter(DEFAULT_BLOCKED + p2pSubnet)
    }
}
