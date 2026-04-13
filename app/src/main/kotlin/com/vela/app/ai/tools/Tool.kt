    package com.vela.app.ai.tools

    /**
     * A callable tool the AI can invoke via Anthropic's native tool calling.
     */
    interface Tool {
        val name: String

        /** Human-readable label for the UI, e.g. "Web Search". Defaults to [name]. */
        val displayName: String get() = name

        /** Emoji icon shown next to the tool label in the conversation, e.g. "🔍". */
        val icon: String get() = "🔧"

        val description: String
        val parameters: List<ToolParameter> get() = emptyList()

        suspend fun execute(args: Map<String, Any>): String
    }

    data class ToolParameter(
        val name: String,
        val type: String,          // "string" | "number" | "boolean" | "integer"
        val description: String,
        val required: Boolean = true,
    )
    