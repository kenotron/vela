package com.vela.ledger

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Item 4: recording a decision updates status and clears attention.required.
 */
@RunWith(RobolectricTestRunner::class)
class DecisionTest {

    private lateinit var db: LedgerDatabase
    private lateinit var repo: SqliteLedgerRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LedgerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = SqliteLedgerRepository(db.jobDao())
    }

    @Test
    fun `decision clears attention and updates status and timestamps`() = runTest {
        val job = JobRecord(
            jobId = "job-decide",
            createdAt = 100L,
            updatedAt = 100L,
            origin = JobRecord.Origin("s", "t", "c"),
            spec = "{}",
            status = JobStatus.NEEDS_ATTENTION,
            attention = JobRecord.Attention(required = true, reason = "pick one", options = listOf("a", "b"), deadline = null),
            progress = emptyList(),
            result = null,
            cost = JobRecord.Cost(null, null),
        )
        repo.createJob(job)

        repo.recordDecision("job-decide", Decision(newStatus = JobStatus.RUNNING, decidedAt = 500L))

        val updated = repo.getJob("job-decide")!!
        assertEquals(JobStatus.RUNNING, updated.status)
        assertFalse(updated.attention.required)
        assertEquals(500L, updated.updatedAt)
        assertTrue(updated.updatedAt > job.updatedAt)
    }

    @Test
    fun `server authoritative version bumps on decision`() = runTest {
        val job = JobRecord(
            jobId = "job-version",
            createdAt = 1L,
            updatedAt = 1L,
            origin = JobRecord.Origin("s", "t", "c"),
            spec = "{}",
            status = JobStatus.NEEDS_ATTENTION,
            attention = JobRecord.Attention(true, "why", emptyList(), null),
            progress = emptyList(),
            result = null,
            cost = JobRecord.Cost(null, null),
        )
        repo.createJob(job)
        val before = db.jobDao().getById("job-version")!!.serverAuthoritativeVersion

        repo.recordDecision("job-version", Decision(JobStatus.DONE, 2L))

        val after = db.jobDao().getById("job-version")!!.serverAuthoritativeVersion
        assertEquals(before + 1, after)
    }
}
