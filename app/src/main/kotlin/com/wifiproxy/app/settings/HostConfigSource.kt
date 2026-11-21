package com.wifiproxy.app.settings

import android.content.Context
import com.wifiproxy.app.service.SessionConfig
import com.wifiproxy.app.session.HostSettings
import com.wifiproxy.app.wifi.AesGcmSecretBox
import com.wifiproxy.app.wifi.CredentialStore
import com.wifiproxy.app.wifi.KeystoreKeys
import com.wifiproxy.app.wifi.LinkConfig
import com.wifiproxy.app.wifi.WifiCredentialRules
import com.wifiproxy.core.WifiCredentials

private const val SECURE_PREFS_NAME = "wifi_proxy_secure"
private const val CREDENTIALS_KEY_ALIAS = "wifi-credentials"

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

enum class SaveResult { SAVED, SAVED_CREDENTIALS_CHANGED, INVALID }

/**
 * The persisted host settings: [prefs] for the plain ones, [credentials] (encrypted) for the
 * Wi-Fi name and password. [sessionConfig] is what [com.wifiproxy.app.service.SessionController]
 * reads once per start.
 */
class HostConfigSource(
    private val prefs: HostPrefs,
    private val credentials: CredentialStore,
) {
    fun hostSettings(): HostSettings {
        val c = credentials.get()
        return HostSettings(
            ssidSuffix = c?.suffix.orEmpty(),
            password = c?.password.orEmpty(),
            maxClients = prefs.maxClients,
            port = prefs.wifiPort,
            prefer5Ghz = prefs.prefer5Ghz,
        )
    }

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
    fun save(settings: HostSettings): SaveResult {
        if (settings.port !in WIFI_PORT_RANGE || settings.port == USB_PORT) return SaveResult.INVALID
        if (settings.maxClients !in MAX_CLIENTS_RANGE) return SaveResult.INVALID
        val clear = settings.ssidSuffix.isBlank() && settings.password.isBlank()
        if (!clear &&
            (WifiCredentialRules.suffixError(settings.ssidSuffix) != null || WifiCredentialRules.passwordError(settings.password) != null)
        ) {
            return SaveResult.INVALID
        }

        val before = credentials.get()
        val changed = if (clear) {
            before != null
        } else {
            before?.suffix != settings.ssidSuffix || before.password != settings.password
        }

        prefs.maxClients = settings.maxClients
        prefs.wifiPort = settings.port
        prefs.prefer5Ghz = settings.prefer5Ghz
        if (changed) {
            if (clear) credentials.clear() else credentials.set(settings.ssidSuffix, settings.password)
        }
        return if (changed) SaveResult.SAVED_CREDENTIALS_CHANGED else SaveResult.SAVED
    }

    companion object {
        fun forContext(context: Context): HostConfigSource = HostConfigSource(
            AppPreferences(context),
            CredentialStore(secureStore(context), AesGcmSecretBox { KeystoreKeys.getOrCreate(CREDENTIALS_KEY_ALIAS) }),
        )

        /** The app-private store behind the credentials and the saved Wi-Fi slots
         *  ([com.wifiproxy.app.wifi.SlotStore]); `allowBackup=false` keeps it on this device. */
        fun secureStore(context: Context): KeyValueStore =
            SharedPrefsStore(context.getSharedPreferences(SECURE_PREFS_NAME, Context.MODE_PRIVATE))
    }
}
