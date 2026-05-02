package com.vela.app.streaming

import com.google.common.truth.Truth.assertThat
import com.vela.app.amplifierd.StreamEvent
import com.vela.app.ui.sessiondetail.ContentBlock
import com.vela.app.ui.sessiondetail.SessionStatus
import com.vela.app.ui.sessiondetail.TodoItem
import com.vela.app.ui.sessiondetail.TodoStatus
import com.vela.app.ui.sessiondetail.TurnContent
import org.junit.Test

/**
 * Tests for [SessionSseNormalizer].
 *
 * Written BEFORE implementation (TDD RED phase).
 * Verifies StreamEvent → SessionState pure mutations including:
 * - Thinking: creates new streaming turn, sets status to EXECUTING
 * - TextDelta: appends token to last Text block or creates one
 * - TextBlock: replaces streaming text with authoritative content
 * - ToolUse: adds ToolUse block or replaces TodoProgress (in-place)
 * - ApprovalRequest: sets pendingApproval only when EXECUTING (SSE replay guard)
 * - ProviderRetry, Named: state unchanged
 * - Done: marks turn done, clears activeTurnIndex, clears pendingApproval, sets IDLE
 * - Error: sets status=ERROR, clears activeTurnIndex
 */
class SessionSseNormalizerTest {

    private val normalizer = SessionSseNormalizer()

    // ── helpers ─────────────────────────────────────────────────────────────────

    private fun minimalState() = SessionState(
        sessionId = "session-1",
        nodeId = "node-1",
        status = SessionStatus.IDLE,
        turns = emptyList(),
        activeTurnIndex = null,
        pendingApproval = null,
        lastUserMessage = null,
        currentTodoActiveForm = null,
        projectName = null,
    )

    private fun executingState(vararg turns: TurnContent, activeTurnIndex: Int = 0) =
        minimalState().copy(
            status = SessionStatus.EXECUTING,
            turns = turns.toList(),
            activeTurnIndex = activeTurnIndex,
        )

    private fun streamingTurn(
        text: String = "",
        blocks: List<ContentBlock> = emptyList(),
    ) = TurnContent(text = text, isUser = false, isStreaming = true, contentBlocks = blocks)

    // ── Thinking ─────────────────────────────────────────────────────────────────

    @Test fun `Thinking creates new streaming assistant turn and sets status to EXECUTING`() {
        val state = minimalState()
        val result = normalizer.applyEvent(state, StreamEvent.Thinking)
        assertThat(result.status).isEqualTo(SessionStatus.EXECUTING)
        assertThat(result.turns).hasSize(1)
        assertThat(result.turns[0].isUser).isFalse()
        assertThat(result.turns[0].isStreaming).isTrue()
        assertThat(result.turns[0].text).isEmpty()
    }

    @Test fun `Thinking sets activeTurnIndex to prior turns size`() {
        val state = minimalState().copy(
            turns = listOf(TurnContent(text = "hello", isUser = true)),
        )
        val result = normalizer.applyEvent(state, StreamEvent.Thinking)
        assertThat(result.activeTurnIndex).isEqualTo(1)
        assertThat(result.turns).hasSize(2)
    }

    @Test fun `Thinking on empty turns sets activeTurnIndex to 0`() {
        val result = normalizer.applyEvent(minimalState(), StreamEvent.Thinking)
        assertThat(result.activeTurnIndex).isEqualTo(0)
    }

    // ── TextDelta ────────────────────────────────────────────────────────────────

    @Test fun `TextDelta returns unchanged state when no activeTurnIndex`() {
        val state = minimalState()
        val result = normalizer.applyEvent(state, StreamEvent.TextDelta("hello"))
        assertThat(result).isEqualTo(state)
    }

    @Test fun `TextDelta appends token to existing Text block`() {
        val state = executingState(
            streamingTurn("Hello", listOf(ContentBlock.Text("Hello"))),
        )
        val result = normalizer.applyEvent(state, StreamEvent.TextDelta(" world"))
        val turn = result.turns[0]
        val textBlock = turn.contentBlocks.filterIsInstance<ContentBlock.Text>().last()
        assertThat(textBlock.markdown).isEqualTo("Hello world")
        assertThat(turn.text).isEqualTo("Hello world")
    }

    @Test fun `TextDelta adds new Text block when no Text block exists`() {
        val state = executingState(streamingTurn())
        val result = normalizer.applyEvent(state, StreamEvent.TextDelta("first"))
        val turn = result.turns[0]
        val textBlock = turn.contentBlocks.filterIsInstance<ContentBlock.Text>().last()
        assertThat(textBlock.markdown).isEqualTo("first")
        assertThat(turn.text).isEqualTo("first")
    }

    @Test fun `TextDelta appends to last Text block even when other blocks follow`() {
        val state = executingState(
            streamingTurn(
                "partial",
                listOf(
                    ContentBlock.Text("partial"),
                    ContentBlock.ToolUse("id-1", "bash", "{}", isRunning = true),
                ),
            ),
        )
        val result = normalizer.applyEvent(state, StreamEvent.TextDelta(" more"))
        val turn = result.turns[0]
        // Last Text block (the first block) should be updated
        val textBlocks = turn.contentBlocks.filterIsInstance<ContentBlock.Text>()
        assertThat(textBlocks).hasSize(1)
        assertThat(textBlocks[0].markdown).isEqualTo("partial more")
    }

    // ── TextBlock ────────────────────────────────────────────────────────────────

    @Test fun `TextBlock returns unchanged state when no activeTurnIndex`() {
        val state = minimalState()
        val result = normalizer.applyEvent(state, StreamEvent.TextBlock("text"))
        assertThat(result).isEqualTo(state)
    }

    @Test fun `TextBlock replaces last Text block with authoritative content`() {
        val state = executingState(
            streamingTurn("partial", listOf(ContentBlock.Text("partial"))),
        )
        val result = normalizer.applyEvent(state, StreamEvent.TextBlock("complete text"))
        val turn = result.turns[0]
        val textBlock = turn.contentBlocks.filterIsInstance<ContentBlock.Text>().last()
        assertThat(textBlock.markdown).isEqualTo("complete text")
        assertThat(turn.text).isEqualTo("complete text")
    }

    @Test fun `TextBlock adds new Text block when none exists`() {
        val state = executingState(streamingTurn())
        val result = normalizer.applyEvent(state, StreamEvent.TextBlock("final text"))
        val turn = result.turns[0]
        assertThat(turn.contentBlocks.filterIsInstance<ContentBlock.Text>()).hasSize(1)
        assertThat(turn.contentBlocks.filterIsInstance<ContentBlock.Text>()[0].markdown)
            .isEqualTo("final text")
    }

    // ── ToolUse (non-todo) ───────────────────────────────────────────────────────

    @Test fun `ToolUse returns unchanged state when no activeTurnIndex`() {
        val state = minimalState()
        val result = normalizer.applyEvent(state, StreamEvent.ToolUse("id-1", "bash", "{}"))
        assertThat(result).isEqualTo(state)
    }

    @Test fun `ToolUse adds ToolUse content block with isRunning=true`() {
        val state = executingState(streamingTurn())
        val result = normalizer.applyEvent(state, StreamEvent.ToolUse("id-1", "bash", "{\"cmd\":\"ls\"}"))
        val turn = result.turns[0]
        val block = turn.contentBlocks.filterIsInstance<ContentBlock.ToolUse>().single()
        assertThat(block.id).isEqualTo("id-1")
        assertThat(block.name).isEqualTo("bash")
        assertThat(block.inputJson).isEqualTo("{\"cmd\":\"ls\"}")
        assertThat(block.isRunning).isTrue()
    }

    // ── ToolUse (todo) ───────────────────────────────────────────────────────────

    @Test fun `ToolUse for todo creates TodoProgress block`() {
        val todoInput = """{"todos":[{"content":"Write code","status":"in_progress","activeForm":"Writing code"}]}"""
        val state = executingState(streamingTurn())
        val result = normalizer.applyEvent(state, StreamEvent.ToolUse("id-todo", "todo", todoInput))
        val turn = result.turns[0]
        val block = turn.contentBlocks.filterIsInstance<ContentBlock.TodoProgress>().single()
        assertThat(block.todos).hasSize(1)
        assertThat(block.todos[0].content).isEqualTo("Write code")
        assertThat(block.todos[0].status).isEqualTo(TodoStatus.IN_PROGRESS)
    }

    @Test fun `ToolUse for todo replaces existing TodoProgress (last wins)`() {
        val existingTodo = ContentBlock.TodoProgress(
            listOf(TodoItem("Old task", TodoStatus.PENDING, "Doing old")),
        )
        val state = executingState(streamingTurn(blocks = listOf(existingTodo)))
        val newInput = """{"todos":[{"content":"New task","status":"in_progress","activeForm":"Doing new"}]}"""
        val result = normalizer.applyEvent(state, StreamEvent.ToolUse("id-todo", "todo", newInput))
        val blocks = result.turns[0].contentBlocks.filterIsInstance<ContentBlock.TodoProgress>()
        assertThat(blocks).hasSize(1)
        assertThat(blocks[0].todos[0].content).isEqualTo("New task")
    }

    @Test fun `ToolUse for todo adds TodoProgress when none exists`() {
        val state = executingState(streamingTurn())
        val input = """{"todos":[{"content":"Task A","status":"pending","activeForm":"Doing A"}]}"""
        val result = normalizer.applyEvent(state, StreamEvent.ToolUse("id-todo", "todo", input))
        val blocks = result.turns[0].contentBlocks.filterIsInstance<ContentBlock.TodoProgress>()
        assertThat(blocks).hasSize(1)
    }

    @Test fun `ToolUse for todo sets currentTodoActiveForm from first IN_PROGRESS item`() {
        val todoInput = """{"todos":[
            {"content":"Task A","status":"completed","activeForm":"Doing A"},
            {"content":"Task B","status":"in_progress","activeForm":"Running B"},
            {"content":"Task C","status":"pending","activeForm":"Doing C"}
        ]}"""
        val state = executingState(streamingTurn())
        val result = normalizer.applyEvent(state, StreamEvent.ToolUse("id-todo", "todo", todoInput))
        assertThat(result.currentTodoActiveForm).isEqualTo("Running B")
    }

    @Test fun `ToolUse for todo preserves currentTodoActiveForm when no IN_PROGRESS item`() {
        val todoInput = """{"todos":[{"content":"Task A","status":"completed","activeForm":"Doing A"}]}"""
        val state = executingState(streamingTurn()).copy(currentTodoActiveForm = "Previous active")
        val result = normalizer.applyEvent(state, StreamEvent.ToolUse("id-todo", "todo", todoInput))
        assertThat(result.currentTodoActiveForm).isEqualTo("Previous active")
    }

    @Test fun `ToolUse for todo with invalid JSON creates empty TodoProgress`() {
        val state = executingState(streamingTurn())
        val result = normalizer.applyEvent(state, StreamEvent.ToolUse("id-todo", "todo", "not json"))
        val blocks = result.turns[0].contentBlocks.filterIsInstance<ContentBlock.TodoProgress>()
        assertThat(blocks).hasSize(1)
        assertThat(blocks[0].todos).isEmpty()
    }

    // ── ApprovalRequest ──────────────────────────────────────────────────────────

    @Test fun `ApprovalRequest sets pendingApproval when status is EXECUTING`() {
        val state = minimalState().copy(status = SessionStatus.EXECUTING)
        val result = normalizer.applyEvent(state, StreamEvent.ApprovalRequest("req-1", "Proceed?"))
        assertThat(result.pendingApproval).isNotNull()
        assertThat(result.pendingApproval!!.id).isEqualTo("req-1")
        assertThat(result.pendingApproval!!.question).isEqualTo("Proceed?")
    }

    @Test fun `ApprovalRequest is ignored when status is IDLE (SSE replay guard)`() {
        val state = minimalState().copy(status = SessionStatus.IDLE)
        val result = normalizer.applyEvent(state, StreamEvent.ApprovalRequest("req-1", "Proceed?"))
        assertThat(result).isEqualTo(state)
    }

    @Test fun `ApprovalRequest is ignored when status is ERROR`() {
        val state = minimalState().copy(status = SessionStatus.ERROR)
        val result = normalizer.applyEvent(state, StreamEvent.ApprovalRequest("req-1", "Proceed?"))
        assertThat(result).isEqualTo(state)
    }

    @Test fun `ApprovalRequest is ignored when status is RESUMING`() {
        val state = minimalState().copy(status = SessionStatus.RESUMING)
        val result = normalizer.applyEvent(state, StreamEvent.ApprovalRequest("req-1", "Proceed?"))
        assertThat(result).isEqualTo(state)
    }

    // ── ProviderRetry ────────────────────────────────────────────────────────────

    @Test fun `ProviderRetry returns state unchanged`() {
        val state = minimalState()
        val result = normalizer.applyEvent(state, StreamEvent.ProviderRetry(1, 3, "Network error", 2.0))
        assertThat(result).isEqualTo(state)
    }

    // ── Named ────────────────────────────────────────────────────────────────────

    @Test fun `Named returns state unchanged`() {
        val state = minimalState()
        val result = normalizer.applyEvent(state, StreamEvent.Named("My Session"))
        assertThat(result).isEqualTo(state)
    }

    // ── Done ─────────────────────────────────────────────────────────────────────

    @Test fun `Done marks active turn isStreaming=false`() {
        val state = executingState(streamingTurn("response"))
        val result = normalizer.applyEvent(state, StreamEvent.Done)
        assertThat(result.turns[0].isStreaming).isFalse()
    }

    @Test fun `Done sets status to IDLE`() {
        val state = minimalState().copy(status = SessionStatus.EXECUTING)
        val result = normalizer.applyEvent(state, StreamEvent.Done)
        assertThat(result.status).isEqualTo(SessionStatus.IDLE)
    }

    @Test fun `Done clears activeTurnIndex`() {
        val state = executingState(streamingTurn())
        val result = normalizer.applyEvent(state, StreamEvent.Done)
        assertThat(result.activeTurnIndex).isNull()
    }

    @Test fun `Done clears pendingApproval`() {
        val state = minimalState().copy(
            status = SessionStatus.EXECUTING,
            pendingApproval = ApprovalRequest("id", "question?"),
        )
        val result = normalizer.applyEvent(state, StreamEvent.Done)
        assertThat(result.pendingApproval).isNull()
    }

    @Test fun `Done works when no active turn`() {
        val state = minimalState().copy(status = SessionStatus.EXECUTING)
        val result = normalizer.applyEvent(state, StreamEvent.Done)
        assertThat(result.status).isEqualTo(SessionStatus.IDLE)
        assertThat(result.activeTurnIndex).isNull()
    }

    @Test fun `Done preserves non-active turns unchanged`() {
        val userTurn = TurnContent(text = "hello", isUser = true)
        val state = executingState(userTurn, streamingTurn("response"), activeTurnIndex = 1)
        val result = normalizer.applyEvent(state, StreamEvent.Done)
        assertThat(result.turns[0]).isEqualTo(userTurn)
        assertThat(result.turns[1].isStreaming).isFalse()
    }

    // ── Error ────────────────────────────────────────────────────────────────────

    @Test fun `Error sets status to ERROR`() {
        val state = minimalState().copy(status = SessionStatus.EXECUTING)
        val result = normalizer.applyEvent(state, StreamEvent.Error("Something went wrong"))
        assertThat(result.status).isEqualTo(SessionStatus.ERROR)
    }

    @Test fun `Error clears activeTurnIndex`() {
        val state = executingState(streamingTurn()).copy(activeTurnIndex = 0)
        val result = normalizer.applyEvent(state, StreamEvent.Error("fail"))
        assertThat(result.activeTurnIndex).isNull()
    }

    // ── Structural checks ────────────────────────────────────────────────────────

    @Test fun `source file exists at correct path`() {
        val file = java.io.File(
            "src/main/kotlin/com/vela/app/streaming/SessionSseNormalizer.kt",
        )
        assertThat(file.exists()).isTrue()
    }

    @Test fun `source file declares Singleton annotation`() {
        val src = java.io.File(
            "src/main/kotlin/com/vela/app/streaming/SessionSseNormalizer.kt",
        ).readText()
        assertThat(src).contains("@Singleton")
    }

    @Test fun `source file declares Inject constructor`() {
        val src = java.io.File(
            "src/main/kotlin/com/vela/app/streaming/SessionSseNormalizer.kt",
        ).readText()
        assertThat(src).contains("@Inject constructor")
    }

    @Test fun `source file contains applyEvent fun`() {
        val src = java.io.File(
            "src/main/kotlin/com/vela/app/streaming/SessionSseNormalizer.kt",
        ).readText()
        assertThat(src).contains("fun applyEvent(state: SessionState, event: StreamEvent): SessionState")
    }
}
