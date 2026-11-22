package com.wifiproxy.app.logs

import android.content.Context
import com.wifiproxy.app.BuildConfig
import com.wifiproxy.app.service.SessionHolder
import com.wifiproxy.app.settings.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch

/** Prefix of every verbose line in the Diagnostics log. */
const val VERBOSE_PREFIX = "[v] "

/** The plain (non-verbose) line logged when verbose logging switches itself off. */
const val VERBOSE_AUTO_OFF_MESSAGE = "Verbose logging turned off (30 minutes elapsed)"

/** "Verbose logging (troubleshooting)": pure timing rules. 0 always means off. */
object VerboseLogging {
    const val DURATION_MS: Long = 30 * 60_000L

    /** On while [nowMs] is before [untilMs]. Also off if [untilMs] is more than [DURATION_MS]
     *  ahead, which only a wall clock moved backwards can cause: it never stays on for longer. */
    fun isActive(nowMs: Long, untilMs: Long): Boolean =
        untilMs > 0 && nowMs < untilMs && untilMs - nowMs <= DURATION_MS

    /** The `untilMs` to store when the user switches it on at [nowMs]. */
    fun enable(nowMs: Long): Long = nowMs + DURATION_MS
}

/** Where [VerboseSwitch] persists its state; [AppPreferences] in the app. */
interface VerbosePrefs {
    /** 0 = off. */
    var verboseUntilMs: Long

    /** The app version that switched it on: a different one (an update) never restores it. */
    var verboseVersionCode: Int
}

/**
 * The verbose-logging switch: off by default, on only when the user turns it on, and off again
 * [VerboseLogging.DURATION_MS] later. [untilMs] is the source of truth (0 = off).
 *
 * The auto-off is a [delay] on [scope], armed by [enable] and re-armed at construction (process
 * start) while a stored [VerbosePrefs.verboseUntilMs] from this same app version is still in the
 * future. [isActive] - checked per log event - is the safety net for a late timer (the device
 * slept): it notices the expiry itself. Either way [onEnded] runs exactly once per switch-off, with
 * `autoOff = true` for the timeout and false for [disable]. Thread-safe: every transition is a CAS
 * on [untilMs], so a timer and an event noticing the expiry at the same moment can't both act.
 */
class VerboseSwitch(
    private val prefs: VerbosePrefs,
    private val versionCode: Int,
    private val clock: () -> Long,
    private val scope: CoroutineScope,
    /** Called after every switch-off; must not block (it can run on a connection handler). A
     *  constructor parameter, not a settable property, so it is in place before `init` re-arms a
     *  stored deadline: one about to expire can never fire into a no-op. */
    private val onEnded: (autoOff: Boolean) -> Unit = {},
) {
    /** The pending auto-off; guarded by `this`. Declared before `init`, which may arm it. */
    private var timer: Job? = null

    private val _untilMs = MutableStateFlow(0L)
    val untilMs: StateFlow<Long> = _untilMs.asStateFlow()

    init {
        val stored = prefs.verboseUntilMs
        if (stored != 0L) {
            if (prefs.verboseVersionCode == versionCode && VerboseLogging.isActive(clock(), stored)) {
                _untilMs.value = stored
                arm(stored)
            } else {
                // Expired while the process was gone, or an update since: it stays off.
                prefs.verboseUntilMs = 0L
            }
        }
    }

    /** Switches it on (or extends it) for [VerboseLogging.DURATION_MS] from now. */
    fun enable() {
        val until = VerboseLogging.enable(clock())
        prefs.verboseVersionCode = versionCode
        prefs.verboseUntilMs = until
        _untilMs.value = until
        arm(until)
    }

    /** Switches it off early. */
    fun disable() {
        val previous = _untilMs.getAndUpdate { 0L }
        if (previous == 0L) return
        synchronized(this) {
            timer?.cancel()
            timer = null
        }
        prefs.verboseUntilMs = 0L
        safeOnEnded(autoOff = false)
    }

    /** Whether verbose lines should be logged right now. Notices an expiry the timer hasn't
     *  handled yet and switches off then. Cheap: a volatile read and a clock read while off. */
    fun isActive(): Boolean {
        val until = _untilMs.value
        if (until == 0L) return false
        if (VerboseLogging.isActive(clock(), until)) return true
        expire(until)
        return false
    }

    private fun arm(until: Long) {
        synchronized(this) {
            timer?.cancel()
            timer = scope.launch {
                // Re-checks the wall clock after every wake-up: a delay doesn't count deep sleep.
                while (true) {
                    val left = until - clock()
                    if (left <= 0) break
                    delay(left)
                }
                expire(until)
            }
        }
    }

    private fun expire(until: Long) {
        if (!_untilMs.compareAndSet(until, 0L)) return
        prefs.verboseUntilMs = 0L
        safeOnEnded(autoOff = true)
    }

    private fun safeOnEnded(autoOff: Boolean) {
        runCatching { onEnded(autoOff) }
    }
}

/** The process-wide [VerboseSwitch], shared by the service (which logs) and the UI (which shows
 *  and toggles it). Its timer runs on a scope of its own for the process's lifetime. */
object VerboseLog {
    @Volatile private var instance: VerboseSwitch? = null

    fun get(context: Context): VerboseSwitch = instance ?: synchronized(this) {
        instance ?: VerboseSwitch(
            prefs = AppPreferences(context.applicationContext),
            versionCode = BuildConfig.VERSION_CODE,
            clock = System::currentTimeMillis,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            onEnded = { autoOff -> SessionHolder.controller.value?.onVerboseEnded(autoOff) },
        ).also { instance = it }
    }
}
