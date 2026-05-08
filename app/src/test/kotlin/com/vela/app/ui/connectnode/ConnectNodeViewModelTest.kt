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
            id: String, type: String, url: String, tailscaleUrl: String, token: String, status: String, machineId: String, endpoints: String,
        ) {}
        override suspend fun updateConnection(id: String, label: String, hosts: String, port: Int, username: String, workspaceDir: String) {}
        override suspend fun updateMachineId(id: String, machineId: String) {}
        override suspend fun updateEndpoints(id: String, endpoints: String) {}
    }

    private class FakeRegistry : SshNodeRegistry(dao = FakeSshNodeDao()) {
        override suspend fun updateBootstrapStatus(nodeId: String, status: BootstrapStatus) {}
        override suspend fun promoteToAmplifierd(nodeId: String, url: String, tailscaleUrl: String, token: String, machineId: String) {}
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

    // ── default form state ───────────────────────────────────────────────────

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

    // ── source-content checks ─────────────────────────────────────────────────

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
}
