# Lane: fleet-f1-broker

## Working directory / branch / base
Work ONLY in this worktree: `../vela-lanes/fleet-f1-broker` on branch
`lane/fleet-f1-broker`, based on `be95269e`. Do not touch the main checkout or
any sibling worktree.

## Origin
Issue #43 (Fleet plane contract conformance D1-D5) — verification report on
#43 found D4/D5 unimplemented and explicitly Stage F1+ scope. Issue #17 (G5:
Fleet Execution & Dispatch epic). Spec:
`docs/designs/2026-08-24-vela-fleet-execution-plane.md`, §"Stage F1 — The
broker", **Lane F1.1 only** (`fleetd-broker` — registry, admission, ledger
writing, decision relay). Do NOT build F1.2 (fleet-worker) — the design doc
itself says not to batch F1.1 and F1.2 since F1.2 consumes the protocol F1.1
defines. That is a future lane.

## Scope / file ownership
Own exclusively: a new `services/fleetd-broker/**` directory (new service,
does not exist yet — no collision with anything else). Do not touch
`services/ledger/**` (owned by a sibling lane, `ledger-l1`),
`services/vela-agentd/**`, or any Android app code.

Implement, per the spec's Stage F1 / Lane F1.1 description and the D1-D5
contract in §8.3 / gates in §11.1:
1. Machine/worker registry + admission (a worker can register, broker tracks
   liveness/heartbeat).
2. Ledger writing — broker writes job state transitions to the ledger
   (calling the REST surface `services/ledger/` already exposes; treat that
   API as a frozen external contract, do not modify it here).
3. Decision relay — the round-trip for attention/decision events described in
   the design doc's D4/D5 mapping.
4. This is protocol-defining work that a future F1.2 (fleet-worker) lane will
   consume — document the protocol/API surface you land clearly (a README or
   doc comment in `services/fleetd-broker/`) since that's the interface
   contract for the next lane, not just internal implementation detail.

If real hardware/fleet-wide testing (Gate FG-1: ≥100 dispatches over
Tailscale at real fleet size; Gate FG-2: literal kill/SIGSTOP/sever-network
adversarial test) is out of reach in this worktree, that's expected — note it
as a named residual per the design doc's own admission that this is
Stage F1+ scope; do not fake or skip real unit/integration test coverage of
the broker's own logic in-process.

## Terminal condition
Complete when **either** every item above (1-4) reaches a terminal state, **or**
it is conclusively demonstrated the remainder cannot, naming the blocker for
each. Items ending FAIL or BLOCKED are residuals, not failures of this goal.

Terminal states per item: `PASS` / `FAIL-named` / `BLOCKED-named` /
`PENDING-HUMAN`.

## Verification
Real test suite for `services/fleetd-broker/` you create (match the test
runner convention already used in `services/ledger/` or
`services/vela-agentd/` — check both for the repo's Python testing
convention before picking one). Report actual pass/fail counts.

## Process discipline
- Commit early and often. `git push -u origin lane/fleet-f1-broker` after
  every commit (push always).
- Never merge to main yourself — the orchestrator (Ditto) does that.
- If you need an edit in a file you don't own (e.g. the ledger REST surface
  turns out to need a new field), record it as a residual in DONE.json
  instead of making it — flag it loudly, since `services/ledger/` has a
  sibling lane actively changing it this same cycle.
- Time bound: 120 minutes wall-clock (this is a new service from scratch —
  more scope than ledger-l1). Exceeding it is a terminal `BUDGET` state —
  commit and push what you have.

## DONE.json
Add `DONE.json` to `.gitignore` in this worktree before writing it (verify
it's covered by the repo root `.gitignore`, don't assume). Write it in the
worktree root as your final act:
```json
{
  "lane": "fleet-f1-broker",
  "session_id": "<this lane's own session id>",
  "verdict": "COMPLETE|BLOCKED|PARTIAL",
  "branch": "lane/fleet-f1-broker",
  "head": "<sha>",
  "pushed": true,
  "items": [{"id": "registry-admission", "state": "..."},
             {"id": "ledger-writing", "state": "..."},
             {"id": "decision-relay-D4-D5", "state": "..."},
             {"id": "protocol-doc-for-F1.2", "state": "..."}],
  "residuals": [],
  "pending_human": [],
  "suite": {"command": "<how to run>", "pass": 0, "fail": 0, "skip": 0}
}
```

## KNOWN
- Design doc: `docs/designs/2026-08-24-vela-fleet-execution-plane.md`.
- `services/ledger/` REST surface (C3) is the frozen contract you write
  against — do not modify it; if it's missing something, that's a residual
  naming the gap, not a reason to edit it.
- F0 (`SshFleetPlane`, already merged) remains the working fallback — this
  lane does not replace it, it builds the next stage alongside it.
