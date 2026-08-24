# Vela — Fleet Execution & Dispatch Plane

**Date:** 2026-08-24
**Status:** Proposed design — ready for review, then `/goal-batch` decomposition
**Design mode:** systems-design (ANALYZE → DESIGN → ASSESS)
**Issue:** [#17 G5: Fleet Execution & Dispatch](https://github.com/kenotron/vela/issues/17) (features #40, #41, #42, #43)
**Companion to:** `docs/designs/2026-08-16-vela-chief-of-staff-rebuild.md` (the "rebuild doc")

> This is the design the rebuild doc deferred. Rebuild doc §1.3 scoped the fleet
> execution plane out explicitly — "muxterm `sessiond` driving, cross-machine channel
> semantics, hyperscale fan-out scheduling, per-machine credential handling" — and
> specified only the contract (§4.5, D1–D5) that this plane must satisfy. This document
> owns everything on the far side of that contract line and changes nothing on the near
> side. If anything here forces a change to D1–D5, that is a revision request against the
> rebuild doc, not a local workaround.

---

## 1. Problem Framing

### 1.1 What is being asked

Vela's agent can decide that a unit of work should happen *somewhere else* — on a dev box,
a build machine, a GPU host, another person's laptop. Today `dispatch_to_fleet`
(`android/host-tools/.../DispatchToFleetTool.kt`) does the right thing against
`StubFleetPlane`, which is a function that returns `reachable = true` unconditionally. The
ask is to replace that stub with a real plane that dispatches work across multiple
machines and reports back.

### 1.2 What the system actually needs (as distinct from what was said)

R5 in the rebuild doc says "channels across muxterm + Claude Code + opencode + Amplifier
for hyperscale fan-out." Restated as system properties, that decomposes into five concerns
that do **not** share a component:

1. **Addressing** — naming a machine or a *capability*, and knowing whether it is alive
   right now, in under a second.
2. **Admission** — turning a spoken intent into a durable job record with an identity that
   outlives the phone, the app, the conversation, and the machine that runs it.
3. **Execution** — actually starting an agent process on a remote machine and keeping it
   alive across the dispatcher's restarts.
4. **Reporting** — pushing progress and attention into the ledger without being polled.
5. **Human re-entry** — a decision made on the phone reaching a process that is blocked on
   a remote machine, and a human being able to attach to that process directly and take
   over.

The framing insight that shapes everything downstream:

> **The <1s dispatch budget (D2) and the reachability guarantee (D3) are irreconcilable
> with an on-demand probe.** Any design where dispatch *initiates* a connection to the
> target machine — SSH, HTTP probe, TCP dial — pays a WAN round trip plus connection setup
> plus (for a sleeping laptop) a timeout, inside a budget of one second, inside a voice
> turn. The only way to satisfy D2 and D3 simultaneously is to make reachability a
> **lookup against a connection that is already open**. That single constraint selects the
> topology: **fleet machines dial in and hold a session; the dispatcher never dials out.**

This is the same structural move the rebuild doc made for voice (A8: neither side needs an
inbound public port, both dial out) — arrived at here independently, for a latency reason
rather than a security reason, with the security benefit as a bonus.

### 1.3 Scope of this document

**In scope:** the fleet plane's internals — topology, the broker, the per-machine worker,
how muxterm's `sessiond` and MCP surfaces are used, job identity and lifecycle, the fan-out
model, credential placement, cancellation, cost accounting, and D1–D5 conformance evidence.

**Explicitly out of scope:**

- **The Android side of `dispatch_to_fleet`.** It exists, it is tested, and it is correct.
  This design fits behind its `FleetPlane` interface (§8.4). If it needs to change, that is
  a finding, not a liberty.
- **The ledger service.** `services/ledger/` exists and is the contract surface. This plane
  is a *client* of `PATCH /ledger/jobs/{id}` and nothing more.
- **Deciding the production host for `vela-agentd`** (#42). Adjacent, and this document
  states a *constraint* on that decision (§8.6) rather than making it.
- **The agent runtimes themselves.** How Claude Code, opencode, or `amplifier-agent` do
  their work is their business. This plane starts them, watches them, relays to them, and
  reports on them.

---

## 2. Explicit Assumptions

Numbered, falsifiable, with confidence and consequence-if-false — same discipline as the
rebuild doc, because these are the places this design breaks silently.

| # | Assumption | Confidence | If false |
|---|---|---|---|
| **FA1** | Every fleet machine can make an **outbound** TLS connection to the broker and hold it open (directly or over Tailscale). | High — this is how every machine in this fleet already reaches everything else. | The dial-in topology collapses and Option B (SSH-out) or Option C (queue) becomes mandatory. Detectable on day one. |
| **FA2** | muxterm's MCP server is **stdio-only and local-only**: `muxterm mcp` speaks JSON-RPC over stdin/stdout and dials a **Unix socket** (`internal/mcp/run.go`, `client.go` → `sessiond.SocketPath()`). SSE transport is parsed but rejected (`cli.go`: "SSE arrives in Phase 5"). | **Verified in source.** | If an HTTP/SSE MCP transport lands upstream, the worker could be thinner — but the design does not depend on it, so this only ever gets cheaper. |
| **FA3** | muxterm has **no cross-machine abstraction**: no machine registry, no job identity, no callbacks, no fan-out. `internal/deploy/ssh.go` is a one-shot `scp` + systemd-unit installer, not a fleet controller. | **Verified in source.** | If a fleet layer appears upstream, §8 shrinks. Nothing here would be *wrong*, just redundant. |
| **FA4** | `run_command` is **synchronous and screen-derived**: it writes to a pane, blocks on an OSC 133 `;D` marker up to `timeout_ms` (default 30 000), and returns ANSI-stripped scrollback with `exit_code -1` on timeout (`internal/mcp/tools_terminal.go`). | **Verified in source.** | It stays unusable as a job runtime regardless (§7.4). This assumption is load-bearing for *why* the worker owns the job lifecycle rather than delegating it to muxterm. |
| **FA5** | An agent CLI running inside a PTY can be wrapped so it emits **structured JSONL events to a sidecar file** independent of its terminal rendering. | Medium-high — true for `amplifier-agent`, plausible for Claude Code/opencode via output redirection; **not verified per-runtime**. | Job state would have to be screen-scraped from the PTY — which §7.4 argues is unacceptable. Mitigation: the adapter layer (§4.3) isolates this per runtime; a runtime that cannot do it gets coarse states only (started/exited) and is documented as degraded. **Spike F-2.** |
| **FA6** | Each fleet machine already holds, or can hold, **its own** provider credentials. The broker never needs to carry or forward an LLM API key. | High — matches how every machine in this fleet is already set up (`ops/agent-serve/install.sh` writes credentials to a machine-local env file). | Credential distribution becomes a first-class subsystem with key rotation, and the broker becomes a high-value target. This assumption is what keeps the broker boring. |
| **FA7** | The fleet is **small enough that an in-process registry of live workers is adequate** — order 1–20 machines, not 1000. | **Low. This is Unresolved #1 from the rebuild doc (§2.1) and it is still unanswered.** See §2.1. | The broker's in-memory registry and single-process fan-out become the bottleneck. Mitigated structurally: §8.5 keeps the transport behind an interface so a durable queue is a component swap, not a redesign. |
| **FA8** | A dispatched job's useful lifetime is minutes-to-hours, not days, and it is acceptable for a job to be **killed by machine reboot** provided the ledger reflects that honestly. | Medium — matches "make this change and open a PR"; does not match "train this model for three days". | Jobs need checkpoint/resume, which is a materially larger piece of work. Detectable early from real job specs. |
| **FA9** | The broker can be **the sole ledger writer** for fleet jobs without becoming an availability problem for the ledger itself. | Medium-high — the ledger is durable and idempotent on `origin.tool_call_id` already (`services/ledger/ledger_service/db.py`). | Workers would need direct ledger credentials, multiplying the credential surface by the fleet size and violating the spirit of FA6. |

### 2.1 The unresolved question, stated honestly

**Rebuild doc §2.1 #1 — fleet size, homogeneity, and always-on-vs-intermittent — is still
unanswered, and this document does not assume an answer.**

It would be easy to quietly pick "a handful of always-on Linux boxes" and design for it.
That would be dishonest, because the answer changes the recommendation:

| If the answer is… | What changes |
|---|---|
| **3–10 machines, mostly always-on, homogeneous Linux** | Recommended design (§8) is correct as written. In-process registry, no queue. |
| **Dozens of machines, intermittently reachable laptops** | The registry needs persistence and the dispatch path needs a *durable* pending queue for offline targets — §8.5's transport swap becomes mandatory rather than optional, and D3 needs a policy decision: does dispatch to an offline-but-known machine *fail* (strict D3) or *queue* (D3 relaxed to "known target, currently offline")? **This is a product decision, not an engineering one.** |
| **Heterogeneous — macOS, Windows, containers** | The worker must be Go (single static binary, matching muxterm's own distribution model), and the job-wrapper protocol (§4.3) cannot assume a POSIX shell. |
| **Hyperscale, hundreds of ephemeral machines** | Option C (§7.1) is correct and this design is wrong. Nothing here survives except the contract mapping. |

**What this design does about it:** it is built so that the answer changes exactly **one
component** (the transport + registry, §8.5) and **zero contracts**. That is the most
useful thing a design can do in the presence of a genuinely unknown requirement — it is
not a substitute for the answer.

**Second unanswered question inherited:** rebuild doc §2.1 #3, the cost ceiling for
fan-out. This design carries cost fields end-to-end (§4.4) so the data exists before the
policy does, and defines the enforcement point (§8.7) without setting a number.

---

## 3. System Boundaries

```
┌──────────────────────── OUTSIDE (owned by the rebuild doc) ─────────────────────┐
│                                                                                  │
│   Vela Android app ──► dispatch_to_fleet (host tool, <1s, handle-returning)     │
│   vela-agentd       ──► C1/C2/C3                                                │
│   services/ledger   ──► POST/PATCH /ledger/jobs        ◄── this plane writes    │
│                                                                                  │
└──────────────────────────────────────────────────────────────────────────────────┘
                       │  D1..D5 contract (the only coupling)
                       ▼
┌─────────────────────────── INSIDE (this design) ────────────────────────────────┐
│                                                                                  │
│   ┌────────────────────────────────────────────────────────────┐                │
│   │  vela-fleetd  — the broker (exactly one, co-located with    │                │
│   │                 vela-agentd)                                │                │
│   │   · worker registry (live sessions, capability labels)      │                │
│   │   · admission: job id, ledger create, target selection      │                │
│   │   · sole ledger writer for fleet jobs                       │                │
│   │   · decision relay (phone → running job)                    │                │
│   │   · fan-out: parent/child job trees                         │                │
│   └────────────────────────────────────────────────────────────┘                │
│            ▲ workers DIAL IN and hold the session (never dialed out to)         │
│            │  WSS over Tailscale · mTLS or bearer · heartbeat                   │
│   ┌────────┴──────────┐  ┌───────────────────┐  ┌───────────────────┐          │
│   │ velafleet-worker  │  │ velafleet-worker  │  │ velafleet-worker  │          │
│   │  (machine: vela0) │  │  (machine: gpu1)  │  │  (machine: mbp)   │          │
│   │                   │  │                   │  │                   │          │
│   │  · job supervisor │  │                   │  │                   │          │
│   │  · runtime adapter│  │                   │  │                   │          │
│   │  · JSONL tail     │  │                   │  │                   │          │
│   │  · local creds    │  │                   │  │                   │          │
│   └─────────┬─────────┘  └───────────────────┘  └───────────────────┘          │
│             │ local Unix socket                                                 │
│   ┌─────────▼─────────────────────────────────┐                                 │
│   │  muxterm sessiond (per machine, existing)  │  ◄── used, not modified        │
│   │   · PTY pane hosting the job               │                                │
│   │   · human attach / takeover                │                                │
│   │   · send_input for decision delivery       │                                │
│   └────────────────────────────────────────────┘                                │
└──────────────────────────────────────────────────────────────────────────────────┘
```

### 3.1 Boundary rules (invariants)

| # | Rule |
|---|---|
| **FB1** | **The broker never dials a fleet machine.** All fleet connections are worker-initiated and held. Any code path that opens a connection *to* a fleet machine at dispatch time is a D2/D3 violation. |
| **FB2** | **The broker is the only ledger writer for fleet jobs.** Workers report to the broker; they hold no ledger credentials and no ledger URL. |
| **FB3** | **No LLM provider credential ever crosses the broker.** Workers use machine-local credentials (FA6). The broker is not a secrets distributor. |
| **FB4** | **Job state is never derived from screen scraping.** PTY output is for humans. Machine-readable job state comes from the structured sidecar stream (§4.3). See §7.4 for why this is a rule and not a preference. |
| **FB5** | **muxterm is not modified.** This plane consumes muxterm's existing surfaces. If it needs something muxterm lacks, that is an upstream contribution with its own justification — not a fork. |
| **FB6** | **The phone never talks to the fleet plane.** `dispatch_to_fleet` calls the broker; nothing on the device knows a fleet machine exists. |

---

## 4. Components and Responsibilities

### 4.1 `vela-fleetd` — the broker

One process, co-located with `vela-agentd` (§8.6). Small by design; it holds no work, only
knowledge of work.

**Owns:**

| Concern | Detail |
|---|---|
| **Worker registry** | Live worker sessions keyed by `machine_id`, each carrying capability labels, last heartbeat, and current job load. Source of truth for D3. |
| **Admission** | `POST /fleet/dispatch` — validate spec, select target, create the ledger record, hand the job to the worker, return a handle. Must complete in <1s (D2). |
| **Ledger writing** | The sole writer (FB2). Coalesces worker progress into `PATCH /ledger/jobs/{id}` with a bounded flush interval so a chatty job cannot flood the ledger. |
| **Decision relay** | Subscribes to `/ledger/events`; on a recorded decision for a job it owns, pushes it down the live worker session. |
| **Fan-out** | Parent/child job trees, per-target children, aggregate rollup (§5.3). |
| **Reconciliation** | On worker reconnect, diff the worker's reported job set against the broker's, and correct the ledger for anything that died while disconnected. |

**Does not own:** execution, credentials, PTYs, scheduling policy beyond simple label
matching and least-loaded selection.

**Durability:** the broker keeps a small local store of `job_id → (machine_id, spec,
last_known_state)`. It is a *cache with a durable backing*, not a second ledger — the
ledger remains the source of truth for anything a human sees. The broker's store exists so
that a broker restart can reconcile with reconnecting workers rather than orphaning jobs.

### 4.2 `velafleet-worker` — the per-machine agent

One long-lived process per fleet machine. Go, single static binary, installed the same way
muxterm is (FA-heterogeneity hedge, §2.1).

**Responsibilities:**

1. **Dial in and stay in.** Outbound WSS to the broker, heartbeat, exponential reconnect.
   This connection *is* the reachability signal (D3).
2. **Declare capabilities.** `machine_id`, OS/arch, installed runtimes (`amplifier-agent`,
   `claude`, `opencode`), free disk, GPU presence, arbitrary operator-set labels. Sent on
   connect and on change.
3. **Supervise jobs.** Start, watch, restart-on-crash-if-policy-says-so, kill on cancel.
   The worker is the process's parent — **not** the broker, and **not** muxterm.
4. **Host the job in a muxterm pane** (§4.3) so a human can attach and take over.
5. **Tail the structured sidecar** and forward events upstream.
6. **Relay decisions** into the running job.
7. **Survive broker absence.** Jobs keep running while the broker is unreachable; state is
   buffered on disk and replayed on reconnect.

### 4.3 The job wrapper — where muxterm actually fits

This is the part that required the most care, so the reasoning is stated in full.

**The naive design** is: worker calls muxterm's `create_pane`, then `run_command`, and
reads the result. **It does not work**, for three verified reasons (FA4):

- `run_command` **blocks** until an OSC 133 `;D` marker or `timeout_ms` (default 30s). A
  fleet job takes minutes to hours. It would time out and return `exit_code -1` while the
  job was still running perfectly well.
- Its return value is **ANSI-stripped scrollback** — a screen, not a result. Deriving
  "did the job succeed, what did it produce, does it need a decision" from a rendered
  terminal is exactly the class of bug the preserved-lessons doc already records once
  (`"executing"` vs `"running"`: inventing plausible status strings produces a
  permanently-empty list with no error).
- The pane buffer is a **ring buffer**. Long jobs lose their own early output.

**The design that works** splits *rendering* from *reporting*:

```
worker
  ├─ mcp: create_pane          ──► muxterm sessiond ──► PTY pane (human-attachable)
  ├─ mcp: send_input           ──► launches:  velafleet-run --job <id> -- <runtime argv>
  │                                            │
  │                                            ├─ execs the agent runtime in the PTY
  │                                            │    (humans see it, can attach, can type)
  │                                            └─ writes JSONL events to
  │                                                 ~/.vela/jobs/<job_id>/events.jsonl
  │
  └─ tails ~/.vela/jobs/<job_id>/events.jsonl ──► structured job state ──► broker ──► ledger
```

`velafleet-run` is a ~200-line supervisor shim. It runs the real runtime, and emits one
JSONL line per state change:

```jsonl
{"ts":1756000000,"kind":"started","runtime":"amplifier-agent","pid":41233}
{"ts":1756000012,"kind":"progress","message":"cloned repo, running tests","percent":20}
{"ts":1756000180,"kind":"attention","reason":"Two migration strategies are viable; which?","options":["in-place","blue-green"]}
{"ts":1756000420,"kind":"cost","usd":0.41,"tokens":88300}
{"ts":1756000900,"kind":"finished","exit_code":0,"result":{"pr_url":"https://…"}}
```

**What this buys, and why it is the right shape:**

| Property | How |
|---|---|
| **Job state is structured** (FB4) | The JSONL file, never the screen. |
| **Human takeover is free** | The job runs in a real muxterm pane. A human opens muxterm, attaches, and is *inside the running job* — the single most valuable thing muxterm brings, and it is available for zero additional work. |
| **Decision delivery is trivial** | `send_input` writes to the pane's stdin. A blocked agent prompt reads it. muxterm's `send_input` was designed for exactly this (literal-bytes vs key-names split), which is why it is safe to relay arbitrary user text through it. |
| **Survives worker restart** | JSONL is a file with an offset. The worker resumes tailing from where it stopped; the job never noticed. |
| **Survives broker restart** | Same, one level up. |
| **Degrades honestly** | A runtime that cannot emit JSONL (FA5) gets `started` / `finished` only, from the shim's own process observation. Coarse, but never *wrong*. |

**muxterm is the observability and human-re-entry surface. It is not the job runtime, and
it is not the source of job state.** That sentence is the entire answer to "where does
muxterm's existing sessiond/MCP surface fit."

### 4.4 The job spec (D1)

The wire form of D1. Deliberately small; targeting is by **capability**, not by hostname,
so that the fleet can change shape without the agent's prompts changing.

```jsonc
{
  "title": "Fix the flaky auth test on main",       // human-facing, goes in the card deck
  "summary": "…",                                   // what the job will do
  "runtime": "amplifier-agent",                     // amplifier-agent | claude | opencode | shell
  "prompt": "…",                                    // the actual instruction to the runtime
  "target": {
    "machine_id": null,                             // exact pin (rare, escape hatch)
    "labels": ["linux", "has:repo/vela"],           // capability match (normal case)
    "strategy": "least_loaded"                      // least_loaded | all | any
  },
  "cwd": "~/workspace/vela",
  "limits": { "wall_clock_s": 3600, "usd": 5.00 },  // §8.7
  "callback": { "ledger_job_id": "…" }              // D4 target; broker fills this in
}
```

Note `target.strategy: "all"` — that is the fan-out primitive (§5.3), and it is the whole
of "hyperscale fan-out" as a schema concern. Everything else about fan-out is job-tree
bookkeeping in the broker.

### 4.5 Credential placement (per-machine credential handling)

| Secret | Lives | Never |
|---|---|---|
| LLM provider keys | Machine-local env file on each worker, exactly as `ops/agent-serve/install.sh` already does | Passes through the broker (FB3), appears in a job spec, or is logged |
| Worker↔broker auth | Per-worker credential issued at enrollment; revocable individually | Shared across machines |
| Ledger auth | Broker only (FB2) | On any worker |
| muxterm auth | Worker↔sessiond is a **local Unix socket** — filesystem permissions are the boundary (FA2) | Exposed to the network |

The consequence worth naming: **compromising the broker gets you the ability to run jobs,
but no keys.** Compromising one worker gets you one machine's keys. That blast-radius
shape is a direct result of FB3 and is the main security argument for the topology.

---

## 5. Data and Control Flows

### 5.1 Dispatch (the <1s path — D1, D2, D3)

```
1.  Model selects dispatch_to_fleet; SSE stream ends (rebuild doc A5)
2.  Android DispatchToFleetTool.run():
      ├─ POST /ledger/jobs            ← ordering-critical, ALREADY IMPLEMENTED
      └─ fleetPlane.dispatch(spec)    ← the interface this design fills
3.  vela-fleetd POST /fleet/dispatch:
      ├─ validate spec                                        ~1ms
      ├─ select target from the LIVE worker registry          ~0ms   ← D3, in-memory lookup
      │     └─ no live worker matches?  ►  400 UNREACHABLE, with the reason
      ├─ persist job→machine binding to broker store          ~2ms
      └─ push job over the ALREADY-OPEN worker session        ~5ms   ← no dial, no handshake
4.  Return {job_id, status: "accepted", machine_id}           total ≈ 10ms LAN / 60ms Tailscale
5.  Android re-POSTs {role:"tool", …} on C1; the turn continues
```

**Step 3 is the design.** The registry lookup replaces a network probe. That is the only
reason the p99 <1s budget is comfortable rather than marginal — and it is why "just SSH to
the box and check" (§7.1 Option B) fails D2/D3 despite being simpler.

**The honest edge case:** a worker whose TCP session is still open but whose machine has
frozen. Heartbeat bounds this — a worker is only "live" if its last heartbeat is within
`2 × heartbeat_interval`. The window of dishonesty is therefore bounded and known, and the
dispatch response carries `last_heartbeat_age_ms` so the caller can see it. **This is a
real, residual D3 gap and it is stated rather than papered over:** D3 guarantees "the
target was alive within the last N seconds", not "the target is alive". No design that
does not pay a synchronous probe can do better, and a synchronous probe cannot meet D2.

### 5.2 Progress and attention (D4, D5)

```
velafleet-run ──► events.jsonl ──► worker tail ──► broker
                                                     │
              ┌──────────────────────────────────────┤
              │                                      │
   kind=progress                            kind=attention
              │                                      │
   coalesced, flushed on a bounded         IMMEDIATE, never coalesced
   interval (default 2s) to                PATCH /ledger/jobs/{id}
   PATCH /ledger/jobs/{id}                   attention: {required: true,
     progress_entry: {…}                                  reason, options}
              │                                      │
              ▼                                      ▼
        ledger SSE ──► app UI only              ledger SSE ──► NOTIFICATION + card deck
        (never notifies — rebuild doc §5.4)
```

**Coalescing progress but never coalescing attention** is the one piece of policy the
broker holds, and it exists because of rebuild doc F-8: notification spam kills the entire
attention model. A job that emits 400 progress lines must not produce 400 ledger writes;
a job that needs a decision must produce that write *now*.

### 5.3 Fan-out (`strategy: "all"`)

```
dispatch(strategy="all", labels=["linux","has:gpu"])
   │
   ├─ broker resolves the label set against the live registry → [gpu1, gpu2, gpu3]
   ├─ creates ONE parent ledger job    (status: running, spec.fanout: {n: 3})
   ├─ creates THREE child ledger jobs  (origin.parent_job_id = parent)
   └─ returns the PARENT handle to the phone            ← one handle, one card
```

Rollup rules, chosen so the card deck stays sane:

| Child states | Parent state |
|---|---|
| any child `needs_attention` | `needs_attention`, with reason `"1 of 3 machines needs a decision"` and the child's options |
| all children terminal, all `done` | `done` |
| all children terminal, ≥1 `failed` | `failed`, result carries the per-child breakdown |
| otherwise | `running`, progress = `"2 of 3 complete"` |

**The user sees one card for a three-machine fan-out.** This is the difference between
fan-out being a capability and fan-out being a notification denial-of-service against its
own owner.

Resolution of `strategy: "all"` is **at dispatch time against the live registry**, and the
resolved set is frozen into the parent spec. A machine that comes online thirty seconds
later is not silently added. Late-joining semantics are a scheduling feature; this is a
dispatcher.

### 5.4 Decision delivery (closing the human loop)

```
User swipes a card ──► POST /ledger/jobs/{id}/decision      (already implemented)
                          │
                          ▼
                    ledger SSE: decision.recorded
                          │
                    broker (subscriber) — is this a job I own?
                          │ yes
                          ▼
                    push over the live worker session
                          │
                    worker ──► mcp: send_input(pane_id, text=decision, keys=["Enter"])
                          │
                    blocked agent prompt reads stdin and continues
                          │
                    velafleet-run emits {"kind":"attention_cleared"} ──► ledger
```

**Failure mode designed for:** the job died while waiting for the decision. The worker
answers `job_not_found`, and the broker patches the ledger to `failed` with reason
`"job exited while awaiting decision"`. The card resolves; nothing hangs. This mirrors the
rebuild doc's F-4 discipline — never an unbounded wait, always a definite resolution.

### 5.5 Cancellation

`POST /fleet/jobs/{id}/cancel` → broker → worker → `SIGTERM` to the process group, 10s
grace, `SIGKILL`. Pane is left open with the termination banner so a human can see what
happened, and is reaped after a retention window. Ledger goes to `cancelled`.

Cancellation while the broker is down is buffered client-side and applied on reconnect. A
job the user believes is cancelled but is still burning tokens is a cost bug, so the ledger
records `cancel_requested_at` separately from `cancelled_at` and the gap is monitorable
(§11).

---

## 6. Risks and Failure Modes

| # | Failure | Likelihood | Blast radius | Mitigation |
|---|---|---|---|---|
| **FF-1** | Broker is a single point of failure. | Medium | **No new dispatches; running jobs unaffected.** | Deliberate. Workers are autonomous once started (§4.2 item 7); the ledger is separate; the phone degrades to "can't start new fleet work", which is legible. Restart is seconds. Not clustered — see §7.4. |
| **FF-2** | Zombie worker: session open, machine wedged. Dispatch succeeds against a dead target. | Medium | One job silently stalls. | Heartbeat window bounds it (§5.1). `last_heartbeat_age_ms` is returned to the caller. Job-level wall-clock limit (§8.7) converts a stall into a `failed` within a bounded time. **This is the residual D3 gap, stated in §5.1.** |
| **FF-3** | Screen-scraping creeps back in — someone "just parses the pane" for a state the JSONL doesn't carry. | **High, over time** | Silent wrong job states; the worst failure class because it looks like it works. | FB4 as an invariant, plus: the worker has **no code path that reads pane content**. It calls `create_pane` and `send_input`, never `get_screen` or `run_command`. Enforced by a lint on the worker's muxterm client, not by review. |
| **FF-4** | Progress flood: a chatty job produces thousands of ledger writes. | Medium-high | Ledger write amplification; app UI churn; notification risk. | Bounded coalescing (§5.2), plus a per-job progress-rate cap that drops to sampling with an explicit `"progress truncated"` marker rather than silently losing entries. |
| **FF-5** | Fan-out notification storm — N machines each demanding a decision. | Medium | User disables notifications; the whole attention model dies (rebuild doc F-8). | Parent rollup (§5.3): one card per fan-out, always. |
| **FF-6** | Cost blowout from fan-out. | Medium | Financial. | Cost events flow from `velafleet-run` (§4.3) into ledger `cost` fields from day one. Enforcement point defined (§8.7); **the limit itself is Unresolved #3 and is not invented here.** |
| **FF-7** | Worker↔broker version skew after a partial rollout. | High (it always happens) | Jobs rejected or mis-parsed. | Explicit protocol version in the worker hello; broker refuses an incompatible worker **at connect** with a clear message, rather than accepting it and failing at dispatch. A refused worker is simply not in the registry, so D3 correctly reports it unreachable. |
| **FF-8** | muxterm absent or `sessiond` not running on a fleet machine. | Medium | That machine can run jobs but offers no human-takeover surface. | **Degrade, don't fail.** Worker runs the job as a plain child process, reports `pane: null`, and declares the label `no:muxterm`. Human takeover is a valuable feature, not a correctness requirement. |
| **FF-9** | Broker restart orphans in-flight jobs. | Medium | Ledger shows `running` forever for jobs that are dead. | Broker durable job→machine store + reconnect reconciliation (§4.1). Workers re-declare their live job set on every connect; anything the broker thought was running and no worker claims is patched to `failed` with reason `"orphaned across broker restart"`. |
| **FF-10** | The unanswered fleet-shape question (§2.1) turns out to be "hundreds of ephemeral machines". | Unknown — **that is the point** | This design is wrong; Option C is right. | Transport behind an interface (§8.5). Contracts unchanged. Rewrite is one component, not the plane. |
| **FF-11** | `dispatch_to_fleet` p99 creeps past 1s as the fleet grows (registry scan, label matching). | Low-medium | Voice UX degrades; G3 gate fails. | Registry lookup is O(live workers) with an index on labels. Instrumented against the existing G3 assertion in `BaseHostTool` — the gate already exists and already fails loudly. |

---

## 7. Tradeoffs

### 7.1 Candidates compared

| | Candidate |
|---|---|
| **A** | **Broker + dial-in workers** — `vela-fleetd` registry, workers hold outbound sessions, muxterm for visibility/takeover *(recommended)* |
| **B** | **Brokerless SSH-out** — `vela-agentd` SSHes to each machine on demand and runs `muxterm mcp` over SSH stdio *(simplest credible alternative)* |
| **C** | **Durable-queue fan-out** — NATS/Redis Streams/equivalent; workers consume; broker becomes a thin producer |
| **D** | **Extend muxterm upstream** — add a cross-machine channel abstraction and job identity to muxterm itself; Vela becomes a client of it |

### 7.2 The 8-dimension matrix

| Dimension | A — Broker + dial-in | B — SSH-out | C — Durable queue | D — Extend muxterm |
|---|---|---|---|---|
| **Latency** | **Good.** D3 is an in-memory lookup; dispatch ≈10–60ms. Comfortably inside the 1s budget. | **Poor.** SSH connect + auth + remote process spawn on every dispatch: 300ms–3s warm, unbounded to a sleeping laptop. **Fails D2/D3 for the case D3 exists to catch.** | **Good.** Enqueue is fast — but "is the target alive" becomes "is anyone consuming", which is a *weaker* answer to D3. | Good in principle; depends entirely on what gets built. |
| **Complexity** | **Adequate.** Two new small components (broker, worker) + a 200-line shim. One new protocol. | **Best.** Zero new daemons. Uses muxterm exactly as it ships today. | **Poor.** A queue is infrastructure: deployment, persistence, monitoring, retention, poison-message handling — all for an unknown fleet size. | **Poor for Vela.** Requires cross-machine, job-identity, and callback semantics in a codebase whose architecture doc explicitly scopes to workspaces and panes. |
| **Reliability** | **Good.** Workers autonomous; broker restart reconciles; jobs survive both. Broker is an SPOF for *new* dispatch only. | Poor. Every dispatch depends on SSH working right now. No progress path at all without inventing one. | **Best.** Queue durability covers broker outage; offline machines drain on return. | Unknown. |
| **Cost** | **Good.** Two small Go/Python processes. | **Best.** Nothing to run. | Higher — a queue to operate forever. | Highest — upstream work on someone else's roadmap. |
| **Security** | **Good.** No inbound ports on fleet machines. No credentials cross the broker (FB3). Per-worker revocable enrollment. | Adequate — but requires SSH keys with remote-exec rights held by the *dispatcher*, which is a materially larger authority than "may submit a job". | Good; queue ACLs are well-understood. | Neutral; muxterm's `/input` is already an acknowledged RCE surface (rebuild doc §9.6 on muxplex) and widening it deserves its own review. |
| **Scalability** | **Adequate — bounded by FA7.** Fine for tens; the registry is the ceiling. | Poor. N SSH connections per fan-out, serially or with a bespoke pool. | **Best.** This is what queues are for. | Unknown. |
| **Reversibility** | **Good.** Transport behind an interface → C is a component swap (§8.5). The worker, the shim, the JSONL protocol, and all contract mappings survive. | **Best in isolation** — nothing to undo — but every capability (progress, attention, cancel, fan-out) has to be invented on top, and none of that work transfers. | Medium. Queue choice is sticky; the operational model is hard to unwind. | **Worst.** Depends on upstream acceptance; unaccepted work is sunk. |
| **Org fit** | **Good.** One person can hold it. Same shape as the rest of the system (Tailscale, dial-out, systemd units, `ops/` scripts). | Good for a week, then the missing capabilities are built ad-hoc and badly. | **Poor.** Introduces operational surface disproportionate to a fleet whose size is *unknown*. | Poor. Blocks Vela on another repo's roadmap. |
| **Optimizes for** | Meeting D1–D5 honestly at the smallest owned surface. | Time to first remote job. | Scale and durability. | Ecosystem leverage. |
| **Sacrifices** | Accepts a broker SPOF for new dispatch, and a bounded heartbeat window on D3. | Sacrifices D2, D3, D4, and D5 — i.e. the contract. | Pays permanent ops burden for scale that may not exist. | Pays schedule control. |

### 7.3 The dominant tradeoff

**The decision is driven by latency and complexity, in that order — and latency decides it
before complexity gets a vote.**

- **A vs B** is not close, and it is decided by D2+D3 together. B is genuinely simpler and
  would be the right answer if D3 did not exist. But D3 exists precisely to catch "the
  target is unreachable", and B's way of discovering that is *to time out attempting to
  reach it* — inside a 1s budget, inside a voice turn. B fails the specific requirement it
  would have to satisfy. (B remains valuable as a milestone; see §9.1.)
- **A vs C** is decided by complexity against an **unknown** (FA7/§2.1). Adopting a durable
  queue for a possibly-three-machine fleet is infrastructure bought against a requirement
  nobody has stated. A is built so C stays available (§8.5). If the fleet answer comes back
  "hundreds", switch — that is the designed escape, not a failure.
- **A vs D** is decided by org fit and schedule control. muxterm is single-machine by
  architecture (FA3), and making it multi-machine is a real design in someone else's repo.
  Vela should not block on it. A specific, narrow upstream contribution remains attractive
  later (§8.8).

### 7.4 What would have to be true for A to be the wrong choice?

Each of these is a monitorable signal, in the spirit of rebuild doc §7.4:

1. **If the fleet turns out to be large or highly intermittent** (§2.1) — C becomes correct.
   *Signal: the answer to Unresolved #1. Ask before Stage F2.*
2. **If workers cannot hold a stable outbound session** (FA1 false — corporate egress
   filtering, aggressive NAT timeouts) — the dial-in premise collapses and B or C returns.
   *Signal: reconnect rate per worker in the first week.*
3. **If broker downtime turns out to be intolerable** — i.e. "I couldn't start a job for 90
   seconds" is reported as a real product failure rather than a shrug — then C's durability
   is worth its ops cost. *Signal: user-visible dispatch failures per week.* **Explicitly
   not pre-solved by clustering the broker: two brokers with a shared registry is a
   consensus problem, and buying consensus before anyone has complained about a restart is
   the exact complexity-theater this design is trying to avoid.*
4. **If no runtime can emit structured events** (FA5 false across the board) — then job
   state must come from the PTY after all, FB4 has to be relaxed, and the honest
   consequence is coarse-grained jobs with no progress and no attention. That would make
   D4/D5 unsatisfiable and is a revision request against the rebuild doc, not a workaround.
   *Signal: Spike F-2.*
5. **If human takeover is never used** — then the muxterm pane is dead weight and the worker
   should run jobs as plain child processes, dropping a dependency. *Signal: count pane
   attaches per job over the first month.* This one is cheap to be wrong about in either
   direction, which is why the design already tolerates `no:muxterm` machines (FF-8).

---

## 8. Recommended Design

### 8.1 One-paragraph statement

Introduce **`vela-fleetd`**, a small broker co-located with `vela-agentd`, and
**`velafleet-worker`**, one long-lived process per fleet machine that **dials in** to the
broker and holds the session open. Reachability (D3) becomes an in-memory registry lookup
rather than a network probe, which is what makes the <1s handle-return (D2) comfortable
rather than marginal. The broker is the sole ledger writer (D4), coalescing progress but
never coalescing attention (D5). Each job runs inside a **muxterm pane** — giving free
human attach-and-take-over — but its machine-readable state comes from a **structured JSONL
sidecar** written by a thin `velafleet-run` supervisor shim, never from the terminal
screen. Fan-out is a parent/child job tree that presents as exactly one card. muxterm is
consumed unmodified, as the observability and human-re-entry surface; it is not the job
runtime and not the source of job state.

### 8.2 Why this specific shape

| Decision | Reason |
|---|---|
| Workers dial in; broker never dials out | The only way to satisfy D2 and D3 simultaneously (§1.2). Security posture is a bonus. |
| Broker is the sole ledger writer | Collapses the credential surface from N machines to 1 (FB2/FA9), and gives one place to enforce coalescing and idempotency. |
| Job runs in a muxterm pane | Human takeover for free — the single highest-value thing muxterm brings, and it costs two MCP calls. |
| Job state from JSONL, never the screen | `run_command` is synchronous, 30s-bounded, and returns stripped scrollback (FA4). Screen-derived state is a known, already-shipped bug class in this codebase's history. |
| Targeting by capability labels, not hostnames | The fleet changes shape; the agent's prompts should not. |
| Fan-out presents one card | Rebuild doc F-8: notification spam is how the attention model dies. |
| Provider credentials never cross the broker | Blast radius shaping (§4.5). Also keeps the broker small enough to reason about. |
| No broker clustering | See §7.4 item 3. Consensus is not free and nobody has complained yet. |
| muxterm unmodified | It is a healthy upstream with its own architecture doc and roadmap. Forking it would be the second fork in this system; one (`vela-agentd`) is already the budget. |

### 8.3 D1–D5 conformance mapping (this is what #43 validates)

| # | Contract | How this design satisfies it | Verifiable by |
|---|---|---|---|
| **D1** | Accept a job spec — structured work description, targeting hints, callback reference | `POST /fleet/dispatch` with the §4.4 schema. `target.labels` is the targeting hint; `callback.ledger_job_id` is the callback reference, filled by the broker at admission. | Schema test + round-trip test |
| **D2** | Return a job handle synchronously in <1s | No network dial in the dispatch path (§5.1). Measured p99 across ≥100 dispatches, LAN and Tailscale. | **Gate FG-1** |
| **D3** | Verify reachability synchronously | Live-registry lookup with a heartbeat freshness bound. Unreachable → `400 UNREACHABLE` with a human-readable reason, surfaced by the existing `DispatchToFleetTool` failure path into the conversation. **Residual gap stated in §5.1.** | **Gate FG-2** |
| **D4** | Push progress to the ledger via `PATCH /ledger/jobs/{id}` | Broker is the sole writer; progress coalesced on a bounded interval (§5.2). Never polled. | **Gate FG-3** |
| **D5** | Set `attention.required` with reason and options | `kind:"attention"` in the JSONL → immediate, uncoalesced PATCH with `reason` + `options` → ledger SSE → notification + card. Round-trips back through §5.4. | **Gate FG-4** |

**#40** (handle-returning, <1s, always) is D2 and is already enforced client-side by
`BaseHostTool.maxSyncMillis = 1000`; this design's job is to not break it.
**#41** (synchronous reachability) is D3 and is §5.1 step 3.
**#43** is this table plus its gates.

### 8.4 What changes on the Android side

**One line.** `DispatchToFleetTool` already takes a `FleetPlane` and already has the correct
ordering, the correct failure path, and the correct handle shape:

```kotlin
// android/host-tools/.../DispatchToFleetTool.kt — unchanged
interface FleetPlane { fun dispatch(jobSpec: String): DispatchOutcome }

// NEW: replaces StubFleetPlane in VelaAppContainer
class HttpFleetPlane(baseUrl: String, auth: String) : FleetPlane { … }
```

`StubFleetPlane` **stays in the tree** as the test double. The existing
`DispatchToFleetToolTest` p99 measurement keeps working against it. That the interface
already fits is a small piece of evidence that the contract line was drawn in the right
place.

### 8.5 The transport seam (the designed escape hatch)

The broker's worker-facing side is an interface, not a WebSocket:

```
WorkerTransport
  ├─ liveWorkers() -> [WorkerHandle]        // D3 depends only on this
  ├─ send(machine_id, message)
  └─ onMessage(handler)

  implementations:
    · DialInWSTransport   ← ship this (Option A)
    · QueueTransport      ← Option C, if §2.1 answers "large/intermittent"
```

Swapping to a durable queue changes `liveWorkers()` from "open sessions" to "consumers with
recent presence" and changes nothing else — not the job spec, not the JSONL protocol, not
the ledger writes, not the Android side, not D1–D5. **That is the concrete, checkable form
of "the unanswered question changes one component."**

### 8.6 Constraint on the `vela-agentd` production-host decision (#42)

Not deciding #42 here, but this design constrains it: **`vela-fleetd` must be co-located
with `vela-agentd`.** The phone reaches the broker on the dispatch path, inside the same
1s budget as the rest of the tool call, and adding a second network hop to a second host
spends budget for nothing. Whatever host #42 picks, `vela-fleetd` goes there, in the same
`ops/` supervision pattern as `ops/agent-serve/` and `ops/vela-agentd/`.

Corollary: that host must accept **inbound** worker connections from the fleet — trivially
satisfied on Tailscale, which is already the reachability model.

### 8.7 Cost and limits (the enforcement point, not the number)

`limits.wall_clock_s` and `limits.usd` are carried in the spec (§4.4) and enforced by
`velafleet-run` locally — the shim kills the runtime and emits
`{"kind":"finished","exit_code":124,"reason":"limit_exceeded"}`. Enforcement is local
because a limit enforced by the broker is a limit that stops working when the broker is
down, and an unbounded job during a broker outage is exactly the cost bug FF-6 describes.

Defaults are deliberately left as a config value with a conservative placeholder
(`wall_clock_s: 3600`, `usd: 5.00`) pending Unresolved #3. **The mechanism ships; the
policy waits for the answer.**

### 8.8 Possible upstream contribution to muxterm (later, narrow)

If human takeover proves valuable (§7.4 item 5), one narrow upstream ask is worth making:
a **pane-exit event with exit code** delivered as an unsolicited event, rather than only via
`run_command`'s synchronous OSC 133 wait. That would let the worker learn about a died job
without either polling or holding a `run_command`. It is a small, general addition
consistent with muxterm's existing event taxonomy — and notably it is *not* a cross-machine
feature, so it does not ask muxterm to become something its architecture doc says it isn't.

Framed that way it is likely acceptable upstream. Framed as "add fleet support to muxterm"
it is not, and should not be.

### 8.9 Feasibility spikes — before any broker work

| Spike | Question | Pass criterion | Falsifies |
|---|---|---|---|
| **F-1 · dial-in latency** | Does a held outbound session over Tailscale actually deliver a job in <100ms, and survive 24h of NAT idling? | 1000 dispatches over a session held ≥24h: p99 broker-receipt-to-worker-receipt <100ms; zero unrecovered disconnects. | FA1 → the whole topology |
| **F-2 · structured job events** | Can each target runtime (`amplifier-agent`, `claude`, `opencode`) be wrapped to emit JSONL progress/attention **while running inside a PTY**? | For each runtime: a real job produces ≥1 `progress` and ≥1 `attention` event in `events.jsonl`, with the PTY simultaneously showing normal interactive output. | FA5 → D4/D5 **and** FB4 |
| **F-3 · decision round-trip** | Does `send_input` into a muxterm pane actually unblock an agent runtime waiting on stdin? | End-to-end: job emits `attention` → ledger → card → decision → `send_input` → runtime continues → `attention_cleared` in the ledger. Measured on `amplifier-agent` at minimum. | §5.4 → D5's return path |

**F-2 is the sneaky-important one**, exactly as S-3 was in the rebuild doc. It is the only
spike that tests the interaction between "runs in a terminal for humans" and "reports
structurally for machines" — and if it fails, FB4 has to be relaxed and D4/D5 become
unsatisfiable in their current form. That is a finding that goes back to the rebuild doc.

---

## 9. Simplest Credible Alternative

### 9.1 Candidate B (SSH-out) as milestone F0

The same move the rebuild doc made with candidate D: use the simplest thing as a
**milestone**, not a destination.

**F0 — SSH-out, no broker.** `vela-agentd` gets a `fleet` module that SSHes to a
**hardcoded single machine**, launches `velafleet-run` under `nohup`, and returns the
handle. Reachability = a cached SSH connection with a 500ms budget and an honest failure
past that. Progress = the same JSONL, tailed over the same SSH connection.

**What you get:** the first genuinely remote job, end to end, in days. Real evidence about
whether jobs are minutes or days (FA8), whether takeover matters (§7.4 item 5), and what
job specs actually look like.

**What you don't get:** reliable D3, multi-machine, fan-out, or a decision path that
survives a dropped SSH session.

**Why it survives into A:** `velafleet-run`, the JSONL protocol, the runtime adapters, the
job spec, and the ledger-writing code are all **identical**. F0 throws away only the
transport — which is precisely the component §8.5 already declares swappable. That is not a
coincidence; it is the same seam serving two purposes.

### 9.2 Rejected: reuse muxterm's `run_command` as the job runtime

Rejected on verified grounds (FA4): synchronous, 30s default timeout, ANSI-stripped
ring-buffered scrollback as the return value. It is an excellent tool for "run `make test`
and tell me what happened" and a poor foundation for a job that runs for an hour and needs
to ask a question halfway through. §4.3 uses `create_pane` and `send_input` and
deliberately never calls `run_command` or `get_screen`.

### 9.3 Rejected: workers write to the ledger directly

Attractive — it deletes the broker from the D4 path. Rejected because it puts ledger
credentials on every fleet machine (violating the spirit of FB3), removes the single place
where progress coalescing (FF-4) and fan-out rollup (§5.3) can happen, and means an
attacker with one machine can write arbitrary state about *any* job. The broker's cost is
one hop; its benefit is a credential surface of one and a policy surface of one.

### 9.4 Rejected: put the fleet plane inside `vela-agentd`

Tempting — one fewer process, and it is already co-located. Rejected because `vela-agentd`
is a **thin, rebaseable fork** with an explicit ≤4-fork-point budget (rebuild doc B2).
Adding a worker registry, a WebSocket server, and a job supervisor to it would blow that
budget and make the rebase story materially worse. `vela-fleetd` being a *separate process
on the same host* costs one systemd unit and preserves the property that makes the fork
acceptable at all.

---

## 10. Migration and Rollout Plan

### Stage F0 — SSH-out single machine *(1 lane · no new daemons)*

Delivers the first real remote job. Builds `velafleet-run`, the JSONL protocol, and the
runtime adapters — all of which survive into every later stage.

- **Lane F0.1 — `fleet-run-shim`**
  - **Scope:** `velafleet-run` supervisor shim + JSONL event protocol + adapters for
    `amplifier-agent` and `shell`.
  - **Touches:** `fleet/run/`, `docs/fleet/JOB_EVENTS.md`.
  - **Done when:** a real `amplifier-agent` job launched inside a muxterm pane produces a
    complete, well-formed `events.jsonl` including at least one `progress` and one
    `attention`; the PTY simultaneously renders normal interactive output (this is Spike
    F-2's pass criterion, executed as a lane).
- **Lane F0.2 — `fleet-ssh-dispatch`**
  - **Scope:** `SshFleetPlane` behind the existing `FleetPlane` interface; JSONL tail over
    SSH; ledger PATCH from `vela-agentd`'s host.
  - **Touches:** `services/vela-agentd/.../fleet/`, `android/app/.../VelaAppContainer.kt`
    (one-line wiring).
  - **Done when:** a voice-initiated `dispatch_to_fleet` runs a real job on a real second
    machine; progress appears in the card deck; the job's completion updates the ledger.
    p99 dispatch measured and **reported honestly even if >1s** — F0 is not claiming D2.

### Stage F1 — The broker *(2 lanes · gated on F-1 and F-2 passing)*

- **Lane F1.1 — `fleetd-broker`** — registry, admission, ledger writing, decision relay,
  durable job→machine store, reconciliation. **Done when:** FG-1 through FG-4 pass (§11).
- **Lane F1.2 — `fleet-worker`** — dial-in worker, capability declaration, job supervision,
  muxterm pane hosting, JSONL tail, reconnect/replay. **Done when:** survives broker
  restart, worker restart, and a 24h idle session with zero lost events.

**Do not batch F1.1 and F1.2.** F1.2 consumes the protocol F1.1 defines — the same
sequencing exception the rebuild doc calls out for lanes 3.1/3.2.

### Stage F2 — Fan-out and limits *(1 lane · gated on F1)*

Parent/child trees, rollup rules, `strategy:"all"`, cancellation, limit enforcement in the
shim. **Done when:** a 3-machine fan-out produces exactly one card; a mid-flight decision on
the parent resolves; cancel reaps all three within 15s.

### Stage F3 — Fleet-shape response *(conditional · do not start blind)*

Only after Unresolved #1 is answered. Either "no change needed" or the `QueueTransport`
swap (§8.5). **This stage may correctly deliver nothing.**

### Rollback plan

| Stage | Rollback |
|---|---|
| F0 | Re-point `VelaAppContainer` at `StubFleetPlane`. One line. |
| F1 | Broker and workers stop; `SshFleetPlane` from F0 remains a working fallback. |
| F2 | Fan-out is additive; `strategy:"least_loaded"` paths are unaffected. |
| F3 | Transport swap is behind an interface; revert the implementation, keep everything else. |

---

## 11. Success Metrics

### 11.1 Binary correctness gates (any failure blocks the stage)

| # | Gate |
|---|---|
| **FG-1** | **D2 — `POST /fleet/dispatch` p99 < 1s** across ≥100 dispatches, measured over Tailscale, with the fleet at its real size. Includes the ledger create. |
| **FG-2** | **D3 — zero silent unreachable dispatches.** Adversarial test: kill a worker's process, `SIGSTOP` it, and sever its network, then dispatch to it. All three must produce a dispatch-time failure with a reason, within the heartbeat bound. A dispatch that returns `accepted` against a dead target is a gate failure. |
| **FG-3** | **D4 — zero lost job events** across worker restart, broker restart, and a 60s network partition. Injected event sequence of known cardinality, same method as rebuild doc G1. |
| **FG-4** | **D5 — attention round-trips end to end.** Job emits `attention` → notification fires → card decided → running job receives the decision and continues → `attention_cleared` lands in the ledger. Zero manual steps. |
| **FG-5** | **Zero pane-content reads in the worker.** Static check: the worker's muxterm client exposes no call to `run_command`, `get_screen`, or any scrollback accessor (FB4/FF-3). Enforced by lint, not review. |
| **FG-6** | **Zero provider credentials observable at the broker.** The broker process's environment, config, logs, and job specs contain no LLM API key (FB3). Verified by an adversarial grep over a captured broker state dump. |

### 11.2 Experience targets

| Metric | Target |
|---|---|
| Dispatch handle return (phone-observed, end to end) | **p99 < 1s** |
| Broker → worker job delivery | p99 < 100ms |
| Job `attention` → phone notification | **p99 < 60s** (inherits the rebuild doc's target) |
| Decision → running job unblocked | p99 < 10s |
| Worker session uptime | > 99% per machine per week, excluding machine downtime |
| Ledger writes per job | **< 30** for a typical hour-long job (coalescing is working) |

### 11.3 Product-truth signals

| Signal | What it tells you |
|---|---|
| **Human pane attaches per job** | Whether the muxterm integration earns its place (§7.4 item 5). Near zero → drop the pane, simplify the worker. High → prioritise §8.8's upstream ask. |
| **Fraction of dispatches that are fan-out (`strategy:"all"`)** | Whether "hyperscale fan-out" (R5) is a real behaviour or an aspiration. Near zero after a month means Stage F2 was built for a use case that does not exist — and that is worth knowing plainly. |
| **`cancel_requested_at` → `cancelled_at` gap** | Cost correctness. A widening gap means jobs keep burning tokens after the user believes they stopped (§5.5). |
| **Dispatch failures attributable to D3** | If near zero, either the fleet is genuinely always-on (answering half of Unresolved #1 empirically) or the heartbeat bound is too loose and FF-2 is hiding. Cross-check against jobs that stall at `running` and time out. |
| **Worker reconnect rate** | The FA1 falsification signal (§7.4 item 2). A machine reconnecting hourly is telling you the dial-in premise does not hold on its network. |
| **Jobs orphaned across broker restart** | Should be zero after reconciliation (FF-9). Any non-zero value means the durable job→machine store is not doing its job. |

---

## 12. Open Questions Returned to the Product Owner

Stated separately because these are **not** engineering decisions and this document
deliberately does not invent answers for them:

1. **Fleet size, homogeneity, and always-on-vs-intermittent** (rebuild doc §2.1 #1, still
   open). Blocks Stage F3, and determines whether §7.1 Option C is correct. **Ask before
   F1 ships, not after.**
2. **Strict vs. queued D3.** When a *known* machine is *currently offline*, should dispatch
   fail loudly in the conversation (strict D3, as written) or accept and queue until the
   machine returns? Strict is the current design. This is a product call about what the
   user should experience, and it changes the topology if the answer is "queue."
3. **Cost ceiling** (rebuild doc §2.1 #3, still open). The mechanism ships in §8.7; the
   number does not exist yet.
4. **What may a fleet job do without asking?** The rebuild doc built an approval gate (F2)
   for tools running inside `vela-agentd`. A fleet job runs an *entire agent* on a machine
   with real credentials and a real filesystem. That is a strictly larger authority, and it
   currently has **no gate at all**. Whether dispatch itself should require approval — and
   whether a fleet job should be able to push, deploy, or delete — is a policy question
   this design surfaces rather than answers. It is arguably the most consequential of the
   four.
