# Vela

> *From the Latin constellation — the sails of the great ship Argo. The sails don't contain the engine. They're what catches the wind and sets the course.*

A mobile-first AI orchestration hub for your personal Amplifier node network.

---

## The Core Idea

Vela runs a capable-enough local model (Gemma 4, ~2B–4B range) on Android — giving it genuine reasoning ability, memory, and planning capacity right on the device. But Vela's real power isn't what it knows. It's what it can **command**.

Distributed across your network are **Amplifier nodes**: purpose-built AI agents with specialized tools, heavier models, and deep capabilities. Vela knows they exist, knows what they can do, and knows how to put them to work. You talk to Vela. Vela orchestrates the army.

**Vela is the general. The nodes are the battalions.**

---

## Principles

1. **Offline-first, not offline-only** — Fully functional without a network. Research, planning, memory — all on device. When nodes come online, capabilities expand seamlessly.

2. **Capability is advertised, not assumed** — Nodes tell Vela what they can do. Discovery is the protocol. Stale capability data degrades gracefully.

3. **The phone does thinking. Nodes do doing.** — Vela's on-device model is optimized for orchestration, not execution. Heavy compute and tool-heavy operations belong on nodes.

4. **Local data stays local** — Your memory, plans, and conversation history never leave the device unless you explicitly route them to a node.

5. **The network is your network** — Nodes can be on your LAN, VPN, home server, cloud VM, or a trusted remote. The topology doesn't matter.

---

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                  VELA (Android)                         │
│                                                         │
│  ┌──────────────┐   ┌──────────────┐  ┌─────────────┐  │
│  │ Gemma 4 (2B) │   │ Local Memory │  │ Node Registry│  │
│  │ Orchestrator │   │ + Vector DB  │  │ (Capability  │  │
│  │ Planner      │   │              │  │  Map)        │  │
│  └──────┬───────┘   └──────────────┘  └──────┬──────┘  │
│         │                                     │         │
│         └──────────── Routes tasks ───────────┘         │
└─────────────────────────────┬───────────────────────────┘
                              │  (WiFi / LTE / VPN)
          ┌───────────────────┼────────────────────┐
          ▼                   ▼                    ▼
  ┌───────────────┐  ┌───────────────┐  ┌─────────────────┐
  │ Amplifier Node│  │ Amplifier Node│  │ Amplifier Node  │
  │ "Researcher"  │  │ "Code Runner" │  │ "Home Hub"      │
  │  web tools    │  │  bash tools   │  │  Home Assistant │
  └───────────────┘  └───────────────┘  └─────────────────┘
```

---

## Node Capability Discovery

Nodes publish a **capability manifest** — a structured advertisement of identity, model, tools, specializations, availability, and trust level.

Discovery is layered:
- **LAN**: mDNS broadcast (`_amplifier._tcp.local`) for zero-config local discovery
- **Trusted remote**: explicitly registered nodes with a lightweight heartbeat protocol
- **Cloud**: Amplifier nodes in VMs/containers that phone home to a known registry endpoint

Vela maintains a persistent capability map on-device. Offline nodes are marked stale but not deleted — Vela can still plan around them and queue tasks.

---

## What Vela Can Do Offline

- **Research** — RAG over a local knowledge index
- **Planning** — Task decomposition, project planning, scheduling
- **Memory** — Semantic memory via local vector store
- **Drafting** — Writing, thinking, exploring ideas
- **Task queuing** — Tasks queue and execute automatically when nodes reconnect

---

## Status

🌱 Early vision stage. Contributions and ideas welcome.

---

## Related

- [Amplifier](https://github.com/microsoft/amplifier) — The AI agent framework that powers Vela's node network
