package com.vela.app.ui.sessiondetail

// ── Session List models ────────────────────────────────────────────────────────

/**
 * Summary of a session for display in the session list.
 * Data source: amplifierd HTTP API (placeholder — emptyList() for now).
 */
data class SessionSummary(
    val id: String,
    val title: String,
    val status: SessionStatus,
    val modelName: String,
    val stepCount: Int,
    val lastActiveMs: Long,
)

enum class SessionStatus { RUNNING, WAITING, DONE, ERROR }

// ── Session Detail models ──────────────────────────────────────────────────────

/**
 * A single turn in the session turn history.
 * [isUser] = true for user prompts, false for agent responses.
 */
data class TurnContent(
    val text: String,
    val toolCalls: List<ToolCall> = emptyList(),
    val isUser: Boolean,
    val contentBlocks: List<ContentBlock> = emptyList(),
)

/**
 * A tool invocation within an agent turn.
 * Shows tool name, optional result, duration, and live/done state.
 */
data class ToolCall(
    val name: String,
    val result: String? = null,
    val isDone: Boolean,
    val isRunning: Boolean,
    val durationMs: Long? = null,
)
