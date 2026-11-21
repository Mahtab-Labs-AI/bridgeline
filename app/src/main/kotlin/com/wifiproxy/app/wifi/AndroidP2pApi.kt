package com.wifiproxy.app.wifi

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import kotlin.coroutines.resume

private const val CALL_TIMEOUT_MS = 10_000L

/**
 * [P2pApi] over [WifiP2pManager]. Callbacks arrive on the main looper. Every call checks the
 * runtime permission first (NEARBY_WIFI_DEVICES on API 33+, ACCESS_FINE_LOCATION on 29-32) and
 * reports [CreateResult.MISSING_PERMISSION] instead of throwing. On API 29-32, [createGroup] also
 * reports [CreateResult.LOCATION_OFF] while Location mode is off.
 *
 * Every [WifiP2pManager] callback wait is bounded by [CALL_TIMEOUT_MS] via `withTimeoutOrNull`
 * (never `withTimeout`, whose `TimeoutCancellationException` would look like real cancellation to
 * [P2pGroupLink]), so a listener that never fires can't stall the link's serialized start/stop.
 */
class AndroidP2pApi(private val context: Context) : P2pApi {
    private val manager: WifiP2pManager? = context.getSystemService(WifiP2pManager::class.java)
    private val channel: WifiP2pManager.Channel? = manager?.initialize(context, Looper.getMainLooper(), null)

    private val _p2pEnabled = MutableStateFlow(context.getSystemService(WifiManager::class.java)?.isWifiEnabled == true)
    override val p2pEnabled: StateFlow<Boolean> = _p2pEnabled.asStateFlow()

    private val _connectionChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    override val connectionChanged: SharedFlow<Unit> = _connectionChanged.asSharedFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION ->
                    _p2pEnabled.value = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) ==
                        WifiP2pManager.WIFI_P2P_STATE_ENABLED
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> _connectionChanged.tryEmit(Unit)
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION) // sticky: delivers the current state now
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun hasPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    /** No LocationManager at all is treated as "on": nothing to ask the user to turn on. */
    private fun locationEnabled(): Boolean =
        context.getSystemService(LocationManager::class.java)?.let { LocationManagerCompat.isLocationEnabled(it) } ?: true

    @SuppressLint("MissingPermission") // checked by hasPermission() just above every call
    override suspend fun createGroup(request: GroupRequest): CreateResult {
        val m = manager ?: return CreateResult.Failure(CreateResult.P2P_UNSUPPORTED)
        val ch = channel ?: return CreateResult.Failure(CreateResult.P2P_UNSUPPORTED)
        if (!hasPermission()) return CreateResult.Failure(CreateResult.MISSING_PERMISSION)
        // On API 29-32 Wi-Fi Direct also needs Location mode on; the framework fails the create
        // with a generic error otherwise, which would just be retried until the failure limit.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && !locationEnabled()) {
            return CreateResult.Failure(CreateResult.LOCATION_OFF)
        }
        val config = WifiP2pConfig.Builder()
            .setNetworkName(request.ssid)
            .setPassphrase(request.passphrase)
            .setGroupOperatingBand(
                if (request.band == GroupBand.GHZ_5) WifiP2pConfig.GROUP_OWNER_BAND_5GHZ else WifiP2pConfig.GROUP_OWNER_BAND_AUTO,
            )
            .enablePersistentMode(false)
            .build()
        return try {
            withTimeoutOrNull(CALL_TIMEOUT_MS) {
                suspendCancellableCoroutine<CreateResult> { cont ->
                    m.createGroup(ch, config, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() = cont.resume(CreateResult.Success)
                        override fun onFailure(reason: Int) = cont.resume(CreateResult.Failure(reason))
                    })
                }
            } ?: CreateResult.Failure(CreateResult.ERROR)
        } catch (_: SecurityException) {
            CreateResult.Failure(CreateResult.MISSING_PERMISSION)
        }
    }

    override suspend fun removeGroup() {
        val m = manager ?: return
        val ch = channel ?: return
        try {
            withTimeoutOrNull(CALL_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    m.removeGroup(ch, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() = cont.resume(Unit)
                        override fun onFailure(reason: Int) = cont.resume(Unit) // no group, or busy: nothing to undo
                    })
                }
            } // null (timeout): nothing more we can do; return normally either way.
        } catch (_: SecurityException) {
            // Nothing we can remove without the permission; the group dies with Wi-Fi anyway.
        }
    }

    @SuppressLint("MissingPermission") // checked by hasPermission()
    override suspend fun groupInfo(): GroupInfo? {
        val m = manager ?: return null
        val ch = channel ?: return null
        if (!hasPermission()) return null
        return try {
            val group = withTimeoutOrNull(CALL_TIMEOUT_MS) {
                suspendCancellableCoroutine<WifiP2pGroup?> { cont -> m.requestGroupInfo(ch) { cont.resume(it) } }
            } ?: return null // timeout, or no group: either way there is nothing to report
            if (!group.isGroupOwner) return null
            // requestConnectionInfo's address can legitimately be null (not yet resolved); wrap the
            // result so a timeout (outer null) is distinguishable from a resolved-but-null address.
            val connection = withTimeoutOrNull(CALL_TIMEOUT_MS) {
                suspendCancellableCoroutine<ConnectionResult> { cont ->
                    m.requestConnectionInfo(ch) { info -> cont.resume(ConnectionResult(info?.groupOwnerAddress as? Inet4Address)) }
                }
            } ?: return null // timeout
            // For a group we own, the system reports the actual configured passphrase (or null if
            // the firmware ignored it); pass it through unchanged.
            GroupInfo(group.networkName, group.passphrase, connection.ownerAddress)
        } catch (_: SecurityException) {
            null
        }
    }

    fun close() {
        runCatching { context.unregisterReceiver(receiver) }
        channel?.close()
    }

    /** Distinguishes a resolved-but-null owner address from a timed-out call (see [groupInfo]). */
    private data class ConnectionResult(val ownerAddress: Inet4Address?)
}
