package com.vela.app.ai.tools

import javax.inject.Singleton

/**
 * Central registry for all tools available to Gemma 4 via JSON-in-prompt tool calling.
 *
 * Instantiated and populated in AppModule; injected into ConversationViewModel via Hilt.
 * [descriptions] produces the compact text block injected into the prompt by [VelaPromptBuilder].
 * [execute] dispatches a parsed [ToolCallParser.ToolCall] to the right [Tool] implementation.
 */
@Singleton
class ToolRegistry(
    private val tools: List<Tool>,
) {
    private val byName: Map<String, Tool> = tools.associateBy { it.name }

    /** All registered tools — used by [VelaPromptBuilder] to build the tool prompt section. */
    fun all(): List<Tool> = tools

    /**
     * Compact tool description string injected into the Gemma 4 prompt.
     * Kept deliberately short to minimise token usage within the 4000-token limit.
     *
     * Format:
     *   get_time() → Returns the current local time
     *   get_battery() → Returns the device battery level and charging state
     */
    fun descriptions(): String = tools.joinToString("\n") { tool ->
        val params = if (tool.parameters.isEmpty()) {
            "()"
        } else {
            "(${tool.parameters.joinToString(", ") { "${it.name}: ${it.type}" }})"
        }
        "${tool.name}$params → ${tool.description}"
    }

    /**
     * Execute a named tool with [args].
     * @throws IllegalArgumentException if [name] is not registered.
     */
    suspend fun execute(name: String, args: Map<String, Any>): String {
        val tool = byName[name]
            ?: throw IllegalArgumentException("Unknown tool: '$name'. Available: ${byName.keys.joinToString()}")
        return tool.execute(args)
    }

    /** True if [name] is a registered tool — used for quick detection in the ViewModel. */
    fun contains(name: String): Boolean = name in byName
}
