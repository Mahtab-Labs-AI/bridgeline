package com.wifiproxy.app.egress

import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.InetAddresses
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.CancellationSignal
import com.wifiproxy.core.egress.Egress
import com.wifiproxy.core.egress.EgressUnavailableException
import com.wifiproxy.core.net.Cidr
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.net.InetAddress
import java.net.NetworkInterface
import java.nio.channels.SocketChannel
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * What `UsbSessionController` needs from the cellular egress: the [Egress] contract `ProxyServer`
 * uses, plus the lifecycle and liveness signals a JVM test can fake without touching
 * `ConnectivityManager`. [CellularEgress] is the only real implementation.
 */
interface SessionEgress : Egress {
    /** Mirrors [Egress.isAvailable] as a flow so the controller can react to egress loss/regain
     *  instead of polling. */
    val available: StateFlow<Boolean>

    /** Fires (on an arbitrary thread) whenever the tracked network's link addresses may have
     *  changed; the controller refreshes the proxy's destination filter in response. */
    var onLinkChange: (() -> Unit)?

    /** Increments once per *distinct* network handed to the controller by `onAvailable` (I5): the
     *  controller collects this (skipping the first value it observes) to know when it must stop
     *  and restart the proxy server against the new network rather than leave tunnels bound to a
     *  dead one. */
    val networkGeneration: StateFlow<Long>

    fun start()
    fun stop()

    /** The host's own interface addresses, as /32 or /128 [Cidr]s, so the destination filter
     *  never lets the proxy dial back into the host itself. */
    fun hostAddresses(): List<Cidr>
}

/**
 * [Egress] bound to the device's cellular network.
 *
 * One long-lived instance swaps its current [Network] internally: [start] registers a
 * [ConnectivityManager.NetworkCallback] for `TRANSPORT_CELLULAR` + `NET_CAPABILITY_INTERNET` and
 * tracks the network it grants; [stop] unregisters it. [resolve] and [newChannel] always read
 * the current network, so `ProxyServer` and `Dialer.forEgress` can hold one instance across
 * network changes.
 */
class CellularEgress(
    private val connectivityManager: ConnectivityManager,
) : SessionEgress {

    private val _network = MutableStateFlow<Network?>(null)

    /** The cellular network currently granted by the system, or `null` while there is none. */
    val network: StateFlow<Network?> = _network.asStateFlow()

    override val isAvailable: Boolean
        get() = _network.value != null

    private val _available = MutableStateFlow(false)

    override val available: StateFlow<Boolean> = _available.asStateFlow()

    private val _isValidated = MutableStateFlow(false)

    override val isValidated: Boolean
        get() = _isValidated.value

    private val _networkGeneration = MutableStateFlow(0L)

    override val networkGeneration: StateFlow<Long> = _networkGeneration.asStateFlow()

    /**
     * Fires (on the connectivity callback thread) whenever the tracked network's
     * [LinkProperties] change. Callers should treat this as a hint to recompute [hostAddresses].
     */
    override var onLinkChange: (() -> Unit)? = null

    private val resolver = DnsResolutionEngine<Network>(
        query = ::queryDnsResolver,
        parseLiteral = { host -> if (InetAddresses.isNumericAddress(host)) InetAddresses.parseNumericAddress(host) else null },
    )

    // @Volatile so a late callback queued just before stop()'s unregister (and delivered just
    // after) always sees the field update promptly (I3): every override below checks `callback
    // !== this` first and returns immediately if it's no longer the installed callback.
    @Volatile
    private var callback: ConnectivityManager.NetworkCallback? = null

    /** Starts tracking the cellular network. Call [stop] before calling this again. */
    override fun start() {
        check(callback == null) { "CellularEgress already started" }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val newCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (callback !== this) return
                val previous = _network.value
                _network.value = network
                _available.value = true
                if (previous != network) {
                    // I5: a distinct network means any earlier validation no longer applies, and
                    // the controller must rebuild the proxy server against the new network.
                    _isValidated.value = false
                    _networkGeneration.value += 1
                }
            }

            override fun onLost(network: Network) {
                if (callback !== this) return
                if (_network.value == network) {
                    _network.value = null
                    _available.value = false
                    _isValidated.value = false
                }
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (callback !== this) return
                if (_network.value == network) {
                    _isValidated.value = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                }
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                if (callback !== this) return
                onLinkChange?.invoke()
            }
        }
        // Publish the field *before* registering (R1 - fixes a regression): ConnectivityService
        // can invoke a NetworkCallback's onAvailable synchronously, on its own thread, from inside
        // requestNetwork() itself, before requestNetwork() has even returned to us. onAvailable
        // fires at most once per network, so if callback were published only after requestNetwork
        // returned, the identity guard at the top of onAvailable (`callback !== this`) would see
        // the field still null/stale and silently drop that first - and only - callback, leaving
        // the session stuck in DEGRADED forever. Publishing first means that guard already sees
        // `newCallback` installed by the time any such reentrant call can land.
        //
        // If requestNetwork throws instead, undo the publish so a retried start() sees a clean
        // slate (callback == null) rather than permanently failing its own "already started"
        // check; stop() is never involved here since nothing was ever registered.
        callback = newCallback
        try {
            connectivityManager.requestNetwork(request, newCallback)
        } catch (e: RuntimeException) {
            callback = null
            throw e
        }
    }

    /** Stops tracking the cellular network. Safe to call when not started, and tolerates
     *  `unregisterNetworkCallback` throwing [IllegalArgumentException] for a callback the system
     *  never actually registered (I3). */
    override fun stop() {
        val current = callback
        callback = null
        if (current != null) {
            try {
                connectivityManager.unregisterNetworkCallback(current)
            } catch (_: IllegalArgumentException) {
                // Never registered (requestNetwork threw before `callback` was published) or
                // already unregistered by an earlier stop() - either way, there is nothing left to
                // undo.
            }
        }
        _network.value = null
        _available.value = false
        _isValidated.value = false
    }

    override suspend fun resolve(host: String): List<InetAddress> = resolver.resolve(_network.value, host)

    // No withContext/dispatcher hop here, deliberately: open()+bindSocket() are quick local calls
    // (ProxyServer already invokes this from Dispatchers.Default, not the NioLoop thread), and a
    // suspending dispatcher switch gives cancellation a window to fire at the withContext resume
    // point *after* the channel is open and bound but *before* it's returned to the caller — which
    // would leak an already cellular-bound, live channel that nothing ever closes. Keeping this
    // body synchronous (just `suspend` in the signature, as the Egress interface requires) means
    // there is no suspension point between opening the channel and returning or closing it.
    override suspend fun newChannel(): SocketChannel {
        val network = _network.value ?: throw EgressUnavailableException("no cellular network")
        val channel = SocketChannel.open()
        try {
            network.bindSocket(channel.socket())
            return channel
        } catch (e: Exception) {
            channel.close()
            if (_network.value == null) {
                throw EgressUnavailableException("cellular network lost while binding").apply { initCause(e) }
            }
            throw e as? IOException ?: IOException("failed to bind egress channel", e)
        }
    }

    /**
     * The host's own interface addresses across every network the system knows about (cellular,
     * stacked CLAT, Wi-Fi STA, …), each as a /32 (IPv4) or /128 (IPv6) [Cidr]. Passed to
     * `DestinationFilter.default(...)` so the proxy never dials back into the host itself.
     *
     * Unions two sources, since either alone misses something:
     * - `LinkProperties` per [ConnectivityManager.getAllNetworks], via `getLinkAddresses()`
     *   (`getAllLinkAddresses()`, named in the design spec, is not present in the compileSdk 36
     *   public SDK);
     * - [NetworkInterface.getNetworkInterfaces] for every *up* interface, which also catches the
     *   stacked CLAT `v4-rmnet*` interface and anything the system doesn't expose as a `Network`.
     *
     * [hostCidrsFrom] does the actual dedupe/conversion; this method is just the (untestable on
     * the JVM) Android-API collection step.
     */
    @Suppress("DEPRECATION") // getAllNetworks() is deprecated in favour of callbacks, but there is
    // no non-deprecated way to enumerate every currently known network on demand, which is
    // exactly what the spec asks for here; this is the call the design/plan name explicitly.
    override fun hostAddresses(): List<Cidr> {
        val addresses = mutableListOf<InetAddress>()
        for (net in connectivityManager.allNetworks) {
            val linkProperties = connectivityManager.getLinkProperties(net) ?: continue
            for (linkAddress in linkProperties.linkAddresses) {
                addresses.add(linkAddress.address)
            }
        }
        // getNetworkInterfaces() is declared to throw SocketException on an I/O error reading the
        // system's interface list; isUp() carries the same declaration per-interface. Either
        // failing shouldn't discard what we already collected from ConnectivityManager above.
        runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull()?.let { interfaces ->
            for (iface in interfaces) {
                if (!runCatching { iface.isUp }.getOrDefault(false)) continue
                for (address in iface.inetAddresses) {
                    addresses.add(address)
                }
            }
        }
        return hostCidrsFrom(addresses)
    }

    private fun queryDnsResolver(network: Network, host: String, onResult: (DnsOutcome) -> Unit): () -> Unit {
        val signal = CancellationSignal()
        DnsResolver.getInstance().query(
            network,
            host,
            DnsResolver.FLAG_EMPTY,
            dnsExecutor,
            signal,
            object : DnsResolver.Callback<List<InetAddress>> {
                override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
                    onResult(DnsOutcome.Answer(answer))
                }

                override fun onError(error: DnsResolver.DnsException) {
                    onResult(DnsOutcome.Failure(error))
                }
            },
        )
        return { signal.cancel() }
    }

    companion object {
        // (R5) One executor for the whole process, never shut down: DnsResolver can still invoke
        // a query's result callback here after the CellularEgress instance that issued it has
        // already stop()'d (the answer was in flight when stop() ran) - the old per-start/per-stop
        // executor lifecycle shut this down in stop(), so that delivery threw
        // RejectedExecutionException on whatever thread DnsResolver happened to call back on,
        // which can be Main. Core size 0 with allowCoreThreadTimeOut still lets the single worker
        // thread exit when idle, exactly like the old per-instance executor did; only the pool
        // object itself now outlives any one CellularEgress/session.
        private val dnsExecutor: ExecutorService = ThreadPoolExecutor(
            0,
            1,
            30,
            TimeUnit.SECONDS,
            LinkedBlockingQueue(),
        ) { runnable -> Thread(runnable, "CellularEgress-dns") }.apply { allowCoreThreadTimeOut(true) }
    }
}
