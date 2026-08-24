# Goal: F0.2 fleet-ssh-dispatch

Working directory: THIS worktree only. Do not touch the main checkout or sibling worktrees.
Branch: lane/f0-2-fleet-ssh-dispatch
Base SHA: 7cf2a74f7e5ce7925326bc8858f40dad58ccda16

## Outcome

Implements GitHub issue #59 (kenotron/vela): `SshFleetPlane` behind the existing
`FleetPlane` interface; JSONL tail over SSH; ledger PATCH from `vela-agentd`'s
host. Per docs/designs/2026-08-24-vela-fleet-execution-plane.md §9.1 (Candidate
B / milestone F0) and §10 (Stage F0, Lane F0.2).

This lane CONSUMES the JSONL event protocol built by F0.1 (merged to main at
7cf2a74f, in `fleet/run/internal/events/` — read `docs/fleet/JOB_EVENTS.md` for
the schema before writing any code). Do not redefine or fork that protocol;
extend/reuse it.

**File ownership:** `services/vela-agentd/.../fleet/` (create as needed),
`android/app/.../VelaAppContainer.kt` (one-line wiring only — do not touch
anything else under `android/`). If you need changes elsewhere, record a
residual and stop.

## Done when

**Complete when either** every item below reaches a terminal state, **or** it
is conclusively demonstrated the remainder cannot, naming the blocker for each.
Items ending FAIL or BLOCKED are residuals, not failures of the goal.

1. `SshFleetPlane` implemented behind the existing `FleetPlane` interface (find
   it — likely a Kotlin interface already referenced by `dispatch_to_fleet`
   or `VelaAppContainer.kt`; if no such interface exists yet, that itself is a
   BLOCKED finding to report, not something to invent unilaterally).
2. Dispatch launches `velafleet-run` (from F0.1) over SSH on a real or
   documented-stub second machine, tails its `events.jsonl` over the SSH
   session.
3. Ledger PATCH issued from `vela-agentd`'s host as job events arrive.
4. One-line wiring in `VelaAppContainer.kt` to use `SshFleetPlane` instead of
   `StubFleetPlane` (or whatever the current default is) — confirm this is
   genuinely one line; if not, report why and what it actually took.
5. **Proof:** a voice-initiated (or directly-invoked, if voice harness isn't
   available in this environment — say so) `dispatch_to_fleet` runs a real job
   on a real second machine (use whatever second SSH-reachable host is
   available in this environment, or a local SSH-to-localhost stand-in if
   nothing else is reachable — report which); progress appears in the card
   deck (or its backing data source, if the UI harness isn't runnable headless
   — say so); the job's completion updates the ledger. Measure p99 dispatch
   latency over your test runs and report it honestly even if it's >1s — F0 is
   not claiming D2 (that's Stage F1 / issue #43).
6. Unit/integration tests for `SshFleetPlane`, run and passing — report exact
   command and pass/fail counts.

Terminal states per item: `PASS` / `FAIL-named` / `BLOCKED-named` /
`PENDING-HUMAN`.

## Constraints

- Commit early, push always (this repo has an `origin` remote).
- Never merge to main — the orchestrator (Ditto) does that.
- State any host capability limits plainly (no second real machine reachable,
  no voice harness, etc.) rather than faking the proof.
- Add `DONE.json` to `.gitignore` before writing it.
- Write `DONE.json` in the worktree root as your final act. Fields: `lane,
  session_id, verdict, branch, head, pushed, items[], residuals[],
  pending_human[], suite`. `verdict` is exactly one of `COMPLETE` / `BLOCKED` /
  `PARTIAL`.
- Time bound: 90 minutes wall-clock (5400s). Exceeding it is a terminal
  `BUDGET` state, not a reason to rush or skip the final commit/push.
