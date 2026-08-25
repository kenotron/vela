# Goal: One-time Auto first-connect provisioning documented (#52)

Working directory: THIS worktree only. Do not touch the main checkout or sibling worktrees.
Branch: goal-batch/auto-first-connect-doc
Base SHA: bcb98531

## Outcome

Google's Android Auto Play/ToS sign-in flow requires a human to be physically
present at the device's screen once per device the first time Auto connects.
This is a documentation-only issue: document this as a known, accepted manual
step, not something to engineer around.

Deliverable: a new doc (e.g. `docs/android-auto-first-connect.md`) that:
1. States the constraint plainly: first-connect requires a human at the
   physical screen; this cannot be automated away (Google-enforced).
2. Describes exactly what the one-time manual step looks like (sign-in
   prompt, permission grants expected) based on Android Auto's publicly
   documented first-run behavior — cite what's actually true, do not
   speculate about steps you can't confirm; where uncertain, say so
   explicitly rather than inventing detail.
3. Cross-references issue #50/#51 (the voice-first Auto flow lane, in
   flight as a sibling) so a future reader knows this is a precondition for
   testing that flow on a real device.

No code changes. This lane does not touch `android/**` at all.

## File ownership

Own: `docs/android-auto-first-connect.md` (new file) only.

Do NOT touch any file under `android/**` or any other `docs/**` file.

## Host capability limits

No physical Android Auto head unit available to verify the sign-in flow
firsthand. Base the document on Android Auto's publicly documented
first-connect behavior and say explicitly it is not independently verified
on real hardware in this environment.

## Terminal states

Complete when the doc is written and cross-referenced, or it is conclusively
demonstrated it cannot be (unlikely for a doc-only task) — name the blocker.
Terminal states: PASS / FAIL-named / BLOCKED-named / PENDING-HUMAN.

## Process

- Commit early, push always (`git push -u origin goal-batch/auto-first-connect-doc`).
- Never merge to main — the orchestrator merges.
- Add `DONE.json` to `.gitignore` if not already present, then write
  `DONE.json` in the worktree root as your FINAL act:
  `{lane, session_id, verdict(COMPLETE|BLOCKED|PARTIAL), branch, head, pushed,
  items[], residuals[], pending_human[], suite}`.
- Time bound: 45 minutes wall-clock (doc-only task). Exceeding it is a
  terminal BUDGET state.
