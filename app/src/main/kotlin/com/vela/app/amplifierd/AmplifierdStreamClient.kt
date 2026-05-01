package com.vela.app.amplifierd

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

// ── Stream event hierarchy ─────────────────────────────────────────────────────

sealed class StreamEvent {
    data class Token(val content: String)                                       : StreamEvent()
    data class ToolStart(val id: String, val name: String, val input: String)  : StreamEvent()
    data class ToolResult(
        val id: String,
        val output: String,
        val isError: Boolean = false,
    ) : StreamEvent()
    data class ApprovalRequest(
        val id: String,
        val question: String,
        val context: String = "",
    ) : StreamEvent()
    object Done                                                                 : StreamEvent()
    data class Error(val message: String)                                       : StreamEvent()
}

/**
 * SSE streaming client for amplifierd session execution.
 *
 * The amplifierd streaming model:
 *   1. POST /sessions/{id}/execute/stream  → accepted (202) with correlation_id
 *   2. GET  /events                         → SSE stream of all events, filtered by session_id
 *
 * Event format (standard SSE):
 *   id: 42
 *   event: llm:chunk
 *   data: {"event":"llm:chunk","data":{"text":"Hello"},"session_id":"...","correlation_id":"...","sequence":42}
 *
 * The "event:" SSE field is the event type; "data:" is the JSON payload.
 * Completion is signalled by `prompt:complete` or `orchestrator:complete` events.
 */
class AmplifierdStreamClient(
    private val baseUrl: String,
    private val token: String,
) {
    /** OkHttp client for SSE — long read timeout since streams run indefinitely. */
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .build()

    /** Short-lived client for the initial execute/stream POST. */
    private val postHttp = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Submit [prompt] to [sessionId] and stream events back as a [Flow].
     *
     * Steps:
     * 1. POST /sessions/{sessionId}/execute/stream to submit the prompt.
     * 2. Open GET /events SSE stream.
     * 3. Parse each event and emit it; stop when `prompt:complete` or `orchestrator:complete`
     *    arrives for this session.
     */
    fun stream(
        sessionId: String,
        prompt: String,
        imageBase64s: List<String> = emptyList(),
    ): Flow<StreamEvent> = flow {
        // Step 1: Submit the prompt
        val correlationId = submitPrompt(sessionId, prompt, imageBase64s)
        if (correlationId == null) {
            emit(StreamEvent.Error("Failed to submit prompt to amplifierd"))
            return@flow
        }
        Log.d(TAG, "Prompt submitted; correlationId=$correlationId")

        // Step 2: Open the SSE event stream scoped to this session.
        // The ?session= param is critical: it tells amplifierd to:
        //   a) deliver only events for this session (server-side filter), and
        //   b) replay all past events from sequence 1 so we never miss tokens
        //      emitted before the SSE connection was established (race-condition fix).
        val eventsRequest = Request.Builder()
            .url("$baseUrl/events?session=$sessionId")
            .header("x-amplifier-token", token)
            .header("Accept", "text/event-stream")
            .get()
            .build()

        withContext(Dispatchers.IO) {
            val response = http.newCall(eventsRequest).execute()
            if (!response.isSuccessful) {
                emit(StreamEvent.Error("GET /events HTTP ${response.code}"))
                return@withContext
            }

            val source = response.body?.source() ?: run {
                emit(StreamEvent.Error("No SSE body from /events"))
                return@withContext
            }

            // SSE state: accumulate lines until a blank line (event boundary)
            var eventType = ""
            var dataLine  = ""

            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break

                when {
                    line.startsWith("event: ") -> eventType = line.removePrefix("event: ").trim()
                    line.startsWith("data: ")  -> dataLine  = line.removePrefix("data: ").trim()
                    line.isEmpty()             -> {
                        // End of SSE event block — parse and emit
                        if (dataLine.isNotBlank()) {
                            val parsed = parseEvent(eventType, dataLine, sessionId, correlationId)
                            if (parsed != null) emit(parsed)

                            // Stop when the prompt is complete
                            if (parsed is StreamEvent.Done) {
                                source.close()
                                return@withContext
                            }
                        }
                        eventType = ""
                        dataLine  = ""
                    }
                    // SSE id: and retry: lines are ignored
                }
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** POST the prompt to execute/stream. Returns the correlation_id or null on failure. */
    private suspend fun submitPrompt(
        sessionId: String,
        prompt: String,
        imageBase64s: List<String>,
    ): String? = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("prompt", prompt)
                if (imageBase64s.isNotEmpty()) put("images", JSONArray(imageBase64s))
            }
            val request = Request.Builder()
                .url("$baseUrl/sessions/$sessionId/execute/stream")
                .header("x-amplifier-token", token)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val resp = postHttp.newCall(request).execute()
            val bodyStr = resp.body?.string() ?: return@withContext null
            if (!resp.isSuccessful) {
                Log.w(TAG, "execute/stream error ${resp.code}: $bodyStr")
                return@withContext null
            }
            JSONObject(bodyStr).optString("correlation_id").ifBlank { null }
        } catch (e: IOException) {
            Log.w(TAG, "submitPrompt failed: ${e.message}")
            null
        }
    }

    /**
     * Parse a single SSE event block into a [StreamEvent].
     *
     * Returns null for events we don't care about (metadata, keep-alives, etc.).
     * Returns [StreamEvent.Done] when the prompt is complete.
     */
    private fun parseEvent(
        sseEventType: String,
        dataJson: String,
        sessionId: String,
        correlationId: String,
    ): StreamEvent? {
        return try {
            val obj     = JSONObject(dataJson)
            val evtSid  = obj.optString("session_id")
            val evtCid  = obj.optString("correlation_id")

            // Filter — only handle events for this session/correlation
            if (evtSid != sessionId && evtCid != correlationId) return null

            val eventName = obj.optString("event").ifBlank { sseEventType }
            val data      = obj.optJSONObject("data") ?: JSONObject()

            when {
                // Text tokens — amplifierd uses "llm:chunk" for streaming tokens
                eventName == "llm:chunk" -> {
                    val text = data.optString("text", data.optString("content", ""))
                    if (text.isNotBlank()) StreamEvent.Token(text) else null
                }

                // Tool start
                eventName.startsWith("tool:start") || eventName == "tool_start" -> {
                    StreamEvent.ToolStart(
                        id    = data.optString("id", data.optString("call_id", "")),
                        name  = data.optString("name", data.optString("tool_name", "")),
                        input = data.optJSONObject("input")?.toString() ?: "",
                    )
                }

                // Tool result / done
                eventName.startsWith("tool:result") || eventName == "tool_result"
                || eventName.startsWith("tool:done") -> {
                    StreamEvent.ToolResult(
                        id      = data.optString("id", data.optString("call_id", "")),
                        output  = data.optString("output", data.optString("result", "")),
                        isError = data.optBoolean("is_error", false),
                    )
                }

                // Approval request
                eventName.contains("approval") -> {
                    StreamEvent.ApprovalRequest(
                        id       = data.optString("id", data.optString("request_id", "")),
                        question = data.optString("question", data.optString("message", "")),
                        context  = data.optString("context", ""),
                    )
                }

                // Completion events
                eventName == "prompt:complete" || eventName == "orchestrator:complete" -> {
                    StreamEvent.Done
                }

                // Provider/LLM errors
                eventName == "provider:error" || (eventName == "llm:response" &&
                    data.optString("status") == "error") -> {
                    val msg = data.optString("error", data.optJSONObject("error")?.optString("msg") ?: "Unknown error")
                    StreamEvent.Error(msg)
                }

                else -> null // ignored event type
            }
        } catch (_: Exception) {
            null // skip malformed JSON
        }
    }

    companion object {
        private const val TAG = "AmplifierdStreamClient"
    }
}
