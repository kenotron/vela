    package com.vela.app.data.repository

    import com.vela.app.domain.model.Message
    import kotlinx.coroutines.flow.Flow

    interface ConversationRepository {
        fun getMessages(): Flow<List<Message>>
        suspend fun saveMessage(message: Message)
        suspend fun updateToolMeta(id: String, toolMeta: String)
    }
    