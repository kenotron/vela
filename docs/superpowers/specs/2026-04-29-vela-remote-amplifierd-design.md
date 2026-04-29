# Vela × amplifierd: Remote Agent Architecture

**Date:** 2026-04-29
**Status:** Draft — awaiting review
**Scope:** Full redesign of Vela from on-device agent runner to voice terminal + remote node client

---

## 1. Problem Statement

Vela currently runs the entire agent loop on the Android device via a JNI Rust library. This is the wrong layer for agent work:

- Android kills background apps aggressively (Doze, battery optimisation)
- A phone is sleepier than a laptop — multi-step agent loops die mid-execution
- The phone cannot receive results while screen-off without a persistent process
- Tools available to the agent are limited to what runs on Android

The fix is the same one VS Code made with remote development: **the heavy work runs on a server, the device is a terminal**. The phone provides voice input, displays results, and contributes Android-specific capabilities (location, camera). The agent loop runs on `amplifierd` nodes that never sleep.

---

## 2. Architecture Overview

```
Vela (Android)
  │
  │  voice → Whisper (Azure OpenAI) → prompt text
  │  WebSocket ←→ active node
  │  Android tools → reverse calls from node
  │  FCM ← Azure Notification Hubs ← node push
  │  Vault on device, synced to GitHub (→ rclone)
  │
  └── connects to any node in the peer mesh
           │
  ┌────────┴──────────────────────────────────┐
  │                                           │
amplifierd-cloud                      amplifierd-mac
(Azure Container Apps)                (Tailscale)
always-on reliable peer               rich local tools
runs coordinator sessions             local fs, Python, SSH
clones vault from GitHub              owns local vault clone
  │                                           │
  └────────────── P2P mesh ──────────────────┘
         Tailscale tag:amplifierd
         + gossip on connect
         + mDNS on LAN
```

**Phone role:** voice in · events out · Android tools on demand · push notification receiver · one-time node configurator  
**Node role:** persistent compute · session store · tool execution · peer registry · coordinator

---

## 3. `amplifierd` Server

### 3.1 What Gets Built

`amplifierd` is a new binary assembled from existing Rust crates plus an axum HTTP layer:

| Existing crate | Role in amplifierd |
|---|---|
| `amplifier-module-orchestrator-loop-streaming` | `LoopOrchestrator` — the agent loop engine |
| `amplifier-module-session-store` | `FileSessionStore` — JSONL session persistence |
| `amplifier-android/src/agents.rs` | Agent registry (foundation agents + vault agents) |
| `amplifier-rust/` provider crates | Anthropic, Gemini, OpenAI, Ollama providers |
| **new** axum server | HTTP + WebSocket transport |

The sandbox CLI binary (`amplifier-android-sandbox`) is the starting point — add axum, expose the existing engine over the wire.

### 3.2 API Surface

```
# Node identity + discovery
GET  /capabilities          → { id, tools[], agents[], bundles[], version }
POST /nodes/register        → register as peer { id, address, capabilities, push_token?, ttl }
GET  /nodes                 → list known peers with status + capabilities

# Projects
POST /projects              → create { name, bundle, system_prompt_override? }
GET  /projects              → list projects
GET  /projects/:id          → project detail
PUT  /projects/:id/config   → push config from phone { bundle?, tools_enabled?, max_steps? }

# Sessions
POST /projects/:id/sessions → create session { type: regular|coordinator, instruction? }
GET  /projects/:id/sessions → list sessions with status
GET  /sessions/:id          → session detail + full event log
POST /sessions/:id/execute  → send a turn { prompt }
WS   /sessions/:id/stream   → bidirectional WebSocket (tokens, tool events, reverse tool calls)
POST /sessions/:id/resume   → resume a completed session with new instruction

# Push
POST /devices/register      → { device_id, push_token, platform: android }
```

### 3.3 WebSocket Protocol

The WebSocket on `/sessions/:id/stream` is bidirectional. Server-to-client events:

```jsonc
{ "type": "token",      "text": "The auth" }
{ "type": "tool_start", "id": "t1", "name": "bash", "args": {...} }
{ "type": "tool_end",   "id": "t1", "result": "..." }
{ "type": "delegate",   "id": "d1", "node": "mac", "session_id": "..." }  // coordinator only
{ "type": "done",       "turn_count": 3, "status": "success" }
{ "type": "needs_input","prompt": "Approve this action?" }                 // approval gate

// Reverse tool calls — node calls an Android tool on the phone:
{ "type": "tool_request", "id": "tr1", "name": "get_location", "args": {} }
```

Client-to-server events:

```jsonc
{ "type": "tool_result", "id": "tr1", "result": "{\"lat\":47.6,\"lng\":-122.3}" }
{ "type": "cancel" }
```

### 3.4 Session + Project Model

```
Node
  └── Projects
        ├── bundle: which Amplifier bundle is active
        ├── config: tool enables, max_steps, system prompt override
        └── Sessions
              ├── id, status: running | waiting | complete | error
              ├── type: regular | coordinator
              ├── created_at, updated_at, turn_count
              └── Events (JSONL, existing FileSessionStore format)
                    session_start, turn, tool_call, session_end
```

Projects map to real-world workspaces: personal assistant, work project, home automation. Sessions within a project share the project's bundle and config.

### 3.5 Coordinator Sessions

A coordinator session is a regular session whose agent has the **node graph injected as context** and whose `delegate` tool is network-aware. When the active bundle includes the coordinator agent, session creation sets `type: coordinator` and the node sends the peer graph as part of the system prompt.

Cross-node delegation tool call:

```jsonc
{
  "tool": "delegate_to_node",
  "args": {
    "node_id": "amplifierd-mac",
    "instruction": "Run the test suite and return results",
    "wait": true
  }
}
```

`amplifierd` dispatches the instruction to the target node (creates a session there, streams result back), then feeds the result into the local agent loop. The coordinator synthesises all sub-results.

Coordinator sessions should run on reliable always-on nodes (cloud node is the natural default). The phone is not a valid coordinator host — it sleeps.

---

## 4. Node Discovery (P2P)

Nodes form a peer mesh. No central registry. Three complementary mechanisms:

### 4.1 Tailscale Tag Query (primary)

Every `amplifierd` node is tagged `tag:amplifierd` on the shared Tailnet. On startup, each node queries:

```
GET https://api.tailscale.com/api/v2/tailnet/{tailnet}/devices
  → filter tag:amplifierd
  → build initial peer list
```

New node joins Tailnet with the tag → all other nodes discover it on next startup or heartbeat cycle. No manual peer config required for Tailscale-connected nodes.

**Requirement:** Each `amplifierd` node needs a Tailscale OAuth client credential (or personal access token) with `devices:read` scope to query the API. Stored as an environment variable / ACA secret.

### 4.2 mDNS (LAN fallback)

Service type `_amplifierd._tcp`. Nodes on the same network discover each other without Tailscale or any configuration.

### 4.3 Gossip on Connect (self-healing)

When any two nodes connect, they exchange their full peer tables:

```jsonc
// A → B
{ "peers": [{ "id": "mac", "address": "mac.tailnet.ts.net:7701", "capabilities": {...} }] }

// B → A
{ "peers": [{ "id": "work", "address": "work.tailnet.ts.net:7701", "capabilities": {...} }] }
```

After one exchange both know all reachable nodes. New node only needs one known peer to learn the rest. Heartbeats every 30s refresh capabilities and detect offline nodes.

### 4.4 Phone as Introducer (setup only)

When the user adds a new node in Vela, the phone:

1. Connects to the new node
2. Pushes the current peer list to it
3. Tells each existing node about the new peer

This is a 5-second one-time operation. After introduction the mesh is self-sustaining. The phone does not need to stay online for the peer graph to function.

### 4.5 Phone as a Node

The phone registers itself with the cloud node as a peer with special characteristics:

```jsonc
POST /nodes/register
{
  "id": "vela-{device-id}",
  "type": "android",
  "capabilities": {
    "tools": ["get_location", "capture_photo", "get_battery", "send_notification"]
  },
  "push_token": "fcm:APA91b...",
  "ttl": 300
}
```

- Phone re-registers on foreground (refreshes TTL)
- Node marks phone offline when TTL expires
- Coordinator sessions can call phone tools when online, skip gracefully when offline
- FCM token stored at registration — node can push to phone at any time

---

## 5. Azure Deployment

### 5.1 `amplifierd-cloud` on Azure Container Apps

`amplifierd-cloud` is the always-on reliable peer. It runs as an ACA containerised app:

- **Scale:** minimum 1 replica (never scales to zero — sessions must persist)
- **Storage:** Azure File Share mounted for `FileSessionStore` (sessions survive restarts)
- **Vault:** clones from GitHub at session start (stateless w.r.t. vault content)
- **Auth:** `x-amplifier-token` header, token stored in ACA secret

### 5.2 ACA Dynamic Sessions (sandboxed tool execution)

For `bash` and `CodeRunner` tool calls on the cloud node, use ACA Dynamic Sessions — Azure's managed isolated execution environments. Each tool execution runs in a fresh sandboxed container:

```
amplifierd-cloud receives bash tool call
  → POST {aca-dynamic-sessions-endpoint}/sessions:execute
  → isolated container runs the command
  → result returned to amplifierd
  → container discarded
```

This provides security isolation for arbitrary code execution without managing container lifecycle ourselves.

### 5.3 IaC Ownership

We own the full deployment. Bicep modules to author:

| Module | What it provisions |
|---|---|
| `container-app.bicep` | ACA environment, `amplifierd` app, ingress, secrets |
| `dynamic-sessions.bicep` | ACA Dynamic Sessions pool for bash/code execution |
| `notification-hub.bicep` | Azure Notification Hub namespace + hub |
| `openai.bicep` | Azure OpenAI deployment (Whisper model) |
| `storage.bicep` | Azure File Share for session store |
| `main.bicep` | Orchestrates all modules, outputs endpoints + tokens |

CI/CD: GitHub Actions deploys on push to `release/cloud`.

---

## 6. Vela Connection Layer

The seam is `AmplifierSession.runTurn()`. Everything above it (`InferenceEngine`, hooks, Room DB persistence) stays unchanged.

### 6.1 Replace JNI with WebSocket Client

```
Before:
  InferenceEngine.processTurn()
    → AmplifierSession.runTurn()   ← JNI call, blocks until done
        → nativeRun() in Rust .so

After:
  InferenceEngine.processTurn()
    → RemoteSession.runTurn()      ← WebSocket to amplifierd node
        → WS /sessions/:id/stream
        → maps server events to existing callbacks:
            token      → onToken(text)
            tool_start → onRustNativeStart(name, args)
            tool_end   → onRustNativeEnd(stableId, result)
            tool_req   → executeTool(name, args) [Android tools]
            done       → turn complete
```

`InferenceEngine` receives identical callbacks whether the loop ran on-device or on a node. Room DB persistence, hook pipeline, and streaming UI bubbles are unaffected.

### 6.2 Session Resume on Reconnect

```kotlin
// On foreground resume
sessionClient.reconnect(activeSessionId) {
    // subscribe to stream from last known event offset
    GET /sessions/:id?from_offset=47
}
```

If the session completed while the phone was off, the client fetches the full event log and reconstructs the UI. If the session is still running, it subscribes to the live stream.

### 6.3 Node Registry (Kotlin)

```kotlin
data class AmplifierdNode(
    val id: String,
    val address: String,          // Tailscale hostname or LAN IP
    val discoveryMethod: Discovery, // TAILSCALE | MDNS | MANUAL
    val capabilities: NodeCapabilities,
    val status: NodeStatus,       // ONLINE | OFFLINE | UNKNOWN
    val activeProjectId: String?
)
```

Stored in Room DB. Updated by mDNS listener, Tailscale query (periodic), and heartbeat responses.

---

## 7. Voice-First Interaction

Whisper is already wired in Vela (`TranscribeAudioTool` → `api.openai.com/v1/audio/transcriptions`). The change is promoting it from a tool to the **primary interaction primitive**.

### 7.1 Flow

```
User holds mic button (or wake word detected)
  → capture audio → stop on silence or button release
  → POST audio to Azure OpenAI Whisper endpoint
  → receive transcript text
  → send as prompt to active session on active node
  → subscribe to WebSocket stream
  → display streaming response + tool events
  → TTS speaks final response (Android TextToSpeech or Azure Speech)
```

### 7.2 Voice Command vs Voice Conversation

Two interaction modes in the same UI:

| Mode | Trigger | Behaviour |
|---|---|---|
| **Command** | Short utterance, imperative | Fire-and-forget. Phone can sleep. Push notification when done. |
| **Conversation** | Multi-turn exchange | Keep session warm, stream responses, TTS each turn. |

The distinction is detected by session type and duration — coordinator sessions and long-running tasks are commands; short exchanges are conversations.

---

## 8. Vault Strategy

### 8.1 Phone is Authoritative

Vault lives on the device at the path managed by `VaultManager`. `VaultGitSync` syncs to GitHub (moving to rclone later — same interface, drop-in replacement).

No architectural change to vault ownership. The phone remains source of truth.

### 8.2 Cloud Node Access

`amplifierd-cloud` clones the vault from GitHub at session start:

```bash
git clone https://{token}@github.com/{user}/{vault-repo}.git /sessions/{id}/vault
```

The clone is scoped to the session and discarded when the session ends. The node is stateless w.r.t. vault content.

For the Mac node: vault is already present (same machine). No clone needed.

### 8.3 rclone Migration

When rclone replaces GitHub as the sync mechanism, `VaultGitSync` is replaced by an `rclone` wrapper with the same interface. Cloud node clone uses `rclone copy` instead of `git clone`. No other changes.

---

## 9. Push Notifications

### 9.1 Flow

```
amplifierd detects: session complete | approval gate | error
  → lookup device push_token from node registry
  → POST to Azure Notification Hub
      { notification: { title: "Session complete", body: "PR review done" },
        tags: ["device:{id}"] }
  → Azure NH routes to FCM
  → FCM delivers to Android
  → Vela receives notification (even screen-off, app killed)
  → user taps → deep link to session → Vela reconnects, shows result
```

### 9.2 Notification Types

| Event | Title | Action on tap |
|---|---|---|
| Session complete | "Done: {project}" | Open session, show result |
| Approval gate | "{project} needs input" | Open session, show approval prompt |
| Session error | "Error in {project}" | Open session, show error detail |
| Coordinator sub-session complete | "{node}: sub-task done" | Open coordinator session |

### 9.3 Android Side

`VelaPushReceiver` (new) handles FCM messages. On tap, `NotificationDeepLinkActivity` routes to the relevant session in the session browser.

---

## 10. UX Retool

### 10.1 Navigation Hierarchy

```
Home (Node list)
  └── Node detail
        └── Projects list
              └── Project detail
                    └── Sessions list (status: running / waiting / done)
                          └── Session detail (chat history)
                                └── Tool call (expandable)
```

Home shows all nodes with live status. A node with a running session shows an activity indicator. Tap into any level.

### 10.2 Session List

Sessions have status chips. Running sessions show a live token counter. Waiting sessions (approval gate) show a highlighted "needs input" badge. Sessions persist — you can return to any completed session and resume it.

### 10.3 Coordinator Session View

Coordinator sessions get a specialised view — a live work graph rather than a linear chat:

```
┌─────────────────────────────────────────────────────┐
│  Coordinator: "Deploy auth service"          ● live │
├─────────────────────────────────────────────────────┤
│  ┌─ amplifierd-mac ──────────────────────┐          │
│  │  ✓ git clone                          │ tap →    │
│  │  ● running: build artifact            │ drill in │
│  └───────────────────────────────────────┘          │
│  ┌─ amplifierd-cloud ────────────────────┐          │
│  │  ○ waiting for mac artifact           │          │
│  └───────────────────────────────────────┘          │
│  ┌─ vela-android ────────────────────────┐          │
│  │  ✓ location provided (47.6, -122.3)   │          │
│  └───────────────────────────────────────┘          │
└─────────────────────────────────────────────────────┘
```

Tap any branch → drill into that sub-session's full chat history.

### 10.4 Voice Interaction

Persistent mic button anchored to the bottom of every screen. Hold to record, release to send. Visual waveform during capture. Whisper transcript shown briefly before it's sent. TTS response plays automatically.

### 10.5 Node Config Screen

Per-node configuration pushed from phone:

- Active bundle selector (shows bundles available on node via `/capabilities`)
- Tool enable/disable toggles
- Max steps slider
- System prompt override field
- "Connect" / "Disconnect" / "Forget"

### 10.6 What Goes Away

- The on-device agent loop (`AmplifierSession.nativeRun`) becomes optional / dev-only
- The "conversations" metaphor is replaced by "sessions"
- Any plans for a local on-device LLM path are shelved — Whisper via Azure OpenAI handles STT; all reasoning stays on nodes

---

## 11. Azure Services Map

| Service | Purpose | Tier |
|---|---|---|
| Azure Container Apps | Host `amplifierd-cloud` | Consumption + Dedicated |
| ACA Dynamic Sessions | Sandboxed bash/code execution | Consumption |
| Azure Notification Hubs | Push to Android via FCM | Basic |
| Azure OpenAI (Whisper) | Speech-to-text for voice input | Standard S0 |
| Azure File Share | Session store persistence for cloud node | LRS |
| Azure Container Registry | Store `amplifierd` Docker image | Basic |

All covered by MSFT Azure credits. Estimated idle cost: ~$15/month (cloud node minimum replica + storage).

---

## 12. Implementation Phases

### Phase 1 — `amplifierd` binary + Vela WebSocket client
- Build axum server around existing Rust crates
- Expose session create/execute/stream endpoints
- Replace `AmplifierSession.runTurn()` with `RemoteSession.runTurn()`
- Connect Vela to Mac node over LAN
- Verify `InferenceEngine` callbacks unchanged

### Phase 2 — Sessions browser + project model
- Add projects API to `amplifierd`
- Build Vela sessions browser UI (Node → Projects → Sessions → Chat history)
- Session resume on reconnect

### Phase 3 — P2P discovery + Tailscale
- Tailscale tag query on node startup
- mDNS service advertisement
- Gossip on connect
- Phone as node registration (Android tools + FCM token)

### Phase 4 — Azure deployment
- Bicep IaC (ACA, Dynamic Sessions, Notification Hub, OpenAI, Storage)
- `amplifierd-cloud` deploy
- Vault clone from GitHub on cloud sessions
- Azure OpenAI Whisper endpoint swap

### Phase 5 — Push notifications + voice-first UX
- Azure Notification Hubs integration in `amplifierd`
- FCM receiver in Vela
- Promote voice to primary interaction (always-visible mic button)
- TTS response playback

### Phase 6 — Coordinator sessions
- Node graph context injection
- `delegate_to_node` tool in `amplifierd`
- Coordinator session work graph UI in Vela

---

## Open Questions

1. **ACA Dynamic Sessions vs custom container lifecycle** — use Azure's managed feature for bash/code sandbox, or own the container lifecycle? Recommendation: use Azure's feature for v1, revisit if constraints emerge.
2. **Wake word** — always-on wake word (needs foreground service + wakelock) vs tap-to-talk. Tap-to-talk is simpler and more battery-friendly for v1.
3. **Vault clone scope** — full vault clone per session is simple but slow for large vaults. Consider sparse checkout or path-scoped clone.
4. **Android tool availability** — when phone goes offline mid-session, coordinator should defer Android tool calls rather than failing. Deferral mechanism TBD.
