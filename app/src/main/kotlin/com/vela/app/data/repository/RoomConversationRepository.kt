    package com.vela.app.data.repository

    import com.vela.app.data.db.ConversationDao
    import com.vela.app.data.db.ConversationEntity
    import com.vela.app.data.db.MessageDao
    import com.vela.app.data.db.MessageEntity
    import com.vela.app.domain.model.Conversation
    import com.vela.app.domain.model.Message
    import com.vela.app.domain.model.MessageRole
    import kotlinx.coroutines.flow.Flow
    import kotlinx.coroutines.flow.map
    import javax.inject.Inject

    class RoomConversationRepository @Inject constructor(
        private val messageDao: MessageDao,
        private val conversationDao: ConversationDao,
    ) : ConversationRepository {

        // ── Sessions ─────────────────────────────────────────────────────────────

        override fun getAllConversations(): Flow<List<Conversation>> =
            conversationDao.getAllConversations().map { list ->
                list.map { it.toDomain() }
            }

        override suspend fun createConversation(conversation: Conversation): Conversation {
            conversationDao.insert(conversation.toEntity())
            return conversation
        }

        override suspend fun updateConversationTitle(id: String, title: String) {
            conversationDao.updateTitle(id, title, System.currentTimeMillis())
        }

        override suspend fun touchConversation(id: String) {
            conversationDao.touch(id, System.currentTimeMillis())
        }

        // ── Messages ──────────────────────────────────────────────────────────────

        override fun getMessages(conversationId: String): Flow<List<Message>> =
            messageDao.getMessagesForConversation(conversationId).map { list ->
                list.map { it.toDomain() }
            }

        override suspend fun saveMessage(message: Message) {
            messageDao.insertMessage(message.toEntity())
        }

        override suspend fun updateToolMeta(id: String, toolMeta: String) {
            messageDao.updateToolMeta(id, toolMeta)
        }

        override suspend fun deleteConversation(id: String) {
            messageDao.deleteForConversation(id)
            conversationDao.delete(id)
        }

        // ── Mappers ───────────────────────────────────────────────────────────────

        private fun ConversationEntity.toDomain() = Conversation(id, title, createdAt, updatedAt)
        private fun Conversation.toEntity() = ConversationEntity(id, title, createdAt, updatedAt)

        private fun MessageEntity.toDomain() = Message(
            id             = id,
            conversationId = conversationId,
            role           = MessageRole.entries.firstOrNull { it.name == role } ?: MessageRole.USER,
            content        = content,
            timestamp      = timestamp,
            toolMeta       = toolMeta,
        )

        private fun Message.toEntity() = MessageEntity(
            id             = id,
            conversationId = conversationId,
            role           = role.name,
            content        = content,
            timestamp      = timestamp,
            toolMeta       = toolMeta,
        )
    }
    