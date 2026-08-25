# Attention-rule examples (#31, #49)

The design doc (`docs/designs/2026-08-16-vela-chief-of-staff-rebuild.md`, §2.1
item 2 and §5.4) names this as the highest-leverage unresolved question for
notification quality: **the distinguishing rule between "needs a decision"
and "just progress" cannot be inferred from nothing -- it needs concrete
examples to check itself against.**

This document is that check. Each example is grounded in a real call site in
this repo that constructs a `LedgerRepository.LedgerEntry` (or the
`attention.required`/`attention.reason` signal it is sourced from), not an
invented scenario. Each is encoded as a `JobSignal` case in
`AttentionRule` (`android/core-domain/.../notification/AttentionRule.kt`) and
proven by a corresponding test in `AttentionRuleTest.kt`.

## Needs a decision (`requiresAttention = true`)

1. **Freshly dispatched job awaiting acknowledgement.**
   Source: `DispatchToFleetTool.kt` -- "a freshly dispatched job is, by
   definition, awaiting a human decision." Example: a lane is dispatched to
   work `#31 + #49` and the user has not yet accepted/deferred/dismissed the
   dispatch itself.
   `JobSignal.ATTENTION_REQUIRED`

2. **Job failed and cannot proceed without a retry/abandon decision.**
   Source: server ledger `attention.required` / `attention.reason` mapping
   (`ServerLedgerRepository.kt`, `SqliteLedgerRepositoryAdapter.kt`). Example:
   a lane's gradle build fails with a compile error the agent cannot resolve
   on its own -- the human must decide retry, reassign, or abandon.
   `JobSignal.FAILED`

3. **Job finished, but flagged something the user explicitly asked to review.**
   Source: design doc §5.4 gating table, `done + user-flagged -> NOTIFY
   (low)`. Example: a lane completes its goal but records a residual it
   could not resolve within its owned files ("this needs a human call on
   whether to touch `core-ui`") -- the ledger entry is done, but the flag
   means a decision is still pending.
   `JobSignal.DONE_USER_FLAGGED`

## Just progress (`requiresAttention = false`)

4. **Job finished cleanly with nothing flagged.**
   Source: design doc §5.4, the implicit "done, no flag" case that is
   deliberately absent from the NOTIFY rows -- a clean completion is progress,
   not a decision point. Example: a lane's `DONE.json` reports
   `verdict: COMPLETE` with an empty `residuals` list.
   `JobSignal.DONE_CLEAN`

5. **Routine step/heartbeat update.**
   Source: design doc §5.4, `progress -> UI only, never notify`. Example: a
   lane's ledger entry logging "ran `./gradlew :core-domain:testDebugUnitTest`,
   12/12 passed" while work continues -- informative, not actionable.
   `JobSignal.PROGRESS`

6. **Mid-flight status update with no outcome yet.**
   Source: same `progress` row, distinct trigger from #5 (a heartbeat with no
   test outcome at all, vs. a step that reports a result). Example: "lane
   `attention-rule-examples-tuning` still running, no output yet" -- there is
   nothing here for the human to decide; it would be pure notification noise.
   `JobSignal.PROGRESS`

## The rule under test

```kotlin
fun requiresAttention(signal: JobSignal): Boolean = when (signal) {
    JobSignal.ATTENTION_REQUIRED,
    JobSignal.FAILED,
    JobSignal.DONE_USER_FLAGGED -> true

    JobSignal.DONE_CLEAN,
    JobSignal.PROGRESS -> false
}
```

This is exactly the design doc's §5.4 gating table (`attention.required`,
`failed`, and `done + user-flagged` all resolve to `true`; `progress` and a
clean `done` resolve to `false`), made concrete as a `core-domain` rule and
checked against all six examples above in `AttentionRuleTest`. No new
classification framework was introduced -- this confirms the already-designed
rule and gives it a durable, testable home instead of leaving it only as
caller-supplied booleans with no cross-checked examples.
