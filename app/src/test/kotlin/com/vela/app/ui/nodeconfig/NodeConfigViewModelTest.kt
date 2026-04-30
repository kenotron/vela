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
}
