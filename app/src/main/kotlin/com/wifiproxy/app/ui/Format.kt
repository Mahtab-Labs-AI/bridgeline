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
