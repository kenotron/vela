package com.vela.app.ui.theme

/**
 * Top-level color token aliases for use within the theme package.
 *
 * All values are sourced from [VelaColors]. These re-exports let Theme.kt
 * reference tokens by bare name (e.g. `Abyss`) without qualifying every
 * call with `VelaColors.`. External callers should use [VelaColors] directly.
 */

// ── Surfaces
internal val Abyss         = VelaColors.Abyss
internal val SurfaceSub    = VelaColors.SurfaceSub
internal val SurfaceRaised = VelaColors.SurfaceRaised
internal val SurfacePeak   = VelaColors.SurfacePeak
internal val CoordBg       = VelaColors.CoordBg
internal val CoordCard     = VelaColors.CoordCard

// ── Accent
internal val Accent      = VelaColors.Accent
internal val AccentCoord = VelaColors.AccentCoord

// ── Status: Running
internal val Running            = VelaColors.Running
internal val RunningOn          = VelaColors.RunningOn
internal val RunningContainer   = VelaColors.RunningContainer
internal val RunningOnContainer = VelaColors.RunningOnContainer

// ── Status: Waiting
internal val Waiting            = VelaColors.Waiting
internal val WaitingOn          = VelaColors.WaitingOn
internal val WaitingContainer   = VelaColors.WaitingContainer
internal val WaitingOnContainer = VelaColors.WaitingOnContainer

// ── Status: Done
internal val Done            = VelaColors.Done
internal val DoneOn          = VelaColors.DoneOn
internal val DoneContainer   = VelaColors.DoneContainer
internal val DoneOnContainer = VelaColors.DoneOnContainer

// ── Status: Error
internal val Error            = VelaColors.Error
internal val ErrorOn          = VelaColors.ErrorOn
internal val ErrorContainer   = VelaColors.ErrorContainer
internal val ErrorOnContainer = VelaColors.ErrorOnContainer

// ── Text
internal val TextPrimary   = VelaColors.TextPrimary
internal val TextSecondary = VelaColors.TextSecondary
internal val TextTertiary  = VelaColors.TextTertiary
internal val TextDisabled  = VelaColors.TextDisabled

// ── Strokes
internal val StrokeHair = VelaColors.StrokeHair
internal val StrokeEdge = VelaColors.StrokeEdge
