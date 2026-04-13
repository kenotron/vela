    package com.vela.app.data.db

    import androidx.room.Entity
    import androidx.room.PrimaryKey

    @Entity(tableName = "messages")
    data class MessageEntity(
        @PrimaryKey val id: String,
        val conversationId: String,
        val role: String,
        val content: String,
        val timestamp: Long,
        val toolMeta: String? = null,
    )
    