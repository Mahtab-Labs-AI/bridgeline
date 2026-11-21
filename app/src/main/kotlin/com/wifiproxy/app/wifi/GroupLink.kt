package com.wifiproxy.app.wifi

import com.wifiproxy.core.WifiCredentials
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.net.Inet4Address

data class LinkConfig(val credentials: WifiCredentials, val prefer5Ghz: Boolean)

sealed interface LinkState {
    data object Idle : LinkState
    /** Not usable right now, but [P2pGroupLink] is working on it; [reason] is user-facing. */
    data class Down(val reason: String) : LinkState
    data class Up(val ownerAddress: Inet4Address, val credentials: WifiCredentials) : LinkState
    /** Needs the user (spec §6 "Failed(needsUser)"); no further retries until stop()/start(). */
    data class Failed(val message: String) : LinkState
}

/** What [com.wifiproxy.app.service.SessionController] needs from the Wi-Fi Direct link. */
interface LinkManager {
    val state: StateFlow<LinkState>
    /** Starts (or restarts) the link with [config]. Returns immediately; follow [state]. */
    fun start(config: LinkConfig)
    /** Stops the link and removes the group. Suspends until the group is removed. */
    suspend fun stop()
    /** Asks the link to re-check that its group still exists (the 30 s watchdog). */
    fun checkHealth()
}

enum class GroupBand { GHZ_5, AUTO }
data class GroupRequest(val ssid: String, val passphrase: String, val band: GroupBand) {
    override fun toString() = "GroupRequest(ssid=$ssid, passphrase=****, band=$band)"
}
data class GroupInfo(val networkName: String, val passphrase: String?, val ownerAddress: Inet4Address?) {
    override fun toString() = "GroupInfo(networkName=$networkName, passphrase=****, ownerAddress=$ownerAddress)"
}

sealed interface CreateResult {
    data object Success : CreateResult
    /** [reason] is a `WifiP2pManager` failure code, or [MISSING_PERMISSION]. */
    data class Failure(val reason: Int) : CreateResult

    companion object {
        const val ERROR = 0            // WifiP2pManager.ERROR
        const val P2P_UNSUPPORTED = 1  // WifiP2pManager.P2P_UNSUPPORTED
        const val BUSY = 2             // WifiP2pManager.BUSY
        const val MISSING_PERMISSION = -1
    }
}

/**
 * The thin Android seam; [AndroidP2pApi] is the only real implementation.
 *
 * [P2pGroupLink.start] and [P2pGroupLink.stop] wait, non-cancellably, for the current run to
 * finish, including any API call it is in: a call that never returns stalls every later
 * start/stop forever. Implementations of [createGroup], [removeGroup] and [groupInfo] must
 * therefore be cancellable (respond to coroutine cancellation promptly, the way suspend functions
 * built on cancellable Android callbacks naturally are) or otherwise time-bounded; never a call
 * that can simply hang. Bound them with `withTimeoutOrNull`, not `withTimeout`: the latter's
 * `TimeoutCancellationException` looks like cancellation, and [P2pGroupLink] treats it as a
 * failure only because it checks whether its own coroutine was really cancelled.
 *
 * [connectionChanged] must be hot: it has to support being subscribed to at any time and start
 * delivering events from that point on, synchronously from the subscriber's point of view (the way
 * a [kotlinx.coroutines.flow.SharedFlow] is), not a cold flow that only starts listening for
 * system broadcasts once collection begins.
 */
interface P2pApi {
    /** Wi-Fi P2P enabled state (follows Wi-Fi on/off). */
    val p2pEnabled: StateFlow<Boolean>
    /** Emits whenever the system reports a P2P connection/group change. */
    val connectionChanged: Flow<Unit>
    suspend fun createGroup(request: GroupRequest): CreateResult
    /** Removes our group if one exists. Never throws. */
    suspend fun removeGroup()
    /** The current group as the system reports it, or null when there is none. */
    suspend fun groupInfo(): GroupInfo?
}
