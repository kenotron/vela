# Goal: Semantic turn detection (#24)

Working directory: THIS worktree only. Do not touch the main checkout or
sibling worktrees. Branch: `goal/voice-turn-detection`. Base SHA: pinned at
launch (see manifest).

## Outcome

Issue #24 (kenotron/vela): "Semantic turn detection (never a silence-threshold
VAD)". Under epic #13 (G1 Voice-First Conversational Core), V2 in
docs/designs — the #1 complaint of voice products surveyed is mistimed
interruption of thinking pauses. Use a purpose-built turn-detection signal,
not a fixed silence timer.

Read `docs/designs/2026-08-16-vela-chief-of-staff-rebuild.md` section on voice
(V1-V8) for full context before starting. Read the existing `android/voice`
module first — it already has LiveKitVoiceTransport, PttController,
EarconPlayer, and barge-in support (issues #25-#28, already closed) which you
must not regress.

Design then implement a `TurnDetector` (or equivalent) abstraction in
`android/voice` that:
- Classifies end-of-turn using more than raw silence duration (e.g. semantic/
  prosodic cues surfaced by the voice vendor transport, or a hybrid VAD +
  minimum-content heuristic if no semantic signal is available from the
  current vendor integration — name explicitly which you used and why).
- Integrates with LiveKitVoiceTransport / barge-in without regressing the
  existing BargeIn tests.
- Is unit-testable without a real device or live vendor connection (fake/fixture
  the transport signal).

If the current voice vendor (LiveKit) exposes no semantic end-of-turn signal
today, that is a legitimate BLOCKED-named finding for the "purpose-built model"
part — in that case implement the best available approximation (e.g. a
hybrid: minimum trailing silence + content-completeness heuristic, clearly
distinguished from a naive fixed-timeout VAD) and name the real semantic-model
integration as a residual, not a failure.

## File ownership

Own: `android/voice/**` only. If you need changes outside `android/voice`
(e.g. app-level wiring), record as a residual instead of editing — do not
cross into `android/app` or other modules.

## Verification

Run `./gradlew :voice:testDebugUnitTest` from `android/` inside your worktree.
All existing voice tests must still pass; add new tests for the turn detector.
Record the real pass/fail counts, not "BUILD SUCCESSFUL" alone.

## Complete when

Complete when **either** the turn detector is implemented, tested, and wired
in a way that demonstrably replaces the "silence-threshold VAD only" gap
without regressing existing voice tests, **or** it is conclusively demonstrated
this cannot be done in this environment — naming the blocker (e.g. "vendor
gives us no semantic signal and no local model is available in this
sandbox") precisely. Items ending FAIL or BLOCKED are residuals, not failures
of the goal.

## Terminal states

PASS / FAIL-named / BLOCKED-named / PENDING-HUMAN.

## Time bound

Wall-clock 90 minutes. Exceeding it is a terminal `BUDGET` state — commit and
push what you have, do not rush the last increment.

## On finish

Commit early, push always (never merge to main — the orchestrator merges).
Add `DONE.json` to this repo's `.gitignore` if not already present, then write
`DONE.json` in the worktree root as your final act with fields: `lane,
session_id, verdict (COMPLETE|BLOCKED|PARTIAL), branch, head, pushed, items[],
residuals[], pending_human[], suite`.
