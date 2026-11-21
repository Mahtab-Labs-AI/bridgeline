package com.wifiproxy.app.session

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Spec §6: no passwords, tokens or URLs in `toString()`. */
class SecretsToStringTest {
    private val password = "correct horse battery"
    private val token = "AbCdEfGhIjKlMnOpQrStUv"

    @Test
    fun `HostSettings never prints its password`() {
        val text = HostSettings(ssidSuffix = "HostPhone", password = password, maxClients = 3, port = 8080, prefer5Ghz = true).toString()
        assertFalse(text.contains(password))
        assertTrue(text.contains("****"))
        assertTrue(text.contains("HostPhone"))
    }

    @Test
    fun `DeviceInvite never prints its QR or URLs`() {
        val invite = DeviceInvite(
            slotId = "slot-42",
            wifiQr = QrPayloads.wifiQr("DIRECT-k3-HostPhone", password),
            pacUrl = QrPayloads.pacUrl("192.168.49.1", 8080, token),
            mobileConfigUrl = QrPayloads.mobileConfigUrl("192.168.49.1", 8080, token),
        )
        val text = invite.toString()
        for (secret in listOf(password, token, "http://", "WIFI:", "192.168.49.1")) {
            assertFalse(text.contains(secret), "toString leaks $secret")
        }
        assertTrue(text.contains("slot-42"))
    }
}
