# Goal: Decision round-trip resumes the fleet job (#32)

Working directory: THIS worktree only. Do not touch the main checkout or
sibling worktrees. Branch: `goal/fleet-decision-resume`. Base SHA: pinned at
launch (see manifest).

## Outcome

Issue #32 (kenotron/vela): "Decision round-trip resumes the fleet job". Under
epic #14 (G2 Attention Queue & Decision Surface / Card Deck), also touches G4
(ledger) and G5 (fleet execution). A swipe decision must actually POST to
`/ledger/jobs/{id}/decision` and cause the fleet plane to resume — not just
update local UI state.

Read `docs/designs/2026-08-24-vela-server-ledger.md` and
`docs/designs/2026-08-24-vela-fleet-execution-plane.md` first. Read
`services/ledger` and `services/fleetd-broker` current code — D1-D5 residuals
(#43) and the ledger zero-lost-events work (#38) landed recently
(commits 9357b53e, 86b5d374 on main) — build on that, do not redo it.

Implement the actual decision round-trip:
- `/ledger/jobs/{id}/decision` endpoint (if not already present — check first)
  accepts a decision, persists it durably, and notifies/wakes the fleet plane
  (fleetd-broker) so the job actually resumes execution rather than just
  flipping a status flag.
- Cover with a real integration-style test: post a decision, assert the fleet
  job transitions out of its waiting state and the ledger reflects it.

This is backend-scoped (services/ledger + services/fleetd-broker). Do NOT
implement or wire the Android card-deck UI itself (#29) — that is separate;
if the UI needs a specific contract from you, name it as a residual/handoff
note in DONE.json rather than building it.

## File ownership

Own: `services/ledger/**`, `services/fleetd-broker/**` only. If tests reveal a
shared contract file elsewhere needs a change, record as residual, do not
edit outside these two trees.

## Verification

Run the existing service test suites (uv run pytest) for both services from
your worktree and report real pass counts. Do not regress the D1-D5 tests
(fanout-all, reconnect-reconciliation, durable-broker-store) or the ledger
zero-lost-events tests.

## Complete when

Complete when **either** the decision round-trip is implemented and verified
end-to-end with a real test demonstrating a posted decision resumes the fleet
job, **or** it is conclusively demonstrated this cannot be completed in this
environment — naming the blocker precisely (e.g. needs a live multi-worker
fleet only available on hardware). Items ending FAIL or BLOCKED are
residuals, not failures of the goal.

## Terminal states

PASS / FAIL-named / BLOCKED-named / PENDING-HUMAN.

## Time bound

Wall-clock 90 minutes. Exceeding it is a terminal `BUDGET` state — commit and
push what you have, do not rush the last increment.

## On finish

Commit early, push always (never merge to main — the orchestrator merges).
Add `DONE.json` to this repo's `.gitignore` if not already present, then write
`DONE.json` in the worktree root as your final act with fields: `lane,
session_id, verdict (COMPLETE|BLOCKED|PARTIAL), branch, head, pushed, items[],
residuals[], pending_human[], suite`.
