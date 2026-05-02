package com.vela.app.streaming

import com.vela.app.ui.sessiondetail.SessionStatus
import com.vela.app.ui.sessiondetail.TurnContent

/**
 * Unified, observable state for a single Amplifier session.
 *
 * Produced and owned by [SessionStreamingManagerImpl]; ViewModels subscribe via
 * [SessionStreamingManager.getSessionFlow]. Both transcript loads and live SSE events
 * normalize to this model so the UI always works from a single source of truth.
 *
 * @param sessionId           Amplifier session identifier.
 * @param nodeId              ID of the [SshNode] that owns this session.
 * @param status              Current lifecycle state of the session.
 * @param turns               Full turn history; same structure regardless of data source.
 * @param activeTurnIndex     Index of the currently-streaming assistant turn; null when idle.
 * @param pendingApproval     Non-null while waiting for user approval; cleared on
 *                            `orchestrator:complete` SSE event.
 * @param lastUserMessage     Most recent message sent by the user; stored to support retry.
 * @param currentTodoActiveForm Present-continuous form of the in-progress todo item;
 *                            drives the foreground notification text (e.g. "Running tests").
 * @param projectName         Project name used in notification titles and session card subtitles.
 */
data class SessionState(
    val sessionId: String,
    val nodeId: String,
    val status: SessionStatus,
    val turns: List<TurnContent>,
    val activeTurnIndex: Int?,
    val pendingApproval: ApprovalRequest?,
    val lastUserMessage: String?,
    val currentTodoActiveForm: String?,
    val projectName: String?,
)

/**
 * A pending approval request surfaced by an `amplifierd approval:request` SSE event.
 *
 * @param id       Unique identifier for this approval (used to correlate the response).
 * @param question Human-readable question to present to the user.
 */
data class ApprovalRequest(
    val id: String,
    val question: String,
)
