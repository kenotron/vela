# Goal: ledger-l2-android — Stage L2 Android ledger client (#30, #37, #38)

## Context
`services/ledger` (server-owned ledger, C3 REST + SSE) is built and merged
(main @ 4d4bf2ff, 14/14 tests passing). `android/ledger` has a local SQLite
mirror (`SqliteLedgerRepository` implementing `LedgerRepository` from
`android/core-domain`) but NO client that talks to the server. That is the
named blocker on three open issues:

- **#30** Attention query backing the deck — server `GET /ledger/attention`
  already works; nothing on Android calls it.
- **#37** Phone-local mirror for offline read — the mirror (SQLite) exists;
  it is never reconciled against the server.
- **#38** Zero-lost-events guarantee — its acceptance test
  (`test_cross_layer_zero_lost_events`, design doc §6.1-6.3) needs a real
  Android client talking to a real (or realistically faked) server; does
  not exist yet.

Read `docs/designs/2026-08-24-vela-server-ledger.md` and
`services/ledger/README.md` (§ schema mapping table) before writing code —
the wire schema is already pinned there.

## Working directory
Worktree `vela-lane-ledger-l2-android`, branch `lane/ledger-l2-android`,
base SHA `4d4bf2ff067cf30b4836e56fa61bb0adef8f218f`. Work ONLY here. Do not
touch the main checkout or sibling worktrees.

## File ownership
- Owned: `android/ledger/**` (new `ServerLedgerRepository`, HTTP client,
  sync/reconcile logic, its tests).
- Owned, minimal-touch only: `android/app/**` DI wiring (e.g.
  `VelaAppContainer`) to construct/select the new repository — do not
  redesign the container, just wire it in.
- Do NOT touch `android/core-domain/**` interfaces unless a genuine gap is
  found; if so, record it as a residual instead of guessing an API change
  that ripples into files you don't own.
- Do NOT touch `services/ledger/**` or `services/fleetd-broker/**` — those
  are server-side and out of this lane's scope (a sibling lane may be
  touching fleetd-broker concurrently).

## Host capability limits
No real Android device or running emulator is guaranteed available in this
environment. Use JVM unit tests against a fake/mock HTTP layer (e.g.
OkHttp `MockWebServer` or a hand-rolled fake `Ktor`/`HttpClient` engine —
whichever this module already depends on) to prove the client's wire
behavior and reconciliation logic. Do NOT claim device verification you
did not perform; name it as a residual instead (`android-tester` device
verification is a separate future lane's job).

## Scope
1. Implement `ServerLedgerRepository` (or equivalently named) implementing
   the `LedgerRepository` interface, backed by HTTP calls to the C3 API
   (`GET /ledger/jobs`, `GET /ledger/jobs/{id}`, `PATCH /ledger/jobs/{id}`,
   `POST /ledger/jobs/{id}/decision`, `GET /ledger/attention`), matching
   the wire schema documented in `services/ledger/README.md`.
2. Wire the attention query (`GET /ledger/attention`) through so a caller
   can observe `attention.required == true` jobs — this is #30's actual
   acceptance criterion.
3. Implement a reconcile/sync path between the local SQLite mirror
   (`SqliteLedgerRepository`) and the server client so the phone can read
   offline and catch up when reconnected (#37's actual acceptance
   criterion) — decide and document the merge/conflict rule explicitly
   (e.g. server `server_authoritative_version` wins), don't leave it
   implicit.
4. Write the cross-layer zero-lost-events test named in #38
   (§6.1-6.3 of the design doc) using a fake server (MockWebServer or
   equivalent) standing in for `services/ledger` — a real dual-process
   integration test is a residual if genuinely not buildable in this
   environment, but attempt the fake-server version first; it is very
   likely buildable.
5. Wire the new repository into `android/app`'s DI container behind
   whatever selection makes sense (e.g. server-backed when a base URL is
   configured, local-only otherwise) — minimal, not a redesign.

## Verification
- `cd android && ./gradlew :ledger:testDebugUnitTest` (and `:app:` tests if
  touched) must pass. Record the before/after test counts explicitly.
- Cite the specific test name(s) that satisfy #30, #37, #38's acceptance
  criteria in your final report — do not just say "tests pass".

## Terminal states
PASS / FAIL-named / BLOCKED-named / PENDING-HUMAN, one per item (#30, #37,
#38's Android-side portion). Complete when **either** every item reaches a
terminal state, **or** it is conclusively demonstrated the remainder cannot,
naming the blocker for each. Items ending FAIL or BLOCKED are residuals,
not failures of this goal.

## Time bound
Wall clock 90 minutes, max 60 turns. Exceeding it is a `BUDGET` terminal
state — commit and push whatever is real, do not rush the last increment.

## Commit discipline
Commit early, commit often, push every commit (branch
`lane/ledger-l2-android` on `origin`). Never merge to main — the
orchestrator does that.

## DONE.json
`DONE.json` is already gitignored at repo root. Write it in the worktree
root as your final act:
```json
{
  "lane": "ledger-l2-android",
  "session_id": "<this session's id>",
  "verdict": "COMPLETE|BLOCKED|PARTIAL",
  "branch": "lane/ledger-l2-android",
  "head": "<git rev-parse HEAD>",
  "pushed": true,
  "items": [{"id": "#30", "state": "PASS|FAIL-named|BLOCKED-named", "note": "..."}, ...],
  "residuals": ["..."],
  "pending_human": [],
  "suite": {"before": "...", "after": "..."}
}
```

## KNOWN
- Server wire schema: `services/ledger/README.md` schema mapping table.
- Server-side already PASS: `GET /ledger/attention`, `record_decision`
  terminal-state guard, `server_authoritative_version`.
- Prior lane `ledger-l1` classified #30/#37/#38 BLOCKED specifically
  because this Android client didn't exist — read its terminal
  classification in `services/ledger/README.md` before starting.
