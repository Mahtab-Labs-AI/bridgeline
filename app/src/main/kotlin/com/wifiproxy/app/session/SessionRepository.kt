package com.wifiproxy.app.session

import android.content.Intent
import kotlinx.coroutines.flow.StateFlow

/** Lifecycle of the shared-proxy session. Mirrors the real session state machine (design spec §3). */
enum class SessionPhase { IDLE, STARTING, RUNNING, DEGRADED, STOPPING }

/** LAPTOP is the pinned USB slot (design spec §8a); it has no QR/invite flow of its own. */
enum class ClientPlatform { ANDROID, IPHONE, LAPTOP }

data class SlotUi(
    val id: String,
    val name: String,
    val platform: ClientPlatform,
    val ip: String?,
    val bytesUp: Long,
    val bytesDown: Long,
    val activeTunnels: Int,
)

data class SessionUi(
    val phase: SessionPhase,
    val degradedReason: String?,
    val cellularUp: Boolean,
    val ssid: String,
    val proxyHost: String,
    val port: Int,
    val slots: List<SlotUi>,
    val maxClients: Int,
    /** Set when the last `start()` itself failed and the session fell back to IDLE (C1); cleared
     *  by the next successful start. Null for the fake, which never fails to start. */
    val lastError: String? = null,
    /** True while the Wi-Fi Direct group is Up, i.e. devices can be invited. */
    val wifiUp: Boolean = false,
)

data class HostSettings(
    val ssidSuffix: String,
    val password: String,
    val maxClients: Int,
    val port: Int,
    val prefer5Ghz: Boolean,
)

data class DeviceInvite(
    val slotId: String,
    val wifiQr: String,
    val pacUrl: String,
    val mobileConfigUrl: String,
)

enum class CheckStatus { OK, ACTION_NEEDED, NOT_APPLICABLE }

data class SetupCheck(
    val title: String,
    val detail: String,
    val status: CheckStatus,
    /** Opens system settings to fix the problem, if there's a direct one; null when there isn't
     *  (e.g. the fake's checks, or a check with no single settings screen to send the user to). */
    val fixIntent: Intent? = null,
)

data class LogEntry(
    val timeMs: Long,
    val message: String,
)

/**
 * The seam between the UI and whatever actually runs the session. [FakeSessionRepository] backs the
 * UI mockup; Plan 2's real service-backed repository implements the same interface.
 */
interface SessionRepository {
    val session: StateFlow<SessionUi>
    val settings: StateFlow<HostSettings>
    val checks: StateFlow<List<SetupCheck>>
    val log: StateFlow<List<LogEntry>>

    fun start()
    fun stop()

    /** Creates a new slot if there's room, or returns null when [HostSettings.maxClients] slots are already taken. */
    fun addDevice(name: String, platform: ClientPlatform): DeviceInvite?

    fun inviteFor(slotId: String): DeviceInvite?

    fun revoke(slotId: String)

    /** Returns false (and rejects the change) if [HostSettings.maxClients] would drop below the
     *  slot count, or if the settings are invalid. */
    fun updateSettings(settings: HostSettings): Boolean

    /** Whether the "USB sharing (laptop)" switch may be turned on (user decision D7: the laptop
     *  needs a free seat next to every saved Wi-Fi device). Always true for the fake. */
    fun canEnableUsb(): Boolean = true

    /** Re-runs the setup checks. A no-op for the fake, which has no real permissions/exemptions
     *  to re-check; [ServiceSessionRepository] rebuilds [checks] from the live Android state.
     *  Call on every Setup-tab view and app resume (design spec §8: "re-checked on every app open"). */
    fun refreshChecks() {}

    /** Cancels this repository's own background work (collectors, pollers). [RepositoryProvider]
     *  calls this on the outgoing repository before swapping in a new one on a demo-mode change
     *  (I7), so switching modes back and forth can never pile up abandoned collectors. A no-op
     *  default since a repository with no background work of its own has nothing to cancel. */
    fun close() {}
}
