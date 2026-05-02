package com.vela.app.streaming

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Test

/**
 * Contract tests for [SessionStreamingManager].
 *
 * The [FakeSessionStreamingManager] inner class is a compile-time contract check — if any
 * method signature changes or a method is removed, this fake will fail to compile.
 * Written BEFORE implementation (TDD RED phase).
 */
class SessionStreamingManagerTest {

    // ── Fake implementation — compile-time interface contract ─────────────────

    private class FakeSessionStreamingManager : SessionStreamingManager {
        override fun getSessionFlow(sessionId: String): StateFlow<SessionState?> =
            MutableStateFlow(null)

        override fun getAllSessionFlows(): StateFlow<Map<String, SessionState>> =
            MutableStateFlow(emptyMap())

        override suspend fun startStreaming(
            sessionId: String,
            nodeId: String,
            projectName: String?,
        ) {}

        override fun stopStreaming(sessionId: String) {}

        override suspend fun resumeSession(sessionId: String): Boolean = false

        override suspend fun retryLastMessage(sessionId: String): Boolean = false

        override suspend fun sendMessage(sessionId: String, message: String): Boolean = false
    }

    // ── Tests ────────────────────────────────────────────────────────────────

    @Test fun `SessionStreamingManager interface can be implemented`() {
        val manager: SessionStreamingManager = FakeSessionStreamingManager()
        assertThat(manager).isNotNull()
    }

    @Test fun `getSessionFlow returns StateFlow that initially emits null`() {
        val manager: SessionStreamingManager = FakeSessionStreamingManager()
        val flow = manager.getSessionFlow("session-1")
        assertThat(flow.value).isNull()
    }

    @Test fun `getAllSessionFlows returns StateFlow of Map`() {
        val manager: SessionStreamingManager = FakeSessionStreamingManager()
        val flow = manager.getAllSessionFlows()
        assertThat(flow.value).isEmpty()
    }

    @Test fun `stopStreaming is non-suspending`() {
        // Verifies that stopStreaming is a regular fun (not suspend) — callable without coroutine.
        val manager: SessionStreamingManager = FakeSessionStreamingManager()
        manager.stopStreaming("session-1")
        // No assertion needed — compile-time check is the goal.
    }

    // ── Source-file structural checks ────────────────────────────────────────

    @Test fun `source file declares interface SessionStreamingManager`() {
        val src = java.io.File(
            "src/main/kotlin/com/vela/app/streaming/SessionStreamingManager.kt"
        ).readText()
        assertThat(src).contains("interface SessionStreamingManager")
    }

    @Test fun `source file contains getSessionFlow method`() {
        val src = java.io.File(
            "src/main/kotlin/com/vela/app/streaming/SessionStreamingManager.kt"
        ).readText()
        assertThat(src).contains("fun getSessionFlow(sessionId: String): StateFlow<SessionState?>")
    }

    @Test fun `source file contains getAllSessionFlows method`() {
        val src = java.io.File(
            "src/main/kotlin/com/vela/app/streaming/SessionStreamingManager.kt"
        ).readText()
        assertThat(src).contains("fun getAllSessionFlows(): StateFlow<Map<String, SessionState>>")
    }

    @Test fun `source file contains startStreaming suspend method`() {
        val src = java.io.File(
            "src/main/kotlin/com/vela/app/streaming/SessionStreamingManager.kt"
        ).readText()
        assertThat(src).contains("suspend fun startStreaming(")
        assertThat(src).contains("sessionId: String")
        assertThat(src).contains("nodeId: String")
        assertThat(src).contains("projectName: String?")
    }

    @Test fun `source file contains stopStreaming method`() {
        val src = java.io.File(
            "src/main/kotlin/com/vela/app/streaming/SessionStreamingManager.kt"
        ).readText()
        assertThat(src).contains("fun stopStreaming(sessionId: String)")
    }

    @Test fun `source file contains resumeSession suspend method returning Boolean`() {
        val src = java.io.File(
            "src/main/kotlin/com/vela/app/streaming/SessionStreamingManager.kt"
        ).readText()
        assertThat(src).contains("suspend fun resumeSession(sessionId: String): Boolean")
    }

    @Test fun `source file contains retryLastMessage suspend method returning Boolean`() {
        val src = java.io.File(
            "src/main/kotlin/com/vela/app/streaming/SessionStreamingManager.kt"
        ).readText()
        assertThat(src).contains("suspend fun retryLastMessage(sessionId: String): Boolean")
    }

    @Test fun `source file contains sendMessage suspend method returning Boolean`() {
        val src = java.io.File(
            "src/main/kotlin/com/vela/app/streaming/SessionStreamingManager.kt"
        ).readText()
        assertThat(src).contains("suspend fun sendMessage(sessionId: String, message: String): Boolean")
    }

    @Test fun `source file contains ViewModels doc comment`() {
        val src = java.io.File(
            "src/main/kotlin/com/vela/app/streaming/SessionStreamingManager.kt"
        ).readText()
        assertThat(src).contains("ViewModels must NEVER open SSE connections directly")
    }
}
