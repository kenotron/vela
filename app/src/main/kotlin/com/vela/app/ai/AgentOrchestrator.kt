    package com.vela.app.ai

    import com.vela.app.ai.tools.ToolCallParser
    import com.vela.app.ai.tools.ToolRegistry
    import javax.inject.Inject
    import javax.inject.Singleton

    /**
     * Drives the agentic loop:
     *   build prompt → stream LLM → parse tool call → execute tool → append result → repeat
     *
     * Pure Kotlin — zero Android deps (no Context, ViewModel, StateFlow).
     * Injected into ConversationViewModel via Hilt.
     *
     * UI callbacks (onTokenChunk, onToolStart, etc.) let the ViewModel update StateFlows
     * without this class knowing anything about Android.
     */
    @Singleton
    class AgentOrchestrator @Inject constructor(
        private val providerRegistry: ProviderRegistry,
        private val toolRegistry: ToolRegistry,
    ) {
        companion object {
            /**
             * Maximum tool-call steps per user turn.
             * Each step adds ~600-800 tokens; 4 steps fits within Gemma's context window
             * and gives the model room to produce a final answer.
             */
            const val MAX_STEPS = 4
        }

        /**
         * Run the full agentic loop for [userInput]. Returns the model's final text response.
         *
         * @param userInput     The user's raw input text.
         * @param onTokenChunk  Called with each streaming token delta (for live UI update).
         * @param onToolStart   Called with the tool name when a tool starts executing.
         * @param onToolEnd     Called when tool execution finishes.
         * @param onStepChange  Called with (currentStep, maxSteps) at the start of each iteration.
         */
        suspend fun runLoop(
            userInput: String,
            onTokenChunk: suspend (String) -> Unit = {},
            onToolStart: suspend (String) -> Unit = {},
            onToolEnd: suspend () -> Unit = {},
            onStepChange: suspend (current: Int, max: Int) -> Unit = { _, _ -> },
        ): String {
            val provider = providerRegistry.current()
            var prompt = PromptBuilder.buildWithTools(userInput, toolRegistry.all())
            var lastResponse = ""

            for (step in 1..MAX_STEPS) {
                onStepChange(step, MAX_STEPS)
                lastResponse = streamAndCollect(provider, prompt, onTokenChunk)

                val toolCall = ToolCallParser.parse(lastResponse)
                if (toolCall == null || !toolRegistry.contains(toolCall.toolName)) {
                    // No valid tool call — model has produced its final answer.
                    return lastResponse
                }

                onToolStart(toolCall.toolName)
                val toolResult = try {
                    toolRegistry.execute(toolCall.toolName, toolCall.args)
                } catch (e: Exception) {
                    "Error running \${toolCall.toolName}: \${e.message?.take(120)}"
                }
                onToolEnd()

                // Append the completed tool exchange so the next inference sees it.
                prompt = appendExchange(prompt, lastResponse, toolResult)
            }

            // Hit the step limit — do one final inference and return whatever the model says.
            return streamAndCollect(provider, prompt, onTokenChunk)
        }

        private suspend fun streamAndCollect(
            provider: InferenceProvider,
            prompt: String,
            onChunk: suspend (String) -> Unit,
        ): String {
            val sb = StringBuilder()
            provider.streamText(prompt).collect { chunk ->
                sb.append(chunk)
                onChunk(chunk)
            }
            return sb.toString().trim()
        }

        /**
         * Append a completed tool-call exchange to the growing prompt.
         * Strips the trailing "Vela:" role prefix before appending to avoid duplication.
         */
        private fun appendExchange(prompt: String, toolCallJson: String, toolResult: String): String {
            val base = prompt.trimEnd().removeSuffix("Vela:").trimEnd()
            return "\$base\nVela: \$toolCallJson\nTool result: \$toolResult\nVela:"
        }
    }
    