package com.wifiproxy.app.settings

import android.content.Context
import androidx.core.content.edit

private const val PREFS_NAME = "wifi_proxy_prefs"
private const val KEY_USB_SHARING = "usb_sharing_enabled"
private const val KEY_DEMO_MODE = "demo_mode"

/**
 * The two app-level toggles that live outside [com.wifiproxy.app.session.SessionRepository]
 * (design spec / Plan 2a Task 4): "USB sharing (laptop)" (always on in 2a; shown disabled) and
 * "Demo mode" (off - real mode - by default on a fresh install).
 */
class AppPreferences(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var usbSharingEnabled: Boolean
        get() = prefs.getBoolean(KEY_USB_SHARING, true)
        set(value) = prefs.edit { putBoolean(KEY_USB_SHARING, value) }

    /** Real mode is the default on the first launch on any device; Demo mode is opt-in. */
    var demoMode: Boolean
        get() = prefs.getBoolean(KEY_DEMO_MODE, false)
        set(value) = prefs.edit { putBoolean(KEY_DEMO_MODE, value) }
}
