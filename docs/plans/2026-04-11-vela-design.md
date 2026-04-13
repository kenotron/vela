# Vela System Design

## Goal

Design the information architecture, orchestration model, and multi-user authorization plane for Vela — a mobile-first AI orchestration hub where each family member has a personal assistant on their phone that commands a shared network of distributed Amplifier nodes.

## Background

AI assistants today are either cloud-dependent, monolithic, or siloed. Vela separates orchestration (always on-device — routing, queuing, history, UI) from intelligence and capability (provided by Amplifier nodes running Claude on the user's network). Amplifier does the thinking. Vela does the routing.

But raw orchestration is not enough. A user directing a fleet of remote agents through a phone needs more than a chat interface. They need:

- **Voice as the primary input** — not typing on a small screen
- **An editorial layer** — AI that decides what to surface, when, and how, rather than dumping raw results
- **Provable delegation** — every task carries measurable acceptance criteria so nodes must prove completion
- **A living profile** — preferences that grow over time so the assistant learns what "good" means for each person
- **Multi-user authorization** — a family of five sharing hardware without an admin panel

This design addresses all five. It was developed around a concrete family of five with distinct needs, specific hardware, and real-world use cases.

**Vela is the general. The nodes are the battalions.**

## System Architecture

Vela is three layers: personal assistants, the node network, and the authorization fabric.

```
┌─────────────────────────────────────────────────────────────┐
│                    PERSONAL ASSISTANTS                       │
│                                                             │
│  ┌───────────┐ ┌───────────┐ ┌───────────┐ ┌───────────┐   │
│  │  Ken's    │ │  Wife's   │ │ Jethro's  │ │ Son's /   │   │
│  │  Vela     │ │  Vela     │ │ Vela      │ │ Daughter's│   │
│  │  (root)   │ │           │ │           │ │ Vela      │   │
│  └─────┬─────┘ └─────┬─────┘ └─────┬─────┘ └─────┬─────┘   │
│        │              │              │              │        │
│  On-device: Vela client, profile, history, job registry     │
└────────┼──────────────┼──────────────┼──────────────┼────────┘
         │              │              │              │
         └──────────┬───┘──────────────┘──────────┬───┘
                    │   WiFi / LTE / VPN          │
         ┌──────────┴─────────────────────────────┴───────────┐
         │              AUTHORIZATION FABRIC                   │
         │  Cryptographic identity per orchestrator            │
         │  Capability tokens, ACLs, preference enforcement    │
         └──────────┬─────────────────┬───────────────────────┘
                    │                 │
    ┌───────────────┼─────────────────┼──────────────────┐
    │               NODE NETWORK                         │
    │                                                    │
    │  ┌──────────────┐  ┌─────────────┐  ┌───────────┐ │
    │  │ M4 Max       │  │ Server A    │  │ Server B  │ │
    │  │ voice-gen    │  │ compute     │  │ storage   │ │
    │  │ inference    │  │ pipelines   │  │ data      │ │
    │  │ (Ken prio)   │  │ amplifier   │  │ amplifier │ │
    │  └──────────────┘  └─────────────┘  └───────────┘ │
    │                                                    │
    │  Nodes advertise capabilities via manifest         │
    │  Nodes don't know WHO you are — only WHICH         │
    │  orchestrators are authorized to talk to them      │
    └────────────────────────────────────────────────────┘
```

### Layer 1 — Personal Assistants

Each family member runs a Vela instance on their phone. Each instance is named, personalized, and independent. It carries:

- **Amplifier node connections** — intent extraction, planning, rubric compilation, and output synthesis are handled by Claude on Amplifier nodes; the phone is the orchestration client, not the reasoning engine
- **Private history** — conversation history and task records, stored on-device, never shared
- **A user profile** — preferences, quality standards, correction history (see Section 5)
- **A job registry** — persistent record of every delegated task (see Section 3)
- **A capability map** — which nodes this instance is authorized to use and what they can do

When nodes are unreachable, the assistant can queue tasks, browse history, and navigate the app — but reasoning and generation require a connected node running Claude.

### Layer 2 — The Node Network

Hardware on the family's network runs Amplifier instances that publish capability manifests. Nodes are stateless servants — they accept authorized work, execute it, and push results back. They maintain no concept of "user," only a list of orchestrator identities that are allowed to talk to them.

Discovery uses the existing layered approach from the VISION: mDNS on LAN, explicit registration for remote nodes, heartbeat for availability. Each node's capability manifest includes:

- Identity, version, location hint
- Model tier and context size
- Available tools and specializations
- Availability and capacity signal
- **Execution mode** — one of three values: background-ok (accepts background work even during an active human session), background-when-idle (accepts background work only when no active session is running), or owner-only (only accepts work from the owner orchestrator, no sharing). The M4 Max is configured as background-when-idle by default. Servers are background-ok.

### Layer 3 — The Authorization Fabric

The glue between assistants and nodes. Each Vela instance has a cryptographic identity. Nodes maintain ACLs of trusted orchestrators. Ken holds root and manages access conversationally — no admin panel. Full specification in Section 6.

## A2UI Protocol

A2UI (Agent-to-User Interface) is the typed event stream flowing from nodes back to each personal assistant. It is the nervous system of the entire architecture — the mechanism that transforms "something is happening on a remote machine" into structured events that Vela's orchestration layer can reason about and present.

### Core Event Types

| Event | Payload | Meaning |
|---|---|---|
| `task.started` | `{ task_id, node_id, capability, timestamp }` | Node acknowledged the delegation, work began |
| `task.progress` | `{ task_id, detail?, percent?, status }` | Heartbeat — optional human-readable detail, progress percentage, current step |
| `task.assertion` | `{ task_id, rubric_item_id, passed, evidence }` | Node claims it satisfied a specific rubric criterion, with attached evidence |
| `task.artifact` | `{ task_id, type, ref, size?, preview? }` | A produced file — the `type` field drives presentation decisions |
| `task.complete` | `{ task_id, summary, evidence_bundle, assertions[] }` | Terminal success — summary plus full evidence for rubric evaluation |
| `task.failed` | `{ task_id, reason, partial_evidence?, retry_hint? }` | Terminal failure — what went wrong, any partial results, whether retry is sensible |

### Evidence Type Tags

Artifact and assertion evidence carries a type tag that the output synthesis layer uses to decide presentation:

- `screenshot` — visual capture of a screen or application state
- `log-excerpt` — relevant lines from execution logs
- `file-ref` — reference to a produced file (path, URI, or blob)
- `audio-clip` — generated or captured audio
- `image` — a produced image (illustration, chart, render)
- `text-summary` — structured or prose text output

### Transport

Events are pushed over a persistent connection from node to orchestrator. If the orchestrator is unreachable (phone offline, network drop), events queue locally on the node and drain when the connection restores. No polling. No missed updates. The protocol is ordered and idempotent — events carry sequence numbers so the orchestrator can detect gaps and request replay.

### Personalized Presentation

The raw event stream is the same regardless of which orchestrator receives it. The editorial decision — what to surface, how, and when — happens on the phone, informed by the user's profile. Ken gets failure details and log evidence. His wife gets a two-sentence audio summary. The middle son gets a silent notification if everything passed, a spoken alert only on failure.

## Task Console

Every delegated task gets a persistent entry in a job registry — an ID, a status, a rubric, and a full event history. The registry is stored on-device and survives app restarts, phone reboots, and network interruptions. Vela never loses track of work.

### Task Lifecycle

```
                    ┌──────────┐
                    │  queued  │
                    └────┬─────┘
                         │
                    ┌────▼─────┐     ┌──────────┐
                    │ running  ├────►│ retrying │
                    └──┬───┬───┘     └────┬─────┘
                       │   │              │
              ┌────────┘   └────────┐     │
              ▼                     ▼     │
        ┌──────────┐         ┌──────────┐ │
        │ complete │         │  failed  │◄┘
        └──────────┘         └──────────┘

    Special states:
    ┌──────────┐  Waiting on another task's output
    │ blocked  │  (dependency in a multi-hop pipeline)
    └──────────┘

    ┌──────────┐  Node went offline mid-execution
    │  stale   │  (job preserved, resumes when node returns)
    └──────────┘
```

### Progressive Disclosure

Information is layered so the user controls how deep they go:

1. **Surface** — single-line status: *"Research task on the data node — running, 4 minutes in."*
2. **Breakdown** — step-by-step record of what the node has done so far, derived from `task.progress` events
3. **Detail** — raw A2UI events, individual assertion results against the rubric, artifact references with previews

The detail is always available. It is never forced. Vela presents the surface by default and lets the user drill in.

### Proactive Highlights

Vela (via a connected Amplifier node using Claude) watches the event stream continuously and surfaces anomalies without being asked:

- An assertion failure that didn't stop the task (partial rubric miss)
- A node running significantly longer than its capability hint suggested
- An artifact that looks structurally different from what prior runs of the same task type produced
- A cascading failure when one node's result fed another and the downstream task failed

These surface as proactive notes — spoken or a brief visual flag — not buried in a log the user would have to hunt for.

### Queue Introspection

At any point, the user can ask "What's happening right now?" Vela synthesizes the current queue state — active jobs, queued jobs, anything flagged — into a spoken summary or a scannable visual list. History is preserved so past tasks can be re-examined: what ran last Tuesday, what it produced, whether it passed its rubric.

## Intent Gate and Rubric Compiler

This is the intelligence layer between voice input and any delegation. Nothing leaves Vela until this pipeline completes. The design is deliberately pessimistic about delegation — it is cheaper to ask one clarifying question than to clean up a misrouted task across remote nodes.

### The Five-Step Pipeline

**Step 1 — Intent Extraction.** The voice input is routed to an Amplifier node where Claude extracts structured intent: action, target capability, constraints, desired outcome. This is semantic parsing, not transcription — it identifies what the user wants accomplished, not just what words they said.

**Step 2 — Clarity Gate.** Before compiling a rubric, Vela asks itself: *can I write verifiable assertions for this?* If not, the intent is not clear enough. The threshold is not "did I hear you correctly" but "do I know enough to prove success." A rubric that cannot be compiled is a signal to stop and ask.

**Step 3 — Clarification Loop.** If the gate fails, Vela asks one targeted question — the single piece of information it most needs to proceed. Not a form. Not a checklist. One question. This loop continues until the rubric can be compiled. Over time, as the user profile matures, this loop fires less often because Vela already knows the user's standards.

**Step 4 — Profile Consultation.** Once intent is clear, Vela pulls relevant preferences from the user's profile and folds them into the rubric automatically. A preference for machine-parseable output becomes an assertion. Jethro's creative voice preference becomes a style constraint. These travel with the delegation without the user re-stating them every time.

**Step 5 — Rubric Compilation.** The output is a set of verifiable assertions. Not "do a good job" but concrete, checkable criteria:

- *"Email summaries returned as structured JSON"*
- *"Artifact produced at path X with resolution above 1024px"*
- *"Test suite passes with zero failures"*
- *"Research output includes at least three cited sources"*

Every assertion is evaluable. The node knows exactly what passing looks like. The rubric is the formalized intent — they are the same thing.

### Judgment After Evidence

When a node sends its `task.complete` event with an evidence bundle, Vela evaluates each assertion in the rubric against the provided evidence:

Vela evaluates evidence independently — via a dedicated Amplifier node running Claude — rather than simply accepting node self-assessments. A node's task.assertion events are claims with attached evidence, not verdicts. For each assertion, Vela reads the evidence payload and reaches its own conclusion against the rubric criterion. The three-outcome decision below applies to Vela's independent evaluation, not the node's claim.

- **All assertions pass** — task is complete. Output synthesis decides what to surface.
- **Minor failure, easy correction** — Vela re-delegates with corrective context. The user profile's correction history informs this threshold. If the user previously said "just retry once" for this failure type, Vela does so silently.
- **Ambiguous or hard failure** — Vela escalates to the user with enough context to make a decision. It never silently swallows a failure it cannot confidently repair.

This judgment loop is what makes the rubric a living contract rather than a fire-and-forget instruction.

## User Profile

The profile is what turns Vela from a smart dispatcher into a genuine assistant. It is a living model of the user — their standards, preferences, working patterns, and accumulated corrections — built quietly over time from every interaction.

### What It Stores

| Category | Examples |
|---|---|
| **Quality standards** | *"Machine-parseable output preferred," "always cite sources on research tasks"* |
| **Domain preferences** | *"For writing, match the established tone of the project," "prefer concise summaries over exhaustive detail"* |
| **Working patterns** | *"Time-sensitive tasks should be expedited without asking," "batch non-urgent completions into a single roll-up"* |
| **Boundaries** | *"Never use AI-generated creative text for Jethro's TTRPG work unless he asks," "middle son: no AI creative work"* |
| **Correction history** | *"Previously escalated this failure type — user said just retry once," "user prefers log excerpts over full logs"* |

### How It Is Built

**Passive accumulation.** Every resolved task leaves a signal. What was requested, what rubric was compiled, how evidence was evaluated, what judgment Vela made, whether the user overrode it. Each interaction is a data point that adjusts the profile's model of the user.

**Active correction.** When the user corrects Vela — "that's not what I meant," "this is fine, don't escalate next time," "I prefer it this way" — the profile updates immediately and with high confidence. Active corrections carry more weight than passive signals.

### How It Is Used

- **At rubric compilation** — preferences fold into assertions automatically. The user's standards travel with every delegation without re-stating them.
- **At judgment time** — correction history informs the retry-vs-escalate decision. Vela learns which failures the user considers trivial and which demand attention.
- **At delegation time** — relevant profile context can travel with the task spec, giving nodes richer direction than a bare task description.
- **At output synthesis** — the profile determines how results are presented: verbose or concise, audio or visual, confirmation or silence.

### Privacy Model

On-device. The profile never leaves the phone unless the user explicitly routes it. Each family member's profile belongs to their Vela instance alone — not shared, not visible to other orchestrators, not aggregated. Ken's preferences do not leak to Jethro's Vela. The profile is private memory.

### The Long Game

Early Vela asks questions. Mature Vela does not. The profile makes the intent gate cheaper over time because the user's standards are already compiled into it. A Vela that has been used for six months should rarely need the clarification loop for routine requests — it already knows what "good" means for this user.

## Authorization Plane

Each Vela instance has a cryptographic identity — a keypair generated on first launch, never transmitted off-device. Nodes do not trust "a Vela." They trust a specific named orchestrator whose public key they have been given. Ken holds root.

### The Invitation Model

Access is managed conversationally. No admin panel, no configuration files, no web UI.

**Granting access:**
Ken tells his Vela: *"Let Jethro's Vela use the writing node for his TTRPG project."*

1. Vela generates a capability token — scoped to that node, that capability, signed by Ken's identity
2. Jethro's phone receives the token (QR code scan, AirDrop, shared link, or similar local transfer)
3. Jethro's Vela can now authenticate to the writing node for the scoped capability
4. The node's ACL is updated to recognize Jethro's orchestrator identity

**Revoking access:**
Ken says: *"Remove Jethro's access to the writing node."* The token is invalidated. The node's ACL is updated. Jethro's next request is rejected.

### Capability Scoping

Tokens do not grant access to a node. They grant access to a specific capability on a node. This distinction matters:

- Jethro can use the writing capability on the M4 Max. He cannot invoke voice generation, see Ken's task history, or access other capabilities on the same hardware.
- The middle son can request image generation on the hardware node. He cannot see what Ken has generated or preempt Ken's queue position.
- The youngest daughter can access art tools on the server. She cannot access the data node's email pipeline.

Scoping is per-capability, per-node, per-orchestrator. The granularity is fine enough for a family without being burdensome to manage because the conversational interface absorbs the complexity.

### Preference Enforcement at the Node Level

When the middle son's Vela delegates to a node, the node checks its ACL entry for his orchestrator token and finds a policy tag: `creative-ai: blocked`. The node enforces this regardless of what the task spec says. His boundary against AI creative work is not a prompt instruction that can be overridden — it is a node-side policy tied to his cryptographic identity.

This pattern generalizes. Any boundary that a family member wants enforced can be encoded in the node's ACL for their identity. The node enforces it. The orchestrator respects it. The user never has to worry about it being accidentally bypassed.

### Priority and Contention

Hardware is shared. Priority rules determine who goes first:

- **M4 Max** — Ken's tasks jump to the front of the queue when he begins active use — but running jobs always complete before his task starts. No job is ever interrupted mid-execution. When Ken is not actively using the machine, the queue is fair.
- **Servers** — fair queue for all authorized orchestrators
- **All nodes** — running jobs are never interrupted; new requests queue behind whatever is executing

Priority preferences are configured conversationally by Ken and encoded in each node's ACL per orchestrator identity. No runtime negotiation between orchestrators.

### Family Scale

This authorization model is intentionally simple. It is designed for approximately five people, not enterprise RBAC. There are no roles, no groups, no inheritance hierarchies. The model is: which orchestrators can talk to this node, what can they request, what policies apply, and in what priority order. That is the entire authorization surface.

## Output Synthesis

Every completed task — every incoming A2UI terminal event — passes through an editorial layer before it reaches the user. Vela (via Claude on an Amplifier node) makes a decision: what is the right way to surface this result, if at all?

### Four Output Modes

**Audio.** A spoken sentence or two. Best for summaries, completions, and soft failures. *"Your email pipeline finished. Twelve messages summarized, one flagged for follow-up."*

**Visual artifact.** An image, screenshot, or rendered file displayed directly on the phone. Best when the result is a visual — an image the GPU node produced, a dashboard screenshot, a chart. The artifact is presented, not described.

**Status signal.** A brief glanceable card: task name, pass/fail, key metric. No voice, no detail. For clean completions where the user just wants to know it is done.

**Silence.** Nothing. The task completed cleanly, the rubric passed, nothing was anomalous. Silence is a valid, intentional output mode. A mature profile that says "don't interrupt for routine completions" will produce silence for most successful tasks.

### The Editorial Algorithm

Claude (via an Amplifier node) applies this decision sequence for every terminal event:

1. Did the rubric fail? → **Audio alert** with enough context to understand the failure
2. Is the result a visual artifact? → **Show it** directly
3. Did something anomalous appear in the event stream? → **Surface it** with context explaining why it is interesting
4. Did the task complete cleanly? → **Check the preference model**
   - User wants confirmation → status signal or brief audio
   - User does not want interruption → silence
5. Never interrupt for a clean completion if the profile says not to

### Multi-Task Roll-Ups

When several nodes complete in a window, Vela does not fire five separate notifications. Claude synthesizes across them into a single roll-up: *"Three background tasks finished while you were away — all passed. One thing worth knowing: the data node took twice as long as usual."*

The roll-up is editorially compressed. Details are in the Task Console for anyone who wants to drill in.

### Voice Input Model

Voice is the primary input modality for Vela on Android. The interaction model is a simple toggle: **tap to start capture, tap again to end**. This is the canonical entry point for all interaction — low friction, no typing, works while the phone is in the user's hand or on a surface nearby.

## Family Personas

The authorization model and profile system were designed around this specific family. Each member has distinct needs that stress-test the architecture.

### Ken (Owner, Root)

Software engineer. Full orchestration stack. Primary use: directing agents on remote machines via voice, managing multi-node pipelines, remote machine control even when the target is in active use. His profile will be the most complex — deep quality standards, strong opinions on output format, expects machine-parseable results and cited sources. Holds root authority over all nodes. His work has priority on the M4 Max.

### Wife (Director, Homeschool Co-op, Board Communications)

Scheduling, communication, and coordination. Her Vela manages email summaries, calendar intelligence, and communication drafts. Does not need raw technical output — her profile will emphasize concise audio summaries and minimal interruption for clean completions. Needs reliable access to the email pipeline node and calendar capabilities.

### Jethro (19, Oldest Son)

Writer and TTRPG worldbuilder, starting a business around his Dungeons & Dragons world and a companion novel. Needs a creative assistant — but one that respects his voice. His profile boundary: AI assists with research, organization, and worldbuilding scaffolding, but creative text is his domain unless he explicitly asks for generation. Needs access to the writing node and potentially data storage for his world bible.

### Middle Son (Audio/Video Production)

Talented in audio and video production. Sensitive to AI doing creative work — his boundary is the hardest constraint in the system. His Vela is primarily a time management and scheduling assistant, not a creative tool. The `creative-ai: blocked` policy is enforced at the node level for his orchestrator identity. If he later relaxes this boundary, it is a conversational change through Ken or his own Vela — not a system migration.

### Youngest Daughter (Digital Artist)

Digital artist and animal lover, currently saving for a Golden Retriever. Needs access to art tools on the network (reference image search, palette generation, asset organization) and financial goal tracking for her savings goal. Her profile may eventually include a similar creative boundary to her brother's if she prefers AI to assist with organization rather than generation — but that is her choice to make, not a default.

## Hardware Inventory

### MacBook Pro M4 Max

- **Role:** Premium inference and voice generation node
- **Capabilities:** High-quality voice synthesis, large model hosting (Q&A models, reasoning), heavy inference tasks
- **Constraint:** Ken's primary work machine — cannot be dedicated to node work full-time
- **Execution mode:** Accepts background work from family orchestrators when idle. Ken's foreground work has absolute priority. When Ken is actively using it, family requests queue until it is available.
- **Authorization:** All family members can be granted access to specific capabilities. Ken controls which capabilities are shared.

### Server A and Server B

- **Role:** Headless compute, data storage, always-on pipeline execution
- **Hardware:** Good CPUs, no high-end GPUs, ample storage
- **Capabilities:** CPU-heavy tasks, Amplifier hosting, email pipelines, data processing, file storage, long-running background jobs
- **Execution mode:** Always available. Fair queue for all authorized orchestrators. No human session to contend with.
- **Future:** Can host OpenCL or similar compute services that an Amplifier node exposes as a capability. This is likely where Vela's always-on background services live — persistent pipelines that monitor, summarize, and prepare data without being asked.

### Node Capability Summary

| Node | Primary Capabilities | Execution Mode | Priority Model |
|---|---|---|---|
| M4 Max | Voice gen, heavy inference, Q&A | Background when idle | Ken preempts |
| Server A | Compute, pipelines, Amplifier hosting | Always-on headless | Fair queue |
| Server B | Storage, data, Amplifier hosting | Always-on headless | Fair queue |

## Case Studies

### CS1 — Remote Machine Control

Ken directs agents on a single machine via voice from his phone, even when the machine is in active use by someone else or by himself.

**Flow:**
1. Voice input → intent gate extracts the command and target machine
2. Rubric compiled: command executed, expected output returned, no errors
3. Delegation sent to the target node with execution mode `background`
4. Node checks its execution mode field — if it accepts background work while in active use, it proceeds; otherwise, the task queues
5. A2UI pushes `task.started`, `task.progress`, and `task.complete` events back to Ken's Vela
6. Output synthesis delivers the result as audio or a status signal

**Gap identified:** The capability manifest must include an execution mode field so Vela knows whether a node accepts background work during active use. This is addressed in the node manifest specification above.

### CS2 — Headless Data Pipeline

A server node sits headless, retrieves Ken's email, summarizes it, and hands the summary off to another node for further processing. The final result is delivered to Ken's Vela.

**Flow:**
1. Pipeline is triggered by voice — the user asks Vela to run the email summary
2. Server A retrieves email and produces structured summaries (rubric: JSON format, all messages processed)
3. Summary is handed to Server B for additional processing — cross-referencing with calendar, flagging action items
4. Each hop has its own rubric, produces its own evidence, and pushes A2UI events back to Ken's Vela
5. Final result: Gemma 4 synthesizes the pipeline output into a spoken summary or a visual card

Multi-hop delegation is sequential — Vela orchestrates each handoff. Hardware nodes that produce artifacts (images, audio) fit this pattern naturally: the artifact they produce is the evidence returned via A2UI.

### CS3 — Shared Resource Access (Son's Vela Using Ken's Node)

Jethro's Vela needs to access a data node that belongs to Ken — it has research material for his TTRPG worldbuilding project.

**Flow:**
1. Ken previously said: *"Let Jethro's Vela use the data node for reading research materials"*
2. Jethro's Vela holds a scoped capability token: read access to the data node's research capability
3. Jethro asks his Vela to find references for a particular aspect of his world
4. His Vela's intent gate compiles a rubric, delegates to Ken's data node
5. The data node checks Jethro's orchestrator identity against its ACL — authorized for read, not write
6. Results flow back to Jethro's Vela via A2UI, not Ken's
7. Ken is never interrupted. His Vela is unaware this happened.

## Open Questions

1. **A2UI transport protocol.** The design specifies push-based persistent connections with buffering. The specific wire protocol (WebSocket, gRPC streaming, custom TCP) is not yet chosen. The choice should optimize for battery life on Android and reliability over intermittent connections.

2. **Profile portability.** If a family member gets a new phone, how does the profile migrate? On-device-only storage means a backup/restore mechanism is needed. Encrypted local backup to a trusted node is one option.

3. ~~**Rubric expressiveness vs. Gemma 4 capability.**~~ **Resolved — no longer applicable.** Rubric compilation is now performed by Claude (via Anthropic) on Amplifier nodes. Claude's capability at rubric compilation is not a limiting factor; complex multi-criteria rubrics are well within its capability. The 2B–4B on-device model constraint no longer applies.

4. **Node-to-node delegation.** The current design has Vela orchestrating every hop in a multi-node pipeline. For latency-sensitive chains, direct node-to-node handoff (with Vela informed via A2UI) may be more efficient. This adds complexity to the authorization model — nodes would need to trust each other, not just orchestrators.

5. **Scheduled and recurring tasks.** On-demand voice-triggered pipelines are in scope for this design (e.g., "run my email summary now"). Recurring/scheduled tasks (daily email summary at 7 AM, weekly report every Monday) are deferred — they need a scheduler component likely on-device, with the job registry managing the cadence and the intent gate pre-compiling rubrics for known recurring patterns. That scheduler is a future addition, not part of this initial design.

6. **Conflict resolution for shared nodes.** The priority model handles contention (Ken preempts, servers use fair queue). But what happens when two family members' tasks conflict on a data level — e.g., both writing to the same storage location? The authorization model scopes capabilities but does not yet address data-level conflicts.

7. **Middle son's boundary evolution.** The `creative-ai: blocked` policy is enforced at the node level. If middle son later wants partial relaxation (AI assists with audio mixing but not composition), the current policy tag is binary. A richer policy vocabulary may be needed — but should only be added when a real use case demands it.

8. ~~Execution mode advertising.~~ Resolved: three execution modes defined in the node capability manifest schema (background-ok, background-when-idle, owner-only). Semantic edge cases (e.g., owner-only nodes in multi-tenant family scenarios) may need refinement during implementation.
