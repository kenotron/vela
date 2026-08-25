# Goal: Card deck UI real swipe-to-decide interaction (#29)

Working directory: THIS worktree only. Do not touch the main checkout or
sibling worktrees. Branch: `goal/card-deck-swipe-decide`. Base SHA: pinned at
launch (see manifest).

## Outcome

Issue #29 (kenotron/vela), under epic #14 (G2 Attention Queue & Decision
Surface / Card Deck). Swipe = decide (accept/decline/defer), not merely
dismiss. `android/core-ui/src/main/java/com/vela/core/ui/CardDeck.kt` is
currently a mock scaffold (lane 1.1); `android/app/src/androidTest/java/com/vela/app/CardDeckSwipeTest.kt`
exists as scaffolding too. This issue wires the real interaction to real
ledger decisions.

Read `docs/designs/2026-08-24-vela-server-ledger.md` first for the
`/ledger/jobs/{id}/decision` contract (already implemented server-side per
#32, commit 21c81c3c — do not redo the server side). Read
`android/core-domain/src/main/java/com/vela/core/domain/LedgerRepository.kt`
for the client-side contract that already exists to call it.

Implement:
- Wire `CardDeck.kt`'s swipe gestures (left/right/up or whatever the existing
  mock uses) to actually invoke the ledger decision repository call
  (accept/decline/defer), not just animate a card away.
- Real state: a swiped card must reflect its decision was durably recorded
  (optimistic UI update + rollback on failure is fine, but there must be a
  real network/repository call, not a no-op).
- Cover with a real test (unit test for the view-model/interaction logic;
  extend `CardDeckSwipeTest.kt` only if it is already wired for
  instrumentation — do not silently skip verification because instrumented
  tests are inconvenient in this environment; if genuinely blocked on an
  emulator dependency, name it precisely as a residual).

This is Android UI-scoped. Do NOT touch `android/voice/**` or
`android/core-domain/src/main/java/com/vela/core/domain/VoiceTransport.kt` —
issue #23 (fast/slow-tier voice split) may be running in a sibling lane
touching those files.

## File ownership

Own: `android/core-ui/src/main/java/com/vela/core/ui/CardDeck.kt` and any new
files under `android/core-ui/**` or `android/app/src/androidTest/java/com/vela/app/CardDeckSwipeTest.kt`.
May READ (not write) `android/core-domain/**` — if the existing
`LedgerRepository` contract needs a new method, ADD one narrowly (do not
restructure it) and record the addition explicitly in DONE.json so the
orchestrator can review it for cross-lane collision before merge.

## Verification

Run the existing Android test suite scoped to what you touched:
`./gradlew :core-ui:testDebugUnitTest :app:testDebugUnitTest --console=plain`
from your worktree's `android/` directory. Report real pass counts from
`build/test-results/**/TEST-*.xml`, not "BUILD SUCCESSFUL".

## Complete when

Complete when **either** swipe gestures are wired to real ledger decision
calls and verified with a real passing test demonstrating a decision was
recorded, **or** it is conclusively demonstrated this cannot be completed in
this environment — naming the blocker precisely. Items ending FAIL or
BLOCKED are residuals, not failures of the goal.

## Terminal states

PASS / FAIL-named / BLOCKED-named / PENDING-HUMAN.

## Time bound

90 minutes wall-clock, `--max-turns 40`. Exceeding the bound is a terminal
`BUDGET` state — commit and push whatever is real, write DONE.json, stop.

## KNOWN

- Env: Android Gradle project at `android/`. Use `./gradlew` from there.
- Baseline before this lane: core-ui and app module tests currently pass
  (verify count yourself as your true baseline before changing anything).
- Add `DONE.json` to `.gitignore` before writing it, if not already ignored.
- Commit early, push always.
- Never merge to main yourself — the orchestrator lands this.
