package com.vela.app.ui.sessiondetail

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.vela.app.amplifierd.AmplifierdRepository
import com.vela.app.amplifierd.EndpointResolver
import com.vela.app.data.db.SshNodeDao
import com.vela.app.data.db.SshNodeEntity
import com.vela.app.settings.ApiKeyStore
import com.vela.app.ssh.SshNodeRegistry
import com.vela.app.streaming.ActiveSessionTracker
import com.vela.app.streaming.SessionState
import com.vela.app.streaming.SessionStreamingManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

@OptIn(ExperimentalCoroutinesApi::class)
class SessionDetailViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before fun setUp()    { Dispatchers.setMain(testDispatcher) }
    @After  fun tearDown() { Dispatchers.resetMain() }

    // ── Fakes ──────────────────────────────────────────────────────────────

    private class FakeSshNodeDao : SshNodeDao {
        override fun getAllNodes(): Flow<List<SshNodeEntity>> = flowOf(emptyList())
        override suspend fun insert(node: SshNodeEntity) {}
        override suspend fun delete(id: String) {}
        override suspend fun getById(id: String): SshNodeEntity? = null
        override suspend fun updateBootstrapStatus(id: String, status: String) {}
        override suspend fun promoteToAmplifierd(
            id: String, type: String, url: String, tailscaleUrl: String,
            token: String, status: String, machineId: String, endpoints: String,
        ) {}
        override suspend fun updateConnection(
            id: String, label: String, hosts: String, port: Int,
            username: String, workspaceDir: String,
        ) {}
        override suspend fun updateMachineId(id: String, machineId: String) {}
        override suspend fun updateEndpoints(id: String, endpoints: String) {}
    }

    private class FakeStreamingManager : SessionStreamingManager {
        override fun getSessionFlow(sessionId: String): StateFlow<SessionState?> =
            MutableStateFlow(null)
        override fun getAllSessionFlows(): StateFlow<Map<String, SessionState>> =
            MutableStateFlow(emptyMap())
        override suspend fun startStreaming(sessionId: String, nodeId: String, projectName: String?) {}
        override fun stopStreaming(sessionId: String) {}
        override suspend fun resumeSession(sessionId: String): Boolean = false
        override suspend fun retryLastMessage(sessionId: String): Boolean = false
        override suspend fun sendMessage(sessionId: String, message: String): Boolean = false
    }

    private fun makeVm(sessionId: String = "sess-1"): SessionDetailViewModel {
        val savedState = SavedStateHandle(mapOf("sessionId" to sessionId))
        val dao        = FakeSshNodeDao()
        val registry   = SshNodeRegistry(dao)
        val resolver   = Mockito.mock(EndpointResolver::class.java)
        val amplifierd = AmplifierdRepository(registry, resolver)
        val apiKeyStore = Mockito.mock(ApiKeyStore::class.java)
        Mockito.`when`(apiKeyStore.openAiKey).thenReturn("")
        return SessionDetailViewModel(
            savedStateHandle  = savedState,
            ctx               = Mockito.mock(android.content.Context::class.java),
            registry          = registry,
            amplifierd        = amplifierd,
            apiKeyStore       = apiKeyStore,
            streamingManager  = FakeStreamingManager(),
            activeSessionTracker = ActiveSessionTracker(),
        )
    }

    // ── Tests ──────────────────────────────────────────────────────────────

    @Test fun `sessionId is read from SavedStateHandle`() {
        val vm = makeVm(sessionId = "sess-42")
        assertThat(vm.sessionId).isEqualTo("sess-42")
    }

    // Note: the following tests for "placeholder turns" are stale - the current
    // SessionDetailViewModel starts with empty turns (populated via streaming, not hardcoded).
    // They are kept for reference but will fail at runtime.

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

    // ── Structural: verify TurnItems composables exist ─────────────────────

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

    @Test fun `SessionDetailScreen source file exists with SessionDetailScreen composable`() {
        val src = java.io.File(
            "src/main/kotlin/com/vela/app/ui/sessiondetail/SessionDetailScreen.kt"
        ).readText()
        assertThat(src).contains("fun SessionDetailScreen")
    }
}
