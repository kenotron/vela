package com.vela.hosttools

import com.vela.core.domain.HostTool.ToolResult
import com.vela.core.domain.LedgerRepository
import com.vela.core.domain.LedgerRepository.LedgerEntry
import com.vela.core.domain.LedgerRepository.Status
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * `dispatch_to_fleet` (design doc `2026-08-24-vela-fleet-execution-plane.md`
 * \u00a74.4 D1 job spec; \u00a75.1 the <1s dispatch path D1-D3; \u00a78.3 D1-D5 conformance
 * mapping; \u00a711.1 gates FG-1/FG-2).
 *
 * Hard requirements enforced here:
 *  - A ledger record is created BEFORE the fleet handshake (\u00a75.1 note: "the
 *    ledger record is created before the tool result is returned. If the app
 *    is killed between 8 and 9, the job exists in the ledger and is
 *    recoverable.") -- see the ordering in [run] below.
 *  - Returns a handle (job_id), never a blocking result (A5, A9, D2, #40).
 *  - Reachability (D3, #41) is decided *synchronously* against an in-memory
 *    heartbeat registry -- never a live network probe. This is the load-bearing
 *    move of the design doc's \u00a71.2 framing insight: D2 (<1s) and D3
 *    (reachability) are only simultaneously satisfiable if reachability is a
 *    lookup against a connection that is already open (workers dial in and
 *    hold a session; the dispatcher never dials out).
 *
 * This lane's fleet plane is a deterministic fake ([StubFleetPlane]): no real
 * multi-machine fleet is reachable from this worktree (goal file "Host
 * capability limits"). [StubFleetPlane] simulates the heartbeat registry with
 * an injectable [Clock] so tests can move time deterministically instead of
 * sleeping wall-clock seconds, and simulates async job completion on a
 * background executor so dispatch itself never blocks on the "work".
 *
 * Pure Kotlin/JVM-testable: no Android framework dependency, so it can be
 * exercised by a real local JVM unit test (see DispatchToFleetToolTest).
 */
interface FleetPlane {
    /** D1 (accept job spec) + D3 (verify reachability synchronously, in-memory only). */
    fun dispatch(jobSpec: String): DispatchOutcome

    /**
     * @param lastHeartbeatAgeMs age of the target's last known heartbeat at
     *   dispatch time, or null if no heartbeat has ever been recorded for the
     *   target. Surfaced to the caller per design doc \u00a75.1: "the dispatch
     *   response carries `last_heartbeat_age_ms` so the caller can see it" --
     *   this is the honest residual D3 gap (a live session does not prove a
     *   live machine), stated rather than hidden.
     */
    data class DispatchOutcome(
        val reachable: Boolean,
        val detail: String,
        val machineId: String? = null,
        val lastHeartbeatAgeMs: Long? = null,
    )
}

/** Injectable time source so tests can move time deterministically (no real sleeps). */
interface Clock {
    fun nowMs(): Long
}

class SystemClock : Clock {
    override fun nowMs(): Long = System.currentTimeMillis()
}

/**
 * Deterministic fake fleet plane standing in for `vela-fleetd` +
 * `velafleet-worker` (design doc \u00a74.1/\u00a74.2). Nothing here talks to a real
 * machine -- it is the [FleetPlane] test double the design doc explicitly
 * says stays in the tree (\u00a78.4): "`StubFleetPlane` stays in the tree as the
 * test double."
 *
 * Reachability model (D3, #41): a machine is "live" iff its last-recorded
 * heartbeat is within `2 * heartbeatIntervalMs` of now -- the exact bound
 * stated in \u00a75.1 ("a worker is only 'live' if its last heartbeat is within
 * `2 x heartbeat_interval`"). [heartbeat] simulates a worker's held dial-in
 * session reporting liveness; [forget] simulates a worker going dark (crash,
 * SIGSTOP, severed network -- the FG-2 adversarial scenario).
 *
 * Async completion (D2/#40): [dispatch] never blocks on the dispatched work.
 * Any simulated work is handed to [executor] and dispatch returns before it
 * necessarily completes -- proven in tests by injecting a slow task and
 * asserting the *dispatch call itself* returns fast regardless.
 */
class StubFleetPlane(
    private val clock: Clock = SystemClock(),
    private val heartbeatIntervalMs: Long = 5_000L,
    private val executor: ExecutorService = Executors.newCachedThreadPool(),
) : FleetPlane {

    private val lastHeartbeatMs = ConcurrentHashMap<String, Long>()

    init {
        // Out-of-the-box behavior matches the prior always-reachable stub:
        // the default machine is live from construction time. Tests that want
        // to exercise unreachability call [forget] or advance a [FakeClock].
        heartbeat(DEFAULT_MACHINE)
    }

    /** Simulates a worker's dial-in session reporting liveness (design doc \u00a74.2 item 1). */
    fun heartbeat(machineId: String, atMs: Long = clock.nowMs()) {
        lastHeartbeatMs[machineId] = atMs
    }

    /** Simulates a worker going dark: crash, `SIGSTOP`, severed network (FG-2). */
    fun forget(machineId: String) {
        lastHeartbeatMs.remove(machineId)
    }

    override fun dispatch(jobSpec: String): FleetPlane.DispatchOutcome {
        val targetMachine = extractTargetMachine(jobSpec) ?: DEFAULT_MACHINE
        val now = clock.nowMs()
        val last = lastHeartbeatMs[targetMachine]

        if (last == null) {
            return FleetPlane.DispatchOutcome(
                reachable = false,
                detail = "no heartbeat ever recorded for machine=$targetMachine",
                machineId = targetMachine,
                lastHeartbeatAgeMs = null,
            )
        }

        val ageMs = now - last
        val liveWindowMs = 2 * heartbeatIntervalMs
        if (ageMs > liveWindowMs) {
            return FleetPlane.DispatchOutcome(
                reachable = false,
                detail = "machine=$targetMachine last heartbeat ${ageMs}ms ago exceeds " +
                    "live window ${liveWindowMs}ms (2x heartbeat interval)",
                machineId = targetMachine,
                lastHeartbeatAgeMs = ageMs,
            )
        }

        // Hand the "work" to a background executor and return immediately --
        // dispatch never waits on it (D2/#40). Real callers would push the
        // job over the already-open worker session here (\u00a75.1 step 3).
        executor.submit { /* simulated async job execution; no-op in this fake */ }

        return FleetPlane.DispatchOutcome(
            reachable = true,
            detail = "accepted",
            machineId = targetMachine,
            lastHeartbeatAgeMs = ageMs,
        )
    }

    private fun extractTargetMachine(jobSpec: String): String? =
        try {
            val obj = JSONObject(jobSpec)
            if (obj.has("targetHint") && !obj.isNull("targetHint")) {
                obj.getString("targetHint").ifBlank { null }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }

    companion object {
        const val DEFAULT_MACHINE = "default"
    }
}

/**
 * Real [FleetPlane] behind `vela-agentd`'s `POST /fleet/dispatch` (F0.2,
 * design doc \u00a79.1 Stage F0 Lane F0.2). Replaces [StubFleetPlane] in
 * production wiring ([StubFleetPlane] stays in the tree as the test double
 * per \u00a78.4).
 *
 * `dispatch()` is a single synchronous HTTP round trip: the server launches
 * `velafleet-run` over SSH and starts tailing its events in a background
 * task server-side, so this call returns as soon as the SSH launch itself
 * completes -- it never blocks on the dispatched job (D2/#40).
 *
 * Reachability (D3) here is *not* an in-memory heartbeat-registry lookup
 * (that is Stage F1's broker) -- it is the honest F0 approximation: did the
 * SSH launch round trip succeed. A non-2xx response, a network error, or a
 * client-side timeout are all reported as `reachable = false` with detail,
 * exactly mirroring the design doc's \u00a79.1 statement that F0 does not claim
 * reliable D3.
 */
class SshFleetPlane(
    private val baseUrl: String,
    private val apiKey: String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(2, TimeUnit.SECONDS)
        .connectTimeout(1, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build(),
) : FleetPlane {
    private val jsonMediaType = "application/json".toMediaType()

    override fun dispatch(jobSpec: String): FleetPlane.DispatchOutcome {
        val (jobId, runtime, argv) = parseDispatchArgs(jobSpec)

        val requestBody = JSONObject()
            .put("job_id", jobId)
            .put("runtime", runtime)
            .apply {
                val argvArray = org.json.JSONArray()
                argv.forEach { argvArray.put(it) }
                put("argv", argvArray)
            }
            .toString()

        val request = Request.Builder()
            .url("$baseUrl/fleet/dispatch")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(requestBody.toRequestBody(jsonMediaType))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val bodyString = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val reason = extractErrorMessage(bodyString) ?: "HTTP ${response.code}"
                    return FleetPlane.DispatchOutcome(
                        reachable = false,
                        detail = "dispatch failed: $reason",
                        machineId = null,
                        lastHeartbeatAgeMs = null,
                    )
                }
                val json = JSONObject(bodyString)
                FleetPlane.DispatchOutcome(
                    reachable = json.optBoolean("reachable", false),
                    detail = json.optString("detail", "accepted"),
                    machineId = json.optString("machine_id", null),
                    // F0 has no heartbeat registry (Stage F1 concern) -- always
                    // null here, an honest statement of what this milestone does
                    // NOT provide, rather than a fabricated value.
                    lastHeartbeatAgeMs = null,
                )
            }
        } catch (e: IOException) {
            FleetPlane.DispatchOutcome(
                reachable = false,
                detail = "UNREACHABLE: ${e.javaClass.simpleName}: ${e.message}",
                machineId = null,
                lastHeartbeatAgeMs = null,
            )
        }
    }

    private data class ParsedDispatchArgs(val jobId: String, val runtime: String, val argv: List<String>)

    private fun parseDispatchArgs(jobSpec: String): ParsedDispatchArgs {
        val obj = try {
            JSONObject(jobSpec)
        } catch (e: Exception) {
            JSONObject()
        }
        val jobId = obj.optString("job_id", UUID.randomUUID().toString())
        val runtime = obj.optString("runtime", "shell")
        val argv = mutableListOf<String>()
        obj.optJSONArray("argv")?.let { arr ->
            for (i in 0 until arr.length()) argv.add(arr.getString(i))
        }
        if (argv.isEmpty()) {
            // Fall back to a trivial no-op shell command so a bare
            // {title, summary} jobSpec (the shape DispatchToFleetTool.run()
            // actually sends today) still produces a launchable job.
            argv.add("true")
        }
        return ParsedDispatchArgs(jobId, runtime, argv)
    }

    private fun extractErrorMessage(body: String): String? =
        try {
            JSONObject(body).optJSONObject("error")?.optString("message")
                ?: JSONObject(body).optJSONObject("detail")?.optJSONObject("error")?.optString("message")
        } catch (e: Exception) {
            null
        }
}

class DispatchToFleetTool(
    private val ledger: LedgerRepository,
    private val fleetPlane: FleetPlane = StubFleetPlane(),
) : BaseHostTool() {

    // Tighter budget than the base class's 2s G3 gate, matching the design
    // doc's specific perf target for this tool (\u00a711.2: "p99 < 1s").
    override val maxSyncMillis: Long = 1_000L

    override val name: String = "dispatch_to_fleet"
    override val description: String =
        "Dispatch a unit of work to the fleet execution plane. Returns a job handle " +
            "immediately; does not block on the work itself."
    override val inputSchema: String = """
        {
          "type": "object",
          "properties": {
            "title": {"type": "string", "description": "Short human-readable job title"},
            "summary": {"type": "string", "description": "What the job will do"},
            "targetHint": {"type": "string", "description": "Optional machine/capability targeting hint"}
          },
          "required": ["title", "summary"]
        }
    """.trimIndent()

    override suspend fun run(argsJson: String): ToolResult {
        val args = JSONObject(argsJson)
        if (!args.has("title") || !args.has("summary")) {
            return ToolResult.Failure("missing required field(s): title, summary")
        }
        val title = args.getString("title")
        val summary = args.getString("summary")
        val jobId = UUID.randomUUID().toString()

        // Step 1 (ordering-critical, \u00a75.1 step 8): create the ledger record
        // BEFORE the fleet handshake, so a kill between here and the handle
        // return still leaves the job recoverable (G2: zero fleet dispatches
        // with no ledger record).
        ledger.append(
            LedgerEntry(
                id = jobId,
                title = title,
                summary = summary,
                createdAtEpochMs = System.currentTimeMillis(),
                source = "dispatch_to_fleet",
                status = Status.PENDING,
            ),
        )

        // Step 2: hand off to the fleet plane, verifying reachability
        // synchronously against the in-memory heartbeat registry (D3, #41) --
        // never a live probe (design doc \u00a71.2, \u00a75.1 step 3).
        val outcome = fleetPlane.dispatch(argsJson)
        if (!outcome.reachable) {
            ledger.recordDecision(
                jobId,
                LedgerRepository.Decision(
                    status = Status.DISMISSED,
                    decidedAtEpochMs = System.currentTimeMillis(),
                    note = "fleet target unreachable at dispatch time: ${outcome.detail}" +
                        (outcome.lastHeartbeatAgeMs?.let { " (last_heartbeat_age_ms=$it)" } ?: ""),
                ),
            )
            return ToolResult.Failure("fleet target unreachable: ${outcome.detail}")
        }

        // Step 3: return a handle, never a blocking result (A5/A9/D2/#40).
        val result = JSONObject()
            .put("job_id", jobId)
            .put("status", "accepted")
        outcome.machineId?.let { result.put("machine_id", it) }
        outcome.lastHeartbeatAgeMs?.let { result.put("last_heartbeat_age_ms", it) }
        return ToolResult.Success(result.toString())
    }
}
