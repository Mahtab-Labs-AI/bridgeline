package com.wifiproxy.app.kit

import java.io.InputStream
import java.security.MessageDigest

/** Why a downloaded `platform-tools` archive was (or wasn't) accepted (design spec §8a:
 *  "Verify the size and SHA-1, and refuse on mismatch"). */
sealed interface VerificationResult {
    data object Ok : VerificationResult
    data class Mismatch(val reason: String) : VerificationResult
}

/** Pure size/SHA-1 checking, kept free of any download or file I/O so it's unit-testable on the
 *  JVM in isolation from [LaptopKitBuilder]'s Android-dependent download/extract/write pipeline. */
object KitVerification {

    private const val HEX_DIGITS = "0123456789abcdef"

    /** Lower-case hex SHA-1 of every byte [input] yields; does not close [input]. */
    fun sha1Hex(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-1")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String {
        val out = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            out.append(HEX_DIGITS[v ushr 4]).append(HEX_DIGITS[v and 0x0F])
        }
        return out.toString()
    }

    /** Compares a downloaded file's actual [actualSizeBytes]/[actualSha1Hex] against what the
     *  repository index promised in [expected]. Case-insensitive on the hex digest. */
    fun verify(actualSizeBytes: Long, actualSha1Hex: String, expected: PlatformToolsArchive): VerificationResult {
        if (actualSizeBytes != expected.sizeBytes) {
            return VerificationResult.Mismatch(
                "size mismatch: expected ${expected.sizeBytes} bytes, got $actualSizeBytes",
            )
        }
        if (!actualSha1Hex.equals(expected.sha1, ignoreCase = true)) {
            return VerificationResult.Mismatch("SHA-1 mismatch")
        }
        return VerificationResult.Ok
    }
}
