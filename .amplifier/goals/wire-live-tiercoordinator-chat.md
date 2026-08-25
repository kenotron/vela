# Goal: Wire live TierCoordinator into ChatViewModel (issue #61)

## Outcome
A real running-app call site in `android/app` constructs a live `TierCoordinator`
(concrete `UtteranceClassifier` + a concrete `SlowTierGateway` bridging to
`AmplifierToolLoopClient`) from actual mic input, and feeds its `handle()` output
into `ChatViewModel.ingestVoiceTurn(scope, utterance, events)` — closing the last
mile from #23 (tier split) + #33 (chat transcript) into one working voice-to-chat
path.

## Exit condition
Complete when **either**:
- A concrete `SlowTierGateway` implementation exists (backed by
  `AmplifierToolLoopClient`), `MainActivity.kt`/`VelaAppContainer.kt` in
  `android/app` instantiate a live `TierCoordinator` and route utterances into
  `ChatViewModel.ingestVoiceTurn`, and `./gradlew :app:testDebugUnitTest
  :voice:testDebugUnitTest` (and any other affected module tests) report BUILD
  SUCCESSFUL — record the exact test counts in DONE.json,
- **or** it is conclusively demonstrated this cannot be completed in scope,
  naming the specific blocker (e.g. a missing upstream API in
  `AmplifierToolLoopClient` that has no workaround) — record as BLOCKED-named,
  not a silent stop.

Two items, each with its own terminal state:
1. **SlowTierGateway impl** — PASS / FAIL-named / BLOCKED-named.
2. **MainActivity/VelaAppContainer wiring + ingestVoiceTurn call site** —
   PASS / FAIL-named / BLOCKED-named.

## SCOPE-OUTS
- No changes to `ChatViewModel.kt`'s existing `ingestVoiceTurn` signature or
  transcript-folding logic (already correct per #33) — call it, don't modify it,
  unless a genuinely required extension point is missing, in which case name it
  as a residual rather than redesigning the class.
- No requirement to run on a real physical device — an emulator
  (`vela-test-avd`) smoke check is sufficient evidence if time allows; it is not
  mandatory for PASS if unit tests + a manual code-path trace establish
  correctness.
- No product-direction decisions (#55/#56) are in scope — this is pure wiring.
- No requirement to implement Android Auto (#50) or notification/approval
  surfaces (#35) — those are separate issues.

## File ownership
- OWN: `android/app/src/main/java/com/vela/app/MainActivity.kt`
- OWN: `android/app/src/main/java/com/vela/app/VelaAppContainer.kt`
- OWN: new `SlowTierGateway` implementation file(s), under
  `android/voice/src/main/java/com/vela/voice/handoff/` or
  `android/app/src/main/java/com/vela/app/` (your call — pick one, keep tests
  alongside).
- DO NOT modify: `ChatViewModel.kt`, `TierCoordinator.kt`'s public contract (may
  read it, must not change its signature).
- If a needed edit falls outside these files, record it as a residual in
  DONE.json instead of making it.

## Working directory / branch
Work ONLY in your assigned worktree. Do not touch the main checkout or sibling
worktrees. Branch: `lane/wire-live-tiercoordinator-chat`, base SHA: current
`main` HEAD at launch time.

## Verification
- `./gradlew :app:testDebugUnitTest` and `:voice:testDebugUnitTest` (or
  equivalent module paths — check `settings.gradle.kts` for actual module
  names) must report BUILD SUCCESSFUL with test counts recorded.
- Commit early, push always.
- Never merge to main yourself — the orchestrator merges.

## Time bound
Wall-clock budget: 3 hours. Exceeding it is a terminal `BUDGET` state — commit
and push whatever is real, write DONE.json noting BUDGET, do not rush a fake
finish.

## DONE.json
Add `DONE.json` to `.gitignore` before writing it (if not already ignored).
Write `DONE.json` in the worktree root as your final act, with fields:
`lane, session_id, verdict (COMPLETE|BLOCKED|PARTIAL|BUDGET), branch, head,
pushed, items[] (with per-item terminal state), residuals[], pending_human[],
suite (command + result + counts)`.

## KNOWN
- Prior lane (#33) landed `ChatViewModel.ingestVoiceTurn` — unit-tested, causal
  order proven, do not re-derive this, just call it.
- `TierCoordinator.kt` and `TierCoordinatorTest.kt` already exist in
  `android/voice/src/main/java/com/vela/voice/handoff/` — read them first.
- `SlowTierGateway` is currently only an interface (residual from #23) — you
  are providing the first concrete implementation.
- `AmplifierToolLoopClient` already exists somewhere under `android/host-tools`
  or is referenced from `VelaAppContainer.kt` — find the existing instance
  rather than re-inventing a client.
- Baseline before this lane: `git log` shows 44aba835 as HEAD; `:core-ui :app
  testDebugUnitTest` passed 4/4 ChatViewModelTest at that commit.
