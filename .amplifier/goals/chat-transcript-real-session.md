# Goal: Text transcript surface backed by the same session as voice (#33, epic #15)

## Outcome
`ChatTranscript.kt` (android/core-ui) currently renders a **mock** `TranscriptMessage`
list with no real backing. Wire it to the actual voice/turn session state so
chat is a peer view onto the same session voice uses — not a separate
conversation. A user switching from voice to typing mid-session must lose no
context: the same turns that appear via voice narration must appear in the
chat transcript, in order, and a typed message from the user must be
indistinguishable in kind from a spoken one once in the transcript.

Ground truth for what "session" means here: read
`android/voice/src/main/java/com/vela/voice/handoff/TierCoordinator.kt` (just
landed on main) and `android/events/src/main/java/com/vela/events/C2EventClient.kt`
/ `ActivityFeed.kt` for the existing event/session model before designing the
binding. Do not invent a second session concept — bind to what's already there.

## Complete when
Either every item below reaches a terminal state, **or** it is conclusively
demonstrated the remainder cannot, naming the blocker for each. Items ending
FAIL or BLOCKED are residuals, not failures of the goal.

- [ ] `ChatTranscript.kt` renders from a real, injectable message source (no
      hardcoded mock list in the production path — the mock may remain as a
      `@Preview` fixture).
- [ ] `ChatViewModel.kt` (android/app/src/main/java/com/vela/app/ui/chat/)
      exposes a `StateFlow`/`Flow` of transcript messages sourced from the
      real session/turn state (TierCoordinator / voice turn events / whatever
      the existing event model already carries) — not synthetic data.
- [ ] A message typed by the user in chat and a turn spoken by voice both
      appear in the same transcript stream, in causal order.
- [ ] Unit test(s) proving: (a) messages from the real source render, (b) a
      chat-typed message is appended to the same stream a voice turn would
      append to.

## Working directory / branch / base
- Worktree: `/home/ken/workspace/vela-lanes/chat-transcript-real-session`
- Branch: `goal/chat-transcript-real-session`
- Base SHA: c505ad7d8f48087992222b2ff2f4b26ed4752373
- Work ONLY in this worktree. Do not touch the main checkout or sibling worktrees.

## File ownership
Owned (edit freely):
- `android/core-ui/src/main/java/com/vela/core/ui/ChatTranscript.kt`
- `android/app/src/main/java/com/vela/app/ui/chat/**`
- New test files under `android/app/src/test/java/com/vela/app/ui/chat/**`
- New test files under `android/core-ui/src/test/**` if needed

If you need to touch anything under `android/voice/**`, `android/events/**`,
`android/core-domain/**`, or `android/host-tools/**`: read-only. If a real
change there is required to complete this goal, **record it as a residual in
DONE.json and stop** — do not edit those files. Another lane (#47) is touching
core-domain/notifications territory concurrently; do not cross into it.

## Constraints
- Never merge to main yourself. Commit and push to your branch only.
- Commit early, push always (`git push -u origin goal/chat-transcript-real-session`
  on first commit).
- This sandbox cannot run instrumented/emulator tests. Unit tests only
  (`./gradlew :core-ui:testDebugUnitTest :app:testDebugUnitTest`). Record the
  actual BUILD SUCCESSFUL output and test counts in DONE.json, not just "passed".
- Time bound: 90 minutes wall clock. Exceeding it is a terminal `BUDGET` state
  — commit and push what you have, write DONE.json with verdict PARTIAL naming
  what's left, do not keep working past the bound.

## DONE.json (write as your final act, in the worktree root — it is gitignored)
Fields: `lane, session_id, verdict (COMPLETE|BLOCKED|PARTIAL), branch, head,
pushed, items[], residuals[], pending_human[], suite`.
