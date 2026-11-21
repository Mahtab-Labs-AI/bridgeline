package com.wifiproxy.app.settings

import android.content.Context
import androidx.core.content.edit

private const val PREFS_NAME = "wifi_proxy_prefs"
private const val KEY_USB_SHARING = "usb_sharing_enabled"
private const val KEY_DEMO_MODE = "demo_mode"
private const val KEY_MAX_CLIENTS = "max_clients"
private const val KEY_WIFI_PORT = "wifi_port"
private const val KEY_PREFER_5GHZ = "prefer_5ghz"
private const val KEY_SESSION_DESIRED = "session_desired"

/**
 * The app-level settings that live outside [com.wifiproxy.app.session.SessionRepository]:
 * "USB sharing (laptop)", "Demo mode" (off - real mode - by default on a fresh install), the
 * non-secret host settings ([HostPrefs]; the Wi-Fi name and password live in the encrypted
 * [com.wifiproxy.app.wifi.CredentialStore]) and [sessionDesired].
 */
class AppPreferences(context: Context) : HostPrefs {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override var usbSharingEnabled: Boolean
        get() = prefs.getBoolean(KEY_USB_SHARING, true)
        set(value) = prefs.edit { putBoolean(KEY_USB_SHARING, value) }

    /** Real mode is the default on the first launch on any device; Demo mode is opt-in. */
    var demoMode: Boolean
        get() = prefs.getBoolean(KEY_DEMO_MODE, false)
        set(value) = prefs.edit { putBoolean(KEY_DEMO_MODE, value) }

    override var maxClients: Int
        get() = prefs.getInt(KEY_MAX_CLIENTS, 1)
        set(value) = prefs.edit { putInt(KEY_MAX_CLIENTS, value) }

    override var wifiPort: Int
        get() = prefs.getInt(KEY_WIFI_PORT, 8080)
        set(value) = prefs.edit { putInt(KEY_WIFI_PORT, value) }

    override var prefer5Ghz: Boolean
        get() = prefs.getBoolean(KEY_PREFER_5GHZ, true)
        set(value) = prefs.edit { putBoolean(KEY_PREFER_5GHZ, value) }

    /** True while the user wants sharing on (spec §3): set by a Start, cleared by a Stop or any
     *  other path to IDLE while the service is alive. A sticky restart after process death only
     *  resumes sharing while this is still true. */
    var sessionDesired: Boolean
        get() = prefs.getBoolean(KEY_SESSION_DESIRED, false)
        set(value) = prefs.edit { putBoolean(KEY_SESSION_DESIRED, value) }
}
