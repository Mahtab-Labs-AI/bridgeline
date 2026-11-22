package com.wifiproxy.app.logs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay

/**
 * Whether verbose logging is on, for the UI: [VerboseLogging.isActive] against the wall clock,
 * not just `untilMs != 0`, so Settings and Diagnostics flip to off at the deadline even if the
 * switch's own timer is late (the device slept) and hasn't cleared [untilMs] yet.
 */
@Composable
fun rememberVerboseActive(untilMs: Long): Boolean {
    val active by produceState(VerboseLogging.isActive(System.currentTimeMillis(), untilMs), untilMs) {
        while (true) {
            val now = System.currentTimeMillis()
            value = VerboseLogging.isActive(now, untilMs)
            if (!value) break
            // Wake at the deadline, but at least once a minute in case the wall clock moves.
            delay((untilMs - now).coerceIn(1L, 60_000L))
        }
    }
    return active
}
