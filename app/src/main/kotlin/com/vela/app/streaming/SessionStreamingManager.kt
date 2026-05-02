package com.vela.app.streaming

import kotlinx.coroutines.flow.StateFlow

/**
 * ViewModels must NEVER open SSE connections directly — all streaming goes through this interface.
 *
 * Owned by the application-scoped [SessionStreamingService]; ViewModels receive a reference via
 * Hilt injection and subscribe to the [StateFlow]s exposed here.  The implementation keeps
 * connections alive across ViewModel destruction so background execution is not interrupted by
 * configuration changes.
 */
interface SessionStreamingManager {

    /**
     * Returns a [StateFlow] that emits the current [SessionState] for [sessionId].
     *
     * Emits `null` until [startStreaming] has been called for [sessionId].  The flow continues
     * emitting after ViewModel destruction because the stream is owned by the service, not the
     * ViewModel.
     */
    fun getSessionFlow(sessionId: String): StateFlow<SessionState?>

    /**
     * Returns a [StateFlow] of all known session states keyed by `sessionId`.
     *
     * Consumed by `SessionListViewModel` to render the session list and by
     * `SessionStreamingService` to drive the foreground notification.
     */
    fun getAllSessionFlows(): StateFlow<Map<String, SessionState>>

    /**
     * Loads the transcript for [sessionId] and opens an SSE event subscription.
     *
     * Lifecycle:
     * - Loads the full transcript via `GET /sessions/{id}` before opening the live event stream.
     * - Opens `GET /sessions/{id}/stream` (SSE) and maps events to [SessionState] updates.
     * - Idempotent: cancels any existing stream for [sessionId] before starting a new one.
     * - The stream remains active until [stopStreaming] is called or the service is destroyed.
     *
     * @param sessionId   Amplifier session identifier.
     * @param nodeId      ID of the SSH node that owns this session.
     * @param projectName Optional project name surfaced in notifications and session cards.
     */
    suspend fun startStreaming(sessionId: String, nodeId: String, projectName: String?)

    /**
     * Cancels the SSE subscription for [sessionId] and frees associated resources.
     *
     * Lifecycle:
     * - Safe to call multiple times; no-op if [sessionId] is not currently streaming.
     * - Does not remove the last-known [SessionState] from [getAllSessionFlows]; it remains
     *   visible in the list until the next app restart or an explicit eviction.
     */
    fun stopStreaming(sessionId: String)

    /**
     * Sends `POST /sessions/{sessionId}/resume` to resume a paused or waiting session.
     *
     * Lifecycle:
     * - Idempotent: safe to call when the session is already executing.
     * - Returns `true` if the server accepted the request (2xx response), `false` otherwise.
     */
    suspend fun resumeSession(sessionId: String): Boolean

    /**
     * Re-sends the stored `lastUserMessage` for [sessionId].
     *
     * Lifecycle:
     * - Transitions the session from `ERROR` → `EXECUTING` on success.
     * - Returns `false` if no `lastUserMessage` is stored for [sessionId].
     * - Returns `true` if the server accepted the re-sent message.
     */
    suspend fun retryLastMessage(sessionId: String): Boolean

    /**
     * Sends [message] to the session via `POST /sessions/{sessionId}/execute/stream`.
     *
     * Lifecycle:
     * - Stores [message] as `lastUserMessage` before posting so retry is possible on failure.
     * - Returns `true` if the server accepted the message (2xx response), `false` otherwise.
     */
    suspend fun sendMessage(sessionId: String, message: String): Boolean
}
