# Vela — Agent Context

> **Read this first before making any changes.**
> This file is the source of truth for any AI agent working on this codebase.
> **EVIDENCE RULE: Don't invent API shapes, event names, or status values. Verify with curl or logcat first.**

---

## What Vela Is

Vela is a **mobile-first AI orchestration hub** (Android app). The phone is the UI and controller. Intelligence runs on remote amplifierd nodes (SSH-accessible machines). The app talks to amplifierd via HTTP + SSE.

---

## Current Architecture (as of 2026-05-02)

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
                                   # (may also appear for thinking blocks in non-streaming path)
id: 9  event: content_block:end    # data.block = {"text": "...", "type": "text"} (full final text)
id:9a  event: content_block:delta  # *** loop-vela ONLY *** data.token = "word", data.block_index = 0
                                   # emitted for each token during streaming; arrives AFTER content_block:end
                                   # due to _tokenize_stream simulating streaming from full response
id:10  event: execution:end
id:11  event: orchestrator:complete  ← DONE signal; data.orchestrator = "loop-vela"
```

### On provider failure (retries)
```
id: 7  event: provider:retry   # data.attempt, data.max_retries, data.error_message, data.delay
                               # (repeated up to max_retries times, then execution:end with error)
```

### Event names that DO NOT EXIST in amplifierd (do not invent these)
- ❌ `llm:chunk` — does NOT exist
- ❌ `tool:start`, `tool_start`, `tool:result`, `tool_result`, `tool:done` — none exist
- ❌ `[DONE]` — amplifierd does not use this SSE pattern
- ❌ Native tool events are NOT in the SSE stream; tool details come via `content_block:end` with `block_type: "tool_use"`
- ✅ `content_block:delta` DOES exist (loop-vela only) — per-token streaming via `_tokenize_stream`

### Response format (loop-vela bundle)
- **`content_block:delta` IS the per-token streaming event** (loop-vela only). Token is in `data.token`.
- `content_block:end` still arrives with the complete final text — use as the authoritative fallback.
- Delta events use `_tokenize_stream` simulation (word/space boundaries at ~10ms delay per token).
- Thinking blocks (`block_type: "thinking"`) exist in `content_block:start/end` — hide from user, don't render.
- Delta events arrive AFTER `content_block:end` because the non-streaming path returns the full text first.

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
ssh_nodes (id, label, hosts, port, username, type, url, token, bootstrapStatus, workspaceDir, addedAt)
  type: AMPLIFIERD = node bootstrapped and running amplifierd
  url: "http://10.x.x.x:8410" — used by AmplifierdClient and AmplifierdStreamClient
  token: vela auth token — sent as x-amplifier-token header
  workspaceDir: default "~" — base dir for all project sessions on this node

DB version: 16
Migrations: 1→2, ..., 15→16 (workspace_dir column added)
```

---

## Key Classes (current, not the old Rust JNI architecture)

```
AmplifierdClient       — HTTP CRUD: GET/POST /sessions, /projects, /capabilities, /transcript
                         + steer(sessionId, message): POST /sessions/{id}/steer for loop-vela
AmplifierdStreamClient — SSE streaming: opens GET /events first, then POST /execute/stream
                         handles content_block:delta → TextDelta events (loop-vela)
AmplifierdRepository   — per-node client factory; reads node from SshNodeRegistry.cache
SshNodeRegistry        — @Singleton; .cache: List<SshNode> populated by HomeViewModel
SessionDetailViewModel — drives chat: sendMessage(), steer(), awaitNode(), turns StateFlow
ApiKeyStore            — EncryptedSharedPreferences: OPENAI_API_KEY only (for Whisper)
NodeBootstrapper       — SSH install of amplifierd: bootstrap() and repair() flows
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

```bash
# Connect
adb connect 10.0.0.106:<port>

# Get app PID (use this, not package-name grep which misses log lines)
APP_PID=$(adb -s 10.0.0.106:<port> shell pidof com.vela.app | tr -d ' \r\n')

# Watch ALL app logs by PID (most reliable)
adb -s 10.0.0.106:<port> logcat --pid=$APP_PID

# Screenshot (exec-out returns black if screen locked; use shell+pull instead)
adb -s 10.0.0.106:<port> shell screencap /sdcard/vela.png
adb -s 10.0.0.106:<port> pull /sdcard/vela.png /tmp/vela.png

# Wake screen (needed before any screenshot)
adb -s 10.0.0.106:<port> shell input keyevent KEYCODE_WAKEUP

# Check if screen is locked
adb -s 10.0.0.106:<port> shell dumpsys window | grep mDreamingLockscreen

# SQLite DB inspection
adb -s 10.0.0.106:<port> shell run-as com.vela.app \
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

# Install
adb -s 10.0.0.106:<port> install -r app/build/outputs/apk/debug/app-debug.apk

# Launch
adb -s 10.0.0.106:<port> shell am start --user 0 -n com.vela.app/.MainActivity
```

## Current Phone
Pixel 10 Pro at 10.0.0.106, wireless debugging port changes each session.
DB: version 16. App package: `com.vela.app`.
