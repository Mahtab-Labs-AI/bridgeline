package com.wifiproxy.app.session

import kotlinx.coroutines.flow.StateFlow

/** Lifecycle of the shared-proxy session. Mirrors the real session state machine (design spec §3). */
enum class SessionPhase { IDLE, STARTING, RUNNING, DEGRADED, STOPPING }

enum class ClientPlatform { ANDROID, IPHONE }

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

    /** Returns false (and rejects the change) if [HostSettings.maxClients] would drop below the active slot count. */
    fun updateSettings(settings: HostSettings): Boolean
}
