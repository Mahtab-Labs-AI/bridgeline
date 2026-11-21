package com.wifiproxy.app.session

import kotlin.test.Test
import kotlin.test.assertEquals

class QrPayloadsTest {

    @Test
    fun `wifi qr has the WPA payload shape`() {
        assertEquals(
            "WIFI:T:WPA;S:DIRECT-wp-HostPhone;P:correct horse battery;;",
            QrPayloads.wifiQr("DIRECT-wp-HostPhone", "correct horse battery"),
        )
    }

    @Test
    fun `wifi qr escapes special characters in ssid and password`() {
        assertEquals(
            """WIFI:T:WPA;S:a\;b\,c\:d\"e\\f;P:p\;ass;;""",
            QrPayloads.wifiQr("""a;b,c:d"e\f""", "p;ass"),
        )
    }

    @Test
    fun `pac url is token scoped under the host and port`() {
        assertEquals(
            "http://192.168.49.1:8080/abc123.pac",
            QrPayloads.pacUrl("192.168.49.1", 8080, "abc123"),
        )
    }

    @Test
    fun `open admission urls are the fixed paths the proxy serves`() {
        assertEquals("http://192.168.49.1:8080/proxy.pac", QrPayloads.openPacUrl("192.168.49.1", 8080))
        assertEquals("http://192.168.49.1:9090/proxy.mobileconfig", QrPayloads.openMobileConfigUrl("192.168.49.1", 9090))
    }

    @Test
    fun `mobileconfig url mirrors the pac url with a different extension`() {
        assertEquals(
            "http://192.168.49.1:8080/abc123.mobileconfig",
            QrPayloads.mobileConfigUrl("192.168.49.1", 8080, "abc123"),
        )
    }
}
