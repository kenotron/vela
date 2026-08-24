package com.vela.hosttools

import com.vela.core.domain.HostTool.ToolResult
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Real approval gate for privileged tools (#44, #45, #46, #57).
 *
 * Every tool call is routed through [guard]. If [isPrivileged] says the tool
 * name is privileged (see [PrivilegedTools]), the underlying tool logic
 * ([runner] in [guard]) is NOT invoked until [requestApproval] resolves
 * `true`. If [requestApproval] does not resolve within [timeoutMs] -- or
 * resolves `false` -- the call is denied and [runner] is never called
 * (fail-closed, matching the server-side `ApprovalGate` in
 * `services/vela-agentd/src/vela_agentd_http/_approval_gate.py`, whose F2
 * adversarial test also asserts "no client -> timeout -> deny").
 *
 * Non-privileged tools bypass approval entirely and run immediately --
 * [requestApproval] is never called for them, so this gate adds zero latency
 * to the majority of tool calls.
 *
 * The default [requestApproval] denies everything. This is deliberate: a
 * caller that forgets to wire a real approval UI/voice channel gets a safe
 * (if unhelpful) fail-closed gate rather than an accidental no-op pass-through.
 * Real callers (e.g. the app's tool-loop wiring) MUST supply a
 * [requestApproval] that surfaces the request to a human -- for example by
 * publishing a `C2Event.ApprovalRequested`-shaped prompt to the UI and/or
 * [ApprovalVoiceBridge], and resolving true/false from the human's decision.
 */
class ApprovalGate(
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val isPrivileged: (String) -> Boolean = PrivilegedTools::isPrivileged,
    private val requestApproval: suspend (ApprovalRequest) -> Boolean = { false },
) {
    /** What a privileged call looks like when handed to [requestApproval]. */
    data class ApprovalRequest(val toolName: String, val argsJson: String)

    /**
     * Executes a tool call, gating on approval first if [toolName] is
     * privileged. [runner] performs the actual tool execution and is only
     * ever invoked after an approval (or immediately, for non-privileged
     * tools) -- never speculatively, never before a decision is known.
     */
    suspend fun guard(
        toolName: String,
        argsJson: String,
        runner: suspend () -> ToolResult,
    ): ToolResult {
        val blocked = denyIfPrivileged(toolName, argsJson)
        return blocked ?: runner()
    }

    /**
     * True single-line integration point for callers that CANNOT restructure their
     * function body into a [guard] lambda -- e.g. a genuine one-line residual inside a
     * file owned by another lane. Returns a blocking [ToolResult.Failure] if [toolName]
     * is privileged and was not approved (denied or timed out); returns `null` if the
     * caller should proceed normally (either the tool is not privileged, or it was
     * approved). Usage as a single inserted line at the top of an existing tool body,
     * with NO other changes (no constructor param, no restructuring):
     *
     * ```kotlin
     * override suspend fun run(argsJson: String): ToolResult {
     *     ApprovalGate.default.denyIfPrivileged(name, argsJson)?.let { return it }
     *     // ... existing body, completely unchanged ...
     * }
     * ```
     *
     * [guard] is implemented in terms of this method, so both entry points share
     * identical fail-closed/timeout semantics.
     */
    suspend fun denyIfPrivileged(toolName: String, argsJson: String): ToolResult? {
        if (!isPrivileged(toolName)) return null

        val approved = withTimeoutOrNull(timeoutMs) {
            requestApproval(ApprovalRequest(toolName, argsJson))
        } ?: false // timed out -- fail closed: treat exactly like an explicit decline

        return if (approved) {
            null
        } else {
            ToolResult.Failure(
                "privileged tool '$toolName' was not approved (denied or timed out after " +
                    "${timeoutMs}ms) -- blocked before execution",
            )
        }
    }

    companion object {
        /** Matches the server-side `ApprovalGate`'s spirit of a bounded, short wait, not a hang. */
        const val DEFAULT_TIMEOUT_MS: Long = 30_000L

        /**
         * Process-wide default gate instance, wired to [PrivilegedTools] classification
         * with the fail-closed (deny-everything) [requestApproval] until reconfigured via
         * [configureDefault]. Exists specifically so a tool in a file this lane cannot
         * edit (e.g. `DispatchToFleetTool.kt`, owned by the fleet-plane lane) can gate
         * itself with the one-line [denyIfPrivileged] call shown in its KDoc, without
         * requiring a constructor change to accept an injected gate.
         */
        @Volatile
        var default: ApprovalGate = ApprovalGate()
            private set

        /** Reconfigure [default] -- e.g. once a real approval UI/voice channel exists. */
        fun configureDefault(gate: ApprovalGate) {
            default = gate
        }
    }
}
