package com.wifiproxy.app.session

import kotlin.test.Test
import kotlin.test.assertEquals

class PasswordStrengthTest {

    @Test
    fun `under 8 characters is invalid`() {
        assertEquals(PasswordStrength.INVALID, passwordStrength(""))
        assertEquals(PasswordStrength.INVALID, passwordStrength("Ab1!xyz"))
    }

    @Test
    fun `exactly 8 lowercase-only characters is weak`() {
        assertEquals(PasswordStrength.WEAK, passwordStrength("abcdefgh"))
    }

    @Test
    fun `single character class is weak regardless of length`() {
        assertEquals(PasswordStrength.WEAK, passwordStrength("aaaaaaaaaaaaaaaa"))
    }

    @Test
    fun `ten chars with two classes is fair`() {
        assertEquals(PasswordStrength.FAIR, passwordStrength("abcdefgh12"))
    }

    @Test
    fun `twelve or more chars with three or more classes is strong`() {
        assertEquals(PasswordStrength.STRONG, passwordStrength("correcthorse1!"))
        assertEquals(PasswordStrength.STRONG, passwordStrength("Tr0ub4dor&3xx"))
    }

    @Test
    fun `twelve chars but only two classes is fair not strong`() {
        assertEquals(PasswordStrength.FAIR, passwordStrength("abcdefghijk1"))
    }

    @Test
    fun `nine chars with three classes is still weak by length`() {
        assertEquals(PasswordStrength.WEAK, passwordStrength("abcDEF1!9"))
    }
}
