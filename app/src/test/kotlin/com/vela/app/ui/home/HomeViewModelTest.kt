package com.vela.app.ui.home

import com.google.common.truth.Truth.assertThat
import com.vela.app.amplifierd.EndpointResolver
import com.vela.app.data.db.SshNodeDao
import com.vela.app.data.db.SshNodeEntity
import com.vela.app.ssh.ConnectivityPoller
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
class HomeViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before fun setUp()    { Dispatchers.setMain(testDispatcher) }
    @After  fun tearDown() { Dispatchers.resetMain() }

    // ── Fake DAO ──────────────────────────────────────────────────────────────────────────────

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
    }

    private fun makeVm(dao: FakeSshNodeDao = FakeSshNodeDao()): HomeViewModel {
        val registry = SshNodeRegistry(dao)
        val resolver = Mockito.mock(EndpointResolver::class.java)
        val poller = ConnectivityPoller(resolver, registry)
        return HomeViewModel(registry, poller)
    }

    // ── Tests ───────────────────────────────────────────────────────────────────────────────

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

    @Test
    fun `nodeConnectivity is sourced from ConnectivityPoller`() {
        val dao = FakeSshNodeDao()
        val registry = SshNodeRegistry(dao)
        val resolver = Mockito.mock(EndpointResolver::class.java)
        val poller = ConnectivityPoller(resolver, registry)
        val vm = HomeViewModel(registry, poller)

        // nodeConnectivity must be the exact same StateFlow instance as poller.nodeConnectivity
        assertThat(vm.nodeConnectivity).isSameInstanceAs(poller.nodeConnectivity)
    }

    // ── Structural: verify composable exists ─────────────────────────────────────────────

    @Test fun `HomeScreen source file exists with HomeScreen composable`() {
        val src = java.io.File(
            "src/main/kotlin/com/vela/app/ui/home/HomeScreen.kt"
        ).readText()
        assertThat(src).contains("fun HomeScreen")
    }
}
