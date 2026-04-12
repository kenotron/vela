package com.vela.app.ai.tools

/**
 * A callable tool that Gemma 4 can invoke via the JSON-in-prompt tool calling pattern.
 *
 * Each tool advertises itself with [name], [description], and optional [parameters].
 * Gemma 4 sees these descriptions in the prompt and outputs:
 *   {"tool":"<name>","args":{"param":"value",...}}
 *
 * The app intercepts the JSON, calls [execute], injects the result, and runs a second
 * inference to produce the final response. See [ToolRegistry] and [ToolCallParser].
 */
interface Tool {
    val name: String
    val description: String

    /**
     * Short parameter list shown in the prompt.
     * e.g. listOf(ToolParameter("city", "string", "city name"))
     * Empty for no-arg tools like get_time().
     */
    val parameters: List<ToolParameter>
        get() = emptyList()

    /** Execute this tool with the provided [args] and return a result string. */
    suspend fun execute(args: Map<String, Any>): String
}

data class ToolParameter(
    val name: String,
    val type: String,          // "string" | "number" | "boolean"
    val description: String,
)
