package com.vela.app.ui.sessionlist

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.vela.app.amplifierd.AmplifierdRepository
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
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionListViewModelTest {

    // ── Structural: verify models exist ────────────────────────────────────────

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

    @OptIn(ExperimentalCoroutinesApi::class)
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
        nodeId: String = "node-1",
        projectId: String = "proj-1",
        dao: FakeSshNodeDao = FakeSshNodeDao(),
    ): SessionListViewModel {
        val savedState = SavedStateHandle(mapOf("nodeId" to nodeId, "projectId" to projectId))
        val registry = SshNodeRegistry(dao)
        return SessionListViewModel(savedState, registry, AmplifierdRepository(registry))
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

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

    // ── Structural: verify screen composable exists ──────────────────────────

    @Test fun `SessionListScreen source file exists with SessionListScreen composable`() {
        val src = java.io.File(
            "src/main/kotlin/com/vela/app/ui/sessionlist/SessionListScreen.kt"
        ).readText()
        assertThat(src).contains("fun SessionListScreen")
    }
}
