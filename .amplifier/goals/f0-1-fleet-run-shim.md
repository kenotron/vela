# Goal: F0.1 fleet-run-shim

Working directory: THIS worktree only. Do not touch the main checkout or sibling worktrees.
Branch: lane/f0-1-fleet-run-shim
Base SHA: 9789ee6873bab5e37e8184f83087e7c91ce05ccd

## Outcome

Implements GitHub issue #58 (kenotron/vela): build `velafleet-run`, a supervisor
shim + JSONL event protocol + adapters for `amplifier-agent` and `shell`, per
docs/designs/2026-08-24-vela-fleet-execution-plane.md §4.3 and §10 (Stage F0,
Lane F0.1).

Read the design doc sections §4.2 (worker responsibilities), §4.3 (job wrapper
— where muxterm fits), §4.4 (job spec / D1), §5.1-5.2 (dispatch and progress
flows) before writing any code. This is the foundation every later fleet-plane
stage builds on — get the JSONL event shape right.

**File ownership:** `fleet/run/`, `docs/fleet/JOB_EVENTS.md`. Do not touch
`android/`, `services/vela-agentd/`, or any other top-level directory. If you
need something there, record it as a residual and stop.

## Done when

**Complete when either** every item below reaches a terminal state, **or** it
is conclusively demonstrated the remainder cannot, naming the blocker for each.
Items ending FAIL or BLOCKED are residuals, not failures of the goal.

1. `velafleet-run` supervisor shim exists in `fleet/run/`, launchable as a
   subprocess that wraps a job command.
2. JSONL event protocol implemented: emits structured events to
   `~/.vela/jobs/<job_id>/events.jsonl` per the design doc's shape (at minimum:
   `progress`, `attention`, `completed`/`failed` event types).
3. Adapter for `amplifier-agent` jobs and a `shell` adapter.
4. **Proof (this is Spike F-2's pass criterion, run for real):** launch a real
   `amplifier-agent` job inside a muxterm pane via this shim. Produce a
   complete, well-formed `events.jsonl` including at least one `progress` and
   one `attention` event, while the PTY simultaneously renders normal
   interactive output. Capture the actual events.jsonl content as evidence.
5. `docs/fleet/JOB_EVENTS.md` documents the event schema for later lanes
   (F0.2, F1.1, F1.2) to consume.
6. Unit tests for the shim and adapters, run and passing (report exact command
   and pass/fail counts, not "tests pass").

Terminal states per item: `PASS` / `FAIL-named` / `BLOCKED-named` /
`PENDING-HUMAN`.

## Constraints

- Commit early, push always (this repo has an `origin` remote).
- Never merge to main — the orchestrator (Ditto) does that.
- State any host capability limits you hit plainly (e.g. no muxterm binary
  available in this environment — say so, don't fake the proof).
- Add `DONE.json` to `.gitignore` before writing it.
- Write `DONE.json` in the worktree root as your final act. Fields: `lane,
  session_id, verdict, branch, head, pushed, items[], residuals[],
  pending_human[], suite`. `verdict` is exactly one of `COMPLETE` / `BLOCKED` /
  `PARTIAL`.
- Time bound: 90 minutes wall-clock. Exceeding it is a terminal `BUDGET` state,
  not a reason to rush or skip the final commit/push.
