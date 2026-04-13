# Vela — Agent Context

> **Read this first.** This file is the source of truth for any AI agent working on this codebase.
> Cross-reference with `docs/plans/2026-04-11-vela-design.md` for the long-range product vision.

---

## What Vela Is

Vela is a **mobile-first AI orchestration hub**. Each family member runs Vela on their phone. The phone is the brain — orchestration, history, routing, and UI all live on-device. Intelligence and compute come from a network of remote Amplifier nodes (SSH-accessible machines) that run Claude.

**Vela is the general. The nodes are the battalions.**

---

## Current Implementation State

This is the Android app (Kotlin + Rust JNI). It is a working prototype that:

- Talks directly to Anthropic's API via a Rust JNI bridge (amplifier-core)
- Stores full conversation history in Room DB on-device
- Renders tool call blocks interleaved with text in correct Anthropic content order
- Can SSH into registered machines and run commands as AI tools
- Manages multiple chat sessions locally

The Amplifier node network, authorization fabric, and multi-user support are in the design doc (`docs/plans/`) but not yet implemented.

---

## Architecture

### Layer diagram

```
┌─────────────────────────────────────────────────────────────┐
│  UI Layer  (Compose)                                        │
│                                                             │
│  ConversationScreen ─── ConversationViewModel               │
│  NodesScreen        ─── NodesViewModel                      │
│                                                             │
│  Reads: Room reactive Flows (StateFlow<List<TurnWithEvents>>│
│  Writes: calls InferenceEngine.startTurn() only             │
└──────────────────────────┬──────────────────────────────────┘
                           │ startTurn(convId, userMessage)
                           ▼
┌─────────────────────────────────────────────────────────────┐
│  InferenceEngine  (@Singleton, own CoroutineScope)          │
│                                                             │
│  CoroutineScope(SupervisorJob() + Dispatchers.IO)           │
│  Lives as long as the process — NOT tied to any ViewModel   │
│                                                             │
│  Drives the agent loop:                                     │
│    1. Creates Turn row in DB                                │
│    2. Calls AmplifierSession.runTurn()                      │
│    3. onToken  → flush to textBuffer, update streamingText  │
│    4. onToolStart → flush textBuffer to TurnEvent (seq N)   │
│                  → insert tool TurnEvent (seq N+1, running) │
│    5. onToolEnd  → UPDATE tool TurnEvent in-place (done)    │
│    6. End of turn → flush remaining text as TurnEvent       │
│    7. UPDATE Turn status = complete                         │
└──────────────────────────┬──────────────────────────────────┘
                           │ runTurn(historyJson, userInput, callbacks)
                           ▼
┌─────────────────────────────────────────────────────────────┐
│  AmplifierSession  (@Singleton, stateless)                  │
│                                                             │
│  Implements InferenceSession interface (testable)           │
│  Owns NO history — callers pass historyJson from DB         │
│  Owns NO coroutine scope — InferenceEngine owns lifecycle   │
│                                                             │
│  Calls AmplifierBridge.nativeRun() (Rust JNI)              │
│  tokenWasEmitted flag guards against double-emit antipattern│
└──────────────────────────┬──────────────────────────────────┘
                           │ JNI
                           ▼
┌─────────────────────────────────────────────────────────────┐
│  Rust JNI Bridge  (amplifier-android crate)                 │
│                                                             │
│  orchestrator.rs — multi-turn agent loop (≤10 steps)        │
│    end_turn:   emit_token(text) AND return text             │
│                ↑ ALWAYS both — tokenWasEmitted flag needed  │
│    tool_use:   emit_token(preamble) then executeTool()×N    │
│                then loop (model sees tool results)          │
│                                                             │
│  provider.rs — POST /v1/messages (non-streaming)            │
└─────────────────────────────────────────────────────────────┘
```

### Database schema (Room, SQLite)

```
conversations   (id, title, createdAt, updatedAt)
  └── turns     (id, conversationId, userMessage, status, timestamp)
       └── turn_events  (id, turnId, seq, type, ...)
                         ORDER BY seq ASC — integer counter, NEVER timestamp

ssh_nodes       (id, label, hosts, port, username, addedAt)
                 hosts = comma-separated ordered list of IPs/hostnames
                 SSH tool tries each in order until one connects

messages        (legacy — user messages in old flat model, kept for migration)
```

**`TurnWithEvents`** is the only unit the UI ever renders. Room `@Relation` joins turn + events in one query. `sortedEvents` sorts by `seq` in Kotlin (Room does not guarantee child order).

### Event ordering invariant

```
Within a turn, events have strictly increasing seq numbers:

  seq=0  text   "I'll search for that..."  ← flushed when tool starts
  seq=1  tool   search_web  running        ← inserted when tool starts
  seq=1  tool   search_web  done           ← UPDATED in-place (same row, same seq)
  seq=2  text   "Based on results..."      ← flushed at turn end

Key: text that arrives BEFORE a tool call gets a lower seq than the tool.
Key: tool updates are UPDATE not INSERT — same row, same position in UI.
Key: seq is AtomicInteger.getAndIncrement() — never System.currentTimeMillis().
```

### Streaming text

`InferenceEngine._streamingText: MutableStateFlow<Map<String, String>>`

- Maps `turnId → uncommitted in-flight text`
- Entry is added as tokens arrive (`onToken`)
- Entry is **removed** when text is flushed to a TurnEvent row
- The streaming bubble in the UI shows this map entry — it naturally disappears when the text moves to DB
- ViewModel exposes this StateFlow directly (no accumulation in ViewModel)

### Tool system

```
Tool interface:
  name, displayName, icon, description, parameters
  suspend fun execute(args: Map<String, Any>): String

Registered tools (AppModule):
  GetTimeTool, GetDateTool, GetBatteryTool    — device info
  SearchWebTool, FetchUrlTool                  — web
  ListSshNodesTool, SshCommandTool             — Vela node network

SshCommandTool:
  Uses device RSA-3072 key (SshKeyManager, stored in private SharedPrefs)
  Tries each host in SshNode.hosts in order (TOFU, StrictHostKeyChecking=no)
  Returns stdout + stderr + exit code
```

### SSH node network

```
SshNode {
  id, label,
  hosts: List<String>,   // ordered — first is primary, rest are fallbacks
  port, username
}
```

Stored as comma-separated string in DB. `SshNodeRegistry.cache` is kept warm by `NodesViewModel`. The AI tool calls `findByLabel(name)` against the cache — no DB query at tool-call time.

---

## Known Antipatterns (Avoided)

These burned us during development. Don't reintroduce them.

| Antipattern | Where it bit us | Fix |
|---|---|---|
| **Double-emit: stream AND return value carry same text** | `orchestrator.rs` calls `emit_token()` AND returns the same string. Kotlin fallback `if (finalText.isNotEmpty()) onToken(finalText)` fired unconditionally → message appeared twice. | `tokenWasEmitted` flag — fallback only fires if no tokens arrived via callback |
| **Flat message list with wall-clock timestamps for ordering** | `System.currentTimeMillis()` has ms resolution. Tool calls and assistant saves within the same turn got the same timestamp → `ORDER BY timestamp ASC` non-deterministic | `TurnEvent.seq` — `AtomicInteger` counter, never a timestamp |
| **Dual rendering paths (live vs completed turn)** | Live turns showed events. Completed turns rendered with `events = emptyList()`. On turn completion `_activeTurnId = null` → everything disappeared | Single path: `TurnWithEvents` via Room `@Relation` — every turn always has its events |
| **Inference lifecycle tied to ViewModel** | `viewModelScope.launch { session.runTurn().collect {} }` — inference cancelled when ViewModel cleared or user navigated away | `InferenceEngine` singleton with `CoroutineScope(SupervisorJob() + Dispatchers.IO)` — UI is a pure reader of DB |
| **Stale StateFlow snapshot in tool callback** | `messages.value.lastOrNull { ... }` in `onToolEnd` — messages StateFlow not updated yet when callback fires → update never applied | Carry `(msgId, metaJson)` from `onToolStart` in `pendingToolIds` map. `onToolEnd` updates by known ID — no snapshot needed |
| **Tool events always before text events** | Forced `ORDER BY seq ASC` with all tools first — wrong. Anthropic content array is `[text, tool_use, text]` | `flushText()` in `onToolStart` — accumulated text is committed as a TurnEvent (seq N) before the tool event (seq N+1) |

---

## Key Interfaces

```kotlin
// Testable inference interface — AmplifierSession implements this
interface InferenceSession {
    fun isConfigured(): Boolean
    suspend fun runTurn(
        historyJson: String,
        userInput: String,
        onToolStart: (suspend (name: String, argsJson: String) -> String),  // returns stableId
        onToolEnd:   (suspend (stableId: String, result: String) -> Unit),
        onToken:     (suspend (token: String) -> Unit),
    )
}

// Tool interface
interface Tool {
    val name: String
    val displayName: String
    val icon: String
    val description: String
    val parameters: List<ToolParameter>
    suspend fun execute(args: Map<String, Any>): String
}
```

---

## Test Harness

Unit tests live in `app/src/test/kotlin/com/vela/app/engine/InferenceFlowTest.kt`.

Run without a device:
```bash
./gradlew :app:testDebugUnitTest
```

Tests verify the event ordering invariants using a pure JVM simulation:
- `preambleTextBeforeToolBySeq`
- `textAndToolsInterleaveCorrectly`
- `noToolsJustText`
- `toolsUpdateInPlaceNeverDuplicated`
- `eventsPersistAfterTurnCompletes`
- `seqsAreStrictlyMonotonic`

**Run the tests before deploying to the phone.** `adb install` should never be the first verification step.

---

## Deployment

```bash
# Build (skips Rust rebuild — use without flag if Rust changed)
./gradlew assembleDebug -x buildRustRelease

# Run tests
./gradlew :app:testDebugUnitTest

# Connect to phone (get port from adb wireless debug settings)
adb connect <phone-ip>:<port>

# Install and launch
adb -t <transport-id> install -r app/build/outputs/apk/debug/app-debug.apk
adb -t <transport-id> shell am force-stop com.vela.app
adb -t <transport-id> shell am start -n com.vela.app/.MainActivity
```

Current phone: Pixel 10 Pro (`blazer`). Transport ID varies by network.

---

## What's Not Built Yet

From `docs/plans/2026-04-11-vela-design.md`:

- A2UI typed event stream from Amplifier nodes (nodes push events, Vela renders them)
- Authorization fabric (capability tokens, per-identity ACLs)
- Multi-user support (family members, role-based delegation)
- Android Foreground Service for inference (currently: app-scoped singleton survives backgrounding but not process death)
- Streaming Anthropic API (currently: non-streaming `/v1/messages` — full response arrives at once)
- Job registry and provable delegation (acceptance criteria, rubric-based completion)
- Living profile (user preference model, learned corrections)
- Scheduled/recurring tasks
