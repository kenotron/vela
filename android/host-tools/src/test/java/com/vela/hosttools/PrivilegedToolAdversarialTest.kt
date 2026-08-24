package com.vela.hosttools

import com.vela.core.domain.HostTool
import com.vela.core.domain.HostTool.ToolResult
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Goal item #46 -- adversarial verification: zero privileged tools reachable
 * unapproved.
 *
 * This is the proof, not a self-report: for every tool name classified as
 * privileged in [PrivilegedTools.PRIVILEGED] (which includes `dispatch_to_fleet`,
 * covering #57 even though this lane does not own `DispatchToFleetTool.kt`),
 * attempt to reach it through the real gating path used in production
 * ([AmplifierToolLoopClient]'s call into [ApprovalGate.guard]) with NO approval
 * granted, and assert:
 *   1. the underlying tool body never executes, and
 *   2. the call comes back as a blocked [ToolResult.Failure], never a hang
 *      and never a [ToolResult.Success].
 *
 * A fake, minimal [HostTool] stands in for each privileged name so this test
 * does not depend on the concrete implementations (some of which, like
 * calendar tools, require the Android framework and are not JVM-testable;
 * `dispatch_to_fleet`'s real class is owned by a sibling lane). The gate
 * itself is classification-by-name (see [PrivilegedTools]), so exercising it
 * against fakes with the real privileged names is a faithful test of the
 * actual gating behavior real tools will get.
 */
class PrivilegedToolAdversarialTest {

    private class RecordingFakeTool(override val name: String) : HostTool {
        val invocationCount = AtomicInteger(0)
        override val description: String = "fake tool for adversarial gate testing"
        override val inputSchema: String = "{}"
        override suspend fun execute(argsJson: String): ToolResult {
            invocationCount.incrementAndGet()
            return ToolResult.Success("{\"should_never_happen\": true}")
        }
    }

    @Test
    fun `every classified privileged tool is blocked when never approved`() = runBlocking {
        assertTrue(
            "expected at least one privileged tool to be classified (#45)",
            PrivilegedTools.PRIVILEGED.isNotEmpty(),
        )

        // Fail-closed gate: no approval channel ever says yes.
        val gate = ApprovalGate(timeoutMs = 100, requestApproval = { false })

        for (toolName in PrivilegedTools.PRIVILEGED) {
            val fakeTool = RecordingFakeTool(toolName)

            val result = gate.guard(toolName, "{}") { fakeTool.execute("{}") }

            assertEquals(
                "privileged tool '$toolName' must never execute without approval",
                0,
                fakeTool.invocationCount.get(),
            )
            assertTrue(
                "privileged tool '$toolName' must come back blocked (Failure), got $result",
                result is ToolResult.Failure,
            )
        }
    }

    @Test
    fun `every classified privileged tool is reachable ONLY after explicit approval`() = runBlocking {
        val gate = ApprovalGate(timeoutMs = 5_000, requestApproval = { true })

        for (toolName in PrivilegedTools.PRIVILEGED) {
            val fakeTool = RecordingFakeTool(toolName)

            val result = gate.guard(toolName, "{}") { fakeTool.execute("{}") }

            assertEquals(1, fakeTool.invocationCount.get())
            assertTrue(result is ToolResult.Success)
        }
    }

    @Test
    fun `known non-privileged tool is never gated even under a deny-everything approver`() = runBlocking {
        val gate = ApprovalGate(timeoutMs = 100, requestApproval = { false })
        val fakeTool = RecordingFakeTool("calendar_read")

        val result = gate.guard("calendar_read", "{}") { fakeTool.execute("{}") }

        assertEquals(1, fakeTool.invocationCount.get())
        assertTrue(result is ToolResult.Success)
    }
}
