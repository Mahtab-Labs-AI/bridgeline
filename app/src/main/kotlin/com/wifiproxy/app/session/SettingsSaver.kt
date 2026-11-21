package com.wifiproxy.app.session

import com.wifiproxy.app.settings.HostConfigSource
import com.wifiproxy.app.settings.HostSnapshot
import com.wifiproxy.app.settings.SaveResult
import com.wifiproxy.app.wifi.SlotStore

/**
 * What [ServiceSessionRepository.updateSettings] does with a save, kept free of Android so a JVM
 * test can drive it:
 * - (D7) refused when the saved Wi-Fi devices (active and inactive alike, counted from storage
 *   whether or not a session runs) plus the USB laptop's seat wouldn't fit the new `maxClients`;
 *   a password change counts none of them, since it deletes them all (plan decision 5);
 * - a password change deletes every saved Wi-Fi slot; a name-only change keeps them;
 * - either change restarts a running session ([restart]), so it comes up with the new
 *   credentials; anything else applies the next time sharing starts.
 */
internal class SettingsSaver(
    private val hostConfig: HostConfigSource,
    private val slotStore: SlotStore,
    private val usbEnabled: () -> Boolean,
    private val sessionActive: () -> Boolean,
    private val restart: () -> Unit,
) {
    /** The saved state, or null when the save was refused (invalid, or D7). */
    fun save(settings: HostSettings): HostSnapshot? {
        val outcome = hostConfig.update(settings) { passwordChanged ->
            SeatRules.fitsAfterSave(slotStore.load().size, passwordChanged, usbEnabled(), settings.maxClients)
        }
        val snapshot = outcome.snapshot ?: return null
        when (outcome.result) {
            SaveResult.SAVED_PASSWORD_CHANGED -> {
                slotStore.clear()
                if (sessionActive()) restart()
            }
            SaveResult.SAVED_SSID_CHANGED -> if (sessionActive()) restart()
            SaveResult.SAVED, SaveResult.INVALID -> Unit
        }
        return snapshot
    }
}
