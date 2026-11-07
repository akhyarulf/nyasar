package com.nyasar.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.provider.Settings

// Outdoor-oriented palette based on #5A7562 (forest green) as primary.
// High-contrast for direct sunlight readability, consistent accent colors.
val NyasarGreen = Color(0xFF5A7562) // Primary - based on #5A7562
val NyasarGreenLight = Color(0xFF7A9582) // Lighter variant for surfaces
val NyasarGreenDark = Color(0xFF3A5542) // Darker variant for dark mode
val PlannedRouteBlue = Color(0xFF42A5F5) // Planned route line color on map
val ForestGreen = Color(0xFF2E5339) // Secondary/tertiary
val WarningAmber = Color(0xFFF2A900) // Warning/pause states
val OffRouteRed = Color(0xFFD64545) // Error/danger
val SuccessGreen = Color(0xFF5A7562) // Actual track/recorded line — matches primary green
// Route trace drawn ON a map. Deliberately not `primary`: the sage accent is
// tuned for text and buttons on our own dark canvas, and on the pale green /
// cream of the map basemap it disappears — a 3dp sage line on green terrain
// reads as a faint smudge, so the one element that carries the whole map card
// was the least legible thing on it. Same hue family, much more saturation.
val NyasarTrackGreen = Color(0xFF2E8B57)
val AccentBlue = Color(0xFF2979FF) // GPS/location markers

// Aksen "mulai rekam" — satu-satunya warna hangat di layar rekam.
// Tombol hero dulu memakai `primary` (sage), yang di atas kartu gelap terbaca
// seperti tombol nonaktif: pengguna harus membaca ikonnya untuk tahu ini aksi
// utama. Oranye menyala memberi sinyal "tekan saya" dari jarak pandang, sama
// seperti tombol rekam di aplikasi referensi. Sengaja TIDAK dipakai di tempat
// lain (navigasi, banner, FAB) supaya maknanya tetap tunggal: mulai/lanjutkan
// sesi rekam. #FF6B2C dipilih agar berbeda jelas dari WarningAmber (#F2A900)
// yang di app ini berarti peringatan, bukan ajakan.
val RecordAccent = Color(0xFFFF6B2C)
val OnRecordAccent = Color(0xFF1C1006)

private val LightColors = lightColorScheme(
    primary = NyasarGreen,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8E9DE),
    onPrimaryContainer = Color(0xFF1A3522),
    secondary = ForestGreen,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD8E9DE),
    onSecondaryContainer = Color(0xFF1A3522),
    tertiary = NyasarGreen,
    onTertiary = Color.White,
    error = OffRouteRed,
    background = Color(0xFFFBFDF9),
    onBackground = Color(0xFF1A1C19),
    surface = Color(0xFFFBFDF9),
    onSurface = Color(0xFF1A1C19),
    surfaceVariant = Color(0xFFDDE5DA),
    onSurfaceVariant = Color(0xFF414941),
    outline = Color(0xFF6F7971),
    outlineVariant = Color(0xFFC2CCC4),
    surfaceContainerLow = Color(0xFFF5F8F2),
    surfaceContainer = Color(0xFFEFF4EC),
    surfaceContainerHigh = Color(0xFFE9EFE6),
    surfaceContainerHighest = Color(0xFFE3EAE0),
    surfaceTint = NyasarGreen,
    inverseSurface = Color(0xFF2F3130),
    inverseOnSurface = Color(0xFFEFF1EE),
    inversePrimary = NyasarGreenLight,
    scrim = Color(0xFF000000)
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
    tertiary = NyasarGreen,
    onTertiary = Color.White,
    error = Color(0xFFE57373),
    errorContainer = Color(0xFF5C2B2B),
    onError = Color(0xFF3B1111),
    onErrorContainer = Color(0xFFF8D7D7),
    // 2026-11 dark rework: near-black, hue-neutral surfaces with the green
    // spent only on accents. The old #1A1C19/#232623 pair sat in a mid grey
    // that read "muted app theme" rather than a deliberate outdoor-brand
    // canvas — everything (cards, headers, chips) landed within a few
    // luminance steps of each other, which is exactly what makes a UI look
    // assembled instead of designed. Green now has nothing competing with it.
    background = Color(0xFF0F110F),
    onBackground = Color(0xFFE4E6E1),
    surface = Color(0xFF0F110F),
    onSurface = Color(0xFFE4E6E1),
    surfaceVariant = Color(0xFF3A403A),
    onSurfaceVariant = Color(0xFFBFC7BD),
    outline = Color(0xFF7E887F),
    outlineVariant = Color(0xFF2C312C),
    surfaceContainerLow = Color(0xFF141614),
    surfaceContainer = Color(0xFF191C19),
    surfaceContainerHigh = Color(0xFF212421),
    surfaceContainerHighest = Color(0xFF282C28),
    surfaceTint = NyasarGreenLight,
    inverseSurface = Color(0xFFE2E3DE),
    inverseOnSurface = Color(0xFF1A1C19),
    inversePrimary = NyasarGreen,
    scrim = Color(0xFF000000)
)

/**
 * Custom shapes for Nyasar — larger radii for bottom sheets and cards,
 * smaller for chips/tags. CircleShape is left untouched wherever it's
 * already used (play button, recenter, compass, zoom).
 */
val NyasarShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
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
    val context = LocalContext.current
    val reducedMotion = remember(context) {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) == 0f
    }
    CompositionLocalProvider(LocalNyasarMotionEnabled provides !reducedMotion) {
        MaterialTheme(
            colorScheme = colors,
            typography = NyasarTypography,
            shapes = NyasarShapes,
            content = content
        )
    }
}
