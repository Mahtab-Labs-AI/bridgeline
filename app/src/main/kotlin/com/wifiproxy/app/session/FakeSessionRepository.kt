package com.wifiproxy.app.session

import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val STARTING_DELAY_MS = 1_500L
private const val STOPPING_DELAY_MS = 500L
private const val TICK_MS = 1_000L
private const val DEGRADE_AFTER_TICKS = 30
private const val DEGRADE_DURATION_MS = 4_000L

/** The OS-assigned two-char code in `DIRECT-xy-<suffix>`; fixed here since the fake never recreates the group. */
private const val SSID_PREFIX_CODE = "wp"

/**
 * Drives the UI mockup with fake data. [scope] hosts the start/stop/tick coroutines; [now] and [delayMs]
 * are injected so tests can run under `runTest` with virtual time instead of waiting on a real clock.
 */
class FakeSessionRepository(
    private val scope: CoroutineScope,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val delayMs: suspend (Long) -> Unit = { delay(it) },
) : SessionRepository {

    private val random = SecureRandom()
    private val tokens = mutableMapOf<String, String>()
    private var nextSlotIndex = 2 // seed data below already uses slot-0 and slot-1

    private val _settings = MutableStateFlow(
        HostSettings(
            ssidSuffix = "HostPhone",
            password = "correct horse battery",
            maxClients = 3,
            port = 8080,
            prefer5Ghz = false,
        ),
    )
    override val settings: StateFlow<HostSettings> = _settings.asStateFlow()

    private val _checks = MutableStateFlow(
        listOf(
            SetupCheck(
                title = "Permissions granted",
                detail = "Nearby Wi-Fi devices and notifications are granted.",
                status = CheckStatus.OK,
            ),
            SetupCheck(
                title = "Wi-Fi password set",
                detail = "A Wi-Fi password is configured for this session.",
                status = CheckStatus.OK,
            ),
            SetupCheck(
                title = "Cellular data available",
                detail = "The host has an active cellular connection to share.",
                status = CheckStatus.OK,
            ),
            SetupCheck(
                title = "Battery optimisation",
                detail = "Exempt Wi-Fi Proxy from battery optimisation so Android doesn't kill it in the background.",
                status = CheckStatus.ACTION_NEEDED,
            ),
            SetupCheck(
                title = "Samsung: Never sleeping apps",
                detail = "Add Wi-Fi Proxy to Never sleeping apps in Device care so One UI doesn't freeze it.",
                status = CheckStatus.ACTION_NEEDED,
            ),
        ),
    )
    override val checks: StateFlow<List<SetupCheck>> = _checks.asStateFlow()

    private val _log = MutableStateFlow<List<LogEntry>>(emptyList())
    override val log: StateFlow<List<LogEntry>> = _log.asStateFlow()

    private val seedSlots = listOf(
        SlotUi(
            id = "slot-0",
            name = "Ben's iPhone",
            platform = ClientPlatform.IPHONE,
            ip = "192.168.49.2",
            bytesUp = 0L,
            bytesDown = 0L,
            activeTunnels = 0,
        ),
        SlotUi(
            id = "slot-1",
            name = "Pixel 7",
            platform = ClientPlatform.ANDROID,
            ip = "192.168.49.3",
            bytesUp = 0L,
            bytesDown = 0L,
            activeTunnels = 0,
        ),
    ).onEach { tokens[it.id] = newToken() }

    private val _session = MutableStateFlow(
        SessionUi(
            phase = SessionPhase.IDLE,
            degradedReason = null,
            cellularUp = true,
            ssid = ssidFor(_settings.value.ssidSuffix),
            proxyHost = "192.168.49.1",
            port = _settings.value.port,
            slots = seedSlots,
            maxClients = _settings.value.maxClients,
        ),
    )
    override val session: StateFlow<SessionUi> = _session.asStateFlow()

    private var runJob: Job? = null

    private fun ssidFor(suffix: String) = "DIRECT-$SSID_PREFIX_CODE-$suffix"

    private fun newToken(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun appendLog(message: String) {
        _log.update { it + LogEntry(now(), message) }
    }

    override fun start() {
        if (_session.value.phase != SessionPhase.IDLE) return
        runJob = scope.launch {
            _session.update { it.copy(phase = SessionPhase.STARTING) }
            appendLog("Starting session")
            delayMs(STARTING_DELAY_MS)
            _session.update { it.copy(phase = SessionPhase.RUNNING, degradedReason = null) }
            appendLog("Session running: ${_session.value.ssid}")
            runLoop()
        }
    }

    private suspend fun runLoop() {
        var tick = 0
        while (true) {
            delayMs(TICK_MS)
            tick++
            if (_session.value.phase == SessionPhase.RUNNING) {
                growCounters()
            }
            if (tick == DEGRADE_AFTER_TICKS) {
                _session.update { it.copy(phase = SessionPhase.DEGRADED, degradedReason = "No mobile data") }
                appendLog("Degraded: no mobile data")
                delayMs(DEGRADE_DURATION_MS)
                _session.update { it.copy(phase = SessionPhase.RUNNING, degradedReason = null) }
                appendLog("Recovered: session running")
            }
        }
    }

    private fun growCounters() {
        _session.update { s ->
            s.copy(
                slots = s.slots.mapIndexed { index, slot ->
                    slot.copy(
                        bytesUp = slot.bytesUp + 300L + index * 500L,
                        bytesDown = slot.bytesDown + 1_000L + index * 2_000L,
                        activeTunnels = 1,
                    )
                },
            )
        }
    }

    override fun stop() {
        if (_session.value.phase == SessionPhase.IDLE) return
        runJob?.cancel()
        runJob = scope.launch {
            _session.update { it.copy(phase = SessionPhase.STOPPING) }
            appendLog("Stopping session")
            delayMs(STOPPING_DELAY_MS)
            _session.update {
                it.copy(
                    phase = SessionPhase.IDLE,
                    degradedReason = null,
                    slots = it.slots.map { slot -> slot.copy(activeTunnels = 0) },
                )
            }
            appendLog("Session stopped")
        }
    }

    override fun addDevice(name: String, platform: ClientPlatform): DeviceInvite? {
        val current = _session.value
        if (current.slots.size >= current.maxClients) return null

        val id = "slot-${nextSlotIndex++}"
        val slot = SlotUi(
            id = id,
            name = name,
            platform = platform,
            ip = null,
            bytesUp = 0L,
            bytesDown = 0L,
            activeTunnels = 0,
        )
        tokens[id] = newToken()
        _session.update { it.copy(slots = it.slots + slot) }
        appendLog("Added device: $name")
        return inviteFor(id)
    }

    override fun inviteFor(slotId: String): DeviceInvite? {
        val token = tokens[slotId] ?: return null
        val s = _session.value
        val currentSettings = _settings.value
        return DeviceInvite(
            slotId = slotId,
            wifiQr = QrPayloads.wifiQr(s.ssid, currentSettings.password),
            pacUrl = QrPayloads.pacUrl(s.proxyHost, s.port, token),
            mobileConfigUrl = QrPayloads.mobileConfigUrl(s.proxyHost, s.port, token),
        )
    }

    override fun revoke(slotId: String) {
        val slot = _session.value.slots.find { it.id == slotId } ?: return
        tokens.remove(slotId)
        _session.update { it.copy(slots = it.slots.filterNot { s -> s.id == slotId }) }
        appendLog("Revoked device: ${slot.name}")
    }

    override fun updateSettings(settings: HostSettings): Boolean {
        val activeCount = _session.value.slots.size
        if (settings.maxClients < activeCount) return false

        val passwordChanged = settings.password != _settings.value.password
        _settings.value = settings
        _session.update {
            it.copy(
                ssid = ssidFor(settings.ssidSuffix),
                port = settings.port,
                maxClients = settings.maxClients,
                slots = if (passwordChanged) emptyList() else it.slots,
            )
        }
        if (passwordChanged) {
            tokens.clear()
            appendLog("Password changed: all devices dropped")
        }
        appendLog("Settings updated")
        return true
    }
}
