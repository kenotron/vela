package com.vela.hosttools

import com.vela.core.domain.HostTool.ToolResult
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Goal item #44: real approval gate for privileged tools, timeout-to-deny.
 * Goal item #45: privileged-tool classification is respected (non-privileged
 * tools bypass the gate entirely).
 */
class ApprovalGateTest {

    @Test
    fun `non-privileged tool runs immediately without ever calling requestApproval`() = runBlocking {
        val approvalRequested = AtomicBoolean(false)
        val gate = ApprovalGate(requestApproval = { approvalRequested.set(true); true })

        val ran = AtomicBoolean(false)
        val result = gate.guard("calendar_read", "{}") {
            ran.set(true)
            ToolResult.Success("{}")
        }

        assertTrue("expected the runner to execute for a non-privileged tool", ran.get())
        assertFalse("requestApproval must never be consulted for a non-privileged tool", approvalRequested.get())
        assertTrue(result is ToolResult.Success)
    }

    @Test
    fun `privileged tool runs only after approval is granted`() = runBlocking {
        val gate = ApprovalGate(requestApproval = { true })
        val ran = AtomicBoolean(false)

        val result = gate.guard("dispatch_to_fleet", "{}") {
            ran.set(true)
            ToolResult.Success("{}")
        }

        assertTrue("expected the runner to execute once approved", ran.get())
        assertTrue(result is ToolResult.Success)
    }

    @Test
    fun `privileged tool is blocked before execution when denied`() = runBlocking {
        val gate = ApprovalGate(requestApproval = { false })
        val ran = AtomicBoolean(false)

        val result = gate.guard("dispatch_to_fleet", "{}") {
            ran.set(true)
            ToolResult.Success("{}")
        }

        assertFalse("the underlying tool must never execute when denied", ran.get())
        assertTrue("expected a Failure result on denial", result is ToolResult.Failure)
    }

    @Test
    fun `privileged tool with no default requestApproval wired is denied fail-closed`() = runBlocking {
        // Uses ApprovalGate()'s own default -- a caller who forgets to wire a real
        // approval channel must get a safe deny, never an accidental pass-through.
        val gate = ApprovalGate(timeoutMs = 200)
        val ran = AtomicBoolean(false)

        val result = gate.guard("calendar_create", "{}") {
            ran.set(true)
            ToolResult.Success("{}")
        }

        assertFalse(ran.get())
        assertTrue(result is ToolResult.Failure)
    }

    @Test
    fun `denyIfPrivileged returns null (proceed) for a non-privileged tool without consulting requestApproval`() =
        runBlocking {
            val consulted = AtomicBoolean(false)
            val gate = ApprovalGate(requestApproval = { consulted.set(true); false })

            val blocked = gate.denyIfPrivileged("calendar_read", "{}")

            assertTrue("expected null (proceed) for a non-privileged tool", blocked == null)
            assertFalse(consulted.get())
        }

    @Test
    fun `denyIfPrivileged returns null (proceed) once a privileged tool is approved`() = runBlocking {
        val gate = ApprovalGate(requestApproval = { true })

        val blocked = gate.denyIfPrivileged("dispatch_to_fleet", "{}")

        assertTrue("expected null (proceed) once approved", blocked == null)
    }

    @Test
    fun `denyIfPrivileged returns a blocking Failure for a privileged tool that is denied`() = runBlocking {
        val gate = ApprovalGate(requestApproval = { false })

        val blocked = gate.denyIfPrivileged("dispatch_to_fleet", "{}")

        assertTrue("expected a Failure to block the caller", blocked is ToolResult.Failure)
    }

    @Test
    fun `no approval response within timeout denies (fail closed), never hangs`() = runBlocking {
        // A deferred that is never completed simulates "no human ever answers".
        val neverResolves = CompletableDeferred<Boolean>()
        val gate = ApprovalGate(
            timeoutMs = 150,
            requestApproval = { neverResolves.await() },
        )
        val ran = AtomicBoolean(false)

        val start = System.nanoTime()
        val result = gate.guard("dispatch_to_fleet", "{}") {
            ran.set(true)
            ToolResult.Success("{}")
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        assertFalse("the underlying tool must never execute on timeout", ran.get())
        assertTrue("expected a Failure result on timeout", result is ToolResult.Failure)
        assertTrue(
            "expected the gate to resolve near its configured timeout (150ms), took ${elapsedMs}ms",
            elapsedMs in 100..5_000,
        )
    }
}
