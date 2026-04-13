    package com.vela.app.ai

    import com.vela.app.ai.tools.Tool
    import kotlinx.coroutines.flow.Flow

    /**
     * Unified inference abstraction over any model backend.
     *
     * Accepts a full multi-turn [messages] list and available [tools].
     * Each provider converts these into its own native format:
     *  - MlKitInferenceProvider: flattens to a prompt string, JSON-in-prompt tools
     *  - LlamaCppProvider: builds Gemma 4 native prompt with <|tool> blocks, parses
     *    <|tool_call> tags from the response
     */
    interface InferenceProvider {
        val name: String
        suspend fun isAvailable(): Boolean

        /**
         * Stream a completion. Each emitted String is a raw token chunk.
         * Caller accumulates into a full response, then passes to
         * [GemmaToolCallParser.parse] to detect native tool calls.
         */
        fun complete(messages: List<ChatMessage>, tools: List<Tool>): Flow<String>

        fun shutdown() {}
    }
    