package com.wifiproxy.core

import com.wifiproxy.core.clients.ClientRegistry
import com.wifiproxy.core.net.Cidr
import java.net.Inet4Address
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
    /** Global cap on in-flight connections (accepted through closed); a pre-admission DoS guard. */
    val maxConnections: Int = ClientRegistry.MAX_CLIENTS_LIMIT * ClientRegistry.DEFAULT_TUNNEL_CAP + 64,
    /** Per-peer-IP cap on connections still reading their request head. */
    val maxPendingHeadsPerPeer: Int = 16,
    /** Global cap, across all peers, on connections still reading their request head; a
     *  pre-admission share of [maxConnections] so a slotless peer with many IP aliases can't
     *  crowd out admitted clients. */
    val maxPendingHeads: Int = 64,
    /** Admits any peer in [peerSubnet] that asks, with an auto slot, while seats are free (see
     *  [ClientRegistry.admitOrCreate]), and serves the fixed `/proxy.pac` and
     *  `/proxy.mobileconfig`. A per-listener property, never a registry-wide one: the registry is
     *  shared with the USB listener, which must never admit anyone but its pinned laptop. */
    val openAdmission: Boolean = false,
) {
    init {
        require(!bindAddress.isAnyLocalAddress) { "refusing wildcard bind address $bindAddress" }
        require(bindAddress is Inet4Address) { "bind address must be IPv4: $bindAddress" }
        require(port in 0..65535) { "port out of range: $port" }
        require(idleTimeoutMs > 0) { "idleTimeoutMs must be positive" }
        require(pushIdleTimeoutMs > 0) { "pushIdleTimeoutMs must be positive" }
        require(headTimeoutMs > 0) { "headTimeoutMs must be positive" }
        require(connectTimeoutMs > 0) { "connectTimeoutMs must be positive" }
        require(maxConnections > 0) { "maxConnections must be positive" }
        require(maxPendingHeadsPerPeer > 0) { "maxPendingHeadsPerPeer must be positive" }
        require(maxPendingHeads > 0) { "maxPendingHeads must be positive" }
    }
}
