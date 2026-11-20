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
import androidx.core.app.NotificationCompat
import com.wifiproxy.app.MainActivity
import com.wifiproxy.app.egress.CellularEgress
import com.wifiproxy.app.session.SessionPhase
import com.wifiproxy.app.ui.formatBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

private const val CHANNEL_ID = "usb_sharing"
private const val NOTIFICATION_ID = 1

/** Safety cap so a leaked wake lock can't drain the battery forever; [updateWakeLock] renews it
 *  on every state tick while actually Running/Degraded, well inside this window. */
private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L

/**
 * Hosts [UsbSessionController] as a `connectedDevice` foreground service (design spec §3, §7,
 * §8a). [onCreate] calls `startForeground()` immediately - before building the (Android-API-
 * touching) egress and controller - so the service is never caught running foreground work
 * without a notification.
 */
class ProxySessionService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var controller: UsbSessionController? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var observeJob: Job? = null
    private var stopRequested = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(SessionPhase.STARTING, null, 0, 0))

        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        val egress = CellularEgress(connectivityManager)
        val newController = UsbSessionController(egress = egress, scope = serviceScope)
        controller = newController
        SessionHolder.bind(newController)
        observeJob = serviceScope.launch { observe(newController) }
        newController.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRequested = true
            controller?.stop()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        observeJob?.cancel()
        releaseWakeLock()
        SessionHolder.unbind()
        serviceScope.cancel()
        super.onDestroy()
    }

    private suspend fun observe(c: UsbSessionController) {
        combine(c.state, c.degradedReason) { phase, reason -> phase to reason }.collectLatest { (phase, reason) ->
            updateWakeLock(phase)
            updateNotification(phase, reason)
            if (phase == SessionPhase.IDLE && stopRequested) {
                stopSelf()
            }
        }
    }

    private fun updateWakeLock(phase: SessionPhase) {
        val shouldHold = phase == SessionPhase.RUNNING || phase == SessionPhase.DEGRADED
        if (!shouldHold) {
            releaseWakeLock()
            return
        }
        val held = wakeLock
        if (held != null && held.isHeld) return
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
