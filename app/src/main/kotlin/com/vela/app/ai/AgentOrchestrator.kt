    package com.vela.app.ai

    import com.vela.app.ai.tools.ToolRegistry
    import javax.inject.Inject
    import javax.inject.Singleton

    /**
     * Drives the agentic loop using a proper multi-turn message list.
     *
     * Each call maintains [ChatMessage] history so the model always sees
     * full conversation context. Tool calls use Gemma 4's native
     * <|tool_call>call:name{args}<tool_call|> format (with JSON-in-prompt fallback
     * for ML Kit), detected by [GemmaToolCallParser].
     *
     * Pure Kotlin — zero Android deps. Injected into ConversationViewModel.
     */
    @Singleton
    class AgentOrchestrator @Inject constructor(
        private val providerRegistry: ProviderRegistry,
        private val toolRegistry: ToolRegistry,
    ) {
        companion object {
            const val MAX_STEPS = 4
            private const val SYSTEM_PROMPT =
                "You are Vela, a helpful on-device AI assistant. " +
                "Be concise. Use tools when live data is needed. " +
                "For structured answers you MAY output vela-ui JSON."
        }

        suspend fun runLoop(
            userInput: String,
            onTokenChunk: suspend (String) -> Unit = {},
            onToolStart: suspend (String) -> Unit = {},
            onToolEnd: suspend () -> Unit = {},
            onStepChange: suspend (Int, Int) -> Unit = { _, _ -> },
        ): String {
            val provider = providerRegistry.current()
            val tools = toolRegistry.all()

            // Build the ongoing message list for this turn
            val messages = mutableListOf(
                ChatMessage(role = "system", content = SYSTEM_PROMPT),
                ChatMessage(role = "user", content = userInput),
            )

            var lastResponse = ""

            for (step in 1..MAX_STEPS) {
                onStepChange(step, MAX_STEPS)

                // Stream next model response
                val sb = StringBuilder()
                provider.complete(messages, tools).collect { chunk ->
                    sb.append(chunk)
                    onTokenChunk(chunk)
                }
                lastResponse = sb.toString().trim()

                // Detect tool call (Gemma native <|tool_call> or JSON fallback)
                val toolCall = GemmaToolCallParser.parse(lastResponse)
                if (toolCall == null || !toolRegistry.contains(toolCall.toolName)) {
                    // No valid tool call — final answer
                    return lastResponse
                }

                onToolStart(toolCall.toolName)

                val toolResult = try {
                    toolRegistry.execute(toolCall.toolName, toolCall.args)
                } catch (e: Exception) {
                    "Error running ${toolCall.toolName}: ${e.message?.take(120)}"
                }

                onToolEnd()

                // Append the assistant turn (containing the tool call) and the tool result
                messages += ChatMessage(role = "assistant", content = lastResponse)
                messages += ChatMessage(
                    role = "tool",
                    content = toolResult,
                    toolName = toolCall.toolName,
                )
            }

            // Hit step limit — one final pass
            val sb = StringBuilder()
            provider.complete(messages, tools).collect { chunk ->
                sb.append(chunk)
                onTokenChunk(chunk)
            }
            return sb.toString().trim()
        }
    }
    