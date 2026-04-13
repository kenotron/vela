package com.vela.app.ai

import com.vela.app.ai.tools.ToolCallParser

/**
 * Parses Gemma 4's native tool call format emitted by llama.cpp.
 *
 * Gemma 4 outputs tool calls as:
 *   <|tool_call>call:search_web{query:"AI news",limit:5}<tool_call|>
 *
 * Arguments use a key:value encoding (not JSON):
 *   Strings:  key:"value"
 *   Numbers:  key:42
 *   Booleans: key:true
 *
 * Falls back to [ToolCallParser] (JSON-in-prompt format {"tool":"name","args":{}})
 * so the harness also works with MlKitInferenceProvider.
 */
object GemmaToolCallParser {

    private val TOOL_CALL_REGEX = Regex(
        """<\|tool_call>call:(\w+)\{(.*?)}<tool_call\|>""",
        setOf(RegexOption.DOT_MATCHES_ALL),
    )

    /** Extract a tool call from [text], or null if none found. */
    fun parse(text: String): ToolCallParser.ToolCall? {
        // 1. Gemma 4 native: <|tool_call>call:name{args}<tool_call|>
        val match = TOOL_CALL_REGEX.find(text)
        if (match != null) {
            val name = match.groupValues[1]
            val args = parseArgs(match.groupValues[2].trim())
            return ToolCallParser.ToolCall(toolName = name, args = args)
        }
        // 2. Fallback: JSON-in-prompt {"tool":"name","args":{}}
        return ToolCallParser.parse(text)
    }

    /**
     * Parse the key:value block inside the {…} of a Gemma tool call.
     * Handles: query:"text", count:5, flag:true
     */
    private fun parseArgs(block: String): Map<String, Any> {
        if (block.isBlank()) return emptyMap()
        val result = mutableMapOf<String, Any>()
        var depth = 0
        var inQuote = false
        val current = StringBuilder()
        val segments = mutableListOf<String>()

        for (ch in block) {
            when {
                ch == '"' && depth == 0   -> { inQuote = !inQuote; current.append(ch) }
                !inQuote && ch == '{'     -> { depth++; current.append(ch) }
                !inQuote && ch == '}'     -> { depth--; current.append(ch) }
                !inQuote && depth == 0 && ch == ',' -> {
                    segments += current.toString().trim()
                    current.clear()
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotBlank()) segments += current.toString().trim()

        for (seg in segments) {
            val colon = seg.indexOf(':')
            if (colon < 0) continue
            val key = seg.substring(0, colon).trim()
            val raw = seg.substring(colon + 1).trim()
            result[key] = coerceValue(raw)
        }
        return result
    }

    private fun coerceValue(raw: String): Any = when {
        raw.startsWith('"') && raw.endsWith('"') -> raw.removeSurrounding("\"")
        raw == "true"                            -> true
        raw == "false"                           -> false
        raw.toLongOrNull()   != null             -> raw.toLong()
        raw.toDoubleOrNull() != null             -> raw.toDouble()
        else                                     -> raw
    }

    /**
     * Build the <|tool_response> block Gemma 4 expects after a tool call.
     * Injected into the ongoing prompt so the model sees the result.
     */
    fun formatToolResponse(toolName: String, result: String): String =
        "<|tool_response>response:$toolName{value:\"${result.replace("\"", "'")}\"}<tool_response|>"

    /** True if [text] contains a Gemma 4 native tool call tag. */
    fun containsToolCall(text: String): Boolean =
        TOOL_CALL_REGEX.containsMatchIn(text) || ToolCallParser.parse(text) != null
}
