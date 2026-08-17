# Vela — AI Chief of Staff (S4′ Rebuild) — Agent Context

> **Read this first before making any changes.**
> This file is the source of truth for any AI agent working on this codebase.
> **This file documents the *new* system, not the previous one.** Refer to the archive branch (`archive/pre-rebuild-2026-08`) and `docs/PRESERVED_LESSONS.md` for lessons from the old architecture.

---

## What Vela Is (New Architecture)

Vela is a **mobile-first AI chief of staff** for real work across a fleet of machines. The primary interaction is **real-time voice** on an Android phone. The intelligence runs on a remote service (`vela-agentd`). The phone surfaces a durable ledger of ongoing work via a swipeable card deck and chat interface. Notifications alert to genuine decisions needed, not progress noise.

---

## Architecture at a Glance (S4′)

```
Android Phone                          Remote Host (vela-agentd)
┌──────────────────────────┐           ┌─────────────────────────────────┐
│ Vela App                 │           │ vela-agentd (thin fork)          │
│                          │  C1 HTTP  │ of amplifier-agent HTTP face     │
│ ├─ Voice transport       │◄─────────►│                                  │
│ ├─ Host tools            │ C2 SSE    │ ├─ chat-completions (C1)        │
│ ├─ Card deck (attention) │◄─────────►│ ├─ events + approval (C2)       │
│ ├─ Chat/transcript       │ C3 REST   │ ├─ ledger (C3 proxy/impl)       │
│ └─ Notifications         │◄─────────►│ └─ approval gate (F2)           │
│                          │           │                                  │
│ Ledger:                  │           │ Ledger store (durable):         │
│ ├─ Local SQLite mirror   │           │ ├─ Job tracking                 │
│ └─ Sync with server      │           │ └─ Event history                │
└──────────────────────────┘           └─────────────────────────────────┘
                                                │
                                         (contract only)
                                                │
                                      ┌─────────▼──────────┐
                                      │ Fleet execution    │
                                      │ plane (separate)   │
                                      │ muxterm/Claude/... │
                                      └────────────────────┘
```

---

## Design Document

**Full design:** `docs/designs/2026-08-16-vela-chief-of-staff-rebuild.md`

**Preserve this reference.** The design document is the authoritative specification for the entire system — architecture, components, boundaries, assumptions, success metrics, and stage-wise rollout plan.

---

## Stage 0 Status

**Stage 0 — Preservation and clearing** is COMPLETE.

- ✅ Archive branch created: `archive/pre-rebuild-2026-08` (preserves full pre-rebuild tree)
- ✅ Lessons extracted: `docs/PRESERVED_LESSONS.md` (8 critical lessons from the old system)
- ✅ Documentation carried forward: `docs/reference/` (phase plans, openapi.json)
- ✅ `main` cleared for fresh start (old code, old gradle, old rust crates deleted)
- ✅ New `AGENTS.md` written (this file)

**What lives on the archive branch (preserved but unused):**
- Old Kotlin Android app (~40K LOC)
- Old Rust crates (~8.7K LOC, proof-of-concept engine)
- Old amplifierd integration code
- All phase docs (now also in `docs/reference/` for easy access)

**When to consult the archive branch:**
- Understanding how the old app handled connectivity/notifications/streaming
- Reference implementation of SSE parsing (with anti-patterns documented)
- Rust crate designs (fallback if assumption A1 becomes false)
- Lessons in `docs/PRESERVED_LESSONS.md` (linked from this file below)

---

## Critical Lessons (from `docs/PRESERVED_LESSONS.md`)

Read these BEFORE implementing any code that touches:

### 1. **Tool-Call Serialization Format Divergence**
- SSE format: `{name, input}` inside `content` array
- Transcript format: `{tool, arguments}` in top-level `tool_calls` array
- **Confusing them silently drops tool calls in production**
- Reference: `docs/PRESERVED_LESSONS.md` §1, archive branch `SessionTranscriptNormalizer`

### 2. **Session Status Strings**
- Correct values: `"executing"`, `"idle"`, `"failed"`, `"completed"`
- WRONG (and never used): `"running"`, `"active"`, `"waiting"`
- **Using wrong values produces permanently-empty active-session list**
- Lesson: Hard-code constants, use them everywhere, add compile-time assertions
- Reference: `docs/PRESERVED_LESSONS.md` §2

### 3. **SSE Stream Ordering: Must Open Before POST**
- **WRONG**: POST `/execute`, then GET `/events` — early events lost to race
- **CORRECT**: GET `/events` first (opens stream, server replays from seq 1), then POST
- **Why**: amplifierd's event stream is durable with sequence numbers; clients get full replay when they connect
- Reference: `docs/PRESERVED_LESSONS.md` §3

### 4. **Multi-Network Connectivity Model (5-State Machine)**
- States: Unknown → Checking → {Reachable(url), Unreachable}
- Must try multiple URL candidates: Tailscale → LAN → SSH, in order
- Must persist last-known state (survives app kill)
- **Why**: Node can move between networks; LAN-only state machine breaks when WiFi is lost
- Reference: `docs/PRESERVED_LESSONS.md` §4, archive branch `AmplifierdRepository.candidateUrls()`

### 5. **Foreground Service + Notification Pattern**
- Hold FGS lock only during active sessions (user speaking, listening, events flowing)
- Release immediately when done (respects Android's 6h/day FGS budget)
- Notification channel persists, service cycles on/off
- **Why**: Voice sessions that drain battery or block other apps fail; app gets force-stopped by OS
- Reference: `docs/PRESERVED_LESSONS.md` §5, archive branch `SessionStreamingService`

### 6. **Parallel-Delegate Streaming Chunk Correlation**
- When multiple sub-agents run in parallel, streaming tokens arrive out-of-order
- Correct fix: "Claim first unclaimed tool call" semantics (not round-robin)
- **Lesson**: This bug recurs when C2 event processing is built; the fix is documented
- Reference: `docs/PRESERVED_LESSONS.md` §7, archive commit `eb207e74`

### 7. **Rust Crates as Fallback (Not Code Reuse)**
- 6 Rust crates preserved on archive branch (~8.7K LOC, 141 tests)
- **Why preserved**: If assumption A1 becomes false (amplifier-agent abandoned), these are the starting point for an engine redesign
- **Why not used in S4′**: A1 confidence is high; fork of Python amplifier-agent is the better bet
- **Read them if**: A1 breaks and a new engine is being considered; then re-learn what they encode
- Reference: `docs/PRESERVED_LESSONS.md` §6

### 8. **API Surface Regression Checking**
- `docs/reference/amplifierd-openapi.json` is the previous system's API
- When finalizing C1/C2/C3 surfaces, audit against it for unintentional feature loss
- Reference: `docs/PRESERVED_LESSONS.md` §8

---

## Three Channels: C1, C2, C3

All three are implemented by the **same service (`vela-agentd`)**, but they have distinct protocols and purposes.

### C1: Chat-Completions (stock OpenAI protocol)

**What it is:** Stock HTTP chat-completions API (Anthropic-compatible). **Unmodified from upstream `amplifier-agent`.**

**Used for:**
- Sending prompts and receiving streaming LLM output
- Client-declared host tools (the OpenCode pattern): calendar, notes, reminders, dispatch_to_fleet
- Streaming text and tool calls

**Protocol:**
```
POST /v1/messages
{
  "model": "claude-3-5-sonnet-20241022",
  "messages": [{role, content}],
  "tools": [
    {
      "name": "dispatch_to_fleet",
      "description": "...",
      "input_schema": {...}
    }
  ]
}

→ SSE stream: delta events (text, tool_calls, etc.)
```

**Tool-call response pattern (OpenCode / client-executed):**
1. Client receives `delta.tool_calls` chunk
2. Client executes tool locally (calendar lookup, etc.)
3. Client re-POSTs: `{role: "tool", name, tool_call_id, content}`
4. Turn resumes

**Why stock?** Keeps the fork minimal (B2), allows host-tool execution on the client, and lets `vela-agentd` be a drop-in replacement for stock `amplifier-agent serve`.

### C2: Events + Control (tee'd internal queue + approval gate)

**What it is:** A new channel exposing amplifier-agent's internal events (tool/started, tool/completed, sub-agent attribution) plus a real approval gate (replacing auto-approve).

**Used for:**
- Visibility: See what work is running (sub-agent name, tool name, timing)
- Narration: "Asking the database...", "Checking your calendar..." in real time
- Approval: Block privileged tools until user explicitly approves (voice + touch)
- Mid-turn steering: Eventually send messages mid-loop (Stage 4)

**Protocol:**
```
GET /events?session={sessionId}&types=tool%2Fstarted%2Ctool%2Fcompleted
→ SSE stream:
  event: tool/started
  data: {
    "tool_call_id": "toolu_01ABC",
    "tool": "bash",
    "agent_name": "research-delegate",
    "timestamp": "2026-08-16T20:15:00Z"
  }

  event: tool/completed
  data: {
    "tool_call_id": "toolu_01ABC",
    "status": "success",
    "duration_ms": 2340,
    "result_size_bytes": 4096
  }

  (approval gate fires if tool is privileged)
  event: approval/requested
  data: {
    "tool": "write_file",
    "reason": "PRIVILEGED",
    "timeout_seconds": 30
  }

  (client sends decision)
  event: approval/responded
  data: {
    "decision": "approve" | "deny",
    "response_time_ms": 2150
  }
```

**Why this channel?** The internal event queue inside amplifier-agent's HTTP face already captures these events (it has to, for logging). C2 just tees it before discarding it, adds an approval gate, and exposes it. This is what makes B2 achievable: a thin fork with a bounded delta.

### C3: Ledger (durable job tracking, separate from conversation)

**What it is:** A REST API + SSE stream for a durable ledger of fleet work.

**Used for:**
- Persistent job tracking (survives process restart)
- Visibility in the card deck (what work is pending, completed, failed)
- Correlation: Which LLM turn spawned which fleet job
- Replay: On app restart, fetch full ledger state without replaying conversation

**Protocol:**

```
POST /v1/ledger/jobs
{
  "origin": {
    "tool_call_id": "toolu_01ABC",
    "session_id": "sess_...",
    "tool_name": "dispatch_to_fleet",
    "turn_number": 3
  },
  "work_spec": {
    "target": "machine-42",
    "operation": "run_test_suite",
    "args": {...}
  }
}

→ {
  "id": "job_xyz",
  "status": "pending" | "running" | "completed" | "failed",
  "created_at": "...",
  "updated_at": "...",
  "result": {...}
}

GET /v1/ledger/jobs?session_id=sess_...
→ [{job objects}]

GET /v1/ledger/events?session_id=sess_...
→ SSE stream of job status changes
```

**Why separate from conversation?** Assumption A6: amplifier-agent's transcript reconciliation deletes orphaned `tool_use` blocks with no paired result. A pending fleet job with no result would be silently wiped on restart. The ledger is the source of truth, independent of the transcript.

---

## Assumptions and Risks

**Refer to `docs/designs/2026-08-16-vela-chief-of-staff-rebuild.md` §2 for the full list with confidence levels.**

Key load-bearing assumptions:
- **A1** (high): `amplifier-agent` remains actively maintained. If false, the thin-fork strategy breaks.
- **A3** (medium): Event tee doesn't perturb the chat-completions path. **V0 Spike S-1 must verify.**
- **A4** (high): Client-declared host tools work from Android over stock chat-completions. **V0 Spike S-2 must verify.**
- **A5** (high): Host tool completes the turn immediately even if the tool runs long. Means `dispatch_to_fleet` must return a handle, not a result.
- **A6** (high): Transcript reconciliation deletes orphaned tool_use blocks. Hence ledger must be separate.

---

## Success Criteria

**Binary gates (any failure blocks the milestone):**
- **G1**: Zero lost ledger events across app kill, server restart, network partition
- **G2**: Zero fleet dispatches with no ledger record (ledger write before fleet handshake)
- **G3**: Zero host tools exceeding 2s without handle-registration
- **G4**: Zero privileged tools reachable without approval gate consent

**Experience targets:**
- Voice turn-taking: p50 < 800ms, p99 < 1.5s (user stops → assistant begins)
- Dead air during work: p99 < 5s (gap between narration events)
- Approval → notification: p99 < 60s
- dispatch_to_fleet return: p99 < 1s (handle, not result)
- Turn-detection false interruptions: < 1 per 50 turns (this determines perceived quality)

---

## Roadmap (Stage Lanes)

**Full roadmap:** `docs/designs/2026-08-16-vela-chief-of-staff-rebuild.md` §12

### Stage 0 (COMPLETE)
- Preserve old tree on archive branch
- Extract lessons to `docs/PRESERVED_LESSONS.md`
- Clear main for fresh start

### Stage 1 (Foundations — parallel, 5 lanes + 3 spikes)
- **Lane 1.1**: Android scaffold (Compose, DI, navigation, interface contracts)
- **Lane 1.2**: Voice transport (LiveKit integration, turn detection, FGS)
- **Lane 1.3**: Host tools (calendar, notes, reminders, dispatch_to_fleet stub)
- **Lane 1.4**: Agent serve ops (stock amplifier-agent, supervised, reachable)
- **Lane 1.5**: Phone ledger (local SQLite, mirroring C3 schema)
- **Spike S-1**: Event tee (verify no chat-completions perturbation)
- **Spike S-2**: Host tool roundtrip (verify OpenCode pattern works from Android)
- **Spike S-3**: Handle dispatch (verify ledger survives transcript reconciliation)

**Deliverable:** D+ milestone = working voice product, no fork yet, phone-local ledger

### Stage 2 (Server ledger)
- **Lane 2.1**: Durable ledger service (C3 implementation, REST + SSE)
- Parallel with late Stage 1; no fork required

### Stage 3 (The fork + visibility)
- **Lane 3.1**: `vela-agentd` fork (event tee F1, approval gate F2, C2 route, C3 wiring)
- **Lane 3.2**: Android C2 client (consume tee'd events, narration, approval UI)
- Gated on S-1 and S-3 passing

**Deliverable:** S4′ milestone = fork works, full visibility, approval gate, narration

### Stage 4 (Upstream steering)
- **Lane 4.1**: Contribute steering to amplifier-agent (mid-turn message injection)
- Independent long-lead; lands when upstream accepts

---

## Running the Code

### Prerequisites

- Android device (Pixel 10 Pro or similar) with wireless debugging enabled
- Mac host running amplifier-agent (or muxterm sessiond, for fleet work)
- Tailscale network (for cross-network connectivity)
- Python 3.11+ for amplifier-agent and vela-agentd

### Building the Android App

```bash
cd android
./gradlew assembleDebug
```

### Running amplifier-agent (stock, for D+ milestone)

```bash
amplifier-agent serve chat-completions \
  --port 8410 \
  --bundle vela \
  --env ANTHROPIC_API_KEY=$API_KEY
```

### Running vela-agentd (forked, for S4′ milestone)

```bash
cd services/vela-agentd
./run-server.sh --port 8410
```

---

## ADB Commands (Debugging)

```bash
# Discover device
DEVICE=$(./scripts/vela-device)

# Watch app logs
adb -s $DEVICE logcat --pid=$(adb -s $DEVICE shell pidof com.vela.app)

# Screenshot
adb -s $DEVICE shell screencap /sdcard/vela.png && adb -s $DEVICE pull /sdcard/vela.png .

# SQLite inspection
adb -s $DEVICE shell run-as com.vela.app sqlite3 /data/data/com.vela.app/databases/vela.db \
  "SELECT * FROM jobs ORDER BY created_at DESC LIMIT 10;"
```

---

## Preserved Knowledge

**All 8 lessons from the old system are in `docs/PRESERVED_LESSONS.md`.** Read that file when:

- Building the Android C2 event client (reference the parallel-delegate bug fix)
- Implementing connectivity to vela-agentd (reference the 5-state machine)
- Implementing foreground services (reference the FGS lifecycle)
- Parsing tool calls (reference the SSE format divergence table)
- Comparing against the old API (reference docs/reference/amplifierd-openapi.json)

---

## Git Workflow

- **Main branch** is the active branch for Stage 1+ work
- **Per-stage lanes branch from main** (e.g., `lane/1-1-android-scaffold`)
- **Archive branch** (`archive/pre-rebuild-2026-08`) is read-only; preserves the full pre-rebuild tree
- Rebasing lanes onto main is safe and encouraged (keeps history clean)

---

## Questions?

1. **What was the old system?** See the archive branch and `docs/PRESERVED_LESSONS.md`
2. **What am I building?** See `docs/designs/2026-08-16-vela-chief-of-staff-rebuild.md`
3. **What's my current lane?** Check the lane definition in §12 of the design doc
4. **What's my stop condition?** Check the "Done when" section for your lane
5. **Did something similar fail before?** Search `docs/PRESERVED_LESSONS.md`

---

Last updated: 2026-08-16
Design owner: Ken Kraatz
