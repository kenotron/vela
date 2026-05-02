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
    /** First user message (preview of what the session is about), loaded lazily from transcript. */
    val preview: String = "",
    /** Last user message — shown when session is EXECUTING so you see what the AI is working on. */
    val lastUserMessage: String = "",
    /** Current todo activeForm for running sessions — sourced from live streaming state. */
    val activeForm: String = "",
)

enum class SessionStatus { EXECUTING, IDLE, RESUMING, ERROR }

// ── Session Detail models ──────────────────────────────────────────────────────

/**
 * A single turn in the session turn history.
 * [isUser] = true for user prompts, false for agent responses.
 */
data class TurnContent(
    val text: String,
    val toolCalls: List<ToolCall> = emptyList(),
    val isUser: Boolean,
    val isStreaming: Boolean = false,
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
