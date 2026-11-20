package com.wifiproxy.core

import com.wifiproxy.core.net.Cidr
import java.net.InetAddress

data class WifiCredentials(val ssid: String, val password: String) {
    override fun toString() = "WifiCredentials(ssid=$ssid, password=****)"
}

data class ProxyConfig(
    /** The Wi-Fi Direct group-owner address. Never a wildcard: cellular IPv6 is often globally routable. */
    val bindAddress: InetAddress,
    /** 0 picks an ephemeral port (tests). */
    val port: Int = 8080,
    /** Only peers inside this subnet are served. */
    val peerSubnet: Cidr,
    /** Enables /<token>.mobileconfig when set. */
    val wifi: WifiCredentials? = null,
    val idleTimeoutMs: Long = 5 * 60_000L,
    val pushIdleTimeoutMs: Long = 30 * 60_000L,
    val headTimeoutMs: Long = 10_000L,
    val connectTimeoutMs: Long = 10_000L,
) {
    init {
        require(!bindAddress.isAnyLocalAddress) { "refusing wildcard bind address $bindAddress" }
        require(port in 0..65535) { "port out of range: $port" }
    }
}
