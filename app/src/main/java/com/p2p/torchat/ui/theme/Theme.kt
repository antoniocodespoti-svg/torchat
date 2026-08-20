package com.p2p.torchat.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Techno-Neon Palette
val NeonCyan = Color(0xFF00F3FF)
val NeonMagenta = Color(0xFFFF00FF)
val NeonGreen = Color(0xFF39FF14)
val DeepBlack = Color(0xFF000000)
val DarkSurface = Color(0xFF0F172A)
val CyberGray = Color(0xFF1E293B)

val CyanPrimary = NeonCyan
val CyanSecondary = NeonMagenta
val RedAccent = Color(0xFFFF0055)
val GreenAccent = NeonGreen

private val DarkColorScheme =
    darkColorScheme(
        primary = NeonCyan,
        secondary = NeonMagenta,
        tertiary = NeonGreen,
        background = DeepBlack,
        surface = DarkSurface,
        onPrimary = Color.Black,
        onSecondary = Color.White,
        onBackground = Color.White,
        onSurface = NeonCyan,
        surfaceVariant = CyberGray,
        onSurfaceVariant = Color.White,
    )

private val LightColorScheme =
    lightColorScheme(
        primary = Color(0xFF0891B2),
        secondary = Color(0xFFC026D3),
        tertiary = Color(0xFF16A34A),
        // Slate 100 for better contrast
        background = Color(0xFFF1F5F9),
        surface = Color.White,
        onPrimary = Color.White,
        onSecondary = Color.White,
        // Dark slate text
        onBackground = Color(0xFF0F172A),
        onSurface = Color(0xFF0F172A),
        // Slate 200 for cards
        surfaceVariant = Color(0xFFE2E8F0),
        onSurfaceVariant = Color(0xFF334155),
    )

@Composable
fun TorP2PChatTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
