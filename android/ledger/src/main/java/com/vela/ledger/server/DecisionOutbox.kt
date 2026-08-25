package com.vela.ledger.server

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.vela.ledger.Decision
import com.vela.ledger.JobStatus

/**
 * Durable queue of [Decision]s made while offline (design doc §5.4: "the offline write
 * queue must itself be durably persisted ... or a phone kill during 'queued but not yet
 * sent' is a real, silent loss" -- §6.2). Backed by Room/SQLite (same file-backed database
 * as [com.vela.ledger.JobEntity], so it survives process death exactly as durably.
 *
 * `append()` (job creation) is explicitly NOT queued here -- per design doc §5.4,
 * `dispatch_to_fleet` already requires network reachability for its own reasons, so
 * there is no offline-create case.
 */
@Entity(tableName = "decision_outbox")
data class DecisionOutboxEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "outbox_id") val outboxId: Long = 0,
    @ColumnInfo(name = "job_id") val jobId: String,
    @ColumnInfo(name = "new_status") val newStatus: String,
    @ColumnInfo(name = "decided_at") val decidedAt: Long,
    @ColumnInfo(name = "queued_at") val queuedAt: Long,
)

@Dao
interface DecisionOutboxDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun enqueue(entry: DecisionOutboxEntity): Long

    @Query("SELECT * FROM decision_outbox ORDER BY outbox_id ASC")
    suspend fun all(): List<DecisionOutboxEntity>

    @Delete
    suspend fun remove(entry: DecisionOutboxEntity)
}

fun DecisionOutboxEntity.toDecision(): Decision = Decision(
    newStatus = JobStatus.fromWireValue(newStatus),
    decidedAt = decidedAt,
)
