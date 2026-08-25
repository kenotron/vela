# Goal: Android Auto voice-first flow (#50 + #51)

Working directory: THIS worktree only. Do not touch the main checkout or sibling worktrees.
Branch: goal-batch/auto-voice-session-flow
Base SHA: bcb98531

## Outcome

Issues #50 and #51 are treated as ONE lane because #51 (instrumentation) must
be built in from day one per its own issue text, not bolted on after #50
lands — same files, same session-lifecycle code path.

1. (#50) Implement a hands-free voice session flow scoped to Android Auto: a
   session starts (e.g. triggered by an Auto-surface entry point / a
   CarAppService-style hook — no physical Auto head unit or emulator is
   available in this environment, so this is a car-surface-shaped module
   built and unit-tested on the JVM, NOT verified on real Auto hardware),
   routes through the existing `voice`/`TierCoordinator` pipeline
   (`android/voice/**`) the same way the phone chat surface already does
   (see `wire-live-tiercoordinator-chat`, already landed), and completes
   (session end / result surfaced).
2. (#51) Instrument from day one: every Auto-initiated voice session must
   emit a recordable "auto session started" / "auto session completed" event
   — a simple counter/log or a small `AutoVoiceSessionTracker` (your naming)
   is sufficient scope. No dashboard needed; a queryable count with tests is
   the deliverable, matching the product-truth-signal intent of #51 (this
   metric will be watched post-launch to tell if the car use case is real).
3. Do not build a full Android Auto app/manifest integration if that requires
   host capabilities this box doesn't have (e.g. Auto emulator/DHU). Build
   the session-flow + instrumentation logic as testable Kotlin in a new
   `android/auto` module (or under `core-domain` if a full module is
   overkill — your call, state which and why), wired to the real voice
   pipeline, not a stub pipeline. If genuine Auto-surface wiring (manifest,
   CarAppService entrypoint) can be added safely without a working Auto
   target to verify against, do so and note it is unverified on-device.

## File ownership

Own (new territory, no existing lane touches this):
- `android/auto/**` (new module) OR `android/core-domain/src/main/java/com/vela/core/domain/auto/**`
  (pick one, state which in DONE.json)
- `android/settings.gradle.kts` (only the one line adding your new module, if used)
- Corresponding test directories under whichever path you choose

Do NOT touch: `android/app/**`, `android/core-ui/**`,
`android/core-domain/**/notification/**` (owned by sibling lane
`attention-rule-examples-tuning`).

## Host capability limits

No physical Android Auto head unit or DHU emulator available. Verify via JVM
unit tests only. Do not claim on-device or Auto-emulator verification you did
not perform — say explicitly this is unverified on real Auto hardware.

## Terminal states

Complete when **either** both items above reach a terminal state, **or** it is
conclusively demonstrated the remainder cannot, naming the blocker for each.
Terminal states: PASS / FAIL-named / BLOCKED-named / PENDING-HUMAN.

## Process

- Commit early, push always (`git push -u origin goal-batch/auto-voice-session-flow`).
- Never merge to main — the orchestrator merges.
- Add `DONE.json` to `.gitignore` if not already present, then write
  `DONE.json` in the worktree root as your FINAL act:
  `{lane, session_id, verdict(COMPLETE|BLOCKED|PARTIAL), branch, head, pushed,
  items[], residuals[], pending_human[], suite}`.
- Time bound: 3 hours wall-clock. Exceeding it is a terminal BUDGET state.
