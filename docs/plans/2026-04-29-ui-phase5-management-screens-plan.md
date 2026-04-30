# UI Phase 5: Node Config Screen & Connect a Node Screen Implementation Plan

> **Execution:** Use the subagent-driven-development workflow to implement this plan.

**Goal:** Build Screen 8 (`NodeConfigScreen` — bundle/tools/limits configuration + push) and Screen 9 (`ConnectNodeScreen` — full SSH onboarding form with bootstrap sheet), then wire both into the Phase 2 NavHost replacing their placeholders.

**Architecture:** Two new packages — `com.vela.app.ui.nodeconfig` and `com.vela.app.ui.connectnode`. Each contains a `@HiltViewModel` and a `@Composable` screen. `NodeConfigViewModel` owns tool toggles, max-steps slider, and push-progress state; it reads `nodeId` from `SavedStateHandle`. `ConnectNodeViewModel` owns the SSH form (`ConnectFormState`), drives `NodeBootstrapper.bootstrap()`, and populates `BootstrapUiState`. Screen 9 reuses `NodeBootstrapSheet` (from Phase 4 bootstrap UI) unchanged. Both screens are wired into `AppNavigation.kt` by replacing the Phase 2 placeholder composables.

**Tech Stack:** Kotlin, Jetpack Compose BOM 2025.04.01, Material 3 Expressive, Hilt 2.51, `androidx.lifecycle:lifecycle-viewmodel-savedstate`, JUnit 4, Google Truth 1.4.2, `kotlinx.coroutines.test` 1.8.0

---

## Prior Phase Contracts — What Already Exists

Do **not** redefine any of these.

| Symbol | Location | Purpose |
|--------|----------|---------| 
| `VelaColors.Accent` | `com.vela.app.ui.theme.VelaColors` | `#1FE0C2` — button bg, switch track, slider active, focused border |
| `VelaColors.Abyss` | `com.vela.app.ui.theme.VelaColors` | `#0B0E1A` — button text on Accent fill |
| `VelaColors.SurfaceSub` | `com.vela.app.ui.theme.VelaColors` | `#11152A` — section card fill |
| `VelaColors.SurfaceRaised` | `com.vela.app.ui.theme.VelaColors` | `#171C36` — bootstrap sheet bg (`surfaceContainerHigh`) |
| `VelaColors.TextPrimary` | `com.vela.app.ui.theme.VelaColors` | Primary text |
| `VelaColors.TextSecondary` | `com.vela.app.ui.theme.VelaColors` | Secondary text |
| `VelaColors.TextTertiary` | `com.vela.app.ui.theme.VelaColors` | Eyebrow / label text |
| `MonoMedium` | `com.vela.app.ui.theme` (top-level val) | JetBrains Mono 13sp — tool name rows |
| `MaterialTheme.typography.displayMedium` | `com.vela.app.ui.theme.VelaTypography` | Instrument Serif 36sp — Screen 9 hero |
| `MaterialTheme.typography.labelSmall` | `com.vela.app.ui.theme.VelaTypography` | Inter 11sp/Bold/+2sp tracking — section eyebrows |
| `MaterialTheme.typography.labelLarge` | `com.vela.app.ui.theme.VelaTypography` | Inter 14sp/600 — button labels |
| `Routes.CONNECT_NODE` | `com.vela.app.ui.navigation.Routes` | Route constant — Phase 2 |
| `Routes.NODE_DETAIL` | `com.vela.app.ui.navigation.Routes` | Parent route — Phase 2 |
| `AppNavigation.kt` | `com.vela.app.ui.navigation` | NavHost composable with NavController |
| `BootstrapUiState` | `com.vela.app.ui.nodes.NodesViewModel` | `data class` for bootstrap progress state |
| `NodeBootstrapSheet` | `com.vela.app.ui.connectors.NodeBootstrapSheet` | `@Composable` progress sheet — reuse as-is |
| `BundleChoice` | `com.vela.app.ssh.NodeBootstrapper` | `enum SUPERPOWERS / LIFEOS / TOOLS_ONLY` |
| `BootstrapEvent` | `com.vela.app.ssh.BootstrapEvent` | Sealed class: `Output`, `StepStart`, `StepComplete`, `Failed`, `Complete` |
| `BootstrapStep` | `com.vela.app.ssh.BootstrapEvent` | Enum of bootstrap phases |
| `BootstrapStatus` | `com.vela.app.ssh.SshNode` | Node lifecycle enum |
| `SshNode` | `com.vela.app.ssh.SshNode` | Domain model |
| `SshNodeRegistry` | `com.vela.app.ssh.SshNodeRegistry` | `open class` — subclass for fakes |
| `NodeBootstrapper` | `com.vela.app.ssh.NodeBootstrapper` | `open class` — subclass for fakes |
| `SshKeyManager` | `com.vela.app.ssh.SshKeyManager` | `open class` — subclass for fakes |
| `SshNodeDao` | `com.vela.app.data.db.SshNodeDao` | interface — implement for DAO fakes |
| `SshNodeEntity` | `com.vela.app.data.db.SshNodeEntity` | Room entity |

---

## New Files Summary

| Action | Path |
|--------|------|
| Create | `app/src/main/kotlin/com/vela/app/ui/nodeconfig/NodeConfigViewModel.kt` |
| Create | `app/src/main/kotlin/com/vela/app/ui/nodeconfig/NodeConfigScreen.kt` |
| Create | `app/src/test/kotlin/com/vela/app/ui/nodeconfig/NodeConfigViewModelTest.kt` |
| Create | `app/src/main/kotlin/com/vela/app/ui/connectnode/ConnectNodeViewModel.kt` |
| Create | `app/src/main/kotlin/com/vela/app/ui/connectnode/ConnectNodeScreen.kt` |
| Create | `app/src/test/kotlin/com/vela/app/ui/connectnode/ConnectNodeViewModelTest.kt` |
| Modify | `app/src/main/kotlin/com/vela/app/ui/navigation/Routes.kt` |
| Modify | `app/src/main/kotlin/com/vela/app/ui/navigation/AppNavigation.kt` |

---

## Task 1: NodeConfigViewModel (TDD)

**Files:**
- Create: `app/src/main/kotlin/com/vela/app/ui/nodeconfig/NodeConfigViewModel.kt`
- Test: `app/src/test/kotlin/com/vela/app/ui/nodeconfig/NodeConfigViewModelTest.kt`

### Step 1: Write the failing test

Create `app/src/test/kotlin/com/vela/app/ui/nodeconfig/NodeConfigViewModelTest.kt`:

```kotlin
package com.vela.app.ui.nodeconfig

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.vela.app.data.db.SshNodeDao
import com.vela.app.data.db.SshNodeEntity
import com.vela.app.ssh.BootstrapEvent
import com.vela.app.ssh.BootstrapStatus
import com.vela.app.ssh.BundleChoice
import com.vela.app.ssh.NodeBootstrapper
import com.vela.app.ssh.SshKeyManager
import com.vela.app.ssh.SshNodeRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

/**
 * Unit tests for NodeConfigViewModel.
 * Hand-rolled fakes per project convention (see NodesViewModelBootstrapTest).
 * Mockito used only for the unavoidable Android Context arg in SshKeyManager constructor
 * (consistent with MiniAppRuntimeTest). No Mockito behavior is configured.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NodeConfigViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before fun setUp()    { Dispatchers.setMain(testDispatcher) }
    @After  fun tearDown() { Dispatchers.resetMain() }

    // ── Fakes ────────────────────────────────────────────────────────────────

    private class FakeSshNodeDao : SshNodeDao {
        override fun getAllNodes(): Flow<List<SshNodeEntity>> = emptyFlow()
        override suspend fun insert(node: SshNodeEntity) {}
        override suspend fun delete(id: String) {}
        override suspend fun getById(id: String): SshNodeEntity? = null
        override suspend fun updateBootstrapStatus(id: String, status: String) {}
        override suspend fun promoteToAmplifierd(
            id: String, type: String, url: String, token: String, status: String,
        ) {}
    }

    private class FakeRegistry : SshNodeRegistry(dao = FakeSshNodeDao()) {
        override suspend fun updateBootstrapStatus(nodeId: String, status: BootstrapStatus) {}
    }

    @Suppress("UNCHECKED_CAST")
    private class FakeSshKeyManager : SshKeyManager(
        context = Mockito.mock(android.content.Context::class.java),
    ) {
        override fun getPublicKey() = "ssh-ed25519 AAAA fake-key vela@android"
    }

    private class FakeBootstrapper(
        registry: SshNodeRegistry = FakeRegistry(),
    ) : NodeBootstrapper(
        keyManager = FakeSshKeyManager(),
        registry   = registry,
    ) {
        override suspend fun bootstrap(
            nodeId: String, host: String, port: Int, username: String,
            bundle: BundleChoice, anthropicKey: String,
        ): Flow<BootstrapEvent> = flow { emit(BootstrapEvent.Complete(url = "", token = "")) }
    }

    private fun newVm(nodeId: String = "test-node") = NodeConfigViewModel(
        savedStateHandle = SavedStateHandle(mapOf("nodeId" to nodeId)),
        registry         = FakeRegistry(),
        bootstrapper     = FakeBootstrapper(),
    )

    // ── nodeId ───────────────────────────────────────────────────────────────

    @Test fun `nodeId is read from SavedStateHandle`() {
        assertThat(newVm("node-42").nodeId).isEqualTo("node-42")
    }

    // ── tools ────────────────────────────────────────────────────────────────

    @Test fun `tools initialises with bash enabled`() {
        assertThat(newVm().tools.value["bash"]).isTrue()
    }

    @Test fun `tools initialises with code_runner disabled`() {
        assertThat(newVm().tools.value["code_runner"]).isFalse()
    }

    @Test fun `toggleTool disables an enabled tool`() {
        val vm = newVm()
        vm.toggleTool("bash")
        assertThat(vm.tools.value["bash"]).isFalse()
    }

    @Test fun `toggleTool re-enables a disabled tool`() {
        val vm = newVm()
        vm.toggleTool("bash")
        vm.toggleTool("bash")
        assertThat(vm.tools.value["bash"]).isTrue()
    }

    @Test fun `toggleTool does not affect other tools`() {
        val vm = newVm()
        vm.toggleTool("bash")
        assertThat(vm.tools.value["github"]).isTrue()
    }

    // ── maxSteps ─────────────────────────────────────────────────────────────

    @Test fun `maxSteps defaults to 10`() {
        assertThat(newVm().maxSteps.value).isEqualTo(10)
    }

    @Test fun `setMaxSteps updates maxSteps value`() {
        val vm = newVm()
        vm.setMaxSteps(30)
        assertThat(vm.maxSteps.value).isEqualTo(30)
    }

    // ── isPushing ─────────────────────────────────────────────────────────────

    @Test fun `isPushing starts false`() {
        assertThat(newVm().isPushing.value).isFalse()
    }

    @Test fun `isPushing is false after pushToNode completes with fake that returns immediately`() =
        runTest {
            val vm = newVm()
            vm.pushToNode()
            advanceUntilIdle()
            assertThat(vm.isPushing.value).isFalse()
        }
}
```

### Step 2: Run test to verify it fails

```
cd /Users/ken/workspace/vela
./gradlew :app:testDebugUnitTest \
  --tests "com.vela.app.ui.nodeconfig.NodeConfigViewModelTest" \
  --no-daemon 2>&1 | tail -20
```

Expected: FAIL — `error: unresolved reference: NodeConfigViewModel`

### Step 3: Implement NodeConfigViewModel

Create `app/src/main/kotlin/com/vela/app/ui/nodeconfig/NodeConfigViewModel.kt`:

```kotlin
package com.vela.app.ui.nodeconfig

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vela.app.ssh.NodeBootstrapper
import com.vela.app.ssh.SshNodeRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NodeConfigViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val registry:    SshNodeRegistry,
    private val bootstrapper: NodeBootstrapper,
) : ViewModel() {

    val nodeId: String = checkNotNull(savedStateHandle["nodeId"])

    private val _tools = MutableStateFlow(
        mapOf(
            "bash"        to true,
            "github"      to true,
            "web_search"  to true,
            "read_file"   to true,
            "code_runner" to false,
        )
    )
    val tools: StateFlow<Map<String, Boolean>> = _tools

    private val _maxSteps = MutableStateFlow(10)
    val maxSteps: StateFlow<Int> = _maxSteps

    private val _isPushing = MutableStateFlow(false)
    val isPushing: StateFlow<Boolean> = _isPushing

    fun toggleTool(name: String) {
        _tools.update { current ->
            current.toMutableMap().apply { this[name] = !(this[name] ?: true) }
        }
    }

    fun setMaxSteps(steps: Int) {
        _maxSteps.value = steps
    }

    fun pushToNode() {
        viewModelScope.launch(Dispatchers.IO) {
            _isPushing.value = true
            // TODO: serialize tools + maxSteps and write config to node via registry/bootstrapper
            _isPushing.value = false
        }
    }
}
```

### Step 4: Run test to verify it passes

```
./gradlew :app:testDebugUnitTest \
  --tests "com.vela.app.ui.nodeconfig.NodeConfigViewModelTest" \
  --no-daemon 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL` — all 10 tests pass.

### Step 5: Commit

```
git add app/src/main/kotlin/com/vela/app/ui/nodeconfig/NodeConfigViewModel.kt \
        app/src/test/kotlin/com/vela/app/ui/nodeconfig/NodeConfigViewModelTest.kt
git commit -m "feat(node-config): add NodeConfigViewModel with tool toggles, max-steps, and push state"
```

---

## Task 2: ConnectFormState + ConnectNodeViewModel (TDD)

**Files:**
- Create: `app/src/main/kotlin/com/vela/app/ui/connectnode/ConnectNodeViewModel.kt`
- Test: `app/src/test/kotlin/com/vela/app/ui/connectnode/ConnectNodeViewModelTest.kt`

### Step 1: Write the failing test

Create `app/src/test/kotlin/com/vela/app/ui/connectnode/ConnectNodeViewModelTest.kt`:

```kotlin
package com.vela.app.ui.connectnode

import com.google.common.truth.Truth.assertThat
import com.vela.app.data.db.SshNodeDao
import com.vela.app.data.db.SshNodeEntity
import com.vela.app.ssh.BootstrapEvent
import com.vela.app.ssh.BootstrapStatus
import com.vela.app.ssh.BundleChoice
import com.vela.app.ssh.NodeBootstrapper
import com.vela.app.ssh.SshKeyManager
import com.vela.app.ssh.SshNodeRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectNodeViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before fun setUp()    { Dispatchers.setMain(testDispatcher) }
    @After  fun tearDown() { Dispatchers.resetMain() }

    // ── Fakes ────────────────────────────────────────────────────────────────

    private class FakeSshNodeDao : SshNodeDao {
        override fun getAllNodes(): Flow<List<SshNodeEntity>> = emptyFlow()
        override suspend fun insert(node: SshNodeEntity) {}
        override suspend fun delete(id: String) {}
        override suspend fun getById(id: String): SshNodeEntity? = null
        override suspend fun updateBootstrapStatus(id: String, status: String) {}
        override suspend fun promoteToAmplifierd(
            id: String, type: String, url: String, token: String, status: String,
        ) {}
    }

    private class FakeRegistry : SshNodeRegistry(dao = FakeSshNodeDao()) {
        override suspend fun updateBootstrapStatus(nodeId: String, status: BootstrapStatus) {}
    }

    private class FakeSshKeyManager : SshKeyManager(
        context = Mockito.mock(android.content.Context::class.java),
    ) {
        override fun getPublicKey() = "ssh-ed25519 AAAA fake-test-pub-key vela@android"
    }

    private class FakeBootstrapper(
        keyManager: SshKeyManager,
        registry: SshNodeRegistry,
        private val events: List<BootstrapEvent> = listOf(
            BootstrapEvent.Complete(url = "http://10.0.0.1:8410", token = "tok"),
        ),
    ) : NodeBootstrapper(keyManager = keyManager, registry = registry) {
        override suspend fun bootstrap(
            nodeId: String, host: String, port: Int, username: String,
            bundle: BundleChoice, anthropicKey: String,
        ): Flow<BootstrapEvent> = flow { events.forEach { emit(it) } }
    }

    private fun newVm(): ConnectNodeViewModel {
        val registry   = FakeRegistry()
        val keyManager = FakeSshKeyManager()
        return ConnectNodeViewModel(
            registry     = registry,
            keyManager   = keyManager,
            bootstrapper = FakeBootstrapper(keyManager = keyManager, registry = registry),
        )
    }

    // ── publicKey ─────────────────────────────────────────────────────────────

    @Test fun `publicKey delegates to keyManager`() {
        assertThat(newVm().publicKey).isEqualTo("ssh-ed25519 AAAA fake-test-pub-key vela@android")
    }

    // ── default form state ────────────────────────────────────────────────────

    @Test fun `form host defaults to empty string`() {
        assertThat(newVm().form.value.host).isEmpty()
    }

    @Test fun `form port defaults to 22 string`() {
        assertThat(newVm().form.value.port).isEqualTo("22")
    }

    @Test fun `form username defaults to empty string`() {
        assertThat(newVm().form.value.username).isEmpty()
    }

    @Test fun `form bundle defaults to SUPERPOWERS`() {
        assertThat(newVm().form.value.bundle).isEqualTo(BundleChoice.SUPERPOWERS)
    }

    @Test fun `form anthropicKey defaults to empty string`() {
        assertThat(newVm().form.value.anthropicKey).isEmpty()
    }

    // ── field updates ─────────────────────────────────────────────────────────

    @Test fun `updateHost sets host in form`() {
        val vm = newVm()
        vm.updateHost("10.0.0.5")
        assertThat(vm.form.value.host).isEqualTo("10.0.0.5")
    }

    @Test fun `updatePort sets port in form`() {
        val vm = newVm()
        vm.updatePort("2222")
        assertThat(vm.form.value.port).isEqualTo("2222")
    }

    @Test fun `updateUsername sets username in form`() {
        val vm = newVm()
        vm.updateUsername("alice")
        assertThat(vm.form.value.username).isEqualTo("alice")
    }

    @Test fun `updateBundle sets bundle in form`() {
        val vm = newVm()
        vm.updateBundle(BundleChoice.LIFEOS)
        assertThat(vm.form.value.bundle).isEqualTo(BundleChoice.LIFEOS)
    }

    @Test fun `updateApiKey sets anthropicKey in form`() {
        val vm = newVm()
        vm.updateApiKey("sk-ant-abc123")
        assertThat(vm.form.value.anthropicKey).isEqualTo("sk-ant-abc123")
    }

    // ── connect / bootstrap ───────────────────────────────────────────────────

    @Test fun `bootstrapState isBootstrapping is false before connect`() {
        assertThat(newVm().bootstrapState.value.isBootstrapping).isFalse()
    }

    @Test fun `connect sets isBootstrapping true during bootstrap then completes`() = runTest {
        val vm = newVm()
        vm.updateHost("10.0.0.1")
        vm.updateUsername("ken")
        vm.connect()
        advanceUntilIdle()
        // Fake emits Complete immediately → isBootstrapping resets to false
        assertThat(vm.bootstrapState.value.isBootstrapping).isFalse()
        assertThat(vm.bootstrapState.value.isComplete).isTrue()
    }
}
```

### Step 2: Run test to verify it fails

```
./gradlew :app:testDebugUnitTest \
  --tests "com.vela.app.ui.connectnode.ConnectNodeViewModelTest" \
  --no-daemon 2>&1 | tail -20
```

Expected: FAIL — `error: unresolved reference: ConnectNodeViewModel`

### Step 3: Implement ConnectFormState + ConnectNodeViewModel

Create `app/src/main/kotlin/com/vela/app/ui/connectnode/ConnectNodeViewModel.kt`:

```kotlin
package com.vela.app.ui.connectnode

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vela.app.ssh.BootstrapEvent
import com.vela.app.ssh.BootstrapStatus
import com.vela.app.ssh.BundleChoice
import com.vela.app.ssh.NodeBootstrapper
import com.vela.app.ssh.SshKeyManager
import com.vela.app.ssh.SshNode
import com.vela.app.ssh.SshNodeRegistry
import com.vela.app.ui.nodes.BootstrapUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class ConnectFormState(
    val host:         String       = "",
    val port:         String       = "22",
    val username:     String       = "",
    val bundle:       BundleChoice = BundleChoice.SUPERPOWERS,
    val anthropicKey: String       = "",
)

@HiltViewModel
class ConnectNodeViewModel @Inject constructor(
    private val registry:     SshNodeRegistry,
    private val keyManager:   SshKeyManager,
    private val bootstrapper: NodeBootstrapper,
) : ViewModel() {

    val publicKey: String get() = keyManager.getPublicKey()

    private val _form = MutableStateFlow(ConnectFormState())
    val form: StateFlow<ConnectFormState> = _form

    private val _bootstrapState = MutableStateFlow(BootstrapUiState())
    val bootstrapState: StateFlow<BootstrapUiState> = _bootstrapState

    fun updateHost(h: String)         { _form.update { it.copy(host = h) } }
    fun updatePort(p: String)         { _form.update { it.copy(port = p) } }
    fun updateUsername(u: String)     { _form.update { it.copy(username = u) } }
    fun updateBundle(b: BundleChoice) { _form.update { it.copy(bundle = b) } }
    fun updateApiKey(k: String)       { _form.update { it.copy(anthropicKey = k) } }

    fun connect() {
        val f      = _form.value
        val nodeId = UUID.randomUUID().toString()
        viewModelScope.launch(Dispatchers.IO) {
            registry.addNode(
                SshNode(
                    id       = nodeId,
                    label    = f.host,
                    hosts    = listOf(f.host),
                    port     = f.port.toIntOrNull() ?: 22,
                    username = f.username,
                )
            )
            registry.updateBootstrapStatus(nodeId, BootstrapStatus.BOOTSTRAPPING)
            _bootstrapState.value = BootstrapUiState(isBootstrapping = true)

            bootstrapper.bootstrap(
                nodeId       = nodeId,
                host         = f.host,
                port         = f.port.toIntOrNull() ?: 22,
                username     = f.username,
                bundle       = f.bundle,
                anthropicKey = f.anthropicKey,
            ).collect { event ->
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

    fun clearBootstrapState() {
        _bootstrapState.value = BootstrapUiState()
    }
}
```

### Step 4: Run test to verify it passes

```
./gradlew :app:testDebugUnitTest \
  --tests "com.vela.app.ui.connectnode.ConnectNodeViewModelTest" \
  --no-daemon 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL` — all 14 tests pass.

### Step 5: Commit

```
git add app/src/main/kotlin/com/vela/app/ui/connectnode/ConnectNodeViewModel.kt \
        app/src/test/kotlin/com/vela/app/ui/connectnode/ConnectNodeViewModelTest.kt
git commit -m "feat(connect-node): add ConnectFormState and ConnectNodeViewModel with bootstrap flow"
```

---

## Task 3: NodeConfigScreen — App Bar, Card Skeleton, and Bundle Section

**Files:**
- Create: `app/src/main/kotlin/com/vela/app/ui/nodeconfig/NodeConfigScreen.kt`
- Test: `app/src/test/kotlin/com/vela/app/ui/nodeconfig/NodeConfigViewModelTest.kt` (add source-existence test)

### Step 1: Write the failing test

Add to the **bottom** of `NodeConfigViewModelTest.kt` (before the closing brace):

```kotlin
// ── source-content checks (verifies screen file content without Compose) ──

@Test fun `NodeConfigScreen source file exists`() {
    val file = java.io.File(
        "src/main/kotlin/com/vela/app/ui/nodeconfig/NodeConfigScreen.kt"
    )
    assertThat(file.exists()).isTrue()
}

@Test fun `NodeConfigScreen declares BUNDLE section eyebrow`() {
    val src = java.io.File(
        "src/main/kotlin/com/vela/app/ui/nodeconfig/NodeConfigScreen.kt"
    ).readText()
    assertThat(src).contains("BUNDLE")
}
```

### Step 2: Run test to verify it fails

```
./gradlew :app:testDebugUnitTest \
  --tests "com.vela.app.ui.nodeconfig.NodeConfigViewModelTest.NodeConfigScreen source file exists" \
  --no-daemon 2>&1 | tail -10
```

Expected: FAIL — file does not exist.

### Step 3: Implement the screen skeleton (app bar + card scaffold + Bundle section)

Create `app/src/main/kotlin/com/vela/app/ui/nodeconfig/NodeConfigScreen.kt`:

```kotlin
package com.vela.app.ui.nodeconfig

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vela.app.ui.theme.VelaColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeConfigScreen(
    nodeId:         String,
    onNavigateBack: () -> Unit,
    viewModel:      NodeConfigViewModel = hiltViewModel(),
) {
    val tools    by viewModel.tools.collectAsState()
    val maxSteps by viewModel.maxSteps.collectAsState()
    val isPushing by viewModel.isPushing.collectAsState()

    Scaffold(
        containerColor = VelaColors.Abyss,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text  = viewModel.nodeId,
                            style = MaterialTheme.typography.titleLarge,
                            color = VelaColors.TextPrimary,
                        )
                        Text(
                            text  = "Configuration",
                            style = MaterialTheme.typography.bodySmall,
                            color = VelaColors.TextSecondary,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(8.dp))

            // ── Bundle section ───────────────────────────────────────────────
            ConfigSectionCard {
                SectionEyebrow("BUNDLE")
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier          = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text     = "Active bundle",
                        style    = MaterialTheme.typography.bodyMedium,
                        color    = VelaColors.TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = VelaColors.SurfaceRaised,
                    ) {
                        Row(
                            modifier          = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text  = "superpowers",
                                style = MaterialTheme.typography.labelMedium,
                                color = VelaColors.TextPrimary,
                            )
                            Icon(
                                imageVector        = Icons.Default.ChevronRight,
                                contentDescription = "Change bundle",
                                tint               = VelaColors.TextSecondary,
                            )
                        }
                    }
                }
            }

            // (Tools and Limits sections added in subsequent tasks)

            Spacer(Modifier.height(80.dp)) // room for sticky button
        }
    }
}

// ── Shared card container ─────────────────────────────────────────────────────

@Composable
internal fun ConfigSectionCard(
    modifier: Modifier = Modifier,
    content:  @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(20.dp),
        color    = VelaColors.SurfaceSub,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            content()
        }
    }
}

// ── Section eyebrow label ─────────────────────────────────────────────────────

@Composable
internal fun SectionEyebrow(label: String) {
    Text(
        text      = label,
        style     = MaterialTheme.typography.labelSmall,
        color     = VelaColors.TextTertiary,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.5.sp,
    )
}
```

### Step 4: Run test to verify it passes

```
./gradlew :app:testDebugUnitTest \
  --tests "com.vela.app.ui.nodeconfig.NodeConfigViewModelTest" \
  --no-daemon 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL` — all tests pass (including new source-existence tests).

### Step 5: Commit

```
git add app/src/main/kotlin/com/vela/app/ui/nodeconfig/NodeConfigScreen.kt \
        app/src/test/kotlin/com/vela/app/ui/nodeconfig/NodeConfigViewModelTest.kt
git commit -m "feat(node-config): scaffold NodeConfigScreen with app bar and Bundle section card"
```

---

## Task 4: NodeConfigScreen — Tools and Limits Sections + Push Button

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/ui/nodeconfig/NodeConfigScreen.kt`
- Test: `app/src/test/kotlin/com/vela/app/ui/nodeconfig/NodeConfigViewModelTest.kt` (add source-content tests)

### Step 1: Write the failing test

Add to the source-content section of `NodeConfigViewModelTest.kt`:

```kotlin
@Test fun `NodeConfigScreen declares TOOLS section eyebrow`() {
    val src = java.io.File(
        "src/main/kotlin/com/vela/app/ui/nodeconfig/NodeConfigScreen.kt"
    ).readText()
    assertThat(src).contains("TOOLS")
}

@Test fun `NodeConfigScreen declares LIMITS section eyebrow`() {
    val src = java.io.File(
        "src/main/kotlin/com/vela/app/ui/nodeconfig/NodeConfigScreen.kt"
    ).readText()
    assertThat(src).contains("LIMITS")
}

@Test fun `NodeConfigScreen declares PUSH TO NODE button label`() {
    val src = java.io.File(
        "src/main/kotlin/com/vela/app/ui/nodeconfig/NodeConfigScreen.kt"
    ).readText()
    assertThat(src).contains("PUSH TO NODE")
}

@Test fun `NodeConfigScreen declares LinearProgressIndicator for push state`() {
    val src = java.io.File(
        "src/main/kotlin/com/vela/app/ui/nodeconfig/NodeConfigScreen.kt"
    ).readText()
    assertThat(src).contains("LinearProgressIndicator")
}
```

### Step 2: Run test to verify it fails

```
./gradlew :app:testDebugUnitTest \
  --tests "com.vela.app.ui.nodeconfig.NodeConfigViewModelTest.NodeConfigScreen declares TOOLS section eyebrow" \
  --no-daemon 2>&1 | tail -10
```

Expected: FAIL — "TOOLS" not found in source.

### Step 3: Add Tools, Limits, and Push button to NodeConfigScreen

In `NodeConfigScreen.kt`, replace the comment `// (Tools and Limits sections added in subsequent tasks)` with the following sections. Also add `Box` and `LinearProgressIndicator` imports. Full additions:

**Imports to add** at the top of the file (insert after existing imports):

```kotlin
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.ui.graphics.StrokeCap
import com.vela.app.ui.theme.MonoMedium
```

**Replace** the `// (Tools and Limits sections added in subsequent tasks)` comment with:

```kotlin
            // ── Tools section ────────────────────────────────────────────────
            ConfigSectionCard {
                SectionEyebrow("TOOLS")
                Spacer(Modifier.height(8.dp))
                tools.entries.toList().forEach { (toolName, enabled) ->
                    ToolToggleRow(
                        name    = toolName,
                        enabled = enabled,
                        onToggle = { viewModel.toggleTool(toolName) },
                    )
                }
            }

            // ── Limits section ────────────────────────────────────────────────
            ConfigSectionCard {
                SectionEyebrow("LIMITS")
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier          = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text     = "Max steps / session",
                        style    = MaterialTheme.typography.bodyMedium,
                        color    = VelaColors.TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text      = maxSteps.toString(),
                        style     = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color     = VelaColors.TextPrimary,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Slider(
                    value         = maxSteps.toFloat(),
                    onValueChange = { viewModel.setMaxSteps(it.toInt()) },
                    valueRange    = 1f..50f,
                    steps         = 48, // 50-1-1 = 48 intermediate steps
                    modifier      = Modifier.fillMaxWidth(),
                    colors        = SliderDefaults.colors(
                        thumbColor              = VelaColors.Accent,
                        activeTrackColor        = VelaColors.Accent,
                        inactiveTrackColor      = VelaColors.SurfaceRaised,
                    ),
                )
            }
```

**Replace** `Spacer(Modifier.height(80.dp))` with the sticky push button:

```kotlin
            Spacer(Modifier.height(16.dp))

            // ── Push button / progress ────────────────────────────────────────
            if (isPushing) {
                LinearProgressIndicator(
                    modifier  = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .padding(vertical = 16.dp),
                    color     = VelaColors.Accent,
                    trackColor = VelaColors.SurfaceRaised,
                    strokeCap  = StrokeCap.Round,
                )
            } else {
                Button(
                    onClick   = { viewModel.pushToNode() },
                    modifier  = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape     = RoundedCornerShape(26.dp),
                    colors    = ButtonDefaults.buttonColors(
                        containerColor = VelaColors.Accent,
                        contentColor   = VelaColors.Abyss,
                    ),
                ) {
                    Text(
                        text      = "PUSH TO NODE",
                        style     = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
```

**Add** the `ToolToggleRow` composable at the bottom of the file (before the final closing):

```kotlin
// ── Tool toggle row ───────────────────────────────────────────────────────────

@Composable
private fun ToolToggleRow(
    name:     String,
    enabled:  Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier          = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text     = name,
            style    = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoMedium.fontFamily),
            fontSize = 13.sp,
            color    = VelaColors.TextPrimary,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked         = enabled,
            onCheckedChange = { onToggle() },
            colors          = SwitchDefaults.colors(
                checkedThumbColor    = androidx.compose.ui.graphics.Color.White,
                checkedTrackColor    = VelaColors.Accent,
                uncheckedThumbColor  = androidx.compose.ui.graphics.Color.White,
                uncheckedTrackColor  = VelaColors.SurfaceRaised,
            ),
        )
    }
}
```

> **Note on MonoMedium usage:** `MonoMedium` is a `TextStyle` val in `com.vela.app.ui.theme`. To use the font family, reference `MonoMedium.fontFamily`. If the theme defines `MonoMedium` differently (e.g., as a `FontFamily`), adjust accordingly.

### Step 4: Run tests to verify they pass

```
./gradlew :app:testDebugUnitTest \
  --tests "com.vela.app.ui.nodeconfig.NodeConfigViewModelTest" \
  --no-daemon 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL` — all tests pass.

### Step 5: Commit

```
git add app/src/main/kotlin/com/vela/app/ui/nodeconfig/NodeConfigScreen.kt \
        app/src/test/kotlin/com/vela/app/ui/nodeconfig/NodeConfigViewModelTest.kt
git commit -m "feat(node-config): complete NodeConfigScreen with Tools, Limits, and Push button"
```

---

## Task 5: ConnectNodeScreen — Hero, SSH Form, Bundle Chips, and API Key

**Files:**
- Create: `app/src/main/kotlin/com/vela/app/ui/connectnode/ConnectNodeScreen.kt`
- Test: `app/src/test/kotlin/com/vela/app/ui/connectnode/ConnectNodeViewModelTest.kt` (add source-content tests)

### Step 1: Write the failing test

Add to the source-content section of `ConnectNodeViewModelTest.kt`:

```kotlin
// ── source-content checks ─────────────────────────────────────────────────────

@Test fun `ConnectNodeScreen source file exists`() {
    val file = java.io.File(
        "src/main/kotlin/com/vela/app/ui/connectnode/ConnectNodeScreen.kt"
    )
    assertThat(file.exists()).isTrue()
}

@Test fun `ConnectNodeScreen declares hero text`() {
    val src = java.io.File(
        "src/main/kotlin/com/vela/app/ui/connectnode/ConnectNodeScreen.kt"
    ).readText()
    assertThat(src).contains("Connect a node.")
}

@Test fun `ConnectNodeScreen declares CONNECT button label`() {
    val src = java.io.File(
        "src/main/kotlin/com/vela/app/ui/connectnode/ConnectNodeScreen.kt"
    ).readText()
    assertThat(src).contains("CONNECT")
}

@Test fun `ConnectNodeScreen references NodeBootstrapSheet`() {
    val src = java.io.File(
        "src/main/kotlin/com/vela/app/ui/connectnode/ConnectNodeScreen.kt"
    ).readText()
    assertThat(src).contains("NodeBootstrapSheet")
}
```

### Step 2: Run test to verify it fails

```
./gradlew :app:testDebugUnitTest \
  --tests "com.vela.app.ui.connectnode.ConnectNodeViewModelTest.ConnectNodeScreen source file exists" \
  --no-daemon 2>&1 | tail -10
```

Expected: FAIL — file does not exist.

### Step 3: Implement ConnectNodeScreen

Create `app/src/main/kotlin/com/vela/app/ui/connectnode/ConnectNodeScreen.kt`:

```kotlin
package com.vela.app.ui.connectnode

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vela.app.ssh.BundleChoice
import com.vela.app.ui.connectors.NodeBootstrapSheet
import com.vela.app.ui.theme.VelaColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectNodeScreen(
    onNavigateBack: () -> Unit,
    onConnected:    () -> Unit,
    viewModel:      ConnectNodeViewModel = hiltViewModel(),
) {
    val form           by viewModel.form.collectAsState()
    val bootstrapState by viewModel.bootstrapState.collectAsState()

    val context         = LocalContext.current
    val sheetState      = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var apiKeyVisible   by remember { mutableStateOf(false) }

    // Show bootstrap sheet whenever bootstrapping or complete
    val showSheet = bootstrapState.isBootstrapping || bootstrapState.isComplete

    Scaffold(
        containerColor = VelaColors.Abyss,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
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

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {

            // ── Hero ──────────────────────────────────────────────────────────
            Spacer(Modifier.height(8.dp))
            Text(
                text  = "Connect a node.",
                style = MaterialTheme.typography.displayMedium,
                color = VelaColors.TextPrimary,
            )
            Text(
                text  = "Enter the address of an amplifierd instance on your network.",
                style = MaterialTheme.typography.bodyMedium,
                color = VelaColors.TextSecondary,
            )

            // ── SSH credentials form ──────────────────────────────────────────
            val fieldColors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor   = VelaColors.Accent,
                unfocusedBorderColor = VelaColors.SurfaceRaised,
                focusedLabelColor    = VelaColors.Accent,
                unfocusedLabelColor  = VelaColors.TextSecondary,
                cursorColor          = VelaColors.Accent,
            )
            val fieldShape = RoundedCornerShape(16.dp)

            OutlinedTextField(
                value         = form.host,
                onValueChange = viewModel::updateHost,
                label         = { Text("Host") },
                singleLine    = true,
                modifier      = Modifier.fillMaxWidth().height(52.dp + 16.dp),
                shape         = fieldShape,
                colors        = fieldColors,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )

            OutlinedTextField(
                value         = form.port,
                onValueChange = viewModel::updatePort,
                label         = { Text("Port") },
                singleLine    = true,
                modifier      = Modifier.fillMaxWidth().height(52.dp + 16.dp),
                shape         = fieldShape,
                colors        = fieldColors,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction    = ImeAction.Next,
                ),
            )

            OutlinedTextField(
                value         = form.username,
                onValueChange = viewModel::updateUsername,
                label         = { Text("Username") },
                singleLine    = true,
                modifier      = Modifier.fillMaxWidth().height(52.dp + 16.dp),
                shape         = fieldShape,
                colors        = fieldColors,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )

            // Public key field (read-only) + copy button
            OutlinedTextField(
                value         = viewModel.publicKey,
                onValueChange = {},
                label         = { Text("Public key") },
                readOnly      = true,
                singleLine    = true,
                modifier      = Modifier.fillMaxWidth().height(52.dp + 16.dp),
                shape         = fieldShape,
                colors        = fieldColors,
                trailingIcon  = {
                    IconButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                            as ClipboardManager
                        clipboard.setPrimaryClip(
                            ClipData.newPlainText("Vela public key", viewModel.publicKey)
                        )
                    }) {
                        Icon(
                            imageVector        = Icons.Default.ContentCopy,
                            contentDescription = "Copy public key",
                            tint               = VelaColors.TextSecondary,
                        )
                    }
                },
            )

            Text(
                text  = "Paste this into ~/.ssh/authorized_keys on the node, then tap Continue.",
                style = MaterialTheme.typography.bodySmall,
                color = VelaColors.TextSecondary,
            )

            // ── Bundle selection chips ────────────────────────────────────────
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BundleChoice.entries.forEach { choice ->
                    val isSelected = form.bundle == choice
                    FilterChip(
                        selected = isSelected,
                        onClick  = { viewModel.updateBundle(choice) },
                        label    = {
                            Text(
                                text      = choice.name.replace("_", " "),
                                style     = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        },
                        shape  = RoundedCornerShape(8.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor     = VelaColors.Accent,
                            selectedLabelColor         = VelaColors.Abyss,
                            containerColor             = VelaColors.SurfaceRaised,
                            labelColor                 = VelaColors.TextSecondary,
                        ),
                    )
                }
            }

            // ── API key field ─────────────────────────────────────────────────
            OutlinedTextField(
                value         = form.anthropicKey,
                onValueChange = viewModel::updateApiKey,
                label         = { Text("ANTHROPIC_API_KEY") },
                singleLine    = true,
                modifier      = Modifier.fillMaxWidth().height(52.dp + 16.dp),
                shape         = fieldShape,
                colors        = fieldColors,
                visualTransformation = if (apiKeyVisible) VisualTransformation.None
                                       else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                        Icon(
                            imageVector        = if (apiKeyVisible) Icons.Default.VisibilityOff
                                                 else Icons.Default.Visibility,
                            contentDescription = if (apiKeyVisible) "Hide key" else "Show key",
                            tint               = VelaColors.TextSecondary,
                        )
                    }
                },
            )

            // ── Connect button ────────────────────────────────────────────────
            Button(
                onClick   = { viewModel.connect() },
                modifier  = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape     = RoundedCornerShape(26.dp),
                colors    = ButtonDefaults.buttonColors(
                    containerColor = VelaColors.Accent,
                    contentColor   = VelaColors.Abyss,
                ),
            ) {
                Text(
                    text       = "CONNECT",
                    style      = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(Modifier.height(16.dp))
        }

        // ── Bootstrap progress sheet ──────────────────────────────────────────
        if (showSheet) {
            ModalBottomSheet(
                onDismissRequest = {
                    viewModel.clearBootstrapState()
                    if (bootstrapState.isComplete) onConnected()
                },
                sheetState        = sheetState,
                containerColor    = VelaColors.SurfaceRaised,
                dragHandle        = null,
            ) {
                NodeBootstrapSheet(
                    state     = bootstrapState,
                    onDismiss = {
                        viewModel.clearBootstrapState()
                        if (bootstrapState.isComplete) onConnected()
                    },
                )
            }
        }
    }
}
```

### Step 4: Run tests to verify they pass

```
./gradlew :app:testDebugUnitTest \
  --tests "com.vela.app.ui.connectnode.ConnectNodeViewModelTest" \
  --no-daemon 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL` — all tests pass.

### Step 5: Commit

```
git add app/src/main/kotlin/com/vela/app/ui/connectnode/ConnectNodeScreen.kt \
        app/src/test/kotlin/com/vela/app/ui/connectnode/ConnectNodeViewModelTest.kt
git commit -m "feat(connect-node): implement ConnectNodeScreen with hero, form, bundle chips, and bootstrap sheet"
```

---

## Task 6: Routes Update + Navigation Wiring

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/ui/navigation/Routes.kt`
- Modify: `app/src/main/kotlin/com/vela/app/ui/navigation/AppNavigation.kt`

**Prerequisite:** Read both files in full before editing. The paths above are definitive; if the files live at slightly different paths (the UI rewrite phases may have moved things), locate them with `find app/src/main/kotlin -name "Routes.kt" -o -name "AppNavigation.kt"`.

### Step 1: Write the failing test

Create `app/src/test/kotlin/com/vela/app/ui/NavigationWiringTest.kt`:

```kotlin
package com.vela.app.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Source-level checks that Phase 5 screens are wired into navigation.
 * Mirrors the approach used in NavigationScaffoldInsetsTest.
 */
class NavigationWiringTest {

    @Test fun `Routes declares NODE_CONFIG route`() {
        val src = findRoutes().readText()
        assertThat(src).contains("NODE_CONFIG")
    }

    @Test fun `AppNavigation references NodeConfigScreen`() {
        val src = findAppNavigation().readText()
        assertThat(src).contains("NodeConfigScreen")
    }

    @Test fun `AppNavigation references ConnectNodeScreen`() {
        val src = findAppNavigation().readText()
        assertThat(src).contains("ConnectNodeScreen")
    }

    private fun findRoutes() =
        File("src/main/kotlin").walk()
            .first { it.name == "Routes.kt" }

    private fun findAppNavigation() =
        File("src/main/kotlin").walk()
            .first { it.name == "AppNavigation.kt" }
}
```

### Step 2: Run test to verify it fails

```
./gradlew :app:testDebugUnitTest \
  --tests "com.vela.app.ui.NavigationWiringTest" \
  --no-daemon 2>&1 | tail -15
```

Expected: FAIL — `NODE_CONFIG` not found in Routes, `NodeConfigScreen` not referenced in AppNavigation.

### Step 3: Add NODE_CONFIG route to Routes.kt

Read `Routes.kt` first. Then add the following constants and builder. Locate the block that defines other route constants (e.g., `CONNECT_NODE`, `NODE_DETAIL`) and add alongside them:

```kotlin
// In Routes object:
const val NODE_CONFIG = "node_config/{nodeId}"

fun nodeConfig(nodeId: String) = "node_config/$nodeId"
```

### Step 4: Wire NodeConfigScreen and ConnectNodeScreen into AppNavigation.kt

Read `AppNavigation.kt` in full. Locate the two placeholder composables — one for `CONNECT_NODE` and one for `NODE_CONFIG` (they may be named `ConnectNodePlaceholder` / `NodeConfigPlaceholder`). Replace each:

**Replace the CONNECT_NODE placeholder composable block** with:

```kotlin
composable(Routes.CONNECT_NODE) {
    com.vela.app.ui.connectnode.ConnectNodeScreen(
        onNavigateBack = { navController.popBackStack() },
        onConnected    = { navController.popBackStack() },
    )
}
```

**Replace the NODE_CONFIG placeholder composable block** with:

```kotlin
composable(
    route     = Routes.NODE_CONFIG,
    arguments = listOf(
        androidx.navigation.navArgument("nodeId") {
            type = androidx.navigation.NavType.StringType
        }
    ),
) { backStackEntry ->
    val nodeId = backStackEntry.arguments?.getString("nodeId") ?: return@composable
    com.vela.app.ui.nodeconfig.NodeConfigScreen(
        nodeId         = nodeId,
        onNavigateBack = { navController.popBackStack() },
    )
}
```

If there is no existing `NODE_CONFIG` composable destination in AppNavigation, add both blocks to the NavHost alongside the other route definitions.

### Step 5: Remove stale ConnectorsScreen wiring

Search for any remaining references to `ConnectorsScreen` being wired as a top-level navigation destination (distinct from any internal usage). If found in `AppNavigation.kt` (e.g., `composable(Routes.CONNECTORS) { ConnectorsScreen(...) }`), remove that composable block. Do **not** delete the `ConnectorsScreen.kt` source file itself — it may still be needed until a full connectors migration is confirmed.

### Step 6: Verify the project compiles

```
./gradlew :app:assembleDebug --no-daemon 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL` with no compilation errors.

### Step 7: Run all navigation tests

```
./gradlew :app:testDebugUnitTest \
  --tests "com.vela.app.ui.NavigationWiringTest" \
  --no-daemon 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL` — all 3 tests pass.

### Step 8: Run full unit test suite

```
./gradlew :app:testDebugUnitTest --no-daemon 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL` — no regressions.

### Step 9: Commit

```
git add app/src/main/kotlin/com/vela/app/ui/navigation/Routes.kt \
        app/src/main/kotlin/com/vela/app/ui/navigation/AppNavigation.kt \
        app/src/test/kotlin/com/vela/app/ui/NavigationWiringTest.kt
git commit -m "feat(node-config): wire NodeConfigScreen and ConnectNodeScreen into AppNavigation NavHost"
```

---

## Final Checklist

Before declaring Phase 5 complete, verify:

- [ ] `./gradlew :app:testDebugUnitTest --no-daemon` → BUILD SUCCESSFUL (zero failures)
- [ ] `./gradlew :app:assembleDebug --no-daemon` → BUILD SUCCESSFUL (no compile errors)
- [ ] `NodeConfigViewModelTest` — 10 tests all green
- [ ] `ConnectNodeViewModelTest` — 14 tests all green
- [ ] `NavigationWiringTest` — 3 tests all green
- [ ] `NodeConfigScreen.kt` exists with BUNDLE / TOOLS / LIMITS sections and PUSH TO NODE button
- [ ] `ConnectNodeScreen.kt` exists with "Connect a node." hero, all form fields, bundle chips, CONNECT button, and `NodeBootstrapSheet` reference
- [ ] Both screens navigable from the NavHost via `Routes.nodeConfig(nodeId)` and `Routes.CONNECT_NODE`

---

## Appendix: Design Token Reference

Quick lookup for the implementer — do not redefine, import from existing locations.

| Token | Hex / Value | Use in Phase 5 |
|-------|-------------|----------------|
| `VelaColors.Accent` | `#1FE0C2` | Button bg, switch track, slider, focused border |
| `VelaColors.Abyss` | `#0B0E1A` | Screen bg, button text on Accent |
| `VelaColors.SurfaceSub` | `#11152A` | Section card fill |
| `VelaColors.SurfaceRaised` | `#171C36` | Chip unselected, field unfocused border, sheet bg |
| `VelaColors.TextPrimary` | `#FFFFFF` / near-white | Body text, slider value |
| `VelaColors.TextSecondary` | ~70% alpha | Subtitle, field hints |
| `VelaColors.TextTertiary` | ~50% alpha | Section eyebrow labels |
| Section card radius | `20.dp` | `ConfigSectionCard` `RoundedCornerShape` |
| Button radius | `26.dp` | Push / Connect button shape |
| Button height | `52.dp` | Both action buttons |
| Field height | `52.dp + 16.dp` | All `OutlinedTextField` instances (label overhead) |
| Field radius | `16.dp` | `OutlinedTextField` shape |
| Chip radius | `8.dp` | `FilterChip` shape |
