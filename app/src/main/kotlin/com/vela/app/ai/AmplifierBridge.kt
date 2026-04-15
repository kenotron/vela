package com.vela.app.ai

/**
 * JNI bridge to the amplifier-android Rust crate (libamplifier_android.so).
 *
 * The Rust side implements the agent loop using amplifier-core types:
 *  - AnthropicProvider — HTTP to api.anthropic.com/v1/messages
 *  - SimpleOrchestrator — tool-calling loop (≤10 steps)
 *  - SimpleContext — in-memory message history
 *
 * Tool calls are delegated back to Kotlin via [ToolCallback] so Android-specific
 * tools (battery, contacts, web search via OkHttp, etc.) stay in Kotlin.
 */
object AmplifierBridge {

    init { System.loadLibrary("amplifier_android") }

    /**
     * Run one user turn through the full agent loop.
     *
     * @param apiKey      Anthropic API key.
     * @param model       Model name, e.g. "claude-3-5-haiku-20241022".
     * @param toolsJson   JSON array of OpenAI-format tool schemas
     *                    [{"type":"function","function":{"name":...,"description":...,"parameters":{...}}}]
     * @param historyJson JSON array of prior messages in Anthropic format
     *                    [{"role":"user","content":"..."},{"role":"assistant","content":"..."},...]
     * @param userInput   The user's current message.
     * @param systemPrompt System prompt text, or empty string for none.
     * @param tokenCb     Called with each text chunk as it arrives (streaming UX).
     * @param toolCb      Called when the model invokes a tool; must return the tool result string.
     * @return            Final assistant response text.
     */
    external fun nativeRun(
        apiKey:          String,
        model:           String,
        toolsJson:       String,
        historyJson:     String,
        userInput:       String,
        userContentJson: String?,          // null = plain text; non-null = content blocks JSON array
        systemPrompt:    String,
        tokenCb:         TokenCallback,
        toolCb:          ToolCallback,
    ): String

    /** Per-token streaming callback — called from the Rust decode loop. */
    fun interface TokenCallback {
        fun onToken(token: String)
    }

    /**
     * Tool execution callback — called when the model issues a tool call.
     *
     * @param name     Tool name (e.g. "search_web")
     * @param argsJson JSON object of arguments (e.g. {"query":"AI news"})
     * @return         Tool result string passed back to the model.
     */
    fun interface ToolCallback {
        fun executeTool(name: String, argsJson: String): String
    }
}
