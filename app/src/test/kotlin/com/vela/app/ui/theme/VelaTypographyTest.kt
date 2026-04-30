package com.vela.app.ui.theme

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * RED → GREEN: verifies DESIGN.md §3 typography scale values are correctly
 * encoded in [VelaTypography] and the font-family constants.
 *
 * These are pure JVM tests.
 * - [FontFamily] is a Kotlin sealed class hierarchy (data classes) — no Android calls.
 * - [TextUnit] is a Kotlin inline value class — comparison is purely numeric.
 * - R.font.* constants are compile-time AAPT ints — available in JVM tests.
 */
class VelaTypographyTest {

    // ── displayLarge
    @Test fun `displayLarge uses InstrumentSerifFamily`() {
        assertThat(VelaTypography.displayLarge.fontFamily).isEqualTo(InstrumentSerifFamily)
    }
    @Test fun `displayLarge has 48sp font size`() {
        assertThat(VelaTypography.displayLarge.fontSize).isEqualTo(48.sp)
    }
    @Test fun `displayLarge has negative 1point5 letter spacing`() {
        assertThat(VelaTypography.displayLarge.letterSpacing).isEqualTo((-1.5).sp)
    }

    // ── displayMedium
    @Test fun `displayMedium uses InstrumentSerifFamily`() {
        assertThat(VelaTypography.displayMedium.fontFamily).isEqualTo(InstrumentSerifFamily)
    }
    @Test fun `displayMedium has 36sp font size`() {
        assertThat(VelaTypography.displayMedium.fontSize).isEqualTo(36.sp)
    }

    // ── displaySmall
    @Test fun `displaySmall uses InstrumentSerifFamily`() {
        assertThat(VelaTypography.displaySmall.fontFamily).isEqualTo(InstrumentSerifFamily)
    }

    // ── headlineLarge
    @Test fun `headlineLarge uses InstrumentSerifFamily`() {
        assertThat(VelaTypography.headlineLarge.fontFamily).isEqualTo(InstrumentSerifFamily)
    }
    @Test fun `headlineLarge has 28sp font size`() {
        assertThat(VelaTypography.headlineLarge.fontSize).isEqualTo(28.sp)
    }

    // ── headlineMedium
    @Test fun `headlineMedium uses system FontFamily Default`() {
        assertThat(VelaTypography.headlineMedium.fontFamily).isEqualTo(FontFamily.Default)
    }
    @Test fun `headlineMedium has SemiBold weight`() {
        assertThat(VelaTypography.headlineMedium.fontWeight).isEqualTo(FontWeight.SemiBold)
    }

    // ── bodyLarge
    @Test fun `bodyLarge has 16sp font size`() {
        assertThat(VelaTypography.bodyLarge.fontSize).isEqualTo(16.sp)
    }
    @Test fun `bodyLarge has 22sp line height`() {
        assertThat(VelaTypography.bodyLarge.lineHeight).isEqualTo(22.sp)
    }

    // ── bodyMedium
    @Test fun `bodyMedium has 14sp font size`() {
        assertThat(VelaTypography.bodyMedium.fontSize).isEqualTo(14.sp)
    }
    @Test fun `bodyMedium has 20sp line height`() {
        assertThat(VelaTypography.bodyMedium.lineHeight).isEqualTo(20.sp)
    }

    // ── labelLarge
    @Test fun `labelLarge has SemiBold weight`() {
        assertThat(VelaTypography.labelLarge.fontWeight).isEqualTo(FontWeight.SemiBold)
    }
    @Test fun `labelLarge has point5 letter spacing`() {
        assertThat(VelaTypography.labelLarge.letterSpacing).isEqualTo(0.5.sp)
    }

    // ── labelMedium
    @Test fun `labelMedium has 12sp font size`() {
        assertThat(VelaTypography.labelMedium.fontSize).isEqualTo(12.sp)
    }
    @Test fun `labelMedium has Medium weight`() {
        assertThat(VelaTypography.labelMedium.fontWeight).isEqualTo(FontWeight.Medium)
    }
    @Test fun `labelMedium has 1point0 letter spacing`() {
        assertThat(VelaTypography.labelMedium.letterSpacing).isEqualTo(1.0.sp)
    }

    // ── labelSmall
    @Test fun `labelSmall has 11sp font size`() {
        assertThat(VelaTypography.labelSmall.fontSize).isEqualTo(11.sp)
    }
    @Test fun `labelSmall has Bold weight`() {
        assertThat(VelaTypography.labelSmall.fontWeight).isEqualTo(FontWeight.Bold)
    }
    @Test fun `labelSmall has 2point0 letter spacing`() {
        assertThat(VelaTypography.labelSmall.letterSpacing).isEqualTo(2.0.sp)
    }

    // ── MonoMedium (non-M3, used directly)
    @Test fun `MonoMedium uses JetBrainsMonoFamily`() {
        assertThat(MonoMedium.fontFamily).isEqualTo(JetBrainsMonoFamily)
    }
    @Test fun `MonoMedium has 13sp font size`() {
        assertThat(MonoMedium.fontSize).isEqualTo(13.sp)
    }
    @Test fun `MonoMedium has 20sp line height`() {
        assertThat(MonoMedium.lineHeight).isEqualTo(20.sp)
    }
}
