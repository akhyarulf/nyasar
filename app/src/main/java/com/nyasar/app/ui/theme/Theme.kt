package com.nyasar.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Outdoor-oriented palette based on #5A7562 (forest green) as primary.
// High-contrast for direct sunlight readability, consistent accent colors.
val NyasarGreen = Color(0xFF5A7562) // Primary - based on #5A7562
val NyasarGreenLight = Color(0xFF7A9582) // Lighter variant for surfaces
val NyasarGreenDark = Color(0xFF3A5542) // Darker variant for dark mode
val TrailOrange = Color(0xFFFF6B00) // Track/route line color
val ForestGreen = Color(0xFF2E5339) // Secondary/tertiary
val WarningAmber = Color(0xFFF2A900) // Warning/pause states
val OffRouteRed = Color(0xFFD64545) // Error/danger
val SuccessGreen = Color(0xFF00C853) // Actual track/recorded line
val AccentBlue = Color(0xFF2979FF) // GPS/location markers

private val LightColors = lightColorScheme(
    primary = NyasarGreen,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8E9DE),
    onPrimaryContainer = Color(0xFF1A3522),
    secondary = ForestGreen,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD8E9DE),
    onSecondaryContainer = Color(0xFF1A3522),
    tertiary = TrailOrange,
    onTertiary = Color.White,
    error = OffRouteRed,
    background = Color(0xFFFBFDF9),
    onBackground = Color(0xFF1A1C19),
    surface = Color(0xFFFBFDF9),
    onSurface = Color(0xFF1A1C19),
    surfaceVariant = Color(0xFFDDE5DA),
    onSurfaceVariant = Color(0xFF414941)
)

private val DarkColors = darkColorScheme(
    primary = NyasarGreenLight,
    onPrimary = Color(0xFF1A3522),
    primaryContainer = Color(0xFF3A5542),
    onPrimaryContainer = Color(0xFFD8E9DE),
    secondary = Color(0xFFB0C9B8),
    onSecondary = Color(0xFF1A3522),
    secondaryContainer = Color(0xFF3A5542),
    onSecondaryContainer = Color(0xFFD8E9DE),
    tertiary = TrailOrange,
    onTertiary = Color.White,
    error = OffRouteRed,
    background = Color(0xFF1A1C19),
    onBackground = Color(0xFFE2E3DE),
    surface = Color(0xFF1A1C19),
    onSurface = Color(0xFFE2E3DE),
    surfaceVariant = Color(0xFF414941),
    onSurfaceVariant = Color(0xFFC1C9BF)
)

@Composable
fun NyasarTheme(
    /** "system" | "light" | "dark" (spec Settings > Appearance). Falls back
     *  to system when null/unrecognized so this is safe to call before
     *  settings have loaded. */
    themeMode: String? = null,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    val colors = if (darkTheme) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, content = content)
}
