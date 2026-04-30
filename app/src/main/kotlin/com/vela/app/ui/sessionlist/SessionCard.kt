package com.vela.app.ui.sessionlist

import androidx.compose.ui.graphics.Color
import com.vela.app.ui.sessiondetail.SessionStatus
import com.vela.app.ui.theme.VelaColors

// ── Pure color-mapping logic ──────────────────────────────────────────────────
// All functions are `internal` so the test source set can access them.

/**
 * M3 tonal fill for the session card surface.
 *
 * Each value is a hardcoded approximation of:
 *   color-mix(StatusContainer × weight%, SurfaceSub)
 * per DESIGN.md §7.2. Computed in sRGB; exact hex values are:
 *   RUNNING  ≈ color-mix(RunningContainer 42%, SurfaceSub)
 *   WAITING  ≈ color-mix(WaitingContainer 48%, SurfaceSub)
 *   DONE     = SurfaceSub (no tint)
 *   ERROR    ≈ color-mix(ErrorContainer 52%, SurfaceSub)
 */
internal fun cardBackgroundFor(status: SessionStatus): Color = when (status) {
    SessionStatus.RUNNING -> Color(0xFF1C1A0E)
    SessionStatus.WAITING -> Color(0xFF1A1234)
    SessionStatus.DONE    -> VelaColors.SurfaceSub
    SessionStatus.ERROR   -> Color(0xFF1C1117)
}

/** Status chip background — status container color per DESIGN.md §7.2. */
internal fun chipContainerFor(status: SessionStatus): Color = when (status) {
    SessionStatus.RUNNING -> VelaColors.RunningContainer
    SessionStatus.WAITING -> VelaColors.WaitingContainer
    SessionStatus.DONE    -> VelaColors.DoneContainer
    SessionStatus.ERROR   -> VelaColors.ErrorContainer
}

/** Status chip text — on-container color per DESIGN.md §7.2. */
internal fun chipOnContainerFor(status: SessionStatus): Color = when (status) {
    SessionStatus.RUNNING -> VelaColors.RunningOnContainer
    SessionStatus.WAITING -> VelaColors.WaitingOnContainer
    SessionStatus.DONE    -> VelaColors.DoneOnContainer
    SessionStatus.ERROR   -> VelaColors.ErrorOnContainer
}
