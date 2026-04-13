    package com.vela.app.domain.model

    import java.util.UUID

    enum class MessageRole { USER, ASSISTANT, TOOL_CALL }

    data class Message(
        val id: String = UUID.randomUUID().toString(),
        val conversationId: String,
        val role: MessageRole,
        val content: String,
        val timestamp: Long = System.currentTimeMillis(),
        val toolMeta: String? = null,
    )
    