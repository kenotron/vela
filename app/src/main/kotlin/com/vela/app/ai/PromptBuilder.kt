package com.vela.app.ai

import com.vela.app.ai.tools.Tool

/**
 * Builds agentic loop prompts. Provider-agnostic.
 * For llama.cpp, llama_chat_apply_template() in native code wraps this text in the
 * model's built-in chat template (Gemma / Qwen / Llama 3) before tokenising.
 */
object PromptBuilder {

    const val MAX_USER_CHARS = 800

    fun buildWithTools(userInput: String, tools: List<Tool>): String {
        val toolBlock = if (tools.isEmpty()) "" else buildString {
            append("\nTools (use when the question requires live data):\n")
            tools.forEach { tool ->
                val params = if (tool.parameters.isEmpty()) "()"
                    else "(${tool.parameters.joinToString(", ") { "${it.name}: ${it.type}" }})"
                append("  ${tool.name}$params \u2192 ${tool.description}\n")
            }
            append("To use a tool output ONLY the JSON: {\"tool\":\"name\",\"args\":{}}\n")
            append("Do NOT use tools for questions you can answer directly.")
        }
        val prefix = "[Vela: on-device AI assistant.$toolBlock\n" +
            "For structured answers you MAY also use vela-ui JSON.\n" +
            "Plain text preferred for simple answers.]"
        return "$prefix\n\nUser: ${userInput.take(MAX_USER_CHARS)}\nVela:"
    }

    fun build(userInput: String): String =
        "[Vela: on-device AI assistant.]\n\nUser: ${userInput.take(MAX_USER_CHARS)}\nVela:"
}
