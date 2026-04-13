    package com.vela.app.data.repository

    import com.vela.app.domain.model.Conversation
    import com.vela.app.domain.model.Message
    import kotlinx.coroutines.flow.Flow

    interface ConversationRepository {
        // ── Sessions ─────────────────────────────────────────────────────────────
        fun getAllConversations(): Flow<List<Conversation>>
        suspend fun createConversation(conversation: Conversation): Conversation
        suspend fun updateConversationTitle(id: String, title: String)
        suspend fun touchConversation(id: String)

        // ── Messages ──────────────────────────────────────────────────────────────
        fun getMessages(conversationId: String): Flow<List<Message>>
        suspend fun saveMessage(message: Message)
        suspend fun updateToolMeta(id: String, toolMeta: String)
        suspend fun deleteConversation(id: String)
    }
    