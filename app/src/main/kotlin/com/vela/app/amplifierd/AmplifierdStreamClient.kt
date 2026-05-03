package com.vela.app.amplifierd

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "AmplifierdStream"

/** Typed events emitted by the amplifierd SSE stream. */
sealed class StreamEvent {
    /** A single streamed token from content_block:delta (loop-vela real-time streaming). */
    data class TextDelta(val token: String, val blockIndex: Int = 0) : StreamEvent()
    /** The complete text for a block, from content_block:end. Used as authoritative final text. */
    data class TextBlock(val text: String, val blockIndex: Int = 0) : StreamEvent()
    data class ToolUse(val id: String, val name: String, val inputJson: String) : StreamEvent()
    data class ProviderRetry(val attempt: Int, val maxRetries: Int, val errorMessage: String, val delaySecs: Double) : StreamEvent()
    data class ApprovalRequest(val id: String, val question: String, val context: String = "") : StreamEvent()
    /** Session was given a name by hooks-session-naming after sufficient turns. */
    data class Named(val name: String) : StreamEvent()
    object Thinking : StreamEvent()
    /** Thinking block content from content_block:end with type="thinking". */
    data class ThinkingBlock(val text: String) : StreamEvent()
    object Done : StreamEvent()
    data class Error(val message: String) : StreamEvent()
}

/**
 * Streams events from an amplifierd session.
 *
 * PROTOCOL (order matters — verified by live curl 2026-05-01):
 * 1. Open GET /events?session={id} FIRST — server replays all past events from seq 1
 * 2. POST /sessions/{id}/execute/stream — returns {correlation_id, status:"accepted"}
 * 3. Filter incoming SSE events to ONLY those matching our correlation_id
 *    (past events have a different correlation_id and must be skipped)
 * 4. Collect until execution:end or orchestrator:complete with OUR correlation_id
 *
 * WHY correlation_id filtering is required:
 * amplifierd replays ALL past events when you subscribe to GET /events. Without filtering,
 * orchestrator:complete from a PREVIOUS turn fires immediately and we exit before the
 * new execution's events arrive. Filtering by correlation_id solves this correctly.
 *
 * WHY flowOn(Dispatchers.IO) not withContext:
 * emit() inside withContext() inside flow {} violates Kotlin Flow invariants.
 * flowOn() runs the entire upstream on IO without breaking the emission contract.
 */
class AmplifierdStreamClient(private val baseUrl: String, private val token: String) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .build()

    fun stream(sessionId: String, message: String): Flow<StreamEvent> = flow {
        Log.d(TAG, "stream: opening SSE for session=$sessionId")

        // Step 1: Open SSE stream BEFORE posting the prompt
        val eventsRequest = Request.Builder()
            .url("$baseUrl/events?session=$sessionId")
            .header("x-amplifier-token", token)
            .header("Accept", "text/event-stream")
            .get()
            .build()

        val sseResponse = http.newCall(eventsRequest).execute()
        if (!sseResponse.isSuccessful) {
            Log.e(TAG, "stream: GET /events failed HTTP ${sseResponse.code}")
            emit(StreamEvent.Error("Events stream failed: HTTP ${sseResponse.code}"))
            return@flow
        }
        Log.d(TAG, "stream: SSE connection open (${sseResponse.code})")

        // Step 2: Submit the prompt — capture correlation_id to filter replayed past events
        val promptBody = JSONObject().apply { put("prompt", message) }
            .toString().toRequestBody("application/json".toMediaType())
        val submitRequest = Request.Builder()
            .url("$baseUrl/sessions/$sessionId/execute/stream")
            .header("x-amplifier-token", token)
            .post(promptBody)
            .build()

        val submitResponse = http.newCall(submitRequest).execute()
        if (!submitResponse.isSuccessful) {
            Log.e(TAG, "stream: POST execute failed HTTP ${submitResponse.code}")
            sseResponse.close()
            emit(StreamEvent.Error("Session not found or cannot accept prompts: HTTP ${submitResponse.code}"))
            return@flow
        }

        val correlationId = try {
            JSONObject(submitResponse.body?.string() ?: "").optString("correlation_id", "")
        } catch (e: Exception) { "" }
        Log.d(TAG, "stream: execute accepted, correlation_id=$correlationId")

        // Step 3: Read SSE events, filter to OUR correlation_id only
        val source = sseResponse.body?.source() ?: run {
            emit(StreamEvent.Error("Empty SSE response body"))
            return@flow
        }

        var currentEventName = ""
        var isDone = false
        var eventCount = 0

        while (!isDone && !source.exhausted()) {
            val line = source.readUtf8Line() ?: break

            when {
                line.startsWith("event: ") -> currentEventName = line.removePrefix("event: ").trim()
                line.startsWith("data: ") -> {
                    val dataStr = line.removePrefix("data: ").trim()
                    if (dataStr == "[DONE]") { emit(StreamEvent.Done); isDone = true; break }

                    try {
                        val obj = JSONObject(dataStr)

                        // Filter out events from previous executions
                        val eventCorrelId = obj.optString("correlation_id", "")
                        if (correlationId.isNotEmpty() && eventCorrelId.isNotEmpty()
                            && eventCorrelId != correlationId) {
                            Log.v(TAG, "stream: skipping replayed event $currentEventName")
                            continue
                        }

                        eventCount++
                        Log.d(TAG, "stream: event[$eventCount] $currentEventName")

                        val dataObj = obj.optJSONObject("data") ?: JSONObject()
                        val event: StreamEvent? = when (currentEventName) {
                            "execution:start" -> StreamEvent.Thinking

                            // loop-vela per-token streaming
                            "content_block:delta" -> {
                                val token = dataObj.optString("token", "")
                                val blockIndex = dataObj.optInt("block_index", 0)
                                if (token.isNotEmpty()) StreamEvent.TextDelta(token, blockIndex) else null
                            }

                            "provider:retry" -> StreamEvent.ProviderRetry(
                                attempt      = dataObj.optInt("attempt", 1),
                                maxRetries   = dataObj.optInt("max_retries", 5),
                                errorMessage = dataObj.optString("error_message", "Connection error"),
                                delaySecs    = dataObj.optDouble("delay", 0.0),
                            )

                            "content_block:end" -> {
                                val block = dataObj.optJSONObject("block")
                                val blockIndex = dataObj.optInt("block_index", 0)
                                when (block?.optString("type")) {
                                    "text" -> {
                                        val text = block.optString("text", "")
                                        if (text.isNotBlank()) {
                                            Log.d(TAG, "stream: TextBlock len=${text.length}")
                                            StreamEvent.TextBlock(text, blockIndex)
                                        } else null
                                    }
                                    // amplifierd SSE uses "tool_call"; transcript uses "tool_use" — handle both.
                                    // Input field: SSE uses "arguments", transcript uses "input" — handle both.
                                    "tool_use", "tool_call" -> StreamEvent.ToolUse(
                                        id        = block.optString("id", ""),
                                        name      = block.optString("name", ""),
                                        inputJson = (block.optJSONObject("arguments")
                                            ?: block.optJSONObject("input"))?.toString() ?: "{}",
                                    )
                                    "thinking" -> {
                                        val thinkText = block.optString("thinking", "")
                                        if (thinkText.isNotBlank()) StreamEvent.ThinkingBlock(thinkText) else null
                                    }
                                    else -> null
                                }
                            }

                            "execution:end", "orchestrator:complete" -> {
                                Log.d(TAG, "stream: Done from $currentEventName")
                                emit(StreamEvent.Done)
                                isDone = true
                                null
                            }

                            "approval:request", "approval_request" -> StreamEvent.ApprovalRequest(
                                id       = dataObj.optString("approval_id", dataObj.optString("id", "")),
                                question = dataObj.optString("question", ""),
                                context  = dataObj.optString("context", ""),
                            )

                            "session:named", "hooks:session-naming:complete" -> {
                                val name = dataObj.optString("name", dataObj.optString("session_name", ""))
                                if (name.isNotBlank()) StreamEvent.Named(name) else null
                            }

                            else -> null
                        }
                        event?.let { emit(it) }
                    } catch (e: Exception) {
                        Log.w(TAG, "stream: parse error: ${e.message}")
                    }
                }
                line.isBlank() -> currentEventName = ""
            }
        }

        Log.d(TAG, "stream: loop exited isDone=$isDone eventCount=$eventCount")
        if (!isDone) emit(StreamEvent.Done)
    }.flowOn(Dispatchers.IO) // run the blocking OkHttp reads on IO without breaking emit() contract

    /**
     * Subscribe to amplifierd SSE events for an existing session WITHOUT submitting a new prompt.
     *
     * Opens GET /events?session={id} and processes all incoming events.
     * amplifierd replays past events from seq 1 — callers must handle idempotent delivery.
     *
     * Use this when the session is already executing (e.g. resume flow, re-attach to running session).
     * For new executions, use [stream] instead.
     */
    fun subscribeEvents(sessionId: String): Flow<StreamEvent> = flow {
        Log.d(TAG, "subscribeEvents: opening SSE for session=$sessionId")

        val eventsRequest = Request.Builder()
            .url("$baseUrl/events?session=$sessionId")
            .header("x-amplifier-token", token)
            .header("Accept", "text/event-stream")
            .get()
            .build()

        val sseResponse = http.newCall(eventsRequest).execute()
        if (!sseResponse.isSuccessful) {
            Log.e(TAG, "subscribeEvents: GET /events failed HTTP ${sseResponse.code}")
            emit(StreamEvent.Error("Events stream failed: HTTP ${sseResponse.code}"))
            return@flow
        }
        Log.d(TAG, "subscribeEvents: SSE connection open (${sseResponse.code})")

        val source = sseResponse.body?.source() ?: run {
            emit(StreamEvent.Error("Empty SSE response body"))
            return@flow
        }

        var currentEventName = ""
        var isDone = false
        var eventCount = 0

        while (!isDone && !source.exhausted()) {
            val line = source.readUtf8Line() ?: break

            when {
                line.startsWith("event: ") -> currentEventName = line.removePrefix("event: ").trim()
                line.startsWith("data: ") -> {
                    val dataStr = line.removePrefix("data: ").trim()
                    if (dataStr == "[DONE]") { emit(StreamEvent.Done); isDone = true; break }

                    try {
                        val obj = JSONObject(dataStr)
                        eventCount++
                        Log.d(TAG, "subscribeEvents: event[$eventCount] $currentEventName")

                        val dataObj = obj.optJSONObject("data") ?: JSONObject()
                        val event: StreamEvent? = when (currentEventName) {
                            "execution:start" -> StreamEvent.Thinking

                            "content_block:delta" -> {
                                val token = dataObj.optString("token", "")
                                val blockIndex = dataObj.optInt("block_index", 0)
                                if (token.isNotEmpty()) StreamEvent.TextDelta(token, blockIndex) else null
                            }

                            "provider:retry" -> StreamEvent.ProviderRetry(
                                attempt      = dataObj.optInt("attempt", 1),
                                maxRetries   = dataObj.optInt("max_retries", 5),
                                errorMessage = dataObj.optString("error_message", "Connection error"),
                                delaySecs    = dataObj.optDouble("delay", 0.0),
                            )

                            "content_block:end" -> {
                                val block = dataObj.optJSONObject("block")
                                val blockIndex = dataObj.optInt("block_index", 0)
                                when (block?.optString("type")) {
                                    "text" -> {
                                        val text = block.optString("text", "")
                                        if (text.isNotBlank()) {
                                            Log.d(TAG, "subscribeEvents: TextBlock len=${text.length}")
                                            StreamEvent.TextBlock(text, blockIndex)
                                        } else null
                                    }
                                    // amplifierd SSE uses "tool_call"; transcript uses "tool_use" — handle both.
                                    // Input field: SSE uses "arguments", transcript uses "input" — handle both.
                                    "tool_use", "tool_call" -> StreamEvent.ToolUse(
                                        id        = block.optString("id", ""),
                                        name      = block.optString("name", ""),
                                        inputJson = (block.optJSONObject("arguments")
                                            ?: block.optJSONObject("input"))?.toString() ?: "{}",
                                    )
                                    "thinking" -> {
                                        val thinkText = block.optString("thinking", "")
                                        if (thinkText.isNotBlank()) StreamEvent.ThinkingBlock(thinkText) else null
                                    }
                                    else -> null
                                }
                            }

                            "execution:end", "orchestrator:complete" -> {
                                Log.d(TAG, "subscribeEvents: Done from $currentEventName")
                                emit(StreamEvent.Done)
                                // Don't exit — stay subscribed for subsequent executions.
                                null
                            }

                            "approval:request", "approval_request" -> StreamEvent.ApprovalRequest(
                                id       = dataObj.optString("approval_id", dataObj.optString("id", "")),
                                question = dataObj.optString("question", ""),
                                context  = dataObj.optString("context", ""),
                            )

                            "session:named", "hooks:session-naming:complete" -> {
                                val name = dataObj.optString("name", dataObj.optString("session_name", ""))
                                if (name.isNotBlank()) StreamEvent.Named(name) else null
                            }

                            else -> null
                        }
                        event?.let { emit(it) }
                    } catch (e: Exception) {
                        Log.w(TAG, "subscribeEvents: parse error: ${e.message}")
                    }
                }
                line.isBlank() -> currentEventName = ""
            }
        }

        Log.d(TAG, "subscribeEvents: loop exited isDone=$isDone eventCount=$eventCount")
        if (!isDone) emit(StreamEvent.Done)
    }.flowOn(Dispatchers.IO)
}
