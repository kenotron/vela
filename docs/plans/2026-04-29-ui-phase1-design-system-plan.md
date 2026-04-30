# UI Phase 1: Design System & Navigation Shell Implementation Plan

> **Execution:** Use the subagent-driven-development workflow to implement this plan.

**Goal:** Replace Vela's placeholder green theme, single-override typography, and drawer-based navigation with the complete DESIGN.md token system and a NavHost navigation shell — the foundation every subsequent UI phase depends on.

**Architecture:** Three new/replaced theme files (`VelaColors.kt`, `Type.kt`, `Theme.kt`) define a dark-only Material 3 color scheme and full typographic scale using bundled Instrument Serif, JetBrains Mono, and system Inter fonts. A new `AppNavigation.kt` in `com.vela.app.ui.navigation` replaces the `ModalNavigationDrawer` / state-var pattern with a proper `NavHost` and placeholder screens for all seven routes. `MainActivity` is simplified to a single `VelaTheme { VelaApp() }` call. Dynamic color is permanently disabled so the DESIGN.md palette actually renders.

**Tech Stack:** Kotlin, Jetpack Compose (BOM 2025.04.01), Material 3, Navigation Compose 2.7.7, JUnit 4, Google Truth 1.4.2

---

## Codebase Orientation

Before starting, know what already exists:

| File | Current State | Phase 1 Action |
|------|--------------|----------------|
| `app/src/main/kotlin/com/vela/app/ui/theme/Color.kt` | 3 dead green tokens (`VelaPrimary`, `VelaSecondary`, `VelaTertiary`) | **Replace** with thin re-exports from `VelaColors` |
| `app/src/main/kotlin/com/vela/app/ui/theme/Type.kt` | One `bodyLarge` override, no font families | **Replace** with full 12-style scale |
| `app/src/main/kotlin/com/vela/app/ui/theme/Theme.kt` | Light+dark schemes, dynamic color enabled | **Replace** with dark-only, dynamic=false |
| `app/src/main/kotlin/com/vela/app/MainActivity.kt` | Calls `NavigationScaffold(windowSizeClass, speechTranscriber)` inside `ApiKeyDialog` logic | **Replace** `setContent` block with `VelaTheme { VelaApp() }` |
| `app/src/main/kotlin/com/vela/app/ui/NavigationScaffold.kt` | `ModalNavigationDrawer` + `when(currentDest)` state var | **Leave as dead code** — still compiles, existing tests still pass. Deleted in a later phase. |
| `app/src/main/res/font/` | **Does not exist** | **Create** + download 3 TTF files |
| `gradle/libs.versions.toml` | `navigationCompose = "2.7.7"` entry exists | No change needed |
| `app/build.gradle.kts` | `navigation-compose` absent from `dependencies {}` | **Add** one line |

**Test framework already in place:** JUnit 4 (`libs.junit`) + Google Truth (`libs.truth`) declared as `testImplementation`. `unitTests.isReturnDefaultValues = true` is set in `android { testOptions }`, so `androidx.compose.ui.graphics.Color`, `TextUnit`, `TextStyle`, and `Font` all work in JVM unit tests without an emulator.

---

## New Files Summary

| Create | Path |
|--------|------|
| Font | `app/src/main/res/font/instrument_serif_regular.ttf` |
| Font | `app/src/main/res/font/jetbrains_mono_regular.ttf` |
| Font | `app/src/main/res/font/jetbrains_mono_medium.ttf` |
| Source | `app/src/main/kotlin/com/vela/app/ui/theme/VelaColors.kt` |
| Source | `app/src/main/kotlin/com/vela/app/ui/navigation/AppNavigation.kt` |
| Test | `app/src/test/kotlin/com/vela/app/ui/theme/VelaColorsTest.kt` |
| Test | `app/src/test/kotlin/com/vela/app/ui/theme/VelaTypographyTest.kt` |
| Test | `app/src/test/kotlin/com/vela/app/ui/navigation/AppNavigationTest.kt` |

---

## Task 1: Download Font Files

**Files:**
- Create: `app/src/main/res/font/instrument_serif_regular.ttf`
- Create: `app/src/main/res/font/jetbrains_mono_regular.ttf`
- Create: `app/src/main/res/font/jetbrains_mono_medium.ttf`

### Step 1: Create the font directory and download all three TTFs

Run from the repo root (`/Users/ken/workspace/vela`):

```bash
mkdir -p app/src/main/res/font

curl -L \
  "https://github.com/google/fonts/raw/refs/heads/main/ofl/instrumentserif/InstrumentSerif-Regular.ttf" \
  -o app/src/main/res/font/instrument_serif_regular.ttf

curl -L \
  "https://github.com/JetBrains/JetBrainsMono/raw/master/fonts/ttf/JetBrainsMono-Regular.ttf" \
  -o app/src/main/res/font/jetbrains_mono_regular.ttf

curl -L \
  "https://github.com/JetBrains/JetBrainsMono/raw/master/fonts/ttf/JetBrainsMono-Medium.ttf" \
  -o app/src/main/res/font/jetbrains_mono_medium.ttf
```

### Step 2: Verify all three files are present and non-empty

Run:
```bash
ls -lh app/src/main/res/font/
```

Expected: Three `.ttf` files, each larger than 50 KB. If any is 0 bytes or absent, re-run its `curl` command.

### Step 3: Commit

```bash
git add app/src/main/res/font/
git commit -m "feat(theme): add bundled font files — Instrument Serif, JetBrains Mono"
```

---

## Task 2: Create VelaColors.kt + VelaColorsTest.kt

**Files:**
- Create: `app/src/main/kotlin/com/vela/app/ui/theme/VelaColors.kt`
- Create: `app/src/test/kotlin/com/vela/app/ui/theme/VelaColorsTest.kt`

### Step 1: Write the failing test

Create `app/src/test/kotlin/com/vela/app/ui/theme/VelaColorsTest.kt`:

```kotlin
package com.vela.app.ui.theme

import androidx.compose.ui.graphics.Color
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

    // ── Surfaces ─────────────────────────────────────────────────────────────

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

    // ── Accent ────────────────────────────────────────────────────────────────

    @Test fun `Accent has correct hex value`() {
        assertThat(VelaColors.Accent.toArgb()).isEqualTo(Color(0xFF5EEAD4).toArgb())
    }

    @Test fun `AccentCoord has correct hex value`() {
        assertThat(VelaColors.AccentCoord.toArgb()).isEqualTo(Color(0xFF1FE0C2).toArgb())
    }

    // ── Status: Running ───────────────────────────────────────────────────────

    @Test fun `Running has correct hex value`() {
        assertThat(VelaColors.Running.toArgb()).isEqualTo(Color(0xFFF5A524).toArgb())
    }

    @Test fun `RunningContainer has correct hex value`() {
        assertThat(VelaColors.RunningContainer.toArgb()).isEqualTo(Color(0xFF3A2400).toArgb())
    }

    @Test fun `RunningOnContainer has correct hex value`() {
        assertThat(VelaColors.RunningOnContainer.toArgb()).isEqualTo(Color(0xFFFFD89B).toArgb())
    }

    // ── Status: Waiting ───────────────────────────────────────────────────────

    @Test fun `Waiting has correct hex value`() {
        assertThat(VelaColors.Waiting.toArgb()).isEqualTo(Color(0xFFA78BFA).toArgb())
    }

    @Test fun `WaitingContainer has correct hex value`() {
        assertThat(VelaColors.WaitingContainer.toArgb()).isEqualTo(Color(0xFF2E1A5C).toArgb())
    }

    // ── Status: Done ──────────────────────────────────────────────────────────

    @Test fun `Done has correct hex value`() {
        assertThat(VelaColors.Done.toArgb()).isEqualTo(Color(0xFF7DCFA5).toArgb())
    }

    @Test fun `DoneContainer has correct hex value`() {
        assertThat(VelaColors.DoneContainer.toArgb()).isEqualTo(Color(0xFF143A29).toArgb())
    }

    @Test fun `DoneOnContainer has correct hex value`() {
        assertThat(VelaColors.DoneOnContainer.toArgb()).isEqualTo(Color(0xFFB8E8CD).toArgb())
    }

    // ── Status: Error ─────────────────────────────────────────────────────────

    @Test fun `Error has correct hex value`() {
        assertThat(VelaColors.Error.toArgb()).isEqualTo(Color(0xFFFF6B6B).toArgb())
    }

    @Test fun `ErrorContainer has correct hex value`() {
        assertThat(VelaColors.ErrorContainer.toArgb()).isEqualTo(Color(0xFF4A1818).toArgb())
    }

    @Test fun `ErrorOnContainer has correct hex value`() {
        assertThat(VelaColors.ErrorOnContainer.toArgb()).isEqualTo(Color(0xFFFFC4C4).toArgb())
    }

    // ── Text ──────────────────────────────────────────────────────────────────

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

    // ── Strokes ───────────────────────────────────────────────────────────────

    @Test fun `StrokeHair has correct hex value`() {
        assertThat(VelaColors.StrokeHair.toArgb()).isEqualTo(Color(0x0FFFFFFF).toArgb())
    }

    @Test fun `StrokeEdge has correct hex value`() {
        assertThat(VelaColors.StrokeEdge.toArgb()).isEqualTo(Color(0x1FFFFFFF).toArgb())
    }
}
```

### Step 2: Run the test to confirm it fails

Run:
```bash
./gradlew :app:testDebugUnitTest \
  --tests "com.vela.app.ui.theme.VelaColorsTest" \
  2>&1 | tail -20
```

Expected: **BUILD FAILED** — `error: unresolved reference: VelaColors`

### Step 3: Create VelaColors.kt

Create `app/src/main/kotlin/com/vela/app/ui/theme/VelaColors.kt`:

```kotlin
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

    // ── Surfaces ─────────────────────────────────────────────────────────────

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

    // ── Accent ────────────────────────────────────────────────────────────────

    /** Brand cyan-aqua accent — used sparingly; one element per screen max. */
    val Accent      = Color(0xFF5EEAD4)
    /** Coordinator-mode teal accent — distinguishes coordinator views. */
    val AccentCoord = Color(0xFF1FE0C2)

    // ── Status: Running (warm amber) ──────────────────────────────────────────

    val Running            = Color(0xFFF5A524)
    val RunningOn          = Color(0xFF1A1000)
    val RunningContainer   = Color(0xFF3A2400)
    val RunningOnContainer = Color(0xFFFFD89B)

    // ── Status: Waiting (electric violet) ────────────────────────────────────

    val Waiting            = Color(0xFFA78BFA)
    val WaitingOn          = Color(0xFF1A0F3A)
    val WaitingContainer   = Color(0xFF2E1A5C)
    val WaitingOnContainer = Color(0xFFDDD0FF)

    // ── Status: Done (quiet sage) ─────────────────────────────────────────────

    val Done            = Color(0xFF7DCFA5)
    val DoneOn          = Color(0xFF0A2418)
    val DoneContainer   = Color(0xFF143A29)
    val DoneOnContainer = Color(0xFFB8E8CD)

    // ── Status: Error (coral) ─────────────────────────────────────────────────

    val Error            = Color(0xFFFF6B6B)
    val ErrorOn          = Color(0xFF2A0808)
    val ErrorContainer   = Color(0xFF4A1818)
    val ErrorOnContainer = Color(0xFFFFC4C4)

    // ── Text ──────────────────────────────────────────────────────────────────

    /** Near-white with a faint warm cast — never surgical against indigo. */
    val TextPrimary   = Color(0xFFF5F2EC)
    val TextSecondary = Color(0xFFB5B8C8)
    val TextTertiary  = Color(0xFF7A7E94)
    val TextDisabled  = Color(0xFF4A4D5E)

    // ── Strokes & Dividers ────────────────────────────────────────────────────

    /** Hairline separator — rgba(255,255,255,0.06). */
    val StrokeHair = Color(0x0FFFFFFF)
    /** Edge border — rgba(255,255,255,0.12). */
    val StrokeEdge = Color(0x1FFFFFFF)
}
```

### Step 4: Run the test to confirm it passes

Run:
```bash
./gradlew :app:testDebugUnitTest \
  --tests "com.vela.app.ui.theme.VelaColorsTest" \
  2>&1 | tail -15
```

Expected: `BUILD SUCCESSFUL` — all 24 tests pass.

### Step 5: Commit

```bash
git add app/src/main/kotlin/com/vela/app/ui/theme/VelaColors.kt \
        app/src/test/kotlin/com/vela/app/ui/theme/VelaColorsTest.kt
git commit -m "feat(theme): add VelaColors object with all 30 DESIGN.md color tokens"
```

---

## Task 3: Replace Color.kt

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/ui/theme/Color.kt`

Color.kt previously held three dead green tokens used only by the old Theme.kt (which we also replace). Replace the entire file content with top-level package-level aliases so Theme.kt can reference tokens by bare name without qualifying every `VelaColors.*` call.

### Step 1: Replace Color.kt

Replace the **entire** content of `app/src/main/kotlin/com/vela/app/ui/theme/Color.kt` with:

```kotlin
package com.vela.app.ui.theme

/**
 * Top-level color token aliases for use within the theme package.
 *
 * All values are sourced from [VelaColors]. These re-exports let Theme.kt
 * reference tokens by bare name (e.g. `Abyss`) without qualifying every
 * call with `VelaColors.`. External callers should use [VelaColors] directly.
 */

// ── Surfaces ──────────────────────────────────────────────────────────────────
internal val Abyss         = VelaColors.Abyss
internal val SurfaceSub    = VelaColors.SurfaceSub
internal val SurfaceRaised = VelaColors.SurfaceRaised
internal val SurfacePeak   = VelaColors.SurfacePeak
internal val CoordBg       = VelaColors.CoordBg
internal val CoordCard     = VelaColors.CoordCard

// ── Accent ────────────────────────────────────────────────────────────────────
internal val Accent      = VelaColors.Accent
internal val AccentCoord = VelaColors.AccentCoord

// ── Status: Running ───────────────────────────────────────────────────────────
internal val Running            = VelaColors.Running
internal val RunningOn          = VelaColors.RunningOn
internal val RunningContainer   = VelaColors.RunningContainer
internal val RunningOnContainer = VelaColors.RunningOnContainer

// ── Status: Waiting ───────────────────────────────────────────────────────────
internal val Waiting            = VelaColors.Waiting
internal val WaitingOn          = VelaColors.WaitingOn
internal val WaitingContainer   = VelaColors.WaitingContainer
internal val WaitingOnContainer = VelaColors.WaitingOnContainer

// ── Status: Done ──────────────────────────────────────────────────────────────
internal val Done            = VelaColors.Done
internal val DoneOn          = VelaColors.DoneOn
internal val DoneContainer   = VelaColors.DoneContainer
internal val DoneOnContainer = VelaColors.DoneOnContainer

// ── Status: Error ─────────────────────────────────────────────────────────────
internal val Error            = VelaColors.Error
internal val ErrorOn          = VelaColors.ErrorOn
internal val ErrorContainer   = VelaColors.ErrorContainer
internal val ErrorOnContainer = VelaColors.ErrorOnContainer

// ── Text ──────────────────────────────────────────────────────────────────────
internal val TextPrimary   = VelaColors.TextPrimary
internal val TextSecondary = VelaColors.TextSecondary
internal val TextTertiary  = VelaColors.TextTertiary
internal val TextDisabled  = VelaColors.TextDisabled

// ── Strokes ───────────────────────────────────────────────────────────────────
internal val StrokeHair = VelaColors.StrokeHair
internal val StrokeEdge = VelaColors.StrokeEdge
```

### Step 2: Verify the existing VelaColors tests still pass

Run:
```bash
./gradlew :app:testDebugUnitTest \
  --tests "com.vela.app.ui.theme.VelaColorsTest" \
  2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`

### Step 3: Commit

```bash
git add app/src/main/kotlin/com/vela/app/ui/theme/Color.kt
git commit -m "feat(theme): replace old green tokens in Color.kt with VelaColors aliases"
```

---

## Task 4: Update Type.kt + VelaTypographyTest.kt

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/ui/theme/Type.kt`
- Create: `app/src/test/kotlin/com/vela/app/ui/theme/VelaTypographyTest.kt`

> **Prerequisite:** Task 1 (font files) must be complete. The `R.font.*` constants are generated by AAPT at compile time and must exist on disk before the project compiles.

### Step 1: Write the failing test

Create `app/src/test/kotlin/com/vela/app/ui/theme/VelaTypographyTest.kt`:

```kotlin
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

    // ── displayLarge ─────────────────────────────────────────────────────────

    @Test fun `displayLarge uses InstrumentSerifFamily`() {
        assertThat(VelaTypography.displayLarge.fontFamily).isEqualTo(InstrumentSerifFamily)
    }

    @Test fun `displayLarge has 48sp font size`() {
        assertThat(VelaTypography.displayLarge.fontSize).isEqualTo(48.sp)
    }

    @Test fun `displayLarge has negative 1point5 letter spacing`() {
        assertThat(VelaTypography.displayLarge.letterSpacing).isEqualTo((-1.5).sp)
    }

    // ── displayMedium ─────────────────────────────────────────────────────────

    @Test fun `displayMedium uses InstrumentSerifFamily`() {
        assertThat(VelaTypography.displayMedium.fontFamily).isEqualTo(InstrumentSerifFamily)
    }

    @Test fun `displayMedium has 36sp font size`() {
        assertThat(VelaTypography.displayMedium.fontSize).isEqualTo(36.sp)
    }

    // ── displaySmall ──────────────────────────────────────────────────────────

    @Test fun `displaySmall uses InstrumentSerifFamily`() {
        assertThat(VelaTypography.displaySmall.fontFamily).isEqualTo(InstrumentSerifFamily)
    }

    // ── headlineLarge ─────────────────────────────────────────────────────────

    @Test fun `headlineLarge uses InstrumentSerifFamily`() {
        assertThat(VelaTypography.headlineLarge.fontFamily).isEqualTo(InstrumentSerifFamily)
    }

    @Test fun `headlineLarge has 28sp font size`() {
        assertThat(VelaTypography.headlineLarge.fontSize).isEqualTo(28.sp)
    }

    // ── headlineMedium ────────────────────────────────────────────────────────

    @Test fun `headlineMedium uses system FontFamily Default`() {
        assertThat(VelaTypography.headlineMedium.fontFamily).isEqualTo(FontFamily.Default)
    }

    @Test fun `headlineMedium has SemiBold weight`() {
        assertThat(VelaTypography.headlineMedium.fontWeight).isEqualTo(FontWeight.SemiBold)
    }

    // ── bodyLarge ─────────────────────────────────────────────────────────────

    @Test fun `bodyLarge has 16sp font size`() {
        assertThat(VelaTypography.bodyLarge.fontSize).isEqualTo(16.sp)
    }

    @Test fun `bodyLarge has 22sp line height`() {
        assertThat(VelaTypography.bodyLarge.lineHeight).isEqualTo(22.sp)
    }

    // ── bodyMedium ────────────────────────────────────────────────────────────

    @Test fun `bodyMedium has 14sp font size`() {
        assertThat(VelaTypography.bodyMedium.fontSize).isEqualTo(14.sp)
    }

    @Test fun `bodyMedium has 20sp line height`() {
        assertThat(VelaTypography.bodyMedium.lineHeight).isEqualTo(20.sp)
    }

    // ── labelLarge ────────────────────────────────────────────────────────────

    @Test fun `labelLarge has SemiBold weight`() {
        assertThat(VelaTypography.labelLarge.fontWeight).isEqualTo(FontWeight.SemiBold)
    }

    @Test fun `labelLarge has point5 letter spacing`() {
        assertThat(VelaTypography.labelLarge.letterSpacing).isEqualTo(0.5.sp)
    }

    // ── labelMedium ───────────────────────────────────────────────────────────

    @Test fun `labelMedium has 12sp font size`() {
        assertThat(VelaTypography.labelMedium.fontSize).isEqualTo(12.sp)
    }

    @Test fun `labelMedium has Medium weight`() {
        assertThat(VelaTypography.labelMedium.fontWeight).isEqualTo(FontWeight.Medium)
    }

    @Test fun `labelMedium has 1point0 letter spacing`() {
        assertThat(VelaTypography.labelMedium.letterSpacing).isEqualTo(1.0.sp)
    }

    // ── labelSmall ────────────────────────────────────────────────────────────

    @Test fun `labelSmall has 11sp font size`() {
        assertThat(VelaTypography.labelSmall.fontSize).isEqualTo(11.sp)
    }

    @Test fun `labelSmall has Bold weight`() {
        assertThat(VelaTypography.labelSmall.fontWeight).isEqualTo(FontWeight.Bold)
    }

    @Test fun `labelSmall has 2point0 letter spacing`() {
        assertThat(VelaTypography.labelSmall.letterSpacing).isEqualTo(2.0.sp)
    }

    // ── MonoMedium (non-M3, used directly) ───────────────────────────────────

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
```

### Step 2: Run the test to confirm it fails

Run:
```bash
./gradlew :app:testDebugUnitTest \
  --tests "com.vela.app.ui.theme.VelaTypographyTest" \
  2>&1 | tail -20
```

Expected: **BUILD FAILED** — `unresolved reference: InstrumentSerifFamily`, `unresolved reference: JetBrainsMonoFamily`, `unresolved reference: MonoMedium`.

### Step 3: Replace Type.kt with the full scale

Replace the **entire** content of `app/src/main/kotlin/com/vela/app/ui/theme/Type.kt`:

```kotlin
package com.vela.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.vela.app.R

// ── Font families ─────────────────────────────────────────────────────────────

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

// ── Typography scale ──────────────────────────────────────────────────────────

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
```

### Step 4: Run the test to confirm it passes

Run:
```bash
./gradlew :app:testDebugUnitTest \
  --tests "com.vela.app.ui.theme.VelaTypographyTest" \
  2>&1 | tail -15
```

Expected: `BUILD SUCCESSFUL` — all 25 tests pass.

### Step 5: Commit

```bash
git add app/src/main/kotlin/com/vela/app/ui/theme/Type.kt \
        app/src/test/kotlin/com/vela/app/ui/theme/VelaTypographyTest.kt
git commit -m "feat(theme): replace Type.kt with full DESIGN.md typography scale + Instrument Serif + JetBrains Mono"
```

---

## Task 5: Replace Theme.kt

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/ui/theme/Theme.kt`

Remove light scheme, remove dynamic color, remove `isSystemInDarkTheme` parameter. The new theme is dark-only with our explicit palette, so the app always renders the DESIGN.md tokens regardless of system settings.

### Step 1: Replace the entire content of Theme.kt

```kotlin
package com.vela.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Vela app theme — dark-only, dynamic color permanently disabled.
 *
 * Dynamic color is disabled so the DESIGN.md palette actually renders on all devices.
 * Enabling it on Android 12+ would override our tokens with the system wallpaper palette.
 *
 * All color tokens come from [VelaColors] via the package-level aliases in Color.kt.
 */
@Composable
fun VelaTheme(content: @Composable () -> Unit) {
    val colorScheme = darkColorScheme(
        background            = Abyss,
        surface               = SurfaceSub,
        surfaceVariant        = SurfaceRaised,
        primary               = Accent,
        onPrimary             = Color(0xFF003731),
        primaryContainer      = RunningContainer,
        onPrimaryContainer    = RunningOnContainer,
        secondary             = Waiting,
        onSecondary           = WaitingOn,
        secondaryContainer    = WaitingContainer,
        onSecondaryContainer  = WaitingOnContainer,
        tertiary              = Done,
        onTertiary            = DoneOn,
        tertiaryContainer     = DoneContainer,
        onTertiaryContainer   = DoneOnContainer,
        error                 = Error,
        onError               = ErrorOn,
        errorContainer        = ErrorContainer,
        onErrorContainer      = ErrorOnContainer,
        onBackground          = TextPrimary,
        onSurface             = TextPrimary,
        onSurfaceVariant      = TextSecondary,
        outline               = StrokeEdge,
        outlineVariant        = StrokeHair,
    )

    MaterialTheme(
        colorScheme = colorScheme,
        typography  = VelaTypography,
        content     = content,
    )
}
```

### Step 2: Verify Theme.kt compiles (run existing theme tests)

Run:
```bash
./gradlew :app:testDebugUnitTest \
  --tests "com.vela.app.ui.theme.*" \
  2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL` — VelaColorsTest and VelaTypographyTest all pass.

### Step 3: Commit

```bash
git add app/src/main/kotlin/com/vela/app/ui/theme/Theme.kt
git commit -m "feat(theme): replace Theme.kt with dark-only VelaTheme, disable dynamic color"
```

---

## Task 6: Create AppNavigation.kt + AppNavigationTest.kt

**Files:**
- Modify: `app/build.gradle.kts` (add one dependency line)
- Create: `app/src/main/kotlin/com/vela/app/ui/navigation/AppNavigation.kt`
- Create: `app/src/test/kotlin/com/vela/app/ui/navigation/AppNavigationTest.kt`

### Step 1: Add the navigation-compose dependency to build.gradle.kts

The `androidx-navigation-compose` entry already exists in `gradle/libs.versions.toml` (version 2.7.7) but is **not** listed in `app/build.gradle.kts`. Add it.

Open `app/build.gradle.kts`. In the `dependencies {}` block, immediately after the line:
```kotlin
implementation(libs.hilt.navigation.compose)
```
Add:
```kotlin
implementation(libs.androidx.navigation.compose)
```

### Step 2: Write the failing Routes test

Create `app/src/test/kotlin/com/vela/app/ui/navigation/AppNavigationTest.kt`:

```kotlin
package com.vela.app.ui.navigation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * RED → GREEN: verifies [Routes] constants and helper functions produce
 * the exact URL strings expected by the NavHost graph.
 *
 * Pure JVM tests — no Compose or Android needed.
 */
class AppNavigationTest {

    // ── Constants ─────────────────────────────────────────────────────────────

    @Test fun `HOME constant is home`() {
        assertThat(Routes.HOME).isEqualTo("home")
    }

    @Test fun `CONNECT_NODE constant is connect`() {
        assertThat(Routes.CONNECT_NODE).isEqualTo("connect")
    }

    @Test fun `NODE_DETAIL pattern contains nodeId placeholder`() {
        assertThat(Routes.NODE_DETAIL).contains("{nodeId}")
    }

    @Test fun `SESSION_DETAIL pattern contains sessionId placeholder`() {
        assertThat(Routes.SESSION_DETAIL).contains("{sessionId}")
    }

    // ── Helper functions ──────────────────────────────────────────────────────

    @Test fun `nodeDetail builds correct route`() {
        assertThat(Routes.nodeDetail("node-7")).isEqualTo("node/node-7")
    }

    @Test fun `nodeDetail handles node IDs with special chars`() {
        assertThat(Routes.nodeDetail("my-node_01")).isEqualTo("node/my-node_01")
    }

    @Test fun `sessionList builds correct route`() {
        assertThat(Routes.sessionList("node-7", "proj-abc"))
            .isEqualTo("node/node-7/project/proj-abc")
    }

    @Test fun `sessionDetail builds correct route`() {
        assertThat(Routes.sessionDetail("sess-99")).isEqualTo("session/sess-99")
    }

    @Test fun `coordinator builds correct route`() {
        assertThat(Routes.coordinator("sess-99")).isEqualTo("session/sess-99/coordinator")
    }

    @Test fun `nodeConfig builds correct route`() {
        assertThat(Routes.nodeConfig("node-7")).isEqualTo("node/node-7/config")
    }
}
```

### Step 3: Run the test to confirm it fails

Run:
```bash
./gradlew :app:testDebugUnitTest \
  --tests "com.vela.app.ui.navigation.AppNavigationTest" \
  2>&1 | tail -20
```

Expected: **BUILD FAILED** — `unresolved reference: Routes`

### Step 4: Create AppNavigation.kt

Create the directory and file:
```bash
mkdir -p app/src/main/kotlin/com/vela/app/ui/navigation
```

Create `app/src/main/kotlin/com/vela/app/ui/navigation/AppNavigation.kt`:

```kotlin
package com.vela.app.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.vela.app.ui.theme.VelaColors

// ── Routes ────────────────────────────────────────────────────────────────────

/**
 * All navigation routes in the Vela NavHost graph.
 *
 * Pattern strings (with `{param}` placeholders) are used when registering
 * composable destinations. Helper functions build concrete route strings for
 * [NavController.navigate] calls.
 */
object Routes {
    const val HOME         = "home"
    const val NODE_DETAIL  = "node/{nodeId}"
    const val SESSION_LIST = "node/{nodeId}/project/{projectId}"
    const val SESSION_DETAIL = "session/{sessionId}"
    const val COORDINATOR  = "session/{sessionId}/coordinator"
    const val NODE_CONFIG  = "node/{nodeId}/config"
    const val CONNECT_NODE = "connect"

    fun nodeDetail(nodeId: String)                         = "node/$nodeId"
    fun sessionList(nodeId: String, projectId: String)     = "node/$nodeId/project/$projectId"
    fun sessionDetail(sessionId: String)                   = "session/$sessionId"
    fun coordinator(sessionId: String)                     = "session/$sessionId/coordinator"
    fun nodeConfig(nodeId: String)                         = "node/$nodeId/config"
}

// ── Root composable ───────────────────────────────────────────────────────────

/**
 * Root composable for the entire Vela UI.
 *
 * Contains the [NavHost] for hierarchical navigation (Home → Node → Project →
 * Session) and the persistent [VoiceFabPlaceholder] overlaid at bottom-right
 * above all screens.
 *
 * All screen destinations are placeholders in Phase 1. They are replaced
 * screen-by-screen in Phases 2–6.
 */
@Composable
fun VelaApp(modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    Box(modifier = modifier.fillMaxSize()) {
        NavHost(
            navController    = navController,
            startDestination = Routes.HOME,
        ) {
            composable(Routes.HOME)           { HomeScreenPlaceholder(navController) }
            composable(Routes.NODE_DETAIL)    { NodeDetailPlaceholder(navController) }
            composable(Routes.SESSION_LIST)   { SessionListPlaceholder(navController) }
            composable(Routes.SESSION_DETAIL) { SessionDetailPlaceholder(navController) }
            composable(Routes.COORDINATOR)    { CoordinatorPlaceholder(navController) }
            composable(Routes.NODE_CONFIG)    { NodeConfigPlaceholder(navController) }
            composable(Routes.CONNECT_NODE)   { ConnectNodePlaceholder(navController) }
        }

        // Persistent Voice FAB — always on top, always bottom-right.
        // Replaced with the real VoiceFab in Phase 2.
        VoiceFabPlaceholder(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp),
        )
    }
}

// ── Placeholder screens ───────────────────────────────────────────────────────
// Each is a minimal Surface + Text so the NavHost graph compiles and the app
// launches. Replaced screen-by-screen in Phases 2–6.

@Composable
private fun HomeScreenPlaceholder(navController: NavController) {
    Surface(modifier = Modifier.fillMaxSize(), color = VelaColors.Abyss) {
        Text(text = "Home — Nodes", color = VelaColors.TextPrimary)
    }
}

@Composable
private fun NodeDetailPlaceholder(navController: NavController) {
    Surface(modifier = Modifier.fillMaxSize(), color = VelaColors.Abyss) {
        Text(text = "Node Detail", color = VelaColors.TextPrimary)
    }
}

@Composable
private fun SessionListPlaceholder(navController: NavController) {
    Surface(modifier = Modifier.fillMaxSize(), color = VelaColors.Abyss) {
        Text(text = "Session List", color = VelaColors.TextPrimary)
    }
}

@Composable
private fun SessionDetailPlaceholder(navController: NavController) {
    Surface(modifier = Modifier.fillMaxSize(), color = VelaColors.Abyss) {
        Text(text = "Session Detail", color = VelaColors.TextPrimary)
    }
}

@Composable
private fun CoordinatorPlaceholder(navController: NavController) {
    Surface(modifier = Modifier.fillMaxSize(), color = VelaColors.CoordBg) {
        Text(text = "Coordinator", color = VelaColors.TextPrimary)
    }
}

@Composable
private fun NodeConfigPlaceholder(navController: NavController) {
    Surface(modifier = Modifier.fillMaxSize(), color = VelaColors.Abyss) {
        Text(text = "Node Config", color = VelaColors.TextPrimary)
    }
}

@Composable
private fun ConnectNodePlaceholder(navController: NavController) {
    Surface(modifier = Modifier.fillMaxSize(), color = VelaColors.Abyss) {
        Text(text = "Connect a Node", color = VelaColors.TextPrimary)
    }
}

// ── Voice FAB placeholder ─────────────────────────────────────────────────────

/**
 * Placeholder for the persistent Voice FAB.
 *
 * Matches the DESIGN.md §7.7 idle-state dimensions (64dp, cyan ring on
 * SurfacePeak disc) without any animation or interaction logic.
 * The real VoiceFab with bloom animation is built in Phase 2.
 */
@Composable
fun VoiceFabPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(VelaColors.SurfacePeak)
            .border(1.5.dp, VelaColors.Accent, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector        = Icons.Default.Mic,
            contentDescription = "Voice",
            tint               = VelaColors.Accent,
            modifier           = Modifier.size(26.dp),
        )
    }
}
```

### Step 5: Run the Routes test to confirm it passes

Run:
```bash
./gradlew :app:testDebugUnitTest \
  --tests "com.vela.app.ui.navigation.AppNavigationTest" \
  2>&1 | tail -15
```

Expected: `BUILD SUCCESSFUL` — all 10 tests pass.

### Step 6: Run all theme tests to confirm no regressions

Run:
```bash
./gradlew :app:testDebugUnitTest \
  --tests "com.vela.app.ui.theme.*" \
  --tests "com.vela.app.ui.navigation.*" \
  2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`

### Step 7: Commit

```bash
git add app/build.gradle.kts \
        app/src/main/kotlin/com/vela/app/ui/navigation/AppNavigation.kt \
        app/src/test/kotlin/com/vela/app/ui/navigation/AppNavigationTest.kt
git commit -m "feat(navigation): add navigation-compose dep, Routes object, VelaApp NavHost with placeholder screens"
```

---

## Task 7: Update MainActivity.kt

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/MainActivity.kt`

Replace the entire `setContent` block and remove all old chat-UI-specific dependencies (`ConversationViewModel`, `SpeechTranscriber`, `WindowSizeClass`, `NavigationScaffold`, `ApiKeyDialog`). These belonged to the old chat interface; the new cockpit UI starts from scratch here.

> **Note:** `NavigationScaffold.kt` is left on disk — it still compiles, and `NavigationScaffoldInsetsTest.kt` still passes. It becomes unreferenced dead code and will be deleted in a later phase once the old screen implementations are removed.

### Step 1: Replace the entire content of MainActivity.kt

```kotlin
package com.vela.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.vela.app.ui.navigation.VelaApp
import com.vela.app.ui.theme.VelaTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VelaTheme {
                VelaApp()
            }
        }
    }
}
```

### Step 2: Confirm the file compiles cleanly by running all unit tests

Run:
```bash
./gradlew :app:testDebugUnitTest 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL` — all existing unit tests pass. (The NavigationScaffoldInsetsTest still passes because `NavigationScaffold.kt` still exists on disk.)

### Step 3: Commit

```bash
git add app/src/main/kotlin/com/vela/app/MainActivity.kt
git commit -m "feat(navigation): simplify MainActivity to VelaTheme { VelaApp() }, remove old chat UI bootstrap"
```

---

## Task 8: Full Build Verification

### Step 1: Run assembleDebug and confirm BUILD SUCCESSFUL

Run:
```bash
./gradlew assembleDebug 2>&1 | tail -5
```

Expected output:
```
BUILD SUCCESSFUL in Xs
3 actionable tasks: 3 executed
```

If the build fails, read the full error output:
```bash
./gradlew assembleDebug 2>&1 | grep -E "error:|BUILD"
```

Common failure causes and fixes:
- `unresolved reference: R.font.instrument_serif_regular` — Task 1 font files are missing. Re-run the curl commands and retry.
- `unresolved reference: VelaColors` — VelaColors.kt not saved correctly. Verify the file exists and has the correct package declaration.
- `duplicate class` — A stale Color.kt token (e.g. `VelaPrimary`) clashes with a new Color.kt alias. Verify Color.kt contains **only** the content from Task 3 Step 1.

### Step 2: Run the full unit test suite

Run:
```bash
./gradlew :app:testDebugUnitTest 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`

### Step 3: Commit (if assembleDebug required any fix commits)

If no fixes were needed, skip. If fixes were made, commit them now:
```bash
git add -A
git commit -m "fix(theme): resolve build issue from phase 1 design system changes"
```

---

## Task 9: Install on Device and Smoke Test

### Step 1: Install the debug APK on a connected device or running emulator

Run:
```bash
./gradlew installDebug 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL` — APK installed.

### Step 2: Launch the app and verify visually

Open the app on the device. Confirm:

| Check | Expected |
|-------|----------|
| App background | Deep blue-indigo `#0B0E1A` — NOT black, NOT grey |
| Surface color | No white or light surfaces visible |
| FAB | 64dp circle, bottom-right, cyan ring (`#5EEAD4`) on dark indigo disc |
| Screen content | "Home — Nodes" label visible in near-white text |
| No crash | App does not crash on launch |

If the background still appears as the Android system default (grey or white), dynamic color may still be active. Confirm Theme.kt was replaced correctly: it should call `darkColorScheme(...)` directly with no `if (dynamicColor && ...)` branch.

### Step 3: Final commit if smoke test reveals any minor issues

If any non-functional issues were fixed (e.g. import cleanup), commit:
```bash
git add -A
git commit -m "chore(ui-phase1): smoke test fixes"
```

---

## Phase 1 Complete — Verification Checklist

Before declaring Phase 1 done, confirm all of the following:

- [ ] `app/src/main/res/font/` contains three `.ttf` files, each > 50 KB
- [ ] `VelaColorsTest` — 24 tests pass
- [ ] `VelaTypographyTest` — 25 tests pass
- [ ] `AppNavigationTest` — 10 tests pass
- [ ] `NavigationScaffoldInsetsTest` — 3 tests still pass (no regression)
- [ ] `./gradlew assembleDebug` → `BUILD SUCCESSFUL`
- [ ] `./gradlew :app:testDebugUnitTest` → `BUILD SUCCESSFUL`
- [ ] App launches on device with `#0B0E1A` background and cyan FAB visible

## What Phase 2 Builds On

Phase 1 delivers the substrate. Every subsequent phase can now:

- Use `VelaColors.Abyss`, `VelaColors.Running`, etc. by name
- Use `VelaTypography.displayLarge`, `MonoMedium`, etc. by name
- Call `MaterialTheme.colorScheme.primary` and get `#5EEAD4` (Accent)
- Navigate via `navController.navigate(Routes.nodeDetail("node-7"))`
- Replace any placeholder screen composable with the real implementation without touching MainActivity or AppNavigation structure
