# vela-ledger-service

> **Lane ledger-l1 terminal status (issue #36, Stage L1, design doc §12)** — committed
> at this SHA so the classification travels with the code, not only in the gitignored
> local `DONE.json`:
>
> | Item | State | Note |
> |---|---|---|
> | 1. `server_authoritative_version` on the wire (§4.3) | **PASS** | New work this lane: `Job.version`, sourced from `server_authoritative_version`. Tested. |
> | 2. Cost accounting fields (§7.1/§7.2, #39) | **PASS** | Fields already existed; this lane resolved the §7.2 accumulation ambiguity explicitly (see "Cost accounting contract" below) and documented it — the open judgment call the spec required. |
> | 3. Attention query backing the deck (§9, #30) | **BLOCKED-named** | `GET /ledger/attention` was already fully implemented and tested *before* this lane; no server-side gap found. The issue itself needs Android wiring (Stage L2, `ServerLedgerRepository`), which does not exist yet and is outside this lane's `services/ledger/**` ownership. Not resolvable from this lane. |
> | 4. Zero-lost-events, server-side scope (§6, #38) | **BLOCKED-named** | This lane added the one server-side gap the design doc named as required (`record_decision` terminal-state guard, §5.3/RF-7 — new work, tested). The issue's actual acceptance test is a cross-layer test (§6.3) requiring a real Android client (Stage L2/L3), which doesn't exist yet. Not resolvable from this lane. |
>
> Verdict: **PARTIAL** — items 1-2 complete, items 3-4 blocked on Stage L2/L3
> (Android), named above, not failures of this lane. Full pytest suite: 14 passed,
> 0 failed, 0 skipped, as of this commit.

Standalone, durable, server-side ledger implementing the C3 REST API and the
`/ledger/events` SSE stream (design doc `docs/designs/2026-08-16-vela-chief-of-staff-rebuild.md`
§4.2). Requires no fork of `amplifier-agent` (§10 Stage 2) — this service is deliberately
independent and ships before any fork exists.

## What this is

The durable record of **work**, as distinct from the record of **conversation**. A
`dispatch_to_fleet` host-tool call (C1) returns a handle (`{job_id, status: "accepted"}`);
this service is where that job's lifecycle — progress, attention requests, human decisions,
final result — lives, transcript-independent (so `amplifier-agent`'s transcript reconciler
never deletes it as an "orphaned tool_use").

## Endpoints

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/ledger/jobs` | Create a job. **Idempotent on `origin.tool_call_id`** (G2) — creating twice with the same `tool_call_id` returns the existing job, never a duplicate row. |
| `GET` | `/ledger/jobs` | List / filter by `status`, `attention_required`, `since` (created_at), `limit`. |
| `GET` | `/ledger/jobs/{id}` | Detail. |
| `PATCH` | `/ledger/jobs/{id}` | Status transition, append a progress entry, set attention, set result, and/or update cost — all optional and composable. Called by the fleet plane. |
| `POST` | `/ledger/jobs/{id}/decision` | Record a human decision: updates status, clears `attention.required`. |
| `GET` | `/ledger/attention` | The card deck's backing query — jobs where `attention.required == true`. |
| `GET` | `/ledger/events` | SSE stream of ledger changes (`job.created`, `job.updated`, `job.decided`), drives notifications. |
| `GET` | `/healthz` | Liveness check. |

## Schema vs. `android/ledger`'s `JobEntity`/`JobRecord` (lane 1.5)

The wire `Job` model (`ledger_service/models.py`) is the nested shape, matching
`JobRecord.kt` (Android's public API) field-for-field. `ledger_service/db.py`'s SQLite
table mirrors `JobEntity.kt` (Android's flattened Room storage) column-for-column:

| C3 field | This service's column | `JobEntity` column |
|---|---|---|
| `job_id` | `job_id` (PK) | `job_id` (PK) |
| `created_at` | `created_at` | `created_at` |
| `updated_at` | `updated_at` | `updated_at` |
| `origin.session_id` | `origin_session_id` | `origin_session_id` |
| `origin.turn_id` | `origin_turn_id` | `origin_turn_id` |
| `origin.tool_call_id` | `origin_tool_call_id` (**UNIQUE** — backs G2) | `origin_tool_call_id` |
| `spec` | `spec_json` (serialized) | `spec_json` (serialized) |
| `status` | `status` (wire string, e.g. `"needs_attention"`) | `status` |
| `attention.required` | `attention_required` | `attention_required` |
| `attention.reason` | `attention_reason` | `attention_reason` |
| `attention.options` | `attention_options_json` (serialized list) | `attention_options_json` |
| `attention.deadline` | `attention_deadline` | `attention_deadline` |
| `progress` | `progress_json` (serialized list, append-only) | `progress_json` |
| `result` | `result_json` (nullable, serialized) | `result_json` |
| `cost.usd` | `cost_usd` | `cost_usd` |
| `cost.tokens` | `cost_tokens` | `cost_tokens` |
| — (not in C3) | `server_authoritative_version` | `server_authoritative_version` |

`server_authoritative_version` exists on both sides for the same reason: the
not-yet-built sync/mirror mode (Android's local SQLite mirror reconciling against
this service as the source of truth). Both increment it on every write. No sync
logic exists yet on either side — only the counter and the invariant.

This mapping is verified by an explicit test
(`tests/test_schema_mapping.py::test_wire_job_schema_matches_android_job_record_fields`),
not by inspection alone, per the lane's requirement (item 2).

**Field alignment note:** the design doc's abstract `spec { … }` and `result { … }` are
opaque JSON objects on both sides — Android serializes them to a string column
(`spec_json`/`result_json`) and this service does too, but the *wire* API exposes them
as JSON objects directly (more natural for the fleet plane, which is this service's
primary write-side client) rather than as double-encoded strings.

## Durability (G1)

Backed by SQLite in **WAL journal mode** with **`synchronous=FULL`** — durable from
commit one, never in-memory-only (design doc F-5 names ledger loss on restart as
low-likelihood but catastrophic). `synchronous=FULL` is the load-bearing setting: it
fsyncs on every commit, which is what actually survives a `kill -9`. WAL mode alone
only changes how writes are logged, not whether they are synced to disk.

Verified by `tests/test_durability_restart.py`: inject a known-cardinality sequence of
job creates/updates against a file-backed store, close the connection with no explicit
flush beyond ordinary commit semantics (simulating a hard kill), open a **fresh**
`LedgerDB` at the same file path (simulating the restarted process), and assert every
job's final state — status, progress, attention, result — matches exactly.

## Idempotency (G2)

`origin_tool_call_id` has a `UNIQUE` constraint. `create_job` first checks for an
existing row by `tool_call_id`; if a race causes two near-simultaneous creates to both
miss that check, the `UNIQUE` constraint's `IntegrityError` is caught and the existing
row is fetched and returned instead of surfacing an error. Verified directly
(`tests/test_idempotency.py`) and over HTTP (`tests/test_api_lifecycle.py`).

## Decision terminal-state guard (design doc §5.3 / RF-7, Stage L1)

`POST /ledger/jobs/{id}/decision` now rejects a decision against a job that is
already in a terminal state (`done`, `failed`, `cancelled`) — **unless** the
decision's `new_status` matches the job's current terminal status exactly, in
which case it converges as a no-op (matching §4.1's "calling it twice with the
same `new_status` converges to the same terminal state").

This is the concrete mechanism behind the design doc's "server always wins"
conflict rule (§5.3): if a phone queued a decision offline while the server
concurrently timed the job out to `failed`, replaying that stale decision must
not silently resurrect/overwrite the terminal state. `record_decision` raises
`JobAlreadyTerminalError`; the HTTP layer maps it to `409 Conflict`, which
clients should surface as "this job was already resolved" rather than retrying
blindly.

## Cost accounting contract (design doc §7.2, #39)

`cost.usd` / `cost.tokens` (columns `cost_usd`, `cost_tokens`) are **overwrite-in-
place** at this layer: `PATCH /ledger/jobs/{id}` with a `cost` field replaces the
previously stored value, it does not sum with it. The ledger is a *recorder* of
cost, never a *meter* (design doc SA6) — it stores and serves whatever figure
it's handed.

**The contract this service relies on:** the value each `PATCH`'s `cost` field
carries must already be the **cumulative total for the job so far**. Because the
fleet plane's `velafleet-run` shim emits periodic `cost` events over a job's
lifetime rather than one final total (fleet plane doc §4.3), it is the
**broker** (`vela-fleetd`, the sole ledger writer per that doc's §4.1/§5.2) that
is responsible for summing before each `PATCH` — not this service. If the
broker instead sends each period's incremental cost, `cost.usd` and
`cost.tokens` will silently regress to a smaller figure on the next PATCH
instead of growing, which is exactly the "looks-right-but-is-wrong" bug this
contract exists to prevent. See design doc §7.2 for the full reasoning,
including the still-open fan-out cost rollup question (parent-vs-children
summation), which depends on fleet plane Stage F2 and is not addressed here.

## SSE stream

`/ledger/events` is backed by an in-process pub/sub broadcaster
(`ledger_service/events.py`): each connected client gets its own `asyncio.Queue`;
route handlers (`create_job`, `patch_job`, `decide`) publish to all current
subscribers after each durable write completes. The broadcaster is a live-notification
convenience, not the durability mechanism — SQLite is. A client that isn't connected
when an event fires simply doesn't see that specific SSE event; the underlying job
state is never lost and remains readable via `GET /ledger/jobs/{id}`.

## End-to-end lifecycle proof (item 6)

`tests/test_api_lifecycle.py::test_full_job_lifecycle_over_http` exercises the full
job lifecycle (create → progress → attention.required → decision → done) as a plain
HTTP client against this service, demonstrating that a server-backed
`LedgerRepository` implementation (Android, lane 1.1) — with the existing
`SqliteLedgerRepository` (lane 1.5) as a local mirror — could be built against this
API and schema. No Android-side swap is implemented here (out of scope for this lane).

## Running locally

```bash
cd services/ledger
python3 -m venv .venv && . .venv/bin/activate
pip install -e ".[dev]"
python -m pytest -q                      # run the test suite
python -m ledger_service --port 9199     # run the service
curl localhost:9199/healthz
```

`LEDGER_DB_PATH` overrides the default DB location (`~/.vela/ledger/ledger.db`).

## Deployment

See `ops/ledger/` for the systemd `--user` unit and install/redeploy scripts,
following the same convention as `ops/agent-serve/` (lane 1.4).
