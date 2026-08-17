package com.vela.events

import org.json.JSONObject

/**
 * Domain model for the C2 event stream exposed by `vela-agentd`'s
 * `GET /v1/events` SSE route (lane 3.1, already merged). See
 * `services/vela-agentd/src/vela_agentd_http/_c2_shapes.py` for the wire
 * contract this mirrors. This module consumes that contract; it never
 * changes it.
 *
 * All variants carry the base fields (`sessionId`, `turnId`, optional
 * `agentName`) plus whatever fields are specific to that event type.
 * `agentName` is present when the event originates from a delegated
 * sub-agent (parallel delegation).
 */
sealed interface C2Event {
    val sessionId: String
    val turnId: String
    val agentName: String?

    data class ToolStarted(
        override val sessionId: String,
        override val turnId: String,
        override val agentName: String?,
        val toolCallId: String,
        val name: String,
        val args: JSONObject?,
    ) : C2Event

    data class ToolCompleted(
        override val sessionId: String,
        override val turnId: String,
        override val agentName: String?,
        val toolCallId: String,
        val name: String,
        val result: JSONObject?,
        val durationMs: Long,
    ) : C2Event

    data class Progress(
        override val sessionId: String,
        override val turnId: String,
        override val agentName: String?,
        val message: String,
        val percent: Int?,
    ) : C2Event

    data class ThinkingDelta(
        override val sessionId: String,
        override val turnId: String,
        override val agentName: String?,
        val text: String,
    ) : C2Event

    data class ThinkingFinal(
        override val sessionId: String,
        override val turnId: String,
        override val agentName: String?,
        val text: String,
    ) : C2Event

    data class Usage(
        override val sessionId: String,
        override val turnId: String,
        override val agentName: String?,
        val inputTokens: Long,
        val outputTokens: Long,
        val cost: Double?,
        val model: String?,
        val provider: String?,
    ) : C2Event

    data class Error(
        override val sessionId: String,
        override val turnId: String,
        override val agentName: String?,
        val code: String,
        val message: String,
        val recoverable: Boolean,
    ) : C2Event

    /**
     * Not part of the `C2_EVENT_TYPES` set on the server, but delivered on
     * the same SSE stream. Surfaces lane 3.1's F2 approval gate to clients.
     */
    data class ApprovalRequested(
        val approvalId: String,
        override val sessionId: String,
        override val turnId: String,
        override val agentName: String?,
        val kind: String,
        val toolName: String,
        val payload: JSONObject?,
        val timeoutSeconds: Int,
    ) : C2Event

    companion object {
        /**
         * Parses one SSE `data:` line's JSON payload into a [C2Event].
         *
         * Returns `null` (never throws) for unknown `type` values or
         * malformed payloads, so the stream stays forward-compatible with
         * event types added to the server later — callers should log and
         * skip a `null` result rather than treat it as fatal.
         */
        fun fromJson(json: JSONObject): C2Event? {
            val type = json.optString("type", "")
            val sessionId = json.optString("sessionId", "")
            val turnId = json.optString("turnId", "")
            val agentName = if (json.isNull("agentName") || !json.has("agentName")) {
                null
            } else {
                json.optString("agentName").ifEmpty { null }
            }

            return when (type) {
                "tool/started" -> ToolStarted(
                    sessionId = sessionId,
                    turnId = turnId,
                    agentName = agentName,
                    toolCallId = json.optString("toolCallId", ""),
                    name = json.optString("name", ""),
                    args = json.optJSONObject("args"),
                )
                "tool/completed" -> ToolCompleted(
                    sessionId = sessionId,
                    turnId = turnId,
                    agentName = agentName,
                    toolCallId = json.optString("toolCallId", ""),
                    name = json.optString("name", ""),
                    result = json.optJSONObject("result"),
                    durationMs = json.optLong("durationMs", 0L),
                )
                "progress" -> Progress(
                    sessionId = sessionId,
                    turnId = turnId,
                    agentName = agentName,
                    message = json.optString("message", ""),
                    percent = if (json.has("percent") && !json.isNull("percent")) json.optInt("percent") else null,
                )
                "thinking/delta" -> ThinkingDelta(
                    sessionId = sessionId,
                    turnId = turnId,
                    agentName = agentName,
                    text = json.optString("text", ""),
                )
                "thinking/final" -> ThinkingFinal(
                    sessionId = sessionId,
                    turnId = turnId,
                    agentName = agentName,
                    text = json.optString("text", ""),
                )
                "usage" -> Usage(
                    sessionId = sessionId,
                    turnId = turnId,
                    agentName = agentName,
                    inputTokens = json.optLong("inputTokens", 0L),
                    outputTokens = json.optLong("outputTokens", 0L),
                    cost = if (json.has("cost") && !json.isNull("cost")) json.optDouble("cost") else null,
                    model = if (json.has("model") && !json.isNull("model")) json.optString("model") else null,
                    provider = if (json.has("provider") && !json.isNull("provider")) {
                        json.optString("provider")
                    } else {
                        null
                    },
                )
                "error" -> Error(
                    sessionId = sessionId,
                    turnId = turnId,
                    agentName = agentName,
                    code = json.optString("code", ""),
                    message = json.optString("message", ""),
                    recoverable = json.optBoolean("recoverable", false),
                )
                "approval/requested" -> ApprovalRequested(
                    approvalId = json.optString("approvalId", ""),
                    sessionId = sessionId,
                    turnId = turnId,
                    agentName = agentName,
                    kind = json.optString("kind", ""),
                    toolName = json.optString("toolName", ""),
                    payload = json.optJSONObject("payload"),
                    timeoutSeconds = json.optInt("timeoutSeconds", 0),
                )
                else -> null
            }
        }
    }
}
