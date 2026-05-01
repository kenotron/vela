package com.vela.app.amplifierd

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
     * SSE streaming client for a single amplifierd session.
     *
     * Streams events from POST /sessions/{sessionId}/execute/stream.
     * The response body is read line-by-line in SSE format:
     *   data: {"type":"token","content":"Hello"}
     *   data: [DONE]
     *
     * Uses a 5-minute read timeout because sessions can run long.
     */
    class AmplifierdStreamClient(
        private val baseUrl: String,
        private val token: String,
    ) {
        private val http = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .build()

        /**
         * Stream events from [sessionId] for the given [prompt].
         *
         * @param imageBase64s  Optional list of base-64-encoded image strings.
         */
        fun stream(
            sessionId: String,
            prompt: String,
            imageBase64s: List<String> = emptyList(),
        ): Flow<StreamEvent> = flow {
            val bodyObj = JSONObject().apply {
                put("prompt", prompt)
                if (imageBase64s.isNotEmpty()) put("images", JSONArray(imageBase64s))
            }
            val request = Request.Builder()
                .url("$baseUrl/sessions/$sessionId/execute/stream")
                .header("x-amplifier-token", token)
                .header("Accept", "text/event-stream")
                .post(bodyObj.toString().toRequestBody("application/json".toMediaType()))
                .build()

            withContext(Dispatchers.IO) {
                val response = http.newCall(request).execute()
                if (!response.isSuccessful) {
                    emit(StreamEvent.Error("HTTP ${response.code}"))
                    return@withContext
                }
                val source = response.body?.source() ?: run {
                    emit(StreamEvent.Error("Empty response body"))
                    return@withContext
                }
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data: ")) continue
                    val data = line.removePrefix("data: ").trim()
                    if (data == "[DONE]") {
                        emit(StreamEvent.Done)
                        break
                    }
                    // Also handle raw JSON that isn't classic SSE (amplifierd variant)
                    try {
                        val obj = JSONObject(data)
                        val event: StreamEvent? = when (obj.optString("type")) {
                            "token"            -> StreamEvent.Token(obj.optString("content", ""))
                            "text"             -> StreamEvent.Token(obj.optString("content", ""))
                            "tool_start"       -> StreamEvent.ToolStart(
                                obj.optString("id"),
                                obj.optString("name", ""),
                                obj.optJSONObject("input")?.toString() ?: "",
                            )
                            "tool_result"      -> StreamEvent.ToolResult(
                                obj.optString("id"),
                                obj.optString("output", ""),
                                obj.optBoolean("is_error", false),
                            )
                            "approval_request" -> StreamEvent.ApprovalRequest(
                                obj.optString("id"),
                                obj.optString("question", ""),
                                obj.optString("context", ""),
                            )
                            else               -> null
                        }
                        event?.let { emit(it) }
                    } catch (_: Exception) { /* skip malformed events */ }
                }
            }
        }
    }
    