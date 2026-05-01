package com.vela.app.amplifierd

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Typed events emitted by the amplifierd SSE stream. */
sealed class StreamEvent {
    /** Text content from a content_block:end with block_type "text". */
    data class TextBlock(val text: String, val blockIndex: Int = 0) : StreamEvent()
    /** Tool call from content_block:end with block_type "tool_use". */
    data class ToolUse(val id: String, val name: String, val inputJson: String) : StreamEvent()
    /** Provider retry notification — surface to UI so user knows LLM is retrying. */
    data class ProviderRetry(val attempt: Int, val maxRetries: Int, val errorMessage: String, val delaySecs: Double) : StreamEvent()
    /** Approval required — user must respond before session continues. */
    data class ApprovalRequest(val id: String, val question: String, val context: String = "") : StreamEvent()
    /** Session execution is in progress — show thinking indicator. */
    object Thinking : StreamEvent()
    /** Session completed successfully. */
    object Done : StreamEvent()
    /** Unrecoverable error. */
    data class Error(val message: String) : StreamEvent()
}

/**
 * Streams events from an amplifierd session.
 *
 * Protocol:
 * 1. Open GET /events?session={id} SSE stream FIRST (server replays from seq 1)
 * 2. POST /sessions/{id}/execute/stream to submit the prompt (returns 202 + correlation_id)
 * 3. Collect events from the SSE stream until execution:end or orchestrator:complete
 */
class AmplifierdStreamClient(private val baseUrl: String, private val token: String) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .build()

    fun stream(sessionId: String, message: String, lastEventId: String? = null): Flow<StreamEvent> = flow {
        withContext(Dispatchers.IO) {
            // Step 1: Open SSE stream BEFORE posting the prompt (no race condition)
            val eventsRequest = Request.Builder()
                .url("$baseUrl/events?session=$sessionId")
                .header("x-amplifier-token", token)
                .header("Accept", "text/event-stream")
                .apply { if (lastEventId != null) header("Last-Event-ID", lastEventId) }
                .get()
                .build()

            val response = http.newCall(eventsRequest).execute()
            if (!response.isSuccessful) {
                emit(StreamEvent.Error("Events stream failed: HTTP ${response.code}"))
                return@withContext
            }

            // Step 2: Submit the prompt asynchronously (fire-and-forget)
            val promptBody = JSONObject().apply { put("prompt", message) }
                .toString().toRequestBody("application/json".toMediaType())
            val submitRequest = Request.Builder()
                .url("$baseUrl/sessions/$sessionId/execute/stream")
                .header("x-amplifier-token", token)
                .post(promptBody)
                .build()
            http.newCall(submitRequest).execute().use { /* just need 202, ignore body */ }

            // Step 3: Read and parse SSE events
            val source = response.body?.source() ?: run {
                emit(StreamEvent.Error("Empty response body from events stream"))
                return@withContext
            }

            var currentEventName = ""
            var isDone = false

            while (!isDone && !source.exhausted()) {
                val line = source.readUtf8Line() ?: break

                when {
                    line.startsWith("event: ") -> currentEventName = line.removePrefix("event: ").trim()
                    line.startsWith("data: ") -> {
                        val dataStr = line.removePrefix("data: ").trim()
                        if (dataStr == "[DONE]") { emit(StreamEvent.Done); isDone = true; break }

                        try {
                            val obj = JSONObject(dataStr)
                            val dataObj = obj.optJSONObject("data") ?: JSONObject()

                            val event = when (currentEventName) {
                                "execution:start" -> StreamEvent.Thinking

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
                                            if (text.isNotBlank()) StreamEvent.TextBlock(text, blockIndex) else null
                                        }
                                        "tool_use" -> StreamEvent.ToolUse(
                                            id        = block.optString("id", ""),
                                            name      = block.optString("name", ""),
                                            inputJson = block.optJSONObject("input")?.toString() ?: "{}",
                                        )
                                        "thinking" -> null // hide internal thinking blocks
                                        else -> null
                                    }
                                }

                                "execution:end", "orchestrator:complete" -> {
                                    emit(StreamEvent.Done)
                                    isDone = true
                                    null
                                }

                                // approval_request from hooks-approval if configured
                                "approval:request", "approval_request" -> {
                                    val approvalId = dataObj.optString("approval_id", dataObj.optString("id", ""))
                                    val question   = dataObj.optString("question", "")
                                    val context    = dataObj.optString("context", "")
                                    StreamEvent.ApprovalRequest(approvalId, question, context)
                                }

                                else -> null // silently ignore session:start, llm:request, etc.
                            }
                            event?.let { emit(it) }
                        } catch (e: Exception) {
                            // malformed event — log but don't crash
                        }
                    }
                    line.isBlank() -> currentEventName = "" // reset on blank separator line
                }
            }

            if (!isDone) emit(StreamEvent.Done)
        }
    }
}
