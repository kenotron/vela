    package com.vela.app.domain.model

    import java.util.UUID

    enum class MessageRole { USER, ASSISTANT, TOOL_CALL }

    data class Message(
        val id: String = UUID.randomUUID().toString(),
        val role: MessageRole,
        val content: String,
        val timestamp: Long = System.currentTimeMillis(),
        /**
         * JSON metadata for TOOL_CALL messages only.
         * Schema: {"displayName":"Web Search","icon":"🔍","summary":"AI news","status":"in_progress|done|error"}
         */
        val toolMeta: String? = null,
    )
    