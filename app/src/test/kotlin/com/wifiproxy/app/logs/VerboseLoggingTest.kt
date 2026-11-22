package com.wifiproxy.app.logs

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val MIN = 60_000L

private class MemoryVerbosePrefs(var until: Long = 0L, var version: Int = 0) : VerbosePrefs {
    override var verboseUntilMs: Long
        get() = until
        set(value) {
            until = value
        }
    override var verboseVersionCode: Int
        get() = version
        set(value) {
            version = value
        }
}

class VerboseLoggingTest {
    @Test
    fun `enable lasts exactly 30 minutes`() {
        assertEquals(1_000L + 30 * MIN, VerboseLogging.enable(1_000L))
    }

    @Test
    fun `isActive is on until the deadline and off from it`() {
        val until = VerboseLogging.enable(0L)
        assertTrue(VerboseLogging.isActive(0L, until))
        assertTrue(VerboseLogging.isActive(until - 1, until))
        assertFalse(VerboseLogging.isActive(until, until))
        assertFalse(VerboseLogging.isActive(until + 1, until))
    }

    @Test
    fun `zero is always off`() {
        assertFalse(VerboseLogging.isActive(0L, 0L))
        assertFalse(VerboseLogging.isActive(123L, 0L))
    }

    @Test
    fun `a wall clock moved backwards never keeps it on longer than 30 minutes`() {
        val until = VerboseLogging.enable(10 * 60 * MIN)
        assertFalse(VerboseLogging.isActive(until - 31 * MIN, until))
        assertTrue(VerboseLogging.isActive(until - 30 * MIN, until))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class VerboseSwitchTest {
    private fun TestScope.switch(prefs: MemoryVerbosePrefs, version: Int = 7, clockOffset: Long = 0L) =
        VerboseSwitch(prefs, version, clock = { currentTime + clockOffset }, scope = backgroundScope)

    @Test
    fun `off by default`() = runTest {
        val prefs = MemoryVerbosePrefs()
        val s = switch(prefs)
        assertEquals(0L, s.untilMs.value)
        assertFalse(s.isActive())
    }

    @Test
    fun `enable persists and turns itself off once after 30 minutes`() = runTest {
        val prefs = MemoryVerbosePrefs()
        val s = switch(prefs)
        val ended = mutableListOf<Boolean>()
        s.onEnded = { ended += it }

        s.enable()
        assertEquals(30 * MIN, prefs.until)
        assertEquals(7, prefs.version)
        assertTrue(s.isActive())

        advanceTimeBy(30 * MIN - 1)
        runCurrent()
        assertTrue(s.isActive())
        assertTrue(ended.isEmpty())

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(true), ended)
        assertEquals(0L, s.untilMs.value)
        assertEquals(0L, prefs.until)
        assertFalse(s.isActive())

        // Stays off: nothing turns it back on.
        advanceTimeBy(120 * MIN)
        runCurrent()
        assertEquals(listOf(true), ended)
        assertFalse(s.isActive())
    }

    @Test
    fun `the per-event check notices a late timer and switches off exactly once`() = runTest {
        val prefs = MemoryVerbosePrefs()
        var offset = 0L
        val s = VerboseSwitch(prefs, 7, clock = { currentTime + offset }, scope = backgroundScope)
        val ended = mutableListOf<Boolean>()
        s.onEnded = { ended += it }
        s.enable()
        // The wall clock jumps past the deadline while the timer hasn't fired (the device slept).
        offset = 31 * MIN
        assertFalse(s.isActive())
        assertFalse(s.isActive())
        assertEquals(listOf(true), ended)
        assertEquals(0L, prefs.until)
        advanceTimeBy(60 * MIN)
        runCurrent()
        assertEquals(listOf(true), ended)
    }

    @Test
    fun `disable switches off early without the auto-off`() = runTest {
        val prefs = MemoryVerbosePrefs()
        val s = switch(prefs)
        val ended = mutableListOf<Boolean>()
        s.onEnded = { ended += it }
        s.enable()
        advanceTimeBy(5 * MIN)
        s.disable()
        assertEquals(listOf(false), ended)
        assertEquals(0L, prefs.until)
        assertFalse(s.isActive())
        advanceTimeBy(60 * MIN)
        runCurrent()
        assertEquals(listOf(false), ended)
        // A second disable is a no-op.
        s.disable()
        assertEquals(listOf(false), ended)
    }

    @Test
    fun `re-enabling restarts the 30 minutes`() = runTest {
        val prefs = MemoryVerbosePrefs()
        val s = switch(prefs)
        val ended = mutableListOf<Boolean>()
        s.onEnded = { ended += it }
        s.enable()
        advanceTimeBy(20 * MIN)
        s.enable()
        advanceTimeBy(20 * MIN)
        runCurrent()
        assertTrue(s.isActive())
        advanceTimeBy(10 * MIN)
        runCurrent()
        assertEquals(listOf(true), ended)
    }

    @Test
    fun `a stored future deadline from the same version is re-armed at process start`() = runTest {
        val prefs = MemoryVerbosePrefs(until = 10 * MIN, version = 7)
        val s = switch(prefs)
        val ended = mutableListOf<Boolean>()
        s.onEnded = { ended += it }
        assertEquals(10 * MIN, s.untilMs.value)
        assertTrue(s.isActive())
        advanceTimeBy(10 * MIN)
        runCurrent()
        assertEquals(listOf(true), ended)
        assertEquals(0L, prefs.until)
    }

    @Test
    fun `it is not restored as on after an app update`() = runTest {
        val prefs = MemoryVerbosePrefs(until = 10 * MIN, version = 6)
        val s = switch(prefs, version = 7)
        assertEquals(0L, s.untilMs.value)
        assertFalse(s.isActive())
        assertEquals(0L, prefs.until)
    }

    @Test
    fun `a stored deadline that already passed stays off`() = runTest {
        val prefs = MemoryVerbosePrefs(until = 10 * MIN, version = 7)
        val s = switch(prefs, clockOffset = 11 * MIN)
        assertEquals(0L, s.untilMs.value)
        assertEquals(0L, prefs.until)
    }

    @Test
    fun `a throwing onEnded never escapes`() = runTest {
        val s = switch(MemoryVerbosePrefs())
        s.onEnded = { throw IllegalStateException("boom") }
        s.enable()
        s.disable()
        s.enable()
        advanceTimeBy(30 * MIN)
        runCurrent()
        assertFalse(s.isActive())
    }
}
