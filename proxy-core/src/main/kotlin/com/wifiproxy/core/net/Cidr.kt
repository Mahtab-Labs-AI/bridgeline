package com.wifiproxy.core.net

import java.net.InetAddress

/** An IPv4 or IPv6 network in CIDR form. [parse] expects an address literal, never a hostname. */
class Cidr(network: InetAddress, val prefix: Int) {
    private val netBytes: ByteArray = network.address

    init {
        require(prefix in 0..netBytes.size * 8) { "prefix $prefix out of range for $network" }
    }

    fun contains(address: InetAddress): Boolean {
        val bytes = address.address
        if (bytes.size != netBytes.size) return false
        var remaining = prefix
        var i = 0
        while (remaining > 0) {
            val mask = if (remaining >= 8) 0xFF else (0xFF shl (8 - remaining)) and 0xFF
            if ((bytes[i].toInt() and mask) != (netBytes[i].toInt() and mask)) return false
            remaining -= 8
            i++
        }
        return true
    }

    override fun toString() = "${InetAddress.getByAddress(netBytes).hostAddress}/$prefix"

    companion object {
        fun parse(text: String): Cidr {
            val slash = text.indexOf('/')
            require(slash > 0) { "missing prefix in '$text'" }
            val host = text.substring(0, slash)
            require(host.isNotEmpty() && host.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == '.' || it == ':' }) { "not an IP literal: '$host'" }
            require('.' in host || ':' in host) { "not an IP literal: '$host'" }
            return Cidr(InetAddress.getByName(host), text.substring(slash + 1).toInt())
        }
    }
}
