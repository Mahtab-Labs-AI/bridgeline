package com.wifiproxy.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.wifiproxy.app.MainActivity
import com.wifiproxy.app.egress.CellularEgress
import com.wifiproxy.app.session.SessionPhase
import com.wifiproxy.app.settings.AppPreferences
import com.wifiproxy.app.settings.HostConfigSource
import com.wifiproxy.app.ui.formatBytes
import com.wifiproxy.app.wifi.AndroidP2pApi
import com.wifiproxy.app.wifi.LinkState
import com.wifiproxy.app.wifi.P2pGroupLink
import com.wifiproxy.app.wifi.SlotStore
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

/** The "Sharing stopped. Tap to resume." notice ([postStoppedNotification]); a separate ID from
 *  the foreground one so [removeNotification] (which every path to IDLE and onDestroy runs) never
 *  cancels it. */
private const val STOPPED_NOTIFICATION_ID = 2
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
 * Hosts [SessionController] as a `connectedDevice` foreground service (design spec §3, §7,
 * §8a). [onCreate] calls `startForeground()` immediately - before building the (Android-API-
 * touching) egress and controller - so the service is never caught running foreground work
 * without a notification.
 *
 * (C2) [serviceScope] runs on `Dispatchers.Default.limitedParallelism(1)`, so every coroutine
 * [SessionController] launches on it (start, stop, the watchdog, network-change handling, link-
 * change filter refreshes) is strictly serialized against every other one.
 */
class ProxySessionService : Service() {

    private val exceptionHandler = CoroutineExceptionHandler { _, e ->
        // (I2) A crash in any coroutine launched on serviceScope must never bring down the
        // process; SupervisorJob already stops it propagating to sibling children, this just
        // makes sure it's not silently swallowed either.
        Log.e(TAG, "Uncaught exception in ProxySessionService's scope", e)
    }
    // (R4) Held in a field rather than written inline at each use: Dispatchers.Default.
    // limitedParallelism(n) returns a *new* limited view every time it's called, each with its own
    // independent queue, so two separate calls don't serialize against each other at all. Every
    // coroutine that must run strictly serialized against every other one - serviceScope's and
    // onDestroy's best-effort shutdown alike - has to be launched on this exact same instance.
    private val serial = Dispatchers.Default.limitedParallelism(1)

    private val serviceScope = CoroutineScope(SupervisorJob() + serial + exceptionHandler)

    private var controller: SessionController? = null

    /** Created on Main in [buildController]; closed on `serial` after [onDestroy]'s shutdown (see
     *  there), never before, since that shutdown's `link.stop()` still needs it to remove the group. */
    private var p2pApi: AndroidP2pApi? = null

    // Lazy: applicationContext only exists once the service is attached (before onCreate).
    private val prefs by lazy { AppPreferences(applicationContext) }
    private val hostConfig by lazy { HostConfigSource.forContext(applicationContext) }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLockJob: Job? = null
    private var notificationJob: Job? = null

    /** Every call into [SessionController.onIdle] is re-posted through this (R3): that callback
     *  can run on the serial worker thread (during a normal `doStop`/rolled-back `doStart`, or
     *  [onDestroy]'s scheduled [SessionController.shutdown]) - never reliably on Main - but
     *  everything it touches here ([currentStartId], [controller], [notificationJob], the
     *  notification APIs) is Main-confined. */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** The most recent `startId` seen by [onStartCommand], always what [stopSelf] is called with
     *  (never the bare, ID-less overload): if a new Start arrives after the one that triggered a
     *  stop but before the service actually dies, `stopSelf(staleId)` is a no-op and the service
     *  survives for the newer command, per the `stopSelf(int)` contract. Only ever read or written
     *  from Main (R3): [onStartCommand] always runs there, and so does every read of it now that
     *  [SessionController.onIdle] is re-posted onto [mainHandler] before touching it. */
    private var currentStartId: Int = 0

    /** Set on Main (M-b) when a Start intent arrives while the session is STOPPING: calling
     *  [SessionController.start] in that phase is a documented no-op, so the request would
     *  otherwise simply be dropped. The onIdle hand-off below checks this once the session actually
     *  reaches IDLE and, if set, starts a fresh session instead of letting the service stop. */
    private var pendingStart: Boolean = false

    /** Set the instant [onDestroy] begins, so [updateNotification] can refuse to resurrect the
     *  notification for a tick that was already in flight when teardown started. */
    @Volatile private var serviceStopping = false

    /** Set on Main by [onForegroundRefused]. The refusal typically hits a sticky restart after
     *  process death, which never started a session and so never runs `link.stop()`; a group the
     *  dead process left behind would otherwise linger (and keep advertising) until Wi-Fi goes
     *  off. [onDestroy] then does a best-effort `removeGroup()` on `serial` before closing the API. */
    private var removeStaleGroupOnClose = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // A refusal here (a sticky restart in the background, API 31+) is handled by
        // onStartCommand, which always follows: its Start path calls enterForeground again and,
        // refused again, stops the service; its Stop and declined-restart paths stop it anyway.
        enterForeground(buildNotification(SessionPhase.STARTING, null, 0, 0))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        currentStartId = startId
        if (intent?.action == ACTION_STOP) {
            // An explicit Stop always wins over an earlier pending restart (M-b).
            pendingStart = false
            prefs.sessionDesired = false
            val c = controller
            if (c == null || c.state.value == SessionPhase.IDLE) {
                // Nothing to stop; don't wait on a phase transition that will never come.
                stopSelf(startId)
            } else {
                c.stop()
            }
            return START_NOT_STICKY
        }

        // (Spec §3) A null intent is a START_STICKY restart after process death: resume sharing
        // only if the user still wanted it when the process died.
        if (intent == null && !prefs.sessionDesired) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        prefs.sessionDesired = true

        val c = controller ?: buildController().also { controller = it }
        // Called unconditionally, not only when state == IDLE (R3): Android requires a
        // startForeground() call for *every* startForegroundService()-triggered onStartCommand,
        // within its own deadline, regardless of whether the service happens to already be
        // running. The call is idempotent (re-posts the same ongoing notification), so this is
        // free on the already-running paths and re-enters foreground state on every one that isn't
        // - a previous session reaching IDLE called stopForeground(STOP_FOREGROUND_REMOVE) (see
        // removeNotification), so a later start on this same (still-alive) service instance needs
        // its own startForeground() again, exactly like a fresh instance's onCreate.
        //
        // Built from the current phase, so a Start tap on a RUNNING/DEGRADED session doesn't flash
        // "Starting…" over the live counters until the next refresh tick.
        val phase = c.state.value
        val notification = if (phase == SessionPhase.IDLE) {
            buildNotification(SessionPhase.STARTING, null, 0, 0)
        } else {
            currentNotification(phase, c.degradedReason.value)
        }
        if (!enterForeground(notification)) {
            onForegroundRefused()
            return START_NOT_STICKY
        }
        when (phase) {
            SessionPhase.IDLE -> {
                // (R3c) Restarted next to c.start(), not left to whatever buildController wired up
                // on first build: a session that is starting again after reaching IDLE needs its
                // own fresh notificationJob too, or its notification never updates again. After
                // c.start(), not before, so the updater's first value is STARTING rather than the
                // IDLE it would otherwise treat as "remove the notification" (stopForeground).
                c.start()
                startNotificationUpdates(c)
            }
            SessionPhase.STOPPING -> {
                // c.start() is a documented no-op while STOPPING; recorded instead so the onIdle
                // hand-off starts a fresh session once this one actually finishes tearing down
                // (M-b).
                pendingStart = true
            }
            else -> Unit // Already STARTING/RUNNING/DEGRADED; the startForeground above is enough.
        }
        return START_STICKY
    }

    /** `startForeground` for [NOTIFICATION_ID]. False when the system refuses it: on API 31+ a
     *  service restarted in the background (START_STICKY) can't enter the foreground unless it is
     *  exempt (the battery-optimisation setup check makes this rare). That refusal,
     *  `ForegroundServiceStartNotAllowedException`, is an [IllegalStateException]; catching the
     *  latter also works on API 29-30, where the former doesn't exist. */
    private fun enterForeground(notification: Notification): Boolean = try {
        startForeground(NOTIFICATION_ID, notification)
        getSystemService(NotificationManager::class.java).cancel(STOPPED_NOTIFICATION_ID)
        true
    } catch (e: IllegalStateException) {
        Log.w(TAG, "Foreground start refused: ${e::class.simpleName}")
        false
    }

    /** Runs on Main. Sharing can't run without the foreground, so stop and tell the user how to
     *  resume. Any session still running is shut down by [onDestroy], like on any other stop. */
    private fun onForegroundRefused() {
        pendingStart = false
        removeStaleGroupOnClose = true
        prefs.sessionDesired = false
        notificationJob?.cancel()
        removeNotification()
        postStoppedNotification()
        stopSelf(currentStartId)
    }

    private fun postStoppedNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Sharing stopped")
            .setContentText("Sharing stopped. Tap to resume.")
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(STOPPED_NOTIFICATION_ID, notification)
    }

    private fun buildController(): SessionController {
        // (R6) applicationContext, not `this`: CellularEgress/its ConnectivityManager outlive any
        // one onStartCommand and are reachable from SessionHolder long after this Service instance
        // would otherwise be eligible for GC, so building it from the Service context (implicitly
        // keeping the Service alive) risks retaining a destroyed service.
        val connectivityManager = applicationContext.getSystemService(ConnectivityManager::class.java)
        val egress = CellularEgress(connectivityManager)
        // On Main (onStartCommand): AndroidP2pApi registers its broadcast receiver and opens its
        // WifiP2pManager channel on the main looper. applicationContext too (R6).
        val api = AndroidP2pApi(applicationContext).also { p2pApi = it }
        val newController = SessionController(
            egress = egress,
            link = P2pGroupLink(api, serviceScope, Build.MODEL),
            slots = SlotStore(HostConfigSource.secureStore(applicationContext)),
            // Read once per start, on `serial` (it decrypts the credentials), never on Main.
            config = hostConfig::sessionConfig,
            scope = serviceScope,
        )
        // (C1) Set before this controller's start() is ever called: see the kdoc on
        // SessionController.onIdle for why this must be a direct callback, not something this
        // service subscribes to by collecting controller.state.
        //
        // (R3) onIdle can run on the serial worker (a normal doStop/rolled-back doStart, or
        // onDestroy's scheduled shutdown()) - never reliably Main - so everything it
        // touches (currentStartId, controller, notificationJob, the notification/foreground APIs)
        // is re-posted onto mainHandler rather than touched here directly. The guard re-checks
        // `controller?.state?.value == SessionPhase.IDLE` once that post actually runs, not just
        // at the moment onIdle fired, so a start that re-entered (moved the phase away from IDLE)
        // in the meantime can't have its fresh startForeground/notification undone by a now-stale
        // teardown (R3b) - and serviceStopping guards the symmetric case where onDestroy has
        // already begun its own teardown by the time this post runs.
        newController.onIdle = {
            mainHandler.post {
                if (!serviceStopping && controller?.state?.value == SessionPhase.IDLE) {
                    notificationJob?.cancel()
                    if (pendingStart) {
                        // (M-b) A Start arrived while this session was STOPPING and was recorded
                        // instead of silently dropped (c.start() is a no-op in that phase); now
                        // that the session has actually reached IDLE, honour it as a fresh start
                        // rather than letting the service stop.
                        pendingStart = false
                        if (enterForeground(buildNotification(SessionPhase.STARTING, null, 0, 0))) {
                            newController.start()
                            startNotificationUpdates(newController)
                        } else {
                            onForegroundRefused()
                        }
                    } else {
                        // (Spec §3) Any path to IDLE while the service is alive - a Stop, a failed
                        // start - means sharing is no longer wanted: don't resume it after a
                        // process death.
                        prefs.sessionDesired = false
                        removeNotification()
                        stopSelf(currentStartId)
                    }
                }
            }
        }
        SessionHolder.bind(newController)
        startWakeLockManagement(newController)
        return newController
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceStopping = true
        wakeLockJob?.cancel()
        notificationJob?.cancel()
        releaseLocks()
        // Belt and braces alongside onIdle's removal: guarantees no notification survives
        // onDestroy even on a path that never ran onIdle (e.g. the process dying mid-session, or
        // the service being torn down for some other reason entirely).
        removeNotification()
        // Deliberately no SessionHolder.unbind(): see its kdoc - the controller (and its
        // lastError, if a start() just failed) stays visible until the next bind() replaces it.
        val c = controller
        // (I1) onCreate/onDestroy always run on main; never block it. If the controller never
        // reached IDLE through the normal stop() path (the process is being torn down mid-
        // session), do the equivalent shutdown off Main.
        //
        // (R4) Launched on `serial` - the exact field serviceScope's own coroutines run on, not a
        // fresh Dispatchers.Default.limitedParallelism(1) (a distinct view wouldn't serialize
        // against whatever is still in flight there) - with its own Job rather than a child of
        // serviceScope's SupervisorJob, so the serviceScope.cancel() below can't cancel this
        // shutdown before it even starts. Launched *before* that cancel() so it queues behind any
        // coroutine already running on `serial` instead of racing it; a job already queued behind
        // it on `serial` at cancellation time is simply never run, since serviceScope's own Job
        // (not `serial` itself) is what gets cancelled. Still never blocks Main: `serial` is
        // backed by Dispatchers.Default, so shutdown() runs on one of its worker threads.
        //
        // The P2P API is closed in that same coroutine, after the shutdown: the shutdown's
        // link.stop() (or a doStop it waits behind) still needs its channel to remove the group.
        val api = p2pApi
        p2pApi = null
        if (c != null && c.state.value != SessionPhase.IDLE) {
            CoroutineScope(serial + Job()).launch {
                try {
                    c.shutdown()
                } finally {
                    api?.close()
                }
            }
        } else if (api != null) {
            // Fire-and-forget, like the shutdown above: never waited on from Main.
            val removeStaleGroup = removeStaleGroupOnClose
            CoroutineScope(serial + Job()).launch {
                try {
                    // Best effort: removeGroup() is documented never to throw and is time-bounded,
                    // but nothing here may stop the close below.
                    if (removeStaleGroup) runCatching { api.removeGroup() }
                } finally {
                    api.close()
                }
            }
        }
        // (R6) After scheduling the shutdown above, not before: a destroyed service must not stay
        // reachable through a controller/egress this onIdle closure keeps alive, and nothing past
        // this point needs onIdle to still fire. shutdown() calls teardown() directly, so onIdle
        // *would* still fire from it, which is exactly why it must already be nulled out by the
        // time that runs.
        c?.onIdle = null
        serviceScope.cancel()
        super.onDestroy()
    }

    /** (I4) Confines every wake-lock call to `Dispatchers.Main`, and keeps it held (re-acquiring
     *  every [WAKE_LOCK_RENEW_INTERVAL_MS], well inside [WAKE_LOCK_TIMEOUT_MS]) for as long as the
     *  phase is RUNNING or DEGRADED, instead of only renewing it on a phase change. A low-latency
     *  [WifiManager.WifiLock] is held alongside it, so the Wi-Fi radio doesn't doze under the
     *  group while sharing. */
    private fun startWakeLockManagement(c: SessionController) {
        wakeLockJob?.cancel()
        wakeLockJob = serviceScope.launch(Dispatchers.Main) {
            c.state.collectLatest { phase ->
                if (phase == SessionPhase.RUNNING || phase == SessionPhase.DEGRADED) {
                    acquireWifiLock()
                    while (isActive) {
                        acquireOrRenewWakeLock()
                        delay(WAKE_LOCK_RENEW_INTERVAL_MS)
                    }
                } else {
                    releaseLocks()
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
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:sharing").apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        val wifiManager = applicationContext.getSystemService(WifiManager::class.java) ?: return
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "$packageName:sharing").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    /** Releases the wake lock and the Wi-Fi lock together. */
    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

    /** (M4) Updates the notification on every phase/reason change, and - while RUNNING/DEGRADED -
     *  re-posts it every [NOTIFICATION_REFRESH_INTERVAL_MS] so the byte counters keep moving, and
     *  so a notification permission granted mid-session starts showing one within that interval.
     *
     *  Reaching IDLE here removes the notification instead of re-posting it; this is belt-and-
     *  braces alongside `onIdle`'s synchronous removal above (collecting `c.state` asynchronously
     *  means this could in principle lag behind an `onIdle` that already fired, which is exactly
     *  why `onIdle` doesn't rely on this path to begin with).
     *
     *  Runs on Main, like the wake-lock job: every other notification/foreground call (onIdle's
     *  hand-off, onStartCommand, onDestroy) is Main-confined, so cancelling this job there always
     *  lands while it is suspended. On the serial worker, a stale job could be mid-way through
     *  removeNotification() - i.e. stopForeground() - right as Main re-enters the foreground for a
     *  restarted session, leaving that session running without foreground status. */
    private fun startNotificationUpdates(c: SessionController) {
        notificationJob?.cancel()
        notificationJob = serviceScope.launch(Dispatchers.Main) {
            combine(c.state, c.degradedReason) { phase, reason -> phase to reason }.collectLatest { (phase, reason) ->
                if (phase == SessionPhase.IDLE) {
                    removeNotification()
                    return@collectLatest
                }
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

    /** No-op once the controller is IDLE or [onDestroy] has started (`serviceStopping`), so a
     *  periodic tick already queued (e.g. mid-`delay`) when the session reached IDLE can't
     *  resurrect a notification that was just removed. */
    private fun updateNotification(phase: SessionPhase, reason: String?) {
        if (serviceStopping || controller?.state?.value == SessionPhase.IDLE) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, currentNotification(phase, reason))
    }

    /** [buildNotification] with the live session's byte counters, and the SSID while the Wi-Fi
     *  Direct group is Up. */
    private fun currentNotification(phase: SessionPhase, reason: String?): Notification {
        val c = controller
        val slots = c?.registry?.slots().orEmpty()
        val ssid = (c?.linkState?.value as? LinkState.Up)?.credentials?.ssid
        return buildNotification(phase, reason, slots.sumOf { it.bytesUp }, slots.sumOf { it.bytesDown }, ssid)
    }

    /** Removes the foreground notification on every path to IDLE (a clean stop, a failed start,
     *  or [onDestroy]): detaches it from the foreground-service state first - a stale
     *  `NotificationManager.notify()` call racing this would otherwise just resurrect it under the
     *  system's foreground-service bookkeeping - then cancels it outright as belt and braces. */
    private fun removeNotification() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    private fun buildNotification(
        phase: SessionPhase,
        reason: String?,
        bytesUp: Long,
        bytesDown: Long,
        ssid: String? = null,
    ): Notification {
        val contentText = when (phase) {
            SessionPhase.STARTING -> "Starting…"
            SessionPhase.STOPPING -> "Stopping…"
            SessionPhase.DEGRADED -> reason ?: "Degraded"
            else -> {
                val counters = "↑ ${formatBytes(bytesUp)}   ↓ ${formatBytes(bytesDown)}"
                if (ssid != null) "$ssid · $counters" else counters
            }
        }
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
            .setContentTitle("Sharing mobile data")
            .setContentText(contentText)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(stopAction)
            .build()
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        // The ID stays "usb_sharing" so existing installs keep one channel; re-creating it with a
        // new name just renames it.
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Sharing", NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        const val ACTION_STOP = "com.wifiproxy.app.action.STOP_USB_SHARING"

        fun startIntent(context: Context): Intent = Intent(context, ProxySessionService::class.java)

        fun stopIntent(context: Context): Intent =
            Intent(context, ProxySessionService::class.java).setAction(ACTION_STOP)
    }
}
