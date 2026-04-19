package com.vela.app.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TurnDao {
    /**
     * Primary query used by the UI — returns every turn WITH its events,
     * ordered by turn.timestamp. Events are joined via @Relation and
     * sorted by seq in [TurnWithEvents.sortedEvents].
     *
     * This is a single reactive Flow: any INSERT or UPDATE to turns or
     * turn_events will re-emit the full list. Compose re-renders only the
     * items whose key changed.
     */
    @Transaction
    @Query("SELECT * FROM turns WHERE conversationId = :convId ORDER BY timestamp ASC")
    fun getTurnsWithEvents(convId: String): Flow<List<TurnWithEvents>>

    /**
     * Completed turns only, for building the Anthropic history context.
     */
    @Transaction
    @Query("SELECT * FROM turns WHERE conversationId = :convId AND status = 'complete' ORDER BY timestamp ASC")
    suspend fun getCompletedTurnsWithEvents(convId: String): List<TurnWithEvents>

    /**
     * Returns the [limit] most recently completed turns across all conversations.
     * Used by [ProfileWorker] to summarise recent session activity for the profile.
     */
    @Transaction
    @Query("SELECT * FROM turns WHERE status = 'complete' ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentCompletedTurns(limit: Int = 30): List<TurnWithEvents>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(turn: TurnEntity)

    @Query("UPDATE turns SET status = :status, error = :error WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, error: String? = null)

    @Query("DELETE FROM turns WHERE conversationId = :convId")
    suspend fun deleteForConversation(convId: String)
}
