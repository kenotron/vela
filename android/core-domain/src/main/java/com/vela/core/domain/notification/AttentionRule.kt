package com.vela.core.domain.notification

/**
 * Job-outcome signal used by callers to derive
 * [com.vela.core.domain.LedgerRepository.LedgerEntry.requiresAttention] when
 * constructing a ledger entry (#31, #49). Each value corresponds to one of
 * the six concrete examples in `docs/examples/attention-rule-examples.md`,
 * which is the durable artifact this rule is tuned against.
 */
enum class JobSignal {
    /** Job explicitly reports it cannot proceed without a human decision. */
    ATTENTION_REQUIRED,

    /** Job failed and cannot proceed without a retry/abandon decision. */
    FAILED,

    /** Job finished, but flagged something the user asked to review. */
    DONE_USER_FLAGGED,

    /** Job finished cleanly with nothing flagged -- no decision needed. */
    DONE_CLEAN,

    /** Routine step/heartbeat update -- work is proceeding as expected. */
    PROGRESS,
}

/**
 * The attention rule (#31, #49): translates a [JobSignal] into the boolean
 * gate consumed by [AttentionCandidate.from]. This is the design doc's §5.4
 * gating table (`attention.required`, `failed`, and `done + user-flagged` all
 * notify; `progress` and a clean `done` never do) made concrete as a testable
 * `core-domain` rule, checked against the six examples in
 * `docs/examples/attention-rule-examples.md` (see `AttentionRuleTest`).
 *
 * This does not replace the existing construction-time gate in
 * [AttentionCandidate] -- callers still set
 * [com.vela.core.domain.LedgerRepository.LedgerEntry.requiresAttention]
 * directly. This rule exists so that value can be derived consistently from
 * a job's outcome instead of being decided ad hoc at each call site.
 */
object AttentionRule {
    fun requiresAttention(signal: JobSignal): Boolean = when (signal) {
        JobSignal.ATTENTION_REQUIRED,
        JobSignal.FAILED,
        JobSignal.DONE_USER_FLAGGED,
        -> true

        JobSignal.DONE_CLEAN,
        JobSignal.PROGRESS,
        -> false
    }
}
