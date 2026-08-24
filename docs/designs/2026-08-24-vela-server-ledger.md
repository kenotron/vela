# Vela — Server-Owned Ledger as Source of Truth (C3 REST + SSE)

**Date:** 2026-08-24
**Status:** Proposed design spec — ready for review, then decomposition into lanes
**Issue:** [#36](https://github.com/kenotron/vela/issues/36) G4: Durable Work Ledger, under [#16](https://github.com/kenotron/vela/issues/16)
**Sibling issues addressed:** #37 (phone-local mirror), #38 (zero-lost-events, cross-layer), #39 (cost accounting), #30 (attention query), #32 (decision round-trip)
**Companion to:** `docs/designs/2026-08-24-vela-fleet-execution-plane.md` (D4/D5 client of this ledger)

> **This is a spec, not code.** It defines contracts, schema, and cutover sequencing. No
> implementation is included or implied to already exist beyond what is cited as already
> built.

---

## 1. Problem Framing

### 1.1 What already exists (do not re-derive)

Two independent things were built in prior lanes, and this document's entire job is to
**connect them correctly**, not invent either from scratch:

1. **`services/ledger/`** (lane 2.1) — a working FastAPI + SQLite service implementing the
   C3 job resource: `POST /ledger/jobs`, `GET /ledger/jobs`, `GET /ledger/jobs/{id}`,
   `PATCH /ledger/jobs/{id}`, `POST /ledger/jobs/{id}/decision`, `GET /ledger/attention`,
   `GET /ledger/events` (SSE). Durable via SQLite WAL + `synchronous=FULL`. Idempotent on
   `origin.tool_call_id`. Already has passing tests for the full lifecycle, idempotency,
   restart durability, and schema parity with Android's `JobEntity`/`JobRecord` shape.
2. **`android/core-domain/.../LedgerRepository.kt`** (lane 1.1) — the domain interface the
   app's UI and tools already code against: `observeEntries()`, `get(id)`, `append(entry)`,
   `recordDecision(entryId, decision)`. `android/host-tools/.../InMemoryLedgerRepository.kt`
   is an explicitly-scoped **stub**, local to host-tools, that exists only so
   `DispatchToFleetTool` has something to write through in tests — it is not lane 1.5's real
   (SQLite-backed, phone-local) implementation and is not addressed by this document.

**What does not yet exist, and is this document's scope:** the *server-authoritative*
Android implementation of `LedgerRepository` — the thing that makes `services/ledger/`
the actual source of truth the app reads from, with the phone-local store demoted to an
offline-read mirror. Issue #36's own framing: "already built... this feature tracks making
it the ACTUAL source of truth."

### 1.2 What "source of truth" concretely changes

Today (implicit end state before this lane): any phone-local ledger store, if written
directly at all, is authoritative for itself. After this lane:

| | Before | After |
|---|---|---|
| Who decides job status | Whoever wrote it last, wherever that was | The server (`server_authoritative_version` monotonic counter already in `db.py`) |
| What the UI reads when online | Local store | Server, via REST + SSE |
| What the UI reads when offline | Local store | Local mirror, honestly labeled stale |
| Where writes land | Local store (if any) | Server, via REST; local store never accepts a write the server hasn't confirmed |
| Conflict resolution | N/A (single writer) | Server always wins (§5) |

### 1.3 Scope of this document

**In scope:**
- The REST API surface as a contract (already implemented in `services/ledger/`; this
  section documents it as the frozen contract, flags one required extension).
- The SSE subscription contract for live updates.
- Persistence choice and schema (already implemented; this section explains why it's
  sufficient and what's missing for the mirror/idempotency/cost stories).
- How #38 (zero-lost-events, cross-layer) is satisfied end-to-end, not just server-side.
- How #39 (cost accounting) is satisfied: field plumbing plus a pre-flight estimate gate.
- The phone-local mirror sync contract (#37): reconciliation, conflict rule, staleness
  signaling.
- How `dispatch_to_fleet` and the decision round-trip (#32) write to the server ledger,
  specifically the handoff between the fleet plane design's `HttpFleetPlane` and this
  service.
- How #30 (attention query backing the deck) is served from the server ledger instead of
  a local-SQLite-only query.

**Explicitly out of scope:**
- The fleet plane's internals (broker, worker, transport) — owned entirely by
  `docs/designs/2026-08-24-vela-fleet-execution-plane.md`. This document only specifies
  the ledger side of the `PATCH /ledger/jobs/{id}` contract that plane already writes to.
- Redesigning the REST/SSE surface. It is treated as frozen and correct per the passing
  schema-parity tests; changes proposed here are additive only.
- Authentication/transport security for the ledger service's network exposure (Tailscale
  ACLs, mTLS) — assumed to follow the same model as the rest of the fleet plane
  (`docs/designs/.../§4.5`) and is not re-litigated here.
- Multi-tenant / multi-device ledger merge (two phones editing the same job). Out of
  scope until a second device is a real requirement; the mirror contract in §5 is written
  so it does not preclude this later.

---

## 2. Explicit Assumptions

| # | Assumption | Confidence | If false |
|---|---|---|---|
| **SA1** | The phone can reach `services/ledger/` over the same network path (Tailscale) that reaches `vela-agentd`/`vela-fleetd`. | High — same topology already assumed by the fleet plane doc. | Ledger reads/writes need their own connectivity story; likely co-locate behind the same host as §8.6 of the fleet doc. |
| **SA2** | `origin.tool_call_id` uniquely identifies a dispatch attempt from Android's perspective and is stable across retries of the *same* tool call. | High — this is exactly what `DispatchToFleetTool` already generates and the idempotency test (`test_idempotency.py`) already exercises. | Idempotent create (G2) breaks; retries would create duplicate jobs. |
| **SA3** | `server_authoritative_version` (already a column in `db.py`, not yet exposed on the wire) is sufficient as a monotonic conflict token for the mirror's "is my cached copy stale" check. | Medium-high — it increments on every `update_job`/`record_decision`; not yet surfaced in the `Job` wire model. | Mirror needs a different staleness signal (e.g., `updated_at` comparison, which is already on the wire and works but is coarser — see §5.3). |
| **SA4** | One ledger service instance is authoritative; no server-side clustering or replication is in scope for this lane. | High — matches the fleet plane's explicit "no broker clustering" decision (§7.4 item 3 of that doc), same reasoning applies here. | Would need a consensus story before this design is complete. Not indicated by current scale. |
| **SA5** | SSE delivery is best-effort for *notification*, and REST GET is the durability fallback — already the explicit design of `events.py` ("a client that connects after an event fires will simply not see that event over SSE, but the underlying job state is never lost"). | High — verified by reading `events.py`. | Would need SSE replay/backfill, which is a materially bigger feature. |
| **SA6** | Cost figures (`usd`, `tokens`) originate from the fleet plane's `velafleet-run` shim (fleet plane doc §4.3, `kind:"cost"` events) and arrive via `PATCH /ledger/jobs/{id}` — the ledger is a *recorder* of cost, not a *meter*. | High — matches the fleet plane doc's cost event shape exactly. | Cost accounting would need its own metering path independent of the ledger, which duplicates the PATCH plumbing that already exists. |

---

## 3. System Boundaries

```
┌────────────────────────────── Android app ──────────────────────────────┐
│                                                                          │
│  UI / ViewModels ──► LedgerRepository (interface, unchanged)            │
│                           │                                             │
│                           ▼                                             │
│              ServerLedgerRepository  (NEW — this document)              │
│                 ├─ REST client (create/patch/decide/list/get)           │
│                 ├─ SSE subscriber (live updates → Flow)                 │
│                 └─ LocalMirrorStore (existing lane-1.5 SQLite schema,    │
│                                       demoted to read-only cache)        │
│                                                                          │
│  DispatchToFleetTool ──► POST /ledger/jobs   (already implemented, C1)  │
│  swipe decision UI   ──► POST /ledger/jobs/{id}/decision (already impl.)│
│  card deck           ──► GET /ledger/attention (via ServerLedgerRepo)   │
│                                                                          │
└───────────────────────────────┬──────────────────────────────────────────┘
                                 │  HTTPS/WSS (Tailscale) — the ONLY network
                                 │  boundary this document adds
                                 ▼
┌────────────────────────── services/ledger/ (existing) ───────────────────┐
│                                                                          │
│   FastAPI app (app.py)          SQLite (db.py, WAL+FULL)                │
│     · POST   /ledger/jobs         jobs table, indexed on status,       │
│     · GET    /ledger/jobs           attention_required, created_at     │
│     · GET    /ledger/jobs/{id}                                        │
│     · PATCH  /ledger/jobs/{id}  ◄── fleet plane writes progress/       │
│     · POST   /ledger/jobs/{id}/decision   attention/cost here          │
│     · GET    /ledger/attention                                        │
│     · GET    /ledger/events (SSE, events.py in-process broadcaster)   │
│                                                                          │
└──────────────────────────────────────────────────────────────────────────┘
                                 ▲
                                 │  same PATCH surface (unchanged)
                     vela-fleetd / velafleet-worker
                     (fleet execution plane — companion doc)
```

### 3.1 Boundary rules (invariants)

| # | Rule |
|---|---|
| **SB1** | **The server is the only writer of `status`, `attention`, `progress`, `cost`, and `result`.** The phone never mutates these fields locally and pushes the mutation up later — it always round-trips through REST first (§5.2). |
| **SB2** | **The local mirror is read-only from the app's perspective.** UI code reads through `LedgerRepository`; whether that resolves to a live server read or a mirror read is a decision `ServerLedgerRepository` makes internally (§5), never something a ViewModel decides. |
| **SB3** | **Job creation is idempotent on `origin.tool_call_id`, always.** No code path may create a job with a different origin key for what is semantically a retry. This is already enforced server-side (`DuplicateToolCallError` handling in `db.py`); the client must not route around it by generating a fresh UUID per retry. |
| **SB4** | **The fleet plane and the Android app both write through the same REST surface.** No back-channel, no shared database file, no direct SQLite access from Android. This is what makes the schema-parity test in `test_schema_mapping.py` meaningful — it is the *only* contract between them. |
| **SB5** | **SSE is a notification path, never a durability path.** Any code that treats a missed SSE event as data loss is wrong; the fix is always "go read the REST resource," never "make SSE more reliable." (SA5.) |

---

## 4. REST API Surface

This section documents the **existing, frozen contract** (`services/ledger/ledger_service/app.py`, `models.py`) as the interface Android codes against, and calls out the one addition needed for this lane.

### 4.1 Endpoint reference

| Method | Path | Purpose | Idempotent? | Used by |
|---|---|---|---|---|
| `POST` | `/ledger/jobs` | Create a job | Yes, on `origin.tool_call_id` (G2) | `DispatchToFleetTool` (Android) |
| `GET` | `/ledger/jobs` | List/filter jobs (`status`, `attention_required`, `since`, `limit`) | N/A (read) | Ledger UI, mirror full-sync (§5.4) |
| `GET` | `/ledger/jobs/{id}` | Fetch one job | N/A (read) | `LedgerRepository.get(id)` |
| `PATCH` | `/ledger/jobs/{id}` | Update status/progress/attention/result/cost | Yes (idempotent per-field merge; repeating an identical PATCH is a no-op in effect) | `vela-fleetd` (broker, sole writer per fleet plane doc FB2) |
| `POST` | `/ledger/jobs/{id}/decision` | Record a human decision, clears `attention.required` | No — each call is a distinct decision event, but calling it twice with the same `new_status` converges to the same terminal state | Swipe decision UI (#32) |
| `GET` | `/ledger/attention` | List jobs with `attention.required == true` | N/A (read) | Card deck (#30) |
| `GET` | `/ledger/events` | SSE stream of `job.created` / `job.updated` / `job.decided` | N/A (subscribe) | `ServerLedgerRepository`'s live `Flow` |
| `GET` | `/healthz` | Liveness | N/A | Connectivity probe backing mirror-mode fallback (§5.5) |

### 4.2 Wire model (`Job`, from `models.py`)

```jsonc
{
  "job_id": "uuid",
  "created_at": 1756000000000,           // epoch ms
  "updated_at": 1756000900000,
  "origin": {
    "session_id": "s1",
    "turn_id": "t1",
    "tool_call_id": "tc-e2e"             // idempotency key (SA2, SB3)
  },
  "spec": { "...": "opaque, tool-defined" },
  "status": "needs_attention",           // accepted|running|needs_attention|blocked|done|failed|cancelled
  "attention": {
    "required": true,
    "reason": "confirm send",
    "options": ["send", "discard"],
    "deadline": null
  },
  "progress": [
    { "ts": 1756000012000, "message": "cloned repo, running tests", "percent": 20, "source": "fleet" }
  ],
  "result": { "pr_url": "https://..." },
  "cost": { "usd": 0.41, "tokens": 88300 }
}
```

This shape is already field-for-field asserted against Android's `JobEntity`/`JobRecord`
by `test_schema_mapping.py` — that test is the standing contract check and should keep
running in CI as the guard against schema drift on either side.

### 4.3 Required addition: expose `server_authoritative_version` on the wire

**Gap identified by this design (not previously flagged):** `db.py` already tracks
`server_authoritative_version` as a monotonic per-job counter, incremented on every
`update_job` and `record_decision` call — but `Job` (the Pydantic wire model) does not
include it, so the client currently has no cheap way to detect "my cached copy is stale by
N writes" versus recomputing from `updated_at` timestamps (which is coarser: two updates
in the same millisecond are indistinguishable).

**Change:** add `version: int` to the `Job` model, sourced from
`server_authoritative_version`. This is additive — existing consumers ignoring the field
are unaffected — and is required for the mirror's cheap staleness check (§5.3). No other
wire model change is proposed.

### 4.4 Mapping to `LedgerRepository`'s domain model

`ServerLedgerRepository` (new Android class, implementing the existing
`com.vela.core.domain.LedgerRepository` interface unchanged) is a **translation layer**,
not a redesign:

| `LedgerRepository` domain type | Server `Job` field(s) |
|---|---|
| `LedgerEntry.id` | `job_id` |
| `LedgerEntry.title` | `spec.title` (fleet plane doc §4.4 job spec) |
| `LedgerEntry.summary` | `spec.summary` |
| `LedgerEntry.createdAtEpochMs` | `created_at` |
| `LedgerEntry.source` | `spec.runtime` or a fixed `"fleet"` / `"local"` tag |
| `LedgerEntry.status` (`PENDING`/`ACCEPTED`/`DEFERRED`/`DISMISSED`) | mapped from server `status` (§4.4.1) |
| `Decision` | `POST /ledger/jobs/{id}/decision` request/response |

**§4.4.1 — Status enum mapping is not 1:1 and must be explicit.** The domain interface's
`Status` enum (`PENDING`, `ACCEPTED`, `DEFERRED`, `DISMISSED`) predates the server's richer
`JobStatus` (`accepted`, `running`, `needs_attention`, `blocked`, `done`, `failed`,
`cancelled`) — they were designed independently (lane 1.1 vs lane 2.1) and diverged. This
document does not silently pick a mapping; it flags the divergence as a decision the
implementation must make explicitly, with the recommended default below, documented as a
comment at the mapping site (not left implicit in code):

| Server `status` | Recommended `LedgerRepository.Status` |
|---|---|
| `accepted`, `running` | `PENDING` |
| `needs_attention` | `PENDING` (surfaced via `attention.required`, not via a distinct domain status — the domain interface has no `NEEDS_ATTENTION` value) |
| `blocked` | `PENDING` |
| `done` | `ACCEPTED` |
| `failed` | `DISMISSED` |
| `cancelled` | `DISMISSED` |

**This is a lossy mapping and is called out as such.** If the app needs to distinguish
`failed` from `cancelled` or `running` from `blocked` in the UI, `LedgerRepository`'s
domain enum needs to grow — that is a finding for lane 1.1's owner, not something this
document works around by inventing new domain states unilaterally.

---

## 5. Phone-Local Mirror Sync Contract (#37)

### 5.1 What "mirror" means after this lane

Per #37: "reconcile it as a true mirror (server-authoritative conflict resolution) rather
than a standalone store." Concretely:

- The mirror's schema is the existing lane-1.5 SQLite schema (`JobEntity` per
  `test_schema_mapping.py`'s own framing) — **unchanged structurally** by this document.
- The mirror's **role** changes: it stops being written-to-directly by app logic and
  becomes a **cache populated only by confirmed server responses**.
- Every write path (`append`, `recordDecision`) becomes: call REST → on success, write the
  server's response into the mirror → return. **Never**: write mirror → queue a
  background sync. That ordering is exactly the swap this lane makes, and it's why "server
  wins" needs no actual conflict resolution logic for writes — there is only ever one
  writer of authoritative data (SB1), and the client never originates a status transition
  outside of `POST .../decision`.

### 5.2 Read path (online vs. offline)

```
ServerLedgerRepository.observeEntries():
  1. Attempt SSE subscription to GET /ledger/events.
     ├─ Connected → merge live events into an in-memory reactive stream,
     │              backed by periodic full reconciliation (§5.4) as a
     │              correctness backstop (SSE is notify-only, SA5).
     │              Mirror is updated as a side effect of each event,
     │              kept warm for the next offline transition.
     └─ Connection fails / times out → mirror-mode (§5.5).

  2. get(id):
     ├─ Online  → GET /ledger/jobs/{id}, write-through to mirror, return.
     └─ Offline → read mirror, tag result as `stale=true` (see below —
                   this is a translation-layer concern; if the domain
                   model needs a stale flag, that's a lane-1.1 addition,
                   otherwise surface via a UI-layer wrapper type. This
                   document does not mandate widening LedgerEntry.)
```

**Staleness signaling:** the domain-level `LedgerRepository` interface has no `stale`
field on `LedgerEntry`, and this document does not add one to that interface (see §4.4.1's
philosophy — don't widen a foundational interface as a side effect of a plumbing change).
Instead, `ServerLedgerRepository` exposes a **separate** observable (`isServerReachable:
Flow<Boolean>` or equivalent) that the UI composes with `observeEntries()` to render a
"showing cached data" banner. This keeps the mirror-mode indicator a UI concern, not a
domain-model concern.

### 5.3 Conflict rule: server always wins

There is exactly one conflict scenario worth naming, because the design otherwise makes
conflicts structurally impossible (SB1: client never writes authoritative fields except
via the decision endpoint):

**Scenario:** the phone made a decision offline (queued locally, §5.4) while the server
concurrently timed out that job (fleet plane doc §5.4's "job died while awaiting decision"
→ `failed` with reason `"job exited while awaiting decision"`).

**Rule:** when the queued decision is finally replayed against the server
(`POST /ledger/jobs/{id}/decision`), the server's current state at replay time wins. If the
job is already terminal (`done`/`failed`/`cancelled`), the decision POST either:
- returns the current (already-terminal) job unchanged if `new_status` matches, or
- is rejected with a `409`-class response the client surfaces as "this job was already
  resolved" rather than silently overwriting a terminal state.

**This requires one server-side addition, not yet present:** `record_decision` in `db.py`
currently unconditionally overwrites `status` regardless of the job's current state — it
does not check "is this job already terminal." Adding that guard (reject or no-op
decisions against a job already in `done`/`failed`/`cancelled`) is the concrete mechanism
behind "server wins," and is called out explicitly here because it is a real gap, not an
already-implemented behavior.

`version` (§4.3) is what makes this cheap to detect client-side *before* even attempting
the replay: if the mirror's cached `version` for a job is behind the version the client
last saw when queuing the decision, the client can pre-emptively re-fetch and inform the
user rather than blindly firing a stale decision.

### 5.4 Sync/reconciliation protocol

Two mechanisms, not one, because they answer different questions:

1. **Live merge (event-driven, while connected).** Each SSE event
   (`job.created`/`job.updated`/`job.decided`) carries the full current `Job` — the mirror
   is upserted with it directly. No delta computation needed; the server always sends the
   whole resource (per `events.py`'s `publish(event_type, job)` — already true today).

2. **Full reconciliation (periodic + on reconnect).** `GET /ledger/jobs?since=<mirror's
   latest known created_at>&limit=...` on every SSE (re)connect and on an app-foreground
   event. This is the backstop for SA5 (SSE is not durable) — it is what guarantees a
   phone that was offline for an hour, or that missed events during a brief disconnect,
   converges to the true server state rather than silently drifting. `since` bounds the
   payload to genuinely new jobs; a full unbounded resync is the fallback if the mirror
   has no watermark at all (first run, or mirror corruption).

**Offline write queue.** `recordDecision` calls made while offline are queued locally
(existing pattern: a small outbox table, or reuse of whatever local persistence lane 1.5
already has) and flushed in order on reconnect, subject to the terminal-state guard in
§5.3. `append()` (creating new entries) is **not queued offline** — job creation is
`dispatch_to_fleet`'s job (fleet plane doc §5.1), which already requires network
reachability for its own reasons (D2/D3), so there is no offline-create case to design for
here.

### 5.5 Mirror-mode fallback

Detected via: SSE connection failure, or `GET /healthz` timeout/error on the periodic
reconciliation attempt. While in mirror-mode:

- Reads serve from the local mirror, unconditionally.
- The UI-facing reachability flow (§5.2) reports `false`.
- Writes (`recordDecision`) are queued (§5.4), never silently dropped, never silently
  applied to the mirror as if authoritative.
- A background retry (exponential backoff, capped) attempts `GET /healthz`; on success,
  transitions back to online mode and immediately runs full reconciliation before
  resubscribing to SSE (so the app doesn't briefly show a mirror that's still behind).

---

## 6. Zero-Lost-Events, Verified Cross-Layer (#38)

### 6.1 What's already independently verified

- **Server-only:** `test_durability_restart.py` — kills and restarts the SQLite-backed
  `LedgerDB` mid-sequence, asserts every job and every field survives. This is G1 from the
  source design doc, verified for the server layer alone.
- **Local-only:** presumed covered by lane 1.5's own tests against the phone-local store
  (not re-verified here; out of scope of this document to re-derive lane 1.5's test suite).

### 6.2 What #38 actually asks for: the end-to-end case

The gap #38 names is real: **each layer was verified in isolation, not together.** The
cross-layer failure modes that only show up when both layers exist simultaneously:

| Failure injected | What must still hold |
|---|---|
| Server restarts mid-write, phone is mid-SSE-subscribe | Phone detects the dropped connection, falls to mirror-mode (§5.5), and on reconnect, full reconciliation (§5.4) recovers any event it would have otherwise missed. No job silently vanishes from the app's view. |
| Phone app is killed (not just backgrounded) mid-decision-POST | The decision either landed server-side before the kill (in which case reconciliation on next launch shows the correct terminal state) or it didn't (in which case the app, on restart, must re-check: was this decision durably queued locally before the process died, or lost?). **This is the one genuinely new mechanism needed:** the offline write queue (§5.4) must itself be durably persisted (same durability discipline as the server's SQLite — WAL or equivalent), not held only in memory, or a phone kill during "queued but not yet sent" is a real, silent loss. |
| Phone offline for an extended period while multiple jobs progress and one reaches `needs_attention` | On reconnect, full reconciliation (not just SSE replay, since SSE cannot replay missed events per SA5) must surface the `needs_attention` job into the attention queue (#30) — verified by asserting the reconciled mirror's `attention_queue`-equivalent query matches server's `GET /ledger/attention` after reconnect. |
| Network partition between fleet plane and ledger service (not phone-related) | Already the fleet plane doc's own concern (FG-3, its own G1-style gate) — this document does not re-own that; it only owns the phone-facing half. |

### 6.3 The verification method (mirrors the server-side test's structure)

A new integration test, `test_cross_layer_zero_lost_events` (naming only — this document
does not write the test, per its own scope), structured the same way as
`test_durability_restart.py` but spanning both layers:

1. Start the real `services/ledger/` app (as `test_api_lifecycle.py` already does via
   `ASGITransport`).
2. Drive a known-cardinality sequence of job creates/PATCHes/decisions through a **real
   instance of `ServerLedgerRepository`** (not a mock) subscribed to the real SSE stream.
3. Inject each of the three failures in §6.2 at defined points in the sequence.
4. Kill/restart the relevant side (server process, client's in-memory state, or both).
5. Assert the client's `observeEntries()` output, after reconciliation, exactly matches
   the server's `GET /ledger/jobs` output — same job count, same field values, same
   attention flags.

This is the direct cross-layer analogue of the server's own restart test, extended to
cover the boundary the server-only test cannot see: the network hop and the client's
recovery behavior across it.

---

## 7. Cost Accounting (#39)

### 7.1 What's already in place

- Wire model: `Cost { usd: float | None, tokens: int | None }`, already on `Job`.
- Storage: `cost_usd`, `cost_tokens` columns, already in `db.py`'s schema.
- Write path: `PATCH /ledger/jobs/{id}` with a `cost` field, already accepted by
  `update_job`.
- Origin: the fleet plane doc's `velafleet-run` shim already emits `{"kind":"cost", "usd":
  ..., "tokens": ...}` JSONL events (fleet plane doc §4.3), which the broker is expected to
  fold into its coalesced PATCH stream (fleet plane doc §5.2).

**What #39 identifies as the actual open work:** "a pre-flight cost-estimate gate on
dispatch is the open follow-on, gated on answering the unresolved 'cost ceiling' question."
This document does not invent the cost ceiling number (that's explicitly a product
decision per the fleet plane doc §12 item 3) — it specifies the **mechanism** so the
number can be dropped in later, matching that doc's own pattern for `limits.usd` (fleet
plane doc §8.7: "the mechanism ships; the policy waits for the answer").

### 7.2 Cost accumulation semantics (currently underspecified — flagged here)

**Gap:** `update_job`'s cost handling (`db.py`) currently treats `cost.usd`/`cost.tokens`
as **overwrite-in-place**, not accumulate. Given the fleet plane emits periodic `cost`
events over a job's lifetime (not one final total), each `PATCH` with a new `cost` value
will **replace**, not add to, the previous figure — unless the fleet plane's broker is
responsible for computing the running total itself before each PATCH (which the fleet
plane doc does not explicitly state either way).

**This document takes a position:** cost values on the wire and in storage represent the
**cumulative total for the job so far**, and the **broker** (not the ledger) is
responsible for summing before each PATCH — matching the existing pattern where the
broker is the sole ledger writer and does all coalescing (fleet plane doc §4.1, §5.2). The
ledger's job is to store and serve whatever cumulative figure it's given; it does not sum
across PATCHes itself. This is called out explicitly because silently assuming
accumulation happens "somewhere" is exactly the kind of gap that produces a
looks-right-but-is-wrong cost figure — the kind of bug this lane exists to prevent.

**Fan-out cost rollup** (fleet plane doc §5.3's parent/child job trees) is a **separate**
open question this document surfaces but does not resolve: does the parent job's `cost`
reflect the sum of its children's costs? If so, that summation is the broker's
responsibility at rollup time (same principle: ledger stores, broker computes), and should
be validated by a test asserting `parent.cost.usd == sum(child.cost.usd for child in
children)` once fan-out ships. Flagged, not solved, here — it depends on Stage F2 of the
fleet plane doc, which has not shipped.

### 7.3 Pre-flight cost-estimate gate (the mechanism for the future policy)

Structure the gate as a check at `dispatch_to_fleet` time that this document specifies the
**shape** of, without setting the threshold:

```
POST /ledger/jobs  (existing, unchanged)
     │
     ▼
[NEW] cost_estimate = estimate(spec)   ← heuristic, e.g. based on spec.runtime + historical
                                          median cost for jobs with similar labels/spec shape
     │
     ├─ cost_estimate <= configured_ceiling → proceed (current behavior, unchanged)
     └─ cost_estimate >  configured_ceiling → job created with
                                                status: "needs_attention",
                                                attention: {
                                                  required: true,
                                                  reason: "estimated cost $X exceeds ceiling $Y",
                                                  options: ["proceed", "cancel"]
                                                }
                                              instead of "accepted" —
                                              i.e. it becomes a normal decision-round-trip
                                              case (#32), reusing the existing attention/
                                              decision machinery rather than inventing a
                                              new approval flow.
```

**Why this shape:** it requires **zero new endpoints and zero new domain concepts.** A
cost-gated job is just a job that starts in `needs_attention` instead of `accepted`,
resolved through the exact same swipe-decision path (#32) as every other attention item.
The only new pieces are (a) the `estimate()` heuristic — explicitly a placeholder pending
real usage data, and (b) `configured_ceiling` — explicitly the unresolved product number
from the fleet plane doc. **Where this gate lives** (ledger service, at `POST
/ledger/jobs` time, vs. the broker, at `POST /fleet/dispatch` time) is left as an
implementation choice for whoever builds this lane, with a lean toward the **broker**
being the natural owner — it already knows `target.labels` and job-shape context the raw
ledger create request does not carry, and it's already the component responsible for
`limits.usd` enforcement (fleet plane doc §8.7).

---

## 8. `dispatch_to_fleet` and Decision Round-Trip Writes (#32)

This section makes explicit what already exists and where this document's job ends,
because it's easy to over-claim scope here.

### 8.1 Dispatch write path (already fully specified elsewhere — cited, not repeated)

Per the fleet plane doc §5.1 and §8.4: `DispatchToFleetTool.run()` does
`POST /ledger/jobs` **first** (ordering-critical, already implemented), then calls
`fleetPlane.dispatch(spec)`. This document's only addition to that flow: the
`ServerLedgerRepository` built here is what backs that `POST` call and what the app's UI
subsequently reads from — i.e., **this document is the client-side implementation that
makes the already-correct dispatch ordering actually show up live in the app**, rather
than only being testable at the HTTP layer as `test_api_lifecycle.py` already does.

### 8.2 Decision write path — this is the #32 gap, precisely

Issue #32's own text: "A swipe decision must actually POST to
`/ledger/jobs/{id}/decision` and cause the fleet plane to resume — not just update local UI
state." That "not just update local UI state" is exactly the bug this document's SB1/SB2
invariants prevent structurally: because the local mirror is never written to directly by
UI code (§5.1), there is no code path left by which a swipe can update "local state" without
also having gone through the REST call first. The decision UI's only legal action is:

```
onSwipe(jobId, decision):
    ledgerRepository.recordDecision(jobId, decision)   // ONLY entry point
       │
       ▼ (ServerLedgerRepository, this document)
    POST /ledger/jobs/{id}/decision                     // already implemented server-side
       │
       ├─ success → write response into mirror, emit through observeEntries()
       └─ offline  → queue (§5.4), UI shows "queued, will sync" — NOT "done"
```

**The causal chain to "fleet plane resumes"** is fully specified by the fleet plane doc
§5.4 (ledger SSE → broker subscriber → `send_input` into the muxterm pane) and is **not**
re-specified here — this document's contribution to #32 is guaranteeing the write actually
lands durably and only through the server, which is the precondition the fleet plane doc's
§5.4 flow depends on (it subscribes to `/ledger/events`, so if the phone never durably
POSTs the decision, there is nothing for it to subscribe to).

### 8.3 What #32 needs from this document, restated as a test

`test_decision_causes_resume` (naming only, not written here): swipe a card in a real
(or realistically stubbed) UI flow → assert the exact `POST /ledger/jobs/{id}/decision`
request lands at the ledger service with the right `job_id` and `new_status` → assert the
resulting `job.decided` SSE event is what the fleet plane's broker consumes. The first half
of that chain is this document's scope; the second half is the fleet plane doc's §5.4,
already specified.

---

## 9. Attention Query Backing the Deck (#30)

**Current gap named by #30:** "Already implemented at the local-SQLite layer (lane 1.5);
needs the server-ledger-backed version wired through."

This is fully covered by the read path in §5.2 plus the existing `GET /ledger/attention`
endpoint — there is no new query design needed, only the wiring:

```
Card deck ViewModel
    │
    ▼
LedgerRepository.observeEntries()          // unchanged interface
    │  (ServerLedgerRepository filters for attention.required == true
    │   client-side, OR calls GET /ledger/attention directly —
    │   see note below)
    ▼
attention-required entries only
```

**One design choice flagged, not resolved by fiat:** should the deck's live feed be (a) a
client-side filter over the full `observeEntries()` stream, or (b) a dedicated subscription
that calls `GET /ledger/attention` directly and treats it as its own reactive source?
**Recommendation: (a).** The full `observeEntries()` stream is already being merged from
SSE + reconciliation (§5.2); filtering it client-side avoids running two independent
reconciliation loops against the same underlying data, which would double the
reconnect/backfill logic for no benefit. `GET /ledger/attention` remains useful as the
one-shot query used during the periodic full-reconciliation step (§5.4) to validate the
client-side filter's output matches server truth (the basis for the §6.2 test case "does
`needs_attention` survive a reconnect").

---

## 10. Risks and Failure Modes

| # | Failure | Likelihood | Blast radius | Mitigation |
|---|---|---|---|---|
| **RF-1** | Offline write queue (§5.4) is held only in memory and lost on process kill. | Medium if not deliberately built durable | A user's swipe decision silently vanishes — the exact bug #32 exists to prevent. | Queue must use the same durability discipline as the server (persisted, not memory-only). Called out explicitly in §6.2 as the one genuinely new mechanism this lane requires. |
| **RF-2** | `version` field (§4.3) is added but nothing actually uses it, becoming dead schema. | Low-medium | None functionally, but schema drift/confusion. | Tie its introduction directly to the mirror's staleness check (§5.3) in the same lane, not as a speculative addition. |
| **RF-3** | Cost accumulation semantics (§7.2) are implemented as overwrite instead of the specified cumulative-total contract, because it wasn't obvious from `db.py` alone. | Medium — this is a real, already-present ambiguity in the existing code | Cost figures silently wrong; #39's whole purpose undermined. | This document states the contract explicitly (§7.2) precisely to close this gap before implementation, not after. |
| **RF-4** | Status enum lossy-mapping (§4.4.1) hides a real state (e.g., `blocked` shown identically to `running`) that the UI actually needs to distinguish. | Medium | Confusing UX; a job stuck needing something looks the same as one progressing normally. | Flagged explicitly as a decision for lane 1.1's owner, not silently absorbed. |
| **RF-5** | Full-reconciliation (§5.4) becomes a heavy, unbounded `GET /ledger/jobs` call as job history grows. | Medium over time | Slow app-foreground / reconnect UX. | `since` parameter already exists server-side; mirror always tracks its own watermark so reconciliation stays proportional to *new* activity, not total history. |
| **RF-6** | The pre-flight cost gate (§7.3) is built against the ledger's `POST /ledger/jobs`, but `estimate()` has no real data and produces a useless (always-pass or always-fail) heuristic. | High initially, by design | Gate exists but provides no real value until real cost data accumulates. | Explicitly acceptable per this document's stance (§7.3): "the mechanism ships; the policy waits for the answer" — mirrors the fleet plane doc's own accepted stance on `limits.usd`. |
| **RF-7** | Terminal-state guard on `record_decision` (§5.3) is not added server-side, and a stale offline decision silently overwrites a `failed` job back to `done`. | Medium — this is a real gap in current `db.py`, not hypothetical | Ledger shows an incorrect terminal state; server-wins conflict rule silently violated in the one case it matters most. | Called out explicitly in §5.3 as required, not assumed already handled. |

---

## 11. Tradeoffs

### 11.1 Candidates compared (for the mirror's staleness/conflict mechanism, §5.3)

| | Candidate |
|---|---|
| **A** | **`server_authoritative_version` counter** (recommended) — cheap integer comparison, already half-built server-side |
| **B** | **`updated_at` timestamp comparison** — already fully on the wire, zero server changes needed |
| **C** | **Vector clocks / CRDT merge** — general-purpose conflict resolution |

| Dimension | A — version counter | B — timestamp | C — CRDT |
|---|---|---|---|
| Complexity | Low — one int column exposed on the wire | **Lowest** — nothing to add | High — real distributed-systems machinery for a single-writer system |
| Precision | Exact ("behind by N writes") | Coarse (same-millisecond updates indistinguishable) | Exact, but overkill |
| Server changes | One field addition (§4.3) | **None** | Significant |
| Fits the "server always wins" model | Yes — directly | Yes, adequately | Wrong tool — CRDTs are for multi-writer merge, and SB1 guarantees there's only ever one writer |

**Chosen: A.** B is the fallback if the version field addition is deprioritized — it is
strictly worse but not wrong, and this document does not block the mirror's basic
correctness on the version field landing first. C is rejected outright: this system has
exactly one authoritative writer by design (SB1); CRDT-style merge solves a problem this
architecture doesn't have.

### 11.2 The dominant tradeoff for this whole document

**Do the minimum to wire two already-correct systems together, versus redesigning either
one.** Every gap identified above (version field, cost accumulation contract, terminal-state
decision guard) is a **small, additive** change to an existing, tested component — never a
redesign. This is deliberate: `services/ledger/` already has passing tests for the hard
parts (durability, idempotency, schema parity); the risk in this lane is entirely in the
**client-side wiring and the handful of real gaps enumerated above**, not in the server
design itself.

---

## 12. Migration and Rollout Plan

### Stage L1 — Server-side additive changes *(1 lane · small, isolated changes to `services/ledger/`)*

- Add `version` field to `Job` wire model (§4.3).
- Add terminal-state guard to `record_decision` (§5.3 / RF-7).
- Document (in `services/ledger/README.md`, not this doc) the cost-accumulation contract
  (§7.2) so the fleet plane's broker implementation has an authoritative reference when it
  is built.
- **Done when:** existing test suite still passes unmodified; new tests added for the
  version field's presence and the terminal-state guard's rejection behavior.

### Stage L2 — `ServerLedgerRepository` (Android) *(1-2 lanes)*

- REST client for all six endpoints (§4.1).
- SSE subscriber feeding a merged `Flow<List<LedgerEntry>>`.
- Write-through to the existing lane-1.5 mirror schema on every confirmed server response.
- **Done when:** `LedgerRepository`'s existing test suite (whatever exercises the
  interface today against `InMemoryLedgerRepository` or a fake) passes unmodified against
  `ServerLedgerRepository` pointed at a real `services/ledger/` instance — proving the
  interface substitution is transparent to existing callers.

### Stage L3 — Mirror-mode and offline decision queue *(1 lane · gated on L2)*

- Durable offline write queue for `recordDecision` (RF-1).
- Reachability detection + mirror-mode fallback (§5.5).
- Full-reconciliation-on-reconnect (§5.4).
- **Done when:** the cross-layer test scenarios in §6.2 pass.

### Stage L4 — Cost gate mechanism *(1 lane · gated on fleet plane Stage F1, since the gate's context depends on `target.labels` the broker resolves)*

- Pre-flight estimate + attention-based gate (§7.3), with a placeholder ceiling.
- **Done when:** a job whose estimated cost exceeds the (placeholder) ceiling starts in
  `needs_attention` and resolves via the existing decision path, with no new endpoint.
- **Explicitly not done here:** setting the real ceiling number (fleet plane doc §12 item
  3, still open).

### Rollback plan

| Stage | Rollback |
|---|---|
| L1 | Additive fields/guards; revert the two small server changes, existing behavior unaffected. |
| L2 | Re-point Android's DI binding back to whatever `LedgerRepository` implementation preceded it (e.g., lane 1.5's local-only version) — one binding change, since the interface never changed. |
| L3 | Disable mirror-mode fallback (treat every disconnect as a hard error surfaced to the user); offline queue becomes inert but doesn't need to be removed. |
| L4 | Cost gate is additive attention-gating logic; removing it reverts jobs to always starting `accepted`. |

---

## 13. Success Metrics

### 13.1 Binary correctness gates

| # | Gate |
|---|---|
| **LG-1** | **Idempotent create holds end-to-end.** A retried `dispatch_to_fleet` tool call (same `tool_call_id`) never produces two jobs, verified through the real Android client, not just the server test. |
| **LG-2** | **Zero lost events, cross-layer** (#38). All three scenarios in §6.2 pass. |
| **LG-3** | **Decision round-trip resumes the fleet job** (#32). §8.3's test passes end-to-end, including the fleet plane's `send_input` delivery. |
| **LG-4** | **Attention query parity.** After any reconnect/reconciliation, the client's locally-filtered attention list exactly matches `GET /ledger/attention`. |
| **LG-5** | **Terminal state cannot be overwritten by a stale decision.** RF-7's guard rejects/no-ops a decision against an already-terminal job. |
| **LG-6** | **Cost figures are cumulative, not overwritten**, verified by a multi-PATCH sequence against a single job asserting monotonic (non-decreasing, summed) `cost.usd`. |

### 13.2 Experience targets

| Metric | Target |
|---|---|
| Mirror staleness on reconnect (event → reconciled in local state) | < 5s on a normal network transition |
| Offline decision queue flush latency after reconnect | < 2s for a single queued decision |
| Full-reconciliation payload size (steady state) | Bounded by `since` watermark — should not scale with total job history |

### 13.3 Product-truth signals

| Signal | What it tells you |
|---|---|
| Frequency of RF-7's guard actually firing in production | Whether the offline-decision-vs-server-timeout race (§5.3) is a real, common occurrence or a theoretical edge case |
| Mirror-mode duration distribution | Whether offline usage is a real pattern worth more investment, or rare enough that §5.5's fallback is sufficient as-is |
| Cost-gate false-positive rate once L4 ships | Whether `estimate()`'s heuristic needs real modeling investment before the ceiling number even matters |

---

## 14. Open Questions Returned to the Product Owner / Other Lane Owners

1. **Status enum divergence (§4.4.1).** Does the UI need to distinguish `blocked` from
   `running`, or `failed` from `cancelled`? If yes, `LedgerRepository.Status` (lane 1.1)
   needs new values — not something this document adds unilaterally to a foundational
   interface.
2. **Fan-out cost rollup (§7.2).** Deferred until fleet plane Stage F2 (fan-out) ships;
   flagged now so it isn't discovered as a surprise later.
3. **Cost ceiling number** — inherited unresolved from the fleet plane doc (§12 item 3);
   this document ships the mechanism (§7.3) without it.
4. **Where the cost gate lives** (ledger service vs. broker, §7.3) — leaned toward broker,
   not mandated.
