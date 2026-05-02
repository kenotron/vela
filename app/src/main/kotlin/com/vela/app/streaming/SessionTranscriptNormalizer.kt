package com.vela.app.streaming

import com.vela.app.ui.sessiondetail.ContentBlock
import com.vela.app.ui.sessiondetail.TodoItem
import com.vela.app.ui.sessiondetail.TodoStatus
import com.vela.app.ui.sessiondetail.TurnContent
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Parses an amplifierd transcript JSON string into a structured list of [TurnContent] turns.
 *
 * amplifierd transcript format verified 2026-05-01:
 * - Top-level key is `messages` (JSONArray).
 * - tool_call (NOT tool_use) is the authoritative block type; tool_use is also handled for
 *   compatibility with older or alternative transcript formats.
 * - Tool results have role='tool' with string content.
 * - Tool call `input` is a JSONObject.
 * - Special: todo tool calls produce [ContentBlock.TodoProgress] instead of [ContentBlock.ToolUse].
 */
@Singleton
class SessionTranscriptNormalizer @Inject constructor() {

    /**
     * Parses [transcriptJson] and returns the full list of turns.
     * Returns [emptyList] on any exception (malformed JSON, missing keys, etc.).
     */
    fun normalize(transcriptJson: String): List<TurnContent> {
        return try {
            val root = JSONObject(transcriptJson)
            val messages = root.getJSONArray("messages")
            buildTurns(messages)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Iterates [messages] and builds [TurnContent] entries.
     *
     * - role='user' with non-blank string content → [TurnContent] with [isUser]=true.
     * - role='assistant' → delegates to [parseAssistantTurn]; consumes the returned next index
     *   so that tool-result messages that were paired inside the assistant turn are skipped.
     * - All other roles (e.g. standalone 'tool' not already consumed) → skipped.
     */
    private fun buildTurns(messages: JSONArray): List<TurnContent> {
        val turns = mutableListOf<TurnContent>()
        var i = 0
        while (i < messages.length()) {
            val msg = messages.getJSONObject(i)
            when (msg.optString("role", "")) {
                "user" -> {
                    val content = msg.opt("content")
                    if (content is String && content.isNotBlank()) {
                        turns.add(TurnContent(text = content, isUser = true))
                    }
                    i++
                }
                "assistant" -> {
                    val (turn, nextIdx) = parseAssistantTurn(messages, i)
                    if (turn != null) turns.add(turn)
                    i = nextIdx
                }
                else -> i++
            }
        }
        return turns
    }

    /**
     * Builds an assistant [TurnContent] starting at [startIdx].
     *
     * Returns a [Pair] of:
     * - The assembled [TurnContent], or `null` if there were no blocks and no plainText.
     * - The next message index to continue from (after consuming any paired tool-result messages).
     *
     * Content block rules:
     * - type='text'        → [ContentBlock.Text]; first text captured as [TurnContent.text].
     * - type='thinking'    → [ContentBlock.Thinking] from the `thinking` field.
     * - type='tool_call' / 'tool_use' + name='todo' → [ContentBlock.TodoProgress] via [parseTodoBlock].
     * - type='tool_call' / 'tool_use' otherwise      → [ContentBlock.ToolUse] (isRunning=false).
     * - String content → plainText + [ContentBlock.Text].
     *
     * After processing the assistant message itself, consecutive role='tool' messages are paired
     * with each [ContentBlock.ToolUse] in order and converted to [ContentBlock.ToolResult].
     */
    private fun parseAssistantTurn(messages: JSONArray, startIdx: Int): Pair<TurnContent?, Int> {
        val msg = messages.getJSONObject(startIdx)
        val content = msg.opt("content")
        val blocks = mutableListOf<ContentBlock>()
        var plainText = ""

        when {
            content is JSONArray -> {
                for (j in 0 until content.length()) {
                    val block = content.getJSONObject(j)
                    when (block.optString("type", "")) {
                        "text" -> {
                            val text = block.optString("text", "")
                            blocks.add(ContentBlock.Text(text))
                            if (plainText.isBlank()) plainText = text
                        }
                        "thinking" -> {
                            val thinking = block.optString("thinking", "")
                            blocks.add(ContentBlock.Thinking(thinking))
                        }
                        "tool_call", "tool_use" -> {
                            val id = block.optString("id", "")
                            val name = block.optString("name", "")
                            // input is a JSONObject in the real format; handle both forms
                            val inputJson = block.optJSONObject("input")?.toString()
                                ?: block.optString("input", "{}")
                            if (name == "todo") {
                                blocks.add(parseTodoBlock(inputJson))
                            } else {
                                blocks.add(
                                    ContentBlock.ToolUse(
                                        id = id,
                                        name = name,
                                        inputJson = inputJson,
                                        isRunning = false,
                                    )
                                )
                            }
                        }
                    }
                }
            }
            content is String && content.isNotBlank() -> {
                plainText = content
                blocks.add(ContentBlock.Text(content))
            }
        }

        // Pair each ToolUse block with the next role='tool' message in order
        val toolUseBlocks = blocks.filterIsInstance<ContentBlock.ToolUse>()
        var nextIdx = startIdx + 1
        for (toolUse in toolUseBlocks) {
            if (nextIdx >= messages.length()) break
            val nextMsg = messages.getJSONObject(nextIdx)
            if (nextMsg.optString("role", "") != "tool") break
            val toolContent = nextMsg.opt("content")
            val output = when (toolContent) {
                is String -> toolContent
                is JSONArray -> buildString {
                    for (k in 0 until toolContent.length()) {
                        val item = toolContent.getJSONObject(k)
                        if (item.optString("type", "") == "text") {
                            if (isNotEmpty()) append('\n')
                            append(item.optString("text", ""))
                        }
                    }
                }
                else -> ""
            }
            val isError = nextMsg.optBoolean("is_error", false)
            blocks.add(ContentBlock.ToolResult(toolUseId = toolUse.id, output = output, isError = isError))
            nextIdx++
        }

        val turn = if (blocks.isEmpty() && plainText.isBlank()) {
            null
        } else {
            TurnContent(text = plainText, isUser = false, contentBlocks = blocks)
        }
        return Pair(turn, nextIdx)
    }

    /**
     * Parses a todo tool call `input` JSON string into a [ContentBlock.TodoProgress].
     *
     * Expected format:
     * ```json
     * { "todos": [ { "content": "…", "status": "pending|in_progress|completed",
     *                "activeForm": "…" or "active_form": "…" } ] }
     * ```
     *
     * Returns [ContentBlock.TodoProgress] with an empty list on any parse exception.
     */
    internal fun parseTodoBlock(inputJson: String): ContentBlock.TodoProgress {
        return try {
            val input = JSONObject(inputJson)
            val todosArray = input.getJSONArray("todos")
            val todos = (0 until todosArray.length()).map { i ->
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
            ContentBlock.TodoProgress(todos)
        } catch (e: Exception) {
            ContentBlock.TodoProgress(emptyList())
        }
    }
}
