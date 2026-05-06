package com.vela.app.streaming

import com.vela.app.amplifierd.StreamEvent
import com.vela.app.ui.sessiondetail.ContentBlock
import com.vela.app.ui.sessiondetail.SessionStatus
import com.vela.app.ui.sessiondetail.TodoItem
import com.vela.app.ui.sessiondetail.TodoStatus
import com.vela.app.ui.sessiondetail.TurnContent
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pure function that applies a [StreamEvent] to a [SessionState], returning a new [SessionState].
 *
 * No side effects, no logging. All mutations produce new state via [copy].
 *
 * Intended to be used by [SessionStreamingManager] to fold incoming SSE events onto the
 * accumulated session state. Each call is deterministic: same inputs always produce the same output.
 */
@Singleton
class SessionSseNormalizer @Inject constructor() {

    /**
     * Applies [event] to [state] and returns the updated [SessionState].
     *
     * This is a pure function — it never mutates [state] in place and has no side effects.
     */
    fun applyEvent(state: SessionState, event: StreamEvent): SessionState {
        return when (event) {

            // ── execution:start ──────────────────────────────────────────────────
            StreamEvent.Thinking -> {
                val newTurn = TurnContent(text = "", isUser = false, isStreaming = true)
                state.copy(
                    status = SessionStatus.EXECUTING,
                    turns = state.turns + newTurn,
                    activeTurnIndex = state.turns.size,
                )
            }

            // ── content_block:end (thinking) ──────────────────────────────────
            is StreamEvent.ThinkingBlock -> {
                val idx = state.activeTurnIndex ?: return state
                val turns = state.turns.toMutableList()
                val turn = turns[idx]
                val blocks = turn.contentBlocks.toMutableList()
                blocks.add(ContentBlock.Thinking(event.text))
                turns[idx] = turn.copy(contentBlocks = blocks)
                state.copy(turns = turns)
            }

            // ── content_block:delta ──────────────────────────────────────────────
            is StreamEvent.TextDelta -> {
                val idx = state.activeTurnIndex ?: return state
                val turns = state.turns.toMutableList()
                val turn = turns[idx]
                val blocks = turn.contentBlocks.toMutableList()

                val lastTextIdx = blocks.indexOfLast { it is ContentBlock.Text }
                if (state.textBlockFired) {
                    // TextBlock (content_block:end) has already set the authoritative complete text.
                    // loop-vela emits content_block:delta events AFTER content_block:end as a
                    // streaming simulation — ignore them so the full response stays visible.
                    return state
                } else {
                    // Subsequent deltas — append
                    if (lastTextIdx >= 0) {
                        val prev = (blocks[lastTextIdx] as ContentBlock.Text).markdown
                        blocks[lastTextIdx] = ContentBlock.Text(prev + event.token)
                    } else {
                        blocks.add(ContentBlock.Text(event.token))
                    }
                    val newText = turn.text + event.token
                    turns[idx] = turn.copy(text = newText, contentBlocks = blocks)
                    state.copy(turns = turns)
                }
            }

            // ── content_block:end (text) ─────────────────────────────────────────
            is StreamEvent.TextBlock -> {
                val idx = state.activeTurnIndex ?: return state
                val turns = state.turns.toMutableList()
                val turn = turns[idx]
                val blocks = turn.contentBlocks.toMutableList()

                val lastTextIdx = blocks.indexOfLast { it is ContentBlock.Text }
                if (lastTextIdx >= 0) {
                    blocks[lastTextIdx] = ContentBlock.Text(event.text)
                } else {
                    blocks.add(ContentBlock.Text(event.text))
                }

                turns[idx] = turn.copy(text = event.text, contentBlocks = blocks)
                state.copy(turns = turns, textBlockFired = true)  // mark that full text is set
            }

            // ── content_block:end (tool_use) ─────────────────────────────────────
            is StreamEvent.ToolUse -> {
                val idx = state.activeTurnIndex ?: return state
                val turns = state.turns.toMutableList()
                val turn = turns[idx]
                val blocks = turn.contentBlocks.toMutableList()
                var todoActiveForm: String? = null

                if (event.name == "todo") {
                    val todos = parseTodoInput(event.inputJson)
                    val progress = ContentBlock.TodoProgress(todos)

                    // Only one TodoProgress per turn; last state wins — replace in place
                    val existingIdx = blocks.indexOfFirst { it is ContentBlock.TodoProgress }
                    if (existingIdx >= 0) {
                        blocks[existingIdx] = progress
                    } else {
                        blocks.add(progress)
                    }

                    todoActiveForm = todos.firstOrNull { it.status == TodoStatus.IN_PROGRESS }?.activeForm
                } else {
                    // Deduplicate by id — the same content_block:end [tool_use] can arrive
                    // twice on stream reconnect or subscribeEvents/stream overlap.
                    val alreadyExists = blocks.any { it is ContentBlock.ToolUse && it.id == event.id }
                    if (!alreadyExists) {
                        blocks.add(
                            ContentBlock.ToolUse(
                                id = event.id,
                                name = event.name,
                                inputJson = event.inputJson,
                                isRunning = true,
                            )
                        )
                    }
                }

                turns[idx] = turn.copy(contentBlocks = blocks)
                state.copy(
                    turns = turns,
                    currentTodoActiveForm = todoActiveForm ?: state.currentTodoActiveForm,
                )
            }

            // ── approval:request ─────────────────────────────────────────────────
            is StreamEvent.ApprovalRequest -> {
                // Guard: only apply when session is actively executing (prevents SSE replay
                // from re-surfacing approvals that were already resolved in a previous turn)
                if (state.status != SessionStatus.EXECUTING) return state
                state.copy(pendingApproval = ApprovalRequest(id = event.id, question = event.question))
            }

            // ── provider:retry ───────────────────────────────────────────────────
            is StreamEvent.ProviderRetry -> state   // notification handled externally

            // ── session:named ────────────────────────────────────────────────────
            is StreamEvent.Named -> state.copy(sessionName = event.name)

            // ── execution:end / orchestrator:complete ────────────────────────────
            StreamEvent.Done -> {
                val activeTurnIdx = state.activeTurnIndex
                val turns = if (activeTurnIdx != null) {
                    state.turns.toMutableList().also { list ->
                        list[activeTurnIdx] = list[activeTurnIdx].copy(isStreaming = false)
                    }
                } else {
                    state.turns
                }
                state.copy(
                    status = SessionStatus.IDLE,
                    activeTurnIndex = null,
                    turns = turns,
                    pendingApproval = null,
                    textBlockFired = false,
                )
            }

            is StreamEvent.DelegateDelta -> {
                // Route child session tokens to the active ToolUse block's streamingText
                val idx = state.activeTurnIndex ?: return state
                val turns = state.turns.toMutableList()
                val turn = turns[idx]
                val blocks = turn.contentBlocks.toMutableList()
                // Find the last running ToolUse block (most recently started delegate)
                val toolIdx = blocks.indexOfLast { it is ContentBlock.ToolUse && it.isRunning }
                if (toolIdx >= 0) {
                    val old = blocks[toolIdx] as ContentBlock.ToolUse
                    blocks[toolIdx] = old.copy(streamingText = old.streamingText + event.token)
                    turns[idx] = turn.copy(contentBlocks = blocks)
                    state.copy(turns = turns)
                } else {
                    state
                }
            }

            is StreamEvent.ToolResult -> {
                // Find the matching ToolUse block by id, mark it done, and attach result
                // immediately from the SSE payload. The transcript reload post-execution will
                // overwrite this idempotently with the canonical version.
                val idx = state.activeTurnIndex ?: return state
                val turns = state.turns.toMutableList()
                val turn = turns[idx]
                val blocks = turn.contentBlocks.toMutableList()
                val toolIdx = blocks.indexOfFirst {
                    it is ContentBlock.ToolUse && it.id == event.toolCallId
                }
                if (toolIdx >= 0) {
                    val old = blocks[toolIdx] as ContentBlock.ToolUse
                    blocks[toolIdx] = old.copy(isRunning = false)
                    // Attach result now — don't wait for transcript reload
                    val alreadyHasResult = blocks.any {
                        it is ContentBlock.ToolResult && it.toolUseId == event.toolCallId
                    }
                    if (!alreadyHasResult && event.output.isNotBlank()) {
                        blocks.add(
                            ContentBlock.ToolResult(
                                toolUseId = event.toolCallId,
                                output    = event.output,
                                isError   = false,
                            )
                        )
                    }
                    turns[idx] = turn.copy(contentBlocks = blocks)
                    state.copy(turns = turns)
                } else {
                    state
                }
            }

            // ── error ────────────────────────────────────────────────────────────
            is StreamEvent.Error -> {
                state.copy(status = SessionStatus.ERROR, activeTurnIndex = null)
            }
        }
    }

    /**
     * Parses a todo tool call `input` JSON string into a list of [TodoItem]s.
     *
     * Expected format:
     * ```json
     * { "todos": [ { "content": "…", "status": "pending|in_progress|completed",
     *                "activeForm": "…" or "active_form": "…" } ] }
     * ```
     *
     * Returns an empty list on any parse exception (mirrors [SessionTranscriptNormalizer.parseTodoBlock]).
     */
    private fun parseTodoInput(inputJson: String): List<TodoItem> {
        return try {
            val input = JSONObject(inputJson)
            val todosArray = input.getJSONArray("todos")
            (0 until todosArray.length()).map { i ->
                val item = todosArray.getJSONObject(i)
                val content = item.optString("content", "")
                val status = when (item.optString("status", "")) {
                    "in_progress" -> TodoStatus.IN_PROGRESS
                    "completed"   -> TodoStatus.COMPLETED
                    else          -> TodoStatus.PENDING
                }
                val activeForm = item.optString("activeForm", "").ifBlank {
                    item.optString("active_form", "")
                }
                TodoItem(content = content, status = status, activeForm = activeForm)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
