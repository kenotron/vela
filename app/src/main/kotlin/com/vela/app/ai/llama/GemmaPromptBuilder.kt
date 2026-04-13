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
     * Structure:
     *   <bos><|turn>system
     *   {system content}
     *   <|tool>declaration:name{description:"...",parameters:{...}}<tool|>
     *   <turn|>
     *   <|turn>user
     *   {user content}
     *   <turn|>
     *   <|turn>model
     *   <|channel>thought
     *   <channel|>{assistant content}<turn|>
     *   ...
     *   <|turn>model
     *   <|channel>thought
     *   <channel|>    <- generation continues here
     *
     * Tool exchanges: the assistant message content contains the raw
     * <|tool_call>call:name{args}<tool_call|> token the model emitted.
     * The subsequent "tool" role message is inlined as a <|tool_response> block.
     */
    object GemmaPromptBuilder {

        fun build(messages: List<ChatMessage>, tools: List<Tool>): String {
            val sb = StringBuilder()

            // BOS token — Gemma 4 template starts with this
            sb.append("<bos>")

            // ── System turn (always emitted when tools are present) ──────────────
            sb.append("<|turn>system
")
            val sysContent = messages.find { it.role == "system" }?.content ?: ""
            if (sysContent.isNotBlank()) sb.append(sysContent.trim())

            // Tool definitions in Gemma's native declaration format
            tools.forEach { tool ->
                sb.append("
")
                sb.append(formatToolDeclaration(tool))
            }
            sb.append("
<turn|>
")

            // ── Conversation turns ────────────────────────────────────────────────
            val nonSystem = messages.filter { it.role != "system" }
            var i = 0
            while (i < nonSystem.size) {
                val msg = nonSystem[i]
                when (msg.role) {
                    "user" -> {
                        sb.append("<|turn>user
${msg.content.trim()}
<turn|>
")
                    }
                    "assistant" -> {
                        sb.append("<|turn>model
<|channel>thought
<channel|>")
                        sb.append(msg.content.trim())
                        // Look ahead: inline tool response immediately after tool call
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
                        sb.append("<turn|>
")
                    }
                    "tool" -> {
                        // Normally consumed by preceding assistant look-ahead.
                        // Orphaned tool messages are silently skipped.
                    }
                }
                i++
            }

            // ── Generation prompt ─────────────────────────────────────────────────
            // <|channel>thought\n<channel|> tells Gemma 4 to skip the thinking
            // channel and respond directly (enable_thinking=false path).
            sb.append("<|turn>model
<|channel>thought
<channel|>")

            return sb.toString()
        }

        /**
         * Format one tool in Gemma 4's declaration syntax:
         *   <|tool>declaration:name{description:"...",parameters:{...}}<tool|>
         */
        private fun formatToolDeclaration(tool: Tool): String {
            val sb = StringBuilder("<|tool>declaration:${tool.name}{")
            sb.append("description:"${tool.description.replace(""", "'")}"")

            if (tool.parameters.isNotEmpty()) {
                sb.append(",parameters:{properties:{")
                tool.parameters.forEachIndexed { idx, param ->
                    if (idx > 0) sb.append(",")
                    sb.append("${param.name}:{")
                    sb.append("description:"${param.description.replace(""", "'")}",")
                    sb.append("type:"${param.type.uppercase()}"}")
                }
                sb.append("},required:[")
                sb.append(tool.parameters.joinToString(",") { ""${it.name}"" })
                sb.append("],type:"OBJECT"}")
            }

            sb.append("}<tool|>")
            return sb.toString()
        }
    }
    