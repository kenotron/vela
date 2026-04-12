package com.vela.app.ai

import com.vela.app.ai.tools.Tool

/**
 * Builds on-device prompts for Gemma 4 E2B (nano-fast) via ML Kit GenAI.
 *
 * ML Kit Preview constraints:
 * - No system prompt → role prefix injected as user-turn workaround
 * - 4000 token limit → user input capped at [MAX_USER_CHARS]
 * - No native tool calling → JSON-in-prompt pattern with [buildWithTools]
 *
 * Tool calling flow:
 *   1. [buildWithTools]   → Gemma 4 produces JSON tool call or plain response
 *   2. [buildToolResult]  → Second call with tool result injected → final answer
 */
object VelaPromptBuilder {

    private const val MAX_USER_CHARS = 800

    // ---------- Plain prompt (no tools) ----------

    private const val PLAIN_PREFIX =
        "[Vela: on-device AI assistant. Respond concisely.\n" +
        "For structured answers (steps, lists, data) you MAY output:\n" +
        "{\"type\":\"vela-ui\",\"components\":[{\"t\":\"card\",\"title\":\"...\"}," +
        "{\"t\":\"step\",\"n\":1,\"text\":\"...\"},{\"t\":\"item\",\"text\":\"...\"}," +
        "{\"t\":\"tip\",\"text\":\"...\"},{\"t\":\"code\",\"text\":\"...\"}]}\n" +
        "Plain text preferred for simple answers.]"

    fun build(userInput: String): String =
        "$PLAIN_PREFIX\n\nUser: ${userInput.take(MAX_USER_CHARS)}\nVela:"

    // ---------- Tool-aware prompt ----------

    /**
     * Build a prompt that advertises [tools] and instructs Gemma 4 to output
     * a JSON tool call when it needs external data.
     *
     * Output format when tool is needed:
     *   {"tool":"get_time","args":{}}
     *
     * Gemma 4 should output ONLY the JSON — no surrounding prose — so the
     * ViewModel can detect and dispatch it cleanly.
     */
    fun buildWithTools(userInput: String, tools: List<Tool>): String {
        val toolBlock = if (tools.isEmpty()) "" else buildString {
            append("\nTools (use when the question requires live data):\n")
            tools.forEach { tool ->
                val params = if (tool.parameters.isEmpty()) "()"
                else "(${tool.parameters.joinToString(", ") { "${it.name}: ${it.type}" }})"
                append("  ${tool.name}$params → ${tool.description}\n")
            }
            append("To use a tool output ONLY the JSON: {\"tool\":\"name\",\"args\":{}}\n" +
                   "Do NOT use tools for questions you can answer directly.")
        }

        val prefix = "[Vela: on-device AI assistant.$toolBlock\n" +
            "For structured answers you MAY also use vela-ui JSON (card, step, item, tip, code).\n" +
            "Plain text preferred for simple answers.]"

        return "$prefix\n\nUser: ${userInput.take(MAX_USER_CHARS)}\nVela:"
    }

    /**
     * Build the follow-up prompt after a tool call has been executed.
     * Injects the original exchange and tool result so Gemma 4 can formulate
     * a natural final answer for the user.
     *
     * @param originalPrompt The prompt from [buildWithTools].
     * @param toolCallJson   The raw JSON string Gemma 4 output (e.g. {"tool":"get_time","args":{}}).
     * @param toolResult     The tool's return value (e.g. "2:34 PM").
     */
    fun buildToolResult(
        originalPrompt: String,
        toolCallJson: String,
        toolResult: String,
    ): String {
        // Strip the trailing "Vela:" to avoid duplicating the role prefix
        val base = originalPrompt.trimEnd().removeSuffix("Vela:").trimEnd()
        return "$base\nVela: $toolCallJson\nTool result: $toolResult\nVela:"
    }
}
