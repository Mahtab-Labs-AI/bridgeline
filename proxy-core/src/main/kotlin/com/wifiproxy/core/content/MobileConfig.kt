package com.wifiproxy.core.content

import java.util.UUID

/** iOS configuration profile: one Wi-Fi payload with automatic (PAC) proxy. */
object MobileConfig {
    const val CONTENT_TYPE = "application/x-apple-aspen-config"

    fun render(ssid: String, password: String, pacUrl: String, token: String): String {
        val wifiUuid = UUID.nameUUIDFromBytes("wifi:$token".toByteArray()).toString().uppercase()
        val profileUuid = UUID.nameUUIDFromBytes("profile:$token".toByteArray()).toString().uppercase()
        return """
            |<?xml version="1.0" encoding="UTF-8"?>
            |<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            |<plist version="1.0">
            |<dict>
            |<key>PayloadContent</key>
            |<array>
            |<dict>
            |<key>PayloadType</key><string>com.apple.wifi.managed</string>
            |<key>PayloadVersion</key><integer>1</integer>
            |<key>PayloadIdentifier</key><string>com.wifiproxy.wifi.$wifiUuid</string>
            |<key>PayloadUUID</key><string>$wifiUuid</string>
            |<key>PayloadDisplayName</key><string>Wi-Fi (${escape(ssid)})</string>
            |<key>SSID_STR</key><string>${escape(ssid)}</string>
            |<key>EncryptionType</key><string>WPA2</string>
            |<key>Password</key><string>${escape(password)}</string>
            |<key>AutoJoin</key><true/>
            |<key>ProxyType</key><string>Auto</string>
            |<key>ProxyPACURL</key><string>${escape(pacUrl)}</string>
            |<key>ProxyPACFallbackAllowed</key><false/>
            |</dict>
            |</array>
            |<key>PayloadType</key><string>Configuration</string>
            |<key>PayloadVersion</key><integer>1</integer>
            |<key>PayloadIdentifier</key><string>com.wifiproxy.profile.$profileUuid</string>
            |<key>PayloadUUID</key><string>$profileUuid</string>
            |<key>PayloadDisplayName</key><string>Wi-Fi Proxy (${escape(ssid)})</string>
            |<key>PayloadRemovalDisallowed</key><false/>
            |</dict>
            |</plist>
        """.trimMargin() + "\n"
    }

    private fun escape(s: String): String {
        require(s.none { it < ' ' && it != '\t' && it != '\n' && it != '\r' }) { "control characters are not allowed in profile values" }
        return s
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
            .replace("\n", "&#10;")
            .replace("\r", "&#13;")
    }
}
