package com.vela.app.ai.llama

import com.vela.app.ai.ChatMessage
import com.vela.app.ai.GemmaToolCallParser
import com.vela.app.ai.tools.Tool

/**
 * Builds a Gemma 4 native chat prompt from a structured message list.
 *
 * Matches the Jinja template at:
 *   llama.cpp/models/templates/google-gemma-4-31B-it.jinja
 *
 * Prompt structure:
 *   <bos><|turn>system
 *   {system_content}
 *   <|tool>declaration:name{description:"...",parameters:{...}}<tool|>
 *   <turn|>
 *   <|turn>user
 *   {content}
 *   <turn|>
 *   <|turn>model
 *   <|channel>thought
 *   <channel|>{assistant content}<turn|>
 *   ...
 *   <|turn>model
 *   <|channel>thought
 *   <channel|>   <- generation continues here
 *
 * Tool exchanges: the assistant message content contains the raw
 * <|tool_call>call:name{args}<tool_call|> the model emitted.
 * The subsequent "tool" role message is inlined as <|tool_response>.
 */
object GemmaPromptBuilder {

    fun build(messages: List<ChatMessage>, tools: List<Tool>): String {
        val sb = StringBuilder()

        // BOS token — Gemma 4 template starts with this
        sb.append("<bos>")

        // ── System turn ──────────────────────────────────────────────────────
        sb.append("<|turn>system\n")
        val sysContent = messages.find { it.role == "system" }?.content ?: ""
        if (sysContent.isNotBlank()) sb.append(sysContent.trim())

        // Tool definitions in Gemma 4's native declaration format
        tools.forEach { tool ->
            sb.append("\n")
            sb.append(formatToolDeclaration(tool))
        }
        sb.append("\n<turn|>\n")

        // ── Conversation turns ────────────────────────────────────────────────
        val nonSystem = messages.filter { it.role != "system" }
        var i = 0
        while (i < nonSystem.size) {
            val msg = nonSystem[i]
            when (msg.role) {
                "user" -> {
                    sb.append("<|turn>user\n")
                    sb.append(msg.content.trim())
                    sb.append("\n<turn|>\n")
                }
                "assistant" -> {
                    sb.append("<|turn>model\n<|channel>thought\n<channel|>")
                    sb.append(msg.content.trim())
                    // Look ahead: inline tool response right after tool call
                    val next = nonSystem.getOrNull(i + 1)
                    if (next?.role == "tool") {
                        sb.append(
                            GemmaToolCallParser.formatToolResponse(
                                toolName = next.toolName ?: "tool",
                                result   = next.content,
                            )
                        )
                        i++ // consume the tool message
                    }
                    sb.append("<turn|>\n")
                }
                "tool" -> {
                    // Normally consumed by the assistant look-ahead above.
                    // Orphaned tool messages are skipped.
                }
            }
            i++
        }

        // ── Generation prompt ─────────────────────────────────────────────────
        // <|channel>thought\n<channel|> tells Gemma 4 to skip extended thinking
        // and respond directly (enable_thinking=false path in the template).
        sb.append("<|turn>model\n<|channel>thought\n<channel|>")

        return sb.toString()
    }

    /**
     * Format one tool in Gemma 4's declaration syntax:
     *   <|tool>declaration:name{description:"...",parameters:{...}}<tool|>
     */
    private fun formatToolDeclaration(tool: Tool): String {
        val sb = StringBuilder("<|tool>declaration:${tool.name}{")
        val desc = tool.description.replace("\"", "'")
        sb.append("description:\"$desc\"")

        if (tool.parameters.isNotEmpty()) {
            sb.append(",parameters:{properties:{")
            tool.parameters.forEachIndexed { idx, param ->
                if (idx > 0) sb.append(",")
                val pdesc = param.description.replace("\"", "'")
                sb.append("${param.name}:{description:\"$pdesc\",type:\"${param.type.uppercase()}\"}")
            }
            sb.append("},required:[")
            sb.append(tool.parameters.joinToString(",") { "\"${it.name}\"" })
            sb.append("],type:\"OBJECT\"}")
        }

        sb.append("}<tool|>")
        return sb.toString()
    }
}
