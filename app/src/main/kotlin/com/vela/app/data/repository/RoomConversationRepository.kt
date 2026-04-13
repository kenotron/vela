    package com.vela.app.data.repository

    import com.vela.app.data.db.MessageDao
    import com.vela.app.data.db.MessageEntity
    import com.vela.app.domain.model.Message
    import com.vela.app.domain.model.MessageRole
    import kotlinx.coroutines.flow.Flow
    import kotlinx.coroutines.flow.map
    import javax.inject.Inject

    class RoomConversationRepository @Inject constructor(
        private val messageDao: MessageDao,
    ) : ConversationRepository {

        override fun getMessages(): Flow<List<Message>> =
            messageDao.getAllMessages().map { entities ->
                entities.map { e ->
                    Message(
                        id       = e.id,
                        role     = MessageRole.entries.firstOrNull { it.name == e.role } ?: MessageRole.USER,
                        content  = e.content,
                        timestamp = e.timestamp,
                        toolMeta = e.toolMeta,
                    )
                }
            }

        override suspend fun saveMessage(message: Message) {
            messageDao.insertMessage(
                MessageEntity(
                    id        = message.id,
                    role      = message.role.name,
                    content   = message.content,
                    timestamp = message.timestamp,
                    toolMeta  = message.toolMeta,
                )
            )
        }

        override suspend fun updateToolMeta(id: String, toolMeta: String) {
            messageDao.updateToolMeta(id, toolMeta)
        }
    }
    