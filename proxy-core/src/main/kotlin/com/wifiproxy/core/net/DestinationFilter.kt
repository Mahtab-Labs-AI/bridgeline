package com.wifiproxy.core.net

import java.net.InetAddress

/** Decides which resolved upstream addresses clients may reach (stops SSRF into the host or LAN). */
class DestinationFilter(blockedList: List<Cidr>) {
    private val blocked: List<Cidr> = blockedList.toList()

    fun isAllowed(address: InetAddress): Boolean {
        // Unwrap IPv4-mapped IPv6 addresses (::ffff:a.b.c.d)
        val checkAddress = if (address.address.size == 16) {
            val bytes = address.address
            if (bytes[0] == 0.toByte() && bytes[1] == 0.toByte() && bytes[2] == 0.toByte() && bytes[3] == 0.toByte() &&
                bytes[4] == 0.toByte() && bytes[5] == 0.toByte() && bytes[6] == 0.toByte() && bytes[7] == 0.toByte() &&
                bytes[8] == 0.toByte() && bytes[9] == 0.toByte() && bytes[10] == 0xFF.toByte() && bytes[11] == 0xFF.toByte()) {
                InetAddress.getByAddress(bytes.copyOfRange(12, 16))
            } else {
                address
            }
        } else {
            address
        }
        return blocked.none { it.contains(checkAddress) }
    }

    companion object {
        private val DEFAULT_BLOCKED = listOf(
            "0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8", "169.254.0.0/16",
            "172.16.0.0/12", "192.168.0.0/16", "192.0.0.0/24", "224.0.0.0/4", "255.255.255.255/32",
            "::/96", "fc00::/7", "fe80::/10", "ff00::/8",
        ).map(Cidr::parse)

        /**
         * Create a filter with defaults (private/reserved ranges) plus a P2P subnet and the phone's own addresses.
         * @param p2pSubnet the WiFi Direct P2P group subnet to block
         * @param hostAddresses the phone's own interface addresses as /32 and /128 ranges; connecting to any
         *   address the host owns reaches the host itself, whatever the socket binding
         */
        fun default(p2pSubnet: Cidr, hostAddresses: List<Cidr> = emptyList()) =
            DestinationFilter(DEFAULT_BLOCKED + p2pSubnet + hostAddresses)
    }
}
