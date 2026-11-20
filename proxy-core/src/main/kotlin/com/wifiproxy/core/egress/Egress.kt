package com.wifiproxy.core.egress

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.nio.channels.SocketChannel

/**
 * Where upstream traffic leaves the host. The Android implementation (Plan 2) binds every
 * channel and lookup to the cellular Network and never falls back to the default network.
 */
interface Egress {
    /** False while no egress network exists; the proxy answers 503. */
    val isAvailable: Boolean

    /** True when the egress network has validated internet; gates local probe answers. */
    val isValidated: Boolean

    suspend fun resolve(host: String): List<InetAddress>

    /** A new, unconnected channel already bound to the egress network. */
    fun newChannel(): SocketChannel
}

/** Uses the JVM's default network. For tests and desktop runs only. */
class DirectEgress(
    override val isAvailable: Boolean = true,
    override val isValidated: Boolean = true,
) : Egress {
    override suspend fun resolve(host: String): List<InetAddress> =
        withContext(Dispatchers.IO) { InetAddress.getAllByName(host).toList() }

    override fun newChannel(): SocketChannel = SocketChannel.open()
}
