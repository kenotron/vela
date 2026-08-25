# Goal: fleet-f1.2-residuals — close the code-level gaps in fleetd-broker (#43)

## Context
`services/fleetd-broker` (Stage F1 Lane F1.1) landed at main @ 4d4bf2ff:
31/31 tests passing. Issue #43 (D1-D5 fleet plane contract conformance)
was re-triaged and left open with these BLOCKED-named residuals recorded
in the merge commit and issue comments:

- **fanout-strategy-all-wiring**: multi-target fan-out dispatch is not
  wired into `POST /fleet/dispatch` (only single-target
  least_loaded/any/pinned path exists).
- **reconnect-reconciliation**: a reconnecting worker's job-set is not
  diffed/reconciled against the ledger.
- **durable-broker-store**: the broker is in-memory only; a restart loses
  all worker/job bindings (design doc §4.1 calls for a durable local
  cache).

Two further residuals — **gate-FG-1-fleet-scale-dispatch** (>=100 real
dispatches) and **gate-FG-2-adversarial-kill-test** (real kill test against
a real worker) — genuinely require real hardware/Tailscale fleet scale and
are OUT OF SCOPE for this lane. Name them BLOCKED-named again, do not
attempt to fake them into a false PASS.

Read `docs/designs/2026-08-24-vela-fleet-execution-plane.md` (§4.1, §8.3
D1-D5 table, §11.1 gates) and `services/fleetd-broker/README.md` before
writing code.

## Working directory
Worktree `vela-lane-fleet-f1.2-residuals`, branch `lane/fleet-f1.2-residuals`,
base SHA `4d4bf2ff067cf30b4836e56fa61bb0adef8f218f`. Work ONLY here. Do not
touch the main checkout or sibling worktrees.

## File ownership
- Owned: `services/fleetd-broker/**` only.
- Do NOT touch `android/**`, `services/ledger/**` — out of scope; a sibling
  lane may be touching `android/**` concurrently.

## Host capability limits
No real fleet, no real worker machines, no Tailscale network reachable
from this environment. Verify fan-out and reconciliation logic against
deterministic in-process fakes (multiple fake worker registrations, fake
heartbeats, fake job sets) — same style as the existing `test_registry.py`
/ `test_worker_events.py`. Durable-store must be verified with a real
process-restart-equivalent test (close and reopen the store at the same
path, like `services/ledger`'s `test_durability_restart.py` pattern) —
that is genuinely buildable here, do it for real, don't fake it.

## Scope
1. **fanout-strategy-all-wiring**: wire the existing `all` fan-out target
   strategy (if partially present) or implement it end-to-end into
   `POST /fleet/dispatch` — dispatching to every currently-reachable
   registered worker, not just one. Test with 3+ fake workers.
2. **reconnect-reconciliation**: when a worker reconnects (re-registers
   after a gap), diff its reported job-set against the ledger's view for
   that worker and reconcile (resume tracking in-flight jobs, mark
   vanished jobs appropriately) rather than silently dropping prior state.
3. **durable-broker-store**: replace (or back) the in-memory registry/job
   binding store with a durable local store (SQLite, same durability
   posture as `services/ledger` — WAL + synchronous=FULL, see that
   service's README for the pattern) so a broker restart does not lose
   worker/job bindings. Prove it with a real close-and-reopen test.

## Verification
`cd services/fleetd-broker && uv run pytest -q` must pass. Baseline before
this lane: **31 passed, 0 failed**. Report the exact after-count. Do not
report success from a stale run — re-run after your last commit.

## Terminal states
PASS / FAIL-named / BLOCKED-named / PENDING-HUMAN, one per residual item
(fanout-strategy-all-wiring, reconnect-reconciliation, durable-broker-store,
gate-FG-1, gate-FG-2). Complete when **either** every item reaches a
terminal state, **or** it is conclusively demonstrated the remainder
cannot, naming the blocker for each (gate-FG-1/FG-2 are expected to land as
BLOCKED-named — that is a correct, honest terminal state, not a failure).

## Time bound
Wall clock 90 minutes, max 60 turns. Exceeding it is a `BUDGET` terminal
state — commit and push whatever is real.

## Commit discipline
Commit early, commit often, push every commit (branch
`lane/fleet-f1.2-residuals` on `origin`). Never merge to main.

## DONE.json
`DONE.json` is already gitignored at repo root. Write it in the worktree
root as your final act:
```json
{
  "lane": "fleet-f1.2-residuals",
  "session_id": "<this session's id>",
  "verdict": "COMPLETE|BLOCKED|PARTIAL",
  "branch": "lane/fleet-f1.2-residuals",
  "head": "<git rev-parse HEAD>",
  "pushed": true,
  "items": [{"id": "fanout-strategy-all-wiring", "state": "PASS|FAIL-named|BLOCKED-named", "note": "..."}, ...],
  "residuals": ["gate-FG-1-fleet-scale-dispatch", "gate-FG-2-adversarial-kill-test"],
  "pending_human": [],
  "suite": {"before": "31 passed", "after": "..."}
}
```

## KNOWN
- Baseline: `31 passed, 0 failed` as of base SHA.
- Sibling service `services/ledger` already solved the durable-SQLite
  pattern (WAL + synchronous=FULL) — copy that posture, don't reinvent.
- `services/fleetd-broker/README.md` documents the current registry,
  ledger_client, and worker_events modules — read it first.
