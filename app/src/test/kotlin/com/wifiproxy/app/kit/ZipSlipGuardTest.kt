package com.wifiproxy.app.kit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ZipSlipGuardTest {

    @Test
    fun `accepts a normal relative entry`() {
        assertEquals(
            listOf("platform-tools", "adb.exe"),
            ZipSlipGuard.safeRelativeSegments("platform-tools/adb.exe"),
        )
    }

    @Test
    fun `accepts a normal entry using backslashes`() {
        assertEquals(
            listOf("platform-tools", "adb.exe"),
            ZipSlipGuard.safeRelativeSegments("platform-tools\\adb.exe"),
        )
    }

    @Test
    fun `rejects a parent-directory traversal`() {
        assertNull(ZipSlipGuard.safeRelativeSegments("../x"))
    }

    @Test
    fun `rejects a parent-directory traversal buried deeper in the path`() {
        assertNull(ZipSlipGuard.safeRelativeSegments("platform-tools/../../x"))
    }

    @Test
    fun `rejects an absolute Unix path`() {
        assertNull(ZipSlipGuard.safeRelativeSegments("/etc/passwd"))
    }

    @Test
    fun `rejects an absolute Windows path with a drive letter`() {
        assertNull(ZipSlipGuard.safeRelativeSegments("C:\\Windows\\System32\\evil.dll"))
    }

    @Test
    fun `rejects a blank entry name`() {
        assertNull(ZipSlipGuard.safeRelativeSegments(""))
    }

    @Test
    fun `rejects an entry that is only a directory separator`() {
        assertNull(ZipSlipGuard.safeRelativeSegments("/"))
    }

    @Test
    fun `collapses a redundant current-directory segment but still accepts the entry`() {
        assertEquals(listOf("a", "b"), ZipSlipGuard.safeRelativeSegments("./a/b"))
    }
}
