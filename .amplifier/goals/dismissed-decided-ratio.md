# Goal: dismissed-vs-decided ratio tracking (#48)

Working directory: THIS worktree only. Do not touch the main checkout or sibling worktrees.
Branch: goal-batch/dismissed-decided-ratio
Base SHA: df8dc040

## Outcome

The design doc's designated feedback signal for notification-rule quality
(epic #19) needs actual instrumentation, not just a stated intention:

1. Every `AttentionNotification` delivered via `AttentionNotifier` (see
   `android/core-domain/.../notification/AttentionNotifier.kt`) that reaches a
   terminal user response must be classifiable as either **dismissed**
   (ignored/swiped away without a decision) or **decided** (the user acted on
   the underlying ledger entry — approved/denied/otherwise resolved it).
2. Add a tracker (e.g. `NotificationOutcomeTracker` or similar, your naming)
   that records each outcome and can report a running dismissed-vs-decided
   ratio — this is the concrete "instrumentation" the issue asks for. It does
   not need a UI; a queryable in-memory/db-backed counter with a real test
   proving the ratio computation is correct is sufficient scope for this
   issue.
3. Wire it to the real ledger/notification path that already exists (don't
   build a parallel/parallel-universe notification system) — the tracker
   should observe real `AttentionCandidate` -> `AttentionNotification` ->
   (dismissed | decided) transitions using the existing types in
   `core-domain`/`ledger`.
4. Prove it with unit tests: at least one test demonstrating a decided case,
   one dismissed case, and one ratio computation across a mixed sequence.

Do not touch chat/UI presentation (that's issue #35, a sibling lane) — this
issue is the tracking/instrumentation layer only.

## File ownership

Own:
- `android/core-domain/src/main/java/com/vela/core/domain/notification/**`
- `android/core-domain/src/test/java/com/vela/core/domain/notification/**`
- `android/ledger/src/main/java/com/vela/ledger/**` (only if a new field/table
  is genuinely needed to persist outcome state — prefer extending existing
  `LedgerEntry`/`JobEntity` minimally over new tables)
- `android/ledger/src/test/java/com/vela/ledger/**`

Do NOT touch (owned by sibling lane `approval-prompts-chat`):
- `android/core-ui/**`
- `android/app/src/main/java/com/vela/app/ui/chat/**`

If you need a change in those files to complete this goal, record it as a
named residual in `DONE.json` instead of editing them.

## Host capability limits

No physical Android device available. Verify via JVM unit tests
(`./gradlew :core-domain:testDebugUnitTest :ledger:testDebugUnitTest`) run
from `android/`. Do not claim device verification you did not perform.

## Terminal states

Complete when **either** every item above reaches a terminal state, **or** it
is conclusively demonstrated the remainder cannot, naming the blocker for
each. Items ending FAIL or BLOCKED are residuals, not failures of the goal.
Terminal states: `PASS` / `FAIL-named` / `BLOCKED-named` / `PENDING-HUMAN`.

## Process discipline

- Commit early, push always (no remote configured for these worktrees is
  fine — commit regardless, note in `DONE.json` if push was impossible).
- Never merge to main yourself — the orchestrator merges.
- `DONE.json` is gitignored already. Write it in the worktree root as your
  FINAL act: fields `lane, session_id, verdict, branch, head, pushed,
  items[], residuals[], pending_human[], suite`. `verdict` is exactly one of
  `COMPLETE` / `BLOCKED` / `PARTIAL`.
- Time bound: 90 minutes wall clock / 40 turns. Exceeding it is a terminal
  `BUDGET` state in `DONE.json` — still commit and write DONE.json.
- Run `./gradlew :core-domain:testDebugUnitTest :ledger:testDebugUnitTest`
  from `android/` yourself before writing DONE.json and record the real pass
  count in `suite`, not just "BUILD SUCCESSFUL".

Complete when **either** every item reaches a terminal state, **or** it is
conclusively demonstrated the remainder cannot, naming the blocker for each.
