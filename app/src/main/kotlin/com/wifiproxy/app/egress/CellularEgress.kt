package com.wifiproxy.app.egress

import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.CancellationSignal
import com.wifiproxy.core.egress.Egress
import com.wifiproxy.core.egress.EgressUnavailableException
import com.wifiproxy.core.net.Cidr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.nio.channels.SocketChannel
import java.util.concurrent.Executors

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
) : Egress {

    private val _network = MutableStateFlow<Network?>(null)

    /** The cellular network currently granted by the system, or `null` while there is none. */
    val network: StateFlow<Network?> = _network.asStateFlow()

    override val isAvailable: Boolean
        get() = _network.value != null

    private val _isValidated = MutableStateFlow(false)

    override val isValidated: Boolean
        get() = _isValidated.value

    /**
     * Fires (on the connectivity callback thread) whenever the tracked network's
     * [LinkProperties] change. Callers should treat this as a hint to recompute [hostAddresses].
     */
    var onLinkChange: (() -> Unit)? = null

    // Lives for the process; DnsResolver queries are infrequent enough that a single thread is
    // plenty, and there is no restart cost worth tearing this down across start()/stop() cycles.
    private val dnsExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "CellularEgress-dns")
    }

    private val resolver = DnsResolutionEngine<Network>(query = ::queryDnsResolver)

    private var callback: ConnectivityManager.NetworkCallback? = null

    /** Starts tracking the cellular network. Call [stop] before calling this again. */
    fun start() {
        check(callback == null) { "CellularEgress already started" }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val newCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                _network.value = network
            }

            override fun onLost(network: Network) {
                if (_network.value == network) {
                    _network.value = null
                    _isValidated.value = false
                }
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (_network.value == network) {
                    _isValidated.value = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                }
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                onLinkChange?.invoke()
            }
        }
        callback = newCallback
        connectivityManager.requestNetwork(request, newCallback)
    }

    /** Stops tracking the cellular network. Safe to call when not started. */
    fun stop() {
        callback?.let { connectivityManager.unregisterNetworkCallback(it) }
        callback = null
        _network.value = null
        _isValidated.value = false
    }

    override suspend fun resolve(host: String): List<InetAddress> = resolver.resolve(_network.value, host)

    override suspend fun newChannel(): SocketChannel {
        val network = _network.value ?: throw EgressUnavailableException("no cellular network")
        return withContext(Dispatchers.IO) {
            val channel = SocketChannel.open()
            try {
                network.bindSocket(channel.socket())
                channel
            } catch (e: Exception) {
                channel.close()
                if (_network.value == null) {
                    throw EgressUnavailableException("cellular network lost while binding").apply { initCause(e) }
                }
                throw e as? IOException ?: IOException("failed to bind egress channel", e)
            }
        }
    }

    /**
     * The host's own interface addresses across every network the system knows about (cellular,
     * stacked CLAT, Wi-Fi STA, …), each as a /32 (IPv4) or /128 (IPv6) [Cidr]. Passed to
     * `DestinationFilter.default(...)` so the proxy never dials back into the host itself.
     *
     * Uses `LinkProperties.getLinkAddresses()`: `getAllLinkAddresses()` (named in the design
     * spec) is not present in the compileSdk 36 public SDK; the base link's addresses already
     * include stacked-interface (CLAT) addresses on current Android versions.
     */
    @Suppress("DEPRECATION") // getAllNetworks() is deprecated in favour of callbacks, but there is
    // no non-deprecated way to enumerate every currently known network on demand, which is
    // exactly what the spec asks for here; this is the call the design/plan name explicitly.
    fun hostAddresses(): List<Cidr> {
        val addresses = mutableListOf<Cidr>()
        for (net in connectivityManager.allNetworks) {
            val linkProperties = connectivityManager.getLinkProperties(net) ?: continue
            for (linkAddress in linkProperties.linkAddresses) {
                val prefix = if (linkAddress.address is Inet4Address) 32 else 128
                addresses.add(Cidr(linkAddress.address, prefix))
            }
        }
        return addresses
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
}
