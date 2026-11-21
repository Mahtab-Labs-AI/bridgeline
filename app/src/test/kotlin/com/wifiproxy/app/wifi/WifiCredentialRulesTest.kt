package com.wifiproxy.app.wifi

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WifiCredentialRulesTest {
    @Test
    fun `suffix accepts letters digits dash underscore up to 22`() {
        assertNull(WifiCredentialRules.suffixError("HostPhone"))
        assertNull(WifiCredentialRules.suffixError("a-b_C9"))
        assertNull(WifiCredentialRules.suffixError("x".repeat(22)))
    }

    @Test
    fun `suffix rejects empty, too long, spaces and non-ascii`() {
        assertNotNull(WifiCredentialRules.suffixError(""))
        assertNotNull(WifiCredentialRules.suffixError("x".repeat(23)))
        assertNotNull(WifiCredentialRules.suffixError("my phone"))
        assertNotNull(WifiCredentialRules.suffixError("téléphone"))
    }

    @Test
    fun `password must be 8 to 63 printable ascii`() {
        assertNull(WifiCredentialRules.passwordError("12345678"))
        assertNull(WifiCredentialRules.passwordError("correct horse battery staple!"))
        assertNull(WifiCredentialRules.passwordError("p".repeat(63)))
        assertNotNull(WifiCredentialRules.passwordError("1234567"))
        assertNotNull(WifiCredentialRules.passwordError("p".repeat(64)))
        assertNotNull(WifiCredentialRules.passwordError("pass\nword1"))
        assertNotNull(WifiCredentialRules.passwordError("pässwörd1"))
    }

    @Test
    fun `ssid code is two lowercase alphanumerics`() {
        val random = Random(42)
        repeat(100) {
            val code = WifiCredentialRules.newSsidCode(random)
            assertEquals(2, code.length)
            assertTrue(code.all { it in 'a'..'z' || it in '0'..'9' })
        }
    }

    @Test
    fun `ssid is prefix plus suffix and fits 32 bytes, toString redacts password`() {
        val c = StoredCredentials(ssidCode = "k3", suffix = "x".repeat(22), password = "secret-password")
        assertEquals("DIRECT-k3-" + "x".repeat(22), c.ssid)
        assertEquals(32, c.ssid.toByteArray(Charsets.UTF_8).size)
        assertFalse(c.toString().contains("secret-password"))
    }

    @Test
    fun `the shown prefix is the saved code, or a placeholder when nothing is saved`() {
        assertEquals("DIRECT-ks-", WifiCredentialRules.prefixOf("DIRECT-ks-HostPhone"))
        assertEquals("DIRECT-··-", WifiCredentialRules.prefixOf(null))
        assertEquals("DIRECT-··-", WifiCredentialRules.prefixOf("USB only"))
        assertEquals("DIRECT-··-", WifiCredentialRules.prefixOf("DIRECT-K3-Upper"))
    }
}
