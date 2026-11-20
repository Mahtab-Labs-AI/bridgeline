package com.wifiproxy.core.egress

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
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

    /**
     * Resolves [host] on this egress's network. Implementations must be cancellable and
     * time-bounded: a blocking `InetAddress.getAllByName` wrapped in `withContext(Dispatchers.IO)`
     * cannot actually be cancelled (the underlying blocking call keeps running on the IO thread
     * after the coroutine gives up on it), so the Android implementation must resolve via
     * `DnsResolver` with an explicit timeout instead.
     */
    suspend fun resolve(host: String): List<InetAddress>

    /**
     * A new, unconnected channel bound to the egress network: an implementation must never
     * return a channel that isn't bound to it ("bound or refuse"). If binding fails, it must
     * close the channel it opened before throwing. Throws [EgressUnavailableException] when the
     * egress network no longer exists, and [IOException] for any other binding failure. May
     * block briefly while binding, so never call this from the NioLoop thread.
     */
    suspend fun newChannel(): SocketChannel
}

/** Thrown by [Egress.newChannel] (or a dial attempt) when the egress network no longer exists. */
class EgressUnavailableException(message: String) : IOException(message)

/** Uses the JVM's default network. For tests and desktop runs only. */
class DirectEgress(
    override val isAvailable: Boolean = true,
    override val isValidated: Boolean = true,
) : Egress {
    override suspend fun resolve(host: String): List<InetAddress> =
        withContext(Dispatchers.IO) { InetAddress.getAllByName(host).toList() }

    override suspend fun newChannel(): SocketChannel = SocketChannel.open()
}
