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
     * Maps child session ID → parent ToolUse block ID.
     *
     * Populated when a [StreamEvent.DelegateDelta] arrives carrying a childSessionId — at that
     * point we know which running ToolUse block "owns" that child. Used to route subsequent
     * child [StreamEvent.ToolUse] and [StreamEvent.ToolResult] events into the correct
     * [ContentBlock.ToolUse.childBlocks] list rather than the parent turn's top-level blocks.
     *
     * For parallel delegates, each child session claims the first *unclaimed* running ToolUse
     * block on its first delta, preventing multiple children from racing to claim the same block.
     *
     * Not part of [SessionState] because it is routing plumbing, not UI state.
     * Cleared on [StreamEvent.Done] so it is fresh for the next execution.
     */
    private val childToParentBlockId = mutableMapOf<String, String>()

    /**
     * Applies [event] to [state] and returns the updated [SessionState].
     *
     * This is a pure function — it never mutates [state] in place and has no side effects.
     */
    fun applyEvent(state: SessionState, event: StreamEvent): SessionState {
        return when (event) {

            // ── execution:start ──────────────────────────────────────────────────────────
            StreamEvent.Thinking -> {
                val newTurn = TurnContent(text = "", isUser = false, isStreaming = true)
                state.copy(
                    status = SessionStatus.EXECUTING,
                    turns = state.turns + newTurn,
                    activeTurnIndex = state.turns.size,
                )
            }

            // ── content_block:end (thinking) ──────────────────────────────────────────────
            is StreamEvent.ThinkingBlock -> {
                val idx = state.activeTurnIndex ?: return state
                val turns = state.turns.toMutableList()
                val turn = turns[idx]
                val blocks = turn.contentBlocks.toMutableList()
                blocks.add(ContentBlock.Thinking(event.text))
                turns[idx] = turn.copy(contentBlocks = blocks)
                state.copy(turns = turns)
            }

            // ── content_block:delta ────────────────────────────────────────────────────────
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

            // ── content_block:end (text) ───────────────────────────────────────────────────
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

            // ── content_block:end (tool_use) — root and child ─────────────────────────────
            is StreamEvent.ToolUse -> {
                val idx = state.activeTurnIndex ?: return state
                val turns = state.turns.toMutableList()
                val turn = turns[idx]
                val blocks = turn.contentBlocks.toMutableList()

                if (event.childSessionId != null) {
                    // Child tool call — append inline into the parent delegate block's childBlocks,
                    // preserving arrival order so it interleaves with text tokens correctly.
                    val parentId = childToParentBlockId[event.childSessionId]
                    val parentIdx = blocks.indexOfFirst {
                        it is ContentBlock.ToolUse && it.id == parentId
                    }
                    if (parentIdx >= 0) {
                        val parent = blocks[parentIdx] as ContentBlock.ToolUse
                        val alreadyExists = parent.childBlocks.any {
                            it is ContentBlock.ToolUse && it.id == event.id
                        }
                        if (!alreadyExists) {
                            val childBlock = ContentBlock.ToolUse(
                                id        = event.id,
                                name      = event.name,
                                inputJson = event.inputJson,
                                isRunning = true,
                            )
                            blocks[parentIdx] = parent.copy(
                                childBlocks = parent.childBlocks + childBlock,
                            )
                        }
                        turns[idx] = turn.copy(contentBlocks = blocks)
                        state.copy(turns = turns)
                    } else {
                        state // parent block not found — drop
                    }
                } else {
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
                                    id        = event.id,
                                    name      = event.name,
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
            }

            // ── approval:request ──────────────────────────────────────────────────────────
            is StreamEvent.ApprovalRequest -> {
                // Guard: only apply when session is actively executing (prevents SSE replay
                // from re-surfacing approvals that were already resolved in a previous turn)
                if (state.status != SessionStatus.EXECUTING) return state
                state.copy(pendingApproval = ApprovalRequest(id = event.id, question = event.question))
            }

            // ── provider:retry ────────────────────────────────────────────────────────────
            is StreamEvent.ProviderRetry -> state   // notification handled externally

            // ── session:named ─────────────────────────────────────────────────────────────
            is StreamEvent.Named -> state.copy(sessionName = event.name)

            // ── execution:end / orchestrator:complete ──────────────────────────────────────
            StreamEvent.Done -> {
                childToParentBlockId.clear()
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
                // Route child session text tokens into the parent delegate block's childBlocks
                // as an interleaved Text block — the same pattern root turns use for TextDelta.
                // Also registers the childSessionId → parent ToolUse.id mapping on first token.
                val idx = state.activeTurnIndex ?: return state
                val turns = state.turns.toMutableList()
                val turn = turns[idx]
                val blocks = turn.contentBlocks.toMutableList()

                // Resolve which ToolUse block owns this child session.
                // If already registered (subsequent tokens), look up directly by id.
                // If not yet registered (first token), claim the first running ToolUse block that
                // hasn't been claimed by any other child yet — this prevents parallel delegates
                // from racing to grab the same last-running block.
                val claimedIds = childToParentBlockId.values.toSet()
                val parentId = childToParentBlockId[event.childSessionId]
                    ?: blocks.firstOrNull {
                        it is ContentBlock.ToolUse && it.isRunning && it.id !in claimedIds
                    }?.let { (it as ContentBlock.ToolUse).id }

                val toolIdx = if (parentId != null) {
                    blocks.indexOfFirst { it is ContentBlock.ToolUse && it.id == parentId }
                } else -1

                if (toolIdx >= 0) {
                    val parent = blocks[toolIdx] as ContentBlock.ToolUse
                    // Register mapping so subsequent child tool events route to this block
                    childToParentBlockId[event.childSessionId] = parent.id
                    // Append token to a trailing Text childBlock, or create one
                    val childBlocks = parent.childBlocks.toMutableList()
                    val lastChildTextIdx = childBlocks.indexOfLast { it is ContentBlock.Text }
                    if (lastChildTextIdx >= 0) {
                        val prev = (childBlocks[lastChildTextIdx] as ContentBlock.Text).markdown
                        childBlocks[lastChildTextIdx] = ContentBlock.Text(prev + event.token)
                    } else {
                        childBlocks.add(ContentBlock.Text(event.token))
                    }
                    blocks[toolIdx] = parent.copy(childBlocks = childBlocks)
                    turns[idx] = turn.copy(contentBlocks = blocks)
                    state.copy(turns = turns)
                } else {
                    state
                }
            }

            is StreamEvent.ToolResult -> {
                val idx = state.activeTurnIndex ?: return state
                val turns = state.turns.toMutableList()
                val turn = turns[idx]
                val blocks = turn.contentBlocks.toMutableList()

                if (event.childSessionId != null) {
                    // Child tool result — mark the matching child ToolUse done and attach result
                    // inline in childBlocks right after the ToolUse (preserving order).
                    val parentId = childToParentBlockId[event.childSessionId]
                    val parentIdx = blocks.indexOfFirst {
                        it is ContentBlock.ToolUse && it.id == parentId
                    }
                    if (parentIdx >= 0) {
                        val parent = blocks[parentIdx] as ContentBlock.ToolUse
                        val childBlocks = parent.childBlocks.toMutableList()
                        // Mark the child ToolUse done
                        val childToolIdx = childBlocks.indexOfFirst {
                            it is ContentBlock.ToolUse && it.id == event.toolCallId
                        }
                        if (childToolIdx >= 0) {
                            childBlocks[childToolIdx] =
                                (childBlocks[childToolIdx] as ContentBlock.ToolUse).copy(isRunning = false)
                        }
                        // Attach result immediately after its ToolUse if not already present
                        val alreadyHasResult = childBlocks.any {
                            it is ContentBlock.ToolResult && it.toolUseId == event.toolCallId
                        }
                        if (!alreadyHasResult && event.output.isNotBlank()) {
                            val insertAt = if (childToolIdx >= 0) childToolIdx + 1 else childBlocks.size
                            childBlocks.add(
                                insertAt,
                                ContentBlock.ToolResult(
                                    toolUseId = event.toolCallId,
                                    output    = event.output,
                                    isError   = false,
                                )
                            )
                        }
                        blocks[parentIdx] = parent.copy(childBlocks = childBlocks)
                        turns[idx] = turn.copy(contentBlocks = blocks)
                        state.copy(turns = turns)
                    } else {
                        state // parent block not found — drop
                    }
                } else {
                    // Root tool result — find the matching ToolUse block by id, mark it done,
                    // and attach result immediately from the SSE payload. The transcript reload
                    // post-execution will overwrite this idempotently with the canonical version.
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
                                toolIdx + 1,
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
            }

            // ── error ─────────────────────────────────────────────────────────────────────
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
