# vela-fleetd-broker

The broker for the fleet execution plane (design doc
`docs/designs/2026-08-24-vela-fleet-execution-plane.md`, Stage F1, **Lane
F1.1 only**). Implements the D1-D5 contract's server side: worker registry,
admission, sole ledger writing for fleet jobs, and decision relay.

**Out of scope for this lane:** `velafleet-worker` (Lane F1.2) and
`velafleet-run` (the per-job JSONL-emitting shim, design doc section 4.3).
Neither exists yet. This service defines the protocol they must speak; it
does not implement them or fake their presence.

## What this service owns

Per design doc section 4.1:

| Concern | Module |
|---|---|
| Worker registry (liveness, capability labels, load) | `registry.py` |
| Admission (`POST /fleet/dispatch`, D1/D2/D3) | `app.py` |
| Sole ledger writer for fleet jobs (FB2) | `ledger_client.py`, `worker_events.py` |
| Decision relay (D4/D5 return path) | `sessions.py`, `app.py` |
| Job <-> worker binding for relay/reconciliation | `sessions.py` |

**Not owned here:** execution, credentials, PTYs, muxterm integration,
scheduling policy beyond simple label matching + least-loaded selection.
Those are Lane F1.2 (`velafleet-worker`) and later stages.

## Running it

Requires a running `services/ledger/` instance (frozen C3 REST contract this
broker writes against -- never modified here).

```bash
cd services/fleetd-broker
pip install -e ".[dev]"
FLEETD_LEDGER_URL=http://127.0.0.1:8001 uvicorn fleetd_broker.app:app --port 8002
```

## HTTP/WS surface (the protocol Lane F1.2 must speak)

### `POST /fleet/dispatch` -- admission (D1, D2, D3)

Request body: `{"origin": {"session_id", "turn_id", "tool_call_id"}, "spec": <JobSpec, design doc 4.4>}`.

1. Select a live target from the in-memory registry (D3: a lookup against an
   already-open connection, never a probe -- invariant FB1).
   - No live worker matches -> `400` with `detail: "UNREACHABLE: ..."` naming
     the reason. This lane does not queue offline dispatches (design doc
     section 2.1's open question -- answered here as "fail honestly", not
     silently accepted or dropped).
2. Create the ledger job via `POST /ledger/jobs` (idempotent on
   `origin.tool_call_id`, per the ledger's own contract -- G2 flows through
   unchanged).
3. Bind `job_id -> machine_id` for future decision relay.

Response: `{"job_id", "status": "accepted", "machine_id", "last_heartbeat_age_ms"}`.
`last_heartbeat_age_ms` makes the D3 dishonesty window (design doc 5.1)
visible to the caller rather than hidden.

### `WS /fleet/worker/{machine_id}` -- the held session (D3's substrate)

This connection is what D3 is a lookup *against*. Newline-delimited JSON
messages, worker-initiated except `decision` (broker-initiated):

```jsonc
// worker -> broker, on connect and whenever capabilities change
{"op": "register", "labels": ["linux", "has:repo/vela"], "runtimes": ["amplifier-agent", "shell"]}
// broker -> worker
{"op": "registered"}

// worker -> broker, periodic (interval configurable via heartbeat_interval_s,
// default 15s; a worker is "live" only within 2x this window)
{"op": "heartbeat"}

// worker -> broker, one per job-wrapper JSONL line (design doc 4.3), tagged
// with job_id. `fields` carries whatever the JSONL event carried beyond
// ts/kind/job_id (message, percent, reason, options, usd, tokens, exit_code, result, ...).
{"op": "event", "ts": 1756000012, "kind": "progress", "job_id": "...", "fields": {"message": "cloned repo", "percent": 20}}

// broker -> worker, in response to POST /fleet/jobs/{id}/decision
{"op": "decision", "job_id": "...", "text": "in-place"}
```

Event `kind` values map onto ledger writes as follows (design doc 5.2):

| `kind` | Ledger effect | Coalesced? |
|---|---|---|
| `started` | `status: running` | n/a |
| `progress` | `status: running` + append progress entry | Yes -- bounded flush interval (default 2s), see `ProgressCoalescer` |
| `attention` | `status: needs_attention`, `attention.required: true` + reason/options | **Never** -- written immediately |
| `cost` | cost accumulation (`usd`/`tokens`) | No |
| `finished` (`exit_code == 0`) | `status: done`, flush any pending progress first | n/a |
| `finished` (`exit_code != 0`) / `failed` | `status: failed`, flush any pending progress first | n/a |

On a terminal event the broker also decrements the worker's `active_jobs`
and unbinds the job from decision relay.

### `POST /fleet/jobs/{job_id}/decision` -- the D4/D5 return path

Records nothing in the ledger directly (that is the ledger's own
`POST /ledger/jobs/{id}/decision`, called by whatever drives that flow
upstream of the broker) -- this endpoint's job is purely the *relay*: push
the decision text down the worker session currently bound to that job.

- `409` if the job's worker is not currently connected
  (`WorkerUnavailableError` -- design doc's honest-degradation stance: a
  decision cannot be relayed to a session that isn't open, and this says so
  rather than silently dropping it).

### `GET /fleet/workers` -- registry introspection (ops/debugging)

### `GET /healthz`

## What Lane F1.2 (`velafleet-worker`) will need to implement against this

1. Open and hold a WebSocket to `/fleet/worker/{machine_id}`, send `register`
   on connect and whenever labels/runtimes change, send `heartbeat` at an
   interval comfortably under `2 * heartbeat_interval_s`.
2. Tail `velafleet-run`'s JSONL sidecar (design doc 4.3) and forward each
   line as an `{"op": "event", ...}` message, stamping `job_id` and
   `machine_id`.
3. Listen for `{"op": "decision", ...}` messages and relay the `text` into
   the running job's stdin via muxterm's `send_input` (design doc 4.3's
   "decision delivery is trivial" argument).
4. Reconnect with backoff on disconnect; the broker retains job bindings
   across a disconnect (see `SessionTable`/`WorkerRegistry.disconnect`) so a
   reconnecting worker does not need to re-dispatch in-flight jobs -- only
   Lane F1.2 needs to decide how it resumes tailing its own JSONL offset.

## Residuals / explicitly not done in this lane

- **Fan-out (`strategy: "all"`, design doc 5.3) resolution and rollup** --
  `WorkerRegistry.select_targets_all` resolves the target set, but parent/
  child ledger job creation and rollup policy is not wired into
  `/fleet/dispatch` (which only implements the single-target
  `least_loaded`/`any` path). Named here as a residual, not implemented as a
  stub.
- **Reconciliation on worker reconnect** (design doc 4.1: diff a
  reconnecting worker's reported job set against the broker's, correct the
  ledger for anything that died while disconnected) -- the registry retains
  bindings across a disconnect so the *data* needed for this exists, but the
  diff-and-correct logic itself is not implemented.
- **A local durable broker store** (design doc 4.1's `job_id -> (machine_id,
  spec, last_known_state)` cache) -- this lane's `SessionTable`/
  `WorkerRegistry` are in-memory only. A broker restart currently loses
  job/worker bindings; workers would need to re-register and the broker
  would need reconciliation logic (above) to recover state, which does not
  exist yet.
- **Real fleet-scale verification (Gates FG-1, FG-2)** -- no real hardware,
  no real Tailscale network, no `velafleet-worker` to dispatch to in this
  environment. In-process unit/integration tests exercise the broker's own
  logic (registry, coalescing, event mapping, relay) with a fake worker
  transport; they do not and cannot exercise real fleet conditions. See
  `DONE.json` for this named as `BLOCKED`, not silently skipped.
