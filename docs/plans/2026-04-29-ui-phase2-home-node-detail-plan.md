# UI Phase 2: Home Screen & Node Detail Screen Implementation Plan

> **Execution:** Use the subagent-driven-development workflow to implement this plan.

**Goal:** Replace the `HomeScreenPlaceholder` and `NodeDetailPlaceholder` stubs in the Phase 1 NavHost with real, design-system-correct screens — node tiles with status stripes + breathing animations on the home screen, and the Instrument Serif hero title on the node detail screen.

**Architecture:** Two new UI packages (`com.vela.app.ui.home`, `com.vela.app.ui.nodedetail`) each contain a `@HiltViewModel` ViewModel backed by `SshNodeRegistry.allFlow()` and a `@Composable` screen composable. `NodeTile.kt` encapsulates all tile-specific logic — status enum, pure color-mapping functions (unit-tested), and the Compose tile component with infinite-transition breathing animation. After the screens pass their tests, `AppNavigation.kt` is modified to replace the two placeholder calls, and the now-dead placeholder functions are deleted.

**Tech Stack:** Kotlin, Jetpack Compose (BOM 2025.04.01), Material 3, Navigation Compose 2.7.7, Hilt, JUnit 4, Google Truth 1.4.2, `kotlinx.coroutines.test` 1.8.0

---

## Phase 1 Contract — What Already Exists

These tokens and types are available the moment Phase 1 is complete. **Do not redefine them.**

| Symbol | Location | Purpose |
|--------|----------|---------|
| `VelaColors.Abyss/SurfaceSub/SurfaceRaised/SurfacePeak` | `com.vela.app.ui.theme.VelaColors` | Surface tones |
| `VelaColors.Running/Waiting/Done/Error/Accent` | `com.vela.app.ui.theme.VelaColors` | Semantic status colors |
| `VelaColors.RunningContainer/WaitingContainer/DoneContainer` | `com.vela.app.ui.theme.VelaColors` | Chip containers |
| `VelaColors.RunningOnContainer/WaitingOnContainer/DoneOnContainer` | `com.vela.app.ui.theme.VelaColors` | Chip text |
| `VelaColors.TextPrimary/TextSecondary/TextTertiary` | `com.vela.app.ui.theme.VelaColors` | Text hierarchy |
| `VelaColors.StrokeEdge` | `com.vela.app.ui.theme.VelaColors` | Dashed border color |
| `MaterialTheme.typography.displayLarge` | `com.vela.app.ui.theme.VelaTypography` | Instrument Serif 48sp (hero title) |
| `MaterialTheme.typography.headlineLarge` | `com.vela.app.ui.theme.VelaTypography` | Instrument Serif 28sp (node name on tile) |
| `MaterialTheme.typography.titleLarge` | `com.vela.app.ui.theme.VelaTypography` | Inter 18sp/600 (project name) |
| `MaterialTheme.typography.bodyMedium` | `com.vela.app.ui.theme.VelaTypography` | Inter 14sp (telemetry) |
| `MaterialTheme.typography.labelSmall` | `com.vela.app.ui.theme.VelaTypography` | Inter 11sp/Bold/+2sp tracking (chip text) |
| `MaterialTheme.typography.labelLarge` | `com.vela.app.ui.theme.VelaTypography` | Inter 14sp/600 (button labels) |
| `MonoMedium` | `com.vela.app.ui.theme` (top-level) | JetBrains Mono 13sp (bundle tag) |
| `Routes.HOME/NODE_DETAIL/CONNECT_NODE` | `com.vela.app.ui.navigation.Routes` | Route constants |
| `Routes.nodeDetail(nodeId)` | `com.vela.app.ui.navigation.Routes` | Route builder |
| `VoiceFabPlaceholder` | `com.vela.app.ui.navigation.AppNavigation` | Persistent FAB — **do not touch** |
| `SshNode` | `com.vela.app.ssh.SshNode` | Domain model — `id`, `label`, `hosts`, `port`, `username`, `type` (NodeType.SSH / AMPLIFIERD), `bootstrapStatus` (BootstrapStatus enum), `primaryHost` |
| `SshNodeRegistry.allFlow()` | `com.vela.app.ssh.SshNodeRegistry` | `Flow<List<SshNode>>` from Room DAO |
| `SshNodeDao` | `com.vela.app.data.db.SshNodeDao` | Interface — implement for test fakes |
| `SshNodeEntity` | `com.vela.app.data.db.SshNodeEntity` | `id`, `label`, `hosts` (comma-sep), `port`, `username`, `addedAt`, `nodeType`="ssh"/"amplifierd", `url`="", `token`="", `bootstrapStatus`="UNPROVISIONED" |

**AppNavigation.kt shape after Phase 1** — the two lines we will replace in Tasks 5 and 8:
```kotlin
composable(Routes.HOME)        { HomeScreenPlaceholder(navController) }
composable(Routes.NODE_DETAIL) { NodeDetailPlaceholder(navController) }
```

---

## Anti-Pattern Checklist

Before writing any code, tattoo these to memory:

- **NO bottom nav bar.** No `BottomNavigation`, no `NavigationBar`, no tab icons.
- **NO Instrument Serif below 22sp.** Project names in `titleLarge` (Inter 18sp), never serif.
- **NO blue.** `#3B82F6` is forbidden. Accent is cyan `#5EEAD4`.
- **NO left-border-only stripes.** The 4dp stripe goes on the *leading edge* using a clipped Box inside the card — not `Modifier.border(start = …)`.
- **NO speech bubbles / chat-bubble shapes.** Tiles are flat-corner `RoundedCornerShape(28.dp)`.
- **NO illustrations in empty state.** Typography + button only.

---

## New Files Summary

| Action | Path |
|--------|------|
| Create | `app/src/main/kotlin/com/vela/app/ui/home/HomeViewModel.kt` |
| Create | `app/src/main/kotlin/com/vela/app/ui/home/NodeTile.kt` |
| Create | `app/src/main/kotlin/com/vela/app/ui/home/HomeScreen.kt` |
| Create | `app/src/test/kotlin/com/vela/app/ui/home/HomeViewModelTest.kt` |
| Create | `app/src/test/kotlin/com/vela/app/ui/home/NodeTileColorTest.kt` |
| Create | `app/src/main/kotlin/com/vela/app/ui/nodedetail/NodeDetailViewModel.kt` |
| Create | `app/src/main/kotlin/com/vela/app/ui/nodedetail/ProjectCard.kt` |
| Create | `app/src/main/kotlin/com/vela/app/ui/nodedetail/NodeDetailScreen.kt` |
| Create | `app/src/test/kotlin/com/vela/app/ui/nodedetail/NodeDetailViewModelTest.kt` |
| Modify | `app/src/main/kotlin/com/vela/app/ui/navigation/AppNavigation.kt` |
| Modify | `app/src/test/kotlin/com/vela/app/ui/navigation/AppNavigationTest.kt` |

---

## Task 1: HomeViewModel TDD

**Files:**
- Create: `app/src/test/kotlin/com/vela/app/ui/home/HomeViewModelTest.kt`
- Create: `app/src/main/kotlin/com/vela/app/ui/home/HomeViewModel.kt`

### Step 1: Write the failing test

Create `app/src/test/kotlin/com/vela/app/ui/home/HomeViewModelTest.kt`:

```kotlin
package com.vela.app.ui.home

import com.google.common.truth.Truth.assertThat
import com.vela.app.data.db.SshNodeDao
import com.vela.app.data.db.SshNodeEntity
import com.vela.app.ssh.SshNodeRegistry
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
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before fun setUp()    { Dispatchers.setMain(testDispatcher) }
    @After  fun tearDown() { Dispatchers.resetMain() }

    // ── Fake DAO ─────────────────────────────────────────────────────────────

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

    private fun makeVm(dao: FakeSshNodeDao = FakeSshNodeDao()) =
        HomeViewModel(SshNodeRegistry(dao))

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    fun `nodes initial value is empty list`() {
        val vm = makeVm()
        assertThat(vm.nodes.value).isEmpty()
    }

    @Test
    fun `nodes emits node from registry when dao emits`() = runTest {
        val dao = FakeSshNodeDao()
        val vm  = makeVm(dao)

        dao.nodeFlow.value = listOf(
            SshNodeEntity(id = "n1", label = "raspi",
                          hosts = "192.168.1.5", port = 22, username = "pi", addedAt = 0L),
        )

        assertThat(vm.nodes.value).hasSize(1)
        assertThat(vm.nodes.value[0].id).isEqualTo("n1")
        assertThat(vm.nodes.value[0].label).isEqualTo("raspi")
    }

    @Test
    fun `nodes emits multiple nodes in order`() = runTest {
        val dao = FakeSshNodeDao()
        val vm  = makeVm(dao)

        dao.nodeFlow.value = listOf(
            SshNodeEntity(id = "a", label = "Node A", hosts = "10.0.0.1", port = 22, username = "pi", addedAt = 1L),
            SshNodeEntity(id = "b", label = "Node B", hosts = "10.0.0.2", port = 22, username = "pi", addedAt = 2L),
        )

        assertThat(vm.nodes.value).hasSize(2)
        assertThat(vm.nodes.value.map { it.id }).containsExactly("a", "b").inOrder()
    }

    @Test
    fun `nodes updates when dao emits new list`() = runTest {
        val dao = FakeSshNodeDao()
        val vm  = makeVm(dao)

        dao.nodeFlow.value = listOf(
            SshNodeEntity(id = "x", label = "X", hosts = "1.1.1.1", port = 22, username = "u", addedAt = 0L),
        )
        assertThat(vm.nodes.value).hasSize(1)

        dao.nodeFlow.value = emptyList()
        assertThat(vm.nodes.value).isEmpty()
    }
}
```

### Step 2: Run to verify it fails

Run from `/Users/ken/workspace/vela`:
```bash
./gradlew :app:testDebugUnitTest \
  --tests "com.vela.app.ui.home.HomeViewModelTest" \
  2>&1 | tail -20
```
Expected: **BUILD FAILED** — `error: unresolved reference: HomeViewModel`

### Step 3: Create HomeViewModel.kt

Create `app/src/main/kotlin/com/vela/app/ui/home/HomeViewModel.kt`:

```kotlin
package com.vela.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vela.app.ssh.SshNode
import com.vela.app.ssh.SshNodeRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val registry: SshNodeRegistry,
) : ViewModel() {

    val nodes: StateFlow<List<SshNode>> = registry.allFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
```

### Step 4: Run to verify it passes

```bash
./gradlew :app:testDebugUnitTest \
  --tests "com.vela.app.ui.home.HomeViewModelTest" \
  2>&1 | tail -15
```
Expected: `BUILD SUCCESSFUL` — 4 tests pass.

### Step 5: Commit

```bash
git add \
  app/src/main/kotlin/com/vela/app/ui/home/HomeViewModel.kt \
  app/src/test/kotlin/com/vela/app/ui/home/HomeViewModelTest.kt
git commit -m "feat(home): add HomeViewModel backed by SshNodeRegistry.allFlow()"
```

---

## Task 2: NodeTile Status/Color Logic TDD

**Files:**
- Create: `app/src/test/kotlin/com/vela/app/ui/home/NodeTileColorTest.kt`
- Create: `app/src/main/kotlin/com/vela/app/ui/home/NodeTile.kt` (pure-logic stub only — no `@Composable` yet)

### Step 1: Write the failing test

Create `app/src/test/kotlin/com/vela/app/ui/home/NodeTileColorTest.kt`:

```kotlin
package com.vela.app.ui.home

import com.google.common.truth.Truth.assertThat
import com.vela.app.ssh.BootstrapStatus
import com.vela.app.ssh.NodeType
import com.vela.app.ssh.SshNode
import com.vela.app.ui.theme.VelaColors
import org.junit.Test

/**
 * RED → GREEN: verifies the pure color-mapping and status-derivation logic
 * for node tile status stripes and live dots.
 *
 * All functions under test are top-level `internal` functions in NodeTile.kt.
 * JVM-only — no Compose rendering needed.
 * androidx.compose.ui.graphics.Color is a Kotlin value class; no Android calls.
 */
class NodeTileColorTest {

    // ── stripeColorFor ────────────────────────────────────────────────────────

    @Test fun `stripeColor for Running is VelaColors Running`() {
        assertThat(stripeColorFor(NodeTileStatus.Running).toArgb())
            .isEqualTo(VelaColors.Running.toArgb())
    }

    @Test fun `stripeColor for Waiting is VelaColors Waiting`() {
        assertThat(stripeColorFor(NodeTileStatus.Waiting).toArgb())
            .isEqualTo(VelaColors.Waiting.toArgb())
    }

    @Test fun `stripeColor for Done is VelaColors Done`() {
        assertThat(stripeColorFor(NodeTileStatus.Done).toArgb())
            .isEqualTo(VelaColors.Done.toArgb())
    }

    @Test fun `stripeColor for Idle is VelaColors Accent`() {
        assertThat(stripeColorFor(NodeTileStatus.Idle).toArgb())
            .isEqualTo(VelaColors.Accent.toArgb())
    }

    // ── stripeAlphaFor ────────────────────────────────────────────────────────

    @Test fun `stripeAlpha for Running is 1f`() {
        assertThat(stripeAlphaFor(NodeTileStatus.Running)).isEqualTo(1f)
    }

    @Test fun `stripeAlpha for Waiting is 1f`() {
        assertThat(stripeAlphaFor(NodeTileStatus.Waiting)).isEqualTo(1f)
    }

    @Test fun `stripeAlpha for Done is 0point5`() {
        assertThat(stripeAlphaFor(NodeTileStatus.Done)).isEqualTo(0.5f)
    }

    @Test fun `stripeAlpha for Idle is 0point4`() {
        assertThat(stripeAlphaFor(NodeTileStatus.Idle)).isEqualTo(0.4f)
    }

    // ── chipContainerColorFor ─────────────────────────────────────────────────

    @Test fun `chipContainerColor for Running is RunningContainer`() {
        assertThat(chipContainerColorFor(NodeTileStatus.Running).toArgb())
            .isEqualTo(VelaColors.RunningContainer.toArgb())
    }

    @Test fun `chipContainerColor for Waiting is WaitingContainer`() {
        assertThat(chipContainerColorFor(NodeTileStatus.Waiting).toArgb())
            .isEqualTo(VelaColors.WaitingContainer.toArgb())
    }

    @Test fun `chipContainerColor for Done is DoneContainer`() {
        assertThat(chipContainerColorFor(NodeTileStatus.Done).toArgb())
            .isEqualTo(VelaColors.DoneContainer.toArgb())
    }

    @Test fun `chipContainerColor for Idle is SurfaceRaised`() {
        assertThat(chipContainerColorFor(NodeTileStatus.Idle).toArgb())
            .isEqualTo(VelaColors.SurfaceRaised.toArgb())
    }

    // ── nodeStatusFor — Phase 2: all nodes are Idle ───────────────────────────

    @Test fun `nodeStatus for SSH node is Idle`() {
        val node = SshNode(label = "pi", type = NodeType.SSH)
        assertThat(nodeStatusFor(node)).isEqualTo(NodeTileStatus.Idle)
    }

    @Test fun `nodeStatus for AMPLIFIERD RUNNING node is Idle (no session data yet)`() {
        val node = SshNode(label = "amp", type = NodeType.AMPLIFIERD,
                           bootstrapStatus = BootstrapStatus.RUNNING)
        assertThat(nodeStatusFor(node)).isEqualTo(NodeTileStatus.Idle)
    }

    @Test fun `nodeStatus for AMPLIFIERD UNPROVISIONED is Idle`() {
        val node = SshNode(label = "amp", type = NodeType.AMPLIFIERD,
                           bootstrapStatus = BootstrapStatus.UNPROVISIONED)
        assertThat(nodeStatusFor(node)).isEqualTo(NodeTileStatus.Idle)
    }

    @Test fun `nodeStatus for AMPLIFIERD FAILED is Idle`() {
        val node = SshNode(label = "amp", type = NodeType.AMPLIFIERD,
                           bootstrapStatus = BootstrapStatus.FAILED)
        assertThat(nodeStatusFor(node)).isEqualTo(NodeTileStatus.Idle)
    }

    // ── telemetryLineFor ──────────────────────────────────────────────────────

    @Test fun `telemetry for SSH node includes type and host`() {
        val node = SshNode(label = "pi", type = NodeType.SSH,
                           hosts = listOf("192.168.1.5"))
        assertThat(telemetryLineFor(node)).contains("ssh")
        assertThat(telemetryLineFor(node)).contains("192.168.1.5")
    }

    @Test fun `telemetry for AMPLIFIERD node includes amplifierd and url host`() {
        val node = SshNode(label = "amp", type = NodeType.AMPLIFIERD,
                           url = "http://10.0.0.106:8410")
        assertThat(telemetryLineFor(node)).contains("amplifierd")
        assertThat(telemetryLineFor(node)).contains("10.0.0.106")
    }
}
```

### Step 2: Run to verify it fails

```bash
./gradlew :app:testDebugUnitTest \
  --tests "com.vela.app.ui.home.NodeTileColorTest" \
  2>&1 | tail -20
```
Expected: **BUILD FAILED** — `error: unresolved reference: NodeTileStatus`

### Step 3: Create NodeTile.kt (pure-logic stub)

Create `app/src/main/kotlin/com/vela/app/ui/home/NodeTile.kt` with just the testable logic — no `@Composable` yet:

```kotlin
package com.vela.app.ui.home

import androidx.compose.ui.graphics.Color
import com.vela.app.ssh.NodeType
import com.vela.app.ssh.SshNode
import com.vela.app.ui.theme.VelaColors

/**
 * Visual status for a node tile's stripe, dot, and chip.
 *
 * Running / Waiting / Done require session data (Phase 3+).
 * In Phase 2 every node resolves to [Idle].
 */
enum class NodeTileStatus { Running, Waiting, Done, Idle }

// ── Pure logic helpers ────────────────────────────────────────────────────────
// All functions are `internal` so they are accessible from the test source set.

/** Stripe and live-dot color for the given status. */
internal fun stripeColorFor(status: NodeTileStatus): Color = when (status) {
    NodeTileStatus.Running -> VelaColors.Running
    NodeTileStatus.Waiting -> VelaColors.Waiting
    NodeTileStatus.Done    -> VelaColors.Done
    NodeTileStatus.Idle    -> VelaColors.Accent
}

/** Stripe and live-dot opacity for the given status. */
internal fun stripeAlphaFor(status: NodeTileStatus): Float = when (status) {
    NodeTileStatus.Running -> 1f
    NodeTileStatus.Waiting -> 1f
    NodeTileStatus.Done    -> 0.5f
    NodeTileStatus.Idle    -> 0.4f
}

/** Status-chip container fill for the given status. */
internal fun chipContainerColorFor(status: NodeTileStatus): Color = when (status) {
    NodeTileStatus.Running -> VelaColors.RunningContainer
    NodeTileStatus.Waiting -> VelaColors.WaitingContainer
    NodeTileStatus.Done    -> VelaColors.DoneContainer
    NodeTileStatus.Idle    -> VelaColors.SurfaceRaised
}

/** Status-chip text color for the given status. */
internal fun chipOnColorFor(status: NodeTileStatus): Color = when (status) {
    NodeTileStatus.Running -> VelaColors.RunningOnContainer
    NodeTileStatus.Waiting -> VelaColors.WaitingOnContainer
    NodeTileStatus.Done    -> VelaColors.DoneOnContainer
    NodeTileStatus.Idle    -> VelaColors.TextSecondary
}

/** All-caps chip label for the given status. */
internal fun chipLabelFor(status: NodeTileStatus): String = when (status) {
    NodeTileStatus.Running -> "RUNNING"
    NodeTileStatus.Waiting -> "WAITING"
    NodeTileStatus.Done    -> "DONE"
    NodeTileStatus.Idle    -> "IDLE"
}

/**
 * Derives tile status from node state alone (Phase 2).
 *
 * Returns [NodeTileStatus.Idle] for all nodes — session data is not available
 * until Phase 3. Phase 3 will pass an active-session count and return the
 * correct Running / Waiting / Done state.
 */
internal fun nodeStatusFor(node: SshNode): NodeTileStatus = NodeTileStatus.Idle

/**
 * Single-line telemetry string shown below the node name on the tile.
 * Format: "type · host"
 */
internal fun telemetryLineFor(node: SshNode): String {
    val typeLabel = if (node.type == NodeType.AMPLIFIERD) "amplifierd" else "ssh"
    val hostLabel = node.primaryHost.ifBlank {
        node.url
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore("/")
    }
    return if (hostLabel.isNotBlank()) "$typeLabel · $hostLabel" else typeLabel
}
```

### Step 4: Run to verify it passes

```bash
./gradlew :app:testDebugUnitTest \
  --tests "com.vela.app.ui.home.NodeTileColorTest" \
  2>&1 | tail -15
```
Expected: `BUILD SUCCESSFUL` — 18 tests pass.

### Step 5: Commit

```bash
git add \
  app/src/main/kotlin/com/vela/app/ui/home/NodeTile.kt \
  app/src/test/kotlin/com/vela/app/ui/home/NodeTileColorTest.kt
git commit -m "feat(home): add NodeTileStatus enum + color/telemetry logic, fully tested"
```

---

## Task 3: NodeTile Compose Component

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/ui/home/NodeTile.kt` (add the `@Composable` function)
- Modify: `app/src/test/kotlin/com/vela/app/ui/home/NodeTileColorTest.kt` (add structural assertion)

### Step 1: Add one source-inspection test to NodeTileColorTest.kt

Append this test inside the `NodeTileColorTest` class (before the closing `}`):

```kotlin
    // ── Structural: verify composable exists ──────────────────────────────────

    @Test fun `NodeTile source contains NodeTileItem composable`() {
        val src = java.io.File(
            "src/main/kotlin/com/vela/app/ui/home/NodeTile.kt"
        ).readText()
        assertThat(src).contains("fun NodeTileItem")
    }
```

### Step 2: Run to verify this test fails

```bash
./gradlew :app:testDebugUnitTest \
  --tests "com.vela.app.ui.home.NodeTileColorTest.NodeTile source contains NodeTileItem composable" \
  2>&1 | tail -15
```
Expected: **FAILED** — source does not contain `fun NodeTileItem`

### Step 3: Append the Compose component to NodeTile.kt

Add the following imports and the `NodeTileItem` composable to the **bottom** of `app/src/main/kotlin/com/vela/app/ui/home/NodeTile.kt`. Keep all existing content — only add after the last existing line:

```kotlin

// ── Composable ────────────────────────────────────────────────────────────────

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.matchParentSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp

/**
 * Node tile — the primary cell of the Home screen.
 *
 * Design spec: DESIGN.md §7.1
 * - Container: SurfaceSub, 28dp radius, full-width minus 16dp gutters, min 120dp height
 * - Leading 4dp status stripe, clipped to the card's rounded-left corners
 * - Node name: headlineLarge (Instrument Serif 28sp)
 * - Telemetry: bodyMedium (Inter 14sp, TextSecondary)
 * - Status chip: 8dp radius, 28dp height, labelSmall
 * - Live dot: 8dp circle, bottom-right, breathing when running
 * - Running glow: radial amber gradient drawn behind the card via drawBehind
 * - Running surface tint: Color(0xFF151209) — SurfaceSub tinted toward RunningContainer
 */
@Composable
fun NodeTileItem(
    node: SshNode,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val status     = nodeStatusFor(node)
    val stripeColor = stripeColorFor(status)
    val stripeAlpha = stripeAlphaFor(status)
    val isRunning  = status == NodeTileStatus.Running

    // Breathing animation — live dot scales 1.0 → 1.15 over 2.4s, ease-in-out, when running
    val infiniteTransition = rememberInfiniteTransition(label = "nodeTileLiveDot")
    val liveDotScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue  = if (isRunning) 1.15f else 1.0f,
        animationSpec = infiniteRepeatable(
            animation  = tween(durationMillis = 2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "liveDotScale",
    )

    // Amber glow behind the card when running (drawBehind renders within layout bounds)
    val glowModifier: Modifier = if (isRunning) {
        Modifier.drawBehind {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFFF5A524).copy(alpha = 0.18f),
                        androidx.compose.ui.graphics.Color.Transparent,
                    ),
                    radius = size.maxDimension * 0.75f,
                    center = center,
                ),
                radius = size.maxDimension * 0.75f,
            )
        }
    } else Modifier

    Surface(
        onClick   = onClick,
        modifier  = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 120.dp)
            .then(glowModifier),
        shape     = RoundedCornerShape(28.dp),
        color     = if (isRunning) Color(0xFF151209) else VelaColors.SurfaceSub,
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {

            // ── Leading 4dp status stripe ─────────────────────────────────────
            // Clipped to follow the card's top-left and bottom-left rounded corners.
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .matchParentSize()
                    .clip(RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp))
                    .background(stripeColor.copy(alpha = stripeAlpha)),
            )

            // ── Main content ──────────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 20.dp),
            ) {
                // Node name — Instrument Serif 28sp (headlineLarge)
                Text(
                    text  = node.label,
                    style = MaterialTheme.typography.headlineLarge,
                    color = VelaColors.TextPrimary,
                )
                Spacer(modifier = Modifier.height(8.dp))
                // Telemetry — Inter 14sp (bodyMedium)
                Text(
                    text  = telemetryLineFor(node),
                    style = MaterialTheme.typography.bodyMedium,
                    color = VelaColors.TextSecondary,
                )
                Spacer(modifier = Modifier.height(12.dp))
                // Status chip — 8dp radius, 28dp height, labelSmall (Inter 11sp/Bold)
                Box(
                    modifier = Modifier
                        .background(
                            color = chipContainerColorFor(status),
                            shape = RoundedCornerShape(8.dp),
                        )
                        .height(28.dp)
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text  = chipLabelFor(status),
                        style = MaterialTheme.typography.labelSmall,
                        color = chipOnColorFor(status),
                    )
                }
            }

            // ── Live dot — bottom-right, 8dp, breathing when running ──────────
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(20.dp)
                    .size((8f * liveDotScale).dp)
                    .background(
                        color = stripeColor.copy(alpha = stripeAlpha),
                        shape = CircleShape,
                    ),
            )
        }
    }
}
```

> **Note on imports:** The import block above should be placed at the **top** of the file with the existing imports, not mid-file. Move them to the top of the file when writing. They are shown here near the composable for readability.

### Step 4: Run all NodeTileColorTest tests to verify they still pass

```bash
./gradlew :app:testDebugUnitTest \
  --tests "com.vela.app.ui.home.NodeTileColorTest" \
  2>&1 | tail -15
```
Expected: `BUILD SUCCESSFUL` — all 19 tests pass (18 existing + 1 structural).

### Step 5: Commit

```bash
git add app/src/main/kotlin/com/vela/app/ui/home/NodeTile.kt \
        app/src/test/kotlin/com/vela/app/ui/home/NodeTileColorTest.kt
git commit -m "feat(home): add NodeTileItem composable with status stripe, chip, live dot, running glow"
```

---

## Task 4: HomeScreen Compose Component

**Files:**
- Create: `app/src/main/kotlin/com/vela/app/ui/home/HomeScreen.kt`
- Modify: `app/src/test/kotlin/com/vela/app/ui/home/HomeViewModelTest.kt` (add structural assertion)

### Step 1: Add one source-inspection test to HomeViewModelTest.kt

Append inside the `HomeViewModelTest` class:

```kotlin
    // ── Structural: verify composable exists ──────────────────────────────────

    @Test fun `HomeScreen source file exists with HomeScreen composable`() {
        val src = java.io.File(
            "src/main/kotlin/com/vela/app/ui/home/HomeScreen.kt"
        ).readText()
        assertThat(src).contains("fun HomeScreen")
    }
```

### Step 2: Run to verify it fails

```bash
./gradlew :app:testDebugUnitTest \
  --tests "com.vela.app.ui.home.HomeViewModelTest.HomeScreen source file exists with HomeScreen composable" \
  2>&1 | tail -10
```
Expected: **FAILED** — file does not exist.

### Step 3: Create HomeScreen.kt

Create `app/src/main/kotlin/com/vela/app/ui/home/HomeScreen.kt`:

```kotlin
package com.vela.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.vela.app.ui.navigation.Routes
import com.vela.app.ui.theme.VelaColors

/**
 * Home screen — the fleet overview.
 *
 * Shows a vertical list of node tiles (one per SshNode in the database) or an
 * empty state when no nodes are connected yet. No bottom nav bar. Voice FAB is
 * provided by the parent VelaApp scaffold and is not touched here.
 *
 * Design spec: DESIGN.md §8 (Screen 1)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val nodes by viewModel.nodes.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    // "Vela" wordmark — Inter 700, accent color, 18sp (DESIGN.md §8)
                    Text(
                        text       = "Vela",
                        color      = VelaColors.Accent,
                        fontSize   = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                },
                actions = {
                    // Settings gear — icon only, navigation wired in a later phase
                    IconButton(onClick = { /* Phase 3: navigate to settings */ }) {
                        Icon(
                            imageVector        = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint               = VelaColors.TextSecondary,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = VelaColors.Abyss,
                ),
            )
        },
        containerColor = VelaColors.Abyss,
    ) { paddingValues ->

        if (nodes.isEmpty()) {
            EmptyState(
                onConnectClick = { navController.navigate(Routes.CONNECT_NODE) },
                modifier       = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(
                    start  = 16.dp,
                    end    = 16.dp,
                    top    = paddingValues.calculateTopPadding() + 16.dp,
                    bottom = 96.dp, // clear the persistent Voice FAB
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(nodes, key = { it.id }) { node ->
                    NodeTileItem(
                        node    = node,
                        onClick = { navController.navigate(Routes.nodeDetail(node.id)) },
                    )
                }
            }
        }
    }
}

// ── Empty state ───────────────────────────────────────────────────────────────

/**
 * Shown when there are no SshNodes in the database.
 *
 * Spec: centered serif text + cyan "Connect a node" button.
 * No illustrations. No gimmicks. (DESIGN.md §9.11)
 */
@Composable
private fun EmptyState(
    onConnectClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier         = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text      = "No nodes connected yet",
                style     = MaterialTheme.typography.displaySmall,
                color     = VelaColors.TextSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onConnectClick,
                colors  = ButtonDefaults.buttonColors(
                    containerColor = VelaColors.Accent,
                    contentColor   = VelaColors.Abyss,
                ),
                shape    = RoundedCornerShape(24.dp),
                modifier = Modifier.height(48.dp),
            ) {
                Text(
                    text  = "Connect a node",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}
```

### Step 4: Run all HomeViewModelTest tests to verify they pass

```bash
./gradlew :app:testDebugUnitTest \
  --tests "com.vela.app.ui.home.HomeViewModelTest" \
  2>&1 | tail -15
```
Expected: `BUILD SUCCESSFUL` — 5 tests pass.

### Step 5: Commit

```bash
git add \
  app/src/main/kotlin/com/vela/app/ui/home/HomeScreen.kt \
  app/src/test/kotlin/com/vela/app/ui/home/HomeViewModelTest.kt
git commit -m "feat(home): add HomeScreen with node tile list and empty state"
```

---

## Task 5: Wire HomeScreen in AppNavigation

**Files:**
- Modify: `app/src/test/kotlin/com/vela/app/ui/navigation/AppNavigationTest.kt`
- Modify: `app/src/main/kotlin/com/vela/app/ui/navigation/AppNavigation.kt`

### Step 1: Add wiring test to AppNavigationTest.kt

Open `app/src/test/kotlin/com/vela/app/ui/navigation/AppNavigationTest.kt`.

Add this test inside the existing `AppNavigationTest` class (the file already exists from Phase 1):

```kotlin
    // ── Phase 2 wiring ────────────────────────────────────────────────────────

    @Test fun `AppNavigation sources HomeScreen (not placeholder)`() {
        val src = java.io.File(
            "src/main/kotlin/com/vela/app/ui/navigation/AppNavigation.kt"
        ).readText()
        assertThat(src).contains("HomeScreen(navController)")
        assertThat(src).doesNotContain("HomeScreenPlaceholder")
    }
```

### Step 2: Run to verify this test fails

```bash
./gradlew :app:testDebugUnitTest \
  --tests "com.vela.app.ui.navigation.AppNavigationTest.AppNavigation sources HomeScreen (not placeholder)" \
  2>&1 | tail -10
```
Expected: **FAILED** — source still contains `HomeScreenPlaceholder`.

### Step 3: Update AppNavigation.kt

Open `app/src/main/kotlin/com/vela/app/ui/navigation/AppNavigation.kt`.

**a) Add imports** near the top of the file, after the existing imports block:
```kotlin
import com.vela.app.ui.home.HomeScreen
```

**b) Replace the HomeScreenPlaceholder call** in the `NavHost` block:

Find:
```kotlin
            composable(Routes.HOME)           { HomeScreenPlaceholder(navController) }
```
Replace with:
```kotlin
            composable(Routes.HOME)           { HomeScreen(navController) }
```

**c) Delete the `HomeScreenPlaceholder` private function** — find and remove the entire block:
```kotlin
@Composable
private fun HomeScreenPlaceholder(navController: NavController) {
    Surface(modifier = Modifier.fillMaxSize(), color = VelaColors.Abyss) {
        Text(text = "Home — Nodes", color = VelaColors.TextPrimary)
    }
}
```

### Step 4: Run AppNavigationTest to verify it passes

```bash
./gradlew :app:testDebugUnitTest \
  --tests "com.vela.app.ui.navigation.AppNavigationTest" \
  2>&1 | tail -15
```
Expected: `BUILD SUCCESSFUL` — all AppNavigationTest tests pass (existing 10 + 1 new).

### Step 5: Commit

```bash
git add \
  app/src/main/kotlin/com/vela/app/ui/navigation/AppNavigation.kt \
  app/src/test/kotlin/com/vela/app/ui/navigation/AppNavigationTest.kt
git commit -m "feat(home): wire HomeScreen into NavHost, remove HomeScreenPlaceholder"
```

---

## Task 6: NodeDetailViewModel TDD

**Files:**
- Create: `app/src/test/kotlin/com/vela/app/ui/nodedetail/NodeDetailViewModelTest.kt`
- Create: `app/src/main/kotlin/com/vela/app/ui/nodedetail/NodeDetailViewModel.kt`

### Step 1: Write the failing test

Create `app/src/test/kotlin/com/vela/app/ui/nodedetail/NodeDetailViewModelTest.kt`:

```kotlin
package com.vela.app.ui.nodedetail

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.vela.app.data.db.SshNodeDao
import com.vela.app.data.db.SshNodeEntity
import com.vela.app.ssh.SshNodeRegistry
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
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NodeDetailViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before fun setUp()    { Dispatchers.setMain(testDispatcher) }
    @After  fun tearDown() { Dispatchers.resetMain() }

    // ── Fake DAO ─────────────────────────────────────────────────────────────

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
        nodeId: String,
        dao: FakeSshNodeDao = FakeSshNodeDao(),
    ): NodeDetailViewModel {
        val savedState = SavedStateHandle(mapOf("nodeId" to nodeId))
        return NodeDetailViewModel(savedState, SshNodeRegistry(dao))
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    fun `nodeId is read from SavedStateHandle`() {
        val vm = makeVm("node-42")
        assertThat(vm.nodeId).isEqualTo("node-42")
    }

    @Test
    fun `node is null initially when registry is empty`() {
        val vm = makeVm("node-42")
        assertThat(vm.node.value).isNull()
    }

    @Test
    fun `node emits matching SshNode when registry contains it`() = runTest {
        val dao = FakeSshNodeDao()
        val vm  = makeVm("node-42", dao)

        dao.nodeFlow.value = listOf(
            SshNodeEntity(id = "node-42", label = "Raspberry Pi 4",
                          hosts = "192.168.1.100", port = 22, username = "pi", addedAt = 0L),
            SshNodeEntity(id = "other",   label = "Other Node",
                          hosts = "10.0.0.1",      port = 22, username = "root", addedAt = 0L),
        )

        assertThat(vm.node.value?.id).isEqualTo("node-42")
        assertThat(vm.node.value?.label).isEqualTo("Raspberry Pi 4")
    }

    @Test
    fun `node is null when registry does not contain nodeId`() = runTest {
        val dao = FakeSshNodeDao()
        val vm  = makeVm("ghost-node", dao)

        dao.nodeFlow.value = listOf(
            SshNodeEntity(id = "different-id", label = "Some Node",
                          hosts = "10.0.0.1", port = 22, username = "pi", addedAt = 0L),
        )

        assertThat(vm.node.value).isNull()
    }

    @Test
    fun `node updates when registry emits a new list`() = runTest {
        val dao = FakeSshNodeDao()
        val vm  = makeVm("n1", dao)

        dao.nodeFlow.value = listOf(
            SshNodeEntity(id = "n1", label = "Before", hosts = "1.1.1.1",
                          port = 22, username = "u", addedAt = 0L),
        )
        assertThat(vm.node.value?.label).isEqualTo("Before")

        dao.nodeFlow.value = listOf(
            SshNodeEntity(id = "n1", label = "After", hosts = "1.1.1.1",
                          port = 22, username = "u", addedAt = 0L),
        )
        assertThat(vm.node.value?.label).isEqualTo("After")
    }
}
```

### Step 2: Run to verify it fails

```bash
./gradlew :app:testDebugUnitTest \
  --tests "com.vela.app.ui.nodedetail.NodeDetailViewModelTest" \
  2>&1 | tail -20
```
Expected: **BUILD FAILED** — `error: unresolved reference: NodeDetailViewModel`

### Step 3: Create NodeDetailViewModel.kt

Create `app/src/main/kotlin/com/vela/app/ui/nodedetail/NodeDetailViewModel.kt`:

```kotlin
package com.vela.app.ui.nodedetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vela.app.ssh.SshNode
import com.vela.app.ssh.SshNodeRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class NodeDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val registry: SshNodeRegistry,
    // Projects come from the amplifierd HTTP API — placeholder: returns empty list for now.
    // Phase 3 will inject an AmplifierdClient and expose a projects StateFlow.
) : ViewModel() {

    val nodeId: String = checkNotNull(savedStateHandle["nodeId"])

    val node: StateFlow<SshNode?> = registry.allFlow()
        .map { nodes -> nodes.find { it.id == nodeId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
```

### Step 4: Run to verify it passes

```bash
./gradlew :app:testDebugUnitTest \
  --tests "com.vela.app.ui.nodedetail.NodeDetailViewModelTest" \
  2>&1 | tail -15
```
Expected: `BUILD SUCCESSFUL` — 5 tests pass.

### Step 5: Commit

```bash
git add \
  app/src/main/kotlin/com/vela/app/ui/nodedetail/NodeDetailViewModel.kt \
  app/src/test/kotlin/com/vela/app/ui/nodedetail/NodeDetailViewModelTest.kt
git commit -m "feat(node-detail): add NodeDetailViewModel backed by SshNodeRegistry + SavedStateHandle"
```

---

## Task 7: ProjectCard + NodeDetailScreen Compose Components

**Files:**
- Create: `app/src/main/kotlin/com/vela/app/ui/nodedetail/ProjectCard.kt`
- Create: `app/src/main/kotlin/com/vela/app/ui/nodedetail/NodeDetailScreen.kt`
- Modify: `app/src/test/kotlin/com/vela/app/ui/nodedetail/NodeDetailViewModelTest.kt` (add structural assertions)

### Step 1: Add structural assertions to NodeDetailViewModelTest.kt

Append inside the `NodeDetailViewModelTest` class:

```kotlin
    // ── Structural: verify composables exist ──────────────────────────────────

    @Test fun `NodeDetailScreen source file exists`() {
        val src = java.io.File(
            "src/main/kotlin/com/vela/app/ui/nodedetail/NodeDetailScreen.kt"
        ).readText()
        assertThat(src).contains("fun NodeDetailScreen")
    }

    @Test fun `ProjectCard source file exists`() {
        val src = java.io.File(
            "src/main/kotlin/com/vela/app/ui/nodedetail/ProjectCard.kt"
        ).readText()
        assertThat(src).contains("fun ProjectCard")
    }
```

### Step 2: Run to verify they fail

```bash
./gradlew :app:testDebugUnitTest \
  --tests "com.vela.app.ui.nodedetail.NodeDetailViewModelTest.NodeDetailScreen source file exists" \
  --tests "com.vela.app.ui.nodedetail.NodeDetailViewModelTest.ProjectCard source file exists" \
  2>&1 | tail -10
```
Expected: **FAILED** — files do not exist.

### Step 3: Create ProjectCard.kt

Create `app/src/main/kotlin/com/vela/app/ui/nodedetail/ProjectCard.kt`:

```kotlin
package com.vela.app.ui.nodedetail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.matchParentSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vela.app.ui.theme.MonoMedium
import com.vela.app.ui.theme.VelaColors

/**
 * Project card — displayed in the Node Detail project list.
 *
 * Design spec: DESIGN.md §8 (Screen 2)
 * - Background: SurfaceSub, 20dp corner radius, 16dp padding
 * - 4dp leading status stripe (Accent at 0.4 — idle in Phase 2, no project sessions yet)
 * - Project name: titleLarge (Inter 18sp/600) — NOT serif. Below 22sp threshold.
 * - Bundle tag: JetBrains Mono 10sp, TextTertiary
 * - Tap → onTap callback (caller navigates to session list)
 */
@Composable
fun ProjectCard(
    projectName: String,
    bundleTag: String = "",
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick  = onTap,
        modifier = modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(20.dp),
        color    = VelaColors.SurfaceSub,
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {

            // 4dp leading status stripe — Accent at 0.4 (idle, no sessions in Phase 2)
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .matchParentSize()
                    .clip(RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp))
                    .background(VelaColors.Accent.copy(alpha = 0.4f)),
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                // Project name — Inter 700 18sp (titleLarge). NOT serif. (DESIGN.md §9.14)
                Text(
                    text  = projectName,
                    style = MaterialTheme.typography.titleLarge,
                    color = VelaColors.TextPrimary,
                )
                if (bundleTag.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    // Bundle tag — JetBrains Mono 10sp (DESIGN.md §8)
                    Text(
                        text  = "bundle: $bundleTag",
                        style = MonoMedium.copy(fontSize = 10.sp),
                        color = VelaColors.TextTertiary,
                    )
                }
            }
        }
    }
}
```

### Step 4: Create NodeDetailScreen.kt

Create `app/src/main/kotlin/com/vela/app/ui/nodedetail/NodeDetailScreen.kt`:

```kotlin
package com.vela.app.ui.nodedetail

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.vela.app.ssh.BootstrapStatus
import com.vela.app.ssh.SshNode
import com.vela.app.ui.navigation.Routes
import com.vela.app.ui.theme.VelaColors

/**
 * Node detail screen — projects list for a single SshNode.
 *
 * The hero block (node name at displayLarge / Instrument Serif 48sp) is the
 * design's signature moment. The rest of the screen is a list of project cards
 * (empty in Phase 2 — project data comes from the amplifierd HTTP API in Phase 3).
 *
 * Design spec: DESIGN.md §8 (Screen 2)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeDetailScreen(
    navController: NavController,
    viewModel: NodeDetailViewModel = hiltViewModel(),
) {
    val node by viewModel.node.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    // Back chevron in accent color (DESIGN.md §8)
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector        = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint               = VelaColors.Accent,
                        )
                    }
                },
                title  = {},
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = VelaColors.Abyss,
                ),
            )
        },
        containerColor = VelaColors.Abyss,
    ) { paddingValues ->
        LazyColumn(
            contentPadding = PaddingValues(
                start  = 24.dp,
                end    = 24.dp,
                top    = paddingValues.calculateTopPadding() + 32.dp,
                bottom = 96.dp, // clear Voice FAB
            ),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {

            // ── Hero block ────────────────────────────────────────────────────
            item(key = "hero") {
                // Node name — Instrument Serif 48sp (displayLarge). This is the
                // design's signature moment. (DESIGN.md §3, §8)
                Text(
                    text  = node?.label ?: "",
                    style = MaterialTheme.typography.displayLarge,
                    color = VelaColors.TextPrimary,
                )
                Spacer(modifier = Modifier.height(12.dp))
                // Telemetry meta — Inter 12sp, TextSecondary
                Text(
                    text  = buildNodeTelemetry(node),
                    style = MaterialTheme.typography.labelMedium,
                    color = VelaColors.TextSecondary,
                )
                Spacer(modifier = Modifier.height(32.dp))
                // Section eyebrow — Inter 700 uppercase, 2dp letter-spacing, TextTertiary
                Text(
                    text          = "PROJECTS",
                    style         = MaterialTheme.typography.labelSmall,
                    color         = VelaColors.TextTertiary,
                    letterSpacing = 2.sp,
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            // ── Project list ──────────────────────────────────────────────────
            // Phase 2: no project data yet. A single "New project" add-card is shown.
            // Phase 3 will inject a projects StateFlow and map it into ProjectCard items.

            item(key = "new-project-placeholder") {
                NewProjectPlaceholder(
                    onTap = { /* Phase 3: show new-project sheet */ }
                )
            }
        }
    }
}

// ── Private composables ───────────────────────────────────────────────────────

/**
 * Dashed-border placeholder card for "New project".
 * Replaced in Phase 3 when real project creation is wired.
 */
@Composable
private fun NewProjectPlaceholder(onTap: () -> Unit) {
    Box(
        modifier = androidx.compose.ui.Modifier
            .fillMaxWidth()
            .height(72.dp)
            .border(
                width = 1.dp,
                color = VelaColors.StrokeEdge,
                shape = RoundedCornerShape(20.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text  = "+ New project",
            style = MaterialTheme.typography.labelLarge,
            color = VelaColors.TextTertiary,
        )
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun buildNodeTelemetry(node: SshNode?): String {
    if (node == null) return ""
    val status = if (node.bootstrapStatus == BootstrapStatus.RUNNING) "online" else "offline"
    return "$status · 0 active sessions · connected"
}
```

### Step 5: Run all NodeDetailViewModelTest tests to verify they pass

```bash
./gradlew :app:testDebugUnitTest \
  --tests "com.vela.app.ui.nodedetail.NodeDetailViewModelTest" \
  2>&1 | tail -15
```
Expected: `BUILD SUCCESSFUL` — all 7 tests pass.

### Step 6: Commit

```bash
git add \
  app/src/main/kotlin/com/vela/app/ui/nodedetail/ProjectCard.kt \
  app/src/main/kotlin/com/vela/app/ui/nodedetail/NodeDetailScreen.kt \
  app/src/test/kotlin/com/vela/app/ui/nodedetail/NodeDetailViewModelTest.kt
git commit -m "feat(node-detail): add NodeDetailScreen hero block, ProjectCard, and NewProjectPlaceholder"
```

---

## Task 8: Wire NodeDetailScreen in AppNavigation

**Files:**
- Modify: `app/src/test/kotlin/com/vela/app/ui/navigation/AppNavigationTest.kt`
- Modify: `app/src/main/kotlin/com/vela/app/ui/navigation/AppNavigation.kt`

### Step 1: Add wiring test to AppNavigationTest.kt

Append inside the `AppNavigationTest` class:

```kotlin
    @Test fun `AppNavigation sources NodeDetailScreen (not placeholder)`() {
        val src = java.io.File(
            "src/main/kotlin/com/vela/app/ui/navigation/AppNavigation.kt"
        ).readText()
        assertThat(src).contains("NodeDetailScreen(navController)")
        assertThat(src).doesNotContain("NodeDetailPlaceholder")
    }
```

### Step 2: Run to verify it fails

```bash
./gradlew :app:testDebugUnitTest \
  --tests "com.vela.app.ui.navigation.AppNavigationTest.AppNavigation sources NodeDetailScreen (not placeholder)" \
  2>&1 | tail -10
```
Expected: **FAILED** — source still contains `NodeDetailPlaceholder`.

### Step 3: Update AppNavigation.kt

Open `app/src/main/kotlin/com/vela/app/ui/navigation/AppNavigation.kt`.

**a) Add import** (with the existing imports):
```kotlin
import com.vela.app.ui.nodedetail.NodeDetailScreen
```

**b) Replace the NodeDetailPlaceholder call** in the `NavHost` block:

Find:
```kotlin
            composable(Routes.NODE_DETAIL)    { NodeDetailPlaceholder(navController) }
```
Replace with:
```kotlin
            composable(Routes.NODE_DETAIL)    { NodeDetailScreen(navController) }
```

**c) Delete the `NodeDetailPlaceholder` private function** — find and remove:
```kotlin
@Composable
private fun NodeDetailPlaceholder(navController: NavController) {
    Surface(modifier = Modifier.fillMaxSize(), color = VelaColors.Abyss) {
        Text(text = "Node Detail", color = VelaColors.TextPrimary)
    }
}
```

### Step 4: Run all AppNavigationTest to verify they pass

```bash
./gradlew :app:testDebugUnitTest \
  --tests "com.vela.app.ui.navigation.AppNavigationTest" \
  2>&1 | tail -15
```
Expected: `BUILD SUCCESSFUL` — all 12 AppNavigationTest tests pass.

### Step 5: Commit

```bash
git add \
  app/src/main/kotlin/com/vela/app/ui/navigation/AppNavigation.kt \
  app/src/test/kotlin/com/vela/app/ui/navigation/AppNavigationTest.kt
git commit -m "feat(node-detail): wire NodeDetailScreen into NavHost, remove NodeDetailPlaceholder"
```

---

## Task 9: Full Build Verification + Smoke Test

### Step 1: Run the complete unit test suite

Run from `/Users/ken/workspace/vela`:
```bash
./gradlew :app:testDebugUnitTest 2>&1 | tail -15
```
Expected: `BUILD SUCCESSFUL` — all tests pass with no regressions.

If tests fail, read the full output:
```bash
./gradlew :app:testDebugUnitTest 2>&1 | grep -E "FAILED|error:"
```

Common causes:
- Import conflicts in `NodeTile.kt` — verify the new imports were added at the top of the file, not mid-file.
- `unresolved reference: MonoMedium` — Phase 1 must be complete; `MonoMedium` is a top-level `val` in `com.vela.app.ui.theme.Type`.
- `unresolved reference: VelaColors` — Phase 1 must be complete; check `com.vela.app.ui.theme.VelaColors` exists.

### Step 2: Run assembleDebug

```bash
./gradlew assembleDebug 2>&1 | tail -5
```
Expected: `BUILD SUCCESSFUL`

If it fails:
```bash
./gradlew assembleDebug 2>&1 | grep -E "error:|BUILD"
```

Common causes and fixes:
- `unresolved reference: hiltViewModel` — verify `hilt.navigation.compose` dependency is present in `app/build.gradle.kts`.
- `unresolved reference: Icons.AutoMirrored` — add `implementation(libs.androidx.compose.material.icons.extended)` if missing, or replace with `Icons.Default.ArrowBack`.
- Type mismatch on `Modifier.Companion` — ensure `import androidx.compose.ui.Modifier` is present in `NodeDetailScreen.kt`.

### Step 3: Install and smoke test on device/emulator

```bash
./gradlew installDebug 2>&1 | tail -5
```

Launch the app and verify:

| Screen | Check | Expected |
|--------|-------|----------|
| Home (empty state) | Background | Deep indigo `#0B0E1A` — not black, not grey |
| Home (empty state) | Empty state text | Serif "No nodes connected yet" visible, centered |
| Home (empty state) | Button | Cyan "Connect a node" button present, no illustration |
| Home (with nodes) | Node tile | Serif node name at large scale (headlineLarge), not body text |
| Home (with nodes) | Tile stripe | Thin 4dp stripe on left edge of each tile, cyan at low opacity |
| Home (with nodes) | Status chip | Small IDLE chip, dark container, low contrast text |
| Home (with nodes) | Tile tap | Navigates to Node Detail screen |
| Node Detail | Back | Cyan back chevron in top-left |
| Node Detail | Hero title | Node name in large Instrument Serif (48sp), not Inter |
| Node Detail | Telemetry | Small Inter text below hero name |
| Node Detail | Section label | "PROJECTS" all-caps, small, tracked out |
| Node Detail | Placeholder card | Dashed border "+ New project" card |
| Node Detail | Voice FAB | Still present at bottom-right |
| Both screens | NO bottom nav | No bottom navigation bar of any kind |

### Step 4: Final commit

If no issues were found, no commit needed. If any minor fixes were required:
```bash
git add -A
git commit -m "fix(ui-phase2): smoke test corrections"
```

---

## Phase 2 Complete — Verification Checklist

Before declaring Phase 2 done, confirm all of the following:

- [ ] `HomeViewModelTest` — 5 tests pass (including 1 structural)
- [ ] `NodeTileColorTest` — 19 tests pass (18 logic + 1 structural)
- [ ] `NodeDetailViewModelTest` — 7 tests pass (including 2 structural)
- [ ] `AppNavigationTest` — 12 tests pass (10 from Phase 1 + 2 wiring)
- [ ] `./gradlew :app:testDebugUnitTest` → `BUILD SUCCESSFUL` (no regressions)
- [ ] `./gradlew assembleDebug` → `BUILD SUCCESSFUL`
- [ ] Home screen renders with indigo background, serif tile names, 4dp status stripe
- [ ] Home screen empty state: serif text + cyan button, no illustration
- [ ] Node detail hero: Instrument Serif 48sp node name — this is THE visual moment
- [ ] Node detail "PROJECTS" eyebrow: uppercase, tracked, tertiary color
- [ ] No bottom navigation bar on either screen
- [ ] No blue anywhere (`#3B82F6` forbidden — accent is `#5EEAD4`)
- [ ] No Instrument Serif below 22sp (project names, telemetry, chips all use Inter)
- [ ] Voice FAB still visible at bottom-right on both screens (untouched from Phase 1)
- [ ] `AppNavigation.kt` contains no `HomeScreenPlaceholder` or `NodeDetailPlaceholder` references

---

## What Phase 3 Builds On

Phase 2 delivers two real screens using design-system tokens. Phase 3 can now:

- Call `nodeStatusFor(node)` with a session-list argument to return `Running`/`Waiting`/`Done` from real data
- Inject an `AmplifierdClient` into `NodeDetailViewModel` and expose a `projects` `StateFlow`
- Populate `ProjectCard` items in `NodeDetailScreen` from live project data
- Wire `ProjectCard.onTap` → `navController.navigate(Routes.sessionList(nodeId, project.id))`
- Add settings navigation to the gear icon in `HomeScreen`'s top bar
