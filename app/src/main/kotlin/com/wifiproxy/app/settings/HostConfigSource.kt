package com.wifiproxy.app.settings

import android.content.Context
import android.util.Log
import com.wifiproxy.app.service.SessionConfig
import com.wifiproxy.app.session.HostSettings
import com.wifiproxy.app.wifi.AesGcmSecretBox
import com.wifiproxy.app.wifi.CredentialStore
import com.wifiproxy.app.wifi.KeystoreKeys
import com.wifiproxy.app.wifi.LinkConfig
import com.wifiproxy.app.wifi.StoredCredentials
import com.wifiproxy.app.wifi.WifiCredentialRules
import com.wifiproxy.core.WifiCredentials

private const val SECURE_PREFS_NAME = "wifi_proxy_secure"
private const val CREDENTIALS_KEY_ALIAS = "wifi-credentials"
private const val TAG = "HostConfigSource"

/** The USB listener's fixed port (`adb forward tcp:8081`); the Wi-Fi port must differ. */
private const val USB_PORT = 8081
private val WIFI_PORT_RANGE = 1024..65535
private val MAX_CLIENTS_RANGE = 1..3

/** The non-secret host settings, persisted by [AppPreferences]. */
interface HostPrefs {
    var usbSharingEnabled: Boolean
    var maxClients: Int
    var wifiPort: Int
    var prefer5Ghz: Boolean
}

/**
 * [SAVED_SSID_CHANGED]: only the network name changed - a running session must restart for the
 * new SSID, but saved devices keep their slots. [SAVED_PASSWORD_CHANGED]: the password changed
 * (including setting the first one, or clearing the credentials) - every saved Wi-Fi slot must be
 * deleted (plan decision 5). A save changing both reports [SAVED_PASSWORD_CHANGED].
 */
enum class SaveResult { SAVED, SAVED_SSID_CHANGED, SAVED_PASSWORD_CHANGED, INVALID }

/** [HostSettings] plus the stored credentials' full SSID (`DIRECT-xy-<suffix>`, null when none
 *  are set), read with a single decrypt. */
data class HostSnapshot(val settings: HostSettings, val ssid: String?)

/** What [HostConfigSource.update] did; [snapshot] is the new state, null on [SaveResult.INVALID]. */
data class SaveOutcome(val result: SaveResult, val snapshot: HostSnapshot?)

/**
 * The persisted host settings: [prefs] for the plain ones, [credentials] (encrypted) for the
 * Wi-Fi name and password. [sessionConfig] is what [com.wifiproxy.app.service.SessionController]
 * reads once per start.
 */
class HostConfigSource(
    private val prefs: HostPrefs,
    private val credentials: CredentialStore,
) {
    fun hostSettings(): HostSettings = snapshot().settings

    /** One decrypt for both the settings and the SSID. */
    fun snapshot(): HostSnapshot = snapshotOf(credentials.get())

    private fun snapshotOf(c: StoredCredentials?) = HostSnapshot(
        settings = HostSettings(
            ssidSuffix = c?.suffix.orEmpty(),
            password = c?.password.orEmpty(),
            maxClients = prefs.maxClients,
            port = prefs.wifiPort,
            prefer5Ghz = prefs.prefer5Ghz,
        ),
        ssid = c?.ssid,
    )

    /** No credentials set means a USB-only session (plan decision 2). */
    fun sessionConfig(): SessionConfig {
        val c = credentials.get()
        return SessionConfig(
            usbEnabled = prefs.usbSharingEnabled,
            wifi = c?.let { LinkConfig(WifiCredentials(it.ssid, it.password), prefs.prefer5Ghz) },
            wifiPort = prefs.wifiPort,
            maxClients = prefs.maxClients,
        )
    }

    /** Validates everything first and changes nothing on [SaveResult.INVALID]. A blank name and
     *  password together clear the credentials (Wi-Fi Direct off). */
    fun save(settings: HostSettings): SaveResult = update(settings).result

    /**
     * [save], also returning the new [HostSnapshot] so a caller needn't decrypt again. At most one
     * decrypt (the previous credentials) and one encrypt (only when they change).
     *
     * [allow] is asked once the settings are valid, with whether this save changes the password
     * (and so deletes the saved devices); returning false refuses the save as
     * [SaveResult.INVALID], changing nothing.
     */
    fun update(settings: HostSettings, allow: (passwordChanged: Boolean) -> Boolean = { true }): SaveOutcome {
        val invalid = SaveOutcome(SaveResult.INVALID, null)
        if (portError(settings.port) != null) return invalid
        if (settings.maxClients !in MAX_CLIENTS_RANGE) return invalid
        val clear = settings.ssidSuffix.isBlank() && settings.password.isBlank()
        if (!clear &&
            (WifiCredentialRules.suffixError(settings.ssidSuffix) != null || WifiCredentialRules.passwordError(settings.password) != null)
        ) {
            return invalid
        }

        val before = credentials.get()
        // Clearing set credentials removes the password too; setting the first ones sets one.
        val passwordChanged = if (clear) before != null else before?.password != settings.password
        val ssidChanged = !clear && before != null && before.suffix != settings.ssidSuffix
        val changed = passwordChanged || ssidChanged
        if (!allow(passwordChanged)) return invalid

        prefs.maxClients = settings.maxClients
        prefs.wifiPort = settings.port
        prefs.prefer5Ghz = settings.prefer5Ghz
        val after = when {
            !changed -> before
            clear -> {
                credentials.clear()
                null
            }
            else -> credentials.set(settings.ssidSuffix, settings.password, ssidCode = before?.ssidCode)
        }
        val result = when {
            passwordChanged -> SaveResult.SAVED_PASSWORD_CHANGED
            ssidChanged -> SaveResult.SAVED_SSID_CHANGED
            else -> SaveResult.SAVED
        }
        return SaveOutcome(result, snapshotOf(after))
    }

    companion object {
        /** Why [port] can't be the Wi-Fi listener's port, or null when it can. */
        fun portError(port: Int): String? = when {
            port !in WIFI_PORT_RANGE -> "Use a port from ${WIFI_PORT_RANGE.first} to ${WIFI_PORT_RANGE.last}"
            port == USB_PORT -> "$USB_PORT is the USB port; pick another"
            else -> null
        }

        fun forContext(context: Context): HostConfigSource = HostConfigSource(
            AppPreferences(context),
            CredentialStore(
                secureStore(context),
                AesGcmSecretBox { KeystoreKeys.getOrCreate(CREDENTIALS_KEY_ALIAS) },
                onTransientError = { className -> Log.w(TAG, "Couldn't read the Wi-Fi credentials, kept them: $className") },
            ),
        )

        /** The app-private store behind the credentials and the saved Wi-Fi slots
         *  ([com.wifiproxy.app.wifi.SlotStore]); `allowBackup=false` keeps it on this device. */
        fun secureStore(context: Context): KeyValueStore =
            SharedPrefsStore(context.getSharedPreferences(SECURE_PREFS_NAME, Context.MODE_PRIVATE))
    }
}
