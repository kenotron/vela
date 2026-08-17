# Vela — Mobile-First AI Chief of Staff (Full Rebuild)

**Date:** 2026-08-16
**Status:** Validated design — ready for `/goal-batch` decomposition
**Design mode:** systems-design (ANALYZE → DESIGN → refinement, three architect passes)
**Supersedes:** the entire existing Vela Android application and the remote-`amplifierd` architecture documented in `AGENTS.md`

> **This is a from-scratch rebuild, not a refactor.** The existing ~40,000-line Kotlin
> application is being retired wholesale. Stage 0 of the migration plan is mandatory,
> blocking, and non-negotiable: preserve the current tree on an archive branch, then clear
> `main`. No stage may begin before Stage 0 completes.

---

## 1. Problem Framing

### 1.1 What is being asked

Build a mobile-first AI "chief of staff": an Android application whose primary interaction
mode is **real-time voice**, backed by an agent that can do real work across a fleet of
machines, that keeps a **durable ledger** of ongoing work, and that surfaces only what
genuinely needs human attention — via Android notifications and a swipeable card deck —
rather than a firehose of progress noise.

Five capabilities were stated as requirements:

| # | Requirement | Restated as a system property |
|---|---|---|
| R1 | Real-time voice chat chief of staff | Sub-second conversational turn-taking against an agent whose real work takes 10–60s+ |
| R2 | Swipeable card deck of visualizations / relevant info | An **attention queue** with a decision surface, not a feed |
| R3 | Chat pane + transcript surface | A text-modality peer to voice, and a durable historical record |
| R4 | Speaks muxterm `sessiond` protocol and/or MCP | A **dispatch contract** to an external execution plane |
| R5 | "Channels" across muxterm + Claude Code + opencode + Amplifier for hyperscale fan-out | A **fleet execution plane** with cross-machine job dispatch |

### 1.2 What the system actually needs (as distinct from what was said)

Restating R1–R5 as system properties exposes that these are **not five features of one
component**. They decompose into four architecturally distinct concerns with different
failure modes, different latency budgets, and different ownership:

1. **A conversational transport** with a hard sub-second turn-taking budget (R1, R3).
2. **An intelligence plane** that runs agent loops with tools, sub-agents, and skills — and
   whose turns routinely exceed the conversational budget by 1–2 orders of magnitude.
3. **A durable work ledger** that survives process restart, app kill, and network partition,
   and which is the *source of truth* for "where are we at" (R2, and the persistence
   requirement stated separately).
4. **A fleet execution plane** that dispatches work to other machines and reports back (R4, R5).

The single most important framing insight, which shapes everything downstream:

> **The conversational latency budget and the agent work budget are irreconcilable
> in one loop.** Voice requires a response to begin in ~800ms. Agent turns with tool
> loops and delegation routinely take 10–60s+. Every product surveyed that tried to
> paper over this failed on the same axis. The architecture must contain an explicit
> **fast-tier / slow-tier split with a defined handoff**, not a hope that the agent
> will be fast enough.

### 1.3 Scope of this document

**In scope:** the Android client, the intelligence/service tier (`vela-agentd`), the ledger,
the voice transport choice, and the **contract** the fleet execution plane must satisfy.

**Explicitly out of scope — deferred to a separate design:** the fleet execution plane
itself (muxterm `sessiond` driving, cross-machine channel semantics, hyperscale fan-out
scheduling, per-machine credential handling). This document specifies only the *interface*
that plane must present. Attempting to design both in one document would produce a document
neither half could be executed against.

### 1.4 Why the existing application is being discarded

The user's position is unambiguous and does not require architectural justification —
but the design record should note that it is also *technically defensible*:

- The existing app is built around **remote `amplifierd`**, a daemon the team is no longer
  investing in. `amplifier-agent` is where the team's effort actually goes.
- Its architecture assumes **SSH-bootstrapped remote nodes** with an app-managed launchd
  plist, API-key injection, and node-registry state — an operational model that exists to
  serve a daemon we are moving off of.
- It has **no voice path at all**. Voice is not a feature that bolts onto this app; it is
  the primary interaction mode of the new product and dictates the transport, the service
  lifecycle, and the notification model.
- Its predecessor architecture (on-device Rust/JNI kernel) was already abandoned once, and
  the current architecture is the *second* abandoned direction. A third incremental pivot
  on the same tree would inherit two layers of vestigial structure.

Discarding the tree is correct. **Discarding the hard-won knowledge inside it is not** —
see §10 (Migration), Stage 0, for exactly what is preserved and why.

---

## 2. Explicit Assumptions

Numbered, falsifiable, with confidence and consequence-if-false. These are the places this
design breaks silently if reality differs.

| # | Assumption | Confidence | If false |
|---|---|---|---|
| **A1** | `amplifier-agent` remains the team's actively-maintained agent runtime for the life of this project. | High — stated directly by the product owner; commit activity confirms (HEAD within days). | The `vela-agentd` fork loses its rebase target. Fork becomes a hard maintenance burden rather than a thin delta. Mitigation is baked in: keep the fork to ≤4 localized change points (§8.2). |
| **A2** | The four fork points in `amplifier-agent`'s HTTP face are stable enough to rebase against over months, not weeks. | Medium — `_session_runner.py` is explicitly labeled a POC in its own source. | Rebase cost rises. Escalation: upstream the event-tee as a first-class feature (it is a general capability, not Vela-specific). |
| **A3** | The internal event queue inside the HTTP-face process carries `tool/started` / `tool/completed` with a populated `agentName` for delegated sub-sessions, and can be tee'd without perturbing the chat-completions path. | Medium-high — verified in source; **not yet verified under a second concurrent consumer.** | C2 (event channel) loses sub-agent visibility, or worse, introduces a race in the primary chat path. **This is V0 Spike #1 and blocks all C2 work.** |
| **A4** | A host-declared, client-executed tool (the OpenCode pattern) transfers cleanly to an Android client over the stock chat-completions wire. | High — the pattern is generic OpenAI function-calling; nothing OpenCode-specific in `amplifier-agent`'s handling. | The entire host-tool path (calendar, notes, reminders, `dispatch_to_fleet`) collapses and must move server-side, losing device-local access. **V0 Spike #2.** |
| **A5** | A host-tool call **ends the SSE stream immediately**, regardless of how long the tool takes on the client — therefore `dispatch_to_fleet` must return a handle, not a result. | High — confirmed in source; the tool-call signal deliberately escapes normal exception handling. | If *false* in the permissive direction (turn stays open), the design still works — it just means a blocking dispatch would also have been possible. No downside risk. |
| **A6** | Storing pending fleet-job state inside the conversation transcript is unsafe, because `amplifier-agent`'s transcript reconciliation deletes orphaned `tool_use` blocks with no paired result. | High — verified behavior. | If false, the ledger could have been simplified into the transcript. Not worth betting on; a separate ledger is defensible regardless. |
| **A7** | Voice turn-taking quality — not model quality — is the dominant driver of perceived product quality. | High — this was the single most consistent finding across every product surveyed (ChatGPT AVM/GPT-Live, Gemini Live, ElevenLabs, and the entire wearables cohort). | Effort is misallocated toward transport tuning that users don't notice. Cheap to detect: turn-taking metrics (§11) will show green while satisfaction stays low. |
| **A8** | Neither the phone nor the intelligence host needs an **inbound public port**. Both dial out. | High for LiveKit and Pipecat-over-Tailscale; **false** for vendor architectures where the vendor's cloud must reach our LLM endpoint. | Requires a public tunnel to a personal machine — a materially worse security posture. This assumption is load-bearing for the voice-vendor choice (§8.4). |
| **A9** | The fleet execution plane can accept a job spec and return a handle **synchronously in under one second**, deferring all real work. | Medium — muxterm's MCP surface and session-create path appear fast, but not measured. | `dispatch_to_fleet` cannot satisfy its <1s contract, and the whole dispatch path needs an intermediate queueing shim in `vela-agentd`. Detectable in V0 Spike #3. |
| **A10** | Android's foreground-service budget (6h/day for user-initiated FGS types) is sufficient for **voice sessions and event streaming**, because no agent loop runs on-device. | High — this is precisely why the on-device design was abandoned; moving the loop off-device is what makes the budget adequate. | Voice sessions get killed mid-conversation. Would force push-based wake-up (FCM high-priority) rather than a held foreground service. |

### 2.1 Unresolved — needs user input, not blocking this document

These four questions must be answered before or during execution. They do not block writing
the design; they *do* block specific lanes, noted inline.

1. **Fleet size and homogeneity.** How many machines? Same OS/toolchain, or heterogeneous?
   Always-on, or intermittently reachable? — *Blocks: fleet-plane design (separate doc); shapes
   the `dispatch_to_fleet` schema.*
2. **Concrete "attention" examples.** Three real examples of something that **needs a decision**
   versus three that are **just progress**. The notification rules are only as good as this
   distinction, and it cannot be inferred. — *Blocks: notification-rule tuning in Stage 3;
   does not block the card-deck UI shell in Stage 1.*
3. **Cost ceiling for fan-out.** Is there a per-day or per-job dollar limit on fleet work? Hyperscale
   fan-out across a fleet is the single largest cost-variance source in this design. — *Blocks:
   ledger cost-accounting fields; shapes whether dispatch needs a pre-flight cost estimate gate.*
4. **Preservation list confirmation.** §10 Stage 0 lists what the author believes is worth
   preserving from the existing app. This is a best-effort assessment. **Confirm or amend before
   Stage 1 begins** — after `main` is cleared, adding to the archive branch is awkward.

---

## 3. System Boundaries

```
┌─────────────────────────── OUTSIDE ────────────────────────────┐
│  LLM providers (Anthropic / OpenAI / …)                        │
│  Voice infrastructure (LiveKit Cloud or self-hosted SFU)       │
│  Android platform services (Calendar, Notifications, Auto, TTS)│
│  Fleet machines (muxterm sessiond, Claude Code, opencode, …)   │
└────────────────────────────────────────────────────────────────┘
        ▲                    ▲                        ▲
        │                    │                        │
┌───────┴────────────────────┴────────────────────────┴──────────┐
│                        INSIDE (this design)                     │
│                                                                 │
│  ┌──────────────────────┐        ┌───────────────────────────┐ │
│  │  Vela Android app    │        │  vela-agentd              │ │
│  │  (pure client)       │◀──────▶│  (thin fork of            │ │
│  │                      │  C1/C2 │   amplifier-agent's       │ │
│  │  · voice transport   │   /C3  │   HTTP face)              │ │
│  │  · card deck         │        │                           │ │
│  │  · chat/transcript   │        │  C1 chat-completions      │ │
│  │  · host tools        │        │  C2 event + control       │ │
│  │  · notifications     │        │  C3 ledger REST           │ │
│  │  · Android Auto      │        │                           │ │
│  └──────────────────────┘        └───────────────────────────┘ │
│                                              │                  │
│                                   ┌──────────┴──────────┐       │
│                                   │  Ledger store       │       │
│                                   │  (durable, server)  │       │
│                                   └─────────────────────┘       │
└─────────────────────────────────────────────────────────────────┘
                                              │
                              ══════ CONTRACT ONLY ══════
                                              ▼
                          ┌──────────────────────────────────┐
                          │  Fleet execution plane           │
                          │  (SEPARATE DESIGN — not here)    │
                          └──────────────────────────────────┘
```

### 3.1 Boundary rules (invariants)

| # | Rule |
|---|---|
| **B1** | **The Android app contains no agent loop, no kernel, no Rust, no NDK, no embedded Python.** It is a pure client. Any proposal that reintroduces an on-device execution engine is a boundary violation and requires a new design. |
| **B2** | **`vela-agentd` is a thin fork with a bounded delta.** Target: ≤4 localized change points against upstream `amplifier-agent`. If the delta grows past that, the change belongs upstream or the fork was the wrong call. |
| **B3** | **The ledger is never stored inside the conversation transcript.** (See A6 — transcript reconciliation actively deletes orphaned tool_use blocks; a pending job record would be silently wiped.) |
| **B4** | **The fleet plane is reached only through the dispatch contract** (§4.5). No component in this design knows how muxterm, Claude Code, or opencode actually work. |
| **B5** | **The voice model is never the brain.** It is ears and mouth. The intelligence plane owns the conversation, the tools, and the ledger. |

---

## 4. Components and Responsibilities

### 4.1 `vela-agentd` — the single owned service

A thin, rebaseable fork of `amplifier-agent`'s HTTP face (`src/amplifier_agent_http/`,
principally `_session_runner.py`). **Not a new custom Rust engine** — see §9.3 for why the
recovered Rust crates were rejected as the engine.

**Owns:**
- Session lifecycle (one `PreparedBundle` loaded at startup, a fresh `AmplifierSession` per
  turn — the pattern already shipped upstream and already concurrency-safe via a
  microsecond-scale creation lock).
- All three wire channels (C1, C2, C3).
- The approval gate (replacing the HTTP face's unconditional auto-approve).
- Ledger persistence and query.

**Does not own:** voice, notifications, UI, device-local data, fleet execution.

**Why a fork and not a wrapper:** the granular internal events (`tool/started`,
`tool/completed`, `progress`, `thinking/*`, `usage`, `error`) **already flow into a queue
inside the HTTP-face process today.** They are explicitly discarded one step before the wire,
with a source comment to the effect of "internal activity stays internal." The needed change
is to *tee* that queue, not to invent an event pipeline. That is a small, localized,
rebaseable delta — materially cheaper than any alternative that has to reconstruct the same
information from outside the process.

#### The four fork points (the entire delta)

| # | Fork point | Change | Risk |
|---|---|---|---|
| **F1** | Internal event queue consumer | Tee: fan out to a second consumer alongside the existing (discarding) one. | Race with the primary chat path. **V0 Spike #1.** |
| **F2** | Approval handling | Replace unconditional auto-approve with a real gate that suspends the tool call and emits an approval request on C2. | Deadlock if no client is attached; needs a timeout + configurable default (deny). |
| **F3** | New C2 route | Add an SSE (or WebSocket) route exposing the tee'd events + approval round-trip. | Additive; low risk. |
| **F4** | New C3 routes | Ledger REST API. | Additive; independently deliverable; can ship **before** the fork (§10 Stage 2). |

### 4.2 The three channels

#### C1 — Stock OpenAI chat-completions (unmodified)

Used exactly as `amplifier-app-opencode` uses it, and for the same reason.

**The mechanism:** the client declares its own tools in the `tools:` field of every request.
When the model selects one, the stream emits a `delta.tool_calls` chunk, the turn ends with
`finish_reason: "tool_calls"`, **the client executes the tool itself**, and re-POSTs the
result as a `{role: "tool", …}` message to continue.

**Why this matters:** `amplifier-agent`'s "internal activity stays internal" rule never bites
OpenCode — because OpenCode's tools never go through the hidden internal loop in the first
place. Vela gets the identical property for the identical reason. Client-declared tools are
**fully visible and fully client-controlled**, with no fork required.

**Vela's host tools on C1:**

| Tool | Executes | Latency class |
|---|---|---|
| `calendar_*` (read/create/modify) | Android Calendar Provider | fast (<500ms) |
| `notes_*` | Local store / user's note backend | fast |
| `reminders_*` | Android alarm/notification APIs | fast |
| `dispatch_to_fleet` | Ledger write + fleet-plane handshake | **must be <1s, returns a handle** |

**Hard constraint (from A5):** a host-tool call ends the SSE stream immediately. The turn
does *not* stay open for the tool's duration. Therefore **any host tool that could take
longer than ~2s must be handle-returning**, registering a job in the ledger and returning
`{job_id, status: "accepted"}` — never a result. `dispatch_to_fleet` is the canonical case.
This is enforced as a binary correctness gate in §11.

#### C2 — Event and control channel (the fork's reason for existing)

A small, purpose-built channel carrying what C1 structurally cannot:

**Server → client events** (tee'd from the internal queue):

| Event | Payload (as generated upstream) |
|---|---|
| `tool/started` | `sessionId, turnId, toolCallId, name, args, agentName?` |
| `tool/completed` | `sessionId, turnId, toolCallId, name, result, durationMs, agentName?` |
| `progress` | `sessionId, turnId, message, percent?` |
| `thinking/delta`, `thinking/final` | `sessionId, turnId, text` |
| `usage` | `sessionId, turnId, inputTokens, outputTokens, cost?, model?, provider?, …` |
| `error` | `sessionId, turnId?, code, message, recoverable` |

The optional `agentName` field is what makes **sub-agent delegation visible** — it is
populated for delegated sessions (session id format `{parent}-{child}_{agent_name}`). This
is the single most valuable thing the tee buys, because it turns "the agent is silent for 40
seconds" into "the agent delegated to `web-research`, which is on its third tool call."

**Client → server control:**
- Approval decisions (allow / allow-always / deny) in response to an approval request.
- *(Future — Stage 4)* mid-turn steering. See §8.5 for why this doesn't exist yet.

**Why not just add all this to C1?** Because C1 must stay stock. Every deviation from the
OpenAI contract on C1 is a deviation from the thing that makes C1 free (no fork, no
maintenance, works with any OpenAI-compatible tooling). Keeping the delta in C2 keeps C1's
zero-cost property intact.

#### C3 — Ledger REST API (server-owned, transcript-independent)

The durable record of **work**, as distinct from the record of **conversation**.

**Core resource — a job:**

```
job {
  job_id            uuid
  created_at        ts
  updated_at        ts
  origin            { session_id, turn_id, tool_call_id }   // provenance
  spec              { … }        // what was dispatched
  status            accepted | running | needs_attention | blocked | done | failed | cancelled
  attention         { required: bool, reason: str, options: [...], deadline?: ts }
  progress          [ { ts, message, percent?, source } ]
  result            { … } | null
  cost              { usd?, tokens? }
}
```

**Endpoints (minimum viable):**

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/ledger/jobs` | Create (called by `dispatch_to_fleet`) |
| `GET` | `/ledger/jobs` | List / filter by status, attention, time |
| `GET` | `/ledger/jobs/{id}` | Detail |
| `PATCH` | `/ledger/jobs/{id}` | Status / progress append (called by fleet plane) |
| `POST` | `/ledger/jobs/{id}/decision` | Record a human decision |
| `GET` | `/ledger/attention` | The card deck's backing query — jobs needing a decision |
| `GET` | `/ledger/events` | SSE stream of ledger changes (drives notifications) |

**Why separate from the transcript (B3, A6):** `amplifier-agent`'s transcript reconciliation
deletes orphaned `tool_use` blocks that have no paired `tool_result`. A `dispatch_to_fleet`
call that returns a handle and completes hours later is *definitionally* an orphaned tool_use
for that entire window. Storing job state in the transcript means it gets silently deleted on
the next turn. This is not a hypothetical — it is the documented behavior of the reconciler.

**Independently deliverable.** C3 requires *no fork*. It can be built, tested, and shipped as
a standalone service before the fork exists (§10 Stage 2), and the Android app can consume it
with a local SQLite mirror before any of the rest lands.

### 4.3 The Android application — a pure client

**Modules:**

| Module | Responsibility |
|---|---|
| **Voice transport** | Bidirectional audio to the voice tier; barge-in; VAD/turn-detection; earcons; mute/PTT. |
| **Session client (C1)** | Chat-completions request/response, streaming, host-tool dispatch loop. |
| **Event client (C2)** | Consume tee'd events; drive live activity UI; surface approval prompts. |
| **Ledger client (C3)** | Query attention queue; local SQLite mirror for offline read; decision submission. |
| **Host-tool executors** | Calendar, notes, reminders, `dispatch_to_fleet`. Each is a small, independently testable unit with a JSON-schema declaration. |
| **Card deck UI** | Swipeable attention queue. Swipe = decide (or defer), not merely dismiss. |
| **Chat / transcript UI** | Text peer to voice; historical record; the "I can't talk right now" surface. |
| **Notification manager** | Attention-gated. Notifies on decisions needed, **not** on progress. |
| **Foreground service** | Holds voice sessions and event stream; posts a state notification (Listening / Thinking / Speaking). |
| **Android Auto** | Voice-first surface in the car — the stated product differentiator. |

**What the app explicitly does not contain (B1):** no agent loop, no Rust, no NDK, no
cross-compilation, no embedded Python, no on-device provider. This is the single most
important simplification in the design and it is the direct lesson of the previously
abandoned architecture (§9.1).

### 4.4 Voice tier

**Recommendation: LiveKit Agents.** Rationale, in priority order:

1. **Neither side needs an inbound public port** (A8). A worker process dials *out* to
   LiveKit; the phone dials *out*. This preserves the property that no personal machine gets
   a public attack surface — which the vendor-brain architectures cannot preserve.
2. Real Kotlin + Compose Android starter, actively maintained.
3. A purpose-built turn-detection model — which, per A7, is the dominant quality driver.
4. **Migration path preserved:** start with a vendor realtime model as the fast tier, migrate
   to a custom pipeline driven by `vela-agentd`, *without touching the Android app*.

**Alternative: Pipecat + SmallWebRTC.** Zero third parties — the phone talks WebRTC directly
to a Python process over Tailscale. Philosophically the closest match to how Vela has always
been built. Higher DIY ops burden. **Keep as the documented fallback**; the Android-side
abstraction should not leak LiveKit specifics into the app's domain layer.

**Rejected as primary:** OpenAI Realtime (best voice quality, but demotes the intelligence
plane to a tool call), Gemini Live (cheapest, Android SDK gap), ElevenLabs (best Android SDK,
but requires a public tunnel to the intelligence host — violates A8), Deepgram (best BYO-LLM
economics, Android SDK gap).

#### Fast-tier / slow-tier split — the load-bearing pattern

The irreconcilable-budgets problem (§1.2) is solved by an explicit two-tier conversation:

```
User speaks ──▶ Fast tier (voice model)
                   │
                   ├─ trivial/social/clarifying ──▶ answers directly, ~800ms
                   │
                   └─ real work ──▶ acknowledges verbally ("I'll get on that")
                                     │
                                     ├──▶ hands off to vela-agentd (slow tier)
                                     │
                                     ├──▶ narrates C2 events as they arrive
                                     │      ("checking the calendar…", "delegating to research…")
                                     │
                                     └──▶ folds the final result back into speech
```

#### Voice UX rules (each derived from an observed production failure)

| # | Rule | Origin |
|---|---|---|
| **V1** | Turn-taking quality outranks model quality. Budget effort accordingly. | Universal across every product surveyed. |
| **V2** | Never interrupt a thinking pause. Silence-threshold VAD is the defining complaint of ChatGPT AVM; both OpenAI and Google shipped explicit fixes. Use a semantic turn-detection model, not a silence timer. | ChatGPT AVM user reports; Gemini Live Dec-2025 fix; OpenAI full-duplex July-2026. |
| **V3** | **No preemptive/speculative generation.** Do not start generating a response before the user has finished. It produces confident answers to the wrong question. | Repeated production finding. |
| **V4** | **Honest silence beats mistimed filler.** Badly-placed filler audio measurably worse than unmasked dead air. Narrate real events (V5) or say nothing. | Explicit production-team finding. |
| **V5** | Narrate **real** C2 events during long work — not synthetic reassurance. "Delegating to research" is honest; "still thinking…" on a loop is not. | Direct consequence of V4 + the C2 tee. |
| **V6** | Hard mute / push-to-talk fallback always available and always one tap away. | Users explicitly asked to regress to PTT when turn detection failed. |
| **V7** | Earcons for every state transition (listening → thinking → speaking). State must be legible without looking at the screen — critical in the car. | Wearables cohort failed primarily on state legibility, not conversation quality. |
| **V8** | Foreground-service notification always shows current voice state. | Same as V7, plus Android FGS policy compliance. |

### 4.5 Fleet dispatch contract (the only thing this design says about the fleet)

`dispatch_to_fleet` is a **host tool executed on the phone**. Its contract to the fleet plane:

| # | Requirement |
|---|---|
| **D1** | **Accept a job spec** — a structured description of work, machine/capability targeting hints, and a callback reference. |
| **D2** | **Return a job handle synchronously in <1s** (A9). Never block on the work itself. |
| **D3** | **Verify reachability synchronously.** A dispatch that will obviously fail (target unreachable) must fail *at dispatch time*, in the conversation, where the user can react — not silently, hours later. |
| **D4** | **Push progress to the ledger** via `PATCH /ledger/jobs/{id}` — the fleet plane is a ledger *writer*, not a thing that gets polled. |
| **D5** | **Set `attention.required` with a reason and options** when human input is needed. This is the sole input to the notification path and the card deck. |

Everything else about the fleet — how muxterm's `sessiond` protocol is driven, whether MCP or
a native client is used, how channels fan out, credential handling, scheduling — is
**out of scope and belongs in its own design document**. If any of D1–D5 turns out to be
unsatisfiable, that is a finding that comes back to *this* document as a revision, not
something to work around inside the Android app.

---

## 5. Data and Control Flows

### 5.1 Voice turn requiring real work (the primary flow)

```
1.  User (voice) ──▶ voice transport ──▶ fast tier
2.  Fast tier classifies: real work
3.  Fast tier speaks acknowledgment  [~800ms budget met]
4.  Fast tier ──▶ vela-agentd C1: POST /v1/chat/completions
                  (tools: [calendar_*, notes_*, reminders_*, dispatch_to_fleet])
5.  vela-agentd runs the agent loop; internal events flow to the queue
6.  Tee ──▶ C2 ──▶ Android ──▶ fast tier narrates ("checking your calendar…")
7.  Model selects dispatch_to_fleet
       ├─ delta.tool_calls chunk emitted on C1
       └─ SSE stream ENDS (finish_reason: tool_calls)          [A5]
8.  Android executes dispatch_to_fleet:
       ├─ POST /ledger/jobs                       (create record)
       ├─ hand spec to fleet plane, verify reachable  [D2, D3]
       └─ return {job_id, status: accepted}            [<1s]
9.  Android re-POSTs on C1 with {role: "tool", content: {job_id,…}}
10. Turn continues; model composes a spoken confirmation
11. Fast tier speaks it. Turn complete.
      ───────── hours later ─────────
12. Fleet plane ──▶ PATCH /ledger/jobs/{id}  (progress, then attention.required)
13. vela-agentd ──▶ /ledger/events SSE ──▶ Android
14. Notification rules: attention.required == true ──▶ Android notification
15. User opens app ──▶ card deck ──▶ swipe = decision
16. POST /ledger/jobs/{id}/decision ──▶ fleet plane resumes
```

**Note the critical property at step 7–9:** the ledger record is created **before** the tool
result is returned. If the app is killed between 8 and 9, the job exists in the ledger and is
recoverable. If it were created after, the job would be lost. This ordering is a correctness
requirement, not an implementation detail.

### 5.2 Approval flow (why F2 exists)

`amplifier-agent`'s HTTP face **auto-approves every tool call unconditionally** — its own
documentation states this and advises isolating the server accordingly. That posture is
acceptable for a co-located developer harness. It is **not** acceptable for a service driven
by voice from a phone, where a misheard utterance could trigger a destructive action.

```
1. Model requests a privileged internal tool
2. F2 gate intercepts ──▶ suspends the tool call
3. Approval request emitted on C2
4. Android: notification + in-app prompt (voice: spoken confirmation request)
5. Decision returned on C2 ──▶ tool proceeds or is denied
6. Timeout (configurable) ──▶ default DENY
```

**Failure mode to design for:** no C2 client attached (app killed, network gone). The gate
must not deadlock the server. Timeout → deny → the turn reports the denial as a tool error,
and the model handles it. Never hang.

### 5.3 "Where are we at?" (the interrogation flow)

This is R2/persistence stated as a question the user asks out loud. It resolves entirely
against the ledger — **not** against the conversation transcript:

```
User (voice): "where are we at?"
   ──▶ agent calls a ledger-query tool
   ──▶ GET /ledger/jobs?status=running,needs_attention,blocked
   ──▶ model composes a spoken summary
```

Because the ledger is server-owned and durable, this answer is correct **after a full server
restart, after an app reinstall, and on a different device.** That property is the entire
reason the ledger is a separate component rather than app state.

### 5.4 Notification gating

```
ledger event ──▶ rule evaluation ──▶ ┬─ attention.required  ──▶ NOTIFY (high priority)
                                     ├─ status: failed      ──▶ NOTIFY
                                     ├─ status: done + user-flagged ──▶ NOTIFY (low)
                                     └─ progress            ──▶ UPDATE UI ONLY, never notify
```

The distinguishing rule between "needs attention" and "just progress" is the **single
highest-leverage unresolved question** (§2.1 #2). Get it wrong toward noisy and the user
disables notifications; get it wrong toward quiet and the fleet stalls unnoticed. The
dismissed-unread ratio (§11) is the designed feedback signal for tuning it.

---

## 6. Risks and Failure Modes

| # | Failure | Likelihood | Blast radius | Mitigation |
|---|---|---|---|---|
| **F-1** | Event tee perturbs the chat-completions path (race, backpressure, dropped chunks). | Medium | **Total** — breaks the primary path. | **V0 Spike #1 gates all C2 work.** Tee must be non-blocking with a bounded buffer; drop tee'd events under pressure rather than block the primary consumer. |
| **F-2** | Fork drift — upstream `amplifier-agent` refactors `_session_runner.py` (self-labeled POC). | Medium-high | Rebase cost, not correctness. | Keep delta ≤4 points (B2). Pin upstream. Upstream the tee as a general feature if drift becomes painful. |
| **F-3** | `dispatch_to_fleet` exceeds 1s and stalls the conversation. | Medium | Voice UX degrades to unusable. | Hard contract (D2) + binary gate (§11). Escalation: `vela-agentd`-side queueing shim so dispatch is always local. |
| **F-4** | Approval gate deadlocks with no client attached. | Medium | Server hangs; all sessions blocked. | Mandatory timeout + default-deny. Never an unbounded wait. |
| **F-5** | Ledger loss on server restart. | Low if durable-by-construction; **catastrophic** if it happens. | Total loss of product trust — "where are we at" is the core promise. | Durable store from day one (not in-memory). Binary gate: zero lost events across kill/restart (§11). |
| **F-6** | Job dispatched with no ledger record (ordering bug). | Medium | Silent orphan work — worse than a crash, because it's invisible. | Ledger write **before** fleet handshake (§5.1 step 8). Binary gate. |
| **F-7** | Voice turn detection interrupts thinking pauses. | High if a silence-threshold VAD is used. | Product feels broken; the #1 complaint of the category. | V2 — semantic turn-detection model. V6 — PTT escape hatch. |
| **F-8** | Notification spam → user disables notifications → attention path dead. | Medium-high | Silent failure of the entire attention model. | Attention-gated by construction (§5.4). Monitor dismissed-unread ratio. |
| **F-9** | Android kills the foreground service mid-voice-session (FGS budget). | Low-medium | Dropped conversation. | A10 — no agent loop on-device keeps sessions short. Escalation: FCM high-priority wake instead of a held service. |
| **F-10** | Steering gap (§8.5) makes long agent work feel dead. | **Certain until Stage 4** | Degraded, not broken. | Interim: spoken narration of real C2 events (V5). This is why the tee matters more than it first appears. |
| **F-11** | Cost blowout from fleet fan-out. | Medium | Financial. | Unresolved #3. Ledger cost fields from day one so the data exists before the policy does. |
| **F-12** | Fleet plane cannot satisfy D1–D5. | Medium | Requires revising this design, not patching around it. | V0 Spike #3 tests the shape early, against a stub if necessary. |

---

## 7. Tradeoffs

### 7.1 Candidates compared

| | Candidate |
|---|---|
| **A** | **S4′ — one owned service: thin `vela-agentd` fork + pure Android client** *(recommended)* |
| **B** | Two-surface: stock HTTP face **+** a separate in-process embedding for events |
| **C** | Custom Rust engine (revive/rebuild the native kernel path; server- or device-hosted) |
| **D** | Stock-only: unmodified `amplifier-agent serve`, plain chat-completions client, no fork *(simplest credible alternative)* |

### 7.2 The 8-dimension matrix

| Dimension | A — S4′ (thin fork) | B — Two-surface | C — Custom Rust engine | D — Stock-only |
|---|---|---|---|---|
| **Latency** | **Good.** Voice fast-tier meets budget; C2 narration masks slow-tier work with honest signal. | Good, but cross-surface event/session reconciliation adds a join on every event. | Good in principle; irrelevant in practice — the voice model is remote either way, so an embedded kernel buys no conversational latency. | **Poor.** No progress signal at all. 10–60s of unexplained dead air per real turn. |
| **Complexity** | **Adequate.** One service, ≤4 fork points, three well-separated channels. | **Poor.** Two runtimes, two session identities, a reconciliation layer that exists only to undo the split. | **Very poor.** Must build provider + context modules that have never existed; 14 module crates; cross-compilation if on-device. | **Best.** Nothing to build server-side. |
| **Reliability** | **Good.** Ledger durable by construction; approval gate real; single process to supervise. | Adequate — but reconciliation is a new, bespoke failure surface. | Unknown — the recovered engine **has never executed a single real LLM turn.** | Adequate for chat; **fails the product promise** (no durable work tracking). |
| **Cost** | **Good.** One process; fork cost is rebase, amortized. | Higher — two runtimes to host, supervise, and version together. | **Highest.** Provider + context + 8–10 tool modules from scratch, then ongoing parity with upstream. | **Lowest.** |
| **Security** | **Good.** Real approval gate (F2) replaces unconditional auto-approve. No inbound public port (A8). | Same gate, doubled surface area. | Same gate possible, but a far larger self-written attack surface. | **Poor.** Ships upstream's stated posture — *every tool call auto-approved* — to a voice-driven phone client. Unacceptable. |
| **Scalability** | **Good.** Multi-session already solved upstream (fresh session per turn, one shared `PreparedBundle`). Fleet fan-out is external by design. | Same ceiling, more moving parts per unit of scale. | Would have to re-solve concurrency that upstream already solved. | Good for chat; no work-tracking dimension to scale. |
| **Reversibility** | **Best.** Fork is ≤4 points — revertible to stock in an afternoon. Voice vendor swappable behind an app-side abstraction. Ledger and app survive an engine change. | Medium — the reconciliation layer is sunk cost if either surface is dropped. | **Worst.** Months of module implementation with no reuse if abandoned. Precedent: exactly this was abandoned once already. | Best in isolation, but every capability added later requires the fork anyway — so it defers the decision without avoiding it. |
| **Org fit** | **Good.** Rides the repo the team actually maintains (A1). Fork points are small enough for one person to hold. | Poor — two runtimes is more surface than this team's throughput supports. | **Poor.** This exact team abandoned this exact path once, for reasons that still hold. | Good, but produces a product that doesn't do the thing that was asked for. |
| **Optimizes for** | Full capability at the minimum owned surface area. | Purity of the stock HTTP face. | Total control of the engine. | Time to first working chat. |
| **Sacrifices** | Accepts a small, permanent rebase obligation. | Pays reconciliation complexity to avoid a 4-point fork. | Pays months to rebuild what already works. | Sacrifices visibility, approval, ledger, and steering — i.e. the product. |

### 7.3 Dominant tradeoff dimensions

The decision is driven by **complexity** and **reversibility**, not latency or cost.

- **A vs B** is decided almost entirely on complexity: the events *already exist inside the
  HTTP-face process*. B pays cross-surface reconciliation to avoid a 4-line tee. That trade
  is inverted.
- **A vs C** is decided on complexity *and* org fit: C requires building a provider and a
  context manager that have never existed in that codebase — and this team already abandoned
  this path once for reasons (Android FGS limits; no working LLM turn) that have not changed.
- **A vs D** is decided on security and capability: D ships unconditional auto-approve to a
  voice client and provides no work ledger. D is the right *starting point* (§9) but not the
  right destination.

### 7.4 What would have to be true for A to be the wrong choice?

This is the catalytic question. Each of these is a monitorable signal:

1. **If the fork delta grows past ~4 localized points** — then a fork was the wrong mechanism
   and the changes belong upstream. *Signal: count the fork points every rebase.*
2. **If the event tee cannot be made non-perturbing** (V0 Spike #1 fails) — then B's
   separate-surface approach becomes correct despite its complexity, because there'd be no
   safe in-process path. *Signal: Spike #1.*
3. **If `amplifier-agent` is abandoned by the team** (A1 falsified) — then C's "own the engine"
   logic returns, and the preserved Rust crates (§10 Stage 0) become relevant again. *Signal:
   upstream commit cadence.*
4. **If the fleet plane cannot satisfy D2 (<1s handle)** — then dispatch needs a queueing shim
   inside `vela-agentd`, growing the fork and weakening the "thin" property. *Signal: Spike #3.*
5. **If device-local action latency turns out to dominate perceived quality** — then on-device
   execution would matter after all. *Currently assessed as false: the voice model is remote
   regardless, so the round-trip already exists.* *Signal: instrument host-tool round-trip
   time separately from voice turn time.*

---

## 8. Recommended Design (S4′)

### 8.1 One-paragraph statement

Build a **new** Android application that is a pure client — voice, cards, chat, notifications,
Android Auto, and host-tool execution — talking to **one owned service, `vela-agentd`**, a
thin rebaseable fork of `amplifier-agent`'s HTTP face exposing three channels: stock
chat-completions for client-declared/client-executed host tools (the proven OpenCode pattern),
a small event/control channel teeing the granular internal events `amplifier-agent` already
generates but currently discards (plus a real approval gate), and a transcript-independent
ledger REST API that is the durable source of truth for in-flight work. The fleet execution
plane is reached only through a handle-returning `dispatch_to_fleet` host tool and a defined
ledger-writing contract, and is designed separately.

### 8.2 Why this specific shape

| Decision | Reason |
|---|---|
| Fork the HTTP face rather than embed in-process | The events already exist in a queue in that process. Tee > reconstruct. |
| Keep the fork to 4 points | Rebaseability is the property that makes the fork acceptable at all (B2). |
| Client-declared tools on stock C1 | Proven by `amplifier-app-opencode`; needs zero fork; gives full client-side visibility and control for the device-local tools. |
| Separate C2 rather than extending C1 | Preserves C1's zero-cost stock property. |
| Ledger outside the transcript | Transcript reconciliation would silently delete pending jobs (A6). |
| Handle-returning dispatch | Host-tool calls end the SSE stream immediately (A5). Non-negotiable. |
| No kernel on the phone | Android FGS limits killed this once already; the voice model is remote anyway, so on-device buys nothing conversationally. |
| Fleet plane out of scope | Different failure modes, different latency budget, different ownership. Contract-only coupling. |

### 8.3 What ships in each milestone

| Milestone | Contents | Product truth |
|---|---|---|
| **V0** | Three feasibility spikes (§8.6). No UI. | "The architecture is real." |
| **D+ (D-plus)** | New Android app shell + voice + host tools against **stock** `amplifier-agent serve` + local SQLite ledger on the phone. | "I can talk to it and it can act on my calendar." |
| **S4′-core** | Server-side ledger (C3) + `vela-agentd` fork (C2 tee + approval gate) + Android C2 client. | "I can see what it's doing, approve what matters, and ask where we're at." |
| **S4′-fleet** | `dispatch_to_fleet` wired to the real fleet plane (separate design). | "It commands other machines." |
| **S4′-steer** | Upstream steering contribution; replace narration with true mid-turn injection. | "I can redirect it while it's working." |

Note the D+ milestone deliberately runs against **stock** `amplifier-agent` with a **phone-local**
ledger. That is candidate D used as a stepping stone — it gets a working, demoable product in
users' hands before any fork exists, and everything built there survives the transition.

### 8.4 Voice tier decision

**LiveKit Agents** as primary (A8-preserving, real Android SDK, purpose-built turn detection,
vendor-model-to-custom-pipeline migration without touching the app). **Pipecat + SmallWebRTC**
documented as the zero-third-party fallback. The Android domain layer must express voice as an
interface (`VoiceTransport`) with no vendor types leaking past it — this is what makes the
fallback real rather than theoretical.

### 8.5 Known open gap: mid-turn steering

**The gap:** the "acknowledge → keep talking → fold in async results" pattern that the voice
UX requires depends on **mid-turn injection** — the ability to add a user message to a turn
that is already running. The *old* Vela architecture had this: `loop-vela`'s steer queue,
drained between LLM iterations, exposed as `POST /sessions/{id}/steer`. It worked.

**None of `amplifier-agent`'s three surfaces has an equivalent today** — not HTTP, not the
subprocess wire protocol, not the in-process library. This is a real regression relative to
what the old architecture could do.

**Resolution path:** a modest upstream contribution to `amplifier-agent`'s `Engine` /
`TurnContext` — conceptually the same axis as the "duplex approval round-trip" already on that
repo's own v2 backlog. Once a turn can accept an inbound control message for approval, accepting
one for steering is a small generalization. Framing the contribution that way (generalize the
duplex channel, don't add a bespoke steer endpoint) makes it far more likely to be accepted.

**Interim mitigation:** spoken narration of real C2 events (V5). This is *not* equivalent — the
user can observe but not redirect — but it converts unexplained dead air into legible progress,
which per the UX research is the larger half of the problem. **This is a documented, accepted
degradation for milestones D+ through S4′-fleet, not an oversight.**

### 8.6 V0 feasibility spikes — must complete before any UI investment

These three spikes test the three assumptions that, if false, invalidate the design. They are
cheap, they are fast, and **none of the UI work should start before they pass.**

| Spike | Question | Pass criterion | Falsifies |
|---|---|---|---|
| **S-1 Event tee** | Can a second consumer read the internal event queue without perturbing the chat-completions path? | 100 consecutive tool-calling turns with the tee attached produce byte-identical C1 output to 100 turns without it; tee'd events include `tool/started`/`tool/completed` with populated `agentName` for at least one delegated sub-agent. | A3 → C2 design; possibly forces candidate B. |
| **S-2 Host-tool round-trip** | Does the OpenCode client-declared-tool pattern work from an Android client? | A minimal Android client declares one tool, receives `delta.tool_calls`, executes locally, re-POSTs `{role:"tool"}`, and the turn completes correctly. | A4 → the entire host-tool path. |
| **S-3 Handle-returning dispatch** | Does a fast handle-returning host tool complete the turn normally, and does the ledger record survive transcript reconciliation on the **next** turn? | Tool returns `{job_id}` in <1s; turn completes; a subsequent turn in the same session still sees the ledger record via a ledger-query tool. | A5, A6, A9 → the dispatch and ledger design. |

**S-3 is the sneaky-important one.** It is the only spike that tests the *interaction* between
the ledger and transcript reconciliation, which is the failure mode most likely to be discovered
late and hurt most (F-6).

---

## 9. Simplest Credible Alternative — and rejected paths

### 9.1 The simplest credible alternative: candidate D (stock-only)

Point the new Android app at an **unmodified** `amplifier-agent serve chat-completions`, declare
host tools client-side, keep the ledger in phone-local SQLite, and skip the fork entirely.

**What you get:** voice, chat, cards, host tools, calendar/notes/reminders, notifications — a
genuinely usable product.

**What you don't get, and why it isn't enough as a destination:**

| Missing | Consequence |
|---|---|
| Sub-agent / tool progress visibility | 10–60s of unexplained dead air per real turn. The #1 UX failure mode in the category. |
| Real approval gate | Upstream's stated posture is *every tool call auto-approved*, with its own docs advising isolation. Shipping that to a voice-driven phone is unacceptable. |
| Server-owned ledger | "Where are we at?" is wrong after an app reinstall or on a second device. Work survives only as long as the phone does. |
| Steering | Same gap as S4′ (§8.5) — no regression, but no fix either. |

**Verdict:** D is the right **milestone**, not the right **destination**. It is explicitly
adopted as the D+ milestone (§8.3) precisely because everything built there — the app, voice,
host tools, card UI — survives unchanged into S4′. The additional complexity of the fork is
justified by exactly three things: visibility, approval, and durability.

### 9.2 Rejected: full on-device native kernel (Rust via `cargo-ndk` + JNI)

**Historically attempted by this exact team, in this exact repository, and abandoned.**
`app/src/main/rust/amplifier-android/` is a real 1,485-LOC `cdylib` with live JNI entrypoints,
wired into the Gradle build, consuming `amplifier-core` as a **direct Cargo git dependency**
(sidestepping PyO3/pydantic entirely — the Python-wheel blocker never applied to this path).
Eight sequenced phase-plan docs exist at `docs/superpowers/plans/2026-04-24-phase-*.md`.

**Why it was abandoned** — documented in the team's own pivot doc, four days after the last
on-device commit:

> "Vela currently runs the entire agent loop on the Android device via a JNI Rust library.
> This is the wrong layer for agent work: Android kills background apps aggressively… The fix
> is the same one VS Code made with remote development."

This is the same Android foreground-service constraint independently derived in this design's
research (A10, F-9). It was not a guess; it was a lived failure.

**Additional blockers found on re-examination:**

- **On-device WASM guest modules cannot substitute.** The WASM mechanism is real and proven for
  Tier-1 pure-compute modules, but the WIT contracts for `Tool` and `Provider` ship with **zero
  host imports** — the Provider's WASI-HTTP import was deliberately removed pre-ship. WASM buys
  sandboxed pure logic, *not* a way to avoid writing native code for network or platform access.
  This inverts the premise that WASM could carry the calendar/HTTP/LLM work.
- **Node/Napi-RS bindings are real but insufficient.** `bindings/node` exposes Session,
  Coordinator, HookRegistry, CancellationToken, and a `JsToolBridge` — but has **no `execute()`
  on the session and no orchestrator or provider bridge at all.** The execution loop, which is
  the entire point, is absent. Also never published to npm (404) and never cross-compiled for
  Android.
- **`amplifier-ffi` (the C-ABI layer) is scaffolding.** Hand-written cbindgen C ABI; Go and C#
  listed as "Future"; the mount functions for providers and tools currently return
  `ERR_INTERNAL` with literal "not yet implemented" messages. No UniFFI anywhere; no Kotlin or
  Swift bindings exist or are planned.

**Verdict:** rejected. The mechanism (Cargo dependency + JNI) is sound; the *placement* (agent
loop on a phone) is what failed, and it fails for platform reasons that have not changed.

### 9.3 Rejected: rebuild the engine in Rust server-side (candidate C)

The recovered crates at `crates/` are real, tested, and substantial:

```
amplifier-module-tool-delegate                2,377 LOC   44 tests
amplifier-module-hooks-routing                1,935 LOC   36 tests
amplifier-module-orchestrator-loop-streaming  1,475 LOC    6 tests
amplifier-module-session-store                1,314 LOC   19 tests
amplifier-module-agent-runtime                  941 LOC   26 tests
amplifier-module-tool-task                      629 LOC   10 tests
                                          ─────────────────────────
                                              8,671 LOC  141 tests
```

`session-store` even has a passing `crash_resume_end_to_end()` integration test.

**But: it has never executed a single real LLM turn.** The set is missing
`amplifier-module-provider-anthropic` and `amplifier-module-context-simple` — both load-bearing.
The sandbox binary that exercises the engine passes an **empty provider map**, with a source
comment acknowledging "no live provider available in the sandbox binary." Of the 14 module
crates the Android build depended on, 6 are recovered and 8 are gone (the external
`amplifier-rust` workspace was never pushed durably and no longer exists on disk).

So the honest characterization is: **excellent scaffolding around a socket nothing has ever
been plugged into.** "Add axum and expose the existing engine" materially understates the work —
it requires writing a provider and a context manager from scratch, then reaching parity with an
upstream that is actively moving.

**Verdict:** rejected as the engine. **Preserved** (§10 Stage 0) as genuinely valuable design
thinking, recoverable if A1 is ever falsified.

### 9.4 Rejected: pure basic chat-completions client with no host tools

Distinct from candidate D. This is the version where the client declares *no* tools and relies
on the server's bundle tools for everything. Rejected because `amplifier-agent`'s HTTP face
hides **all** internal tool/hook/delegation activity by design — so this variant has literally
zero observability and no device-local access. Candidate D avoids this by using the OpenCode
pattern for host tools; this variant does not, and is strictly worse than D on every dimension.

### 9.5 Rejected: two-surface design (candidate B)

A stock HTTP face **plus** a separate in-process `amplifier_agent_lib` embedding to capture
events. Rejected because the granular events **already flow into a queue inside the HTTP-face
process** and are dropped one step before the wire. B pays for cross-surface session
reconciliation — a bespoke, novel failure surface — to avoid a small, localized tee. The
complexity trade is inverted.

### 9.6 Prior art surveyed — and why none of it is the shortcut

| Repo | Finding |
|---|---|
| `bkrabach/webamp` / `amplifier-app-webamp` | A ~2.5-hour AI-generated PoC (Jan 2026), dormant since. **Never actually runs amplifier-core** — bundles a *pre-Rust* pure-Python wheel and bypasses it entirely, talking straight to WebLLM. Three of its own calls would fail Pydantic validation if reached — proof the path was never exercised. Today's Rust+PyO3 kernel makes this strictly harder. **Dead end, not unexplored.** |
| `bkrabach/cortex` | Vision docs match this problem space almost exactly (voice, multi-modal, dashboards, cross-device). **Shipped code implements one slice:** capture OS notifications → LLM-score relevance → push/summarize/suppress → triage queue. No voice, no ledger, no mobile client. Dormant ~5.5 months. **Valuable prior art on the attention-firewall slice specifically.** |
| `bkrabach/muxplex` | The strongest available prior art. Real multi-host federation (aggregated session lists, cross-host create/delete, remote terminal relay, LWW settings sync), PWA + mobile compose bar. **No service worker** → no offline, no push. Its `/input` RCE threat model and follow-up-queue design are directly relevant to the fleet plane. |
| `kenotron-ms/muxterm` | Go PTY daemon (`sessiond`), working MCP server, tunnel/deploy for single remote machines. **Single-machine-per-connection today**; no cross-machine channel abstraction. Architecturally opposed to muxplex (owns PTYs directly vs. wrapping tmux). Relevant to the fleet plane design, not to this one. |
| `bkrabach/amplifier-voice` + `microsoft/amplifier-voice` + `amplifier-voice-bridge` | Not a chief-of-staff framing — "voice front-end to a team of Amplifier agents." Its one load-bearing idea is directly applicable: **the voice model is an orchestrator that may only delegate/dispatch, never act directly** (B5). `amplifier-voice-bridge` is literally Siri/CarPlay → Tailscale → remote Amplifier. |
| Claude Code Channels / Agent Teams | "Channels" is **not** the multi-agent coordination feature — it's an MCP server that *pushes* external events into one already-running session. Multi-agent coordination is separate (Agent Teams, cross-session messaging, `claude agents`, Remote Control). Useful conceptual vocabulary for the fleet design; not a component to adopt here. |

---

## 10. Migration and Rollout Plan

> **Stage 0 is mandatory and blocking. No other stage may begin until it completes.**

### Stage 0 — Preserve, then clear (blocking, not parallelizable)

**Step 0a — Create the preservation branch.**

```bash
git checkout -b archive/pre-rebuild-2026-08
git push -u origin archive/pre-rebuild-2026-08
```

The branch preserves the tree **exactly as-is at `d147bde6`**, including the existing
`AGENTS.md`, the Rust crates, the phase-plan docs, and all 286 Kotlin files (~40,384 LOC).
No cleanup, no curation on the branch — a faithful snapshot. Curation happens in the
knowledge extract (0b), not in the archive.

**Step 0b — Extract the knowledge that must survive into the new tree.**

The archive branch preserves *code*. This step preserves *lessons* — a small set of documents
that carry forward into the clean `main` so the rebuild doesn't re-learn them the hard way.

| Preserve | Why it matters even though unused in S4′ |
|---|---|
| **`SessionSseNormalizer` + the SSE parsing lessons** | The documented antipatterns are the expensive part, not the code: (1) **tool-call format divergence** — the SSE representation uses `block.name`/`block.input` inside a `content` array while the transcript API uses a top-level `tool_calls[]` with `tc.tool`/`tc.arguments`; conflating them silently drops tool blocks post-stream (a real, shipped bug); (2) the **`"executing"` vs `"running"` status bug** — inventing plausible-sounding status strings produces a permanently-empty list with no error; (3) **SSE must open before POST** — otherwise early events are lost to a race. All three will recur in a new client unless written down. |
| **The 5-state connectivity / node-tile model + multi-URL fallback** | `candidateUrls()` / `findReachableUrl()` — ordered Tailscale → LAN → SSH-derived probing with a persistent last-known state and a 5-state tile model (`Unknown → Checking → Reachable → Unreachable`, plus persisted last-known). The new app faces the same problem (reaching `vela-agentd` across networks) and this is a solved, tested answer. |
| **`SessionStreamingService`** | Foreground-service + notification-dispatch pattern. Conceptually reusable even though it will be rebuilt against a different event source. Encodes real knowledge about Android FGS lifecycle and notification channel setup. |
| **The 6 Rust crates at `crates/`** (~8,671 LOC, 141 tests) | Not used in S4′ (§9.3) but they represent real, tested design thinking on delegation, model-role routing, streaming orchestration, and crash-resumable session storage. If A1 is ever falsified and the engine question reopens, this is the starting point — do not make anyone rediscover it. |
| **The 8 phase-plan docs** (`docs/superpowers/plans/2026-04-24-phase-*.md`) | Implementation-ready, TDD-shaped specs for core agent depth, providers/tools, sandboxed tools, delegation, context threading, model routing, session persistence, and the Android agent surface. **Valuable regardless of language or engine choice** — much of the task decomposition thinking transfers directly. |
| **`eb207e74` — parallel-delegate streaming fix** ("claim first unclaimed ToolUse for parallel delegate chunks") and adjacent recent fixes | Hard-won debugging on correlating streaming chunks to the right tool call when multiple delegates run in parallel. The new C2 client will face the identical correlation problem. Reference these so the same bug is not re-introduced. |
| **`docs/amplifierd-openapi.json`** | Historical record of the API surface being moved off of. Useful for understanding what capabilities existed, so the new design can be checked for unintentional regressions (e.g. steering — §8.5 — which *was* a regression and is now explicitly tracked). |

**Deliverable of 0b:** a single `docs/PRESERVED_LESSONS.md` on the new `main`, plus the
phase-plan docs carried forward under `docs/reference/`. Everything else lives only on the
archive branch.

**Step 0c — Clear `main` for the fresh start.**

Remove the existing application source, the Gradle app module, the Rust crates, the plugins,
the sandbox and harness directories. What remains on `main`: repository metadata, LICENSE,
`docs/designs/` (this document), `docs/PRESERVED_LESSONS.md`, and `docs/reference/`.
A new `AGENTS.md` is written from scratch describing the new architecture — the existing one
documents a system that no longer exists and would be actively misleading (context poisoning).

**Step 0d — Confirm the preservation list with the product owner** (Unresolved #4). After
`main` is cleared, adding to the archive is awkward. Confirm before proceeding.

**Stage 0 definition of done:** `archive/pre-rebuild-2026-08` pushed and verified to contain
the full pre-rebuild tree; `main` contains only the retained docs and repo metadata; a new
`AGENTS.md` describes the S4′ architecture; the preservation list is confirmed.

### Stage 1 — Foundations (parallel; safe to batch)

All Stage 1 lanes touch **disjoint, new** areas of a cleared tree. They do not conflict.
Together they produce the **D+ milestone**: a working voice product against stock
`amplifier-agent serve` with a phone-local ledger.

Run V0 spikes S-1/S-2/S-3 (§8.6) concurrently with Stage 1 scaffolding — but **do not start
Stage 3 until S-1 and S-3 pass.**

### Stage 2 — Server ledger (parallel with late Stage 1)

C3 as a standalone service. **No fork required** — this is why it is its own stage and can
land before any `vela-agentd` work begins.

### Stage 3 — The fork (`vela-agentd`) and C2 integration

Gated on S-1 passing. Delivers visibility and the approval gate — the S4′-core milestone.

### Stage 4 — Upstream steering

The `amplifier-agent` contribution (§8.5). Independent of everything else; can start any time
after Stage 1, lands whenever upstream accepts.

### Rollback plan

| Stage | Rollback |
|---|---|
| 0 | The archive branch *is* the rollback. Full tree recoverable. |
| 1 | Revert lane branches; D+ has no server-side dependency beyond stock `amplifier-agent`. |
| 2 | Ledger service is additive; app falls back to local SQLite mirror. |
| 3 | **Revert `vela-agentd` to stock upstream** — the ≤4-point fork is designed to make this a same-day operation (B2, reversibility rating in §7.2). App degrades to D+ behavior. |
| 4 | Upstream PR simply doesn't merge; narration mitigation (V5) remains in place. |

---

## 11. Success Metrics

### 11.1 Binary correctness gates (any failure blocks the milestone)

| # | Gate |
|---|---|
| **G1** | **Zero lost ledger events** across app kill, app restart, server restart, and network partition. Measured by an injected event sequence with known cardinality. |
| **G2** | **Zero fleet dispatches with no ledger record.** Every `dispatch_to_fleet` invocation has a corresponding ledger row, created *before* the fleet handshake (§5.1 step 8). |
| **G3** | **Zero host tools exceeding 2s without handle-registration.** Any host tool whose p99 exceeds 2s must be handle-returning. Enforced by instrumentation, not by review. |
| **G4** | **Zero privileged tools reachable on the unapproved path.** No internal tool classified as privileged executes without traversing the F2 approval gate. Verified by an adversarial test that attempts each privileged tool with no C2 client attached (expected: timeout → deny). |

### 11.2 Experience targets

| Metric | Target |
|---|---|
| Voice turn-taking latency (user stops → assistant begins) | **p50 < 800ms, p99 < 1.5s** |
| Dead air during agent work (gap between narration events) | **p99 < 5s** |
| Attention-needed → notification delivered | **p99 < 60s** |
| `dispatch_to_fleet` return (handle, not result) | **p99 < 1s** |
| Turn-detection false interruptions (assistant cuts off a thinking pause) | **< 1 per 50 turns** (this is the A7 metric; if this is red, nothing else matters) |

### 11.3 Product-truth signals

These are the metrics that tell you whether the *product* works, as distinct from whether the
*system* works.

| Signal | What it tells you |
|---|---|
| **Correctness of "where are we at" answers** | Sample real queries and grade against ledger ground truth. If this is wrong, the ledger design has failed regardless of uptime. |
| **Work survives a full server restart** | Restart `vela-agentd` mid-fleet-job; the job must still be tracked, still resumable, still answerable. This is the core product promise. |
| **Ratio of cards *decided* vs. *dismissed-unread*** | The designed feedback signal for notification-rule quality (§5.4, F-8). A rising dismissed-unread ratio means the attention rules are too permissive — the user is being trained to ignore the deck. |
| **Voice sessions initiated from Android Auto** | Direct validation of the stated differentiator. If this is near zero, the car use case is aspirational rather than real, and the effort budget should shift. |
| **Fraction of turns that trigger narration** (i.e. exceed the fast-tier budget) | Tells you how load-bearing the C2 tee actually is. If it's low, the fork bought less than expected; if it's high, F-10 (steering gap) hurts more than expected and Stage 4 should be prioritized. |

---

## 12. Goal-Batch Task Decomposition

Structured for `/goal-batch`, which runs each lane as its own isolated **git worktree +
branch + tmux session**. Lanes within a stage touch disjoint paths and are safe to run
concurrently.

### Legend

- **Scope** — one sentence, the lane's entire mandate.
- **Touches** — primary paths. Lanes in the same stage must not overlap here.
- **Depends on** — what must be *complete* (not merely started) first.
- **Done when** — the stop condition. Written to be checkable, not aspirational.

---

### STAGE 0 — Preservation and clearing  *(BLOCKING · NOT PARALLELIZABLE · exactly one lane)*

> **No Stage 1 lane may start until Lane 0.1 is merged to `main`.** Every subsequent lane
> branches from the cleared `main`; starting early guarantees a conflict against a tree that
> is about to be deleted.

#### Lane 0.1 — `archive-and-clear`

- **Scope:** Preserve the entire existing tree on `archive/pre-rebuild-2026-08`, extract the
  durable lessons into the new tree, then clear `main` to a clean foundation.
- **Touches:** the whole repository. This is why nothing else may run concurrently.
- **Depends on:** nothing. **Blocks everything.**
- **Steps:**
  1. `git checkout -b archive/pre-rebuild-2026-08 && git push -u origin archive/pre-rebuild-2026-08` — faithful snapshot at `d147bde6`, no curation.
  2. Write `docs/PRESERVED_LESSONS.md` covering, at minimum: the SSE tool-call format divergence table, the `"executing"` vs `"running"` status bug, the SSE-before-POST ordering rule, the 5-state connectivity model + multi-URL fallback algorithm, the `SessionStreamingService` FGS/notification pattern, a pointer to the 6 Rust crates on the archive branch with an explanation of *why* they're preserved but unused, and the `eb207e74` parallel-delegate chunk-correlation lesson.
  3. Copy the 8 phase-plan docs to `docs/reference/phase-plans/`.
  4. Retain `docs/amplifierd-openapi.json` under `docs/reference/`.
  5. Delete: `app/`, `crates/`, `plugins/`, `sandbox/`, `harness/`, `amplifier-agent-foundation/`, `target/`, root `Cargo.toml`/`Cargo.lock`, Gradle build files, `PLAN.md`, `VISION.md`, `mockup-agent-messages.html`, `docs/design/`, `docs/DESIGN.md`, `docs/plans/`, `docs/specs/`, `docs/superpowers/`, `docs/storyboard-ux.html`.
  6. Rewrite `AGENTS.md` from scratch for the S4′ architecture. **Do not carry the old one forward** — it documents a dead system and is active context poison.
  7. Confirm the preservation list with the product owner (Unresolved #4) before merging.
- **Done when:** `archive/pre-rebuild-2026-08` exists on the remote and `git diff d147bde6 archive/pre-rebuild-2026-08` is empty; `main` contains only repo metadata, `docs/designs/`, `docs/PRESERVED_LESSONS.md`, `docs/reference/`, and a new `AGENTS.md`; the repo builds nothing and that is expected; preservation list confirmed.

---

### STAGE 1 — Foundations  *(PARALLEL · 5 lanes + 3 spikes · all branch from cleared `main`)*

These lanes touch entirely disjoint new directories. Batch them.

#### Lane 1.1 — `android-scaffold`

- **Scope:** New Android application from scratch — Gradle project, Compose, DI, navigation, module structure, CI, and the card-deck + chat UI shells with mock data.
- **Touches:** `android/` (new root), `android/app/`, `android/core-ui/`, `android/core-domain/`, `settings.gradle.kts`, `.github/workflows/android.yml`.
- **Depends on:** Lane 0.1.
- **Notes:** Establishes the module boundaries every other Android lane plugs into — publish the interface contracts (`VoiceTransport`, `HostTool`, `LedgerRepository`, `EventStream`) **early** so parallel lanes can code against them. Define these as the lane's first commit, not its last.
- **Done when:** app builds, installs, and launches on a device; card deck renders a mock attention queue and supports swipe-to-decide; chat surface renders a mock transcript; the four domain interfaces are defined and merged; CI green.

#### Lane 1.2 — `voice-transport`

- **Scope:** LiveKit Agents client integration behind the `VoiceTransport` interface, with turn detection, barge-in, PTT fallback, earcons, and the foreground service holding the session.
- **Touches:** `android/voice/`, `android/app/src/main/.../service/VoiceForegroundService.kt`, `voice-worker/` (the LiveKit-side Python worker).
- **Depends on:** Lane 1.1's interface commit (not the whole lane).
- **Notes:** Implements V1–V8 (§4.4). **No vendor types may cross the `VoiceTransport` boundary** — this is what keeps Pipecat viable as a fallback. Verify that constraint with a compile-time check or lint rule, not a code-review convention.
- **Done when:** a full spoken round-trip works against a stock LLM; measured turn-taking p50 < 800ms; barge-in interrupts TTS mid-sentence; PTT mode toggles and works; each of the three states emits a distinct earcon and updates the FGS notification; the app survives a 20-minute continuous voice session.

#### Lane 1.3 — `host-tools`

- **Scope:** Android-side host-tool executors — calendar, notes, reminders, and `dispatch_to_fleet` — each with a JSON-schema declaration and the client-side execution loop for the OpenCode pattern.
- **Touches:** `android/host-tools/`, `android/core-domain/.../HostTool.kt`.
- **Depends on:** Lane 1.1's interface commit; informed by Spike S-2.
- **Notes:** `dispatch_to_fleet` in this lane targets a **stub** fleet plane — it writes a ledger record and returns a handle. The real fleet wiring is a separate design/stage. Enforce the <1s / handle-returning contract (G3) with an instrumented assertion in the executor base class, so violations fail loudly in development.
- **Done when:** each tool executes against real Android APIs; each declares a valid JSON schema; the client-side tool loop (receive `delta.tool_calls` → execute → re-POST `{role:"tool"}`) completes turns correctly against stock `amplifier-agent serve`; `dispatch_to_fleet` returns a handle in p99 < 1s against the stub.

#### Lane 1.4 — `agent-serve-ops`

- **Scope:** Deploy and operate **stock, unmodified** `amplifier-agent serve chat-completions` — host setup, bundle configuration, process supervision, Tailscale reachability, TLS/auth, health checks, logs.
- **Touches:** `ops/`, `ops/agent-serve/`, `ops/README.md`.
- **Depends on:** Lane 0.1 only. **Fully independent of all Android lanes.**
- **Notes:** This is what the whole D+ milestone runs against. Deliberately stock — the fork comes in Stage 3 and must be a drop-in replacement for whatever this lane stands up. Document the reachability model explicitly (this is where the preserved multi-URL fallback lesson applies).
- **Done when:** service runs under supervision and survives host reboot; reachable from the phone over Tailscale; auth enforced; health check + log tailing documented; a documented one-command redeploy exists.

#### Lane 1.5 — `phone-ledger`

- **Scope:** Local SQLite ledger on the phone implementing the `LedgerRepository` interface — the D+ milestone's durability story, and later the offline mirror of the server ledger.
- **Touches:** `android/ledger/`, `android/ledger/src/main/.../db/`.
- **Depends on:** Lane 1.1's interface commit.
- **Notes:** Model the schema against §4.2 C3's job resource **exactly**, so the Stage 2 server ledger is a transparent swap behind the same interface. Design for a sync/mirror mode from the start (row-level `updated_at`, server-authoritative conflict resolution) even though nothing syncs yet.
- **Done when:** jobs persist across app kill and device reboot; the attention query drives the card deck; decisions are recorded; G1 (zero lost events) passes for the local-only case; schema documented and matched to the C3 job resource.

#### Spike S-1 — `spike-event-tee`  *(gates Stage 3)*

- **Scope:** Prove a second consumer can read `amplifier-agent`'s internal event queue without perturbing the chat-completions path.
- **Touches:** a throwaway branch of `amplifier-agent`; `spikes/s1-event-tee/`.
- **Depends on:** Lane 0.1.
- **Done when:** 100 tool-calling turns with the tee produce byte-identical C1 output to 100 without it; tee'd stream contains `tool/started`/`tool/completed` with a populated `agentName` for at least one delegated sub-agent; findings written to `docs/spikes/s1-findings.md` including the exact fork points and their line locations.

#### Spike S-2 — `spike-host-tool-roundtrip`

- **Scope:** Prove the OpenCode client-declared-tool pattern works from an Android client.
- **Touches:** `spikes/s2-host-tool/`.
- **Depends on:** Lane 1.4 (needs a running `agent serve`).
- **Done when:** a minimal Android client declares one tool, receives `delta.tool_calls`, executes locally, re-POSTs, and the turn completes; findings written up, including exact wire captures.

#### Spike S-3 — `spike-handle-dispatch`  *(gates Stage 3)*

- **Scope:** Prove a handle-returning host tool completes the turn normally **and** that a ledger record survives transcript reconciliation into the next turn.
- **Touches:** `spikes/s3-dispatch/`.
- **Depends on:** Lane 1.4; benefits from S-2.
- **Done when:** tool returns `{job_id}` in <1s and the turn completes; a *subsequent* turn in the same session successfully retrieves the ledger record via a ledger-query tool; the reconciliation behavior (A6) is documented with evidence either way.

---

### STAGE 2 — Server ledger  *(1 lane · parallel with late Stage 1 · no fork required)*

#### Lane 2.1 — `ledger-service`

- **Scope:** Standalone durable ledger service implementing the C3 REST API and the `/ledger/events` SSE stream.
- **Touches:** `services/ledger/`, `ops/ledger/`.
- **Depends on:** Lane 1.5 (schema alignment) — can start on the schema before 1.5 merges, but must not diverge from it.
- **Notes:** Deliberately standalone so it ships before the fork and can later be folded into `vela-agentd` (or stay separate — both are viable). Durable-by-construction from commit one; **never** an in-memory phase, because F-5's blast radius is total.
- **Done when:** all C3 endpoints implemented and documented; SSE event stream works; G1 passes across service restart; G2 enforceable (job creation is idempotent on `origin.tool_call_id`); the Android `LedgerRepository` can swap from local-only to server-backed with the local DB as mirror.

---

### STAGE 3 — The fork and full visibility  *(2 lanes · gated on S-1 and S-3)*

#### Lane 3.1 — `vela-agentd-fork`

- **Scope:** Fork `amplifier-agent`'s HTTP face into `vela-agentd` — implement the event tee (F1), the real approval gate (F2), the C2 route (F3), and C3 route wiring or proxy (F4).
- **Touches:** `services/vela-agentd/` (vendored fork), `ops/vela-agentd/`, `docs/FORK_POINTS.md`.
- **Depends on:** Spike S-1 **passed**; Lane 2.1 (for C3 wiring); Lane 1.4 (drop-in replacement target).
- **Notes:** **Maintain `docs/FORK_POINTS.md` as a living document** listing each fork point, its upstream location, and its rationale — this is the artifact that makes B2 checkable and rebases tractable. If the count exceeds 4, stop and escalate per §7.4 signal 1. Approval gate must have a mandatory timeout with default-deny (F-4).
- **Done when:** C2 emits tee'd events including `agentName` for sub-agents; approval gate suspends privileged tools and resolves via C2 decision or times out to deny; G4 passes (adversarial test with no C2 client attached); C1 output is byte-identical to stock for the same inputs; `docs/FORK_POINTS.md` lists ≤4 points; deploys as a drop-in replacement for Lane 1.4's stock service.

#### Lane 3.2 — `android-c2-client`

- **Scope:** Android C2 client — consume tee'd events, drive live activity UI, surface approval prompts (visual + spoken), and feed voice narration (V5).
- **Touches:** `android/events/`, `android/app/src/main/.../ui/activity/`, `android/voice/.../Narrator.kt`.
- **Depends on:** Lane 3.1 (protocol must exist); Lane 1.2 (narration hooks into voice).
- **Notes:** **Apply the `eb207e74` lesson here** — correlating streaming chunks to the right tool call when multiple delegates run in parallel is a known, previously-shipped bug class. Claim-first-unclaimed semantics, or an equivalent explicit correlation strategy, from the start.
- **Done when:** live tool activity renders with correct sub-agent attribution during parallel delegation; approval prompts appear within 2s of the gate firing and can be answered by voice and by touch; narration fires on real events with p99 inter-event gap < 5s; a 5-way parallel delegation renders all five correctly attributed with zero cross-assignment.

---

### STAGE 4 — Upstream steering  *(1 lane · independent · long-lead)*

#### Lane 4.1 — `upstream-steering`

- **Scope:** Contribute mid-turn steering to `amplifier-agent`'s `Engine`/`TurnContext`, framed as a generalization of the duplex approval round-trip already on that repo's v2 backlog.
- **Touches:** upstream `amplifier-agent` (external PR); `docs/UPSTREAM_STEERING.md` for the local design note.
- **Depends on:** nothing hard — can start any time after Stage 1. Lands when upstream accepts.
- **Notes:** Frame as *generalizing the duplex control channel*, not as a bespoke steer endpoint — materially more likely to be accepted, and better design regardless. Reference the old `loop-vela` steer-queue implementation (on the archive branch) as proven prior art for the drain-between-iterations semantics.
- **Done when:** upstream PR opened with tests and design rationale; once merged, `vela-agentd` rebases onto it and Android replaces narration-only with true mid-turn injection; the acknowledge → keep-talking → fold-in-results pattern works end-to-end in voice.

---

### Dependency graph

```
Lane 0.1  ─────────────────────────────────────────────── BLOCKING
    │
    ├──▶ 1.1 android-scaffold ──┬──▶ 1.2 voice-transport ──────┐
    │         (interfaces first)├──▶ 1.3 host-tools            │
    │                           └──▶ 1.5 phone-ledger ──┐      │
    │                                                   │      │
    ├──▶ 1.4 agent-serve-ops ──┬──▶ S-2 ──▶ S-3 ────────┼──┐   │
    │                          └──▶ (hosts D+ milestone)│  │   │
    │                                                   │  │   │
    ├──▶ S-1 spike-event-tee ───────────────────────────┼──┤   │
    │                                                   │  │   │
    │                                     2.1 ledger ◀──┘  │   │
    │                                         │            │   │
    │                          3.1 vela-agentd-fork ◀──────┘   │
    │                                         │                │
    │                          3.2 android-c2-client ◀─────────┘
    │
    └──▶ 4.1 upstream-steering  (independent, long-lead)
```

### Batching guidance for `/goal-batch`

| Batch | Lanes | Rationale |
|---|---|---|
| **Batch 0** | `0.1` alone | Rewrites the whole tree. Nothing else can coexist. |
| **Batch 1** | `1.1`, `1.4`, `S-1` | Disjoint: new Android tree / ops tree / throwaway spike. `1.1` must land its interface commit early for Batch 2. |
| **Batch 2** | `1.2`, `1.3`, `1.5`, `S-2` | All depend only on `1.1`'s interfaces; each owns its own Android module directory. |
| **Batch 3** | `2.1`, `S-3`, `4.1` | Server ledger, dispatch spike, and the long-lead upstream PR — no overlap. |
| **Batch 4** | `3.1` then `3.2` | **Sequential, not parallel** — `3.2` consumes the protocol `3.1` defines. |

**Do not batch `3.1` and `3.2`.** The C2 protocol is defined by `3.1`; running them
concurrently means `3.2` codes against a guess. This is the one place in the plan where
sequencing beats parallelism.

---

## 13. Headless Android Development & Testing (for `/goal-batch` lanes)

Every Stage 1 and Stage 3 lane that touches the Android app must produce a working artifact
**with zero human touching a device** — a `/goal-batch` agent cannot tap a screen. This section
specifies the toolchain, the honest limits, and the structured feedback contract each lane's
harness must emit. Treat this as binding on Lanes 1.1–1.3, 1.5, and 3.2; the ops-only lanes
(1.4, 2.1, 4.1) don't need it.

### 13.1 The tool for each job — do not default to the emulator GUI or a human tester

| Need | Use | Why |
|---|---|---|
| Headless VM lifecycle | `android emulator create/start/stop` (Android CLI) or raw `emulator -no-window` | Full adb + gRPC control surface, no display required |
| Build artifact discovery | `android describe --project_dir=.` | JSON with APK output paths — no path-guessing in scripts |
| Install + launch, all perms granted | `adb install -r -t -g <apk>` then `adb shell am start -n <pkg>/<activity>` | `-g` grants every runtime permission in one shot |
| Read the live UI as data | `android layout --pretty --diff` | JSON tree; `--diff` bounds output to what changed, keeping an agent's context cheap across a long drive |
| Scripted taps without coordinate math | `android screen capture --annotate` + `android screen resolve` | Label → coordinate translation; use this before hand-computing tap positions |
| Deterministic UI assertions (CI gate) | Compose `performTouchInput` + `onNodeWithTag` in an instrumented test | Fast, hermetic, no AI judgment in the loop — **this is what gates a merge, not an AI-driven flow** |
| Exploratory / self-healing UI drives | Android CLI + **Journeys** | Useful for triage; **never gate a merge on it** — see §13.7 |
| Synthetic mic / speaker | Emulator gRPC `injectAudio` / `streamAudio` | The only first-party way to feed/capture audio without real hardware |
| Proving a host tool actually wrote to Android | `adb shell content query --uri content://...` | Proves the write landed in the **real system provider**, out-of-process — nothing else proves this |
| Proving a notification actually posted | `adb shell dumpsys notification --noredact` (smoke) + `NotificationManager.getActiveNotifications()` in an instrumented test (gate) | `dumpsys` for cheap agent-side smoke checks; the typed API for the thing that blocks a merge |
| Android Auto | Google's DHU, `--headless` flag, scripted via stdin | See §13.6 — partially automatable only |
| Nightly real-device matrix | `gcloud firebase test android run` | Cloud device farm; **no ADB access**, cannot host an interactive agent loop — use for coverage breadth, not for lane feedback |

### 13.2 Headless emulator, concretely

```bash
emulator -avd vela_lane_${LANE_ID} \
  -port $((5554 + LANE_ID * 2)) -grpc $((8554 + LANE_ID)) \
  -no-window -no-boot-anim -no-snapshot -wipe-data \
  -gpu swiftshader_indirect -memory 4096 &
adb wait-for-device
timeout 300 adb shell 'while [[ -z $(getprop sys.boot_completed) ]]; do sleep 1; done'
```

**Device hygiene, run before every lane** (each of these has bitten someone in production and is
worth taking on faith rather than rediscovering):

```bash
adb shell locksettings set-disabled true          # a locked screen silently fails every flow
adb shell settings put global package_verifier_user_consent -1
for s in window_animation_scale transition_animation_scale animator_duration_scale; do
  adb shell settings put global $s 0               # animation timing is a flakiness source
done
adb shell svc power stayon true
```

**Lane isolation is non-negotiable, for three concrete reasons:** (1) `injectAudio` permits
**exactly one active microphone per emulator instance** — concurrent lanes sharing an emulator
will fail with `FAILED_PRECONDITION`; (2) ADB's auto-detection heuristic creates phantom devices
when ports collide — give every lane an explicit `-port` and set `ANDROID_SERIAL`; (3) `pm clear`
or `-wipe-data` from one lane will destroy another's state. Each `/goal-batch` lane needs its own
emulator instance, not a shared one.

**Container caveat:** Gradle Managed Devices and hardware-accelerated emulators need `/dev/kvm`.
If lanes run in Docker without `--device /dev/kvm`, fall back to a self-hosted runner or a
cloud-hosted device with SSH access (Genymotion Cloud is the one confirmed working option;
Firebase Test Lab and AWS Device Farm do not expose ADB and cannot host this loop).

### 13.3 Verifying host tools without a human (Lane 1.3, informed by Spike S-2)

The strongest verification for any host tool is proving its effect landed in the **real,
out-of-process Android system provider** — not a mock, not an in-app assertion:

```bash
# calendar_create wrote a UUID-stamped test event; prove it exists in the real provider
adb shell content query \
  --uri content://com.android.calendar/events \
  --projection _id:title:dtstart:calendar_id \
  --where "title LIKE '%${TEST_UUID}%'"
```

Design every host-tool test around this pattern: stamp a UUID into the test prompt, have the
tool's real Android write include it, then query the real provider for it out-of-band. This is
also the pattern that proves `dispatch_to_fleet`'s ledger write (G2) actually happened — query
the ledger's own store the same way, not the app's in-memory state.

**Runtime permissions are fully automatable** (`pm grant`/`pm revoke` covers `RECORD_AUDIO`,
`READ/WRITE_CALENDAR`, `POST_NOTIFICATIONS`). **AppOps / "special app access"** (battery
exemption, usage-stats access) is *mostly* automatable via `adb shell cmd appops set` but
coverage varies by API level — verify each one empirically on the target emulator image rather
than assuming.

### 13.4 Verifying the voice pipeline without a microphone (Lane 1.2)

The emulator's gRPC `EmulatorController` service is the only first-party mechanism for synthetic
audio, and it supports exactly the round-trip this app needs to prove:

```python
# inject a WAV as the guest microphone, capture what the app plays back via TTS
stub.injectAudio(wav_packets("assets/whats_on_my_calendar.wav"))
captured = collect(stub.streamAudio(AudioFormat(samplingRate=16000, channels=Mono)), seconds=10)
assert rms(captured) > SILENCE_THRESHOLD          # the app produced audio out
```

Combined with the content-query check from §13.3, this proves the full loop — synthetic voice in,
real calendar write, synthetic TTS out — with no human and no real hardware. This is the strongest
zero-touch evidence available for "the voice pipeline actually works," and it should be the core
assertion behind Lane 1.2's "full spoken round-trip" done-when criterion.

**One thing to spike, not assume:** whether `injectAudio` behaves correctly under `-no-window`
with no audio device attached. This is foundational enough to this whole testing strategy that
it's worth confirming on real CI hardware in Lane 1.2's first days, not discovering it late.

### 13.5 Card-deck UI verification (Lane 1.1, cheapest tier first)

1. **Compose semantics tests** (`onNodeWithTag("card_deck").performTouchInput { swipeLeft() }`)
   — deterministic, hermetic, this is what CI gates on.
2. **`android layout --pretty`** as a live, agent-readable JSON UI tree — tag every card with
   `Modifier.testTag(...)` and a real `contentDescription` from day one so the tree stays
   machine-legible, not just human-legible.
3. **`uiautomator dump` + `adb shell input tap/swipe`** as a last-resort fallback only.

### 13.6 Android Auto — the honest limit, stated plainly

**Do not promise full headless automation for the Auto surface.** Google's own Desktop Head
Unit supports `--headless` and stdin-driven scripting for *steady-state* interaction — but
first-connect requires a real, previously-provisioned phone with a human accepting the Auto
terms of service and Play Store sign-in on-screen, once. This is a one-time manual step per
test device, not a per-run one: provision one physical or long-lived emulator image by hand,
snapshot or dedicate it, and script everything after that point through DHU's headless mode.
Do not architect Lane 1.2 or any CI gate around Auto being provisionable from a cold, freshly
wiped image — it isn't.

### 13.7 AI-driven UI exploration (Journeys) — useful, but never a merge gate

Android CLI's Journeys feature (LLM-driven, self-healing UI flows) is genuinely valuable for
exploratory "does this flow still make sense" checks that survive UI refactors — but real
production experience with it reports **~10% flakiness** and, more importantly, a documented
failure mode where **an AI-judged assertion returns green on an ambiguous instruction even when
the underlying behavior is wrong** (e.g., "verify the tab is selected" being satisfied by the
agent *selecting* the tab itself, rather than checking it was already selected). The mitigation
that worked in practice: word every Journey assertion in the negative-constrained form ("verify
X **without** doing X"), and **deliberately break every new Journey once to confirm it can
actually fail** before trusting a pass. Use Journeys for agent-driven triage and exploration;
keep the actual `/goal-batch` merge gate on deterministic Compose + instrumented tests +
`content query`/`dumpsys` checks (§13.1–13.5).

### 13.8 Structured feedback contract

Every lane's test harness should emit machine-readable JSON on stdout (exit code still 0/1 for
shell ergonomics) so an agent driving `/goal-batch` can reason about *which* check failed without
re-running the whole lane:

```json
{
  "lane": "1.2-voice-transport",
  "checks": [
    {"id": "app_launch",       "ok": true},
    {"id": "voice_roundtrip",  "ok": true,  "detail": "TTS RMS above silence threshold"},
    {"id": "calendar_write",   "ok": true,  "detail": "UUID a3f1... found via content query"},
    {"id": "notification_post","ok": false, "detail": "no entry in dumpsys for com.vela.app"}
  ],
  "artifacts": {
    "logcat": "artifacts/logcat.txt",
    "screenshots": ["artifacts/00_launch.png", "artifacts/01_after_voice.png"],
    "ui_tree": "artifacts/layout.json",
    "audio_out": "artifacts/tts_capture.wav"
  }
}
```

Always emit artifacts even on success — screenshots and the `android layout` JSON let an agent
reason visually about a passing lane without re-running it.

### 13.9 What is genuinely not automatable — set expectations honestly

| Limit | Why | Workaround |
|---|---|---|
| Android Auto first-connect ToS + Play sign-in | Requires a human on the physical screen, by Google's own design | One-time manual provisioning of a dedicated long-lived test device (§13.6) |
| Firebase Test Lab as an interactive loop | Managed runner, no ADB access | Use for nightly real-device coverage breadth only, never for lane feedback |
| GMD / hardware-accelerated emulators in plain Docker | Needs `/dev/kvm`, unavailable in unprivileged containers | `--device /dev/kvm`, a self-hosted runner, or a cloud device with SSH |
| AI-judged (Journeys) assertions | ~10% flakiness; can return false-green on ambiguous phrasing | Exploratory use only; deterministic tests gate the merge (§13.7) |
| `injectAudio` across parallel lanes on one emulator | Exactly one active microphone per instance | Lane isolation — one emulator per lane (§13.2) |

### 13.10 Cross-references

This section is binding on: Lane 1.1's done-when (card-deck verification, §13.5), Lane 1.2's
done-when (voice round-trip, §13.4, plus the `injectAudio`-under-`-no-window` spike called out
above), Lane 1.3's done-when (host-tool verification, §13.3), and Lane 3.2's done-when (live
activity + approval prompts, extend §13.5's tiering to the C2-driven UI). Each lane's harness
should emit the §13.8 JSON contract as part of its CI job, independent of whether it also runs a
Journeys-based exploratory pass.
