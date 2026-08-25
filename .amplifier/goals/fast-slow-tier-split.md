# Goal: Fast-tier / slow-tier conversational split (#23)

Working directory: THIS worktree only. Do not touch the main checkout or
sibling worktrees. Branch: `goal/fast-slow-tier-split`. Base SHA: pinned at
launch (see manifest).

## Outcome

Issue #23 (kenotron/vela), under epic #13 (G1 Voice-First Conversational
Core). Load-bearing pattern per design doc §4.4
(`docs/designs/2026-08-16-vela-chief-of-staff-rebuild.md`): the fast tier
(voice model) classifies an utterance as trivial-chat vs real-work; real
work hands off to the slow tier (vela-agentd) and the fast tier narrates
real events while waiting, rather than blocking silently.

Read `docs/designs/2026-08-16-vela-chief-of-staff-rebuild.md` §4.4 first.
Read `android/voice/**` (LiveKitVoiceTransport.kt, TurnDetector.kt just
landed for #24 — build on it, do not redo turn detection) and
`android/core-domain/src/main/java/com/vela/core/domain/VoiceTransport.kt`
and `EventStream.kt` for the existing seams to hook a classifier and
narration-while-waiting behavior into.

Implement:
- A classifier step (fast tier) that labels an incoming user turn as
  trivial vs real-work. If a real model call is out of scope for this
  environment, a deterministic/rule-based classifier behind the same
  interface is an acceptable MVP — name the simplification in DONE.json.
- Real-work turns hand off to the slow tier (vela-agentd link — check
  `android/host-tools/**` and `voice-worker/**` for the existing agentd
  bridge) and the fast tier continues narrating real progress events
  (via EventStream) while the slow tier works, instead of the user hearing
  silence.
- Real tests: unit-test the classifier and the hand-off/narration state
  machine.

This is voice/core-domain-scoped. Do NOT touch `android/core-ui/**` or
`android/app/src/androidTest/java/com/vela/app/CardDeckSwipeTest.kt` — issue
#29 (card-deck UI) may be running in a sibling lane touching those files.

## File ownership

Own: `android/voice/**`, and may ADD (not restructure) to
`android/core-domain/src/main/java/com/vela/core/domain/VoiceTransport.kt` /
`EventStream.kt`. If a shared contract elsewhere needs a change, record as a
residual rather than editing outside these trees.

## Verification

Run: `./gradlew :voice:testDebugUnitTest :core-domain:testDebugUnitTest --console=plain`
from your worktree's `android/` directory. Report real pass counts from
`build/test-results/**/TEST-*.xml`, not "BUILD SUCCESSFUL". Do not regress
the 43 voice-module tests that landed with #24 (baseline: 43 passed, 0
failed) — re-verify that count yourself before you start as your true
baseline.

## Complete when

Complete when **either** the fast/slow-tier split is implemented (classifier
+ hand-off + narration-while-waiting) and verified with real passing tests,
**or** it is conclusively demonstrated this cannot be completed in this
environment — naming the blocker precisely (e.g. no real agentd link
reachable here). Items ending FAIL or BLOCKED are residuals, not failures of
the goal.

## Terminal states

PASS / FAIL-named / BLOCKED-named / PENDING-HUMAN.

## Time bound

90 minutes wall-clock, `--max-turns 40`. Exceeding the bound is a terminal
`BUDGET` state — commit and push whatever is real, write DONE.json, stop.

## KNOWN

- Env: Android Gradle project at `android/`. Use `./gradlew` from there.
- Baseline: voice module currently 43 passed, 0 failed (verify fresh
  yourself as your true starting baseline).
- Add `DONE.json` to `.gitignore` before writing it, if not already ignored.
- Commit early, push always.
- Never merge to main yourself — the orchestrator lands this.
