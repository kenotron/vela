package com.vela.app.streaming

import com.google.common.truth.Truth.assertThat
import com.vela.app.ui.sessiondetail.SessionStatus
import com.vela.app.ui.sessiondetail.TurnContent
import org.junit.Test

/**
 * Tests for [SessionState] and [ApprovalRequest] data classes.
 * Written BEFORE implementation (TDD RED phase).
 */
class SessionStateTest {

    // ── ApprovalRequest ───────────────────────────────────────────────────────

    @Test fun `ApprovalRequest stores id`() {
        val req = ApprovalRequest(id = "req-1", question = "Continue?")
        assertThat(req.id).isEqualTo("req-1")
    }

    @Test fun `ApprovalRequest stores question`() {
        val req = ApprovalRequest(id = "req-2", question = "Deploy now?")
        assertThat(req.question).isEqualTo("Deploy now?")
    }

    @Test fun `ApprovalRequest equality is structural`() {
        val a = ApprovalRequest("id", "question?")
        val b = ApprovalRequest("id", "question?")
        assertThat(a).isEqualTo(b)
    }

    // ── SessionState — required fields ────────────────────────────────────────

    @Test fun `SessionState stores sessionId`() {
        val state = minimalState()
        assertThat(state.sessionId).isEqualTo("session-123")
    }

    @Test fun `SessionState stores nodeId`() {
        val state = minimalState()
        assertThat(state.nodeId).isEqualTo("node-abc")
    }

    @Test fun `SessionState stores status`() {
        val state = minimalState()
        assertThat(state.status).isEqualTo(SessionStatus.IDLE)
    }

    @Test fun `SessionState stores turns`() {
        val turn = TurnContent(text = "Hello", isUser = true)
        val state = minimalState().copy(turns = listOf(turn))
        assertThat(state.turns).hasSize(1)
        assertThat(state.turns[0].text).isEqualTo("Hello")
    }

    @Test fun `SessionState activeTurnIndex defaults to null`() {
        val state = minimalState()
        assertThat(state.activeTurnIndex).isNull()
    }

    @Test fun `SessionState activeTurnIndex can be set`() {
        val state = minimalState().copy(activeTurnIndex = 2)
        assertThat(state.activeTurnIndex).isEqualTo(2)
    }

    @Test fun `SessionState pendingApproval defaults to null`() {
        val state = minimalState()
        assertThat(state.pendingApproval).isNull()
    }

    @Test fun `SessionState pendingApproval can be set`() {
        val req = ApprovalRequest("id-1", "Proceed?")
        val state = minimalState().copy(pendingApproval = req)
        assertThat(state.pendingApproval).isEqualTo(req)
    }

    @Test fun `SessionState lastUserMessage defaults to null`() {
        val state = minimalState()
        assertThat(state.lastUserMessage).isNull()
    }

    @Test fun `SessionState lastUserMessage can be set`() {
        val state = minimalState().copy(lastUserMessage = "Fix the bug")
        assertThat(state.lastUserMessage).isEqualTo("Fix the bug")
    }

    @Test fun `SessionState currentTodoActiveForm defaults to null`() {
        val state = minimalState()
        assertThat(state.currentTodoActiveForm).isNull()
    }

    @Test fun `SessionState currentTodoActiveForm can be set`() {
        val state = minimalState().copy(currentTodoActiveForm = "Running tests")
        assertThat(state.currentTodoActiveForm).isEqualTo("Running tests")
    }

    @Test fun `SessionState projectName defaults to null`() {
        val state = minimalState()
        assertThat(state.projectName).isNull()
    }

    @Test fun `SessionState projectName can be set`() {
        val state = minimalState().copy(projectName = "my-project")
        assertThat(state.projectName).isEqualTo("my-project")
    }

    @Test fun `SessionState equality is structural`() {
        val a = minimalState()
        val b = minimalState()
        assertThat(a).isEqualTo(b)
    }

    // ── helper ────────────────────────────────────────────────────────────────

    private fun minimalState() = SessionState(
        sessionId = "session-123",
        nodeId = "node-abc",
        status = SessionStatus.IDLE,
        turns = emptyList(),
        activeTurnIndex = null,
        pendingApproval = null,
        lastUserMessage = null,
        currentTodoActiveForm = null,
        projectName = null,
    )
}
