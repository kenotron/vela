package com.vela.ledger.server

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.vela.core.domain.LedgerRepository.Decision as DomainDecision
import com.vela.core.domain.LedgerRepository.Status
import com.vela.ledger.JobRecord
import com.vela.ledger.JobStatus
import com.vela.ledger.LedgerDatabase
import com.vela.ledger.SqliteLedgerRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Exercises [ServerLedgerRepository] against a real HTTP server (MockWebServer standing
 * in for `services/ledger/`, per the goal file's "fake-server first" instruction) --
 * these are the tests satisfying #30, #37, and #38's Android-side acceptance criteria:
 *
 *  - `attention query is served from GET slash ledger slash attention` -> #30
 *  - `offline reads fall back to the mirror` / `reconcile pulls new jobs into the mirror` -> #37
 *  - `test_cross_layer_zero_lost_events` -> #38
 */
@RunWith(RobolectricTestRunner::class)
class ServerLedgerRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var db: LedgerDatabase
    private lateinit var repo: ServerLedgerRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LedgerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = ServerLedgerRepository(
            api = LedgerApiClient(baseUrl = server.url("/").toString().trimEnd('/')),
            mirror = SqliteLedgerRepository(db.jobDao()),
            outbox = db.decisionOutboxDao(),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
    }

    private fun wireJob(
        id: String,
        status: JobStatus = JobStatus.ACCEPTED,
        attentionRequired: Boolean = false,
        createdAt: Long = 1_000L,
    ): String = JSONObject().apply {
        put("job_id", id)
        put("created_at", createdAt)
        put("updated_at", createdAt)
        put("origin", JSONObject().apply { put("session_id", "s"); put("turn_id", "t"); put("tool_call_id", "c-$id") })
        put("spec", JSONObject())
        put("status", status.wireValue())
        put(
            "attention",
            JSONObject().apply {
                put("required", attentionRequired)
                put("reason", if (attentionRequired) "needs decision" else JSONObject.NULL)
                put("options", JSONArray())
                put("deadline", JSONObject.NULL)
            },
        )
        put("progress", JSONArray())
        put("result", JSONObject.NULL)
        put("cost", JSONObject().apply { put("usd", JSONObject.NULL); put("tokens", JSONObject.NULL) })
    }.toString()

    // --- #30: attention query backing the deck ---

    @Test
    fun `attention query is served from GET ledger attention`() = runTest {
        server.enqueue(MockResponse().setBody("[${wireJob("job-1", attentionRequired = true)}]"))

        val result = repo.refreshAttentionQueue()

        assertEquals(listOf("job-1"), result.map { it.jobId })
        assertEquals(true, result.first().attention.required)
        assertEquals("/ledger/attention", server.takeRequest().path)
    }

    @Test
    fun `attention query falls back to the mirror when the server is unreachable`() = runTest {
        // Prime the mirror via a successful reconcile first...
        server.enqueue(MockResponse().setBody("true")) // healthz
        server.enqueue(MockResponse().setBody("[${wireJob("job-2", attentionRequired = true)}]")) // listJobs
        repo.reconcile()

        // ...then simulate the server going away for the attention query itself.
        server.enqueue(MockResponse().setResponseCode(500))
        val result = repo.refreshAttentionQueue()

        assertEquals(listOf("job-2"), result.map { it.jobId })
    }

    // --- #37: phone-local mirror for offline read ---

    @Test
    fun `reconcile pulls new jobs into the mirror and clears reachability flag on success`() = runTest {
        server.enqueue(MockResponse().setBody("true"))
        server.enqueue(MockResponse().setBody("[${wireJob("job-3")}, ${wireJob("job-4")}]"))

        val pulled = repo.reconcile()

        assertEquals(2, pulled)
        val mirrored = repo.observeEntries().first()
        assertEquals(setOf("job-3", "job-4"), mirrored.map { it.id }.toSet())
        assertEquals(true, repo.isServerReachable.first())
    }

    @Test
    fun `get falls back to the mirror and reports unreachable when the server errors`() = runTest {
        server.enqueue(MockResponse().setBody("true"))
        server.enqueue(MockResponse().setBody("[${wireJob("job-5")}]"))
        repo.reconcile()

        server.enqueue(MockResponse().setResponseCode(503))
        val entry = repo.get("job-5")

        assertEquals("job-5", entry?.id)
        assertEquals(false, repo.isServerReachable.first())
    }

    // --- #38: zero-lost-events, offline decision durability ---

    @Test
    fun `a decision made while offline is durably queued, not lost, and flushes on reconnect`() = runTest {
        // Seed the mirror with a job the decision will target.
        server.enqueue(MockResponse().setBody("true"))
        server.enqueue(MockResponse().setBody("[${wireJob("job-6", status = JobStatus.NEEDS_ATTENTION, attentionRequired = true)}]"))
        repo.reconcile()

        // Server unreachable for the decision itself -> must be durably queued, not thrown away.
        server.enqueue(MockResponse().setResponseCode(500))
        repo.recordDecision("job-6", DomainDecision(status = Status.ACCEPTED, decidedAtEpochMs = 2_000L))

        assertEquals(1, db.decisionOutboxDao().all().size)

        // Simulate a fresh process (new repository instance over the SAME durable db) --
        // the queued decision must still be there, proving it was not held only in memory.
        val revivedRepo = ServerLedgerRepository(
            api = LedgerApiClient(baseUrl = server.url("/").toString().trimEnd('/')),
            mirror = SqliteLedgerRepository(db.jobDao()),
            outbox = db.decisionOutboxDao(),
        )
        assertEquals(1, db.decisionOutboxDao().all().size)

        // Reconnect: flush the outbox against the now-healthy server.
        server.enqueue(MockResponse().setBody(wireJob("job-6", status = JobStatus.DONE)))
        revivedRepo.flushOutbox()

        assertEquals(0, db.decisionOutboxDao().all().size)
        val finalJob = db.jobDao().getById("job-6")
        assertEquals("done", finalJob?.status)
    }

    @Test
    fun `server-wins conflict - a stale queued decision against an already-terminal job converges without loss`() = runTest {
        server.enqueue(MockResponse().setBody("true"))
        server.enqueue(MockResponse().setBody("[${wireJob("job-7", status = JobStatus.NEEDS_ATTENTION, attentionRequired = true)}]"))
        repo.reconcile()

        // The decision POST comes back 409 (server already resolved this job, e.g. it timed out).
        server.enqueue(MockResponse().setResponseCode(409))
        // The follow-up GET (server-wins re-fetch) returns the server's true terminal state.
        server.enqueue(MockResponse().setBody(wireJob("job-7", status = JobStatus.FAILED)))

        repo.recordDecision("job-7", DomainDecision(status = Status.ACCEPTED, decidedAtEpochMs = 3_000L))

        // No outbox entry left behind -- the conflict was resolved immediately, not queued.
        assertEquals(0, db.decisionOutboxDao().all().size)
        val finalJob = db.jobDao().getById("job-7")
        assertEquals("failed", finalJob?.status)
    }

    /**
     * `test_cross_layer_zero_lost_events` (design doc §6.3, goal item #38): drives a
     * create -> attention -> offline-decision -> reconnect sequence through a real
     * [ServerLedgerRepository] against a real HTTP server, kills the in-memory repository
     * mid-sequence (simulating an app process death per §6.2's second failure mode) and
     * asserts the client's final observable state exactly matches what the server would
     * report -- no job silently vanishes and no decision is silently lost.
     */
    @Test
    fun `test_cross_layer_zero_lost_events`() = runTest {
        // 1. Job created (mirrors dispatch_to_fleet, then reaches needs_attention).
        server.enqueue(MockResponse().setBody("true"))
        server.enqueue(MockResponse().setBody("[${wireJob("job-e2e", status = JobStatus.NEEDS_ATTENTION, attentionRequired = true)}]"))
        repo.reconcile()
        val attentionBefore = repo.observeAttentionQueue().first()
        assertEquals(listOf("job-e2e"), attentionBefore.map { it.jobId })

        // 2. Phone attempts a decision while the network to the ledger is down --
        //    must be durably queued (§6.2's "app killed mid-decision-POST" scenario).
        server.enqueue(MockResponse().setResponseCode(500))
        repo.recordDecision("job-e2e", DomainDecision(status = Status.ACCEPTED, decidedAtEpochMs = 4_000L))
        assertEquals(1, db.decisionOutboxDao().all().size)

        // 3. Simulate process death: drop the in-memory repository entirely, rebuild it
        //    from nothing but the durable database (the actual failure mode §6.2 names).
        val afterRestartRepo = ServerLedgerRepository(
            api = LedgerApiClient(baseUrl = server.url("/").toString().trimEnd('/')),
            mirror = SqliteLedgerRepository(db.jobDao()),
            outbox = db.decisionOutboxDao(),
        )

        // 4. Reconnect: full reconciliation must recover the job's presence AND the
        //    queued decision must flush cleanly against the (now healthy) server.
        server.enqueue(MockResponse().setBody("true")) // healthz for reconcile()
        server.enqueue(MockResponse().setBody("[${wireJob("job-e2e", status = JobStatus.NEEDS_ATTENTION, attentionRequired = true)}]")) // listJobs
        server.enqueue(MockResponse().setBody(wireJob("job-e2e", status = JobStatus.DONE))) // flushOutbox's decision POST
        val recovered = afterRestartRepo.reconcile()

        // 5. Final assertion: exactly matches what the server itself would report --
        //    the job exists, its decision was not lost, and the outbox is drained.
        assertEquals(1, recovered)
        assertTrue(db.decisionOutboxDao().all().isEmpty())
        val finalState = db.jobDao().getById("job-e2e")
        assertEquals("done", finalState?.status)
        assertEquals(true, afterRestartRepo.isServerReachable.first())
    }
}
