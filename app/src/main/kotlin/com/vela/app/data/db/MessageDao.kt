    package com.vela.app.data.db

    import androidx.room.Dao
    import androidx.room.Insert
    import androidx.room.OnConflictStrategy
    import androidx.room.Query
    import kotlinx.coroutines.flow.Flow

    @Dao
    interface MessageDao {
        @Query("SELECT * FROM messages ORDER BY timestamp ASC")
        fun getAllMessages(): Flow<List<MessageEntity>>

        @Insert(onConflict = OnConflictStrategy.REPLACE)
        suspend fun insertMessage(message: MessageEntity)

        /** Update toolMeta in-place — used to flip TOOL_CALL from in_progress → done. */
        @Query("UPDATE messages SET toolMeta = :toolMeta WHERE id = :id")
        suspend fun updateToolMeta(id: String, toolMeta: String)

        @Query("DELETE FROM messages")
        suspend fun deleteAll()
    }
    