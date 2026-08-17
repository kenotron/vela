package com.vela.hosttools

import com.vela.core.domain.HostTool
import com.vela.core.domain.HostTool.ToolResult
import kotlin.system.measureTimeMillis

/**
 * Base class enforcing the G3 correctness gate from the Vela design doc
 * (docs/designs/2026-08-16-vela-chief-of-staff-rebuild.md §11.1):
 *
 *   "Zero host tools exceeding 2s without handle-registration. Any host tool
 *   whose p99 exceeds 2s must be handle-returning. Enforced by instrumentation,
 *   not by review."
 *
 * Subclasses implement [run] with their real logic. [execute] wraps that call,
 * measures wall-clock duration, and if the tool exceeds [maxSyncMillis] AND the
 * result is not a handle-returning shape (NeedsConfirmation, or a Success whose
 * resultJson looks like a handle/job-id payload), the violation is surfaced as
 * a loud failure rather than silently accepted.
 *
 * "Handle-returning" is detected structurally: a Success result is considered
 * handle-returning if its JSON contains a "handle" or "job_id" key. This keeps
 * the gate mechanical (no per-tool opt-out flag) while still allowing tools
 * that are inherently slow-but-synchronous to declare their own budget via
 * [maxSyncMillis] override (default matches the design doc's G3 gate: 2000ms).
 */
abstract class BaseHostTool : HostTool {

    /** G3 gate threshold. Default is the design doc's 2s binary gate. */
    protected open val maxSyncMillis: Long = 2_000L

    /** Subclasses implement real tool logic here. */
    protected abstract suspend fun run(argsJson: String): ToolResult

    final override suspend fun execute(argsJson: String): ToolResult {
        var result: ToolResult?
        val elapsed = measureTimeMillis {
            result = try {
                run(argsJson)
            } catch (t: Throwable) {
                ToolResult.Failure("$name threw: ${t.message}", t)
            }
        }
        val finalResult = result ?: ToolResult.Failure("$name produced no result")

        if (elapsed > maxSyncMillis && !isHandleReturning(finalResult)) {
            val violation = "G3 violation: $name took ${elapsed}ms (> ${maxSyncMillis}ms) " +
                "without returning a handle. Any host tool whose duration can exceed " +
                "$maxSyncMillis ms must be handle-returning (design doc §11.1 G3)."
            System.err.println(violation)
            return ToolResult.Failure(violation)
        }
        return finalResult
    }

    private fun isHandleReturning(result: ToolResult): Boolean {
        return when (result) {
            is ToolResult.NeedsConfirmation -> true
            is ToolResult.Success -> result.resultJson.contains("\"handle\"") ||
                result.resultJson.contains("\"job_id\"")
            is ToolResult.Failure -> true // failures are terminal, not a slow-hang concern
        }
    }
}
