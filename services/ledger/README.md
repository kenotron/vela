# vela-ledger-service

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
