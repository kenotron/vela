package com.vela.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * RED → GREEN: verifies every DESIGN.md color token is present in VelaColors
 * with the exact hex value specified in the design document.
 *
 * These are pure JVM tests — no device or emulator needed.
 * androidx.compose.ui.graphics.Color is a Kotlin value class backed by a Long;
 * its constructor and toArgb() perform no Android framework calls.
 */
class VelaColorsTest {

    // ── Surfaces ───────────────────────────────────────────────────────────

    @Test fun `Abyss has correct hex value`() {
        assertThat(VelaColors.Abyss.toArgb()).isEqualTo(Color(0xFF0B0E1A).toArgb())
    }

    @Test fun `SurfaceSub has correct hex value`() {
        assertThat(VelaColors.SurfaceSub.toArgb()).isEqualTo(Color(0xFF11152A).toArgb())
    }

    @Test fun `SurfaceRaised has correct hex value`() {
        assertThat(VelaColors.SurfaceRaised.toArgb()).isEqualTo(Color(0xFF171C36).toArgb())
    }

    @Test fun `SurfacePeak has correct hex value`() {
        assertThat(VelaColors.SurfacePeak.toArgb()).isEqualTo(Color(0xFF1F2542).toArgb())
    }

    @Test fun `CoordBg has correct hex value`() {
        assertThat(VelaColors.CoordBg.toArgb()).isEqualTo(Color(0xFF0C1E26).toArgb())
    }

    @Test fun `CoordCard has correct hex value`() {
        assertThat(VelaColors.CoordCard.toArgb()).isEqualTo(Color(0xFF13303C).toArgb())
    }

    // ── Accent ─────────────────────────────────────────────────────────────

    @Test fun `Accent has correct hex value`() {
        assertThat(VelaColors.Accent.toArgb()).isEqualTo(Color(0xFF5EEAD4).toArgb())
    }

    @Test fun `AccentCoord has correct hex value`() {
        assertThat(VelaColors.AccentCoord.toArgb()).isEqualTo(Color(0xFF1FE0C2).toArgb())
    }

    // ── Status: Running ────────────────────────────────────────────────────

    @Test fun `Running has correct hex value`() {
        assertThat(VelaColors.Running.toArgb()).isEqualTo(Color(0xFFF5A524).toArgb())
    }

    @Test fun `RunningContainer has correct hex value`() {
        assertThat(VelaColors.RunningContainer.toArgb()).isEqualTo(Color(0xFF3A2400).toArgb())
    }

    @Test fun `RunningOnContainer has correct hex value`() {
        assertThat(VelaColors.RunningOnContainer.toArgb()).isEqualTo(Color(0xFFFFD89B).toArgb())
    }

    // ── Status: Waiting ────────────────────────────────────────────────────

    @Test fun `Waiting has correct hex value`() {
        assertThat(VelaColors.Waiting.toArgb()).isEqualTo(Color(0xFFA78BFA).toArgb())
    }

    @Test fun `WaitingContainer has correct hex value`() {
        assertThat(VelaColors.WaitingContainer.toArgb()).isEqualTo(Color(0xFF2E1A5C).toArgb())
    }

    // ── Status: Done ───────────────────────────────────────────────────────

    @Test fun `Done has correct hex value`() {
        assertThat(VelaColors.Done.toArgb()).isEqualTo(Color(0xFF7DCFA5).toArgb())
    }

    @Test fun `DoneContainer has correct hex value`() {
        assertThat(VelaColors.DoneContainer.toArgb()).isEqualTo(Color(0xFF143A29).toArgb())
    }

    @Test fun `DoneOnContainer has correct hex value`() {
        assertThat(VelaColors.DoneOnContainer.toArgb()).isEqualTo(Color(0xFFB8E8CD).toArgb())
    }

    // ── Status: Error ──────────────────────────────────────────────────────

    @Test fun `Error has correct hex value`() {
        assertThat(VelaColors.Error.toArgb()).isEqualTo(Color(0xFFFF6B6B).toArgb())
    }

    @Test fun `ErrorContainer has correct hex value`() {
        assertThat(VelaColors.ErrorContainer.toArgb()).isEqualTo(Color(0xFF4A1818).toArgb())
    }

    @Test fun `ErrorOnContainer has correct hex value`() {
        assertThat(VelaColors.ErrorOnContainer.toArgb()).isEqualTo(Color(0xFFFFC4C4).toArgb())
    }

    // ── Text ───────────────────────────────────────────────────────────────

    @Test fun `TextPrimary has correct hex value`() {
        assertThat(VelaColors.TextPrimary.toArgb()).isEqualTo(Color(0xFFF5F2EC).toArgb())
    }

    @Test fun `TextSecondary has correct hex value`() {
        assertThat(VelaColors.TextSecondary.toArgb()).isEqualTo(Color(0xFFB5B8C8).toArgb())
    }

    @Test fun `TextTertiary has correct hex value`() {
        assertThat(VelaColors.TextTertiary.toArgb()).isEqualTo(Color(0xFF7A7E94).toArgb())
    }

    @Test fun `TextDisabled has correct hex value`() {
        assertThat(VelaColors.TextDisabled.toArgb()).isEqualTo(Color(0xFF4A4D5E).toArgb())
    }

    // ── Strokes ────────────────────────────────────────────────────────────

    @Test fun `StrokeHair has correct hex value`() {
        assertThat(VelaColors.StrokeHair.toArgb()).isEqualTo(Color(0x0FFFFFFF).toArgb())
    }

    @Test fun `StrokeEdge has correct hex value`() {
        assertThat(VelaColors.StrokeEdge.toArgb()).isEqualTo(Color(0x1FFFFFFF).toArgb())
    }
}
