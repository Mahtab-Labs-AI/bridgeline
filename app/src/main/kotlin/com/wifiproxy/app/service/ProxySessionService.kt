package com.wifiproxy.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.wifiproxy.app.MainActivity
import com.wifiproxy.app.egress.CellularEgress
import com.wifiproxy.app.session.SessionPhase
import com.wifiproxy.app.ui.formatBytes
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val CHANNEL_ID = "usb_sharing"
private const val NOTIFICATION_ID = 1
private const val TAG = "ProxySessionService"

/** Safety cap so a leaked wake lock can't drain the battery forever; [startWakeLockManagement]
 *  re-acquires well inside this window (every [WAKE_LOCK_RENEW_INTERVAL_MS]) so it never lapses
 *  while actually RUNNING/DEGRADED (I4). */
private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L
private const val WAKE_LOCK_RENEW_INTERVAL_MS = 5 * 60 * 1000L

/** How often the foreground notification's byte counters are refreshed while RUNNING/DEGRADED
 *  (M4); also the cadence at which a newly-granted notification permission gets picked up. */
private const val NOTIFICATION_REFRESH_INTERVAL_MS = 5_000L

/**
 * Hosts [UsbSessionController] as a `connectedDevice` foreground service (design spec §3, §7,
 * §8a). [onCreate] calls `startForeground()` immediately - before building the (Android-API-
 * touching) egress and controller - so the service is never caught running foreground work
 * without a notification.
 *
 * (C2) [serviceScope] runs on `Dispatchers.Default.limitedParallelism(1)`, so every coroutine
 * [UsbSessionController] launches on it (start, stop, the watchdog, network-change handling, link-
 * change filter refreshes) is strictly serialized against every other one.
 */
class ProxySessionService : Service() {

    private val exceptionHandler = CoroutineExceptionHandler { _, e ->
        // (I2) A crash in any coroutine launched on serviceScope must never bring down the
        // process; SupervisorJob already stops it propagating to sibling children, this just
        // makes sure it's not silently swallowed either.
        Log.e(TAG, "Uncaught exception in ProxySessionService's scope", e)
    }
    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default.limitedParallelism(1) + exceptionHandler,
    )

    private var controller: UsbSessionController? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeLockJob: Job? = null
    private var notificationJob: Job? = null

    /** The most recent `startId` seen by [onStartCommand], always what [stopSelf] is called with
     *  (never the bare, ID-less overload): if a new Start arrives after the one that triggered a
     *  stop but before the service actually dies, `stopSelf(staleId)` is a no-op and the service
     *  survives for the newer command, per the `stopSelf(int)` contract. */
    private var currentStartId: Int = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(SessionPhase.STARTING, null, 0, 0))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        currentStartId = startId
        if (intent?.action == ACTION_STOP) {
            val c = controller
            if (c == null || c.state.value == SessionPhase.IDLE) {
                // Nothing to stop; don't wait on a phase transition that will never come.
                stopSelf(startId)
            } else {
                c.stop()
            }
            return START_NOT_STICKY
        }

        val c = controller ?: buildController().also { controller = it }
        if (c.state.value == SessionPhase.IDLE) {
            c.start()
        }
        return START_NOT_STICKY
    }

    private fun buildController(): UsbSessionController {
        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        val egress = CellularEgress(connectivityManager)
        val newController = UsbSessionController(egress = egress, scope = serviceScope)
        // (C1) Set before this controller's start() is ever called: see the kdoc on
        // UsbSessionController.onIdle for why this must be a direct callback, not something this
        // service subscribes to by collecting controller.state.
        newController.onIdle = { stopSelf(currentStartId) }
        SessionHolder.bind(newController)
        startWakeLockManagement(newController)
        startNotificationUpdates(newController)
        return newController
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        wakeLockJob?.cancel()
        notificationJob?.cancel()
        releaseWakeLock()
        // Deliberately no SessionHolder.unbind(): see its kdoc - the controller (and its
        // lastError, if a start() just failed) stays visible until the next bind() replaces it.
        val c = controller
        // (I1) onCreate/onDestroy always run on main; never block it. If the controller never
        // reached IDLE through the normal stop() path (the process is being torn down mid-
        // session), do the equivalent shutdown synchronously but on a background thread.
        if (c != null && c.state.value != SessionPhase.IDLE) {
            Thread({ c.shutdownBlocking() }, "ProxySessionService-shutdown").start()
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    /** (I4) Confines every wake-lock call to `Dispatchers.Main`, and keeps it held (re-acquiring
     *  every [WAKE_LOCK_RENEW_INTERVAL_MS], well inside [WAKE_LOCK_TIMEOUT_MS]) for as long as the
     *  phase is RUNNING or DEGRADED, instead of only renewing it on a phase change. */
    private fun startWakeLockManagement(c: UsbSessionController) {
        wakeLockJob?.cancel()
        wakeLockJob = serviceScope.launch(Dispatchers.Main) {
            c.state.collectLatest { phase ->
                if (phase == SessionPhase.RUNNING || phase == SessionPhase.DEGRADED) {
                    while (isActive) {
                        acquireOrRenewWakeLock()
                        delay(WAKE_LOCK_RENEW_INTERVAL_MS)
                    }
                } else {
                    releaseWakeLock()
                }
            }
        }
    }

    private fun acquireOrRenewWakeLock() {
        val held = wakeLock
        if (held != null && held.isHeld) {
            held.acquire(WAKE_LOCK_TIMEOUT_MS) // re-acquiring while held just extends the timeout
            return
        }
        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:usb-sharing").apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    /** (M4) Updates the notification on every phase/reason change, and - while RUNNING/DEGRADED -
     *  re-posts it every [NOTIFICATION_REFRESH_INTERVAL_MS] so the byte counters keep moving, and
     *  so a notification permission granted mid-session starts showing one within that interval. */
    private fun startNotificationUpdates(c: UsbSessionController) {
        notificationJob?.cancel()
        notificationJob = serviceScope.launch {
            combine(c.state, c.degradedReason) { phase, reason -> phase to reason }.collectLatest { (phase, reason) ->
                updateNotification(phase, reason)
                if (phase == SessionPhase.RUNNING || phase == SessionPhase.DEGRADED) {
                    while (isActive) {
                        delay(NOTIFICATION_REFRESH_INTERVAL_MS)
                        updateNotification(phase, reason)
                    }
                }
            }
        }
    }

    private fun updateNotification(phase: SessionPhase, reason: String?) {
        val slots = controller?.registry?.slots().orEmpty()
        val bytesUp = slots.sumOf { it.bytesUp }
        val bytesDown = slots.sumOf { it.bytesDown }
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(phase, reason, bytesUp, bytesDown))
    }

    private fun buildNotification(phase: SessionPhase, reason: String?, bytesUp: Long, bytesDown: Long): Notification {
        val contentText = when (phase) {
            SessionPhase.STARTING -> "Starting…"
            SessionPhase.STOPPING -> "Stopping…"
            SessionPhase.DEGRADED -> reason ?: "Degraded"
            else -> "↑ ${formatBytes(bytesUp)}   ↓ ${formatBytes(bytesDown)}"
        }
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stopAction = NotificationCompat.Action(
            0,
            "Stop",
            PendingIntent.getService(
                this,
                0,
                stopIntent(this),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Sharing over USB")
            .setContentText(contentText)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(stopAction)
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "USB sharing", NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        const val ACTION_STOP = "com.wifiproxy.app.action.STOP_USB_SHARING"

        fun startIntent(context: Context): Intent = Intent(context, ProxySessionService::class.java)

        fun stopIntent(context: Context): Intent =
            Intent(context, ProxySessionService::class.java).setAction(ACTION_STOP)
    }
}
