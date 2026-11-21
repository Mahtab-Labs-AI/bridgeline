package com.wifiproxy.app.settings

import android.content.SharedPreferences
import androidx.core.content.edit

/** The one persistence seam the Wi-Fi stores need, so they can be tested with a map on the JVM. */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
}

class SharedPrefsStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String?) = prefs.edit { if (value == null) remove(key) else putString(key, value) }
}
