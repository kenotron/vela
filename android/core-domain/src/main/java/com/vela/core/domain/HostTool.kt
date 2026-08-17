package com.vela.core.domain

/**
 * Domain interface for a host-side tool the assistant can invoke (calendar, email,
 * device actions, etc.). Implemented by lane 1.3. This lane (1.1) defines the
 * contract only.
 */
interface HostTool {

    /** Stable machine name, e.g. "calendar.create_event". */
    val name: String

    /** Human-readable description surfaced to the model / UI. */
    val description: String

    /** JSON-schema-shaped description of expected input, as a raw JSON string. */
    val inputSchema: String

    /** Execute the tool with the given JSON-encoded arguments. */
    suspend fun execute(argsJson: String): ToolResult

    sealed interface ToolResult {
        data class Success(val resultJson: String) : ToolResult
        data class Failure(val message: String, val cause: Throwable? = null) : ToolResult
        data class NeedsConfirmation(val promptText: String, val confirmationToken: String) : ToolResult
    }
}

/** Registry contract for looking up available host tools by name. */
interface HostToolRegistry {
    fun all(): List<HostTool>
    fun find(name: String): HostTool?
}
