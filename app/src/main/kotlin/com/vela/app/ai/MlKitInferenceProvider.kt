    package com.vela.app.ai

    import com.vela.app.ai.tools.Tool
    import kotlinx.coroutines.flow.Flow
    import javax.inject.Inject
    import javax.inject.Singleton

    /**
     * [InferenceProvider] backed by ML Kit Gemma 4 via Android AICore.
     *
     * ML Kit doesn't support native tool calling or multi-turn messages, so this
     * provider flattens the [messages] list into a plain-text prompt using
     * JSON-in-prompt tool format (the original approach). [GemmaToolCallParser]
     * catches both native <|tool_call> and JSON fallback formats so the orchestrator
     * handles both providers uniformly.
     */
    @Singleton
    class MlKitInferenceProvider @Inject constructor(
        private val engine: MlKitGemma4Engine,
    ) : InferenceProvider {

        override val name = "mlkit-gemma4"

        override suspend fun isAvailable(): Boolean =
            engine.checkReadiness() == ReadinessState.Available

        override fun complete(messages: List<ChatMessage>, tools: List<Tool>): Flow<String> {
            val prompt = buildPrompt(messages, tools)
            return engine.streamText(prompt)
        }

        override fun shutdown() = engine.shutdown()

        /** Convert message list + tools → flat prompt for ML Kit (JSON-in-prompt). */
        private fun buildPrompt(messages: List<ChatMessage>, tools: List<Tool>): String {
            val sb = StringBuilder()

            // Tool block
            if (tools.isNotEmpty()) {
                sb.append("[Vela: on-device AI assistant.
")
                sb.append("Tools (use when the question requires live data):
")
                tools.forEach { tool ->
                    val params = if (tool.parameters.isEmpty()) "()"
                    else "(${tool.parameters.joinToString(", ") { "${it.name}: ${it.type}" }})"
                    sb.append("  ${tool.name}$params -> ${tool.description}
")
                }
                sb.append("To call a tool output ONLY: {"tool":"name","args":{}}
")
                sb.append("Do NOT use tools for questions you can answer directly.]

")
            } else {
                sb.append("[Vela: on-device AI assistant. Respond concisely.]

")
            }

            // Conversation turns (skip system — already in the header above)
            messages.filter { it.role != "system" }.forEach { msg ->
                when (msg.role) {
                    "user"      -> sb.append("User: ${msg.content.take(800)}
")
                    "assistant" -> sb.append("Vela: ${msg.content}
")
                    "tool"      -> sb.append("Tool result: ${msg.content}
")
                }
            }

            sb.append("Vela:")
            return sb.toString()
        }
    }
    