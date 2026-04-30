# UI Phase 3: Session List & Session Detail Screens Implementation Plan

> **Execution:** Use the subagent-driven-development workflow to implement this plan.

**Goal:** Build Screen 3 (SessionListScreen — Project Detail with session cards) and Screen 4 (SessionDetailScreen — Turn History) by replacing the Phase 1 NavHost placeholders with design-system-correct Compose screens, backed by `@HiltViewModel`s.

**Architecture:** Four new packages — `com.vela.app.ui.sessionlist` and `com.vela.app.ui.sessiondetail`. Session models (`SessionSummary`, `SessionStatus`, `TurnContent`, `ToolCall`) live in a single `SessionModels.kt` in the `sessiondetail` package and are imported by the session-list package. `SessionCard.kt` encapsulates all card-specific color-mapping pure functions (unit-testable on the JVM) alongside the `@Composable` component. Both ViewModels use `SavedStateHandle` for nav args; the amplifierd HTTP API does not exist yet so sessions are stubs. After both screens pass their tests, `AppNavigation.kt` replaces the two placeholders.

**Tech Stack:** Kotlin, Jetpack Compose (BOM 2025.04.01), Material 3, Navigation Compose 2.7.7, Hilt, JUnit 4, Google Truth 1.4.2, `kotlinx.coroutines.test` 1.8.0

---

## Phase 2 Contract — What Already Exists

Do **not** redefine any of these.

| Symbol | Location | Purpose |
|--------|----------|---------|
| `VelaColors.Abyss/SurfaceSub/SurfaceRaised/SurfacePeak` | `com.vela.app.ui.theme.VelaColors` | Surface tones |
| `VelaColors.Running/Waiting/Done/Error/Accent` | `com.vela.app.ui.theme.VelaColors` | Semantic status colors |
| `VelaColors.RunningContainer/WaitingContainer/DoneContainer/ErrorContainer` | `com.vela.app.ui.theme.VelaColors` | Chip fill colors |
| `VelaColors.RunningOnContainer/WaitingOnContainer/DoneOnContainer/ErrorOnContainer` | `com.vela.app.ui.theme.VelaColors` | Chip text colors |
| `VelaColors.RunningOn/WaitingOn/DoneOn/ErrorOn` | `com.vela.app.ui.theme.VelaColors` | On-status colors |
| `VelaColors.TextPrimary/TextSecondary/TextTertiary/TextDisabled` | `com.vela.app.ui.theme.VelaColors` | Text hierarchy |
| `VelaColors.StrokeHair/StrokeEdge` | `com.vela.app.ui.theme.VelaColors` | `0x0FFFFFFF` / `0x1FFFFFFF` |
| `MaterialTheme.typography.displayMedium` | `com.vela.app.ui.theme.VelaTypography` | Instrument Serif 36sp (session title hero) |
| `MaterialTheme.typography.titleLarge` | `com.vela.app.ui.theme.VelaTypography` | Inter 18sp/600 (app bar + card titles) |
| `MaterialTheme.typography.labelLarge` | `com.vela.app.ui.theme.VelaTypography` | Inter 14sp/600 (button labels) |
| `MaterialTheme.typography.labelSmall` | `com.vela.app.ui.theme.VelaTypography` | Inter 11sp/Bold/+2sp tracking (chips, eyebrows) |
| `MaterialTheme.typography.bodyLarge` | `com.vela.app.ui.theme.VelaTypography` | Inter 16sp (agent turn body text) |
| `MaterialTheme.typography.bodyMedium` | `com.vela.app.ui.theme.VelaTypography` | Inter 14sp (card meta: model, step count) |
| `MonoMedium` | `com.vela.app.ui.theme` (top-level) | JetBrains Mono 13sp (tool names, results) |
| `InstrumentSerifFamily` | `com.vela.app.ui.theme` (top-level) | Instrument Serif font family |
| `Routes.SESSION_LIST` | `com.vela.app.ui.navigation.Routes` | `"session_list/{nodeId}/{projectId}"` |
| `Routes.SESSION_DETAIL` | `com.vela.app.ui.navigation.Routes` | `"session_detail/{sessionId}"` |
| `Routes.sessionList(nodeId, projectId)` | `com.vela.app.ui.navigation.Routes` | Route builder |
| `Routes.sessionDetail(sessionId)` | `com.vela.app.ui.navigation.Routes` | Route builder |
| `SshNodeRegistry` | `com.vela.app.ssh.SshNodeRegistry` | Required by `SessionListViewModel` constructor |
| `SshNodeDao` | `com.vela.app.data.db.SshNodeDao` | Interface — implement for fakes in tests |
| `SshNodeEntity` | `com.vela.app.data.db.SshNodeEntity` | `id`, `label`, `hosts`, `port`, `username`, `addedAt`, `nodeType`, `url`, `token`, `bootstrapStatus` |

**AppNavigation.kt shape after Phase 2** — the two lines this plan replaces:
```kotlin
composable(Routes.SESSION_LIST)   { SessionListPlaceholder(navController) }
composable(Routes.SESSION_DETAIL) { SessionDetailPlaceholder(navController) }
```

---

## Anti-Pattern Checklist

Before writing any code, engrave these rules:

- **NO left-border-only stripes.** Session cards use M3 tonal fills (tinted background) — NOT a leading `Modifier.border(start = …)`.
- **NO chat bubbles.** User turns in SessionDetail are right-aligned `Text` with a 2dp trailing cyan line. No bubble shape, no card background.
- **NO bottom nav bar, no tab row.**
- **NO Instrument Serif below 22sp.** Session card titles use `titleLarge` (Inter 18sp/600), not serif.
- **NO blue** (`#3B82F6` forbidden). Accent is cyan `#5EEAD4`.
- **NO illustrations in empty state.** Typography + button only.

---

## New Files Summary

| Action | Path |
|--------|------|
| Create | `app/src/main/kotlin/com/vela/app/ui/sessiondetail/SessionModels.kt` |
| Create | `app/src/main/kotlin/com/vela/app/ui/sessionlist/SessionListViewModel.kt` |
| Create | `app/src/main/kotlin/com/vela/app/ui/sessionlist/SessionCard.kt` |
| Create | `app/src/main/kotlin/com/vela/app/ui/sessionlist/SessionListScreen.kt` |
| Create | `app/src/main/kotlin/com/vela/app/ui/sessiondetail/SessionDetailViewModel.kt` |
| Create | `app/src/main/kotlin/com/vela/app/ui/sessiondetail/TurnItems.kt` |
| Create | `app/src/main/kotlin/com/vela/app/ui/sessiondetail/SessionDetailScreen.kt` |
| Create | `app/src/test/kotlin/com/vela/app/ui/sessionlist/SessionListViewModelTest.kt` |
| Create | `app/src/test/kotlin/com/vela/app/ui/sessionlist/SessionCardColorTest.kt` |
| Create | `app/src/test/kotlin/com/vela/app/ui/sessiondetail/SessionDetailViewModelTest.kt` |
| Modify | `app/src/main/kotlin/com/vela/app/ui/navigation/AppNavigation.kt` |
| Modify | `app/src/test/kotlin/com/vela/app/ui/navigation/AppNavigationTest.kt` |

---

## Task 1: SessionModels Data Classes

**Files:**
- Create: `app/src/main/kotlin/com/vela/app/ui/sessiondetail/SessionModels.kt`
- Create: `app/src/test/kotlin/com/vela/app/ui/sessionlist/SessionListViewModelTest.kt` (structural test only — ViewModel added in Task 2)

### Step 1: Write the failing structural test

Create `app/src/test/kotlin/com/vela/app/ui/sessionlist/SessionListViewModelTest.kt`:

```kotlin
package com.vela.app.ui.sessionlist

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SessionListViewModelTest {

    // ── Structural: verify models exist ──────────────────────────────────────────

    @Test fun `SessionModels source file contains SessionSummary`() {
        val src = java.io.File(
            "src/main/kotlin/com/vela/app/ui/sessiondetail/SessionModels.kt"
        ).readText()
        assertThat(src).contains("data class SessionSummary")
    }

    @Test fun `SessionModels source file contains SessionStatus enum`() {
        val src = java.io.File(
            "src/main/kotlin/com/vela/app/ui/sessiondetail/SessionModels.kt"
        ).readText()
        assertThat(src).contains("enum class SessionStatus")
    }

    @Test fun `SessionModels source file contains TurnContent`() {
        val src = java.io.File(
            "src/main/kotlin/com/vela/app/ui/sessiondetail/SessionModels.kt"
        ).readText()
        assertThat(src).contains("data class TurnContent")
    }

    @Test fun `SessionModels source file contains ToolCall`() {
        val src = java.io.File(
            "src/main/kotlin/com/vela/app/ui/sessiondetail/SessionModels.kt"
        ).readText()
        assertThat(src).contains("data class ToolCall")
    }
}
```

### Step 2: Run to verify it fails

Run from `/Users/ken/workspace/vela`:
```bash
./gradlew :app:testDebugUnitTest \
  --tests "com.vela.app.ui.sessionlist.SessionListViewModelTest" \
  2>&1 | tail -20
```
Expected: **BUILD FAILED** — file does not exist.

### Step 3: Create SessionModels.kt

Create `app/src/main/kotlin/com/vela/app/ui/sessiondetail/SessionModels.kt`:

```kotlin
package com.vela.app.ui.sessiondetail

// ── Session List models ───────────────────────────────────────────────────────

/**
 * Summary of a session for display in the session list.
 * Data source: amplifierd HTTP API (placeholder — emptyList() for now).
 */
data class SessionSummary(
    val id: String,
    val title: String,
    val status: SessionStatus,
    val modelName: String,
    val stepCount: Int,
    val lastActiveMs: Long,
)

enum class SessionStatus { RUNNING, WAITING, DONE, ERROR }

// ── Session Detail models ─────────────────────────────────────────────────────

/**
 * A single turn in the session turn history.
 * [isUser] = true for user prompts, false for agent responses.
 */
data class TurnContent(
    val text: String,
    val toolCalls: List<ToolCall> = emptyList(),
    val isUser: Boolean,
)

/**
 * A tool invocation within an agent turn.
 * Shows tool name, optional result, duration, and live/done state.
 */
data class ToolCall(
    val name: String,
    val result: String? = null,
    val isDone: Boolean,
    val isRunning: Boolean,
    val durationMs: Long? = null,
)
```

### Step 4: Run to verify it passes

```bash
./gradlew :app:testDebugUnitTest \
  --tests "com.vela.app.ui.sessionlist.SessionListViewModelTest" \
  2>&1 | tail -15
```
Expected: `BUILD SUCCESSFUL` — 4 tests pass.

### Step 5: Commit

```bash
git add \
  app/src/main/kotlin/com/vela/app/ui/sessiondetail/SessionModels.kt \
  app/src/test/kotlin/com/vela/app/ui/sessionlist/SessionListViewModelTest.kt
git commit -m "feat(session-list): add SessionSummary, SessionStatus, TurnContent, ToolCall data models"
```

---

## Task 2: SessionListViewModel TDD

**Files:**
- Modify: `app/src/test/kotlin/com/vela/app/ui/sessionlist/SessionListViewModelTest.kt`
- Create: `app/src/main/kotlin/com/vela/app/ui/sessionlist/SessionListViewModel.kt`

### Step 1: Append ViewModel tests to SessionListViewModelTest.kt

Open `app/src/test/kotlin/com/vela/app/ui/sessionlist/SessionListViewModelTest.kt`.

Add these imports at the top of the file:

```kotlin
import androidx.lifecycle.SavedStateHandle
import com.vela.app.data.db.SshNodeDao
import com.vela.app.data.db.SshNodeEntity
import com.vela.app.ssh.SshNodeRegistry
import com.vela.app.ui.sessiondetail.SessionSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
```

Add the `@OptIn` annotation to the class and these members inside the class (after the existing structural tests):

```kotlin
    @OptIn(ExperimentalCoroutinesApi::class)
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before fun setUp()    { Dispatchers.setMain(testDispatcher) }
    @After  fun tearDown() { Dispatchers.resetMain() }

    // ── Fake DAO ──────────────────────────────────────────────────────────────────

    private class FakeSshNodeDao : SshNodeDao {
        val nodeFlow = MutableStateFlow<List<SshNodeEntity>>(emptyList())
        override fun getAllNodes(): Flow<List<SshNodeEntity>> = nodeFlow
        override suspend fun insert(node: SshNodeEntity) {}
        override suspend fun delete(id: String) {}
        override suspend fun getById(id: String): SshNodeEntity? = null
        override suspend fun updateBootstrapStatus(id: String, status: String) {}
        override suspend fun promoteToAmplifierd(
            id: String, type: String, url: String, token: String, status: String,
        ) {}
    }

    private fun makeVm(
        nodeId: String = "node-1",
        projectId: String = "proj-1",
        dao: FakeSshNodeDao = FakeSshNodeDao(),
    ): SessionListViewModel {
        val savedState = SavedStateHandle(mapOf("nodeId" to nodeId, "projectId" to projectId))
        return SessionListViewModel(savedState, SshNodeRegistry(dao))
    }

    // ── Tests ─────────────────────────────────────────────────────────────────────

    @Test fun `nodeId is read from SavedStateHandle`() {
        val vm = makeVm(nodeId = "node-42")
        assertThat(vm.nodeId).isEqualTo("node-42")
    }

    @Test fun `projectId is read from SavedStateHandle`() {
        val vm = makeVm(projectId = "proj-99")
        assertThat(vm.projectId).isEqualTo("proj-99")
    }

    @Test fun `activeSessions initial value is empty list`() = runTest {
        val vm = makeVm()
        assertThat(vm.activeSessions.value).isEmpty()
    }

    @Test fun `recentSessions initial value is empty list`() = runTest {
        val vm = makeVm()
        assertThat(vm.recentSessions.value).isEmpty()
    }
```

Also add the `@OptIn(ExperimentalCoroutinesApi::class)` annotation to the class declaration:

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
class SessionListViewModelTest {
```

### Step 2: Run to verify the new tests fail

```bash
./gradlew :app:testDebugUnitTest \
  --tests "com.vela.app.ui.sessionlist.SessionListViewModelTest.nodeId is read from SavedStateHandle" \
  2>&1 | tail -20
```
Expected: **BUILD FAILED** — `error: unresolved reference: SessionListViewModel`

### Step 3: Create SessionListViewModel.kt

Create `app/src/main/kotlin/com/vela/app/ui/sessionlist/SessionListViewModel.kt`:

```kotlin
package com.vela.app.ui.sessionlist

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.vela.app.ssh.SshNodeRegistry
import com.vela.app.ui.sessiondetail.SessionSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * ViewModel for Screen 3: Project Detail — Sessions List.
 *
 * Sessions are served by the amplifierd HTTP API which does not exist yet.
 * [activeSessions] and [recentSessions] are empty placeholder StateFlows until
 * the API client is wired in a future phase.
 */
@HiltViewModel
class SessionListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val registry: SshNodeRegistry,
) : ViewModel() {

    val nodeId: String    = checkNotNull(savedStateHandle["nodeId"])
    val projectId: String = checkNotNull(savedStateHandle["projectId"])

    /** Sessions currently RUNNING or WAITING — placeholder: empty until HTTP API exists. */
    val activeSessions: StateFlow<List<SessionSummary>> = MutableStateFlow(emptyList())

    /** Sessions with status DONE or ERROR — placeholder: empty until HTTP API exists. */
    val recentSessions: StateFlow<List<SessionSummary>> = MutableStateFlow(emptyList())
}
```

### Step 4: Run all SessionListViewModelTest tests to verify they pass

```bash
./gradlew :app:testDebugUnitTest \
  --tests "com.vela.app.ui.sessionlist.SessionListViewModelTest" \
  2>&1 | tail -15
```
Expected: `BUILD SUCCESSFUL` — 8 tests pass (4 structural + 4 ViewModel).

### Step 5: Commit

```bash
git add \
  app/src/main/kotlin/com/vela/app/ui/sessionlist/SessionListViewModel.kt \
  app/src/test/kotlin/com/vela/app/ui/sessionlist/SessionListViewModelTest.kt
git commit -m "feat(session-list): add SessionListViewModel with nodeId/projectId from SavedStateHandle"
```

---

## Task 3: SessionCard Color Logic TDD

**Files:**
- Create: `app/src/test/kotlin/com/vela/app/ui/sessionlist/SessionCardColorTest.kt`
- Create: `app/src/main/kotlin/com/vela/app/ui/sessionlist/SessionCard.kt` (pure-logic stub only — no `@Composable` yet)

These pure functions are testable on the JVM because `androidx.compose.ui.graphics.Color` is a Kotlin value class with no Android runtime calls.

### Step 1: Write the failing test

Create `app/src/test/kotlin/com/vela/app/ui/sessionlist/SessionCardColorTest.kt`:

```kotlin
package com.vela.app.ui.sessionlist

import androidx.compose.ui.graphics.Color
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

    // ── cardBackgroundFor ─────────────────────────────────────────────────────────
    // M3 tonal fills: color-mix(StatusContainer, SurfaceSub) per DESIGN.md §7.2
    // Hardcoded approximations — see SessionCard.kt for exact hex rationale.

    @Test fun `cardBackground for RUNNING is amber-tinted surface`() {
        assertThat(cardBackgroundFor(SessionStatus.RUNNING).toArgb())
            .isEqualTo(Color(0xFF1C1A0E).toArgb())
    }

    @Test fun `cardBackground for WAITING is violet-tinted surface`() {
        assertThat(cardBackgroundFor(SessionStatus.WAITING).toArgb())
            .isEqualTo(Color(0xFF1A1234).toArgb())
    }

    @Test fun `cardBackground for DONE is default SurfaceSub`() {
        assertThat(cardBackgroundFor(SessionStatus.DONE).toArgb())
            .isEqualTo(VelaColors.SurfaceSub.toArgb())
    }

    @Test fun `cardBackground for ERROR is coral-tinted surface`() {
        assertThat(cardBackgroundFor(SessionStatus.ERROR).toArgb())
            .isEqualTo(Color(0xFF1C1117).toArgb())
    }

    // ── chipContainerFor ──────────────────────────────────────────────────────────

    @Test fun `chipContainer for RUNNING is RunningContainer`() {
        assertThat(chipContainerFor(SessionStatus.RUNNING).toArgb())
            .isEqualTo(VelaColors.RunningContainer.toArgb())
    }

    @Test fun `chipContainer for WAITING is WaitingContainer`() {
        assertThat(chipContainerFor(SessionStatus.WAITING).toArgb())
            .isEqualTo(VelaColors.WaitingContainer.toArgb())
    }

    @Test fun `chipContainer for DONE is DoneContainer`() {
        assertThat(chipContainerFor(SessionStatus.DONE).toArgb())
            .isEqualTo(VelaColors.DoneContainer.toArgb())
    }

    @Test fun `chipContainer for ERROR is ErrorContainer`() {
        assertThat(chipContainerFor(SessionStatus.ERROR).toArgb())
            .isEqualTo(VelaColors.ErrorContainer.toArgb())
    }

    // ── chipOnContainerFor ────────────────────────────────────────────────────────

    @Test fun `chipOnContainer for RUNNING is RunningOnContainer`() {
        assertThat(chipOnContainerFor(SessionStatus.RUNNING).toArgb())
            .isEqualTo(VelaColors.RunningOnContainer.toArgb())
    }

    @Test fun `chipOnContainer for WAITING is WaitingOnContainer`() {
        assertThat(chipOnContainerFor(SessionStatus.WAITING).toArgb())
            .isEqualTo(VelaColors.WaitingOnContainer.toArgb())
    }

    @Test fun `chipOnContainer for DONE is DoneOnContainer`() {
        assertThat(chipOnContainerFor(SessionStatus.DONE).toArgb())
            .isEqualTo(VelaColors.DoneOnContainer.toArgb())
    }

    @Test fun `chipOnContainer for ERROR is ErrorOnContainer`() {
        assertThat(chipOnContainerFor(SessionStatus.ERROR).toArgb())
            .isEqualTo(VelaColors.ErrorOnContainer.toArgb())
    }
}
```

### Step 2: Run to verify it fails

```bash
./gradlew :app:testDebugUnitTest \
  --tests "com.vela.app.ui.sessionlist.SessionCardColorTest" \
  2>&1 | tail -20
```
Expected: **BUILD FAILED** — `error: unresolved reference: cardBackgroundFor`

### Step 3: Create SessionCard.kt with the pure-logic functions

Create `app/src/main/kotlin/com/vela/app/ui/sessionlist/SessionCard.kt`:

```kotlin
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
```

### Step 4: Run to verify it passes

```bash
./gradlew :app:testDebugUnitTest \
  --tests "com.vela.app.ui.sessionlist.SessionCardColorTest" \
  2>&1 | tail -15
```
Expected: `BUILD SUCCESSFUL` — 12 tests pass.

### Step 5: Commit

```bash
git add \
  app/src/main/kotlin/com/vela/app/ui/sessionlist/SessionCard.kt \
  app/src/test/kotlin/com/vela/app/ui/sessionlist/SessionCardColorTest.kt
git commit -m "feat(session-list): add session card color-mapping logic (cardBackgroundFor, chipContainerFor, chipOnContainerFor)"
```

---

## Task 4: SessionCard Composable

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/ui/sessionlist/SessionCard.kt`
- Modify: `app/src/test/kotlin/com/vela/app/ui/sessionlist/SessionCardColorTest.kt`

### Step 1: Add the structural source-inspection test to SessionCardColorTest.kt

Append this test inside the `SessionCardColorTest` class (before the closing `}`):

```kotlin
    // ── Structural: verify composable exists ──────────────────────────────────────

    @Test fun `SessionCard source contains SessionCard composable`() {
        val src = java.io.File(
            "src/main/kotlin/com/vela/app/ui/sessionlist/SessionCard.kt"
        ).readText()
        assertThat(src).contains("fun SessionCard")
    }
```

### Step 2: Run to verify this test fails

```bash
./gradlew :app:testDebugUnitTest \
  --tests "com.vela.app.ui.sessionlist.SessionCardColorTest.SessionCard source contains SessionCard composable" \
  2>&1 | tail -15
```
Expected: **FAILED** — source does not contain `fun SessionCard`.

### Step 3: Append the SessionCard composable to SessionCard.kt

Add the following imports and the `SessionCard` composable to the **bottom** of `app/src/main/kotlin/com/vela/app/ui/sessionlist/SessionCard.kt`. Keep all existing content.

```kotlin

// ── Composable ────────────────────────────────────────────────────────────────

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vela.app.ui.sessiondetail.SessionSummary
import com.vela.app.ui.sessiondetail.SessionStatus
import com.vela.app.ui.theme.MonoMedium
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Session card for Screen 3 — Project Detail (Sessions List).
 *
 * Design: DESIGN.md §7.2
 * - M3 tonal fill background (NO left border stripe)
 * - 8dp radius status chip (NOT pill)
 * - RUNNING: amber glow via drawBehind + 14dp thin spinner
 * - WAITING: 1dp violet outline around card + "▶ Decide" affordance
 * - DONE/ERROR: no animation
 */
@Composable
fun SessionCard(
    session: SessionSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isRunning = session.status == SessionStatus.RUNNING
    val isWaiting = session.status == SessionStatus.WAITING

    // Running glow animation
    val infiniteTransition = rememberInfiniteTransition(label = "sessionCardGlow")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.12f,
        targetValue  = 0.22f,
        animationSpec = infiniteRepeatable(
            animation  = tween(2400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glowAlpha",
    )

    // Running spinner rotation
    val spinnerRotation by infiniteTransition.animateFloat(
        initialValue  = 0f,
        targetValue   = 360f,
        animationSpec = infiniteRepeatable(
            animation  = tween(1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "spinnerRotation",
    )

    val borderStroke = if (isWaiting) {
        BorderStroke(1.dp, VelaColors.Waiting)
    } else {
        null
    }

    Surface(
        onClick  = onClick,
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (isRunning) Modifier.drawBehind {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                VelaColors.Running.copy(alpha = glowAlpha),
                                Color.Transparent,
                            ),
                            radius = size.maxDimension * 0.75f,
                        ),
                    )
                } else Modifier
            ),
        shape  = RoundedCornerShape(20.dp),
        color  = cardBackgroundFor(session.status),
        border = borderStroke,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {

            // ── Title row ─────────────────────────────────────────────────────
            Row(
                modifier          = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text     = session.title,
                    style    = MaterialTheme.typography.titleLarge,
                    color    = VelaColors.TextPrimary,
                    maxLines = 2,
                    modifier = Modifier.weight(1f),
                )
                if (isRunning) {
                    Spacer(Modifier.width(8.dp))
                    CircularProgressIndicator(
                        modifier    = Modifier.size(14.dp).rotate(spinnerRotation),
                        color       = VelaColors.Running,
                        strokeWidth = 1.5.dp,
                        progress    = { 0.25f },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── Meta row: model name + step count ─────────────────────────────
            Row(
                modifier          = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text  = session.modelName,
                    style = MonoMedium.copy(fontSize = 11.sp),
                    color = VelaColors.TextTertiary,
                )
                Text(
                    text  = "${session.stepCount} steps",
                    style = MaterialTheme.typography.bodyMedium,
                    color = VelaColors.TextTertiary,
                )
            }

            Spacer(Modifier.height(10.dp))

            // ── Status chip row ───────────────────────────────────────────────
            Row(
                modifier          = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // Status chip: 8dp radius, 28dp height, Inter 11sp 600
                Surface(
                    shape  = RoundedCornerShape(8.dp),
                    color  = chipContainerFor(session.status),
                    modifier = Modifier.height(28.dp),
                ) {
                    Box(
                        modifier         = Modifier.padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text  = session.status.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = chipOnContainerFor(session.status),
                        )
                    }
                }

                // Timestamp (top-right)
                Text(
                    text  = formatTimestamp(session.lastActiveMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = VelaColors.TextTertiary,
                )
            }

            // ── Waiting: "▶ Decide" affordance ───────────────────────────────
            if (isWaiting) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text  = "▶ Decide",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.5.sp),
                    color = VelaColors.Waiting,
                )
            }
        }
    }
}

private fun formatTimestamp(ms: Long): String =
    SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(ms))
```

### Step 4: Run all SessionCardColorTest tests to verify they pass

```bash
./gradlew :app:testDebugUnitTest \
  --tests "com.vela.app.ui.sessionlist.SessionCardColorTest" \
  2>&1 | tail -15
```
Expected: `BUILD SUCCESSFUL` — 13 tests pass (12 color + 1 structural).

### Step 5: Commit

```bash
git add app/src/main/kotlin/com/vela/app/ui/sessionlist/SessionCard.kt \
        app/src/test/kotlin/com/vela/app/ui/sessionlist/SessionCardColorTest.kt
git commit -m "feat(session-list): add SessionCard composable with tonal fills, chip, running glow, waiting outline"
```

---

## Task 5: SessionListScreen Composable

**Files:**
- Create: `app/src/main/kotlin/com/vela/app/ui/sessionlist/SessionListScreen.kt`
- Modify: `app/src/test/kotlin/com/vela/app/ui/sessionlist/SessionListViewModelTest.kt`

### Step 1: Add structural test to SessionListViewModelTest.kt

Append this test inside `SessionListViewModelTest` (before the closing `}`):

```kotlin
    // ── Structural: verify screen composable exists ───────────────────────────────

    @Test fun `SessionListScreen source file exists with SessionListScreen composable`() {
        val src = java.io.File(
            "src/main/kotlin/com/vela/app/ui/sessionlist/SessionListScreen.kt"
        ).readText()
        assertThat(src).contains("fun SessionListScreen")
    }
```

### Step 2: Run to verify this test fails

```bash
./gradlew :app:testDebugUnitTest \
  --tests "com.vela.app.ui.sessionlist.SessionListViewModelTest.SessionListScreen source file exists with SessionListScreen composable" \
  2>&1 | tail -10
```
Expected: **FAILED** — file does not exist.

### Step 3: Create SessionListScreen.kt

Create `app/src/main/kotlin/com/vela/app/ui/sessionlist/SessionListScreen.kt`:

```kotlin
package com.vela.app.ui.sessionlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.vela.app.ui.navigation.Routes
import com.vela.app.ui.sessiondetail.SessionStatus
import com.vela.app.ui.theme.VelaColors

/**
 * Screen 3: Project Detail — Sessions List.
 *
 * Design: DESIGN.md §8 (Screen 3)
 * Layout:
 *   - App bar: back chevron (Accent) + project name in titleLarge
 *   - "NEW SESSION" button: Accent fill, Abyss text, 52dp height, 26dp radius
 *   - "ACTIVE" eyebrow + running/waiting session cards
 *   - "RECENT" eyebrow + done/error session cards
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListScreen(
    navController: NavController,
    viewModel: SessionListViewModel = hiltViewModel(),
) {
    val activeSessions by viewModel.activeSessions.collectAsStateWithLifecycle()
    val recentSessions by viewModel.recentSessions.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = VelaColors.Abyss,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text  = "Project",  // Phase 3 placeholder — project name from API in Phase 4
                        style = MaterialTheme.typography.titleLarge,
                        color = VelaColors.TextPrimary,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector        = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint               = VelaColors.Accent,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = VelaColors.Abyss,
                ),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier      = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {

            // ── NEW SESSION button ────────────────────────────────────────────
            item {
                Button(
                    onClick  = { /* TODO: create new session via amplifierd */ },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape    = RoundedCornerShape(26.dp),
                    colors   = ButtonDefaults.buttonColors(
                        containerColor = VelaColors.Accent,
                        contentColor   = VelaColors.Abyss,
                    ),
                ) {
                    Text(
                        text       = "NEW SESSION",
                        fontWeight = FontWeight.Bold,
                        fontSize   = 14.sp,
                        letterSpacing = 1.sp,
                    )
                }
            }

            // ── ACTIVE section ────────────────────────────────────────────────
            item {
                Spacer(Modifier.height(8.dp))
                SectionEyebrow("ACTIVE")
            }

            if (activeSessions.isEmpty()) {
                item {
                    Text(
                        text  = "No active sessions",
                        style = MaterialTheme.typography.bodyMedium,
                        color = VelaColors.TextTertiary,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            } else {
                items(activeSessions, key = { it.id }) { session ->
                    SessionCard(
                        session = session,
                        onClick = {
                            navController.navigate(Routes.sessionDetail(session.id))
                        },
                    )
                }
            }

            // ── RECENT section ────────────────────────────────────────────────
            item {
                Spacer(Modifier.height(8.dp))
                SectionEyebrow("RECENT")
            }

            if (recentSessions.isEmpty()) {
                item {
                    Text(
                        text  = "No recent sessions",
                        style = MaterialTheme.typography.bodyMedium,
                        color = VelaColors.TextTertiary,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            } else {
                items(recentSessions, key = { it.id }) { session ->
                    SessionCard(
                        session = session,
                        onClick = {
                            navController.navigate(Routes.sessionDetail(session.id))
                        },
                    )
                }
            }

            // Bottom padding to clear Voice FAB
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

// ── Private sub-composables ───────────────────────────────────────────────────

/**
 * Section eyebrow label: uppercase Inter 700, TextTertiary, 2sp letter spacing.
 * Used for "ACTIVE" and "RECENT" section dividers.
 */
@Composable
private fun SectionEyebrow(text: String) {
    Text(
        text  = text,
        style = MaterialTheme.typography.labelSmall,
        color = VelaColors.TextTertiary,
    )
}
```

### Step 4: Run all SessionListViewModelTest tests to verify they pass

```bash
./gradlew :app:testDebugUnitTest \
  --tests "com.vela.app.ui.sessionlist.SessionListViewModelTest" \
  2>&1 | tail -15
```
Expected: `BUILD SUCCESSFUL` — 9 tests pass.

### Step 5: Commit

```bash
git add \
  app/src/main/kotlin/com/vela/app/ui/sessionlist/SessionListScreen.kt \
  app/src/test/kotlin/com/vela/app/ui/sessionlist/SessionListViewModelTest.kt
git commit -m "feat(session-list): add SessionListScreen with ACTIVE/RECENT sections and NEW SESSION button"
```

---

## Task 6: Wire SessionListScreen in AppNavigation

**Files:**
- Modify: `app/src/test/kotlin/com/vela/app/ui/navigation/AppNavigationTest.kt`
- Modify: `app/src/main/kotlin/com/vela/app/ui/navigation/AppNavigation.kt`

### Step 1: Add wiring test to AppNavigationTest.kt

Open `app/src/test/kotlin/com/vela/app/ui/navigation/AppNavigationTest.kt`.

Add this test inside the existing `AppNavigationTest` class:

```kotlin
    // ── Phase 3 wiring ────────────────────────────────────────────────────────────

    @Test fun `AppNavigation sources SessionListScreen (not placeholder)`() {
        val src = java.io.File(
            "src/main/kotlin/com/vela/app/ui/navigation/AppNavigation.kt"
        ).readText()
        assertThat(src).contains("SessionListScreen(navController)")
        assertThat(src).doesNotContain("SessionListPlaceholder")
    }
```

### Step 2: Run to verify this test fails

```bash
./gradlew :app:testDebugUnitTest \
  --tests "com.vela.app.ui.navigation.AppNavigationTest.AppNavigation sources SessionListScreen (not placeholder)" \
  2>&1 | tail -10
```
Expected: **FAILED** — source still contains `SessionListPlaceholder`.

### Step 3: Update AppNavigation.kt

Open `app/src/main/kotlin/com/vela/app/ui/navigation/AppNavigation.kt`.

**a) Add import** in the imports block:
```kotlin
import com.vela.app.ui.sessionlist.SessionListScreen
```

**b) Replace the SessionListPlaceholder call** in the `NavHost` block:

Find:
```kotlin
            composable(Routes.SESSION_LIST)   { SessionListPlaceholder(navController) }
```
Replace with:
```kotlin
            composable(Routes.SESSION_LIST)   { SessionListScreen(navController) }
```

**c) Delete the `SessionListPlaceholder` private function** — find and remove the entire block:
```kotlin
@Composable
private fun SessionListPlaceholder(navController: NavController) {
    Surface(modifier = Modifier.fillMaxSize(), color = VelaColors.Abyss) {
        Text(text = "Session List", color = VelaColors.TextPrimary)
    }
}
```

### Step 4: Run AppNavigationTest to verify all tests pass

```bash
./gradlew :app:testDebugUnitTest \
  --tests "com.vela.app.ui.navigation.AppNavigationTest" \
  2>&1 | tail -15
```
Expected: `BUILD SUCCESSFUL` — all AppNavigationTest tests pass (existing + 1 new).

### Step 5: Commit

```bash
git add \
  app/src/main/kotlin/com/vela/app/ui/navigation/AppNavigation.kt \
  app/src/test/kotlin/com/vela/app/ui/navigation/AppNavigationTest.kt
git commit -m "feat(session-list): wire SessionListScreen into NavHost, remove SessionListPlaceholder"
```

---

## Task 7: SessionDetailViewModel TDD

**Files:**
- Create: `app/src/test/kotlin/com/vela/app/ui/sessiondetail/SessionDetailViewModelTest.kt`
- Create: `app/src/main/kotlin/com/vela/app/ui/sessiondetail/SessionDetailViewModel.kt`

### Step 1: Write the failing test

Create `app/src/test/kotlin/com/vela/app/ui/sessiondetail/SessionDetailViewModelTest.kt`:

```kotlin
package com.vela.app.ui.sessiondetail

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionDetailViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before fun setUp()    { Dispatchers.setMain(testDispatcher) }
    @After  fun tearDown() { Dispatchers.resetMain() }

    private fun makeVm(sessionId: String = "sess-1"): SessionDetailViewModel {
        val savedState = SavedStateHandle(mapOf("sessionId" to sessionId))
        return SessionDetailViewModel(savedState)
    }

    // ── Tests ─────────────────────────────────────────────────────────────────────

    @Test fun `sessionId is read from SavedStateHandle`() {
        val vm = makeVm(sessionId = "sess-42")
        assertThat(vm.sessionId).isEqualTo("sess-42")
    }

    @Test fun `turns initial value is not empty (has placeholder data)`() = runTest {
        val vm = makeVm()
        assertThat(vm.turns.value).isNotEmpty()
    }

    @Test fun `turns initial value has two placeholder turns`() = runTest {
        val vm = makeVm()
        assertThat(vm.turns.value).hasSize(2)
    }

    @Test fun `first placeholder turn is a user turn`() = runTest {
        val vm = makeVm()
        assertThat(vm.turns.value[0].isUser).isTrue()
    }

    @Test fun `second placeholder turn is an agent turn`() = runTest {
        val vm = makeVm()
        assertThat(vm.turns.value[1].isUser).isFalse()
    }

    @Test fun `second placeholder turn has one tool call`() = runTest {
        val vm = makeVm()
        assertThat(vm.turns.value[1].toolCalls).hasSize(1)
    }

    @Test fun `placeholder tool call is marked done`() = runTest {
        val vm = makeVm()
        val toolCall = vm.turns.value[1].toolCalls[0]
        assertThat(toolCall.isDone).isTrue()
        assertThat(toolCall.isRunning).isFalse()
    }
}
```

### Step 2: Run to verify it fails

```bash
./gradlew :app:testDebugUnitTest \
  --tests "com.vela.app.ui.sessiondetail.SessionDetailViewModelTest" \
  2>&1 | tail -20
```
Expected: **BUILD FAILED** — `error: unresolved reference: SessionDetailViewModel`

### Step 3: Create SessionDetailViewModel.kt

Create `app/src/main/kotlin/com/vela/app/ui/sessiondetail/SessionDetailViewModel.kt`:

```kotlin
package com.vela.app.ui.sessiondetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * ViewModel for Screen 4: Session Detail — Turn History.
 *
 * Turn data comes from the amplifierd HTTP API, which does not exist yet.
 * [turns] is seeded with two placeholder turns so the screen renders non-blank
 * in Phase 3. Replace this with real API data in a future phase.
 */
@HiltViewModel
class SessionDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val sessionId: String = checkNotNull(savedStateHandle["sessionId"])

    val turns: StateFlow<List<TurnContent>> = MutableStateFlow(
        listOf(
            // Placeholder user prompt
            TurnContent(
                text   = "Review auth PR #847 and leave inline comments on security issues",
                isUser = true,
            ),
            // Placeholder agent response with a completed tool call
            TurnContent(
                text = "I'll fetch the PR diff and analyze the security implications...",
                toolCalls = listOf(
                    ToolCall(
                        name      = "github: get_pull_request",
                        result    = "PR #847 · +342 −89 · 14 files changed",
                        isDone    = true,
                        isRunning = false,
                        durationMs = 1200L,
                    ),
                ),
                isUser = false,
            ),
        )
    )
}
```

### Step 4: Run to verify it passes

```bash
./gradlew :app:testDebugUnitTest \
  --tests "com.vela.app.ui.sessiondetail.SessionDetailViewModelTest" \
  2>&1 | tail -15
```
Expected: `BUILD SUCCESSFUL` — 7 tests pass.

### Step 5: Commit

```bash
git add \
  app/src/main/kotlin/com/vela/app/ui/sessiondetail/SessionDetailViewModel.kt \
  app/src/test/kotlin/com/vela/app/ui/sessiondetail/SessionDetailViewModelTest.kt
git commit -m "feat(session-detail): add SessionDetailViewModel with sessionId from SavedStateHandle and placeholder turns"
```

---

## Task 8: TurnItems Composables

**Files:**
- Create: `app/src/main/kotlin/com/vela/app/ui/sessiondetail/TurnItems.kt`
- Modify: `app/src/test/kotlin/com/vela/app/ui/sessiondetail/SessionDetailViewModelTest.kt`

### Step 1: Add structural tests to SessionDetailViewModelTest.kt

Append these tests inside the `SessionDetailViewModelTest` class:

```kotlin
    // ── Structural: verify TurnItems composables exist ────────────────────────────

    @Test fun `TurnItems source contains UserTurnItem composable`() {
        val src = java.io.File(
            "src/main/kotlin/com/vela/app/ui/sessiondetail/TurnItems.kt"
        ).readText()
        assertThat(src).contains("fun UserTurnItem")
    }

    @Test fun `TurnItems source contains AgentTurnItem composable`() {
        val src = java.io.File(
            "src/main/kotlin/com/vela/app/ui/sessiondetail/TurnItems.kt"
        ).readText()
        assertThat(src).contains("fun AgentTurnItem")
    }

    @Test fun `TurnItems source contains ToolCallCard composable`() {
        val src = java.io.File(
            "src/main/kotlin/com/vela/app/ui/sessiondetail/TurnItems.kt"
        ).readText()
        assertThat(src).contains("fun ToolCallCard")
    }
```

### Step 2: Run to verify these tests fail

```bash
./gradlew :app:testDebugUnitTest \
  --tests "com.vela.app.ui.sessiondetail.SessionDetailViewModelTest.TurnItems source contains UserTurnItem composable" \
  2>&1 | tail -10
```
Expected: **FAILED** — file does not exist.

### Step 3: Create TurnItems.kt

Create `app/src/main/kotlin/com/vela/app/ui/sessiondetail/TurnItems.kt`:

```kotlin
package com.vela.app.ui.sessiondetail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vela.app.ui.theme.MonoMedium
import com.vela.app.ui.theme.VelaColors
import com.vela.app.ui.theme.InstrumentSerifFamily

/**
 * User turn item — Screen 4 turn list.
 *
 * Design: DESIGN.md §9.4 — CRITICAL: NO CHAT BUBBLES.
 * Right-aligned serif text with a 2dp trailing cyan accent line.
 * Max 80% width. Instrument Serif 14sp.
 */
@Composable
fun UserTurnItem(
    text: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier         = modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                text      = text,
                style     = MaterialTheme.typography.bodyLarge.copy(
                    fontFamily = InstrumentSerifFamily,
                ),
                color     = VelaColors.TextPrimary,
                textAlign = TextAlign.End,
                modifier  = Modifier
                    .weight(1f)
                    .padding(start = 40.dp),
            )
            Spacer(Modifier.width(6.dp))
            // 2dp trailing cyan accent line
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(VelaColors.Accent),
            )
        }
    }
}

/**
 * Agent turn item — Screen 4 turn list.
 *
 * Design: DESIGN.md §8 (Screen 4)
 * SurfaceSub card, 20dp radius, Inter 16sp body text.
 * Tool-call cards rendered inline below the prose text.
 */
@Composable
fun AgentTurnItem(
    content: TurnContent,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape    = RoundedCornerShape(20.dp),
        color    = VelaColors.SurfaceSub,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier            = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text  = content.text,
                style = MaterialTheme.typography.bodyLarge,
                color = VelaColors.TextPrimary,
            )
            content.toolCalls.forEach { ToolCallCard(it) }
        }
    }
}

/**
 * Tool-call card — nested inside agent turns.
 *
 * Design: DESIGN.md §7.3 — M3 Outlined card.
 * - Full-perimeter 1dp border (StrokeHair), 12dp radius, SurfaceRaised background
 * - Tool name in JetBrains Mono, TextSecondary
 * - Duration + ✓ when done (sage green)
 * - Spinner (14dp, amber, 1.5dp stroke) when running
 * - Result text in Mono, TextTertiary
 */
@Composable
fun ToolCallCard(
    call: ToolCall,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape    = RoundedCornerShape(12.dp),
        color    = VelaColors.SurfaceRaised,
        border   = BorderStroke(1.dp, VelaColors.StrokeHair),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(10.dp, 10.dp, 12.dp, 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text     = call.name,
                    style    = MonoMedium,
                    color    = VelaColors.TextSecondary,
                    modifier = Modifier.weight(1f),
                )
                if (call.isDone && call.durationMs != null) {
                    Text(
                        text  = "✓ ${call.durationMs}ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = VelaColors.Done,
                    )
                }
                if (call.isRunning) {
                    CircularProgressIndicator(
                        modifier    = Modifier.size(14.dp),
                        color       = VelaColors.Running,
                        strokeWidth = 1.5.dp,
                    )
                }
            }
            if (call.result != null) {
                Text(
                    text  = call.result,
                    style = MonoMedium,
                    color = VelaColors.TextTertiary,
                )
            }
        }
    }
}
```

### Step 4: Run all SessionDetailViewModelTest tests to verify they pass

```bash
./gradlew :app:testDebugUnitTest \
  --tests "com.vela.app.ui.sessiondetail.SessionDetailViewModelTest" \
  2>&1 | tail -15
```
Expected: `BUILD SUCCESSFUL` — 10 tests pass (7 ViewModel + 3 structural).

### Step 5: Commit

```bash
git add \
  app/src/main/kotlin/com/vela/app/ui/sessiondetail/TurnItems.kt \
  app/src/test/kotlin/com/vela/app/ui/sessiondetail/SessionDetailViewModelTest.kt
git commit -m "feat(session-detail): add UserTurnItem (serif right-aligned), AgentTurnItem, ToolCallCard composables"
```

---

## Task 9: SessionDetailScreen Composable

**Files:**
- Create: `app/src/main/kotlin/com/vela/app/ui/sessiondetail/SessionDetailScreen.kt`
- Modify: `app/src/test/kotlin/com/vela/app/ui/sessiondetail/SessionDetailViewModelTest.kt`

### Step 1: Add structural test to SessionDetailViewModelTest.kt

Append inside the `SessionDetailViewModelTest` class:

```kotlin
    @Test fun `SessionDetailScreen source file exists with SessionDetailScreen composable`() {
        val src = java.io.File(
            "src/main/kotlin/com/vela/app/ui/sessiondetail/SessionDetailScreen.kt"
        ).readText()
        assertThat(src).contains("fun SessionDetailScreen")
    }
```

### Step 2: Run to verify this test fails

```bash
./gradlew :app:testDebugUnitTest \
  --tests "com.vela.app.ui.sessiondetail.SessionDetailViewModelTest.SessionDetailScreen source file exists with SessionDetailScreen composable" \
  2>&1 | tail -10
```
Expected: **FAILED** — file does not exist.

### Step 3: Create SessionDetailScreen.kt

Create `app/src/main/kotlin/com/vela/app/ui/sessiondetail/SessionDetailScreen.kt`:

```kotlin
package com.vela.app.ui.sessiondetail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.vela.app.ui.theme.InstrumentSerifFamily
import com.vela.app.ui.theme.VelaColors

/**
 * Screen 4: Session Detail — Turn History.
 *
 * Design: DESIGN.md §8 (Screen 4)
 * Layout:
 *   - App bar: back + session title in titleLarge + running dot (8dp amber) if RUNNING
 *   - Title area: session name in displayMedium (Instrument Serif 36sp) + status pill
 *   - Turn list: LazyColumn of UserTurnItem / AgentTurnItem, 16dp spacing
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailScreen(
    navController: NavController,
    viewModel: SessionDetailViewModel = hiltViewModel(),
) {
    val turns by viewModel.turns.collectAsStateWithLifecycle()

    // Derive session status from first non-user turn's tool calls for placeholder display
    val isRunning = turns.any { !it.isUser && it.toolCalls.any { tc -> tc.isRunning } }

    Scaffold(
        containerColor = VelaColors.Abyss,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text  = "Session",  // Placeholder — session title from API in future phase
                        style = MaterialTheme.typography.titleLarge,
                        color = VelaColors.TextPrimary,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector        = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint               = VelaColors.Accent,
                        )
                    }
                },
                actions = {
                    // Running dot indicator: 8dp amber circle, breathing animation
                    if (isRunning) {
                        RunningDotIndicator()
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = VelaColors.Abyss,
                ),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier       = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {

            // ── Session title hero area ────────────────────────────────────────
            item {
                SessionTitleArea(
                    // Placeholder title — replace with real session.title in future phase
                    title   = turns.firstOrNull { it.isUser }?.text?.take(60) ?: "Session",
                    isRunning = isRunning,
                )
            }

            // ── Turn list ─────────────────────────────────────────────────────
            items(turns) { turn ->
                if (turn.isUser) {
                    UserTurnItem(text = turn.text)
                } else {
                    AgentTurnItem(content = turn)
                }
            }

            // Bottom padding to clear Voice FAB
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

// ── Private sub-composables ───────────────────────────────────────────────────

/**
 * Session title area displayed below the app bar.
 * Title uses displayMedium (Instrument Serif 36sp) — the key serif moment on this screen.
 * Status pill: 8dp radius, 26dp height, Inter 10sp 700 uppercase.
 */
@Composable
private fun SessionTitleArea(
    title: String,
    isRunning: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text  = title,
            style = MaterialTheme.typography.displayMedium,
            color = VelaColors.TextPrimary,
        )

        // Status pill
        val (pillColor, pillText, pillTextColor) = if (isRunning) {
            Triple(VelaColors.RunningContainer, "RUNNING", VelaColors.RunningOnContainer)
        } else {
            Triple(VelaColors.DoneContainer, "DONE", VelaColors.DoneOnContainer)
        }

        Surface(
            shape    = RoundedCornerShape(8.dp),
            color    = pillColor,
            modifier = Modifier.height(26.dp),
        ) {
            Box(
                modifier         = Modifier.padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text  = pillText,
                    style = MaterialTheme.typography.labelSmall,
                    color = pillTextColor,
                )
            }
        }
    }
}

/**
 * Amber breathing dot shown in the app bar when session is RUNNING.
 * 8dp circle, VelaColors.Running fill.
 */
@Composable
private fun RunningDotIndicator(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(end = 16.dp)
            .size(8.dp)
            .background(VelaColors.Running, CircleShape),
    )
}
```

### Step 4: Run all SessionDetailViewModelTest tests to verify they pass

```bash
./gradlew :app:testDebugUnitTest \
  --tests "com.vela.app.ui.sessiondetail.SessionDetailViewModelTest" \
  2>&1 | tail -15
```
Expected: `BUILD SUCCESSFUL` — 11 tests pass.

### Step 5: Commit

```bash
git add \
  app/src/main/kotlin/com/vela/app/ui/sessiondetail/SessionDetailScreen.kt \
  app/src/test/kotlin/com/vela/app/ui/sessiondetail/SessionDetailViewModelTest.kt
git commit -m "feat(session-detail): add SessionDetailScreen with displayMedium title, status pill, turn list"
```

---

## Task 10: Wire SessionDetailScreen in AppNavigation

**Files:**
- Modify: `app/src/test/kotlin/com/vela/app/ui/navigation/AppNavigationTest.kt`
- Modify: `app/src/main/kotlin/com/vela/app/ui/navigation/AppNavigation.kt`

### Step 1: Add wiring test to AppNavigationTest.kt

Append inside the existing `AppNavigationTest` class:

```kotlin
    @Test fun `AppNavigation sources SessionDetailScreen (not placeholder)`() {
        val src = java.io.File(
            "src/main/kotlin/com/vela/app/ui/navigation/AppNavigation.kt"
        ).readText()
        assertThat(src).contains("SessionDetailScreen(navController)")
        assertThat(src).doesNotContain("SessionDetailPlaceholder")
    }
```

### Step 2: Run to verify this test fails

```bash
./gradlew :app:testDebugUnitTest \
  --tests "com.vela.app.ui.navigation.AppNavigationTest.AppNavigation sources SessionDetailScreen (not placeholder)" \
  2>&1 | tail -10
```
Expected: **FAILED** — source still contains `SessionDetailPlaceholder`.

### Step 3: Update AppNavigation.kt

Open `app/src/main/kotlin/com/vela/app/ui/navigation/AppNavigation.kt`.

**a) Add import** in the imports block:
```kotlin
import com.vela.app.ui.sessiondetail.SessionDetailScreen
```

**b) Replace the SessionDetailPlaceholder call** in the `NavHost` block:

Find:
```kotlin
            composable(Routes.SESSION_DETAIL) { SessionDetailPlaceholder(navController) }
```
Replace with:
```kotlin
            composable(Routes.SESSION_DETAIL) { SessionDetailScreen(navController) }
```

**c) Delete the `SessionDetailPlaceholder` private function** — find and remove the entire block:
```kotlin
@Composable
private fun SessionDetailPlaceholder(navController: NavController) {
    Surface(modifier = Modifier.fillMaxSize(), color = VelaColors.Abyss) {
        Text(text = "Session Detail", color = VelaColors.TextPrimary)
    }
}
```

### Step 4: Run the full AppNavigationTest and both screen test suites

```bash
./gradlew :app:testDebugUnitTest \
  --tests "com.vela.app.ui.navigation.AppNavigationTest" \
  --tests "com.vela.app.ui.sessionlist.SessionListViewModelTest" \
  --tests "com.vela.app.ui.sessionlist.SessionCardColorTest" \
  --tests "com.vela.app.ui.sessiondetail.SessionDetailViewModelTest" \
  2>&1 | tail -20
```
Expected: `BUILD SUCCESSFUL` — all test classes pass.

### Step 5: Final commit

```bash
git add \
  app/src/main/kotlin/com/vela/app/ui/navigation/AppNavigation.kt \
  app/src/test/kotlin/com/vela/app/ui/navigation/AppNavigationTest.kt
git commit -m "feat(session-detail): wire SessionDetailScreen into NavHost, remove SessionDetailPlaceholder"
```

---

## Phase 3 Complete — Verification

Run the full test suite to confirm no regressions:

```bash
./gradlew :app:testDebugUnitTest 2>&1 | tail -20
```
Expected: `BUILD SUCCESSFUL` with all tests passing.

### Summary of deliverables

| Screen | Files added | Tests added |
|--------|-------------|-------------|
| Session List | `SessionListViewModel.kt`, `SessionCard.kt`, `SessionListScreen.kt` | `SessionListViewModelTest.kt` (9 tests), `SessionCardColorTest.kt` (13 tests) |
| Session Detail | `SessionModels.kt`, `SessionDetailViewModel.kt`, `TurnItems.kt`, `SessionDetailScreen.kt` | `SessionDetailViewModelTest.kt` (11 tests) |
| Navigation | `AppNavigation.kt` (2 placeholder swaps) | `AppNavigationTest.kt` (2 wiring tests) |

**Total new tests: 35**
