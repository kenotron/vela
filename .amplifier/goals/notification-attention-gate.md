# Goal: Notification rules gated strictly on attention.required (#47, epic #19)

## Outcome
"Progress updates never notify — construction-level guarantee, not a filter
applied at render time." Today there is no `attention.required` concept
anywhere in the domain model (verified: `grep -r attention android/` finds
nothing). This lane must:

1. Add an explicit `requiresAttention: Boolean` (or equivalent) field to the
   domain entry type that notifications are derived from
   (`LedgerRepository.LedgerEntry` in
   `android/core-domain/src/main/java/com/vela/core/domain/LedgerRepository.kt`),
   so an entry is attention-required BY CONSTRUCTION at creation time, not by
   a downstream filter guessing from status/category.
2. Add a notification-emission path (new file(s), e.g. under a new
   `android/core-domain/.../notification/` package or wherever fits the
   existing module layout) that can ONLY emit a notification for entries
   where `requiresAttention == true` — make it a type-level guarantee (e.g.
   the notifier's input type doesn't even expose non-attention entries) not
   an `if` check that could be bypassed.
3. A "progress" entry (e.g. a status update on existing work, not requiring
   any user action) must be structurally incapable of reaching the notifier.

## Complete when
Either every item below reaches a terminal state, **or** it is conclusively
demonstrated the remainder cannot, naming the blocker for each. Items ending
FAIL or BLOCKED are residuals, not failures of the goal.

- [ ] `requiresAttention` (or equivalent) field added to the domain entry
      type, set at construction.
- [ ] Notifier/gate component whose input type structurally excludes
      non-attention entries (e.g. takes a wrapper/sealed type that can only be
      constructed from an attention-required entry, or an interface method
      that's simply unreachable for progress entries) — not a boolean check
      that a future caller could skip.
- [ ] Unit tests proving: an attention-required entry reaches the notifier; a
      progress-only entry cannot be passed to the notifier at all (a
      compile-time or construction-time guarantee, demonstrated by a test that
      would fail to compile/construct if the guarantee were removed).
- [ ] Existing callers of `LedgerEntry` (SqliteLedgerRepositoryAdapter,
      SqliteLedgerRepository, ServerLedgerRepository, JobWire, CardDeck,
      QueueViewModel) updated to supply the new field without breaking
      existing tests — if any of these lives outside your ownership below,
      make the narrowest possible edit and record it as
      `needs_cross_lane_review` per the card-deck lane's precedent.

## Working directory / branch / base
- Worktree: `/home/ken/workspace/vela-lanes/notification-attention-gate`
- Branch: `goal/notification-attention-gate`
- Base SHA: c505ad7d8f48087992222b2ff2f4b26ed4752373
- Work ONLY in this worktree. Do not touch the main checkout or sibling worktrees.

## File ownership
Owned (edit freely):
- `android/core-domain/src/main/java/com/vela/core/domain/**`
- New notification package files (wherever you place them)
- New/updated test files under `android/core-domain/src/test/**`

Cross-lane (edit ONLY the minimum needed to keep the build green after your
`LedgerEntry` change, mark each such edit `needs_cross_lane_review: true` in
DONE.json, and describe exactly what changed and why):
- `android/app/src/main/java/com/vela/app/SqliteLedgerRepositoryAdapter.kt`
- `android/ledger/src/main/java/com/vela/ledger/**`
- `android/core-ui/src/main/java/com/vela/core/ui/CardDeck.kt`
- `android/app/src/main/java/com/vela/app/ui/queue/QueueViewModel.kt`

Do NOT touch `android/core-ui/src/main/java/com/vela/core/ui/ChatTranscript.kt`
or `android/app/src/main/java/com/vela/app/ui/chat/**` — another lane (#33) owns
those concurrently.

## Constraints
- Never merge to main yourself. Commit and push to your branch only.
- Commit early, push always (`git push -u origin goal/notification-attention-gate`
  on first commit).
- Unit tests only (no emulator). Run
  `./gradlew :core-domain:testDebugUnitTest :ledger:testDebugUnitTest :app:testDebugUnitTest :core-ui:testDebugUnitTest`
  and record actual BUILD SUCCESSFUL output + test counts in DONE.json.
- If adding the field breaks compilation in a file outside your ownership list
  above (one you were not told to touch), stop, record it as a residual
  naming the exact file and error, and do not touch it.
- Time bound: 90 minutes wall clock. Exceeding it is a terminal `BUDGET` state
  — commit and push what you have, write DONE.json with verdict PARTIAL naming
  what's left.

## DONE.json (write as your final act, in the worktree root — it is gitignored)
Fields: `lane, session_id, verdict (COMPLETE|BLOCKED|PARTIAL), branch, head,
pushed, items[], residuals[], pending_human[], suite`.
