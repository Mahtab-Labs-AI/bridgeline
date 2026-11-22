package com.wifiproxy.app.wifi

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.TimeSource

const val REASON_CREATING = "Creating the Wi-Fi Direct group"
const val REASON_RETRYING = "Wi-Fi Direct group failed; retrying"
const val REASON_WIFI_OFF = "Wi-Fi is off"
const val REASON_LOST = "Wi-Fi Direct group lost; recreating"
const val REASON_LOCATION_OFF = "Turn on Location (needed for Wi-Fi Direct on Android 10–12), then start sharing again."

private const val MAX_BACKOFF_MS = 60_000L

/** Failures that would fail the same way on another band, so a 5 GHz create doesn't retry at AUTO. */
private val NO_BAND_FALLBACK = setOf(
    CreateResult.BUSY,
    CreateResult.P2P_UNSUPPORTED,
    CreateResult.MISSING_PERMISSION,
    CreateResult.LOCATION_OFF,
)

/**
 * Creates the autonomous Wi-Fi Direct group, verifies the firmware really used the requested SSID
 * and passphrase (spec §4 read-back verification), and recreates it with the same credentials
 * whenever it is lost (spec §6), backing off 1 s → 2 s → … → 60 s. Every Android call goes
 * through [api], so all of this runs on the JVM under virtual time.
 *
 * Lifecycle: [start] and [stop] are serialized by one FIFO [Mutex] ([lifecycle]), in call order.
 * Each first cancels the current run and joins it non-cancellably (even one stuck in an
 * uncancellable Android call), so an old run can never act after a later start/stop. [stop] runs
 * entirely in its caller's coroutine and never needs [scope] to be alive, so it always ends with
 * `removeGroup` + [LinkState.Idle], even after [scope] is cancelled.
 */
class P2pGroupLink(
    private val api: P2pApi,
    private val scope: CoroutineScope,
    private val deviceModel: String,
    private val maxConsecutiveFailures: Int = 10,
    /** How often a created group's info is polled until it is complete. */
    private val infoRetryDelayMs: Long = 1_000L,
    /** How many poll intervals to wait in total (30 × 1 s) before treating the create as failed. */
    private val infoRetries: Int = 30,
    /** Verbose diagnostics: one line per step of each group attempt (adopt or create, the band,
     *  `WifiP2pManager` result codes, the group-info wait, the 5 GHz fallback, retries and
     *  losses). Never the Wi-Fi name or password. Called from the run coroutine, outside
     *  [lifecycle]; it must not block, and an exception from it is ignored. */
    private val debug: (String) -> Unit = {},
) : LinkManager {
    private val _state = MutableStateFlow<LinkState>(LinkState.Idle)
    override val state: StateFlow<LinkState> = _state.asStateFlow()

    private val healthChecks = Channel<Unit>(Channel.CONFLATED)

    /** Serializes [start] and [stop]; FIFO-fair, so they take effect in call order. */
    private val lifecycle = Mutex()

    /** The current run, if any. Written only while holding [lifecycle]. */
    @Volatile
    private var job: Job? = null

    override fun start(config: LinkConfig) {
        // UNDISPATCHED: queue on the mutex *now*, in the caller's thread, so a stop() called right
        // after start() returns always runs after this start (Mutex waiters are FIFO).
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            lifecycle.withLock {
                cancelRunAndWait()
                job = scope.launch {
                    // Barrier: wait for any start/stop queued behind this one, so a run that is
                    // already superseded never touches the API. Can't deadlock: whoever holds the
                    // lock and joins this run cancels it first, and a waiting lock() is cancellable.
                    lifecycle.withLock { }
                    run(config)
                }
            }
        }
    }

    override suspend fun stop() {
        lifecycle.withLock {
            cancelRunAndWait()
            job = null
            withContext(NonCancellable) {
                removeGroupSafely()
                _state.value = LinkState.Idle
            }
        }
    }

    /** Cancels the current run and waits, non-cancellably, until it has really finished - even one
     *  stuck in an uncancellable API call - so it can never act after a later start/stop. */
    private suspend fun cancelRunAndWait() {
        job?.let {
            it.cancel()
            withContext(NonCancellable) { it.join() }
        }
    }

    /** [P2pApi.removeGroup] is documented to never throw, but a teardown must always reach Idle:
     *  the group may already be gone, or the implementation may misbehave. */
    private suspend fun removeGroupSafely() {
        try {
            api.removeGroup()
        } catch (e: CancellationException) {
            rethrowIfCancelled(e)
        } catch (e: Exception) {
            // Swallow: reaching Idle matters more than this.
        }
    }

    /** A [CancellationException] is real cancellation only if this coroutine is itself cancelled;
     *  otherwise (e.g. a `TimeoutCancellationException` leaking out of an API implementation's
     *  `withTimeout`) it is an ordinary failure and the caller handles it as one. */
    private suspend fun rethrowIfCancelled(e: CancellationException) {
        if (!currentCoroutineContext().isActive) throw e
    }

    override fun checkHealth() {
        healthChecks.trySend(Unit)
    }

    private fun log(message: String) {
        runCatching { debug(message) }
    }

    private sealed interface Attempt {
        data class Up(val state: LinkState.Up) : Attempt
        data class Fatal(val message: String) : Attempt
        data class Retry(val reason: Int) : Attempt
    }

    /** Runs [config] until stopped or fatally failed. An exception from the run - including a
     *  stray [CancellationException] while the run itself is not cancelled - becomes
     *  [LinkState.Failed] instead of propagating. Only the exception's class name is used: the
     *  message could carry network details (spec's logging constraint). */
    private suspend fun run(config: LinkConfig) {
        try {
            runUntilStoppedOrFatal(config)
        } catch (e: Exception) {
            if (e is CancellationException) rethrowIfCancelled(e)
            log("group run failed: ${e::class.simpleName ?: "unknown"}")
            _state.value = LinkState.Failed("Wi-Fi Direct error: ${e::class.simpleName ?: "unknown"}")
        }
    }

    private suspend fun runUntilStoppedOrFatal(config: LinkConfig) {
        var failures = 0
        // Set when the previous iteration ended because the group was lost, so the *next*
        // attempt's Down state can say so (REASON_LOST), rather than being immediately overwritten
        // with REASON_CREATING. A failure still takes priority: once we're retrying, the reason is
        // REASON_RETRYING regardless of how we got here.
        var lostPreviously = false
        while (true) {
            if (!api.p2pEnabled.value) {
                log("Wi-Fi P2P is off; waiting for it")
                _state.value = LinkState.Down(REASON_WIFI_OFF)
                api.p2pEnabled.first { it }
            }
            _state.value = LinkState.Down(
                when {
                    failures > 0 -> REASON_RETRYING
                    lostPreviously -> REASON_LOST
                    else -> REASON_CREATING
                },
            )
            lostPreviously = false
            when (val attempt = createAndVerify(config)) {
                is Attempt.Up -> {
                    failures = 0
                    _state.value = attempt.state
                    if (awaitLoss()) {
                        log("group lost")
                        lostPreviously = true
                    } else {
                        log("Wi-Fi P2P turned off while the group was up")
                    }
                }
                is Attempt.Fatal -> {
                    log("attempt failed for good; needs the user")
                    _state.value = LinkState.Failed(attempt.message)
                    return
                }
                is Attempt.Retry -> {
                    if (!api.p2pEnabled.value) {
                        log("attempt failed (reason ${attempt.reason}) while Wi-Fi P2P went off; not counted")
                        // Wi-Fi dropped during this attempt (create failed, or disappeared, because
                        // of it); that's not a consecutive failure of our own doing. Reset the count
                        // and let the top of the loop wait for Wi-Fi to come back.
                        failures = 0
                    } else {
                        failures++
                        if (failures >= maxConsecutiveFailures) {
                            log("attempt failed (reason ${attempt.reason}); giving up after $failures in a row")
                            _state.value =
                                LinkState.Failed("Couldn't create the Wi-Fi Direct group (error ${attempt.reason}).")
                            return
                        }
                        val backoffMs = minOf(1000L shl (failures - 1), MAX_BACKOFF_MS)
                        log("attempt failed (reason ${attempt.reason}); failure $failures, retrying in $backoffMs ms")
                        _state.value = LinkState.Down(REASON_RETRYING)
                        delay(backoffMs)
                    }
                }
            }
        }
    }

    private suspend fun createAndVerify(config: LinkConfig): Attempt {
        val creds = config.credentials
        // A complete group with exactly our credentials already exists (e.g. left by a process that
        // died, or one that came up late after a previous attempt gave up on it): adopt it instead
        // of tearing it down, so its clients stay connected.
        api.groupInfo()?.let { existing ->
            val owner = existing.ownerAddress
            if (owner != null && existing.networkName == creds.ssid && existing.passphrase == creds.password) {
                log("adopting the existing group (owner ${owner.hostAddress})")
                return Attempt.Up(LinkState.Up(owner, creds))
            }
            log("an existing group doesn't match (or isn't complete); removing it")
        }
        api.removeGroup()
        var band = if (config.prefer5Ghz) GroupBand.GHZ_5 else GroupBand.AUTO
        var result = createLogged(creds.ssid, creds.password, band)
        if (band == GroupBand.GHZ_5 && result is CreateResult.Failure && result.reason !in NO_BAND_FALLBACK) {
            log("5 GHz create failed; falling back to AUTO")
            band = GroupBand.AUTO
            result = createLogged(creds.ssid, creds.password, band)
        }
        if (result is CreateResult.Failure) return failureAttempt(result.reason)
        var info = awaitGroupInfoLogged()
        if (info == null && band == GroupBand.GHZ_5) {
            // Some firmware accepts a 5 GHz group and then never brings it up; try AUTO once
            // before counting this attempt as a failure.
            log("5 GHz group never came up; recreating at AUTO")
            api.removeGroup()
            band = GroupBand.AUTO
            val retry = createLogged(creds.ssid, creds.password, band)
            if (retry is CreateResult.Failure) return failureAttempt(retry.reason)
            info = awaitGroupInfoLogged()
        }
        if (info == null) return Attempt.Retry(CreateResult.ERROR)
        val ownerAddress = info.ownerAddress ?: return Attempt.Retry(CreateResult.ERROR)
        if (info.networkName != creds.ssid || info.passphrase != creds.password) {
            log("the group came up with a different name or password than requested")
            api.removeGroup()
            return Attempt.Fatal(
                "This phone ($deviceModel) ignored the requested Wi-Fi name or password. Wi-Fi Direct sharing can't be used on it.",
            )
        }
        log("group up at $band (owner ${ownerAddress.hostAddress})")
        return Attempt.Up(LinkState.Up(ownerAddress, creds))
    }

    /** [P2pApi.createGroup], logging the band and the result code (never the name or password). */
    private suspend fun createLogged(ssid: String, passphrase: String, band: GroupBand): CreateResult {
        val result = api.createGroup(GroupRequest(ssid, passphrase, band))
        log(
            when (result) {
                CreateResult.Success -> "createGroup($band): success"
                is CreateResult.Failure -> "createGroup($band): failure, reason ${result.reason}"
            },
        )
        return result
    }

    /** [awaitGroupInfo], logging how long the group info took (or that it never came). */
    private suspend fun awaitGroupInfoLogged(): GroupInfo? {
        val mark = TimeSource.Monotonic.markNow()
        val info = awaitGroupInfo()
        val waitedMs = mark.elapsedNow().inWholeMilliseconds
        log(if (info != null) "group info after $waitedMs ms" else "no complete group info after $waitedMs ms")
        return info
    }

    private fun failureAttempt(reason: Int): Attempt = when (reason) {
        CreateResult.P2P_UNSUPPORTED -> Attempt.Fatal("This phone doesn't support Wi-Fi Direct.")
        CreateResult.MISSING_PERMISSION ->
            Attempt.Fatal("Allow the Nearby devices permission (Location on Android 10–12), then start sharing again.")
        CreateResult.LOCATION_OFF -> Attempt.Fatal(REASON_LOCATION_OFF)
        else -> Attempt.Retry(reason)
    }

    /**
     * Group info can lag createGroup's success callback by many seconds on a slow device (the
     * emulator saw ~23 s). Polls every [infoRetryDelayMs] for up to [infoRetries] × that in total,
     * and re-queries immediately whenever [P2pApi.connectionChanged] fires meanwhile. The listener
     * is subscribed [CoroutineStart.UNDISPATCHED] before the first query, so an event between a
     * query and the wait is never missed (it is buffered in the conflated channel).
     */
    private suspend fun awaitGroupInfo(): GroupInfo? = coroutineScope {
        val changed = Channel<Unit>(Channel.CONFLATED)
        val listener = launch(start = CoroutineStart.UNDISPATCHED) {
            api.connectionChanged.collect { changed.trySend(Unit) }
        }
        try {
            withTimeoutOrNull(infoRetries * infoRetryDelayMs) {
                var info = completeGroupInfo()
                while (info == null) {
                    withTimeoutOrNull(infoRetryDelayMs) { changed.receive() }
                    info = completeGroupInfo()
                }
                info
            }
        } finally {
            listener.cancel()
        }
    }

    private suspend fun completeGroupInfo(): GroupInfo? = api.groupInfo()?.takeIf { it.ownerAddress != null }

    /** Suspends while the group is up. True when the group was lost; false when Wi-Fi went off
     *  (the outer loop then waits for it to come back). */
    private suspend fun awaitLoss(): Boolean {
        var lost = false
        lossEvents().first { wifiOff ->
            when {
                wifiOff -> true
                api.groupInfo() == null -> { lost = true; true }
                else -> false
            }
        }
        return lost
    }

    /**
     * [P2pApi.connectionChanged] has no replay, so a loss it signals before we're listening would
     * otherwise go unnoticed until the 30 s watchdog's [checkHealth]. A plain
     * `merge(...).onStart { emit(false) }` doesn't close that gap: the `onStart` emission runs
     * *before* the merged flows are subscribed, so a loss between the emission and the actual
     * subscription is still missed. Here, each source is subscribed with
     * [CoroutineStart.UNDISPATCHED] - which runs the collector synchronously up to its first
     * suspension point - *before* the synthetic initial `false` is sent, so by the time that
     * initial [P2pApi.groupInfo] re-check runs, all three sources are already live.
     */
    private fun lossEvents(): Flow<Boolean> = channelFlow {
        launch(start = CoroutineStart.UNDISPATCHED) { api.connectionChanged.collect { send(false) } }
        launch(start = CoroutineStart.UNDISPATCHED) { healthChecks.receiveAsFlow().collect { send(false) } }
        launch(start = CoroutineStart.UNDISPATCHED) { api.p2pEnabled.filter { !it }.collect { send(true) } }
        send(false)
    }
}
