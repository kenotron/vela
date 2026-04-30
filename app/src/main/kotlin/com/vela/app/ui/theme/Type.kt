package com.vela.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.vela.app.R

// ── Font families

/**
 * Instrument Serif — used for display titles and headline-scale node/session names.
 * Gives the app gravitas that sans-serif cannot achieve at display scale on dark indigo.
 */
val InstrumentSerifFamily = FontFamily(
    Font(R.font.instrument_serif_regular, FontWeight.Normal),
)

/**
 * JetBrains Mono — used exclusively for tool I/O, code blocks, and machine identifiers.
 * Never in titles.
 */
val JetBrainsMonoFamily = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
)

// ── Typography scale

/**
 * Vela typography scale per DESIGN.md §3.
 *
 * - Display / Headline: Instrument Serif (gravitas for named entities)
 * - Body / UI / Labels: System sans-serif via [FontFamily.Default] (Inter / Google Sans on Pixel)
 * - Mono: [JetBrainsMonoFamily] (tool I/O — see [MonoMedium] below, not in M3 Typography)
 */
val VelaTypography = Typography(
    displayLarge = TextStyle(
        fontFamily   = InstrumentSerifFamily,
        fontSize     = 48.sp,
        fontWeight   = FontWeight.Normal,
        letterSpacing = (-1.5).sp,
    ),
    displayMedium = TextStyle(
        fontFamily   = InstrumentSerifFamily,
        fontSize     = 36.sp,
        fontWeight   = FontWeight.Normal,
        letterSpacing = (-1.0).sp,
    ),
    displaySmall = TextStyle(
        fontFamily   = InstrumentSerifFamily,
        fontSize     = 28.sp,
        fontWeight   = FontWeight.Normal,
        letterSpacing = (-0.5).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily   = InstrumentSerifFamily,
        fontSize     = 28.sp,
        fontWeight   = FontWeight.Normal,
    ),
    headlineMedium = TextStyle(
        fontFamily  = FontFamily.Default,
        fontSize    = 22.sp,
        fontWeight  = FontWeight.SemiBold,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize   = 18.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize   = 15.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    bodyLarge = TextStyle(
        fontFamily  = FontFamily.Default,
        fontSize    = 16.sp,
        fontWeight  = FontWeight.Normal,
        lineHeight  = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize   = 14.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontFamily    = FontFamily.Default,
        fontSize      = 14.sp,
        fontWeight    = FontWeight.SemiBold,
        letterSpacing = 0.5.sp,
    ),
    labelMedium = TextStyle(
        fontFamily    = FontFamily.Default,
        fontSize      = 12.sp,
        fontWeight    = FontWeight.Medium,
        letterSpacing = 1.0.sp,
    ),
    labelSmall = TextStyle(
        fontFamily    = FontFamily.Default,
        fontSize      = 11.sp,
        fontWeight    = FontWeight.Bold,
        letterSpacing = 2.0.sp,
    ),
)

/**
 * Monospace style for tool I/O, code blocks, and machine identifiers.
 *
 * Not part of M3 [Typography] — use directly as a [TextStyle]:
 * ```kotlin
 * Text("ls -la", style = MonoMedium)
 * ```
 */
val MonoMedium = TextStyle(
    fontFamily = JetBrainsMonoFamily,
    fontSize   = 13.sp,
    fontWeight = FontWeight.Normal,
    lineHeight = 20.sp,
)
