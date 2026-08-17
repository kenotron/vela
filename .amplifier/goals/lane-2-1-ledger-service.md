# Lane 2.1 — ledger-service

**Outcome:** A standalone, durable server-side ledger service exists implementing the full C3
REST API and the `/ledger/events` SSE stream (design doc §4.2), requiring no fork of
`amplifier-agent`.

**Working directory / branch / base SHA:** worktree only, branch `lane/2.1-ledger-service`,
base SHA `dccf2daf`. Work ONLY in this worktree.

**File ownership:** `services/ledger/`, `ops/ledger/`. Read-only reference to
`android/ledger/README.md` and `android/ledger/src/main/java/com/vela/ledger/JobEntity.kt`
(lane 1.5, already merged) for schema alignment — do not modify Android code.

**Complete when** either every item below reaches a terminal state, **or** it is conclusively
demonstrated the remainder cannot, naming the blocker for each. Terminal states:
PASS / FAIL-named / BLOCKED-named / PENDING-HUMAN.

Items:
1. All C3 endpoints implemented and documented: `POST /ledger/jobs`, `GET /ledger/jobs`,
   `GET /ledger/jobs/{id}`, `PATCH /ledger/jobs/{id}`, `POST /ledger/jobs/{id}/decision`,
   `GET /ledger/attention`, `GET /ledger/events` (SSE).
2. The job resource schema matches `android/ledger`'s `JobEntity`/`JobRecord` field-for-field
   (job_id, created_at, updated_at, origin{session_id,turn_id,tool_call_id}, spec, status,
   attention{required,reason,options,deadline}, progress[], result, cost{usd,tokens}) — verify
   with an explicit schema-comparison test or documented mapping table, not by inspection alone.
3. `/ledger/events` SSE stream works: a client connects, a job is created/updated, the client
   receives the corresponding event within a bounded time window.
4. Durable by construction from commit one — **never an in-memory-only phase**. Verify G1 (zero
   lost events) across a service restart: inject a known-cardinality sequence of job
   creates/updates, kill and restart the service process, confirm the final state matches
   exactly what should have survived.
5. G2 enforceable: job creation is idempotent on `origin.tool_call_id` — creating a job twice
   with the same `tool_call_id` does not produce two ledger rows; verify with a direct test.
6. The Android `LedgerRepository` interface (lane 1.1) could swap from the current local-only
   `SqliteLedgerRepository` (lane 1.5) to a server-backed implementation using this service as
   the source of truth and the local DB as a mirror — you do not need to implement that swap
   in Android code (out of scope, see below), but the server API and schema must actually
   support it: demonstrate this with a plain HTTP client test exercising the full job
   lifecycle (create → progress → attention.required → decision → done) end-to-end against
   this service.

**Host capability limits:** headless Linux machine — this is a server-side service, fully
testable via HTTP/CLI, no emulator needed. No blockers expected from headlessness itself;
if a real durable-store dependency (e.g. a specific external DB) is unavailable, use an
embedded durable store (SQLite/similar) rather than in-memory, and document the choice.

**Commit early, push always.** Never merge to main yourself.

**Time bound:** 6 hours wall-clock. Exceeding it is a terminal BUDGET state.

**DONE.json:** write to the worktree root as your final act (`.gitignore` already covers it).
Fields: `lane, session_id, verdict (COMPLETE|BLOCKED|PARTIAL), branch, head, pushed, items[],
residuals[], pending_human[], suite`.

## SCOPE-OUTS
- No changes to Android code (`android/ledger/`, `android/core-domain/`) — schema alignment is
  verified by comparison/testing, not by editing the Android side to match. If a genuine
  incompatibility is found, record it as a residual for a future reconciliation lane.
- No fork of `amplifier-agent` — this service is deliberately standalone and can ship before
  any fork exists (design doc §10 Stage 2). It may later be folded into `vela-agentd` or stay
  separate; either is viable and out of scope for this lane to decide.
- No real fleet-plane integration — this lane only proves the ledger API surface, not fleet
  dispatch itself.

## KNOWN
- F-5 in the design doc names ledger loss on restart as low-likelihood but catastrophic —
  durability from day one is non-negotiable, not a follow-up optimization.
- Lane 1.5's phone-ledger schema (already merged) is the reference this service's schema must
  not diverge from.
- muxterm is available in this environment for isolated session/process management.
