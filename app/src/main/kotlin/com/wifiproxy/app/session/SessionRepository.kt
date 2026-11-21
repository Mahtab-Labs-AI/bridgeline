package com.wifiproxy.app.session

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private val ALWAYS_ALLOWED: StateFlow<Boolean?> = MutableStateFlow<Boolean?>(true).asStateFlow()
private val ALWAYS_READY: StateFlow<SettingsStatus> = MutableStateFlow(SettingsStatus.READY).asStateFlow()

/** See [SessionRepository.settingsStatus]. */
enum class SettingsStatus { LOADING, READY, UNREADABLE }

/** What [SessionRepository.updateSettings] did. */
enum class SettingsUpdate { SAVED, REJECTED, CREDENTIALS_UNREADABLE }

/** Lifecycle of the shared-proxy session. Mirrors the real session state machine (design spec §3). */
enum class SessionPhase { IDLE, STARTING, RUNNING, DEGRADED, STOPPING }

/** LAPTOP is the pinned USB slot (design spec §8a); it has no QR/invite flow of its own.
 *  UNKNOWN is an auto slot (open admission, D8): a device that just joined, with no invite. Neither
 *  is ever persisted (the slot codec drops both). */
enum class ClientPlatform { ANDROID, IPHONE, LAPTOP, UNKNOWN }

data class SlotUi(
    val id: String,
    val name: String,
    val platform: ClientPlatform,
    val ip: String?,
    val bytesUp: Long,
    val bytesDown: Long,
    val activeTunnels: Int,
    /** An auto slot (open admission, D8): admitted by its IP alone, with no invite. */
    val auto: Boolean = false,
    /** Mirrors [com.wifiproxy.core.clients.Slot.lastActivityMs]; drives the "Active now"/"Idle N
     *  min" line shown for auto devices under open admission. */
    val lastActivityMs: Long = 0L,
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
    /** True while the Wi-Fi Direct group is Up and its listener runs, i.e. devices can be invited. */
    val wifiUp: Boolean = false,
    /** Where devices reach the proxy right now: the Wi-Fi listener while it serves, else the USB
     *  one (`127.0.0.1:8081 (USB)`) while that listener is up, else null (idle, or a Wi-Fi-only
     *  session whose Wi-Fi isn't up yet). */
    val proxyAddress: String? = null,
    /** (D8) The running session's Wi-Fi admission while sharing, else what the next start will
     *  use: true means no per-device code is needed. False for the demo, which shows invites. */
    val openAdmission: Boolean = false,
    /** (B1) A VPN applies to this app: its sockets, replies to Wi-Fi Direct devices included, go
     *  into the tunnel. */
    val vpnActive: Boolean = false,
    /** Wall-clock time this [SessionUi] was built, so "Idle N min" (derived from
     *  [SlotUi.lastActivityMs]) keeps advancing on the 1 Hz poll even when nothing else about the
     *  session has changed. Always 0 for the fake, which has no idle auto slots to show. */
    val nowMs: Long = 0L,
)

data class HostSettings(
    val ssidSuffix: String,
    val password: String,
    val maxClients: Int,
    val port: Int,
    val prefer5Ghz: Boolean,
) {
    /** Spec §6: never the password. */
    override fun toString() =
        "HostSettings(ssidSuffix=$ssidSuffix, password=****, maxClients=$maxClients, port=$port, prefer5Ghz=$prefer5Ghz)"
}

data class DeviceInvite(
    val slotId: String,
    val wifiQr: String,
    val pacUrl: String,
    val mobileConfigUrl: String,
) {
    /** Spec §6: the QR carries the Wi-Fi password and both URLs the slot token. */
    override fun toString() = "DeviceInvite(slotId=$slotId, wifiQr=****, pacUrl=****, mobileConfigUrl=****)"
}

/** What any device needs to join under open admission (D8): the Wi-Fi network plus the fixed
 *  proxy addresses, the same for every device. */
data class OpenInvite(
    val wifiQr: String,
    val host: String,
    val port: Int,
    val pacUrl: String,
    val mobileConfigUrl: String,
) {
    /** Spec §6: the QR carries the Wi-Fi password. */
    override fun toString() = "OpenInvite(wifiQr=****, host=$host, port=$port)"
}

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

    /** The shared setup for every device under open admission, or null unless a session with
     *  open admission has its Wi-Fi listener serving. Null for the demo. */
    fun openInvite(): OpenInvite? = null

    fun revoke(slotId: String)

    /** [SettingsUpdate.REJECTED] (changing nothing) if [HostSettings.maxClients] would drop below
     *  the slot count, or if the settings are invalid; [SettingsUpdate.CREDENTIALS_UNREADABLE]
     *  (changing nothing) if the stored Wi-Fi settings can't be read right now. */
    fun updateSettings(settings: HostSettings): SettingsUpdate

    /** Whether [settings] holds the real saved values yet. Until [SettingsStatus.READY], its name
     *  and password are blanks that mean nothing, and must not be edited from or saved back.
     *  Always READY for the fake. */
    val settingsStatus: StateFlow<SettingsStatus> get() = ALWAYS_READY

    /** Re-reads the saved settings, e.g. after [SettingsStatus.UNREADABLE]. A no-op for the fake. */
    fun reloadSettings() {}

    /** Whether Wi-Fi Direct credentials are saved, so a Start needs the Wi-Fi Direct permission.
     *  Answered without decrypting (so it is right even before [settings] has loaded, and while
     *  the credentials are unreadable). */
    fun isWifiConfigured(): Boolean = settings.value.password.isNotEmpty()

    /** Whether the "USB sharing (laptop)" switch may be turned on (user decision D7: the laptop
     *  needs a free seat next to every saved Wi-Fi device); null until it has been computed. A
     *  cached value, cheap to read from composition; always true for the fake. */
    val usbSwitchAllowed: StateFlow<Boolean?> get() = ALWAYS_ALLOWED

    /** "Require a code for each device" (D8): false (the default) lets any device that joined the
     *  group use the proxy, up to the device limit. Applies the next time sharing starts. */
    val requireDeviceCodes: StateFlow<Boolean>

    fun setRequireDeviceCodes(value: Boolean)

    /** [usbSwitchAllowed]'s current value; false while it is still unknown. */
    fun canEnableUsb(): Boolean = usbSwitchAllowed.value == true

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
