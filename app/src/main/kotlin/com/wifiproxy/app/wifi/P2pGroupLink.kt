package com.wifiproxy.app.wifi

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
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
 * [start], [stop] and the internal re-creates never race each other: every state change is a
 * [Command] processed, one at a time and strictly in order, by a single supervisor coroutine (see
 * [commands]). Before it acts on a command - starting a new run, or tearing down with
 * `removeGroup` + `Idle` - the supervisor always cancels and *fully, non-cancellably* joins
 * whatever run came before, even one stuck inside an uncancellable Android call. That means an
 * earlier run can never act after a later command has started being processed, and there is never
 * a window where two runs are both live, or where a run and a teardown are both in flight.
 *
 * None of that matters if [stop] can hang forever, though, and the supervisor is not guaranteed to
 * outlive the object: [scope] can be cancelled (e.g. a service's `onDestroy`) out from under a
 * pending [stop] call, or even before the supervisor ever got to run at all. [stop] therefore falls
 * back to tearing down inline whenever it can tell nobody is left to process its command (see
 * [stop]'s body), and the supervisor itself always closes [commands] - draining and completing any
 * already-queued [Command.Stop] - no matter how it ends, including never having started. A bug
 * inside a run is also never allowed to propagate out and take the supervisor down with it: see
 * [run]'s `try`/`catch`.
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

    private sealed interface Command {
        data class Start(val config: LinkConfig) : Command
        data class Stop(val done: CompletableDeferred<Unit>) : Command
    }

    /** Every start()/stop() is a [Command] sent here; [UNLIMITED][Channel.UNLIMITED] because
     *  [start] must not suspend or drop a request. A single long-lived coroutine (below) drains
     *  this strictly in order, which is what makes start/stop/recreate race-free. Closed once the
     *  supervisor is gone for any reason - see the class KDoc. */
    private val commands = Channel<Command>(Channel.UNLIMITED)

    /** The currently active run, if any. Normally touched only by the supervisor coroutine; also
     *  read and written by [stop]'s inline-teardown fallback, but only once [commands] is known to
     *  be closed, i.e. only after the supervisor is confirmed gone - so there is never genuine
     *  concurrent access, just a value that must be visible across whichever threads are involved. */
    @Volatile
    private var job: Job? = null

    init {
        val supervisor = scope.launch {
            try {
                for (command in commands) {
                    // Whatever run is current is no longer wanted as of this command; wait for it
                    // to actually finish - uncancellably, since it might itself be stuck inside an
                    // uncancellable Android call - before acting on the command. This is the one
                    // rule that makes the whole class race-free: nothing below ever runs while an
                    // old run might still touch the API.
                    cancelCurrentRun()
                    when (command) {
                        is Command.Start -> job = scope.launch { run(command.config) }
                        is Command.Stop -> finishStopCommand(command)
                    }
                }
            } finally {
                closeAndDrainCommands()
            }
        }
        // Belt and suspenders for the case the body above never even started (scope already
        // cancelled when this object was constructed): invokeOnCompletion fires for that too.
        supervisor.invokeOnCompletion { closeAndDrainCommands() }
    }

    private suspend fun cancelCurrentRun() {
        job?.let { withContext(NonCancellable) { it.cancelAndJoin() } }
        job = null
    }

    /** Tears down for a [Command.Stop]: always reaches [LinkState.Idle] and always completes
     *  [Command.Stop.done], even if [P2pApi.removeGroup] throws or this coroutine is cancelled
     *  partway through (the `finally` still runs; completing an already-completed `done` is a
     *  no-op). */
    private suspend fun finishStopCommand(command: Command.Stop) {
        try {
            removeGroupQuietly()
            _state.value = LinkState.Idle
        } finally {
            command.done.complete(Unit)
        }
    }

    /** [P2pApi.removeGroup] is documented to never throw, but [stop] must not hang or blow up on a
     *  misbehaving implementation. A genuine cancellation is never swallowed. */
    private suspend fun removeGroupQuietly() {
        try {
            api.removeGroup()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Swallow: reaching Idle (and completing stop()'s caller) matters more than this.
        }
    }

    private fun closeAndDrainCommands() {
        commands.close()
        while (true) {
            val leftover = commands.tryReceive().getOrNull() ?: break
            if (leftover is Command.Stop) leftover.done.complete(Unit)
        }
    }

    override fun start(config: LinkConfig) {
        // A no-op if the supervisor is gone: commands is closed, so this trySend simply fails.
        commands.trySend(Command.Start(config))
    }

    override suspend fun stop() {
        val done = CompletableDeferred<Unit>()
        if (commands.trySend(Command.Stop(done)).isSuccess) {
            done.await()
            return
        }
        // The supervisor is gone - scope was cancelled (possibly before it ever got to run at
        // all) - so there is nobody left to process a Stop command. Tear down inline instead of
        // awaiting a done that would otherwise never complete.
        cancelCurrentRun()
        removeGroupQuietly()
        _state.value = LinkState.Idle
    }

    override fun checkHealth() {
        healthChecks.trySend(Unit)
    }

    private sealed interface Attempt {
        data class Up(val state: LinkState.Up) : Attempt
        data class Fatal(val message: String) : Attempt
        data class Retry(val reason: Int) : Attempt
    }

    /** Runs [config] until stopped or fatally failed. A bug here must never kill the supervisor
     *  coroutine - and so never leave [stop] hanging - so anything but [CancellationException] is
     *  caught and turned into [LinkState.Failed] instead of propagating. Only the exception's
     *  class name is used: the message could carry network details (spec's logging constraint). */
    private suspend fun run(config: LinkConfig) {
        try {
            runUntilStoppedOrFatal(config)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = LinkState.Failed("Wi-Fi Direct error: ${e::class.simpleName}")
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
