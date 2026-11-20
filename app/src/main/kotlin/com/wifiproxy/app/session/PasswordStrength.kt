package com.wifiproxy.app.session

/** WPA2 passphrases are 8-63 ASCII characters; anything shorter can't even be set. */
enum class PasswordStrength { INVALID, WEAK, FAIR, STRONG }

/**
 * Pure scoring function: length plus the number of distinct character classes present
 * (lowercase, uppercase, digit, symbol).
 */
fun passwordStrength(password: String): PasswordStrength {
    if (password.length < 8) return PasswordStrength.INVALID

    var classes = 0
    if (password.any { it.isLowerCase() }) classes++
    if (password.any { it.isUpperCase() }) classes++
    if (password.any { it.isDigit() }) classes++
    if (password.any { !it.isLetterOrDigit() }) classes++

    return when {
        password.length in 8..9 || classes <= 1 -> PasswordStrength.WEAK
        password.length >= 12 && classes >= 3 -> PasswordStrength.STRONG
        else -> PasswordStrength.FAIR
    }
}
