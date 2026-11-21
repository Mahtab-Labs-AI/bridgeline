package com.wifiproxy.app.ui

import kotlin.math.ln
import kotlin.math.pow

/** Human-readable byte count, e.g. 940 -> "940 B", 2_400_000 -> "2.3 MB". */
fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    val exponent = (ln(bytes.toDouble()) / ln(1024.0)).toInt().coerceIn(1, units.size)
    val value = bytes / 1024.0.pow(exponent)
    return "%.1f %s".format(value, units[exponent - 1])
}

/** An auto device's activity under open admission: "Active now" for the first minute since
 *  [lastActivityMs], then "Idle N min" (floored, never negative even if the clocks disagree),
 *  switching to whole hours ("Idle N h") past 60 minutes. */
fun formatActivity(nowMs: Long, lastActivityMs: Long): String {
    val idleMin = ((nowMs - lastActivityMs) / 60_000L).coerceAtLeast(0L)
    return when {
        idleMin < 1 -> "Active now"
        idleMin < 60 -> "Idle $idleMin min"
        else -> "Idle ${idleMin / 60} h"
    }
}
