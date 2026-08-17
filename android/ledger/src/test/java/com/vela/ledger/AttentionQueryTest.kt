package com.vela.ledger

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Item 3: the attention query (jobs where attention.required == true) returns exactly
 * and only the correct set — the card deck's backing query.
 */
@RunWith(RobolectricTestRunner::class)
class AttentionQueryTest {

    private lateinit var db: LedgerDatabase
    private lateinit var repo: SqliteLedgerRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LedgerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = SqliteLedgerRepository(db.jobDao())
    }

    private fun job(id: String, required: Boolean) = JobRecord(
        jobId = id,
        createdAt = 1L,
        updatedAt = 1L,
        origin = JobRecord.Origin("s", "t", "c-$id"),
        spec = "{}",
        status = if (required) JobStatus.NEEDS_ATTENTION else JobStatus.RUNNING,
        attention = JobRecord.Attention(required, if (required) "needs decision" else null, emptyList(), null),
        progress = emptyList(),
        result = null,
        cost = JobRecord.Cost(null, null),
    )

    @Test
    fun `returns exactly and only jobs with attention required`() = runTest {
        val required = setOf("job-2", "job-4")
        val allIds = listOf("job-1", "job-2", "job-3", "job-4", "job-5")

        for (id in allIds) {
            repo.createJob(job(id, id in required))
        }

        val queue = repo.observeAttentionQueue().first()
        val queueIds = queue.map { it.jobId }.toSet()

        assertEquals(required, queueIds)
        assertEquals(true, queue.all { it.attention.required })
    }

    @Test
    fun `empty when no jobs require attention`() = runTest {
        repo.createJob(job("job-a", false))
        repo.createJob(job("job-b", false))

        val queue = repo.observeAttentionQueue().first()

        assertEquals(emptyList<JobRecord>(), queue)
    }
}
