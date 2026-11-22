package com.wifiproxy.app.settings

import android.content.Context
import androidx.core.content.edit
import com.wifiproxy.app.logs.VerbosePrefs

private const val PREFS_NAME = "wifi_proxy_prefs"
private const val KEY_USB_SHARING = "usb_sharing_enabled"
private const val KEY_DEMO_MODE = "demo_mode"
private const val KEY_MAX_CLIENTS = "max_clients"
private const val KEY_WIFI_PORT = "wifi_port"
private const val KEY_PREFER_5GHZ = "prefer_5ghz"
private const val KEY_SESSION_DESIRED = "session_desired"
private const val KEY_REQUIRE_DEVICE_CODES = "require_device_codes"
private const val KEY_VERBOSE_UNTIL = "verbose_until_ms"
private const val KEY_VERBOSE_VERSION = "verbose_version_code"

/**
 * The app-level settings that live outside [com.wifiproxy.app.session.SessionRepository]:
 * "USB sharing (laptop)", "Demo mode" (off - real mode - by default on a fresh install), the
 * non-secret host settings ([HostPrefs]; the Wi-Fi name and password live in the encrypted
 * [com.wifiproxy.app.wifi.CredentialStore]) and [sessionDesired].
 */
class AppPreferences(context: Context) : HostPrefs, VerbosePrefs {
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

    /** User decision D8: off by default - any device that joined the group (so knows the Wi-Fi
     *  password) may use the proxy, up to the device limit. On restores per-device invites. */
    override var requireDeviceCodes: Boolean
        get() = prefs.getBoolean(KEY_REQUIRE_DEVICE_CODES, false)
        set(value) = prefs.edit { putBoolean(KEY_REQUIRE_DEVICE_CODES, value) }

    /** True while the user wants sharing on (spec §3): set by a Start, cleared by a Stop or any
     *  other path to IDLE while the service is alive. A sticky restart after process death only
     *  resumes sharing while this is still true. */
    var sessionDesired: Boolean
        get() = prefs.getBoolean(KEY_SESSION_DESIRED, false)
        set(value) = prefs.edit { putBoolean(KEY_SESSION_DESIRED, value) }

    /** "Verbose logging (troubleshooting)": when it turns itself off, 0 = off (the default). Only
     *  ever set by the user's switch; see [com.wifiproxy.app.logs.VerboseSwitch]. */
    override var verboseUntilMs: Long
        get() = prefs.getLong(KEY_VERBOSE_UNTIL, 0L)
        set(value) = prefs.edit { putLong(KEY_VERBOSE_UNTIL, value) }

    override var verboseVersionCode: Int
        get() = prefs.getInt(KEY_VERBOSE_VERSION, 0)
        set(value) = prefs.edit { putInt(KEY_VERBOSE_VERSION, value) }
}
