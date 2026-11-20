package com.wifiproxy.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val SeedBlue = Color(0xFF1C3A5E)
private val SeedBlueLight = Color(0xFF5B7FA6)

private val LightColors = lightColorScheme(
    primary = SeedBlue,
    secondary = SeedBlueLight,
)

private val DarkColors = darkColorScheme(
    primary = SeedBlueLight,
    secondary = SeedBlue,
)

/** Phase colours used across screens (status card, chips, slot badges). */
object PhaseColors {
    val Idle = Color(0xFF6B7280)
    val Starting = Color(0xFFB8860B)
    val Running = Color(0xFF1E8E3E)
    val Degraded = Color(0xFFD9730D)
    val Stopping = Color(0xFF6B7280)
}

@Composable
fun WifiProxyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = MaterialTheme.typography,
        content = content,
    )
}
