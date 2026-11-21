package com.wifiproxy.app.session

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.wifiproxy.app.service.ProxySessionService
import com.wifiproxy.app.service.SessionHolder
import com.wifiproxy.app.service.SessionController
import com.wifiproxy.app.settings.AppPreferences
import com.wifiproxy.app.settings.HostConfigSource
import com.wifiproxy.app.wifi.LinkState
import com.wifiproxy.app.wifi.SlotStore
import com.wifiproxy.core.clients.Slot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

private const val POLL_INTERVAL_MS = 1_000L

/** Android's Wi-Fi Direct group-owner address, shown while the group isn't Up. */
private const val DEFAULT_GROUP_OWNER = "192.168.49.1"
private const val NO_WIFI_SSID = "USB only"

/**
 * The real [SessionRepository]: `start`/`stop` drive [ProxySessionService], and [session]/[log]
 * read the live [SessionController] through [SessionHolder] (there's no controller - and so no
 * slots - until the service has actually been started once).
 *
 * [settings] are the persisted host settings ([HostConfigSource]); device invites need a running
 * session with the Wi-Fi Direct group Up (plan decision 4). [refreshChecks] re-runs the real
 * Android checks, since permissions, Wi-Fi and the battery exemption can change outside the app
 * (call it on resume).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServiceSessionRepository(
    private val context: Context,
    private val scope: CoroutineScope,
) : SessionRepository {

    // A child Job of [scope] (I7): [close] cancels only this repository's own collectors/pollers,
    // never the activity-lifetime scope it was handed, so a demo-mode round trip can create and
    // close any number of these without leaking work on the shared parent.
    private val repositoryJob = Job(scope.coroutineContext[Job])
    private val repositoryScope = CoroutineScope(scope.coroutineContext + repositoryJob)

    private val prefs = AppPreferences(context)
    private val hostConfig = HostConfigSource.forContext(context)
    private val slotStore = SlotStore(HostConfigSource.secureStore(context))

    // One decrypt (a Keystore round trip, on Main) for both the settings and the SSID.
    private val initial = hostConfig.snapshot()

    private val _settings = MutableStateFlow(initial.settings)
    override val settings: StateFlow<HostSettings> = _settings.asStateFlow()

    /** The stored credentials' full SSID (`DIRECT-xy-<suffix>`), or null when none are set. */
    private val configuredSsid = MutableStateFlow(initial.ssid)

    private val host = combine(_settings, configuredSsid) { settings, ssid -> HostView(settings, ssid) }

    private val _checks = MutableStateFlow(buildChecks())
    override val checks: StateFlow<List<SetupCheck>> = _checks.asStateFlow()

    // Counters live on ClientRegistry, not a Flow (design spec §6: "stats by polling registry.slots()").
    private val ticker = flow {
        while (true) {
            emit(Unit)
            delay(POLL_INTERVAL_MS)
        }
    }

    // WhileSubscribed (M7), not Eagerly: nobody is polling registry.slots() once every screen
    // observing this has gone away (e.g. the app backgrounded with the session idle), and the
    // 5s grace period means a quick tab switch doesn't restart the ticker from scratch.
    override val session: StateFlow<SessionUi> = SessionHolder.controller
        .flatMapLatest { controller -> controller?.let(::sessionFlowFor) ?: host.map(::idleUi) }
        .stateIn(repositoryScope, SharingStarted.WhileSubscribed(5_000), idleUi(HostView(_settings.value, configuredSsid.value)))

    override val log: StateFlow<List<LogEntry>> = SessionHolder.controller
        .flatMapLatest { it?.log ?: flowOf(emptyList()) }
        .stateIn(repositoryScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // (R6) Stops the 1 Hz poll entirely while IDLE: flatMapLatest on phase so a session screen left
    // open on an idle session doesn't keep this process (and ServiceSessionRepository's own
    // WhileSubscribed collectors) waking up once a second for nothing to show.
    private fun tickerForPhase(phase: SessionPhase): Flow<Unit> =
        if (phase == SessionPhase.IDLE) flowOf(Unit) else ticker

    // Six inputs: the five controller flows are combined first, then with the host settings and
    // the ticker (kotlinx's typed combine overloads stop at five).
    private fun sessionFlowFor(controller: SessionController) =
        combine(
            combine(
                controller.state,
                controller.degradedReason,
                controller.cellularAvailable,
                controller.lastError,
                controller.linkState,
            ) { phase, reason, cellular, lastError, link -> Live(phase, reason, cellular, lastError, link) },
            host,
            controller.state.flatMapLatest(::tickerForPhase),
        ) { live, hostView, _ -> buildUi(live, hostView, controller) }

    /** The controller's state, as one value for [buildUi]. */
    private data class Live(
        val phase: SessionPhase,
        val reason: String?,
        val cellularUp: Boolean,
        val lastError: String?,
        val link: LinkState,
    )

    private data class HostView(val settings: HostSettings, val ssid: String?)

    private fun buildUi(live: Live, host: HostView, controller: SessionController?): SessionUi {
        val up = live.link as? LinkState.Up
        return SessionUi(
            phase = live.phase,
            degradedReason = live.reason,
            cellularUp = live.cellularUp,
            ssid = up?.credentials?.ssid ?: host.ssid ?: NO_WIFI_SSID,
            proxyHost = up?.ownerAddress?.hostAddress ?: DEFAULT_GROUP_OWNER,
            port = controller?.wifiPort ?: host.settings.port,
            slots = controller?.registry?.slots().orEmpty().map { it.toSlotUi(controller?.platformOf(it.id)) },
            maxClients = host.settings.maxClients,
            lastError = live.lastError,
            wifiUp = up != null,
        )
    }

    private fun idleUi(host: HostView) = buildUi(
        Live(phase = SessionPhase.IDLE, reason = null, cellularUp = false, lastError = null, link = LinkState.Idle),
        host,
        controller = null,
    )

    private fun Slot.toSlotUi(platform: ClientPlatform?) = SlotUi(
        id = id,
        name = name,
        platform = platform ?: ClientPlatform.ANDROID,
        ip = boundIp?.hostAddress,
        bytesUp = bytesUp,
        bytesDown = bytesDown,
        activeTunnels = activeTunnels,
    )

    override fun start() {
        context.startForegroundService(ProxySessionService.startIntent(context))
    }

    override fun stop() {
        context.startService(ProxySessionService.stopIntent(context))
    }

    /** LAPTOP is only ever the pinned USB slot (and the slot codec drops it on load), so it can't
     *  be added as a Wi-Fi device. */
    override fun addDevice(name: String, platform: ClientPlatform): DeviceInvite? {
        if (platform == ClientPlatform.LAPTOP) return null
        val controller = SessionHolder.controller.value ?: return null
        return controller.addDevice(name, platform)?.let { inviteFor(it.id) }
    }

    /** Null unless the group is Up (the PAC URL needs the group-owner address) and [slotId] is a
     *  live, non-pinned slot. */
    override fun inviteFor(slotId: String): DeviceInvite? {
        val controller = SessionHolder.controller.value ?: return null
        val up = controller.linkState.value as? LinkState.Up ?: return null
        val slot = controller.registry?.slots()?.firstOrNull { it.id == slotId && !it.pinned } ?: return null
        val port = controller.wifiPort ?: return null
        val owner = up.ownerAddress.hostAddress ?: return null
        return DeviceInvite(
            slotId = slotId,
            wifiQr = QrPayloads.wifiQr(up.credentials.ssid, up.credentials.password),
            pacUrl = QrPayloads.pacUrl(owner, port, slot.token),
            mobileConfigUrl = QrPayloads.mobileConfigUrl(owner, port, slot.token),
        )
    }

    /** Goes through the controller, which only ever revokes via `ProxyServer.revoke`. */
    override fun revoke(slotId: String) {
        SessionHolder.controller.value?.revoke(slotId)
    }

    /** See [SettingsSaver]: D7 against the stored devices, a password change deletes them, and a
     *  name or password change restarts a running session. The Stop then Start become a restart
     *  through the service's pending-start path (M-b). */
    private val saver = SettingsSaver(
        hostConfig = hostConfig,
        slotStore = slotStore,
        usbEnabled = { prefs.usbSharingEnabled },
        sessionActive = ::sessionActive,
        restart = {
            stop()
            start()
        },
    )

    /**
     * Applies [settings] through [saver]. At most one decrypt and one encrypt (Keystore round
     * trips): [HostConfigSource.update] returns the new state instead of it being read back.
     */
    override fun updateSettings(settings: HostSettings): Boolean {
        val snapshot = saver.save(settings) ?: return false
        _settings.value = snapshot.settings
        configuredSsid.value = snapshot.ssid
        refreshChecks()
        return true
    }

    /** (D7) Whether the USB switch may be turned on: every saved Wi-Fi device plus the laptop's
     *  seat must fit `maxClients`. */
    override fun canEnableUsb(): Boolean = SeatRules.canEnableUsb(slotStore.load().size, prefs.maxClients)

    private fun sessionActive(): Boolean = when (SessionHolder.controller.value?.state?.value) {
        SessionPhase.STARTING, SessionPhase.RUNNING, SessionPhase.DEGRADED -> true
        else -> false
    }

    override fun refreshChecks() {
        _checks.value = buildChecks()
    }

    override fun close() {
        repositoryJob.cancel()
    }

    private fun buildChecks(): List<SetupCheck> = listOf(
        notificationCheck(),
        wifiPasswordCheck(),
        p2pPermissionCheck(),
        wifiOnCheck(),
        cellularCheck(),
        usbDebuggingCheck(),
        batteryOptimisationCheck(),
    )

    /** No fix intent: the password is set on the Settings tab. */
    private fun wifiPasswordCheck(): SetupCheck {
        val set = _settings.value.password.isNotEmpty()
        return SetupCheck(
            title = "Wi-Fi password set",
            detail = "Needed for Wi-Fi Direct sharing",
            status = if (set) CheckStatus.OK else CheckStatus.ACTION_NEEDED,
        )
    }

    /** NEARBY_WIFI_DEVICES on API 33+, ACCESS_FINE_LOCATION on 29-32 (what AndroidP2pApi checks). */
    private fun p2pPermissionCheck(): SetupCheck {
        val nearby = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        val permission = if (nearby) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
        val granted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        return SetupCheck(
            title = if (nearby) "Nearby devices permission" else "Location permission",
            detail = "Wi-Fi Direct needs it to create the sharing group.",
            status = if (granted) CheckStatus.OK else CheckStatus.ACTION_NEEDED,
            fixIntent = if (granted) {
                null
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())
            },
        )
    }

    private fun wifiOnCheck(): SetupCheck {
        val on = context.getSystemService(WifiManager::class.java)?.isWifiEnabled == true
        return SetupCheck(
            title = "Wi-Fi on",
            detail = "Wi-Fi Direct sharing needs Wi-Fi turned on (it doesn't need a Wi-Fi network).",
            status = if (on) CheckStatus.OK else CheckStatus.ACTION_NEEDED,
            fixIntent = if (on) null else Intent(Settings.Panel.ACTION_WIFI),
        )
    }

    private fun notificationCheck(): SetupCheck {
        val enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        return SetupCheck(
            title = "Notifications allowed",
            detail = "The ongoing \"Sharing mobile data\" notification needs notification permission.",
            status = if (enabled) CheckStatus.OK else CheckStatus.ACTION_NEEDED,
            fixIntent = if (enabled) {
                null
            } else {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            },
        )
    }

    // allNetworks() is deprecated in favour of callbacks, but there is no non-deprecated way to
    // ask "is there a cellular network right now" on demand (same tradeoff as CellularEgress.hostAddresses).
    @Suppress("DEPRECATION")
    private fun cellularCheck(): SetupCheck {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        val hasCellular = connectivityManager?.allNetworks.orEmpty().any { network ->
            val caps = connectivityManager?.getNetworkCapabilities(network)
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        return SetupCheck(
            title = "Cellular data available",
            detail = "The host needs an active cellular connection to share.",
            status = if (hasCellular) CheckStatus.OK else CheckStatus.ACTION_NEEDED,
            fixIntent = if (hasCellular) null else Intent(Settings.ACTION_WIRELESS_SETTINGS),
        )
    }

    private fun usbDebuggingCheck(): SetupCheck {
        val enabled = Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
        return SetupCheck(
            title = "USB debugging enabled",
            detail = "The laptop's connect-usb.ps1 needs adb, which needs USB debugging turned on here.",
            status = if (enabled) CheckStatus.OK else CheckStatus.ACTION_NEEDED,
            fixIntent = if (enabled) null else Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
        )
    }

    private fun batteryOptimisationCheck(): SetupCheck {
        val powerManager = context.getSystemService(PowerManager::class.java)
        val exempt = powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
        return SetupCheck(
            title = "Battery optimisation",
            detail = "Exempt Wi-Fi Proxy from battery optimisation so Android doesn't kill it in the background.",
            status = if (exempt) CheckStatus.OK else CheckStatus.ACTION_NEEDED,
            fixIntent = if (exempt) {
                null
            } else {
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${context.packageName}"),
                )
            },
        )
    }
}
