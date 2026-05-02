package com.vela.app.ui.sessionlist

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.google.common.truth.Truth.assertThat
import com.vela.app.ui.sessiondetail.SessionStatus
import com.vela.app.ui.theme.VelaColors
import org.junit.Test

/**
 * RED → GREEN: verifies the pure color-mapping logic for session card
 * tonal backgrounds, status chip containers, and chip text colors.
 *
 * All functions under test are `internal` top-level functions in SessionCard.kt.
 * JVM-only — no Compose rendering needed.
 * androidx.compose.ui.graphics.Color is a Kotlin value class; no Android calls.
 */
class SessionCardColorTest {

    // ── cardBackgroundFor ──────────────────────────────────────────────────────
    // M3 tonal fills: color-mix(StatusContainer, SurfaceSub) per DESIGN.md §7.2
    // Hardcoded approximations — see SessionCard.kt for exact hex rationale.

    @Test fun `cardBackground for EXECUTING is amber-tinted surface`() {
        assertThat(cardBackgroundFor(SessionStatus.EXECUTING).toArgb())
            .isEqualTo(Color(0xFF1C1A0E).toArgb())
    }

    @Test fun `cardBackground for RESUMING is violet-tinted surface`() {
        assertThat(cardBackgroundFor(SessionStatus.RESUMING).toArgb())
            .isEqualTo(Color(0xFF1A1234).toArgb())
    }

    @Test fun `cardBackground for IDLE is default SurfaceSub`() {
        assertThat(cardBackgroundFor(SessionStatus.IDLE).toArgb())
            .isEqualTo(VelaColors.SurfaceSub.toArgb())
    }

    @Test fun `cardBackground for ERROR is coral-tinted surface`() {
        assertThat(cardBackgroundFor(SessionStatus.ERROR).toArgb())
            .isEqualTo(Color(0xFF1C1117).toArgb())
    }

    // ── chipContainerFor ───────────────────────────────────────────────────────

    @Test fun `chipContainer for EXECUTING is RunningContainer`() {
        assertThat(chipContainerFor(SessionStatus.EXECUTING).toArgb())
            .isEqualTo(VelaColors.RunningContainer.toArgb())
    }

    @Test fun `chipContainer for RESUMING is WaitingContainer`() {
        assertThat(chipContainerFor(SessionStatus.RESUMING).toArgb())
            .isEqualTo(VelaColors.WaitingContainer.toArgb())
    }

    @Test fun `chipContainer for IDLE is DoneContainer`() {
        assertThat(chipContainerFor(SessionStatus.IDLE).toArgb())
            .isEqualTo(VelaColors.DoneContainer.toArgb())
    }

    @Test fun `chipContainer for ERROR is ErrorContainer`() {
        assertThat(chipContainerFor(SessionStatus.ERROR).toArgb())
            .isEqualTo(VelaColors.ErrorContainer.toArgb())
    }

    // ── chipOnContainerFor ─────────────────────────────────────────────────────

    @Test fun `chipOnContainer for EXECUTING is RunningOnContainer`() {
        assertThat(chipOnContainerFor(SessionStatus.EXECUTING).toArgb())
            .isEqualTo(VelaColors.RunningOnContainer.toArgb())
    }

    @Test fun `chipOnContainer for RESUMING is WaitingOnContainer`() {
        assertThat(chipOnContainerFor(SessionStatus.RESUMING).toArgb())
            .isEqualTo(VelaColors.WaitingOnContainer.toArgb())
    }

    @Test fun `chipOnContainer for IDLE is DoneOnContainer`() {
        assertThat(chipOnContainerFor(SessionStatus.IDLE).toArgb())
            .isEqualTo(VelaColors.DoneOnContainer.toArgb())
    }

    @Test fun `chipOnContainer for ERROR is ErrorOnContainer`() {
        assertThat(chipOnContainerFor(SessionStatus.ERROR).toArgb())
            .isEqualTo(VelaColors.ErrorOnContainer.toArgb())
    }

    // ── Structural: verify composable exists ───────────────────────────────────

    @Test fun `SessionCard source contains SessionCard composable`() {
        val src = java.io.File(
            "src/main/kotlin/com/vela/app/ui/sessionlist/SessionCard.kt"
        ).readText()
        assertThat(src).contains("fun SessionCard")
    }
}
