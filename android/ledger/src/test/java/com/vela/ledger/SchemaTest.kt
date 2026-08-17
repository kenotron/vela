package com.vela.ledger

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Item 1: SQLite schema modeled against the C3 job resource exactly.
 * Creates a job with every field populated and asserts round-trip equality field by field.
 */
@RunWith(RobolectricTestRunner::class)
class SchemaTest {

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
    fun `all C3 job fields round-trip exactly`() = runTest {
        val job = JobRecord(
            jobId = "job-123",
            createdAt = 1_000L,
            updatedAt = 1_000L,
            origin = JobRecord.Origin(
                sessionId = "session-abc",
                turnId = "turn-1",
                toolCallId = "call-xyz",
            ),
            spec = """{"tool":"dispatch_to_fleet","args":{"task":"scan"}}""",
            status = JobStatus.NEEDS_ATTENTION,
            attention = JobRecord.Attention(
                required = true,
                reason = "Ambiguous target host",
                options = listOf("proceed", "abort", "clarify"),
                deadline = 5_000L,
            ),
            progress = listOf(
                JobRecord.ProgressEntry(ts = 1_100L, message = "started", percent = 0, source = "fleet"),
                JobRecord.ProgressEntry(ts = 1_200L, message = "halfway", percent = 50, source = "fleet"),
            ),
            result = null,
            cost = JobRecord.Cost(usd = 0.42, tokens = 1234L),
        )

        repo.createJob(job)
        val readBack = repo.getJob("job-123")

        assertEquals(job, readBack)
    }

    @Test
    fun `nullable fields round-trip as null`() = runTest {
        val job = JobRecord(
            jobId = "job-minimal",
            createdAt = 1L,
            updatedAt = 1L,
            origin = JobRecord.Origin("s", "t", "c"),
            spec = "{}",
            status = JobStatus.ACCEPTED,
            attention = JobRecord.Attention(required = false, reason = null, options = emptyList(), deadline = null),
            progress = emptyList(),
            result = null,
            cost = JobRecord.Cost(usd = null, tokens = null),
        )

        repo.createJob(job)
        val readBack = repo.getJob("job-minimal")

        assertEquals(job, readBack)
    }
}
