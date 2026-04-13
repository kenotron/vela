# Vela

> *From the Latin constellation — the sails of the great ship Argo. The sails don't contain the engine. They're what catches the wind and sets the course.*

A mobile-first AI orchestration hub for your personal Amplifier node network.

---

## The Core Idea

Vela is a mobile-first orchestration interface backed by a network of **Amplifier nodes** — each powered by Claude (Anthropic). Rather than squeezing a local inference model onto the phone, Vela delegates intelligence to purpose-built agents that have the tools, compute, and model capacity to act. Vela's real power isn't any single node. It's knowing which ones to call, when, and how to stitch their outputs into something coherent.

Distributed across your network are Amplifier nodes: specialized AI agents with deep capabilities and Claude under the hood. Vela knows they exist, knows what they can do, and knows how to put them to work. You talk to Vela. Vela orchestrates the army.

**Vela is the general. The nodes are the battalions.**

---

## Principles

1. **Offline-capable, not offline-intelligent** — Vela handles task queuing, conversation history, and UI with no network. Reasoning and generation require nodes. When nodes reconnect, queued tasks drain automatically and the experience is continuous.

2. **Capability is advertised, not assumed** — Nodes tell Vela what they can do. Discovery is the protocol. Stale capability data degrades gracefully.

3. **Amplifier does the thinking. Vela does the routing.** — Claude-powered Amplifier nodes handle reasoning, planning, and execution. Vela decides which nodes to engage and orchestrates their outputs into a coherent on-device experience.

4. **Local data stays local** — Your memory, plans, and conversation history never leave the device unless you explicitly route them to a node.

5. **The network is your network** — Nodes can be on your LAN, VPN, home server, cloud VM, or a trusted remote. The topology doesn't matter.

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

- **Conversation history** — Browse and reference past exchanges
- **Task queuing** — Tasks queue and execute automatically when nodes reconnect
- **Node registry browsing** — Review your fleet, capability maps, and connection status
- **UI and navigation** — Full app experience

What requires a connected node: reasoning, planning, drafting, memory queries, and any generative output (all powered by Claude on Amplifier nodes).

---

## Status

🌱 Early vision stage. Contributions and ideas welcome.

---

## Related

- [Amplifier](https://github.com/microsoft/amplifier) — The AI agent framework that powers Vela's node network
