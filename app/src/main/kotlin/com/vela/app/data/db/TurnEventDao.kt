package com.vela.app.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TurnEventDao {
    @Query("SELECT * FROM turn_events WHERE turnId = :turnId ORDER BY seq ASC")
    fun getEventsForTurn(turnId: String): Flow<List<TurnEventEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: TurnEventEntity)

    /** Update a tool event in-place — same row, same seq, same position in UI. */
    @Query("UPDATE turn_events SET toolStatus = :status, toolResult = :result, text = :text WHERE id = :id")
    suspend fun updateEvent(id: String, status: String? = null, result: String? = null, text: String? = null)
}
