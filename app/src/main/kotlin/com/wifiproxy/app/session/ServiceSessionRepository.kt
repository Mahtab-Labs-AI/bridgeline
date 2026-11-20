package com.wifiproxy.app.session

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.wifiproxy.app.service.ProxySessionService
import com.wifiproxy.app.service.SessionHolder
import com.wifiproxy.app.service.UsbSessionController
import com.wifiproxy.core.clients.Slot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

private const val POLL_INTERVAL_MS = 1_000L
private const val PROXY_PORT = 8081

/**
 * The real [SessionRepository]: `start`/`stop` drive [ProxySessionService], and [session]/[log]
 * read the live [UsbSessionController] through [SessionHolder] (there's no controller - and so no
 * slots - until the service has actually been started once).
 *
 * `addDevice`/`inviteFor` return null: Wi-Fi Direct devices are Plan 2b. [checks] and [settings]
 * are plain in-memory state; [refreshChecks] re-runs the real Android checks, since permissions
 * and the battery exemption can change outside the app (call it on resume).
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

    private val _settings = MutableStateFlow(
        HostSettings(ssidSuffix = "", password = "", maxClients = 1, port = PROXY_PORT, prefer5Ghz = false),
    )
    override val settings: StateFlow<HostSettings> = _settings.asStateFlow()

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
        .flatMapLatest { controller -> controller?.let(::sessionFlowFor) ?: flowOf(idleUi()) }
        .stateIn(repositoryScope, SharingStarted.WhileSubscribed(5_000), idleUi())

    override val log: StateFlow<List<LogEntry>> = SessionHolder.controller
        .flatMapLatest { it?.log ?: flowOf(emptyList()) }
        .stateIn(repositoryScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun sessionFlowFor(controller: UsbSessionController) =
        combine(
            controller.state,
            controller.degradedReason,
            controller.cellularAvailable,
            controller.lastError,
            ticker,
        ) { phase, reason, cellular, lastError, _ ->
            buildUi(phase, reason, cellular, lastError, controller.registry?.slots().orEmpty())
        }

    private fun buildUi(
        phase: SessionPhase,
        reason: String?,
        cellularUp: Boolean,
        lastError: String?,
        slots: List<Slot>,
    ) = SessionUi(
        phase = phase,
        degradedReason = reason,
        cellularUp = cellularUp,
        ssid = "USB (adb forward)",
        proxyHost = "127.0.0.1",
        port = _settings.value.port,
        slots = slots.map { it.toSlotUi() },
        maxClients = _settings.value.maxClients,
        lastError = lastError,
    )

    private fun idleUi() = buildUi(
        phase = SessionPhase.IDLE,
        reason = null,
        cellularUp = false,
        lastError = null,
        slots = emptyList(),
    )

    private fun Slot.toSlotUi() = SlotUi(
        id = id,
        name = name,
        platform = ClientPlatform.LAPTOP,
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

    override fun addDevice(name: String, platform: ClientPlatform): DeviceInvite? = null

    override fun inviteFor(slotId: String): DeviceInvite? = null

    /** Goes through the controller, which only ever revokes via `ProxyServer.revoke`. */
    override fun revoke(slotId: String) {
        SessionHolder.controller.value?.revoke(slotId)
    }

    override fun updateSettings(settings: HostSettings): Boolean {
        _settings.value = settings
        return true
    }

    override fun refreshChecks() {
        _checks.value = buildChecks()
    }

    override fun close() {
        repositoryJob.cancel()
    }

    private fun buildChecks(): List<SetupCheck> = listOf(
        notificationCheck(),
        cellularCheck(),
        usbDebuggingCheck(),
        batteryOptimisationCheck(),
    )

    private fun notificationCheck(): SetupCheck {
        val enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        return SetupCheck(
            title = "Notifications allowed",
            detail = "The ongoing \"Sharing over USB\" notification needs notification permission.",
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
