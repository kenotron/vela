package com.vela.app.ui.nodes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.vela.app.data.db.SshNodeDao
import com.vela.app.data.db.SshNodeEntity
import com.vela.app.ssh.BootstrapEvent
import com.vela.app.ssh.BootstrapStep
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
import org.junit.runner.RunWith

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

    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before fun setUp()    { Dispatchers.setMain(mainDispatcher) }
    @After  fun tearDown() { Dispatchers.resetMain() }

    // ── Fakes ──────────────────────────────────────────────────────────────

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
        ): Flow<BootstrapEvent> = flow {
            events.forEach { emit(it) }
        }
    }

    private class FakeSshNodeDao : SshNodeDao {
        override fun getAllNodes(): Flow<List<SshNodeEntity>> = emptyFlow()
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

    // ── Tests ──────────────────────────────────────────────────────────────

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
