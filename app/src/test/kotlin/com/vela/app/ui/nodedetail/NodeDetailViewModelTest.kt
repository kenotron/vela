package com.vela.app.ui.nodedetail

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.vela.app.amplifierd.AmplifierdRepository
import com.vela.app.amplifierd.EndpointResolver
import com.vela.app.data.db.SshNodeDao
import com.vela.app.data.db.SshNodeEntity
import com.vela.app.ssh.NodeBootstrapper
import com.vela.app.ssh.SshKeyManager
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
import org.mockito.Mockito

@OptIn(ExperimentalCoroutinesApi::class)
class NodeDetailViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before fun setUp()    { Dispatchers.setMain(testDispatcher) }
    @After  fun tearDown() { Dispatchers.resetMain() }

    // ── Fake DAO ────────────────────────────────────────────────────────────────

    private class FakeSshNodeDao : SshNodeDao {
        val nodeFlow = MutableStateFlow<List<SshNodeEntity>>(emptyList())
        override fun getAllNodes(): Flow<List<SshNodeEntity>> = nodeFlow
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
        override suspend fun updateLastKnownReachable(id: String, reachable: Int) = Unit
    }

    private fun makeVm(
        nodeId: String,
        dao: FakeSshNodeDao = FakeSshNodeDao(),
    ): NodeDetailViewModel {
        val savedState = SavedStateHandle(mapOf("nodeId" to nodeId))
        val registry = SshNodeRegistry(dao)
        val resolver = Mockito.mock(EndpointResolver::class.java)
        val amplifierd = AmplifierdRepository(resolver)
        val bootstrapper = NodeBootstrapper(
            keyManager = SshKeyManager(android.content.ContextWrapper(null)),
            registry   = registry,
        )
        return NodeDetailViewModel(savedState, registry, amplifierd, bootstrapper)
    }

    // ── Tests ───────────────────────────────────────────────────────────────────

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

    // ── Structural: verify composables exist ─────────────────────────────────────

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
}
