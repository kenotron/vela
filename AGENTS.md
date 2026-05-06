# Vela — Agent Context

> **Read this first before making any changes.**
> This file is the source of truth for any AI agent working on this codebase.
> **EVIDENCE RULE: Don't invent API shapes, event names, or status values. Verify with curl or logcat first.**

---

## What Vela Is

Vela is a **mobile-first AI orchestration hub** (Android app). The phone is the UI and controller. Intelligence runs on remote amplifierd nodes (SSH-accessible machines). The app talks to amplifierd via HTTP + SSE.

---

## Current Architecture (as of 2026-05-04)

```
Phone (Android)                           Mac / Remote Node
┌─────────────────────────────┐           ┌──────────────────────────────┐
│ Vela App                    │           │ amplifierd daemon             │
│                             │  HTTP     │  port 8410                   │
│ SessionDetailViewModel  ────┼──────────►│  /sessions  (CRUD)           │
│   ├─ sendMessage()          │  SSE      │  /events?session=ID (stream) │
│   └─ AmplifierdStreamClient─┼──────────►│  /projects  (vela plugin)    │
│                             │           │  /capabilities               │
│ SshNodeRegistry (@Singleton)│           │                              │
│   └─ cache: List<SshNode>   │           │  Uses Anthropic API          │
│      url, token, workspaceDir           │  Key: from launchd plist env │
└─────────────────────────────┘           └──────────────────────────────┘
```

---

## Proven amplifierd SSE Event Vocabulary

**Verified by live curl on 2026-05-01 against `http://10.0.0.143:8410`**

### Protocol (order matters)
1. Open `GET /events?session={sessionId}` SSE stream **FIRST** — server replays from seq 1
2. Then `POST /sessions/{id}/execute/stream` with `{"prompt": "..."}` — returns `{"correlation_id":"...","status":"accepted"}`
3. Collect events from GET /events until `execution:end` or `orchestrator:complete`

### Event sequence (successful turn — loop-vela bundle)
```
id: 1  event: session:start      # lightweight, just session_id + timestamp
id: 2  event: session:start      # full config dump (agents, hooks, providers, tools)
id: 3  event: prompt:submit      # data.prompt = the submitted text
id: 4  event: execution:start    # data.prompt = the submitted text
id: 5  event: provider:request   # data.provider = "anthropic", data.iteration = 1
id: 6  event: llm:request        # data.model, data.thinking_enabled, data.message_count
id: 7  event: llm:response       # data.duration_ms, data.status = "ok", data.usage
id: 8  event: content_block:start  # data.block_index = 0, data.block_type = "text"
id:8a  event: content_block:delta  # *** loop-vela ONLY *** data.token = "word", data.block_index = 0
                                   # REAL tokens from Anthropic SDK streaming via VelaAnthropicProvider
                                   # arrives BEFORE content_block:end (correct order)
id: 9  event: content_block:end    # data.block = {"text": "...", "type": "text"} (full final text, authoritative)
id:10  event: execution:end
id:11  event: orchestrator:complete  ← DONE signal; data.orchestrator = "loop-vela"
```

### On provider failure (retries)
```
id: 7  event: provider:retry   # data.attempt, data.max_retries, data.error_message, data.delay
                               # (repeated up to max_retries times, then execution:end with error)
```

---

## ⚠️ Tool Call Format — Two Layers, Different Field Names

**This divergence caused a real bug (2026-05-06): `SessionTranscriptNormalizer` was written
reading the SSE format but consuming the transcript API — tool blocks silently disappeared
post-stream. Add this table to your mental model before touching ANY tool-call parsing code.**

amplifierd has two independent serialization paths for tool calls. They use **different field
names for the same concept**. The origin:

- **SSE format** — mirrors the Anthropic SDK streaming protocol directly
- **Transcript format** — loop-vela's own storage format using Amplifier core's `ToolCall`
  dataclass, which uses OpenAI-style names

### The Two Formats Side by Side

**SSE stream** (`content_block:end` event, `data.block`):
```json
{
  "type": "tool_use",
  "id": "toolu_01ABC",
  "name": "bash",
  "input": {"command": "echo hello"}
}
```
Lives inside the `block` field of a `content_block:end` event. The `content` array of the
assistant message (in SSE) also contains this structure.

**Transcript API** (`GET /sessions/:id/transcript`, assistant message):
```json
{
  "role": "assistant",
  "content": "",
  "tool_calls": [
    {"id": "toolu_01ABC", "tool": "bash", "arguments": {"command": "echo hello"}}
  ]
}
```
Tool calls are in a **top-level `tool_calls` key** (not inside `content`). `content` is an
empty string when the only output was tool calls.

### Field Name Mapping

| Concept              | SSE (`content_block:end`) | Transcript (`tool_calls[]`) |
|----------------------|---------------------------|------------------------------|
| Block location       | `data.block` (in content) | top-level `tool_calls` array |
| Tool name            | `block.name`              | `tc.tool`                    |
| Tool arguments       | `block.input` (object)    | `tc.arguments` (object)      |
| Tool call ID         | `block.id`                | `tc.id`                      |

**Tool result format is the SAME in both layers:**
```json
{"role": "tool", "name": "bash", "tool_call_id": "toolu_01ABC", "content": "...output..."}
```

### Why Different?
- **SSE**: `VelaAnthropicProvider.stream()` re-emits Anthropic SDK events verbatim → Anthropic uses `name`/`input`
- **Transcript**: loop-vela stores via `ToolCall.name` → serialized as `"tool"`, and
  `ToolCall.arguments` (from Anthropic's `input`, renamed at the provider boundary) → `"arguments"`
- Amplifier core's `ToolCall` class bridges Anthropic and OpenAI APIs, using OpenAI-style names

### Rule: Which to Trust
- **Reading SSE events** (`AmplifierdStreamClient`, `SessionSseNormalizer`): use `block.type`,
  `block.name`, `block.input`, blocks are inside `content` array
- **Reading transcript** (`SessionTranscriptNormalizer`, any code hitting `/transcript`): use
  top-level `tool_calls` with `tc.tool`, `tc.arguments`; `content` will be empty string

---

### Event names that DO NOT EXIST in amplifierd (do not invent these)
- ❌ `llm:chunk` — does NOT exist
- ❌ `tool:start`, `tool_start`, `tool:result`, `tool_result`, `tool:done` — none exist
- ❌ `[DONE]` — amplifierd does not use this SSE pattern
- ❌ Native tool events are NOT in the SSE stream; tool details come via `content_block:end` with `block_type: "tool_use"`
- ✅ `content_block:delta` DOES exist (loop-vela only) — real tokens from Anthropic SDK streaming

### Response format (loop-vela bundle)
- **`content_block:delta` IS the per-token streaming event** (loop-vela only). Token is in `data.data.token`.
- `content_block:end` arrives AFTER all deltas with the complete final text — authoritative source.
- Delta events come from REAL Anthropic API streaming via `VelaAnthropicProvider.stream()` — no simulation.
- Thinking blocks (`block_type: "thinking"`) exist in `content_block:start/end` — hide from user, don't render.
- Delta events arrive BEFORE `content_block:end` (correct order: streaming chunks → final block).

### Steer endpoint (loop-vela sessions only)
```
POST /sessions/{id}/steer
Body: {"message": "redirect message here"}
Auth: x-amplifier-token header
Returns: {"status": "queued", "session_id": "..."}  or 404 if session not using loop-vela
```
The message is injected as a user turn at the next tool-call boundary in the running loop.

### Session statuses (from `GET /sessions`)
```
"executing"  ← active, currently running LLM loop
"idle"       ← session exists, no current execution (maps to "completed" in Vela UI)
"failed"     ← error state
"completed"  ← done
```
**WRONG values (do not use):** `"running"`, `"waiting"` — these don't exist in amplifierd.

### Execute endpoint
```
POST /sessions/{id}/execute/stream
Body: {"prompt": "the message"}   ← field is "prompt" NOT "message"
Auth: x-amplifier-token header
Returns immediately: {"correlation_id":"...", "session_id":"...", "status":"accepted"}
```

---

## Server-Side Components (on Mac at 10.0.0.143)

### loop-vela orchestrator
Custom amplifierd orchestrator based on `loop-streaming` with additions:
- **Per-token delta events**: emits `content_block:delta {token, block_index}` for each word/token
- **Steer queue**: each session gets an `asyncio.Queue`; `POST /sessions/{id}/steer` enqueues messages
- **Mid-loop injection**: between LLM iterations, drains the steer queue and injects messages as user turns
- Source: `/Users/ken/workspace/vela/plugins/loop-vela/`
- Installed: editable install in amplifierd Python env (`uv pip install -e`)
- Entry point: `loop-vela = amplifier_module_loop_vela:mount`

### vela bundle
Registered at `~/.amplifier/bundles/vela.md`. Inherits `distro` (all tools/providers/context) but
overrides the orchestrator to `loop-vela`. Sessions must be created with `bundle_name: "vela"`.

### Vela plugin steer endpoint
Added to `plugins/amplifierd-vela/src/vela_plugin/steer.py`.
Route: `POST /sessions/{id}/steer` — imports `_steer_queues` from `amplifier_module_loop_vela`
(same process), puts message in the session's asyncio.Queue.

### How to re-install after changes
```bash
# Reinstall loop-vela (editable install — changes take effect on next amplifierd restart)
cd /Users/ken/workspace/vela/plugins/loop-vela && \
  uv pip install -e . --python ~/.local/share/uv/tools/amplifierd/bin/python

# Reinstall Vela plugin (not editable — must reinstall to pick up source changes)
cd /Users/ken/workspace/vela/plugins/amplifierd-vela && \
  uv pip install . --python ~/.local/share/uv/tools/amplifierd/bin/python

# Restart amplifierd
launchctl unload ~/Library/LaunchAgents/com.vela.amplifierd.plist
launchctl load ~/Library/LaunchAgents/com.vela.amplifierd.plist
```

---

## Known Plist Bug (Fixed 2026-05-01)

The `generateLaunchdPlist()` function in `NodeBootstrapper.kt` would embed the ANTHROPIC_API_KEY AND the VELA_AUTH_TOKEN concatenated with a newline in a single plist `<string>` tag if the inputs weren't trimmed. This made the Anthropic API key invalid, causing all sessions to fail with "Connection error." provider:retry events.

**Fix:** `generateLaunchdPlist` now calls `.trim()` on both `anthropicKey` and `token` before interpolating.

**The CORRECT key is in `~/.amplifier/keys.env` on the Mac.** The launchd plist at `~/Library/LaunchAgents/com.vela.amplifierd.plist` must have separate `<key>` entries for `ANTHROPIC_API_KEY` and `VELA_AUTH_TOKEN`.

---

## Room Database Schema (current)

```
ssh_nodes (id, label, hosts, port, username, type, url, tailscale_url, token, bootstrapStatus, workspaceDir, addedAt)
  type: AMPLIFIERD = node bootstrapped and running amplifierd
  url: "http://{sshHost}:8410" — LAN/SSH IP URL (primary)
  tailscale_url: "http://100.x.x.x:8410" — Tailscale IP URL (tried first, works cross-network)
  token: vela auth token — sent as x-amplifier-token header
  workspaceDir: default "~" — base dir for all project sessions on this node

DB version: 17
Migrations: 1→2, ..., 16→17 (tailscale_url column added)
```

### Multi-URL Connectivity (AmplifierdRepository)
`candidateUrls(node)` returns URLs in priority order:
1. `tailscaleUrl` (if set) — Tailscale IP, works across any network with TS running
2. `url` — LAN IP from bootstrap
3. Derived from `hosts` list (SSH IPs) using same port

`findReachableUrl(node)` tries each candidate with `GET /health` and returns the first 200.
`HomeViewModel` polls all AMPLIFIERD nodes every 60s, exposes `nodeConnectivity: StateFlow<Map<String, NodeConnectivity>>`.

### NodeConnectivity states
`Unknown` → `Checking` → `Reachable(activeUrl)` | `Unreachable`
Maps to `NodeTileStatus`: Reachable=Idle, Unreachable=Offline (red stripe + "OFFLINE" chip)

---

## Key Classes (current, not the old Rust JNI architecture)

```
AmplifierdClient       — HTTP CRUD: GET/POST /sessions, /projects, /capabilities, /transcript
                         + steer(sessionId, message): POST /sessions/{id}/steer for loop-vela
AmplifierdStreamClient — SSE streaming: opens GET /events first, then POST /execute/stream
                         handles content_block:delta → TextDelta events (loop-vela)
AmplifierdRepository   — per-node client factory; candidateUrls() + findReachableUrl() for
                         multi-URL fallback; clientForNode() still works for single-URL callers
SshNodeRegistry        — @Singleton; .cache: List<SshNode> populated by HomeViewModel
HomeViewModel          — polls all AMPLIFIERD nodes every 60s; exposes nodeConnectivity StateFlow
NodeConnectivity       — sealed class: Unknown / Checking / Reachable(activeUrl) / Unreachable
SessionDetailViewModel — drives chat: sendMessage(), steer(), awaitNode(), turns StateFlow
ApiKeyStore            — EncryptedSharedPreferences: OPENAI_API_KEY only (for Whisper)
NodeBootstrapper       — SSH install of amplifierd: bootstrap() and repair() flows
TailscaleApiClient     — Tailscale REST API client: listDevices() → List<TailscaleDevice>
```

### sendMessage() flow (SessionDetailViewModel)
```
sendMessage(message) called
  → if streaming or blank: return early
  → clearInputText() + clearAttachments()
  → launch(Dispatchers.IO):
      → _isStreaming = true
      → append user TurnContent to _turns
      → awaitNode() — polls registry.cache up to 5s (10× 500ms)
      → streamClientForNode(node) — needs type=AMPLIFIERD and url non-blank
      → append empty assistant TurnContent to _turns
      → streamClient.stream(sessionId, message).collect { event → ... }
          → Thinking → update assistant slot with "…"
          → TextDelta → append token to assistant slot text (real-time streaming)
          → TextBlock → replace assistant slot with final complete text
          → ToolUse → append ToolCall to assistant slot
          → ProviderRetry → show statusMessage
          → Done → _isStreaming = false
          → Error → _isStreaming = false
```

---

## Antipatterns Discovered (Never Reintroduce)

| Antipattern | Symptom | Fix |
|---|---|---|
| Wrong SSE event names | Chat shows nothing, no errors | Use ONLY the event vocabulary above |
| POST before GET /events | Race condition, miss early events | Always open SSE stream FIRST |
| `registry.cache` read without waiting | awaitNode returns null silently | Use awaitNode() with retry loop |
| Plist API key with untrimmed trailing newline | "Connection error." retries forever | .trim() both key and token before interpolating |
| `"running"/"waiting"` status strings | ACTIVE session list always empty | Use `"executing"` for active status comparison |
| `"message"` field name in execute body | 422 from amplifierd | Use `"prompt"` field name |
| SFTP writes for plist/config | Silent failures on some SSH configs | Use execWrite() (base64 via exec channel) |
| `AmplifierdRepository.clientFor(nodeId)` cache race | Null client, silent no-op | Use `clientForNode(node: SshNode?)` — pass the already-loaded SshNode directly |

---

## ADB Debugging Commands

**Device discovery (run this first — port changes every session):**
```bash
DEVICE=$(./scripts/vela-device)   # auto-discovers IP:port, connects if needed
```

If `vela-device` fails (device not advertising yet), enable wireless debugging on the phone,
then `adb connect 10.0.0.106:<port>` once — after that `vela-device` will find it via `adb devices`.

```bash
# Discover device (agents should always run this first)
DEVICE=$(./scripts/vela-device)

# Get app PID (use this, not package-name grep which misses log lines)
APP_PID=$(adb -s $DEVICE shell pidof com.vela.app | tr -d ' \r\n')

# Watch ALL app logs by PID (most reliable)
adb -s $DEVICE logcat --pid=$APP_PID

# Screenshot (exec-out returns black if screen locked; use shell+pull instead)
adb -s $DEVICE shell screencap /sdcard/vela.png
adb -s $DEVICE pull /sdcard/vela.png /tmp/vela.png

# Wake screen (needed before any screenshot)
adb -s $DEVICE shell input keyevent KEYCODE_WAKEUP

# Check if screen is locked
adb -s $DEVICE shell dumpsys window | grep mDreamingLockscreen

# SQLite DB inspection
adb -s $DEVICE shell run-as com.vela.app \
  sqlite3 /data/data/com.vela.app/databases/vela_database "SELECT * FROM ssh_nodes"
```

---

## amplifierd Node Management

### Launchd plist location
`~/Library/LaunchAgents/com.vela.amplifierd.plist`

### Correct plist structure (no concatenation bugs)
```xml
<key>EnvironmentVariables</key>
<dict>
  <key>PATH</key><string>/Users/ken/.local/bin:/usr/local/bin:/usr/bin:/bin</string>
  <key>ANTHROPIC_API_KEY</key><string>sk-ant-api03-...</string>    ← one key ONLY per tag
  <key>VELA_AUTH_TOKEN</key><string>cjpOWhq...</string>            ← separate tag
</dict>
```

### Verify amplifierd is working
```bash
curl -s http://10.0.0.143:8410/health   # should return {"status":"healthy",...}
curl -s -H "x-amplifier-token: TOKEN" http://10.0.0.143:8410/projects
```

### Reload after plist changes
```bash
launchctl bootout gui/$(id -u)/com.vela.amplifierd 2>/dev/null || true
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.vela.amplifierd.plist
```

---

## Build and Deploy

```bash
# Build (skip tests for speed)
cd /Users/ken/workspace/vela
./gradlew assembleDebug -x test

# Discover device (port changes every session — always do this first)
DEVICE=$(./scripts/vela-device)

# Install
adb -s $DEVICE install -r app/build/outputs/apk/debug/app-debug.apk

# Launch
adb -s $DEVICE shell am start --user 0 -n com.vela.app/.MainActivity
```

## Current Phone
Pixel 10 Pro at 10.0.0.106, hardware serial `58121FDCH002PR` (stable, never changes).
Wireless debugging port changes each session — use `./scripts/vela-device` to discover it.
DB: version 17. App package: `com.vela.app`.
