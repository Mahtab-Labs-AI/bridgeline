package com.wifiproxy.app.kit

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class KitVerificationTest {

    @Test
    fun `sha1Hex matches a known vector`() {
        // echo -n "abc" | sha1sum -> a9993e364706816aba3e25717850c26c9cd0d89d
        val hash = KitVerification.sha1Hex(ByteArrayInputStream("abc".toByteArray()))
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", hash)
    }

    @Test
    fun `sha1Hex of empty input is the empty-string hash`() {
        val hash = KitVerification.sha1Hex(ByteArrayInputStream(ByteArray(0)))
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", hash)
    }

    @Test
    fun `verify accepts matching size and sha1, case-insensitively`() {
        val expected = PlatformToolsArchive("x.zip", sizeBytes = 3L, sha1 = "A9993E364706816ABA3E25717850C26C9CD0D89")
        val result = KitVerification.verify(
            actualSizeBytes = 3L,
            actualSha1Hex = "a9993e364706816aba3e25717850c26c9cd0d89",
            expected = expected,
        )
        assertEquals(VerificationResult.Ok, result)
    }

    @Test
    fun `verify refuses on a size mismatch`() {
        val expected = PlatformToolsArchive("x.zip", sizeBytes = 100L, sha1 = "a9993e364706816aba3e25717850c26c9cd0d89")
        val result = KitVerification.verify(
            actualSizeBytes = 99L,
            actualSha1Hex = "a9993e364706816aba3e25717850c26c9cd0d89",
            expected = expected,
        )
        assertIs<VerificationResult.Mismatch>(result)
    }

    @Test
    fun `verify refuses on a sha1 mismatch even when the size matches`() {
        val expected = PlatformToolsArchive("x.zip", sizeBytes = 3L, sha1 = "a9993e364706816aba3e25717850c26c9cd0d89")
        val result = KitVerification.verify(
            actualSizeBytes = 3L,
            actualSha1Hex = "ffffffffffffffffffffffffffffffffffffffff",
            expected = expected,
        )
        assertIs<VerificationResult.Mismatch>(result)
    }
}
