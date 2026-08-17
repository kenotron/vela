# FORK_POINTS.md — vela-agentd

Living document tracking every point where `services/vela-agentd/` diverges
from its upstream source, `amplifier-agent`'s HTTP face
(`amplifier_agent_http` + `amplifier_agent_lib`, vendored from
`/home/ken/workspace/claude-code/amplifier-agent/src/`). Per design doc
§7.4 signal 1, the target is **≤4 localized fork points**. This document
names each one, its upstream location, and its rationale, and calls out any
count-exceeding residual explicitly rather than hiding scope.

## Vendoring note (not counted as a fork point)

The entire `amplifier_agent_http`, `amplifier_agent_lib`, and
`amplifier_agent_cli` packages were copied verbatim into
`services/vela-agentd/src/` and mechanically renamed
(`amplifier_agent_* → vela_agentd_*`) via a global import-path substitution.
This is a **build/packaging** transform, not a behavioral fork — every
upstream file's logic is byte-for-byte identical except for the import
prefix. `amplifier_agent_cli` is vendored in full because
`vela_agentd_http.app` imports from `vela_agentd_cli.admin.models` and
`vela_agentd_cli.provider_sources` at startup (provider enumeration); it is
otherwise unused by the HTTP face and not itself modified beyond the same
mechanical rename.

The F1 event tee (`HttpQueueDisplaySystem.tee_queue` in
`vela_agentd_lib/protocol_points/defaults_http.py`) was **already present**
in the fork source at vendor time — S-1's proven diff had already been
merged upstream before this lane's recon. No additional F1 edit was needed
beyond the mechanical rename; `defaults_http.py` is otherwise untouched.

## The 4 localized fork points

### 1. `vela_agentd_http/app.py` — process-wide C2/F2/F4 state + router mounts

- **Upstream location:** `amplifier_agent_http/app.py`, `lifespan()` and
  `build_app()`.
- **What changed:** the lifespan now constructs and stashes three new
  pieces of process-wide state on `app.state`: `c2_broadcaster`
  (`EventBroadcaster`, F3), `approval_gate` (`ApprovalGate`, F2), and
  `ledger_client` (`LedgerProxyClient`, F4). `build_app()` mounts three new
  routers (`events`, `approvals`, `ledger`) alongside the untouched
  `models`/`chat_completions` routers.
- **Rationale:** this is the single, obvious place to wire new
  process-lifetime singletons — mirrors exactly how upstream already wires
  `app.state.prepared`, `app.state.agent_configs`, etc. No upstream state or
  control flow is removed or reordered; only additive lines are inserted.

### 2. `vela_agentd_http/routes/chat_completions.py` — approval swap + C2 tee wiring

- **Upstream location:** `amplifier_agent_http/routes/chat_completions.py`,
  the `chat_completions()` handler (around where `display` and `approval`
  are constructed) and `_stream_chat_completion()`'s cleanup block.
- **What changed:**
  - `approval = HttpAutoApprovalSystem()` becomes
    `approval = request.app.state.approval_gate` (falling back to the
    upstream auto-approve class only if the gate isn't wired — defensive,
    not a behavior change for the normal path). This is F2.
  - A second bounded tee queue is created and drained by a new
    `_drain_tee_to_c2()` coroutine (mirrors the existing, untouched
    `_drain_tee_to_jsonl()` exactly: sentinel-terminated drain loop, reads
    from its own queue, can never perturb C1). This is F3's wiring into the
    proven F1 tee mechanism.
  - The new `c2_tee_task` follows the identical drain-then-cancel cleanup
    discipline already used for `tee_task` (JSONL).
- **Rationale:** this file is the seam where `display` and `approval` are
  instantiated per-request; F2 and F3 both need to plug in at that seam,
  and reusing the proven tee-queue pattern (rather than inventing a new
  fan-out mechanism) keeps F1's non-perturbing guarantee intact by
  construction — verified in `tests/test_c1_identity.py`.

### 3. New route modules — `routes/events.py`, `routes/approvals.py`, `routes/ledger.py`

- **Upstream location:** none — net-new files, no upstream equivalent.
- **What they add:** `GET /v1/events` (F3, C2 SSE stream, §4.2 payload
  shapes via `_c2_shapes.py`), `POST /v1/approvals/{id}/decision` (F2's
  control route), and the `/ledger/*` proxy routes (F4, forwarding to
  `services/ledger` via `_ledger_proxy.py`, reimplementing none of its
  logic).
- **Rationale:** counted as one fork point because all three are additive
  route registrations following the exact `APIRouter` + `Depends(require_bearer)`
  pattern upstream's own `routes/models.py` and `routes/chat_completions.py`
  already use — no new auth or routing mechanism was introduced.

### 4. New support modules — `_c2_broadcaster.py`, `_c2_shapes.py`, `_approval_gate.py`, `_ledger_proxy.py`

- **Upstream location:** none — net-new files, no upstream equivalent.
- **What they add:** the `EventBroadcaster` (mirrors lane 2.1's
  `services/ledger/ledger_service/events.py` `EventBroadcaster` pattern,
  read-only reference, not modified), the §4.2 payload shaper, the real
  approval gate (suspend → publish on C2 → await decision or bounded
  timeout-deny), and the ledger `httpx` forwarding client.
- **Rationale:** counted as one fork point because these are the
  self-contained "bricks" implementing F2/F3/F4's actual logic — kept out
  of upstream's files entirely (points 1–2 above are only the *wiring*,
  these are the *implementation*). Grouped as one point because they share
  a single design rationale: new capability, zero modification of existing
  kernel/session-runner/protocol-point logic.

## Running count vs. the ≤4 budget

**4 of 4.** At budget. No residual to declare at this time — every F1–F4
change fits within the localized-fork-point ceiling. If a future item (e.g.
mid-turn steering, out of scope per this lane's SCOPE-OUTS) requires
touching upstream files again, it MUST be evaluated against this same
budget and any excess explicitly named here per §7.4 signal 1 rather than
silently expanding scope.

## What was deliberately left untouched

- `vela_agentd_lib/protocol_points/defaults_http.py` — `HttpQueueDisplaySystem`
  (F1 tee) and `HttpAutoApprovalSystem` (superseded per-request by
  `ApprovalGate`, but the class itself is left in place as a fallback / for
  any other caller) are both untouched.
- `vela_agentd_http/_event_translator.py` — C1's `_DROPPED_EVENT_TYPES`
  filtering logic is untouched; the C2 tee reads the raw pre-filter stream
  independently.
- `vela_agentd_http/_wire.py`, `_auth.py`, `_config.py`, `_reconciler.py`,
  `_host_tool_signal.py`, `_session_runner.py` — untouched (aside from the
  mechanical import-path rename).
- `spikes/s1-event-tee/` and `services/ledger/` — read-only references per
  this lane's ownership; never modified.
