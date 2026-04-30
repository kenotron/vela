# UI Phase 6: Coordinator Session Screen Implementation Plan

> **Execution:** Use the subagent-driven-development workflow to implement this plan.

**Goal:** Build Screen 5 (`CoordinatorScreen`) — the full-screen coordinator session view with its characteristic teal temperature shift, branch cards showing per-node parallel work, and gradient title — then wire it into the NavHost replacing `CoordinatorPlaceholder`.

**Architecture:** One new package — `com.vela.app.ui.coordinator` — containing three files: `CoordinatorViewModel.kt` (Hilt ViewModel with `BranchState`/`BranchStep`/`StepStatus` data model and sample data), `BranchCard.kt` (composable for an individual node's work card with tonal fill, border, step rows), and `CoordinatorScreen.kt` (the root screen composable with `CoordBg` surface, gradient app bar, coordinator strip with step pips, and a `LazyColumn` of branch cards separated by connector labels). The screen is wired into `AppNavigation.kt` in Task 4 by replacing the Phase 1 `CoordinatorPlaceholder`. The entire temperature shift — from indigo `Abyss` to teal-tinted `CoordBg` — is communicated exclusively via the surface background swap and `AccentCoord` tinting; the title's `Brush.linearGradient` is the sole gradient in the app.

**Tech Stack:** Kotlin, Jetpack Compose BOM 2025.04.01, Material 3 Expressive, Hilt 2.51, `androidx.lifecycle:lifecycle-viewmodel-savedstate`, JUnit 4, Google Truth 1.4.2

---

## Prior Phase Contracts — What Already Exists

Do **not** redefine any of these.

| Symbol | Location | Purpose |
|--------|----------|---------|
| `VelaColors.Abyss` | `com.vela.app.ui.theme.VelaColors` | `#0B0E1A` — standard screen background (NOT used in this screen) |
| `VelaColors.CoordBg` | `com.vela.app.ui.theme.VelaColors` | `#0C1E26` — coordinator screen background (teal-tinted indigo) |
| `VelaColors.CoordCard` | `com.vela.app.ui.theme.VelaColors` | `#13303C` — branch card container fill |
| `VelaColors.SurfaceRaised` | `com.vela.app.ui.theme.VelaColors` | `#171C36` — step pip "todo" color |
| `VelaColors.Accent` | `com.vela.app.ui.theme.VelaColors` | `#5EEAD4` — gradient title start color |
| `VelaColors.AccentCoord` | `com.vela.app.ui.theme.VelaColors` | `#1FE0C2` — coordinator teal accent (borders, strip, badge, back chevron) |
| `VelaColors.Running` | `com.vela.app.ui.theme.VelaColors` | `#F5A524` — running step text color and active pip |
| `VelaColors.Done` | `com.vela.app.ui.theme.VelaColors` | `#7DCFA5` — completed pip color |
| `VelaColors.TextPrimary` | `com.vela.app.ui.theme.VelaColors` | `#F5F2EC` — node name text |
| `VelaColors.TextSecondary` | `com.vela.app.ui.theme.VelaColors` | `#B5B8C8` — done-step text color |
| `VelaColors.TextTertiary` | `com.vela.app.ui.theme.VelaColors` | `#7A7E94` — waiting-step text + connector label |
| `MonoMedium` | `com.vela.app.ui.theme` (top-level val) | JetBrains Mono 13sp — base style for machine identifiers |
| `MaterialTheme.typography.displayMedium` | `com.vela.app.ui.theme.VelaTypography` | Instrument Serif 36sp — coordinator title |
| `Routes.COORDINATOR` | `com.vela.app.ui.navigation.Routes` | `"session/{sessionId}/coordinator"` — route constant |
| `Routes.coordinator(sessionId)` | `com.vela.app.ui.navigation.Routes` | Route builder for navigation |
| `Routes.sessionDetail(sessionId)` | `com.vela.app.ui.navigation.Routes` | Route builder used by "view session →" links |
| `CoordinatorPlaceholder` | `com.vela.app.ui.navigation.AppNavigation` | Private placeholder — **replaced in Task 4** |
| `AppNavigation.kt` | `com.vela.app.ui.navigation` | NavHost with `NavController` — **modified in Task 4** |

---

## Anti-Patterns — Hard Rules

These mistakes must not appear anywhere in the new files.

| Forbidden | Correct |
|-----------|---------|
| Asymmetric corner radius on branch cards (24dp/8dp) | Uniform `16.dp` `RoundedCornerShape` |
| `Brush.linearGradient` on anything except the coordinator title | Gradient on title text only |
| Left-border stripe (2dp leading tint strip on card) | Tonal fill on the entire card background |
| `VelaColors.Abyss` as `Surface` `color` in `CoordinatorScreen` | `VelaColors.CoordBg` |
| `Color(0xFF4A9EF5)` or any blue accent | `VelaColors.AccentCoord` (`#1FE0C2`) |

---

## New Files Summary

| Action | Path |
|--------|------|
| Create | `app/src/main/kotlin/com/vela/app/ui/coordinator/CoordinatorViewModel.kt` |
| Create | `app/src/main/kotlin/com/vela/app/ui/coordinator/BranchCard.kt` |
| Create | `app/src/main/kotlin/com/vela/app/ui/coordinator/CoordinatorScreen.kt` |
| Create | `app/src/test/kotlin/com/vela/app/ui/coordinator/CoordinatorViewModelTest.kt` |
| Create | `app/src/test/kotlin/com/vela/app/ui/coordinator/BranchCardColorTest.kt` |
| Modify | `app/src/main/kotlin/com/vela/app/ui/navigation/AppNavigation.kt` |

---

## Task 1: CoordinatorViewModel

**Files:**
- Create: `app/src/test/kotlin/com/vela/app/ui/coordinator/CoordinatorViewModelTest.kt`
- Create: `app/src/main/kotlin/com/vela/app/ui/coordinator/CoordinatorViewModel.kt`

This ViewModel holds the data model for the coordinator screen. It has no async loading — branches are seeded as static sample data. The entire test suite runs without Android or Hilt dependencies.

### Step 1: Write the failing test

Create `app/src/test/kotlin/com/vela/app/ui/coordinator/CoordinatorViewModelTest.kt`:

```kotlin
package com.vela.app.ui.coordinator

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CoordinatorViewModelTest {

    private fun newVm(sessionId: String = "test-session-123") = CoordinatorViewModel(
        savedStateHandle = SavedStateHandle(mapOf("sessionId" to sessionId)),
    )

    // ── sessionId ────────────────────────────────────────────────────────────

    @Test fun `sessionId is read from SavedStateHandle`() {
        assertThat(newVm("sess-abc").sessionId).isEqualTo("sess-abc")
    }

    // ── branches StateFlow ───────────────────────────────────────────────────

    @Test fun `branches has two entries by default`() {
        assertThat(newVm().branches.value).hasSize(2)
    }

    @Test fun `first branch nodeId is node-1`() {
        assertThat(newVm().branches.value[0].nodeId).isEqualTo("node-1")
    }

    @Test fun `first branch nodeName is amplifierd-mac`() {
        assertThat(newVm().branches.value[0].nodeName).isEqualTo("amplifierd-mac")
    }

    @Test fun `first branch has three steps`() {
        assertThat(newVm().branches.value[0].steps).hasSize(3)
    }

    @Test fun `first branch step 0 status is DONE`() {
        assertThat(newVm().branches.value[0].steps[0].status)
            .isEqualTo(CoordinatorViewModel.StepStatus.DONE)
    }

    @Test fun `first branch step 1 status is DONE`() {
        assertThat(newVm().branches.value[0].steps[1].status)
            .isEqualTo(CoordinatorViewModel.StepStatus.DONE)
    }

    @Test fun `first branch step 2 status is RUNNING`() {
        assertThat(newVm().branches.value[0].steps[2].status)
            .isEqualTo(CoordinatorViewModel.StepStatus.RUNNING)
    }

    @Test fun `second branch nodeId is node-2`() {
        assertThat(newVm().branches.value[1].nodeId).isEqualTo("node-2")
    }

    @Test fun `second branch nodeName is amplifierd-cloud`() {
        assertThat(newVm().branches.value[1].nodeName).isEqualTo("amplifierd-cloud")
    }

    @Test fun `second branch has three steps all WAITING`() {
        val steps = newVm().branches.value[1].steps
        assertThat(steps).hasSize(3)
        assertThat(steps.all { it.status == CoordinatorViewModel.StepStatus.WAITING }).isTrue()
    }

    // ── step progress ────────────────────────────────────────────────────────

    @Test fun `currentStep is 3`() {
        assertThat(newVm().currentStep).isEqualTo(3)
    }

    @Test fun `totalSteps is 5`() {
        assertThat(newVm().totalSteps).isEqualTo(5)
    }

    // ── StepStatus enum ──────────────────────────────────────────────────────

    @Test fun `StepStatus has DONE RUNNING and WAITING entries`() {
        val names = CoordinatorViewModel.StepStatus.entries.map { it.name }
        assertThat(names).containsExactly("DONE", "RUNNING", "WAITING")
    }

    // ── BranchStep description ───────────────────────────────────────────────

    @Test fun `first branch step 0 description contains git clone`() {
        assertThat(newVm().branches.value[0].steps[0].description)
            .contains("git clone")
    }

    @Test fun `first branch step 2 description contains build artifact`() {
        assertThat(newVm().branches.value[0].steps[2].description)
            .contains("build artifact")
    }
}
```

### Step 2: Run tests to verify they fail

```bash
cd /Users/ken/workspace/vela
./gradlew :app:testDebugUnitTest \
  --tests "com.vela.app.ui.coordinator.CoordinatorViewModelTest" \
  -x lint 2>&1 | tail -20
```

Expected: FAILED — `error: unresolved reference: CoordinatorViewModel`

### Step 3: Write CoordinatorViewModel.kt

Create `app/src/main/kotlin/com/vela/app/ui/coordinator/CoordinatorViewModel.kt`:

```kotlin
package com.vela.app.ui.coordinator

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class CoordinatorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val sessionId: String = checkNotNull(savedStateHandle["sessionId"])

    // ── Data model ───────────────────────────────────────────────────────────

    data class BranchState(
        val nodeId: String,
        val nodeName: String,
        val steps: List<BranchStep>,
        val sessionId: String? = null,
    )

    data class BranchStep(
        val description: String,
        val status: StepStatus,
    )

    enum class StepStatus { DONE, RUNNING, WAITING }

    // ── State ────────────────────────────────────────────────────────────────

    private val _branches = MutableStateFlow(
        listOf(
            BranchState(
                nodeId   = "node-1",
                nodeName = "amplifierd-mac",
                steps    = listOf(
                    BranchStep("git clone auth-service",           StepStatus.DONE),
                    BranchStep("run tests (47 passed)",            StepStatus.DONE),
                    BranchStep("build artifact & push registry…",  StepStatus.RUNNING),
                ),
            ),
            BranchState(
                nodeId   = "node-2",
                nodeName = "amplifierd-cloud",
                steps    = listOf(
                    BranchStep("waiting for mac build artifact",   StepStatus.WAITING),
                    BranchStep("deploy auth-service:v2.3.1",       StepStatus.WAITING),
                    BranchStep("run health checks",                StepStatus.WAITING),
                ),
            ),
        )
    )
    val branches: StateFlow<List<BranchState>> = _branches

    val currentStep: Int = 3
    val totalSteps: Int  = 5
}
```

### Step 4: Run tests to verify they pass

```bash
cd /Users/ken/workspace/vela
./gradlew :app:testDebugUnitTest \
  --tests "com.vela.app.ui.coordinator.CoordinatorViewModelTest" \
  -x lint 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL — 15 tests passing

### Step 5: Commit

```bash
git add app/src/main/kotlin/com/vela/app/ui/coordinator/CoordinatorViewModel.kt \
        app/src/test/kotlin/com/vela/app/ui/coordinator/CoordinatorViewModelTest.kt
git commit -m "feat(coordinator): add CoordinatorViewModel with BranchState data model"
```

---

## Task 2: BranchCard + BranchCardColorTest (all source-scan tests)

**Files:**
- Create: `app/src/test/kotlin/com/vela/app/ui/coordinator/BranchCardColorTest.kt`
- Create: `app/src/main/kotlin/com/vela/app/ui/coordinator/BranchCard.kt`

`BranchCardColorTest.kt` is written in full in this task — it contains source-scan tests for **both** `BranchCard.kt` and `CoordinatorScreen.kt`. After writing `BranchCard.kt` in Step 3, BranchCard tests go green but CoordinatorScreen tests stay red (that file doesn't exist yet). They go green in Task 3.

### Step 1: Write the failing tests

Create `app/src/test/kotlin/com/vela/app/ui/coordinator/BranchCardColorTest.kt`:

```kotlin
package com.vela.app.ui.coordinator

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Source-scan tests verifying color token usage and design-system constraints in the
 * coordinator screen composables.
 *
 * These tests read the source files as text — they don't compile or run Compose.
 * They enforce the design rules documented in DESIGN.md §7.5 and §8 Screen 5:
 *   - Branch cards use CoordCard + AccentCoord, not Abyss or blue
 *   - CoordinatorScreen background is CoordBg, never Abyss
 *   - The ONE permitted gradient is the coordinator title (Brush.linearGradient)
 */
class BranchCardColorTest {

    // ── BranchCard.kt source ─────────────────────────────────────────────────

    private val branchCardSource = File(
        "src/main/kotlin/com/vela/app/ui/coordinator/BranchCard.kt"
    )

    @Test fun `BranchCard source file exists`() {
        assertThat(branchCardSource.exists()).isTrue()
    }

    @Test fun `BranchCard uses VelaColors CoordCard as card container fill`() {
        assertThat(branchCardSource.readText()).contains("VelaColors.CoordCard")
    }

    @Test fun `BranchCard uses VelaColors AccentCoord for border`() {
        assertThat(branchCardSource.readText()).contains("VelaColors.AccentCoord")
    }

    @Test fun `BranchCard corner radius is uniform 16dp not asymmetric`() {
        val source = branchCardSource.readText()
        // Uniform: RoundedCornerShape(16.dp) — must be present
        assertThat(source).contains("16.dp")
        // Asymmetric pair (24dp leading + 8dp trailing) must NOT appear together
        assertThat(source).doesNotContain("24.dp")
    }

    @Test fun `BranchCard does not use forbidden blue accent color`() {
        assertThat(branchCardSource.readText()).doesNotContain("0xFF4A9EF5")
    }

    @Test fun `BranchCard does not contain Brush linearGradient`() {
        // Gradient is permitted ONLY in CoordinatorScreen for the title text.
        // BranchCard must have no gradients.
        assertThat(branchCardSource.readText()).doesNotContain("linearGradient")
    }

    // ── CoordinatorScreen.kt source ──────────────────────────────────────────

    private val coordScreenSource = File(
        "src/main/kotlin/com/vela/app/ui/coordinator/CoordinatorScreen.kt"
    )

    @Test fun `CoordinatorScreen source file exists`() {
        assertThat(coordScreenSource.exists()).isTrue()
    }

    @Test fun `CoordinatorScreen uses VelaColors CoordBg as Surface color`() {
        assertThat(coordScreenSource.readText()).contains("VelaColors.CoordBg")
    }

    @Test fun `CoordinatorScreen does not use VelaColors Abyss as Surface color`() {
        // Abyss must not appear as the screen-level surface — that would be
        // the wrong temperature. CoordBg is the required background.
        val source = coordScreenSource.readText()
        // The string "CoordBg" must appear (checked above); Abyss must not be
        // used as a Surface color argument.
        assertThat(source).doesNotContain("color = VelaColors.Abyss")
    }

    @Test fun `CoordinatorScreen title uses Brush linearGradient`() {
        // This is the ONE permitted gradient in the entire app (DESIGN.md §8 Screen 5).
        assertThat(coordScreenSource.readText()).contains("Brush.linearGradient")
    }

    @Test fun `CoordinatorScreen gradient includes both Accent and AccentCoord`() {
        val source = coordScreenSource.readText()
        assertThat(source).contains("VelaColors.Accent")
        assertThat(source).contains("VelaColors.AccentCoord")
    }

    @Test fun `CoordinatorScreen does not use forbidden blue accent color`() {
        assertThat(coordScreenSource.readText()).doesNotContain("0xFF4A9EF5")
    }

    @Test fun `CoordinatorScreen back chevron is tinted AccentCoord`() {
        val source = coordScreenSource.readText()
        // Back chevron tint must be AccentCoord — confirmed by checking tint param
        assertThat(source).contains("tint = VelaColors.AccentCoord")
    }
}
```

### Step 2: Run tests to verify they all fail

```bash
cd /Users/ken/workspace/vela
./gradlew :app:testDebugUnitTest \
  --tests "com.vela.app.ui.coordinator.BranchCardColorTest" \
  -x lint 2>&1 | tail -25
```

Expected: FAILED — all tests fail (neither source file exists yet)

### Step 3: Write BranchCard.kt

Create `app/src/main/kotlin/com/vela/app/ui/coordinator/BranchCard.kt`:

```kotlin
package com.vela.app.ui.coordinator

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vela.app.ui.theme.MonoMedium
import com.vela.app.ui.theme.VelaColors

@Composable
internal fun BranchCard(
    branch: CoordinatorViewModel.BranchState,
    onViewSession: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(cardBackground(branch))
            .border(
                width  = 1.dp,
                color  = VelaColors.AccentCoord.copy(alpha = 0.14f),
                shape  = shape,
            )
            .padding(start = 18.dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
    ) {
        Column {
            // Node name row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier          = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text   = "🖥 ${branch.nodeName}",
                    style  = MonoMedium.copy(fontSize = 12.sp),
                    fontWeight = FontWeight.SemiBold,
                    color  = VelaColors.TextPrimary,
                    modifier = Modifier.weight(1f),
                )
                if (branch.sessionId != null) {
                    Text(
                        text     = "view session →",
                        color    = VelaColors.AccentCoord,
                        fontSize = 10.sp,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .clickable { onViewSession(branch.sessionId) },
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Steps
            branch.steps.forEach { step ->
                BranchStepRow(step)
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun BranchStepRow(step: CoordinatorViewModel.BranchStep) {
    val (prefix, color) = when (step.status) {
        CoordinatorViewModel.StepStatus.DONE    -> "✓" to VelaColors.TextSecondary
        CoordinatorViewModel.StepStatus.RUNNING -> "⟳" to VelaColors.Running
        CoordinatorViewModel.StepStatus.WAITING -> "○" to VelaColors.TextTertiary
    }
    Text(
        text     = "$prefix  ${step.description}",
        color    = color,
        fontSize = 10.5.sp,
    )
}

/**
 * Tonal fill for the branch card based on the branch's active status.
 *
 * Running: color-mix(RunningContainer 30%, CoordCard) — warm amber wash
 * Done:    color-mix(DoneContainer 25%, CoordCard)    — cool sage wash
 * Waiting: CoordCard unchanged
 */
internal fun cardBackground(branch: CoordinatorViewModel.BranchState): Color {
    val steps = branch.steps
    return when {
        steps.any { it.status == CoordinatorViewModel.StepStatus.RUNNING } ->
            Color(0xFF1E1508) // amber-tinted coordinator card
        steps.all { it.status == CoordinatorViewModel.StepStatus.DONE } ->
            Color(0xFF152120) // sage-tinted coordinator card
        else -> VelaColors.CoordCard
    }
}
```

### Step 4: Run tests — expect BranchCard tests GREEN, CoordinatorScreen tests RED

```bash
cd /Users/ken/workspace/vela
./gradlew :app:testDebugUnitTest \
  --tests "com.vela.app.ui.coordinator.BranchCardColorTest" \
  -x lint 2>&1 | tail -25
```

Expected output: BranchCard tests (6 tests) pass. CoordinatorScreen tests (6 tests) fail with `"src/main/kotlin/com/vela/app/ui/coordinator/CoordinatorScreen.kt" does not exist`.

### Step 5: Commit

```bash
git add app/src/main/kotlin/com/vela/app/ui/coordinator/BranchCard.kt \
        app/src/test/kotlin/com/vela/app/ui/coordinator/BranchCardColorTest.kt
git commit -m "feat(coordinator): add BranchCard composable and BranchCardColorTest source-scan suite"
```

---

## Task 3: CoordinatorScreen

**Files:**
- Create: `app/src/main/kotlin/com/vela/app/ui/coordinator/CoordinatorScreen.kt`

The 6 `BranchCardColorTest` tests for `CoordinatorScreen.kt` are already written and RED. Writing this file turns them green.

### Step 1: Confirm the remaining failing tests

```bash
cd /Users/ken/workspace/vela
./gradlew :app:testDebugUnitTest \
  --tests "com.vela.app.ui.coordinator.BranchCardColorTest" \
  -x lint 2>&1 | grep "FAILED\|CoordinatorScreen"
```

Expected: 6 CoordinatorScreen tests fail with file-not-found.

### Step 2: Write CoordinatorScreen.kt

Create `app/src/main/kotlin/com/vela/app/ui/coordinator/CoordinatorScreen.kt`:

```kotlin
package com.vela.app.ui.coordinator

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.vela.app.ui.navigation.Routes
import com.vela.app.ui.theme.VelaColors

@Composable
fun CoordinatorScreen(
    navController: NavController,
    viewModel: CoordinatorViewModel = hiltViewModel(),
) {
    val branches by viewModel.branches.collectAsState()

    // The entire background shifts to CoordBg — the teal temperature change
    // is the primary signal that you are in coordinator mode.
    Surface(
        color    = VelaColors.CoordBg,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            CoordinatorAppBar(onBack = { navController.popBackStack() })
            CoordinatorStrip(
                nodeCount   = branches.size,
                currentStep = viewModel.currentStep,
                totalSteps  = viewModel.totalSteps,
            )
            LazyColumn(
                contentPadding    = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
                modifier          = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(branches) { index, branch ->
                    BranchCard(
                        branch         = branch,
                        onViewSession  = { branchSessionId ->
                            navController.navigate(Routes.sessionDetail(branchSessionId))
                        },
                    )
                    if (index < branches.lastIndex) {
                        Spacer(Modifier.height(6.dp))
                        ConnectorLabel(label = "parallel · branch ${index + 2}")
                        Spacer(Modifier.height(6.dp))
                    } else {
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }
        }
    }
}

// ── App bar ───────────────────────────────────────────────────────────────────

@Composable
private fun CoordinatorAppBar(onBack: () -> Unit) {
    // The gradient on the title text is the ONE permitted gradient in the app
    // (DESIGN.md §8 Screen 5). It runs from Accent (#5EEAD4) to AccentCoord (#1FE0C2).
    val titleGradient = Brush.linearGradient(
        colors = listOf(VelaColors.Accent, VelaColors.AccentCoord),
    )
    Row(
        modifier           = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment  = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector  = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint         = VelaColors.AccentCoord,
            )
        }
        Text(
            text     = "Coordinator",
            style    = MaterialTheme.typography.displayMedium.merge(
                TextStyle(brush = titleGradient)
            ),
            modifier = Modifier.weight(1f),
        )
        CoordinatorBadge()
    }
}

@Composable
private fun CoordinatorBadge() {
    Surface(
        color = VelaColors.AccentCoord.copy(alpha = 0.12f),
        shape = RoundedCornerShape(50),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier          = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(VelaColors.AccentCoord),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text       = "coordinator",
                color      = VelaColors.AccentCoord,
                fontSize   = 10.sp,
                fontWeight = FontWeight(600),
            )
        }
    }
}

// ── Coordinator strip ─────────────────────────────────────────────────────────

@Composable
private fun CoordinatorStrip(nodeCount: Int, currentStep: Int, totalSteps: Int) {
    Surface(
        color    = VelaColors.AccentCoord.copy(alpha = 0.06f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier            = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment   = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text       = "◉  Orchestrating $nodeCount nodes · step $currentStep",
                color      = VelaColors.AccentCoord,
                fontSize   = 11.sp,
                fontWeight = FontWeight(600),
            )
            StepPips(current = currentStep, total = totalSteps)
        }
    }
}

@Composable
private fun StepPips(current: Int, total: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(total) { index ->
            val pipIndex = index + 1
            val pipColor = when {
                pipIndex < current  -> VelaColors.Done
                pipIndex == current -> VelaColors.Running
                else                -> VelaColors.SurfaceRaised
            }
            Box(
                modifier = Modifier
                    .size(width = 14.dp, height = 3.dp)
                    .clip(RoundedCornerShape(50))
                    .background(pipColor),
            )
        }
    }
}

// ── Connector label ───────────────────────────────────────────────────────────

@Composable
private fun ConnectorLabel(label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier          = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        HorizontalDivider(
            color    = VelaColors.AccentCoord.copy(alpha = 0.18f),
            modifier = Modifier.weight(1f),
        )
        Text(
            text     = label,
            color    = VelaColors.TextTertiary,
            fontSize = 9.5.sp,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        HorizontalDivider(
            color    = VelaColors.AccentCoord.copy(alpha = 0.18f),
            modifier = Modifier.weight(1f),
        )
    }
}
```

### Step 3: Run all coordinator tests — expect full GREEN

```bash
cd /Users/ken/workspace/vela
./gradlew :app:testDebugUnitTest \
  --tests "com.vela.app.ui.coordinator.*" \
  -x lint 2>&1 | tail -25
```

Expected: BUILD SUCCESSFUL — all 28 tests passing (15 ViewModel + 13 color source-scan)

### Step 4: Commit

```bash
git add app/src/main/kotlin/com/vela/app/ui/coordinator/CoordinatorScreen.kt
git commit -m "feat(coordinator): add CoordinatorScreen with CoordBg surface, gradient title, branch list, and connector labels"
```

---

## Task 4: Wire CoordinatorScreen into AppNavigation

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/ui/navigation/AppNavigation.kt`

Replace the Phase 1 `CoordinatorPlaceholder` with the real `CoordinatorScreen`. Two edits to `AppNavigation.kt`:

1. Replace the placeholder call in the `NavHost` block.
2. Delete the private `CoordinatorPlaceholder` function body.

### Step 1: Edit AppNavigation.kt

Open `app/src/main/kotlin/com/vela/app/ui/navigation/AppNavigation.kt`.

**Edit 1** — replace the composable destination (find this exact line):

```kotlin
            composable(Routes.COORDINATOR)    { CoordinatorPlaceholder(navController) }
```

Replace with:

```kotlin
            composable(Routes.COORDINATOR)    { com.vela.app.ui.coordinator.CoordinatorScreen(navController) }
```

**Edit 2** — delete the now-dead placeholder function. Find and remove this entire block:

```kotlin
@Composable
private fun CoordinatorPlaceholder(navController: NavController) {
    Surface(modifier = Modifier.fillMaxSize(), color = VelaColors.CoordBg) {
        Text(text = "Coordinator", color = VelaColors.TextPrimary)
    }
}
```

### Step 2: Run the full coordinator test suite

```bash
cd /Users/ken/workspace/vela
./gradlew :app:testDebugUnitTest \
  --tests "com.vela.app.ui.coordinator.*" \
  -x lint 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL — 28 tests passing

### Step 3: Verify the app still compiles

```bash
cd /Users/ken/workspace/vela
./gradlew :app:assembleDebug -x lint 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL

### Step 4: Commit

```bash
git add app/src/main/kotlin/com/vela/app/ui/navigation/AppNavigation.kt
git commit -m "feat(coordinator): wire CoordinatorScreen into NavHost, remove CoordinatorPlaceholder"
```

---

## Implementation Notes for the Implementer

### The temperature-shift principle
The entire point of `CoordBg` (`#0C1E26`) is that you **feel** the cooler teal surface the moment you enter this screen. Do not dilute this by accidentally using `VelaColors.Abyss` anywhere as a surface color. The `BranchCardColorTest` will catch this.

### The one permitted gradient
`Brush.linearGradient(listOf(VelaColors.Accent, VelaColors.AccentCoord))` appears **only** on the coordinator title `Text`. No other composable in the coordinator package (or anywhere in the app) uses a gradient. The source-scan test enforces this on `BranchCard.kt`.

### `cardBackground()` is `internal` — this is intentional
The function is exposed as `internal` so future tests can verify it directly without needing a source scan. Don't change it to `private`.

### `HorizontalDivider` not `Divider`
The project uses Compose BOM 2025.04.01. `Divider` is deprecated — use `HorizontalDivider` from `androidx.compose.material3`.

### `TextStyle.merge()` for the gradient title
`MaterialTheme.typography.displayMedium.merge(TextStyle(brush = titleGradient))` is the correct pattern for applying a gradient brush while preserving all other typographic properties (Instrument Serif family, 36sp size, line height). Do not construct a bare `TextStyle(brush = ...)` and lose the font family.

### Navigation from "view session →"
`onViewSession = { branchSessionId -> navController.navigate(Routes.sessionDetail(branchSessionId)) }` — this relies on `Routes.sessionDetail()` which was defined in Phase 3. Do not re-declare it.
