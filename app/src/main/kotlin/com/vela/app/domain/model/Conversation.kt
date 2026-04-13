    package com.vela.app.domain.model

    import java.util.UUID

    data class Conversation(
        val id: String = UUID.randomUUID().toString(),
        val title: String = "New Chat",
        val createdAt: Long = System.currentTimeMillis(),
        val updatedAt: Long = System.currentTimeMillis(),
    )
    