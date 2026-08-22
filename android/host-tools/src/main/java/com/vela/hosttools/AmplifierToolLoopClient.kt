package com.vela.hosttools

import com.vela.core.domain.HostTool.ToolResult
import com.vela.core.domain.HostToolRegistry
import java.io.BufferedReader
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Client-side implementation of the OpenCode tool-loop pattern (design doc
 * §4.2 C1, and the goal file item 6):
 *
 *   1. POST to a stock OpenAI-compatible chat-completions endpoint, declaring
 *      the client's own tools in the `tools:` field.
 *   2. The model selects a tool: a `delta.tool_calls` chunk streams in over
 *      SSE, and the stream ends immediately with `finish_reason: "tool_calls"`
 *      (A5). Verified against the live `amplifier-agent serve` instance:
 *      `tool_calls` are populated ONLY in the streamed delta chunks — the
 *      non-streaming aggregated `message` does not carry them (confirmed by
 *      direct curl probes against this deployment). So this client MUST use
 *      `stream: true` and reconstruct the tool call from the deltas, exactly
 *      as OpenCode's own client does for the identical reason.
 *   3. Execute the matching [com.vela.core.domain.HostTool] locally via the
 *      [HostToolRegistry].
 *   4. Re-POST the conversation with a `{role: "tool", content: ...,
 *      tool_call_id: ...}` message appended, continuing until a normal
 *      assistant turn completes (no more tool_calls).
 */
class AmplifierToolLoopClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val registry: HostToolRegistry,
    private val model: String = "claude-haiku-4-5-20251001",
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build(),
) {
    private val jsonMediaType = "application/json".toMediaType()

    /**
     * Runs a full turn starting from [userMessage], executing any tool calls
     * the model requests, until a normal (non-tool-call) assistant message is
     * returned. Returns the final assistant message content.
     *
     * @param history prior conversation turns (role/content pairs, oldest first) to send
     *   ahead of [userMessage] so the server sees the real conversation, not just this one
     *   message in isolation. Each entry must have "role" (user|assistant) and "content".
     *   Callers own retaining/trimming this list across calls -- this client is stateless
     *   per call by design; it does not persist history itself.
     * @param maxRounds safety bound on tool-call round-trips to avoid infinite loops.
     */
    suspend fun runTurn(
        userMessage: String,
        history: List<Pair<String, String>> = emptyList(),
        maxRounds: Int = 5,
    ): TurnResult = withContext(Dispatchers.IO) {
        val messages = JSONArray()
        for ((role, content) in history) {
            messages.put(JSONObject().put("role", role).put("content", content))
        }
        messages.put(JSONObject().put("role", "user").put("content", userMessage))

        val toolCallLog = mutableListOf<String>()

        repeat(maxRounds) {
            val turnChunk = streamChatCompletion(messages)

            if (turnChunk.finishReason != "tool_calls" || turnChunk.toolCalls.isEmpty()) {
                // Normal completed turn.
                return@withContext TurnResult(turnChunk.content, toolCallLog)
            }

            // Append the assistant's tool-call message to the transcript before executing.
            val assistantMessage = JSONObject().put("role", "assistant")
            if (turnChunk.content.isNotEmpty()) assistantMessage.put("content", turnChunk.content)
            val toolCallsJson = JSONArray()
            for (tc in turnChunk.toolCalls) {
                toolCallsJson.put(
                    JSONObject()
                        .put("id", tc.id)
                        .put("type", "function")
                        .put(
                            "function",
                            JSONObject().put("name", tc.name).put("arguments", tc.argumentsJson),
                        ),
                )
            }
            assistantMessage.put("tool_calls", toolCallsJson)
            messages.put(assistantMessage)

            for (toolCall in turnChunk.toolCalls) {
                val tool = registry.find(toolCall.name)
                val resultJson = if (tool == null) {
                    JSONObject().put("error", "unknown tool: ${toolCall.name}").toString()
                } else {
                    when (val result = tool.execute(toolCall.argumentsJson)) {
                        is ToolResult.Success -> result.resultJson
                        is ToolResult.Failure -> JSONObject().put("error", result.message).toString()
                        is ToolResult.NeedsConfirmation -> JSONObject()
                            .put("needsConfirmation", true)
                            .put("prompt", result.promptText)
                            .put("confirmationToken", result.confirmationToken)
                            .toString()
                    }
                }
                toolCallLog.add("${toolCall.name}(${toolCall.argumentsJson}) -> $resultJson")

                messages.put(
                    JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", toolCall.id)
                        .put("content", resultJson),
                )
            }
        }
        throw IOException("tool loop did not converge within $maxRounds rounds")
    }

    /** Accumulated state for tool-call deltas, keyed by the streaming `index`. */
    private class ToolCallAccumulator {
        var id: String = ""
        var name: String = ""
        val argumentsBuilder = StringBuilder()
    }

    private data class ToolCallResolved(val id: String, val name: String, val argumentsJson: String)

    private data class StreamedTurn(
        val content: String,
        val finishReason: String,
        val toolCalls: List<ToolCallResolved>,
    )

    /** POSTs with `stream: true` and aggregates the SSE deltas into one logical turn result. */
    private fun streamChatCompletion(messages: JSONArray): StreamedTurn {
        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("stream", true)
            .put("tools", buildToolsArray())

        val request = Request.Builder()
            .url("$baseUrl/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Accept", "text/event-stream")
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errBody = response.body?.string() ?: ""
                throw IOException("chat completion failed: HTTP ${response.code}: $errBody")
            }
            val source = response.body?.source() ?: throw IOException("empty response body")
            val contentBuilder = StringBuilder()
            var finishReason = ""
            val toolCallAccumulators = LinkedHashMap<Int, ToolCallAccumulator>()

            val reader: BufferedReader = source.inputStream().bufferedReader()
            reader.forEachLine { line ->
                if (!line.startsWith("data:")) return@forEachLine
                val payload = line.removePrefix("data:").trim()
                if (payload == "[DONE]" || payload.isEmpty()) return@forEachLine

                val chunk = JSONObject(payload)
                val choices = chunk.optJSONArray("choices") ?: return@forEachLine
                if (choices.length() == 0) return@forEachLine
                val choice = choices.getJSONObject(0)
                choice.optString("finish_reason", null)?.let { if (it.isNotEmpty() && it != "null") finishReason = it }

                val delta = choice.optJSONObject("delta") ?: return@forEachLine
                delta.optString("content", null)?.let { contentBuilder.append(it) }

                val toolCallsDelta = delta.optJSONArray("tool_calls")
                if (toolCallsDelta != null) {
                    for (i in 0 until toolCallsDelta.length()) {
                        val tcDelta = toolCallsDelta.getJSONObject(i)
                        val index = tcDelta.optInt("index", 0)
                        val acc = toolCallAccumulators.getOrPut(index) { ToolCallAccumulator() }
                        tcDelta.optString("id", null)?.let { if (it.isNotEmpty()) acc.id = it }
                        tcDelta.optJSONObject("function")?.let { fn ->
                            fn.optString("name", null)?.let { if (it.isNotEmpty()) acc.name = it }
                            fn.optString("arguments", null)?.let { acc.argumentsBuilder.append(it) }
                        }
                    }
                }
            }

            val resolvedToolCalls = toolCallAccumulators.values.map {
                ToolCallResolved(it.id, it.name, it.argumentsBuilder.toString().ifBlank { "{}" })
            }
            return StreamedTurn(contentBuilder.toString(), finishReason, resolvedToolCalls)
        }
    }

    private fun buildToolsArray(): JSONArray {
        val tools = JSONArray()
        for (tool in registry.all()) {
            val function = JSONObject()
                .put("name", tool.name)
                .put("description", tool.description)
                .put("parameters", JSONObject(tool.inputSchema))
            tools.put(JSONObject().put("type", "function").put("function", function))
        }
        return tools
    }

    data class TurnResult(val finalContent: String, val toolCallLog: List<String>)
}
