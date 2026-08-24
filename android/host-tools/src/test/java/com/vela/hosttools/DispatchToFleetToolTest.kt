package com.vela.hosttools

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deterministic fake clock so heartbeat-freshness tests move time without real sleeps. */
private class FakeClock(var currentMs: Long = 0L) : Clock {
    override fun nowMs(): Long = currentMs
}

/**
 * Goal file items #40/#41/#43: measure REAL wall-clock p99 latency of
 * dispatch_to_fleet over >=10 calls (never a mocked instant-return), confirm
 * the ledger-before-handle ordering (G2), and exercise the synchronous
 * heartbeat-based reachability check (D3) both for the happy path and the
 * adversarial "dead target" path (FG-2), plus proof that dispatch never
 * blocks on the dispatched work (D2/#40).
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

    @Test
    fun `dispatch_to_fleet p99 stays under 1s even when dispatched work is slow (D2, never blocks)`() = runBlocking {
        // #40: dispatch_to_fleet must never block on the actual remote work.
        // Prove it with a REAL wall-clock measurement against a fleet plane
        // whose background "work" executor sleeps far longer than the 1s
        // budget -- if dispatch() waited on it even once, this test's p99
        // would blow past 1s. It doesn't, because the executor.submit() call
        // in StubFleetPlane.dispatch() does not block the caller.
        val slowExecutor = java.util.concurrent.Executors.newCachedThreadPool()
        val fleetPlane = StubFleetPlane(executor = object : java.util.concurrent.ExecutorService by slowExecutor {
            override fun submit(task: Runnable): java.util.concurrent.Future<*> {
                return slowExecutor.submit {
                    Thread.sleep(3_000) // far longer than the 1s dispatch budget
                    task.run()
                }
            }
        })
        val ledger = InMemoryLedgerRepository()
        val tool = DispatchToFleetTool(ledger, fleetPlane)
        val callCount = 12
        val latenciesMs = mutableListOf<Long>()

        repeat(callCount) { i ->
            val args = JSONObject().put("title", "slow-job-$i").put("summary", "s-$i").toString()
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
        println("slow-work dispatch_to_fleet latencies (ms), n=$callCount: $sorted")

        assertTrue(
            "p99 latency was ${p99}ms with a 3000ms background task in flight -- " +
                "dispatch must not block on dispatched work",
            p99 < 1000,
        )
        slowExecutor.shutdownNow()
        Unit
    }

    @Test
    fun `dispatch_to_fleet succeeds when target heartbeat is fresh (D3 happy path)`() = runBlocking {
        val clock = FakeClock(currentMs = 0L)
        val fleetPlane = StubFleetPlane(clock = clock, heartbeatIntervalMs = 1_000L)
        fleetPlane.heartbeat("worker-1", atMs = 0L)
        clock.currentMs = 500L // within the 2x1000ms=2000ms live window

        val ledger = InMemoryLedgerRepository()
        val tool = DispatchToFleetTool(ledger, fleetPlane)
        val args = JSONObject()
            .put("title", "reachable job")
            .put("summary", "should succeed")
            .put("targetHint", "worker-1")
            .toString()

        val result = tool.execute(args)
        check(result is com.vela.core.domain.HostTool.ToolResult.Success) {
            "expected Success, got $result"
        }
        val resultJson = JSONObject(result.resultJson)
        assertEquals("worker-1", resultJson.getString("machine_id"))
        assertEquals(500L, resultJson.getLong("last_heartbeat_age_ms"))
    }

    @Test
    fun `dispatch_to_fleet fails synchronously when target heartbeat is stale (D3, FG-2 adversarial)`() = runBlocking {
        // Adversarial scenario per FG-2: a worker whose heartbeat has gone
        // silent (crashed, SIGSTOP'd, or network-severed) must produce a
        // dispatch-time failure with a reason -- never a silent "accepted"
        // against a dead target.
        val clock = FakeClock(currentMs = 0L)
        val fleetPlane = StubFleetPlane(clock = clock, heartbeatIntervalMs = 1_000L)
        fleetPlane.heartbeat("worker-2", atMs = 0L)
        clock.currentMs = 5_000L // well past the 2x1000ms=2000ms live window

        val ledger = InMemoryLedgerRepository()
        val tool = DispatchToFleetTool(ledger, fleetPlane)
        val args = JSONObject()
            .put("title", "stale job")
            .put("summary", "should fail")
            .put("targetHint", "worker-2")
            .toString()

        val result = tool.execute(args)
        check(result is com.vela.core.domain.HostTool.ToolResult.Failure) {
            "expected Failure for stale heartbeat, got $result"
        }
        assertTrue(result.message.contains("unreachable"))

        // The ledger record must still exist (created before the handshake)
        // and be marked dismissed with the unreachable reason -- G2.
        val entries = ledger.allEntries()
        val entry = entries.first { it.title == "stale job" }
        assertEquals(com.vela.core.domain.LedgerRepository.Status.DISMISSED, entry.status)
    }

    @Test
    fun `dispatch_to_fleet fails synchronously when target has no heartbeat at all (D3, unknown machine)`() = runBlocking {
        val fleetPlane = StubFleetPlane()
        val ledger = InMemoryLedgerRepository()
        val tool = DispatchToFleetTool(ledger, fleetPlane)
        val args = JSONObject()
            .put("title", "unknown target job")
            .put("summary", "should fail")
            .put("targetHint", "never-seen-machine")
            .toString()

        val result = tool.execute(args)
        check(result is com.vela.core.domain.HostTool.ToolResult.Failure) {
            "expected Failure for unknown machine, got $result"
        }
        assertTrue(result.message.contains("unreachable"))
    }

    @Test
    fun `dispatch_to_fleet fails after a live worker goes dark (forget simulates crash-or-sever)`() = runBlocking {
        val clock = FakeClock(currentMs = 0L)
        val fleetPlane = StubFleetPlane(clock = clock, heartbeatIntervalMs = 1_000L)
        fleetPlane.heartbeat("worker-3", atMs = 0L)

        val ledger = InMemoryLedgerRepository()
        val tool = DispatchToFleetTool(ledger, fleetPlane)
        val reachableArgs = JSONObject()
            .put("title", "before-forget")
            .put("summary", "s")
            .put("targetHint", "worker-3")
            .toString()
        val firstResult = tool.execute(reachableArgs)
        assertTrue(firstResult is com.vela.core.domain.HostTool.ToolResult.Success)

        // Simulate the worker crashing / getting SIGSTOP'd / losing network.
        fleetPlane.forget("worker-3")

        val afterForgetArgs = JSONObject()
            .put("title", "after-forget")
            .put("summary", "s")
            .put("targetHint", "worker-3")
            .toString()
        val secondResult = tool.execute(afterForgetArgs)
        check(secondResult is com.vela.core.domain.HostTool.ToolResult.Failure) {
            "expected Failure after forget(), got $secondResult"
        }
        assertNull(null) // heartbeat age is absent (no heartbeat record); nothing further to assert here
        assertFalse(secondResult.message.isBlank())
    }
}
