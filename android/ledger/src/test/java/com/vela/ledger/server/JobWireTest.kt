package com.vela.ledger.server

import com.vela.ledger.JobRecord
import com.vela.ledger.JobStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Wire schema round-trip against the exact C3 shape documented in design doc §4.2. */
class JobWireTest {

    private fun sampleRecord() = JobRecord(
        jobId = "job-123",
        createdAt = 1_000L,
        updatedAt = 1_000L,
        origin = JobRecord.Origin(sessionId = "s1", turnId = "t1", toolCallId = "tc-e2e"),
        spec = """{"tool":"dispatch_to_fleet"}""",
        status = JobStatus.NEEDS_ATTENTION,
        attention = JobRecord.Attention(
            required = true,
            reason = "confirm send",
            options = listOf("send", "discard"),
            deadline = null,
        ),
        progress = listOf(JobRecord.ProgressEntry(ts = 1_100L, message = "started", percent = 20, source = "fleet")),
        result = null,
        cost = JobRecord.Cost(usd = 0.41, tokens = 88300L),
    )

    @Test
    fun `create request encodes and decodes back to the same record`() {
        val record = sampleRecord()
        val json = JobWire.encodeCreateRequest(record)
        val decoded = JobWire.decodeJob(json)

        assertEquals(record, decoded.record)
    }

    @Test
    fun `version is null when the server omits it (services-ledger not yet extended, §4_3)`() {
        val json = JobWire.encodeCreateRequest(sampleRecord())
        val decoded = JobWire.decodeJob(json)
        assertNull(decoded.version)
    }

    @Test
    fun `version is read when the server does include it`() {
        val json = JobWire.encodeCreateRequest(sampleRecord())
        val withVersion = org.json.JSONObject(json).put("version", 7).toString()
        val decoded = JobWire.decodeJob(withVersion)
        assertEquals(7L, decoded.version)
    }

    @Test
    fun `decodes a full example matching the design doc's exact wire shape`() {
        val json = """
        {
          "job_id": "uuid",
          "created_at": 1756000000000,
          "updated_at": 1756000900000,
          "origin": {"session_id": "s1", "turn_id": "t1", "tool_call_id": "tc-e2e"},
          "spec": {"tool": "dispatch_to_fleet"},
          "status": "needs_attention",
          "attention": {"required": true, "reason": "confirm send", "options": ["send", "discard"], "deadline": null},
          "progress": [{"ts": 1756000012000, "message": "cloned repo, running tests", "percent": 20, "source": "fleet"}],
          "result": {"pr_url": "https://example.com"},
          "cost": {"usd": 0.41, "tokens": 88300}
        }
        """.trimIndent()

        val decoded = JobWire.decodeJob(json)

        assertEquals("uuid", decoded.record.jobId)
        assertEquals(JobStatus.NEEDS_ATTENTION, decoded.record.status)
        assertEquals(true, decoded.record.attention.required)
        assertEquals(listOf("send", "discard"), decoded.record.attention.options)
        assertEquals(1, decoded.record.progress.size)
        assertEquals(0.41, decoded.record.cost.usd)
        assertEquals(88300L, decoded.record.cost.tokens)
    }

    @Test
    fun `decision request encodes new_status and decided_at`() {
        val json = JobWire.encodeDecisionRequest(JobStatus.DONE, 123L)
        val obj = org.json.JSONObject(json)
        assertEquals("done", obj.getString("new_status"))
        assertEquals(123L, obj.getLong("decided_at"))
    }
}
