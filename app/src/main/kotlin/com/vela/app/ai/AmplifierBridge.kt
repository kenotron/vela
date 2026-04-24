package com.vela.app.ai

/**
 * JNI bridge to the amplifier-android Rust crate (libamplifier_android.so).
 *
 * The Rust side implements the agent loop:
 *  - AnthropicProvider — HTTP to api.anthropic.com/v1/messages
 *  - SimpleOrchestrator — tool-calling loop (≤10 steps) with hook registry
 *  - SimpleContext — in-memory message history
 *
 * Tool calls and hook callbacks delegate back to Kotlin so Android-specific
 * logic stays in Kotlin.
 *
 * Hooks are registered via [HookRegistration] entries in the [nativeRun]
 * [hookCallbacks] parameter. Each registration declares which events it
 * handles and provides a [HookCallback] to invoke.
 */
object AmplifierBridge {

    init { System.loadLibrary("amplifier_android") }

    external fun nativeRun(
        apiKey:          String,
        model:           String,
        toolsJson:       String,
        historyJson:     String,
        userInput:       String,
        userContentJson: String?,           // null = plain text; non-null = content blocks JSON
        systemPrompt:    String,
        tokenCb:         TokenCallback,
        toolCb:          ToolCallback,
        hookCallbacks:   Array<HookRegistration>, // hook registry — provider_request, etc.
        serverToolCb:    ServerToolCallback,       // called when server tool executes
    ): String

    /** Per-token streaming callback — called from the Rust decode loop. */
    fun interface TokenCallback {
        fun onToken(token: String)
    }

    /**
     * Tool execution callback.
     *
     * @param name     Tool name (e.g. "search_web")
     * @param argsJson JSON object of arguments
     * @return         Tool result string passed back to the model
     */
    fun interface ToolCallback {
        fun executeTool(name: String, argsJson: String): String
    }

    /**
     * Server tool callback — called when Anthropic server tools (e.g. web_search_20250305)
     * execute. UI display only — no local execution needed.
     *
     * @param name     Tool name, e.g. "web_search"
     * @param argsJson JSON object of arguments, e.g. {"query":"..."}
     */
    fun interface ServerToolCallback {
        fun onServerTool(name: String, argsJson: String)
    }

    /**
     * Hook callback — invoked by the Rust orchestrator when a registered hook event fires.
     *
     * @return JSON string: {"action":"continue"|"deny"|"inject_context","context_injection":"..."}
     *         null or empty string is treated as {"action":"continue"}.
     */
    fun interface HookCallback {
        fun handleHook(event: String, contextJson: String): String
    }

    /**
     * Declares which events a [HookCallback] handles.
     *
     * Array-safe [equals] and [hashCode] are implemented explicitly because
     * the Kotlin data class compiler does not generate structural equality
     * for [Array] fields.
     */
    data class HookRegistration(val events: Array<String>, val callback: HookCallback) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is HookRegistration) return false
            return events.contentEquals(other.events) && callback == other.callback
        }
        override fun hashCode(): Int {
            var result = events.contentHashCode()
            result = 31 * result + callback.hashCode()
            return result
        }
    }
}
