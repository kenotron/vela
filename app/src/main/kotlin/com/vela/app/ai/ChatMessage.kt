package com.vela.app.ai

/**
 * A single turn in a multi-turn conversation.
 *
 * Maps to OpenAI Chat Completions message format so the same structure
 * works across both MlKitInferenceProvider (flattened to a prompt) and
 * LlamaCppProvider (passed to the C++ bridge for native Gemma 4 template).
 *
 * @param role      "system" | "user" | "assistant" | "tool"
 * @param content   The message text. For "assistant" messages that contain a
 *                  tool call this is the raw model output (including the
 *                  Gemma 4 <|tool_call>…<tool_call|> tag for replay).
 * @param toolName  For role="tool" — name of the tool that produced this result.
 */
data class ChatMessage(
    val role: String,
    val content: String,
    val toolName: String? = null,
)
