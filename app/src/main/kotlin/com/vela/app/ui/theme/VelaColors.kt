package com.vela.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Vela design-system color tokens.
 *
 * All values are sourced verbatim from DESIGN.md §2.
 * Access via [VelaColors.Abyss], [VelaColors.Accent], etc.
 * Top-level package-level aliases for these tokens live in Color.kt,
 * allowing Theme.kt to reference them without qualifying every call.
 */
object VelaColors {

    // ── Surfaces
    /** App background — deep blue-indigo midnight. */
    val Abyss         = Color(0xFF0B0E1A)
    /** Resting card surface — one step lifted from Abyss. */
    val SurfaceSub    = Color(0xFF11152A)
    /** Elevated card / bottom sheet — where expanded states live. */
    val SurfaceRaised = Color(0xFF171C36)
    /** Top-of-stack overlay — lightest indigo in the system. */
    val SurfacePeak   = Color(0xFF1F2542)
    /** Coordinator session background — distinct cool teal-tinted indigo. */
    val CoordBg       = Color(0xFF0C1E26)
    /** Coordinator branch card background. */
    val CoordCard     = Color(0xFF13303C)

    // ── Accent
    /** Brand cyan-aqua accent — used sparingly; one element per screen max. */
    val Accent      = Color(0xFF5EEAD4)
    /** Coordinator-mode teal accent — distinguishes coordinator views. */
    val AccentCoord = Color(0xFF1FE0C2)

    // ── Status: Running (warm amber)
    val Running            = Color(0xFFF5A524)
    val RunningOn          = Color(0xFF1A1000)
    val RunningContainer   = Color(0xFF3A2400)
    val RunningOnContainer = Color(0xFFFFD89B)

    // ── Status: Waiting (electric violet)
    val Waiting            = Color(0xFFA78BFA)
    val WaitingOn          = Color(0xFF1A0F3A)
    val WaitingContainer   = Color(0xFF2E1A5C)
    val WaitingOnContainer = Color(0xFFDDD0FF)

    // ── Status: Done (quiet sage)
    val Done            = Color(0xFF7DCFA5)
    val DoneOn          = Color(0xFF0A2418)
    val DoneContainer   = Color(0xFF143A29)
    val DoneOnContainer = Color(0xFFB8E8CD)

    // ── Status: Error (coral)
    val Error            = Color(0xFFFF6B6B)
    val ErrorOn          = Color(0xFF2A0808)
    val ErrorContainer   = Color(0xFF4A1818)
    val ErrorOnContainer = Color(0xFFFFC4C4)

    // ── Text
    /** Near-white with a faint warm cast — never surgical against indigo. */
    val TextPrimary   = Color(0xFFF5F2EC)
    val TextSecondary = Color(0xFFB5B8C8)
    val TextTertiary  = Color(0xFF7A7E94)
    val TextDisabled  = Color(0xFF4A4D5E)

    // ── Strokes & Dividers
    /** Hairline separator — rgba(255,255,255,0.06). */
    val StrokeHair = Color(0x0FFFFFFF)
    /** Edge border — rgba(255,255,255,0.12). */
    val StrokeEdge = Color(0x1FFFFFFF)
}
