package com.wifiproxy.app.wifi

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

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
    private var job: Job? = null

    override fun start(config: LinkConfig) {
        // Chain onto the previous run rather than just cancel()-ing it: this guarantees the old
        // run has fully stopped (and so can't race a concurrent stop()) before the new one touches
        // the API, while still returning immediately as the contract requires.
        val prev = job
        job = scope.launch {
            prev?.cancelAndJoin()
            run(config)
        }
    }

    override suspend fun stop() {
        // Capture the job we're stopping; if a start() replaces it with a newer run while we're
        // suspended in cancelAndJoin(), that newer run is not ours to tear down - leave it alone.
        val stopping = job
        stopping?.cancelAndJoin()
        if (job === stopping) {
            job = null
            api.removeGroup()
            _state.value = LinkState.Idle
        }
    }

    override fun checkHealth() {
        healthChecks.trySend(Unit)
    }

    private sealed interface Attempt {
        data class Up(val state: LinkState.Up) : Attempt
        data class Fatal(val message: String) : Attempt
        data class Retry(val reason: Int) : Attempt
    }

    private suspend fun run(config: LinkConfig) {
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
                    if (awaitLoss()) {
                        lostPreviously = true
                        _state.value = LinkState.Down(REASON_LOST)
                    }
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
     *  (the outer loop then waits for it to come back).
     *
     *  [connectionChanged][P2pApi.connectionChanged] has no replay, and we only start collecting
     *  it here - after the create/verify round trip - so a loss that happened in that gap (or
     *  that arrived while nobody was listening) would otherwise go unnoticed until the 30 s
     *  watchdog's [checkHealth]. [onStart] forces one immediate [P2pApi.groupInfo] check as soon
     *  as we start listening, so that case is caught right away instead. */
    private suspend fun awaitLoss(): Boolean {
        var lost = false
        merge(
            api.connectionChanged.map { false },
            healthChecks.receiveAsFlow().map { false },
            api.p2pEnabled.filter { !it }.map { true },
        ).onStart { emit(false) }.first { wifiOff ->
            when {
                wifiOff -> true
                api.groupInfo() == null -> { lost = true; true }
                else -> false
            }
        }
        return lost
    }
}
