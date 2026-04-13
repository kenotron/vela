package com.vela.app.ai

import com.vela.app.ai.tools.Tool
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [InferenceProvider] backed by ML Kit Gemma 4 via Android AICore.
 *
 * ML Kit has no native tool calling or multi-turn message support, so this
 * provider flattens the [messages] list into a plain-text prompt using the
 * JSON-in-prompt format. [GemmaToolCallParser] handles both Gemma native
 * <|tool_call> tags AND the JSON fallback, so the orchestrator works uniformly
 * across both providers.
 */
@Singleton
class MlKitInferenceProvider @Inject constructor(
    private val engine: MlKitGemma4Engine,
) : InferenceProvider {

    override val name = "mlkit-gemma4"

    override suspend fun isAvailable(): Boolean =
        engine.checkReadiness() == ReadinessState.Available

    override fun complete(messages: List<ChatMessage>, tools: List<Tool>): Flow<String> =
        engine.streamText(buildPrompt(messages, tools))

    override fun shutdown() = engine.shutdown()

    /** Flatten message list + tools into a single ML Kit prompt string. */
    private fun buildPrompt(messages: List<ChatMessage>, tools: List<Tool>): String {
        val sb = StringBuilder()

        if (tools.isNotEmpty()) {
            sb.append("[Vela: on-device AI assistant.\n")
            sb.append("Tools (use when the question requires live data):\n")
            tools.forEach { tool ->
                val params = if (tool.parameters.isEmpty()) "()"
                else "(${tool.parameters.joinToString(", ") { "${it.name}: ${it.type}" }})"
                sb.append("  ${tool.name}$params -> ${tool.description}\n")
            }
            sb.append("To call a tool output ONLY: {\"tool\":\"name\",\"args\":{}}\n")
            sb.append("Do NOT use tools for questions you can answer directly.]\n\n")
        } else {
            sb.append("[Vela: on-device AI assistant. Respond concisely.]\n\n")
        }

        messages.filter { it.role != "system" }.forEach { msg ->
            when (msg.role) {
                "user"      -> sb.append("User: ${msg.content.take(800)}\n")
                "assistant" -> sb.append("Vela: ${msg.content}\n")
                "tool"      -> sb.append("Tool result: ${msg.content}\n")
            }
        }
        sb.append("Vela:")
        return sb.toString()
    }
}
