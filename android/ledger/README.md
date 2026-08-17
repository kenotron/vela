# ledger

Local SQLite (Room-backed) implementation of the C3 job resource ("phone-ledger"),
per the design doc's §4.2 C3 Ledger REST API. Provides the D+ milestone's durability
story and, later, the offline mirror of the server-side ledger (Stage 2, lane 2.1).

## Schema vs. the C3 job resource

The design doc (`docs/designs/2026-08-16-vela-chief-of-staff-rebuild.md`, §4.2) defines
the job resource as:

```
job {
  job_id            uuid
  created_at        ts
  updated_at        ts
  origin            { session_id, turn_id, tool_call_id }
  spec              { … }
  status            accepted | running | needs_attention | blocked | done | failed | cancelled
  attention         { required: bool, reason: str, options: [...], deadline?: ts }
  progress          [ { ts, message, percent?, source } ]
  result            { … } | null
  cost              { usd?, tokens? }
}
```

`JobEntity` (the Room storage row, `src/main/java/com/vela/ledger/JobEntity.kt`) maps
each field 1:1, flattening nested objects into columns:

| C3 field | JobEntity column(s) |
|---|---|
| `job_id` | `job_id` (PK) |
| `created_at` | `created_at` |
| `updated_at` | `updated_at` |
| `origin.session_id` | `origin_session_id` |
| `origin.turn_id` | `origin_turn_id` |
| `origin.tool_call_id` | `origin_tool_call_id` |
| `spec` | `spec_json` (serialized) |
| `status` | `status` (stored as the wire string, e.g. `"needs_attention"`) |
| `attention.required` | `attention_required` |
| `attention.reason` | `attention_reason` |
| `attention.options` | `attention_options_json` (serialized list) |
| `attention.deadline` | `attention_deadline` (nullable) |
| `progress` | `progress_json` (serialized list, append-only via `appendProgress`) |
| `result` | `result_json` (nullable, serialized) |
| `cost.usd` | `cost_usd` |
| `cost.tokens` | `cost_tokens` |
| — (not in C3) | `server_authoritative_version` — see below |

The public API (`JobRecord`, `src/main/java/com/vela/ledger/JobRecord.kt`) is the
nested shape matching the C3 resource exactly; `SqliteLedgerRepository` maps between
the two internally, so callers never see the flattened storage representation.

Serialization for `spec`, `attention.options`, `progress`, and `result` uses a small
hand-rolled JSON encoder/decoder (no `kotlinx-serialization` dependency was already
present in this project, and the value shapes here — string lists, small flat progress
records — don't justify adding one). See `SqliteLedgerRepository.kt`.

## Future sync/mirror mode

Two fields exist solely to support the **not-yet-built** sync/mirror mode described in
the design doc (nothing in this lane implements sync logic — this is schema-only
preparation, per the lane's explicit scope-out):

- **`updated_at`** — already part of the C3 resource itself; row-level last-write
  timestamp. A future sync process will compare this against the server's `updated_at`
  to detect which side has the newer write.
- **`server_authoritative_version`** — NOT part of the C3 wire resource. A local-only
  counter that increments on every local write (`createJob`, `updateStatus`,
  `appendProgress`, `recordDecision`). When Stage 2's server-side ledger (lane 2.1)
  exists, a future sync process can compare this local version against a
  server-assigned version to decide whether the local row is stale, ahead, or in
  conflict, and drive last-write-wins or manual-merge conflict resolution. No such
  comparison logic exists yet — only the counter and the invariant that it advances
  on every local mutation.

When the sync/mirror mode is eventually built, this schema should not need to change:
the fields it needs are already present.

## Public API

- `SqliteLedgerRepository.createJob(job: JobRecord)`
- `SqliteLedgerRepository.updateStatus(jobId, status, updatedAt)`
- `SqliteLedgerRepository.appendProgress(jobId, entry, updatedAt)`
- `SqliteLedgerRepository.recordDecision(jobId, decision)` — updates status and clears
  `attention.required`
- `SqliteLedgerRepository.getJob(jobId): JobRecord?`
- `SqliteLedgerRepository.observeAll(): Flow<List<JobRecord>>`
- `SqliteLedgerRepository.observeAttentionQueue(): Flow<List<JobRecord>>` — the card
  deck's backing query (jobs where `attention.required == true`); rendering the deck
  itself is lane 1.1's concern.

**This is a separate API from `com.vela.core.domain.LedgerRepository`** (owned by lane
1.1). That interface's `LedgerEntry` is a simpler flat model that predates the full C3
job resource shape and does not carry `origin`, `spec`, `progress`, or `cost`. This
lane's `SqliteLedgerRepository` does not implement that interface. Reconciling the two
— e.g. making the simpler domain interface a view over this richer store — is recorded
as a residual for a future lane, not attempted here.

## Testing

This host has no `/dev/kvm`, so no Android emulator is available (the same blocker
already recorded in lane 1.1). Per the lane's host capability limits, items 2 and 5
(app-kill/reboot persistence, zero-lost-events under force-stop) are verified instead
with Robolectric JVM tests against a **file-backed** (not in-memory) Room database:
write, close the database with no extra flush beyond normal commit semantics, then
open a **new** `LedgerDatabase` instance at the same file path and assert the
read-back state. See the header comments in `PersistenceAcrossRestartTest.kt` and
`ZeroLostEventsTest.kt` for the exact substitution rationale.

Run tests: `./gradlew :ledger:test`
