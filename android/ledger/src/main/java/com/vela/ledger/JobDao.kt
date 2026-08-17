package com.vela.ledger

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface JobDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(job: JobEntity)

    @Update
    suspend fun update(job: JobEntity)

    @Query("SELECT * FROM jobs WHERE job_id = :jobId")
    suspend fun getById(jobId: String): JobEntity?

    @Query("SELECT * FROM jobs ORDER BY created_at DESC")
    fun observeAll(): Flow<List<JobEntity>>

    /** Backing query for the card deck (item 3): jobs where attention.required == true. */
    @Query("SELECT * FROM jobs WHERE attention_required = 1 ORDER BY created_at DESC")
    fun observeAttentionRequired(): Flow<List<JobEntity>>

    /** Insert-or-replace in a single transaction, keyed on job_id. */
    @Transaction
    suspend fun upsert(job: JobEntity) {
        val existing = getById(job.jobId)
        if (existing == null) {
            insert(job)
        } else {
            update(job)
        }
    }
}
