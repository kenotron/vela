package com.vela.hosttools

import com.vela.core.domain.HostTool.ToolResult
import com.vela.core.domain.LedgerRepository
import com.vela.core.domain.LedgerRepository.LedgerEntry
import com.vela.core.domain.LedgerRepository.Status
import java.util.UUID
import org.json.JSONObject

/**
 * `dispatch_to_fleet` (design doc §4.2, C1 table; §5.1 step 8; §11.1 G2/G3;
 * §11.2 perf target "p99 < 1s").
 *
 * Hard requirements enforced here:
 *  - A ledger record is created BEFORE the fleet handshake (§5.1 note: "the
 *    ledger record is created before the tool result is returned. If the app
 *    is killed between 8 and 9, the job exists in the ledger and is
 *    recoverable.") — see the ordering in [run] below.
 *  - Returns a handle (job_id), never a blocking result (A5, A9, D2).
 *  - This lane's fleet plane is a STUB: [FleetPlane.dispatch] simulates a
 *    synchronous reachability check (D3) and returns immediately. Real fleet
 *    wiring is explicitly out of scope for this lane (see goal file SCOPE-OUTS).
 *
 * Pure Kotlin/JVM-testable: no Android framework dependency, so it can be
 * exercised by a real local JVM unit test (see DispatchToFleetToolTest),
 * satisfying the goal file's requirement to measure real p99 latency over
 * >=10 calls against the stub.
 */
interface FleetPlane {
    /** Simulates D1 (accept job spec) + D3 (verify reachability synchronously). */
    fun dispatch(jobSpec: String): DispatchOutcome

    data class DispatchOutcome(val reachable: Boolean, val detail: String)
}

/** Stub fleet plane: always reachable, near-instant — stands in for a real muxterm-backed plane. */
class StubFleetPlane : FleetPlane {
    override fun dispatch(jobSpec: String): FleetPlane.DispatchOutcome {
        // Simulate a fast synchronous reachability handshake (D3).
        return FleetPlane.DispatchOutcome(reachable = true, detail = "stub: accepted")
    }
}

class DispatchToFleetTool(
    private val ledger: LedgerRepository,
    private val fleetPlane: FleetPlane = StubFleetPlane(),
) : BaseHostTool() {

    // Tighter budget than the base class's 2s G3 gate, matching the design
    // doc's specific perf target for this tool (§11.2: "p99 < 1s").
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

        // Step 1 (ordering-critical, §5.1 step 8): create the ledger record
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

        // Step 2: hand off to the fleet plane, verifying reachability synchronously (D3).
        val outcome = fleetPlane.dispatch(argsJson)
        if (!outcome.reachable) {
            ledger.recordDecision(
                jobId,
                LedgerRepository.Decision(
                    status = Status.DISMISSED,
                    decidedAtEpochMs = System.currentTimeMillis(),
                    note = "fleet target unreachable at dispatch time: ${outcome.detail}",
                ),
            )
            return ToolResult.Failure("fleet target unreachable: ${outcome.detail}")
        }

        // Step 3: return a handle, never a blocking result (A5/A9/D2).
        return ToolResult.Success(
            JSONObject()
                .put("job_id", jobId)
                .put("status", "accepted")
                .toString(),
        )
    }
}
