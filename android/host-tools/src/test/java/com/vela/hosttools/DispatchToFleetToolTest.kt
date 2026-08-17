package com.vela.hosttools

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Goal file item 4: measure p99 latency of dispatch_to_fleet over >=10 calls
 * against the stub fleet plane, and confirm each call writes a ledger record
 * BEFORE returning a handle (G2), and that the handle is returned (never a
 * blocking result).
 */
class DispatchToFleetToolTest {

    @Test
    fun `dispatch_to_fleet writes ledger record before returning handle`() = runBlocking {
        val ledger = InMemoryLedgerRepository()
        val tool = DispatchToFleetTool(ledger)

        val args = JSONObject().put("title", "test job").put("summary", "does a thing").toString()
        val result = tool.execute(args)

        check(result is com.vela.core.domain.HostTool.ToolResult.Success) {
            "expected Success, got $result"
        }
        val resultJson = JSONObject(result.resultJson)
        assertEquals("accepted", resultJson.getString("status"))
        val jobId = resultJson.getString("job_id")

        val entry = ledger.get(jobId)
        assertTrue("ledger entry must exist for job_id=$jobId", entry != null)
        assertEquals("test job", entry!!.title)
    }

    @Test
    fun `dispatch_to_fleet p99 latency is under 1s over 10+ calls`() = runBlocking {
        val ledger = InMemoryLedgerRepository()
        val tool = DispatchToFleetTool(ledger)
        val callCount = 25
        val latenciesMs = mutableListOf<Long>()

        repeat(callCount) { i ->
            val args = JSONObject().put("title", "job-$i").put("summary", "summary-$i").toString()
            val start = System.nanoTime()
            val result = tool.execute(args)
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            latenciesMs.add(elapsedMs)
            check(result is com.vela.core.domain.HostTool.ToolResult.Success) {
                "call $i failed: $result"
            }
        }

        val sorted = latenciesMs.sorted()
        val p99Index = ((sorted.size * 99) / 100).coerceAtMost(sorted.size - 1)
        val p99 = sorted[p99Index]

        println("dispatch_to_fleet latencies (ms), n=$callCount: $sorted")
        println("dispatch_to_fleet p99 = ${p99}ms")

        assertTrue("p99 latency was ${p99}ms, expected < 1000ms", p99 < 1000)
        assertEquals(callCount, ledger.size())
    }
}
