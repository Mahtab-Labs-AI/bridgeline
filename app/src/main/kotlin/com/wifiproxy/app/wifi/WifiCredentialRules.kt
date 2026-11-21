package com.wifiproxy.app.wifi

import java.util.Random

/** What the host user configures for the Wi-Fi Direct group (design spec §4 Layer 1). [ssidCode]
 *  is the `xy` in `DIRECT-xy-`: chosen once at random and kept, so the SSID never changes and
 *  clients auto-rejoin. */
data class StoredCredentials(val ssidCode: String, val suffix: String, val password: String) {
    val ssid: String get() = "DIRECT-$ssidCode-$suffix"
    override fun toString() = "StoredCredentials(ssid=$ssid, password=****)"
}

object WifiCredentialRules {
    /** 32-byte SSID limit minus the 10-byte `DIRECT-xy-` prefix. */
    const val SUFFIX_MAX = 22
    private val SUFFIX = Regex("[A-Za-z0-9_-]{1,$SUFFIX_MAX}")
    private const val CODE_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789"

    fun suffixError(suffix: String): String? = when {
        suffix.isEmpty() -> "Enter a network name"
        suffix.length > SUFFIX_MAX -> "At most $SUFFIX_MAX characters"
        !SUFFIX.matches(suffix) -> "Use letters, digits, - and _ only"
        else -> null
    }

    /** WPA2 passphrase rule: 8-63 printable ASCII characters. */
    fun passwordError(password: String): String? = when {
        password.length < 8 -> "At least 8 characters"
        password.length > 63 -> "At most 63 characters"
        password.any { it.code !in 0x20..0x7E } -> "Use plain ASCII characters only"
        else -> null
    }

    fun newSsidCode(random: Random): String =
        String(CharArray(2) { CODE_CHARS[random.nextInt(CODE_CHARS.length)] })
}
