package com.vela.app.ai

    import com.vela.app.ai.tools.Tool

    /**
     * Builds agentic loop prompts. Provider-agnostic — uses the plain-text JSON-in-prompt
     * format that works for both ML Kit Gemma 4 and Gemma GGUF models via llama.cpp.
     *
     * For llama.cpp, llama_chat_apply_template() in native code wraps this text in the
     * model's built-in chat template (Gemma / Qwen / Llama 3 etc.) before tokenising.
     *
     * Migrated from VelaPromptBuilder; VelaPromptBuilder is kept for backward compat.
     */
    object PromptBuilder {

        /** Max chars from user input — guard against massive prompts exhausting the context. */
        const val MAX_USER_CHARS = 800

        /**
         * Build a tool-aware prompt for [userInput] advertising [tools].
         * The model outputs {"tool":"name","args":{}} to call a tool, or
         * plain text / vela-ui JSON for a direct answer.
         */
        fun buildWithTools(userInput: String, tools: List<Tool>): String {
            val toolBlock = if (tools.isEmpty()) "" else buildString {
                append("
Tools (use when the question requires live data):
")
                tools.forEach { tool ->
                    val params = if (tool.parameters.isEmpty()) "()"
                    else "(${tool.parameters.joinToString(", ") { "${it.name}: ${it.type}" }})"
                    append("  ${tool.name}$params → ${tool.description}
")
                }
                append(
                    "To use a tool output ONLY the JSON: {"tool":"name","args":{}}
" +
                    "Do NOT use tools for questions you can answer directly."
                )
            }

            val prefix = "[Vela: on-device AI assistant.$toolBlock
" +
                "For structured answers you MAY also use vela-ui JSON (card, step, item, tip, code).
" +
                "Plain text preferred for simple answers.]"

            return "$prefix

User: ${userInput.take(MAX_USER_CHARS)}
Vela:"
        }

        /** Plain prompt with no tools — for simple factual queries. */
        fun build(userInput: String): String =
            "[Vela: on-device AI assistant. Respond concisely.]

User: ${userInput.take(MAX_USER_CHARS)}
Vela:"
    }
    