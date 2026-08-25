# Goal: approval prompts surfaced in chat (visual + spoken) (#35)

Working directory: THIS worktree only. Do not touch the main checkout or sibling worktrees.
Branch: goal-batch/approval-prompts-chat
Base SHA: df8dc040

## Outcome

When an approval gate fires (an `AttentionCandidate`-worthy ledger entry that
represents a decision requiring the user's yes/no, per `#18`/`AttentionNotifier`
in `android/core-domain/src/main/java/com/vela/core/domain/notification/`),
the chat/transcript surface (`android/core-ui/.../ChatTranscript.kt` and
`android/app/.../ui/chat/ChatViewModel.kt`) must:

1. Render the approval prompt as a distinct message type in the transcript
   (not indistinguishable from a normal assistant message) — visually flagged,
   with the prompt text and available responses (approve/deny or equivalent).
2. Show the resolution once answered — whether the user answered by tapping in
   chat or (if a voice path already exists) by voice — as a follow-up entry in
   the same transcript, not a silently vanished prompt.
3. Be exercised by real unit tests (ViewModel-level and/or Compose semantics
   assertions using the existing `testTag`/`contentDescription` pattern
   already used in `ChatTranscript.kt`), not manual-only verification.

Do not invent a new approval *decision engine* — reuse whatever ledger/gate
type already exists (`AttentionCandidate`, `LedgerRepository.LedgerEntry`,
`requiresAttention`). This issue is about the chat-surface *presentation* of
an approval that the ledger already knows requires a decision, plus capturing
the user's response back into the transcript. If no wiring point exists yet
for "the ledger has a pending approval that needs surfacing in chat", add the
minimal seam (e.g. a new `TranscriptMessage` variant / sealed type + a
ChatViewModel method to post it and record the resolution) rather than a
larger subsystem.

## File ownership

Own:
- `android/core-ui/src/main/java/com/vela/core/ui/ChatTranscript.kt`
- `android/core-ui/src/test/java/com/vela/core/ui/**` (add tests here)
- `android/app/src/main/java/com/vela/app/ui/chat/ChatViewModel.kt`
- `android/app/src/test/java/com/vela/app/ui/chat/ChatViewModelTest.kt`

Do NOT touch (owned by sibling lane `dismissed-decided-ratio`):
- `android/core-domain/src/main/java/com/vela/core/domain/notification/**`
- `android/ledger/**`

If you need a change in those files to complete this goal, record it as a
named residual in `DONE.json` instead of editing them.

## Host capability limits

This host has no reliable physical Android device. Verify via JVM unit tests
(`./gradlew :core-ui:testDebugUnitTest :app:testDebugUnitTest`) run from
`android/`. Do not claim device verification you did not perform.

## Terminal states

Complete when **either** every item above reaches a terminal state, **or** it
is conclusively demonstrated the remainder cannot, naming the blocker for
each. Items ending FAIL or BLOCKED are residuals, not failures of the goal.
Terminal states: `PASS` / `FAIL-named` / `BLOCKED-named` / `PENDING-HUMAN`.

## Process discipline

- Commit early, push always (this repo has no remote configured for these
  worktrees — if push fails because there is no remote, that's fine, just
  commit; note it in `DONE.json`).
- Never merge to main yourself — the orchestrator merges.
- `DONE.json` is gitignored already (root `.gitignore` has `DONE.json`). Write
  it in the worktree root as your FINAL act, fields: `lane, session_id,
  verdict, branch, head, pushed, items[], residuals[], pending_human[],
  suite`. `verdict` is exactly one of `COMPLETE` / `BLOCKED` / `PARTIAL`.
- Time bound: 90 minutes wall clock / 40 turns. Exceeding it is a terminal
  `BUDGET` state in `DONE.json` — do not skip the final commit or DONE.json
  to "save time".
- Run `./gradlew :core-ui:testDebugUnitTest :app:testDebugUnitTest` from
  `android/` yourself before writing DONE.json and record the real pass count
  in `suite`, not just "BUILD SUCCESSFUL".

Complete when **either** every item reaches a terminal state, **or** it is
conclusively demonstrated the remainder cannot, naming the blocker for each.
