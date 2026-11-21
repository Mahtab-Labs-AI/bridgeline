package com.wifiproxy.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class FormatTest {
    @Test
    fun `formatActivity says Active now within the first minute`() {
        assertEquals("Active now", formatActivity(nowMs = 100_000L, lastActivityMs = 100_000L))
        assertEquals("Active now", formatActivity(nowMs = 100_000L, lastActivityMs = 59_999L))
    }

    @Test
    fun `formatActivity reports whole idle minutes`() {
        assertEquals("Idle 1 min", formatActivity(nowMs = 160_000L, lastActivityMs = 100_000L))
        assertEquals("Idle 3 min", formatActivity(nowMs = 280_000L, lastActivityMs = 100_000L))
    }

    @Test
    fun `formatActivity never goes negative`() {
        assertEquals("Active now", formatActivity(nowMs = 100_000L, lastActivityMs = 200_000L))
    }

    @Test
    fun `formatActivity switches to whole hours past 60 minutes`() {
        val lastActivityMs = 0L
        assertEquals("Idle 59 min", formatActivity(nowMs = 59 * 60_000L, lastActivityMs = lastActivityMs))
        assertEquals("Idle 1 h", formatActivity(nowMs = 60 * 60_000L, lastActivityMs = lastActivityMs))
        assertEquals("Idle 2 h", formatActivity(nowMs = 125 * 60_000L, lastActivityMs = lastActivityMs))
    }
}
