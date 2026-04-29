# Phase 4: Android Bootstrap UI Implementation Plan

> **Execution:** Use the subagent-driven-development workflow to implement this plan.

**Goal:** Wire the Compose UI for bootstrapping a node — extend `ConnectorsScreen.kt` with a "Bootstrap amplifierd" form, add `NodeBootstrapSheet.kt` showing live progress, and extend `NodesViewModel` to drive `NodeBootstrapper` and expose its event stream as UI state.

**Architecture:** `NodesViewModel` gains a `BootstrapUiState` `StateFlow` driven by collecting `NodeBootstrapper.bootstrap()` on `Dispatchers.IO`. The UI shows a bottom-sheet (`NodeBootstrapSheet`) whenever `isBootstrapping || isComplete`. Step indicators, scrolling log area, and error/done states are rendered from the `BootstrapUiState`. Tests cover the ViewModel reducer logic only — the Composable rendering is verified by running the app.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, Hilt, kotlinx.coroutines `Flow`/`StateFlow`, JUnit 4, Google Truth, hand-rolled fakes (no Mockito/MockK).

**Design Reference:** `docs/plans/2026-04-29-amplifierd-node-bootstrap-design.md` Section 3.

**Commit convention:** `feat(bootstrap-ui): <description>`

---

## Prerequisites

**Phases 2 AND 3 must be complete and committed.** This plan assumes the following exist:

- `BootstrapEvent` sealed class with `Output(line)`, `StepStart(step)`, `StepComplete(step)`, `Failed(step, error, logs)`, `Complete(url, token)` — at `app/src/main/kotlin/com/vela/app/ssh/BootstrapEvent.kt`
- `BootstrapStep` enum: `DETECT, INSTALL_UV, INSTALL_AMPLIFIERD, WRITE_CONFIG, INSTALL_SERVICE, VERIFY` — same file
- `BootstrapStatus` enum on `SshNode`
- `NodeBootstrapper` class at `app/src/main/kotlin/com/vela/app/ssh/NodeBootstrapper.kt` with method:
  ```kotlin
  fun bootstrap(
      nodeId: String,
      host: String,
      port: Int,
      username: String,
      bundle: BundleChoice,
      anthropicKey: String,
  ): Flow<BootstrapEvent>
  ```
- `BundleChoice` enum (with at minimum `superpowers` and a `label: String` property) — defined alongside `NodeBootstrapper`
- `SshNodeRegistry.promoteToAmplifierd(nodeId, url, token)` method

If any are missing, **stop and complete the prior phase first.** Do not stub them.

## Audience Note

The implementer:
- Knows Kotlin and Jetpack Compose but **not** this codebase
- Should follow `ConnectorsScreen.kt` (`app/src/main/kotlin/com/vela/app/ui/connectors/ConnectorsScreen.kt`) for screen/composable patterns — Material 3 (`androidx.compose.material3.*`), `OutlinedTextField`, `OutlinedButton`/`Button`, `AnimatedVisibility(expandVertically()/shrinkVertically())`, private sub-composables in the same file, `MaterialTheme.colorScheme` for colors
- Should follow `NodesViewModel.kt` (`app/src/main/kotlin/com/vela/app/ui/nodes/NodesViewModel.kt`) for ViewModel patterns — `@HiltViewModel`, `MutableStateFlow`/`StateFlow`, `viewModelScope.launch(Dispatchers.IO) { … }`
- Should follow `app/src/test/kotlin/com/vela/app/ui/profile/ProfileViewModelTest.kt` for test style — JUnit 4, `com.google.common.truth.Truth.assertThat`, `runTest {}` from `kotlinx-coroutines-test`, hand-rolled fakes (no Mockito/MockK)

---

## Files

**Create:**
- `app/src/main/kotlin/com/vela/app/ui/connectors/NodeBootstrapSheet.kt`
- `app/src/androidTest/kotlin/com/vela/app/ui/nodes/NodesViewModelBootstrapTest.kt`

**Modify:**
- `app/src/main/kotlin/com/vela/app/ssh/NodeBootstrapper.kt` (add `open` modifiers — prep)
- `app/src/main/kotlin/com/vela/app/ui/nodes/NodesViewModel.kt` (add bootstrap state + actions; inject `NodeBootstrapper`)
- `app/src/main/kotlin/com/vela/app/ui/connectors/ConnectorsScreen.kt` (wire bootstrap form + sheet)

---

## Task 1: Prep — make `NodeBootstrapper` open for test fakes

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/ssh/NodeBootstrapper.kt`

**Step 1: Open the class**

Open the file. Change the class declaration from:

```kotlin
class NodeBootstrapper @Inject constructor(...)
```

to:

```kotlin
open class NodeBootstrapper @Inject constructor(...)
```

**Step 2: Open the `bootstrap` method**

Find the `fun bootstrap(...)` method that returns `Flow<BootstrapEvent>`. Add the `open` modifier:

```kotlin
open fun bootstrap(
    nodeId: String,
    host: String,
    port: Int,
    username: String,
    bundle: BundleChoice,
    anthropicKey: String,
): Flow<BootstrapEvent> = ...
```

**Step 3: Open `SshKeyManager` class and `getPublicKey()` method**

Open the file `app/src/main/kotlin/com/vela/app/ssh/SshKeyManager.kt`. Add the `open` modifier to the class declaration:

```kotlin
open class SshKeyManager @Inject constructor(...)
```

Find the `getPublicKey()` method and add the `open` modifier:

```kotlin
open fun getPublicKey(): String = ...
```

This is required for `FakeKeyManager` to override `getPublicKey()` in instrumented tests.

**Step 4: Compile to verify**

Run: `cd /Users/ken/workspace/vela && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

**Step 5: Commit**

```
git add app/src/main/kotlin/com/vela/app/ssh/NodeBootstrapper.kt
git commit -m "feat(bootstrap-ui): open NodeBootstrapper for test fakes"
```

---

## Task 2: Add `BootstrapUiState` data class

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/ui/nodes/NodesViewModel.kt`

**Step 1: Add the data class above the `NodesViewModel` class**

Open `app/src/main/kotlin/com/vela/app/ui/nodes/NodesViewModel.kt`. Add this data class at the **top of the file**, after the imports and before the `@HiltViewModel` line:

```kotlin
/**
 * UI state for the amplifierd bootstrap flow. Driven by NodeBootstrapper events
 * collected by NodesViewModel.bootstrapNode().
 */
data class BootstrapUiState(
    val isBootstrapping: Boolean = false,
    val currentStep: com.vela.app.ssh.BootstrapStep? = null,
    val completedSteps: Set<com.vela.app.ssh.BootstrapStep> = emptySet(),
    val logLines: List<String> = emptyList(),
    val errorMessage: String? = null,
    val isComplete: Boolean = false,
)
```

(Use fully-qualified names here so this task touches one file with no new import lines yet — Task 4 will add the imports.)

**Step 2: Compile to verify**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

**Step 3: Commit**

```
git add app/src/main/kotlin/com/vela/app/ui/nodes/NodesViewModel.kt
git commit -m "feat(bootstrap-ui): add BootstrapUiState data class"
```

---

## Task 3: Write the failing `NodesViewModel` bootstrap tests

**Files:**
- Create: `app/src/test/kotlin/com/vela/app/ui/nodes/NodesViewModelBootstrapTest.kt`

**Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/vela/app/ui/nodes/NodesViewModelBootstrapTest.kt`:

```kotlin
package com.vela.app.ui.nodes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.vela.app.ssh.BootstrapEvent
import com.vela.app.ssh.BootstrapStep
import com.vela.app.ssh.BundleChoice
import com.vela.app.ssh.NodeBootstrapper
import com.vela.app.ssh.SshKeyManager
import com.vela.app.ssh.SshNodeRegistry
import com.vela.app.ssh.SshNodeDao
import com.vela.app.ssh.SshNodeEntity
import com.vela.app.ssh.BootstrapStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for the bootstrap state machine inside NodesViewModel.
 *
 * The fake NodeBootstrapper emits a configurable sequence of BootstrapEvent
 * values; the test asserts that bootstrapState transitions correctly.
 *
 * No Mockito / MockK — hand-rolled fakes per project convention.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class NodesViewModelBootstrapTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before fun setUp()    { Dispatchers.setMain(mainDispatcher) }
    @After  fun tearDown() { Dispatchers.resetMain() }

    // ── Fakes ───────────────────────────────────────────────────────────────

    private class FakeNodeBootstrapper(
        private val events: List<BootstrapEvent>,
        keyManager: SshKeyManager,
        registry: SshNodeRegistry,
    ) : NodeBootstrapper(keyManager, registry) {
        override suspend fun bootstrap(
            nodeId: String,
            host: String,
            port: Int,
            username: String,
            bundle: BundleChoice,
            anthropicKey: String,
        ): Flow<BootstrapEvent> = kotlinx.coroutines.flow.flow {
            events.forEach { emit(it) }
        }
    }

    private class FakeSshNodeDao : SshNodeDao {
        override fun getAllNodes(): kotlinx.coroutines.flow.Flow<List<SshNodeEntity>> = kotlinx.coroutines.flow.emptyFlow()
        override suspend fun insert(node: SshNodeEntity) {}
        override suspend fun delete(id: String) {}
        override suspend fun getById(id: String): SshNodeEntity? = null
        override suspend fun updateBootstrapStatus(id: String, status: String) {}
        override suspend fun promoteToAmplifierd(id: String, type: String, url: String, token: String, status: String) {}
    }

    private class FakeRegistry : SshNodeRegistry(dao = FakeSshNodeDao()) {
        val statusUpdates = mutableListOf<Pair<String, BootstrapStatus>>()
        var promoted: Triple<String, String, String>? = null
        override suspend fun updateBootstrapStatus(nodeId: String, status: BootstrapStatus) {
            statusUpdates += nodeId to status
        }
        override suspend fun promoteToAmplifierd(nodeId: String, url: String, token: String) {
            promoted = Triple(nodeId, url, token)
        }
    }

    private class FakeKeyManager(context: Context) : SshKeyManager(context) {
        override fun getPublicKey(): String = "ssh-rsa AAAAB3N fake-test-key test@vela"
    }

    private fun newVm(events: List<BootstrapEvent> = emptyList()): NodesViewModel {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val registry = FakeRegistry()
        val keyManager = FakeKeyManager(ctx)
        val bootstrapper = FakeNodeBootstrapper(events, keyManager, registry)
        return NodesViewModel(registry = registry, keyManager = keyManager, bootstrapper = bootstrapper)
    }

    private fun trigger(vm: NodesViewModel) =
        vm.bootstrapNode(
            nodeId       = "n1",
            host         = "10.0.0.1",
            port         = 22,
            username     = "ken",
            bundle       = BundleChoice.SUPERPOWERS,
            anthropicKey = "sk-test",
        )

    // ── Tests ───────────────────────────────────────────────────────────────

    @Test
    fun `bootstrapNode sets isBootstrapping=true immediately`() = runTest {
        val vm = newVm(emptyList())
        trigger(vm)
        assertThat(vm.bootstrapState.value.isBootstrapping).isTrue()
    }

    @Test
    fun `Output events append to logLines in order`() = runTest {
        val vm = newVm(listOf(
            BootstrapEvent.Output("line one"),
            BootstrapEvent.Output("line two"),
        ))
        trigger(vm)
        advanceUntilIdle()
        assertThat(vm.bootstrapState.value.logLines)
            .containsExactly("line one", "line two").inOrder()
    }

    @Test
    fun `StepStart updates currentStep`() = runTest {
        val vm = newVm(listOf(BootstrapEvent.StepStart(BootstrapStep.INSTALL_UV)))
        trigger(vm)
        advanceUntilIdle()
        assertThat(vm.bootstrapState.value.currentStep).isEqualTo(BootstrapStep.INSTALL_UV)
    }

    @Test
    fun `StepComplete adds step to completedSteps`() = runTest {
        val vm = newVm(listOf(
            BootstrapEvent.StepComplete(BootstrapStep.DETECT),
            BootstrapEvent.StepComplete(BootstrapStep.INSTALL_UV),
        ))
        trigger(vm)
        advanceUntilIdle()
        assertThat(vm.bootstrapState.value.completedSteps)
            .containsExactly(BootstrapStep.DETECT, BootstrapStep.INSTALL_UV)
    }

    @Test
    fun `Failed sets errorMessage clears isBootstrapping and appends logs`() = runTest {
        val vm = newVm(listOf(
            BootstrapEvent.Output("running…"),
            BootstrapEvent.Failed(
                step  = BootstrapStep.INSTALL_AMPLIFIERD,
                error = "exit 1",
                logs  = listOf("err line"),
            ),
        ))
        trigger(vm)
        advanceUntilIdle()
        val s = vm.bootstrapState.value
        assertThat(s.isBootstrapping).isFalse()
        assertThat(s.errorMessage).isEqualTo("exit 1")
        assertThat(s.logLines).containsExactly("running…", "err line").inOrder()
    }

    @Test
    fun `Complete sets isComplete=true and clears isBootstrapping`() = runTest {
        val vm = newVm(listOf(BootstrapEvent.Complete("http://10.0.0.1:8410", "tok")))
        trigger(vm)
        advanceUntilIdle()
        val s = vm.bootstrapState.value
        assertThat(s.isComplete).isTrue()
        assertThat(s.isBootstrapping).isFalse()
    }

    @Test
    fun `clearBootstrapState resets to defaults`() = runTest {
        val vm = newVm(listOf(
            BootstrapEvent.Output("hello"),
            BootstrapEvent.Complete("http://x", "t"),
        ))
        trigger(vm)
        advanceUntilIdle()
        assertThat(vm.bootstrapState.value.isComplete).isTrue()

        vm.clearBootstrapState()

        assertThat(vm.bootstrapState.value).isEqualTo(BootstrapUiState())
    }
}
```

**Step 2: Run the tests to verify they fail**

Run: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.vela.app.ui.nodes.NodesViewModelBootstrapTest`
Expected: FAIL — most likely a compile error: "No value passed for parameter 'bootstrapper'", or "unresolved reference: bootstrapState / bootstrapNode / clearBootstrapState".

If `FakeRegistry` or `FakeKeyManager` cannot be constructed because the real classes don't have suitable constructors for tests, **stop and ask** — those classes are owned by Phases 2/3 and may need adjustment. (If `SshNodeRegistry` requires a `dao`, you can pass a hand-rolled fake DAO — see how Phase 2 tests construct the registry.) Update `newVm` accordingly. The point of this task is to assert the **VM contract**; how the fakes get built is a mechanical concern.

**Step 3: Commit the failing test**

```
git add app/src/test/kotlin/com/vela/app/ui/nodes/NodesViewModelBootstrapTest.kt
git commit -m "feat(bootstrap-ui): add failing NodesViewModel bootstrap tests"
```

---

## Task 4: Implement `NodesViewModel` bootstrap state machine

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/ui/nodes/NodesViewModel.kt`

**Step 1: Add imports and inject `NodeBootstrapper`**

Open `NodesViewModel.kt`. Add these imports (alphabetical, near the existing imports):

```kotlin
import com.vela.app.ssh.BootstrapEvent
import com.vela.app.ssh.BootstrapStep
import com.vela.app.ssh.BundleChoice
import com.vela.app.ssh.NodeBootstrapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
```

Replace the existing constructor:

```kotlin
class NodesViewModel @Inject constructor(
    private val registry: SshNodeRegistry,
    private val keyManager: SshKeyManager,
) : ViewModel() {
```

with:

```kotlin
class NodesViewModel @Inject constructor(
    private val registry: SshNodeRegistry,
    private val keyManager: SshKeyManager,
    private val bootstrapper: NodeBootstrapper,
) : ViewModel() {
```

**Step 2: Replace the fully-qualified `BootstrapUiState` with imported names**

In the `BootstrapUiState` data class added in Task 2, change `com.vela.app.ssh.BootstrapStep` to just `BootstrapStep` (now imported).

**Step 3: Add bootstrap state, action, and reset method**

Inside the `NodesViewModel` class body, **above the closing brace** (after `fun clearError()`), add:

```kotlin
    // ── Bootstrap flow ──────────────────────────────────────────────────────

    private val _bootstrapState = MutableStateFlow(BootstrapUiState())
    val bootstrapState: StateFlow<BootstrapUiState> = _bootstrapState

    /**
     * Drive the amplifierd bootstrap pipeline against [host] for the SSH node
     * identified by [nodeId]. Updates [bootstrapState] live as events arrive.
     */
    fun bootstrapNode(
        nodeId: String,
        host: String,
        port: Int,
        username: String,
        bundle: BundleChoice,
        anthropicKey: String,
    ) {
        _bootstrapState.value = BootstrapUiState(isBootstrapping = true)
        viewModelScope.launch(Dispatchers.IO) {
            bootstrapper.bootstrap(nodeId, host, port, username, bundle, anthropicKey)
                .collect { event ->
                    when (event) {
                        is BootstrapEvent.Output ->
                            _bootstrapState.update { it.copy(logLines = it.logLines + event.line) }
                        is BootstrapEvent.StepStart ->
                            _bootstrapState.update { it.copy(currentStep = event.step) }
                        is BootstrapEvent.StepComplete ->
                            _bootstrapState.update { it.copy(completedSteps = it.completedSteps + event.step) }
                        is BootstrapEvent.Failed ->
                            _bootstrapState.update {
                                it.copy(
                                    isBootstrapping = false,
                                    errorMessage    = event.error,
                                    logLines        = it.logLines + event.logs,
                                )
                            }
                        is BootstrapEvent.Complete ->
                            _bootstrapState.update { it.copy(isBootstrapping = false, isComplete = true) }
                    }
                }
        }
    }

    /** Reset bootstrap UI state to defaults (e.g. after the user dismisses the sheet). */
    fun clearBootstrapState() {
        _bootstrapState.value = BootstrapUiState()
    }
```

**Step 4: Run the tests to verify they pass**

Run: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.vela.app.ui.nodes.NodesViewModelBootstrapTest`
Expected: PASS — 7 tests.

**Step 5: Compile the rest of the app**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. (The new `bootstrapper` constructor parameter must be Hilt-providable — `NodeBootstrapper` is `@Inject constructor(…)` from Phase 3, so Hilt resolves it automatically.)

**Step 6: Commit**

```
git add app/src/main/kotlin/com/vela/app/ui/nodes/NodesViewModel.kt
git commit -m "feat(bootstrap-ui): wire NodeBootstrapper into NodesViewModel"
```

---

## Task 5: Scaffold `NodeBootstrapSheet.kt` (container + drag handle, no logic)

**Files:**
- Create: `app/src/main/kotlin/com/vela/app/ui/connectors/NodeBootstrapSheet.kt`

**Step 1: Create the file with the bare composable shell**

```kotlin
package com.vela.app.ui.connectors

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.vela.app.ui.nodes.BootstrapUiState

/**
 * Bottom-sheet content showing live amplifierd bootstrap progress.
 *
 * Hosted by ConnectorsScreen — it appears whenever
 * `bootstrapState.isBootstrapping || bootstrapState.isComplete`.
 */
@Composable
fun NodeBootstrapSheet(
    state: BootstrapUiState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        color    = cs.surfaceContainerHigh,  // "surface-raised"
    ) {
        Column(
            modifier            = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Drag handle: 36×4dp, onSurfaceVariant
            Box(
                Modifier
                    .size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(cs.onSurfaceVariant),
            )
            // (step indicator, log area, error/complete states will be added in later tasks)
        }
    }
}
```

**Step 2: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

**Step 3: Commit**

```
git add app/src/main/kotlin/com/vela/app/ui/connectors/NodeBootstrapSheet.kt
git commit -m "feat(bootstrap-ui): scaffold NodeBootstrapSheet (container + drag handle)"
```

---

## Task 6: Add step-indicator row to `NodeBootstrapSheet`

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/ui/connectors/NodeBootstrapSheet.kt`

**Step 1: Add imports**

Add these to the top of `NodeBootstrapSheet.kt`:

```kotlin
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.vela.app.ssh.BootstrapStep
```

**Step 2: Add the private step-indicator row helper**

Add at the bottom of the file (still inside `package`):

```kotlin
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StepIndicatorRow(state: BootstrapUiState) {
    val cs = MaterialTheme.colorScheme
    FlowRow(
        modifier              = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement   = Arrangement.spacedBy(8.dp),
    ) {
        BootstrapStep.entries.forEach { step ->
            val isCurrent   = state.currentStep == step && !state.completedSteps.contains(step)
            val isCompleted = state.completedSteps.contains(step)
            Row(
                modifier              = Modifier
                    .background(cs.surfaceContainer, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                when {
                    isCompleted -> Text("✓", color = Color(0xFF34C759), fontWeight = FontWeight.Bold)
                    isCurrent   -> CircularProgressIndicator(
                        modifier  = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                    )
                    else        -> Text("○", color = cs.onSurfaceVariant.copy(alpha = 0.5f))
                }
                Text(
                    text  = stepLabel(step),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isCompleted || isCurrent) cs.onSurface
                            else cs.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
    }
}

private fun stepLabel(step: BootstrapStep): String = when (step) {
    BootstrapStep.CONNECT             -> "Connect"
    BootstrapStep.DETECT              -> "Detect"
    BootstrapStep.INSTALL_UV          -> "Install uv"
    BootstrapStep.INSTALL_AMPLIFIERD  -> "Install amplifierd"
    BootstrapStep.WRITE_CONFIG        -> "Config"
    BootstrapStep.INSTALL_SERVICE     -> "Service"
    BootstrapStep.HEALTH_CHECK        -> "Health check"
    BootstrapStep.PROMOTE             -> "Promote"
}
```

**Step 3: Wire the indicator into the sheet body**

Inside the existing `Column` of `NodeBootstrapSheet`, **after the drag-handle `Box`**, add:

```kotlin
            StepIndicatorRow(state)
```

**Step 4: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

**Step 5: Commit**

```
git add app/src/main/kotlin/com/vela/app/ui/connectors/NodeBootstrapSheet.kt
git commit -m "feat(bootstrap-ui): add step indicator row to NodeBootstrapSheet"
```

---

## Task 7: Add scrollable log area with auto-scroll

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/ui/connectors/NodeBootstrapSheet.kt`

**Step 1: Add imports**

```kotlin
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.text.font.FontFamily
```

**Step 2: Add the private log-area helper**

Add at the bottom of the file:

```kotlin
@Composable
private fun BootstrapLogArea(logLines: List<String>) {
    val cs = MaterialTheme.colorScheme
    val listState = rememberLazyListState()

    // Auto-scroll to the bottom whenever a new line arrives
    LaunchedEffect(logLines.size) {
        if (logLines.isNotEmpty()) {
            listState.animateScrollToItem(logLines.size - 1)
        }
    }

    LazyColumn(
        state    = listState,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 300.dp)
            .background(cs.surfaceContainer, RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(logLines) { line ->
            Text(
                text  = line,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = cs.onSurface,
            )
        }
    }
}
```

**Step 3: Wire the log area into the sheet body**

Inside the `Column` in `NodeBootstrapSheet`, **after `StepIndicatorRow(state)`**, add:

```kotlin
            BootstrapLogArea(state.logLines)
```

**Step 4: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

**Step 5: Commit**

```
git add app/src/main/kotlin/com/vela/app/ui/connectors/NodeBootstrapSheet.kt
git commit -m "feat(bootstrap-ui): add log area with auto-scroll to NodeBootstrapSheet"
```

---

## Task 8: Add error and complete states to `NodeBootstrapSheet`

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/ui/connectors/NodeBootstrapSheet.kt`

**Step 1: Add imports**

```kotlin
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
```

**Step 2: Add the error and success blocks at the end of the sheet body**

Inside the `Column` in `NodeBootstrapSheet`, **after `BootstrapLogArea(state.logLines)`**, add:

```kotlin
            // Error state
            if (state.errorMessage != null) {
                Surface(
                    color    = cs.errorContainer,
                    shape    = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Bootstrap failed",
                            style      = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color      = cs.onErrorContainer,
                        )
                        Text(state.errorMessage, style = MaterialTheme.typography.bodySmall, color = cs.onErrorContainer)
                        OutlinedButton(
                            onClick  = onDismiss,
                            modifier = Modifier.align(Alignment.End),
                        ) { Text("Retry") }
                    }
                }
            }

            // Complete state
            if (state.isComplete) {
                Row(
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF34C759))
                    Text("Done!", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
                Button(
                    onClick  = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Close") }
            }
```

**Step 3: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

**Step 4: Commit**

```
git add app/src/main/kotlin/com/vela/app/ui/connectors/NodeBootstrapSheet.kt
git commit -m "feat(bootstrap-ui): add error and complete states to NodeBootstrapSheet"
```

---

## Task 9: Add `BootstrapForm` sub-composable to `ConnectorsScreen.kt`

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/ui/connectors/ConnectorsScreen.kt`

**Step 1: Add imports**

Open `ConnectorsScreen.kt`. Add these imports near the others:

```kotlin
import androidx.compose.material3.FilterChip
import com.vela.app.ssh.BundleChoice
```

**Step 2: Add the `BootstrapForm` private composable**

At the **end of the file** (after `ConnectedNodeRow`), append:

```kotlin
// ── Bootstrap amplifierd form ─────────────────────────────────────────────────

@Composable
private fun BootstrapForm(
    publicKey: String,
    context:   Context,
    onConnect: (host: String, port: Int, username: String, bundle: BundleChoice, anthropicKey: String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme

    var host         by remember { mutableStateOf("") }
    var port         by remember { mutableStateOf("22") }
    var username     by remember { mutableStateOf("") }
    var bundle       by remember { mutableStateOf(BundleChoice.SUPERPOWERS) }
    var anthropicKey by remember { mutableStateOf("") }

    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier            = Modifier.padding(bottom = 12.dp),
    ) {
        Text("Bootstrap amplifierd", style = MaterialTheme.typography.titleSmall)

        OutlinedTextField(
            host, { host = it },
            label           = { Text("Host / IP address") },
            singleLine      = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier        = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                port, { port = it },
                label           = { Text("Port") },
                singleLine      = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier        = Modifier.width(90.dp),
            )
            OutlinedTextField(
                username, { username = it },
                label      = { Text("Username") },
                singleLine = true,
                modifier   = Modifier.weight(1f),
            )
        }

        // Read-only public-key field + copy button
        OutlinedTextField(
            value         = publicKey,
            onValueChange = {},
            readOnly      = true,
            label         = { Text("Device public key") },
            modifier      = Modifier.fillMaxWidth(),
            maxLines      = 3,
        )
        OutlinedButton(
            onClick  = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Vela SSH Key", publicKey))
                Toast.makeText(context, "Key copied", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.align(Alignment.End),
        ) {
            Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text("Copy key")
        }

        // Bundle chips
        Text("Bundle", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BundleChoice.entries.forEach { choice ->
                FilterChip(
                    selected = bundle == choice,
                    onClick  = { bundle = choice },
                    label    = { Text(choice.bundleName) },
                )
            }
        }

        OutlinedTextField(
            anthropicKey, { anthropicKey = it },
            label                = { Text("Anthropic API key") },
            singleLine           = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier             = Modifier.fillMaxWidth(),
        )

        Button(
            onClick  = {
                onConnect(
                    host,
                    port.toIntOrNull() ?: 22,
                    username,
                    bundle,
                    anthropicKey,
                )
            },
            modifier = Modifier.fillMaxWidth(),
            enabled  = host.isNotBlank() && username.isNotBlank() && anthropicKey.isNotBlank(),
        ) { Text("Connect") }
    }
}
```

**Step 3: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. (`BundleChoice` must exist with an `entries` list and a `label: String` property — added in Phase 3. If the property is named differently, update the `Text(choice.label)` line.)

**Step 4: Commit**

```
git add app/src/main/kotlin/com/vela/app/ui/connectors/ConnectorsScreen.kt
git commit -m "feat(bootstrap-ui): add BootstrapForm composable to ConnectorsScreen"
```

---

## Task 10: Wire bootstrap entry into `SshDetail` panel

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/ui/connectors/ConnectorsScreen.kt`

The bootstrap form lives **inside the SSH connector card**, below the existing "Add SSH Server" form, gated by an expandable "Bootstrap amplifierd" toggle. We wire it via a new optional callback on `SshDetail` so the screen-level composable supplies the connect handler.

**Step 1: Extend `SshDetail` signature**

Find `SshDetail` (~line 274). Add a new parameter `onBootstrap`:

```kotlin
@Composable
private fun SshDetail(
    nodes:        List<SshNode>,
    publicKey:    String,
    context:      Context,
    showForm:     Boolean,
    addError:     String?,
    onToggleForm: (Boolean) -> Unit,
    onAdd:        (label: String, host: String, port: String, user: String) -> Unit,
    onAddHost:    (nodeId: String, host: String) -> Unit,
    onRemoveHost: (nodeId: String, host: String) -> Unit,
    onDelete:     (id: String) -> Unit,
    onBootstrap:  (host: String, port: Int, username: String, bundle: BundleChoice, anthropicKey: String) -> Unit,
)
```

**Step 2: Add the expandable bootstrap section inside `SshDetail`**

Inside `SshDetail`, **after the "Add SSH Server" `OutlinedButton` block** (the `if (!showForm)` … block ending around line 320) and **before the "Device key" `Spacer(Modifier.height(8.dp))`**, insert:

```kotlin
    // ── Bootstrap amplifierd ────────────────────────────────────────────────
    var showBootstrap by remember { mutableStateOf(false) }
    Spacer(Modifier.height(8.dp))
    TextButton(
        onClick        = { showBootstrap = !showBootstrap },
        contentPadding = PaddingValues(horizontal = 0.dp),
    ) {
        Icon(
            if (showBootstrap) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            null,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            if (showBootstrap) "Hide bootstrap" else "Bootstrap amplifierd",
            style = MaterialTheme.typography.labelSmall,
        )
    }
    AnimatedVisibility(
        visible = showBootstrap,
        enter   = expandVertically(),
        exit    = shrinkVertically(),
    ) {
        BootstrapForm(
            publicKey = publicKey,
            context   = context,
            onConnect = onBootstrap,
        )
    }
```

**Step 3: Pass the callback from `ConnectorsScreen`**

Find the call site of `SshDetail` inside `ConnectorsScreen` (~line 132). It currently ends:

```kotlin
                            onDelete     = { viewModel.removeNode(it) },
                        )
```

Add the new argument **just before the closing `)`**:

```kotlin
                            onDelete     = { viewModel.removeNode(it) },
                            onBootstrap  = { host, port, user, bundle, key ->
                                // nodeId: bootstrap creates a fresh ssh node first; for now, use the node we just added
                                // by looking up its id by primaryHost. If none exists, registry will be promoted post-bootstrap.
                                val nodeId = nodes.firstOrNull { it.hosts.contains(host) }?.id
                                    ?: java.util.UUID.randomUUID().toString()
                                viewModel.bootstrapNode(nodeId, host, port, user, bundle, key)
                            },
```

(The exact `nodeId` source depends on Phase 3's contract — if `NodeBootstrapper.bootstrap()` requires an *existing* SSH node, fall back to the first SSH node's id; if it tolerates a synthesized id, the fallback above is fine. Refer to `NodeBootstrapper.kt` to confirm.)

**Step 4: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

**Step 5: Commit**

```
git add app/src/main/kotlin/com/vela/app/ui/connectors/ConnectorsScreen.kt
git commit -m "feat(bootstrap-ui): expose bootstrap form inside SshDetail panel"
```

---

## Task 11: Show `NodeBootstrapSheet` whenever `isBootstrapping || isComplete`

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/ui/connectors/ConnectorsScreen.kt`

**Step 1: Add imports**

```kotlin
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
```

**Step 2: Collect bootstrap state and host the sheet**

In `ConnectorsScreen`, near the other `collectAsState` calls (~line 89), add:

```kotlin
    val bootstrapState by viewModel.bootstrapState.collectAsState()
    val sheetState     = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope          = rememberCoroutineScope()
```

**Step 3: Render the sheet**

After the `Scaffold(...) { … }` block in `ConnectorsScreen` (i.e. **after the closing brace of `Scaffold`, before the closing brace of `ConnectorsScreen`**), add:

```kotlin
    if (bootstrapState.isBootstrapping || bootstrapState.isComplete || bootstrapState.errorMessage != null) {
        ModalBottomSheet(
            onDismissRequest = {
                scope.launch { sheetState.hide() }
                viewModel.clearBootstrapState()
            },
            sheetState       = sheetState,
        ) {
            NodeBootstrapSheet(
                state     = bootstrapState,
                onDismiss = {
                    scope.launch { sheetState.hide() }
                    viewModel.clearBootstrapState()
                },
            )
        }
    }
```

**Step 4: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

**Step 5: Commit**

```
git add app/src/main/kotlin/com/vela/app/ui/connectors/ConnectorsScreen.kt
git commit -m "feat(bootstrap-ui): host NodeBootstrapSheet from ConnectorsScreen"
```

---

## Task 12: Manual verification on device/emulator

**Step 1: Build and install**

Run: `./gradlew :app:installDebug`
Expected: BUILD SUCCESSFUL, app installed on the connected device/emulator.

**Step 2: Run the full test suite**

Run: `./gradlew :app:testDebugUnitTest && ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.vela.app.ui.nodes.NodesViewModelBootstrapTest`
Expected: All tests PASS. (Especially `NodesViewModelBootstrapTest` — 7 tests.)

**Step 3: Walk-through checklist on device**

1. Open the app, navigate to **Connectors**.
2. Tap **SSH Server** → expand.
3. Add a new SSH node pointing at a machine you control (host, port, username) — confirm it appears.
4. Below "Add SSH Server", tap **Bootstrap amplifierd** → the section expands.
5. Confirm: host/port/username fields, read-only public-key field with **Copy key** button, bundle chips (`superpowers` selected), masked Anthropic key field.
6. Fill in a valid host/username and an Anthropic key, tap **Connect**.
7. Bottom sheet appears — drag handle visible, step chips show ○ for pending steps, spinner on the running step, ✓ as steps complete; log lines stream and auto-scroll.
8. On success: green ✓ "Done!" with a **Close** button. Tapping Close dismisses the sheet.
9. On failure (try a bogus host): red error card with the error message and a **Retry** button that dismisses the sheet.

If any item misbehaves, fix and re-test before declaring this phase complete.

**Step 4: Final commit (only if any fix-ups were needed in step 3)**

```
git add -A
git commit -m "feat(bootstrap-ui): manual-verification fix-ups"
```

---

## Done Criteria

- [ ] Phases 2 + 3 prerequisites verified before starting (Task 1 prep step succeeds).
- [ ] `BootstrapUiState` data class exists in `NodesViewModel.kt`.
- [ ] `NodesViewModel` constructor takes `NodeBootstrapper`; exposes `bootstrapState: StateFlow<BootstrapUiState>`, `bootstrapNode(...)`, `clearBootstrapState()`.
- [ ] `NodesViewModelBootstrapTest` has 7 passing tests.
- [ ] `NodeBootstrapSheet.kt` renders drag handle, step indicator row, scrollable log area with auto-scroll, error card, and complete state.
- [ ] `ConnectorsScreen.kt` shows the bootstrap form inside the SSH connector card and hosts a `ModalBottomSheet` driven by `bootstrapState`.
- [ ] Manual verification on device passes the 9-point walk-through.
- [ ] All commits use the `feat(bootstrap-ui): …` convention.
