package com.vela.app.ai.tools

import org.json.JSONObject

/**
 * Parses Gemma 4's response text to detect and extract a tool call.
 *
 * Gemma 4 outputs tool calls as JSON when instructed by [VelaPromptBuilder.buildWithTools]:
 *   {"tool":"get_time","args":{}}
 *   {"tool":"get_battery","args":{}}
 *
 * The parser scans the response for the first valid `{"tool":...}` object and
 * returns null if not found or malformed — the caller falls back to plain text rendering.
 */
object ToolCallParser {

    data class ToolCall(
        val toolName: String,
        val args: Map<String, Any>,
    )

    /**
     * Try to extract a [ToolCall] from [responseText].
     * Searches for `{"tool":` anywhere in the response, so it works whether
     * the model outputs pure JSON or JSON mixed with prose.
     * Returns null if no valid tool call is found.
     */
    fun parse(responseText: String): ToolCall? {
        return try {
            // Find the opening brace of a potential tool call JSON object
            var searchFrom = 0
            while (searchFrom < responseText.length) {
                val start = responseText.indexOf('{', searchFrom)
                if (start == -1) return null

                // Walk forward to find the matching close brace (simple depth counter)
                var depth = 0
                var end = start
                while (end < responseText.length) {
                    when (responseText[end]) {
                        '{' -> depth++
                        '}' -> {
                            depth--
                            if (depth == 0) break
                        }
                    }
                    end++
                }
                if (depth != 0) return null // unclosed

                val candidate = responseText.substring(start, end + 1)
                val json = runCatching { JSONObject(candidate) }.getOrNull()

                if (json != null && json.has("tool")) {
                    val toolName = json.optString("tool").ifBlank { return null }
                    val argsJson = json.optJSONObject("args")
                    val args = mutableMapOf<String, Any>()
                    if (argsJson != null) {
                        for (key in argsJson.keys()) {
                            args[key] = argsJson.get(key)
                        }
                    }
                    return ToolCall(toolName = toolName, args = args)
                }

                searchFrom = end + 1
            }
            null
        } catch (_: Exception) {
            null
        }
    }
}
