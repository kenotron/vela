# Vela — Vision Document

**A mobile-first AI orchestration hub for your personal Amplifier network**

---

## The Problem It Solves

Today, AI assistants are either:
- **Too cloud-dependent** — useless on a plane, on a slow connection, or when the API is down
- **Too monolithic** — one model trying to do everything, poorly
- **Siloed** — each service is its own island; nothing orchestrates them

Vela solves this by separating **intelligence** (always local, always available) from **capability** (distributed, node-provided, discovered on demand). You get the offline resilience of a local model and the raw power of a fleet of specialized agents, without being forced to choose.

---

## Architecture

The Android app runs Gemma 4 in a small variant (~2B–4B parameters). This provides real reasoning ability, memory, and planning capacity entirely on-device. The on-device model is optimized for orchestration — decomposing intent, selecting nodes, routing tasks, synthesizing results. Heavy compute, long-running tasks, and tool-heavy operations belong on nodes.

Distributed across the network are **Amplifier nodes** — purpose-built AI agent instances with:
- Heavier models (GPT-4, Claude, Gemini, or larger local models)
- Specialized tool sets (web search, bash, filesystem, APIs, home control)
- Capability manifests that Vela discovers and caches

---

## Node Capability Discovery

Nodes publish a **capability manifest** containing:
- **Identity**: node name, version, location hint
- **Model**: LLM tier, context size, reasoning capability
- **Tools**: available tools (web search, bash, filesystem, APIs, etc.)
- **Specializations**: high-level tags (research, code, home-control, media, etc.)
- **Availability**: online/offline, latency hint, capacity signal
- **Trust level**: what Vela is allowed to delegate to this node

### Discovery Layers

- **LAN**: mDNS broadcast (`_amplifier._tcp.local`) — zero-config local discovery
- **Trusted remote**: explicitly registered nodes with lightweight heartbeat
- **Cloud**: Amplifier nodes that phone home to a registry endpoint

Vela maintains a persistent capability map on-device. Offline nodes are marked stale but not deleted — Vela plans around them and queues tasks that execute when nodes return.

---

## Offline-First Design

When disconnected, Vela handles:
- **Research** — RAG over a local knowledge index (seeded and maintained by Vela)
- **Planning** — Task decomposition, project planning, scheduling
- **Memory** — Semantic memory via local vector store ("what did we decide about X?")
- **Drafting** — Writing, thinking, exploring ideas
- **Task queuing** — "Send this to the research node when back on WiFi" — executes automatically on reconnect

---

## The Orchestration Role

When a request is complex or requires real capability:

1. Vela's local model **decomposes** the request into a workflow
2. It **selects** nodes based on the capability map
3. It **spawns** Amplifier sessions on those nodes — delegating each piece
4. It **monitors** session progress, handles failures, re-routes if a node drops
5. It **synthesizes** results back into a coherent response

This is the `delegate()` pattern from Amplifier, elevated to a cross-device, cross-network primitive. Vela is a meta-orchestrator: an orchestrator of orchestrators. Complex multi-node workflows run in the background — you ask, pocket your phone, and the answer is there when you look.

---

## Node Ecosystem (Evolving)

| Node Type | What it does |
|---|---|
| **Research node** | Heavy web research, document analysis, RAG over large corpora |
| **Code node** | Code execution, file operations, git, CI/CD |
| **Home node** | Home Assistant integration, lighting, climate, presence |
| **Media node** | Plex/Jellyfin control, transcoding, recommendations |
| **Calendar/comms node** | M365, calendar intelligence, smart drafting |
| **Archive node** | Long-term memory, vault management, document processing |
| **Compute node** | GPU-accelerated tasks, image gen, fine-tuning |

Every node is an Amplifier instance with a purpose-built bundle. Adding a new capability type means deploying a new node — no changes to Vela required.

---

## v1 Success Criteria

- Android app ships with Gemma 4 integrated, runs fully offline
- Local memory persists across sessions and is semantically searchable
- Node discovery works on LAN (mDNS) and for explicitly registered remote nodes
- Capability manifest protocol is defined and implemented
- Vela can spawn an Amplifier session on a remote node and get results back
- Basic task queuing — offline tasks execute when nodes become available
- At least one non-trivial multi-node workflow works end-to-end

---

## The Long Game

A personal computing mesh where every piece of infrastructure you own or trust is AI-accessible through one coherent interface that lives in your pocket.

Home automation. Personal research assistant. Code runner. Media brain. Health tracker. The nodes define the capability. Vela defines the experience.

The phone is not the limit. It's the constant.
