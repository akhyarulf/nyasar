package com.nyasar.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.nyasar.app.R

/**
 * Inter font family for Nyasar — imported from Inter v4.1 static TTF files.
 * Weights used: Regular (400), Medium (500), SemiBold (600), Bold (700).
 *
 * Typography rationale:
 * - display/headline styles use Bold weight so big stat numbers
 *   (distance, time, elevation) have strong visual weight and contrast
 *   against the lighter label text below them.
 * - Title styles use SemiBold for slightly more presence than the default
 *   Medium weight, without going full Bold.
 * - Body/label styles stay at Regular/Medium for readability.
 *
 * Scale discipline (2026-11): the old scale was Material's defaults plus
 * positive letter-spacing on every body/label style, which made a dense feed
 * read as "everything is big and everything is equally loud". Two fixes:
 *
 * 1. Sizes step down faster. Title tier tops out at 20sp and the headline used
 *    for stat numbers at 21sp, so a route name, a stat value and a byline can
 *    sit in one card without the card feeling like a poster. Big type is now
 *    reserved for the places that earn it: the live recording timer and
 *    display-size numbers in activity summaries.
 * 2. Tracking goes negative on titles and ~zero on body. Inter is drawn for
 *    UI text; adding +0.25 / +0.5sp on top of it (Material's old default for
 *    Roboto) is what gave small labels that loose, slightly off look. Titles
 *    get a little negative tracking so long route names hold together, small
 *    caps-like labels get a touch of positive tracking only where they are
 *    genuinely uppercase.
 *
 * Hierarchy should come from colour and weight (quiet onSurfaceVariant labels
 * over bold values), not from making every element larger.
 */
val InterFontFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

/**
 * Feed stat number. Material's name-based scale has no slot between
 * labelLarge (14sp) and titleMedium (16sp) that reads as a number, and the
 * headline sizes are far too large for a row of three values inside a card —
 * at 21sp "10.17 km" filled most of its third of the row. 17sp bold is the
 * reference app's proportion: bigger and heavier than the caption above it,
 * small enough that three values sit comfortably on one line.
 */
val NyasarStatValue = TextStyle(
    fontFamily = InterFontFamily,
    fontWeight = FontWeight.Bold,
    fontSize = 17.sp,
    lineHeight = 22.sp,
    letterSpacing = (-0.2).sp
)

val NyasarTypography = Typography(
    // --- Display / Headline: stat numbers ---
    displayLarge = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 48.sp,
        lineHeight = 54.sp,
        letterSpacing = (-0.5).sp
    ),
    displayMedium = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 38.sp,
        lineHeight = 44.sp,
        letterSpacing = (-0.4).sp
    ),
    displaySmall = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.3).sp
    ),
    // --- Headline: section headers, live values ---
    headlineLarge = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 27.sp,
        lineHeight = 33.sp,
        letterSpacing = (-0.3).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 23.sp,
        lineHeight = 29.sp,
        letterSpacing = (-0.25).sp
    ),
    headlineSmall = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.2).sp
    ),
    // --- Title: card/section titles ---
    titleLarge = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.2).sp
    ),
    titleMedium = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = (-0.1).sp
    ),
    titleSmall = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp
    ),
    // --- Body: readable text ---
    bodyLarge = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 23.sp,
        letterSpacing = 0.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp
    ),
    bodySmall = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.05.sp
    ),
    // --- Label: small UI labels / chips ---
    labelLarge = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.05.sp
    ),
    labelMedium = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp
    ),
    labelSmall = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.3.sp
    )
)
