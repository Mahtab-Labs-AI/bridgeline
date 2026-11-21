package com.wifiproxy.app.wifi

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
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

const val REASON_CREATING = "Creating the Wi-Fi Direct group"
const val REASON_RETRYING = "Wi-Fi Direct group failed; retrying"
const val REASON_WIFI_OFF = "Wi-Fi is off"
const val REASON_LOST = "Wi-Fi Direct group lost; recreating"

private const val MAX_BACKOFF_MS = 60_000L

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
    private val infoRetryDelayMs: Long = 500L,
    private val infoRetries: Int = 6,
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
                    if (awaitLoss()) lostPreviously = true
                }
                is Attempt.Fatal -> {
                    _state.value = LinkState.Failed(attempt.message)
                    return
                }
                is Attempt.Retry -> {
                    if (!api.p2pEnabled.value) {
                        // Wi-Fi dropped during this attempt (create failed, or disappeared, because
                        // of it); that's not a consecutive failure of our own doing. Reset the count
                        // and let the top of the loop wait for Wi-Fi to come back.
                        failures = 0
                    } else {
                        failures++
                        if (failures >= maxConsecutiveFailures) {
                            _state.value =
                                LinkState.Failed("Couldn't create the Wi-Fi Direct group (error ${attempt.reason}).")
                            return
                        }
                        _state.value = LinkState.Down(REASON_RETRYING)
                        delay(minOf(1000L shl (failures - 1), MAX_BACKOFF_MS))
                    }
                }
            }
        }
    }

    private suspend fun createAndVerify(config: LinkConfig): Attempt {
        val creds = config.credentials
        api.removeGroup()
        var result = api.createGroup(GroupRequest(creds.ssid, creds.password, if (config.prefer5Ghz) GroupBand.GHZ_5 else GroupBand.AUTO))
        if (config.prefer5Ghz && result is CreateResult.Failure && result.reason != CreateResult.BUSY &&
            result.reason != CreateResult.P2P_UNSUPPORTED && result.reason != CreateResult.MISSING_PERMISSION
        ) {
            result = api.createGroup(GroupRequest(creds.ssid, creds.password, GroupBand.AUTO))
        }
        if (result is CreateResult.Failure) {
            return when (result.reason) {
                CreateResult.P2P_UNSUPPORTED -> Attempt.Fatal("This phone doesn't support Wi-Fi Direct.")
                CreateResult.MISSING_PERMISSION ->
                    Attempt.Fatal("Allow the Nearby devices permission (Location on Android 10–12), then start sharing again.")
                else -> Attempt.Retry(result.reason)
            }
        }
        val info = awaitGroupInfo() ?: return Attempt.Retry(CreateResult.ERROR)
        val ownerAddress = info.ownerAddress ?: return Attempt.Retry(CreateResult.ERROR)
        if (info.networkName != creds.ssid || info.passphrase != creds.password) {
            api.removeGroup()
            return Attempt.Fatal(
                "This phone ($deviceModel) ignored the requested Wi-Fi name or password. Wi-Fi Direct sharing can't be used on it.",
            )
        }
        return Attempt.Up(LinkState.Up(ownerAddress, creds))
    }

    /** Group info can lag createGroup's success callback; poll briefly for a complete answer. */
    private suspend fun awaitGroupInfo(): GroupInfo? {
        repeat(infoRetries) { i ->
            api.groupInfo()?.takeIf { it.ownerAddress != null }?.let { return it }
            if (i < infoRetries - 1) delay(infoRetryDelayMs)
        }
        return null
    }

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
