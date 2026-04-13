package com.vela.app.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TurnDao {
    @Query("SELECT * FROM turns WHERE conversationId = :convId ORDER BY timestamp ASC")
    fun getTurnsForConversation(convId: String): Flow<List<TurnEntity>>

    @Query("SELECT * FROM turns WHERE conversationId = :convId AND status = 'complete' ORDER BY timestamp ASC")
    suspend fun getCompletedTurns(convId: String): List<TurnEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(turn: TurnEntity)

    @Query("UPDATE turns SET status = :status, error = :error WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, error: String? = null)

    @Query("DELETE FROM turns WHERE conversationId = :convId")
    suspend fun deleteForConversation(convId: String)
}
