# Lane: ledger-l1

## Working directory / branch / base
Work ONLY in this worktree: `../vela-lanes/ledger-l1` on branch `lane/ledger-l1`,
based on `be95269e`. Do not touch the main checkout or any sibling worktree.

## Origin
Issue #36 (G4: Durable Work Ledger — server-owned ledger as source of truth).
Spec: `docs/designs/2026-08-24-vela-server-ledger.md`, §12 "Stage L1 — Server-side
additive changes (1 lane, small isolated changes to services/ledger/)".

## Scope / file ownership
Own exclusively: `services/ledger/**`. Do not touch Android app code
(`app/**` or similar), `services/vela-agentd/**`, `services/fleetd-broker/**`
(does not exist yet — not yours), or any file under `.amplifier/`.

Implement, per the spec:
1. §4.3 — expose `server_authoritative_version` on the wire (`Job` model).
2. §7.1/§7.2 — cost accounting fields on ledger jobs (issue #39), with the
   accumulation semantics the spec flags as underspecified in §7.2 — if you
   must make a judgment call, note it explicitly as a residual, do not
   silently pick one and hide it.
3. §9 — the attention query backing the deck (`attention.required == true`)
   (issue #30), including the REST endpoint / query parameter the spec
   describes.
4. §6 — whatever server-side pieces are needed for the zero-lost-events
   guarantee (issue #38) that are honestly server-side scope (not the
   cross-layer Android verification — that's Stage L2/L3, not yours).

Read §17-cited "what already exists" section (§1.1) first — do not re-derive
what's already built in `services/ledger/`.

## Terminal condition
Complete when **either** every item above (1-4) reaches a terminal state, **or**
it is conclusively demonstrated the remainder cannot, naming the blocker for
each. Items ending FAIL or BLOCKED are residuals, not failures of this goal.

Terminal states per item: `PASS` / `FAIL-named` / `BLOCKED-named` /
`PENDING-HUMAN`.

## Verification
- Real test suite for `services/ledger/` (pytest or whatever it uses — check
  existing test runner). Report actual pass/fail counts, not "tests pass".
- No skipped/disabled tests counted as evidence.

## Process discipline
- Commit early and often. `git push -u origin lane/ledger-l1` after every
  commit (push always).
- Never merge to main yourself — the orchestrator (Ditto) does that.
- If you need an edit in a file you don't own, record it as a residual in
  DONE.json instead of making it.
- Time bound: 90 minutes wall-clock. Exceeding it is a terminal `BUDGET`
  state — commit and push what you have, do not rush the last item to "look"
  done.

## DONE.json
Add `DONE.json` to `.gitignore` in this worktree before writing it (it is
already gitignored at repo root — verify, don't assume). Write it in the
worktree root as your final act:
```json
{
  "lane": "ledger-l1",
  "session_id": "<this lane's own session id>",
  "verdict": "COMPLETE|BLOCKED|PARTIAL",
  "branch": "lane/ledger-l1",
  "head": "<sha>",
  "pushed": true,
  "items": [{"id": "server_authoritative_version", "state": "PASS|FAIL-named|BLOCKED-named"},
             {"id": "cost-accounting-#39", "state": "..."},
             {"id": "attention-query-#30", "state": "..."},
             {"id": "zero-lost-events-server-side-#38", "state": "..."}],
  "residuals": [],
  "pending_human": [],
  "suite": {"command": "<how to run>", "pass": 0, "fail": 0, "skip": 0}
}
```

## KNOWN
- `services/ledger/` already has a working implementation (prior lane 2.1) —
  read it before writing anything.
- Design doc: `docs/designs/2026-08-24-vela-server-ledger.md`.
