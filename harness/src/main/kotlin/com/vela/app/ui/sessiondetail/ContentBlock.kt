package com.vela.app.ui.sessiondetail

/** Typed content blocks parsed from an amplifierd assistant message. */
sealed class ContentBlock {
    /** Regular markdown text */
    data class Text(val markdown: String) : ContentBlock()
    /** Internal reasoning — shown as compact inline strip */
    data class Thinking(val text: String) : ContentBlock()
    /**
     * Tool invocation card — isRunning=true while in-flight, false after result arrives.
     *
     * For delegate tool calls, [childBlocks] holds the interleaved content of the child agent
     * (Text and ToolUse blocks in order of arrival, mirroring how root-session turns work).
     * [streamingText] is unused for delegates; tokens are folded directly into childBlocks.
     */
    data class ToolUse(
        val id: String,
        val name: String,
        val inputJson: String,
        val isRunning: Boolean = true,
        /** Interleaved child content — text and nested tool calls in arrival order. Delegate only. */
        val childBlocks: List<ContentBlock> = emptyList(),
    ) : ContentBlock()
    /** Tool result (paired with ToolUse by id) */
    data class ToolResult(val toolUseId: String, val output: String, val isError: Boolean = false) : ContentBlock()
    /**
     * Live todo widget — rendered as a TodoProgressCard, updated in-place.
     * Only one TodoProgress per turn; last state wins.
     * The activeForm of the IN_PROGRESS item is surfaced in the foreground notification.
     */
    data class TodoProgress(val todos: List<TodoItem>) : ContentBlock()
}

/** Lifecycle state of a single todo item. */
enum class TodoStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
}

/**
 * A single item in a todo list.
 *
 * @param content     Imperative description, e.g. "Run tests".
 * @param status      Current lifecycle state.
 * @param activeForm  Present-continuous form, e.g. "Running tests".
 *                    Shown in the foreground notification while the item is IN_PROGRESS.
 */
data class TodoItem(
    val content: String,
    val status: TodoStatus,
    val activeForm: String,
)
