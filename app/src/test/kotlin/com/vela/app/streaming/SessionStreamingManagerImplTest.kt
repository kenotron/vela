package com.vela.app.streaming

import com.google.common.truth.Truth.assertThat
import com.vela.app.amplifierd.AmplifierdRepository
import com.vela.app.amplifierd.EndpointResolver
import com.vela.app.data.db.SshNodeDao
import com.vela.app.data.db.SshNodeEntity
import com.vela.app.ssh.SshNodeRegistry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.Mockito

/**
 * Tests for [SessionStreamingManagerImpl].
 * Written BEFORE implementation (TDD RED phase).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionStreamingManagerImplTest {

    // ── Fake DAO ──────────────────────────────────────────────────────────────

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
        override suspend fun updateConnection(
            id: String, label: String, hosts: String, port: Int, username: String, workspaceDir: String,
        ) {}
        override suspend fun updateMachineId(id: String, machineId: String) {}
        override suspend fun updateEndpoints(id: String, endpoints: String) {}
    }

    private fun makeManager(): SessionStreamingManagerImpl {
        val dao = FakeSshNodeDao()
        val registry = SshNodeRegistry(dao)
        val resolver = Mockito.mock(EndpointResolver::class.java)
        val amplifierd = AmplifierdRepository(registry, resolver)
        return SessionStreamingManagerImpl(
            amplifierd = amplifierd,
            nodeRegistry = registry,
            transcriptNormalizer = SessionTranscriptNormalizer(),
            sseNormalizer = SessionSseNormalizer(),
        )
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test fun `implements SessionStreamingManager interface`() {
        val manager = makeManager()
        val iface: SessionStreamingManager = manager
        assertThat(iface).isNotNull()
    }

    @Test fun `getSessionFlow returns StateFlow that initially emits null`() {
        val manager = makeManager()
        val flow = manager.getSessionFlow("session-1")
        assertThat(flow.value).isNull()
    }

    @Test fun `getSessionFlow returns distinct flow per sessionId`() {
        val manager = makeManager()
        val flow1 = manager.getSessionFlow("session-1")
        val flow2 = manager.getSessionFlow("session-2")
        // Both start null but are independent flows
        assertThat(flow1.value).isNull()
        assertThat(flow2.value).isNull()
    }

    @Test fun `getAllSessionFlows returns StateFlow of empty Map initially`() {
        val manager = makeManager()
        val flow = manager.getAllSessionFlows()
        assertThat(flow.value).isEmpty()
    }

    @Test fun `stopStreaming on non-streaming session is a no-op`() {
        val manager = makeManager()
        // Should not throw
        manager.stopStreaming("session-not-streaming")
    }

    @Test fun `stopStreaming is idempotent`() {
        val manager = makeManager()
        // Multiple calls should not throw
        manager.stopStreaming("session-1")
        manager.stopStreaming("session-1")
    }

    @Test fun `retryLastMessage returns false when no message stored`() = runTest {
        val manager = makeManager()
        val result = manager.retryLastMessage("session-1")
        assertThat(result).isFalse()
    }

    @Test fun `resumeSession returns false when session not known`() = runTest {
        val manager = makeManager()
        val result = manager.resumeSession("session-unknown")
        assertThat(result).isFalse()
    }

    @Test fun `sendMessage returns false when session state not available`() = runTest {
        val manager = makeManager()
        val result = manager.sendMessage("session-unknown", "hello")
        assertThat(result).isFalse()
    }

    // ── Source-file structural checks ─────────────────────────────────────────

    @Test fun `source file exists and contains Singleton annotation`() {
        val src = java.io.File(
            "src/main/kotlin/com/vela/app/streaming/SessionStreamingManagerImpl.kt"
        ).readText()
        assertThat(src).contains("@Singleton")
    }

    @Test fun `source file contains ConcurrentHashMap for thread safety`() {
        val src = java.io.File(
            "src/main/kotlin/com/vela/app/streaming/SessionStreamingManagerImpl.kt"
        ).readText()
        assertThat(src).contains("ConcurrentHashMap")
    }

    @Test fun `source file contains SupervisorJob for resilient scope`() {
        val src = java.io.File(
            "src/main/kotlin/com/vela/app/streaming/SessionStreamingManagerImpl.kt"
        ).readText()
        assertThat(src).contains("SupervisorJob")
    }

    @Test fun `source file contains SessionStreamingMgr TAG`() {
        val src = java.io.File(
            "src/main/kotlin/com/vela/app/streaming/SessionStreamingManagerImpl.kt"
        ).readText()
        assertThat(src).contains("SessionStreamingMgr")
    }

    @Test fun `source file declares class SessionStreamingManagerImpl`() {
        val src = java.io.File(
            "src/main/kotlin/com/vela/app/streaming/SessionStreamingManagerImpl.kt"
        ).readText()
        assertThat(src).contains("class SessionStreamingManagerImpl")
    }

    // ── task-11: single-resolve fix (active-URL propagation bug) ──────────────

    @Test fun `source file imports AmplifierdStreamClient directly`() {
        val src = java.io.File(
            "src/main/kotlin/com/vela/app/streaming/SessionStreamingManagerImpl.kt"
        ).readText()
        assertThat(src).contains("import com.vela.app.amplifierd.AmplifierdStreamClient")
    }

    @Test fun `startStreaming and sendMessage build stream client from client baseUrl`() {
        val src = java.io.File(
            "src/main/kotlin/com/vela/app/streaming/SessionStreamingManagerImpl.kt"
        ).readText()
        // Both startStreaming and sendMessage should construct AmplifierdStreamClient directly
        // using client.baseUrl — not via streamClientForNode.
        assertThat(src).contains("AmplifierdStreamClient(client.baseUrl, node.token)")
    }

    @Test fun `startStreaming log message reflects single-resolve semantics`() {
        val src = java.io.File(
            "src/main/kotlin/com/vela/app/streaming/SessionStreamingManagerImpl.kt"
        ).readText()
        // After the fix the null-client warning uses the new message from the spec.
        assertThat(src).contains("unreachable on all endpoints")
    }

    @Test fun `source file does not call streamClientForNode in startStreaming or sendMessage`() {
        val src = java.io.File(
            "src/main/kotlin/com/vela/app/streaming/SessionStreamingManagerImpl.kt"
        ).readText()
        // streamClientForNode should no longer appear — both methods now resolve once via
        // clientForNode and construct AmplifierdStreamClient directly.
        assertThat(src).doesNotContain("streamClientForNode")
    }
}
