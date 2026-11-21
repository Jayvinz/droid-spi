package com.example.droidreel.base.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val ReelBackground = Color(0xFF0B0B0F)
val ReelSurface = Color(0xFF15151C)
val ReelSurfaceHigh = Color(0xFF1E1E27)
val ReelPrimary = Color(0xFFFF4D67)
val ReelSecondary = Color(0xFF38D6C4)
val ReelText = Color(0xFFF5F5F7)
val ReelMuted = Color(0xFF9A9AA6)
val ReelDivider = Color(0xFF2A2A34)

private val Colors = darkColorScheme(
    primary = ReelPrimary,
    onPrimary = Color.White,
    secondary = ReelSecondary,
    onSecondary = Color.Black,
    background = ReelBackground,
    onBackground = ReelText,
    surface = ReelSurface,
    onSurface = ReelText,
    surfaceVariant = ReelSurfaceHigh,
    onSurfaceVariant = ReelMuted,
    outline = ReelDivider,
    error = Color(0xFFFF6B6B),
)

@Composable
fun DroidReelTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Colors,
        typography = Typography(),
        content = content,
    )
}
