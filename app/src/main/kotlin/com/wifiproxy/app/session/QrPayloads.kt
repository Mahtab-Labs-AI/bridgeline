package com.wifiproxy.app.session

/** Pure builders for the two QR payloads shown on the Add-device invite sheet. */
object QrPayloads {

    private val ESCAPED_CHARS = charArrayOf('\\', ';', ',', ':', '"')

    private fun escape(value: String): String = buildString {
        for (c in value) {
            if (c in ESCAPED_CHARS) append('\\')
            append(c)
        }
    }

    /** `WIFI:T:WPA;S:<ssid>;P:<pass>;;`, escaping `\ ; , : "` with a backslash. */
    fun wifiQr(ssid: String, password: String): String =
        "WIFI:T:WPA;S:${escape(ssid)};P:${escape(password)};;"

    fun pacUrl(host: String, port: Int, token: String): String =
        "http://$host:$port/$token.pac"

    fun mobileConfigUrl(host: String, port: Int, token: String): String =
        "http://$host:$port/$token.mobileconfig"
}
