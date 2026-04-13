# Vela — Vision Document

**A mobile-first AI orchestration hub for your personal Amplifier network**

---

## The Core Idea

Your phone is the most personal computer you own — but AI assistants still treat it as a dumb terminal, either routing everything to a cloud you don't control or running a local model too small to reason well. Vela takes a different path. Rather than compromising, it delegates intelligence to a network of **Amplifier nodes** — each powered by Claude (Anthropic) — while keeping orchestration and experience on your device where it belongs. Vela's power isn't what it knows. It's what it can **command**.

Distributed across your network are **Amplifier nodes**: purpose-built AI agents with specialized tools, heavier models, and deep capabilities. Vela knows they exist, knows what they can do, and knows how to put them to work. You talk to Vela. Vela orchestrates the army.

**Vela is the general. The nodes are the battalions.**

---

## The Problem It Solves

Today, AI assistants are either:

- **Too cloud-dependent** — useless on a plane, on a slow connection, or when the API is down
- **Too monolithic** — one model trying to do everything, poorly
- **Siloed** — each service is its own island; nothing orchestrates them

Vela solves this by separating **orchestration** (always on-device — task routing, history, queuing, and UI) from **intelligence and capability** (provided by Amplifier nodes running Claude). You get a coherent orchestration experience in your pocket and genuine reasoning power from nodes that can grow without Android constraints.

---

## Principles

**1. Offline-capable, not offline-intelligent**
Vela handles task queuing, conversation history, and UI with no network connection. Reasoning, planning, and generation require a connected Amplifier node. When nodes reconnect, queued tasks drain automatically. The transition is invisible to you.

**2. Capability is advertised, not assumed**
Nodes tell Vela what they can do. Vela never hardcodes a node's capabilities. Discovery is the protocol; capability maps are maintained locally and refreshed when connectivity allows. Stale capability data degrades gracefully — Vela routes around unavailable nodes.

**3. Amplifier does the thinking. Vela does the routing.**
Claude-powered Amplifier nodes handle intent extraction, planning, task decomposition, and execution. Vela's job is knowing which nodes to engage, routing tasks intelligently based on the capability map, and synthesizing results into a coherent on-device experience. The intelligence lives on the network. The orchestration lives on the phone.

**4. Local data stays local**
Your memory, your plans, your conversation history — none of it leaves the device unless you explicitly route it to a node. The node network is a resource pool, not a surveillance layer.

**5. The network is your network**
Nodes can be on your LAN, on a VPN, on a home server, in a cloud VM, or on a trusted remote machine. The topology doesn't matter. If Vela can reach it and the node advertises capabilities, it's in the fleet.

---

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                  VELA (Android)                         │
│                                                         │
│  ┌──────────────┐   ┌──────────────┐  ┌─────────────┐  │
│  │ Orchestration│   │ Local Storage│  │ Node Registry│  │
│  │ Client       │   │ + History    │  │ (Capability  │  │
│  │ Task Router  │   │              │  │  Map)        │  │
│  └──────┬───────┘   └──────────────┘  └──────┬──────┘  │
│         │                                     │         │
│         └──────────── Routes tasks ───────────┘         │
└─────────────────────────────┬───────────────────────────┘
                              │  (WiFi / LTE / VPN)
          ┌───────────────────┼────────────────────┐
          ▼                   ▼                    ▼
  ┌───────────────┐  ┌───────────────┐  ┌─────────────────┐
  │ Amplifier Node│  │ Amplifier Node│  │ Amplifier Node  │
  │               │  │               │  │                 │
  │ "Researcher"  │  │ "Code Runner" │  │ "Home Hub"      │
  │  GPT-4o       │  │  Claude 3.5   │  │  Gemini Flash   │
  │  web tools    │  │  bash tools   │  │  Home Assistant │
  │  search tools │  │  file tools   │  │  lighting / env │
  └───────────────┘  └───────────────┘  └─────────────────┘
```

---

## Node Capability Discovery

Nodes run an Amplifier instance that publishes a **capability manifest** — a structured advertisement of:

- **Identity**: node name, version, location hint (local/remote)
- **Model**: what LLM is running and roughly what it can do (reasoning tier, context size)
- **Tools**: what tools are available (web search, bash, filesystem, APIs, etc.)
- **Specializations**: high-level capability tags (research, code, home-control, media, etc.)
- **Availability**: online/offline, latency hint, capacity signal
- **Trust level**: what Vela is allowed to ask it to do

### Discovery Layers

Discovery uses a layered approach:

- **LAN**: mDNS broadcast (`_amplifier._tcp.local`) for zero-config local discovery
- **Trusted remote**: explicitly registered nodes with a lightweight heartbeat protocol
- **Cloud**: Amplifier nodes in VMs/containers that phone home to a known registry endpoint

Vela maintains a **persistent capability map** on-device. When a node goes offline, its capabilities are marked stale but not deleted — Vela can still plan for them and queue tasks that will execute when the node returns.

---

## What Vela Can Do Offline

Without connected nodes, Vela's intelligence is limited — there is no local model doing reasoning on-device. What Vela can do offline:

- **Conversation history** — Browse and reference past exchanges
- **Task queuing** — *"Send this to the research node when I'm back on WiFi"* — queued tasks execute automatically when connectivity returns
- **Node registry browsing** — Review your fleet, capability maps, and connection status
- **UI and navigation** — Full app experience

What requires a connected node (powered by Claude via Amplifier):

- Reasoning, planning, and intent extraction
- Research, drafting, and any generative output
- Memory queries that need semantic search

When nodes reconnect, queued tasks drain. Results sync. The experience is continuous.

---

## The Orchestration Role

This is Vela's identity. When a request is complex or requires real capability:

1. Vela **routes** the request to an appropriate Amplifier node, which uses Claude to decompose it into a workflow
2. It **selects** nodes based on the capability map (who has the right tools? who's available?)
3. It **spawns** Amplifier sessions on those nodes — delegating each piece
4. It **monitors** session progress, handles failures, re-routes if a node drops
5. It **synthesizes** results back into a coherent response on-device

This is exactly the `delegate()` pattern from Amplifier, elevated to a cross-device, cross-network primitive. Vela is a meta-orchestrator: **an orchestrator of orchestrators**.

Complex multi-node workflows can run in the background. You ask Vela something, put your phone in your pocket, and the answer is waiting when you next look.

---

## The Node Ecosystem (Evolving)

Nodes start simple and grow into whatever you need:

| Node Type | What it does |
|---|---|
| **Research node** | Heavy web research, document analysis, RAG over large corpora |
| **Code node** | Code execution, file operations, git, CI/CD integration |
| **Home node** | Home Assistant integration, lighting, climate, presence detection |
| **Media node** | Plex/Jellyfin control, transcoding, content recommendations |
| **Calendar/comms node** | M365, calendar intelligence, smart drafting |
| **Archive node** | Long-term memory, vault management, document processing |
| **Compute node** | GPU-accelerated tasks, fine-tuning, image gen |

Every node is just an Amplifier instance with a purpose-built bundle. Adding a new capability type means deploying a new node — no changes to Vela required.

---

## v1 Success Criteria

A minimal but complete first version:

- Android app ships as a working orchestration client — node discovery, task routing, and result delivery working end-to-end
- Local memory persists across sessions and is semantically searchable
- Node discovery works on LAN (mDNS) and for explicitly registered remote nodes
- Capability manifest protocol is defined and implemented
- Vela can spawn an Amplifier session on a remote node and get results back
- Basic task queuing — offline tasks execute when nodes become available
- At least one non-trivial multi-node workflow works end-to-end

---

## The Long Game

A personal computing mesh where **every piece of infrastructure you own or trust is AI-accessible** through one coherent interface that lives in your pocket.

Home automation. Personal research assistant. Code runner. Media brain. Health tracker. Financial analyzer. The nodes define the capability. Vela defines the experience.

The phone is not the limit. It's the constant.
