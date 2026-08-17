# Preserved Lessons from the Previous Architecture

This document captures hard-won lessons from the Amplifierd-based Vela Android application (preserved on `archive/pre-rebuild-2026-08`). These insights apply directly to the S4′ rebuild, even though the code is not reused.

---

## 1. SSE Tool-Call Format Divergence

**The critical bug that silently dropped tool calls in production.**

amplifierd's HTTP face uses two independent serialization paths for tool calls, with **different field names for the same concept**. Confusing them causes tool blocks to disappear post-stream with zero error signal.

### The Two Formats

**SSE stream format** (`GET /events`, `content_block:end` event):
```json
{
  "type": "tool_use",
  "id": "toolu_01ABC",
  "name": "bash",
  "input": {"command": "echo hello"}
}
```
- Tool name is in `block.name`
- Arguments are in `block.input` (a single object)
- Blocks live inside the `block` field within a `content` array

**Transcript API format** (`GET /sessions/:id/transcript`, assistant message):
```json
{
  "role": "assistant",
  "content": "",
  "tool_calls": [
    {"id": "toolu_01ABC", "tool": "bash", "arguments": {"command": "echo hello"}}
  ]
}
```
- Tool name is in `tc.tool` (not `name`)
- Arguments are in `tc.arguments` (not `input`)
- Tool calls are in a **top-level `tool_calls` array**, not nested inside `content`
- When only tool calls are output, `content` is an empty string

### Why This Happens

- **SSE events**: The HTTP face re-emits Anthropic SDK events verbatim. Anthropic uses `name`/`input`.
- **Transcript API**: loop-vela's own storage format uses `ToolCall.tool`/`ToolCall.arguments` (OpenAI-style names).
- Amplifier core's `ToolCall` dataclass bridges Anthropic and OpenAI, using OpenAI naming in its serialization.

### The Real Bug

`SessionTranscriptNormalizer` was written reading the SSE format but consuming the transcript API. It looked for `block.name` in the top-level transcript `tool_calls` array (which only has `tc.tool`), found nothing, and silently discarded the tool call. **The bug was silent** — no error message, no dropped-tool indicator, just missing work.

### Rule: Which Format to Trust

- **Reading SSE events** (`AmplifierdStreamClient`, `SessionSseNormalizer`): use SSE format names (`block.type`, `block.name`, `block.input`); blocks are inside `content` array.
- **Reading transcript** (`SessionTranscriptNormalizer`, code calling `/transcript`): use top-level `tool_calls` with `tc.tool`, `tc.arguments`; `content` is usually empty.
- **Tool result format is the same in both**: `{role: "tool", name: "bash", tool_call_id: "toolu_01ABC", content: "...output..."}`

### Lesson for S4′

When building the C2 event client and any tool-call parsing, **verify you're using the correct field names for the source format**. Add an explicit comment on the code reading tool calls stating which format it expects. Write a test that deliberately passes a tool call with a mismatched field name and **asserts it is detected as an error**, not silently dropped.

---

## 2. Session Status Strings: The Inverted Status Bug

**The mistake that made active sessions disappear from the UI.**

amplifierd returns session status as a string, and the list of active sessions is typically filtered by checking if status == `"running"` or status == `"active"` or similar plausible-sounding values. **But amplifierd's actual status strings don't match those names.**

### The Actual Status Values (from amplifierd)

```
"executing"  ← active, currently running LLM loop
"idle"       ← session exists, no current execution (maps to "completed" in Vela UI)
"failed"     ← error state
"completed"  ← done
```

### The Bug in Practice

Code written assuming `"running"` or `"waiting"` would produce a permanently-empty active-sessions list with zero error indication. The status value would simply never match the filter, and the list would stay empty even with multiple active sessions.

### Why It's Hard to Catch

The mistake looks like it could work — there *should* be a status value for "currently executing". The code compiles, runs, and produces a plausible-looking output (an empty list). It's only when you notice the active session list is always empty that you realize something is wrong. And by then, you're debugging the wrong layer (the filter, the query, the network request) instead of checking the actual status string against the API docs.

### Lesson for S4′

**Hard-code the correct status string values and add a static assertion.** Don't infer them from analogies to other systems:

```kotlin
// Correct status values from vela-agentd
object SessionStatus {
    const val EXECUTING = "executing"    // Active, LLM loop running
    const val IDLE = "idle"              // Session exists, no active execution
    const val FAILED = "failed"          // Error state
    const val COMPLETED = "completed"    // Done
    
    // This will FAIL at compile time if any of these strings are typos
    private val VALID_STATUSES = setOf(EXECUTING, IDLE, FAILED, COMPLETED)
}
```

Use these constants everywhere. If the API changes, the assertion breaks and code doesn't compile. If someone guesses at a status string, compilation fails. This is a $50,000+ mistake in production time; a compile-time assertion that catches it is worth the tiny bit of verbosity.

---

## 3. SSE Stream Protocol Ordering: Must Open Before POST

**The race condition that lost early events.**

When using SSE (Server-Sent Events) with a follow-up request to start execution:

1. **WRONG**: `POST /sessions/:id/execute/stream` (request returns immediately with session ID) then `GET /events?session=:id` (opens stream)
   - The server emits events immediately after accepting the POST
   - If the GET opens even 10ms late, early events are lost
   - Session starts, LLM responds, tool call fires — all missed

2. **CORRECT**: `GET /events?session=:id` (opens stream **FIRST**, server replays from seq 1) then `POST /sessions/:id/execute/stream` (starts the execution)
   - Stream is already open and listening when POST is processed
   - Early events (first session heartbeat, setup) are captured
   - Execution happens in the right order

### Why amplifierd Works This Way

The event stream is durable — it has a sequence number and the server re-serves historical events from the beginning (seq 1) when a client connects. This is not a live-tail stream; it's a replay stream. Taking advantage of this means opening the reader *before* the writer starts.

### Implementation Pattern

```kotlin
// The AmplifierdStreamClient pattern (proven)
suspend inline fun stream(sessionId: String, prompt: String): Flow<StreamEvent> = flow {
    // Step 1: Open SSE FIRST
    val eventStream = openSSEStream(sessionId)
    
    // Step 2: Execute via POST (this returns immediately, returns correlation_id)
    val result = executeRequest(sessionId, prompt)
    
    // Step 3: Consume events
    eventStream.collect { event -> emit(event) }
}
```

### Lesson for S4′

Document this ordering as part of the C1/C2 client handshake. Write a test that deliberately opens in the wrong order (POST then GET) and **asserts that events are missed**. Then fix the test to use the correct order and verify all events arrive. This prevents a regression that could take weeks to debug.

---

## 4. Five-State Connectivity Model with Multi-URL Fallback

**The solution to reaching `vela-agentd` reliably across networks.**

The Android app must reach `vela-agentd` across multiple network domains:
- **LAN** (WiFi, same building)
- **Tailscale** (VPN overlay, works across any network with TS running)
- **SSH tunnels** (last resort, slow)

A node might be unreachable on LAN but reachable on Tailscale. A node might move between networks. The connectivity state machine must be explicit.

### The Five States

```
Unknown
  ├─ (check connectivity)
  └─→ Checking
       ├─ (success) ─→ Reachable(activeUrl)
       └─ (failure) ─→ Unreachable
           ├─ (check again after delay)
           └─→ Checking
```

**State transitions:**
- `Unknown` → `Checking`: Periodic poll trigger or user-initiated check
- `Checking` → `Reachable(activeUrl)`: Health check (`GET /health`) returned 200
- `Checking` → `Unreachable`: Health check timed out or returned error
- `Unreachable` → `Checking`: After exponential backoff (e.g., 30s, 60s, 120s, cap at 5m)
- `Reachable(activeUrl)` → `Checking`: Periodic revalidation, or explicit request

**Key insight:** The state is *persisted* — it survives app kill. When the app restarts and finds a node in the DB, it knows the last-known reachability state and can prioritize accordingly.

### Multi-URL Candidate Strategy

For each node, maintain an ordered list of candidate URLs:

1. **Tailscale URL** (if available) — Works cross-network; try first
2. **LAN URL** — Fastest when available; try if Tailscale doesn't work
3. **SSH-derived URLs** — Slowest; last resort

```kotlin
fun candidateUrls(node: SshNode): List<String> {
    return listOfNotNull(
        node.tailscaleUrl,  // 100.x.x.x address
        node.url,           // 192.168.x.x or similar LAN address
        node.hosts.firstOrNull()?.let { "http://$it:8410" }  // SSH hostname
    )
}

suspend fun findReachableUrl(node: SshNode): String? {
    for (url in candidateUrls(node)) {
        if (healthCheck(url)) return url
    }
    return null
}
```

### Lesson for S4′

The app must implement this five-state machine and multi-URL fallback for `vela-agentd` connectivity. Document the state machine explicitly in code. In the UI, make the connectivity state visible (e.g., a small indicator showing "Reachable on Tailscale", "Unreachable — last known 5m ago").

The database schema must include:
- `tailscale_url`: The Tailscale IP-based URL
- `url`: The LAN/primary URL
- `hosts`: List of SSH hostnames (for fallback)
- `lastKnownReachableUrl`: Persisted for efficient retry
- `lastConnectivityCheck`: Timestamp (drive the exponential backoff)

---

## 5. Android Foreground Service + Notification Pattern

**The pattern that keeps voice sessions alive without burning battery.**

`SessionStreamingService` implements a proven foreground service that:
- Holds a **foreground service lock** while actively streaming (voice, events)
- Updates a persistent **notification** to show state (Listening, Speaking, Thinking, Completed)
- Survives process kill and app backgrounding (within the 6h/day foreground-service budget)
- Releases the lock immediately when done, freeing the budget

### Key Constraints

Android 12+ enforces a strict **foreground-service budget** (~6 hours per day for user-initiated services). An app cannot hold FGS indefinitely. The strategy:

- **FGS is on only during active sessions** (user speaking, listening, receiving events)
- **FGS is off during idle periods** (app backgrounded, session complete, waiting for next input)
- **Notification channel is persistent** but the service itself cycles on/off

### Implementation Pattern

```kotlin
// Simplified pattern (see archive branch for full implementation)
class SessionStreamingService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification("Listening for commands...")
        startForeground(NOTIFICATION_ID, notification)  // Hold FGS lock
        
        streamClient.stream(sessionId, prompt).collect { event ->
            notification.update(event)  // Update without restarting
            notificationManager.notify(NOTIFICATION_ID, notification)
        }
        
        stopForeground(STOP_FOREGROUND_REMOVE)  // Release lock when done
        stopSelf()
        return START_STICKY
    }
}
```

### Notification Channel Setup

```kotlin
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
    val channel = NotificationChannel(
        CHANNEL_ID,
        "Voice Sessions",
        NotificationManager.IMPORTANCE_LOW  // No sound/vibration, just presence
    )
    notificationManager.createNotificationChannel(channel)
}
```

### Lesson for S4′

The phone-ledger lane and voice-transport lane must implement a similar service. Document the FGS lifecycle explicitly. Test that:
1. Service starts and holds FGS when a voice session begins
2. Notification updates reflect real-time state changes
3. Service releases FGS and stops itself when the session completes
4. The app can run multiple independent voice sessions without stacking FGS locks (use a per-session ID, not a global service)
5. Device reboot doesn't leave a orphaned foreground service (use a manifest receiver or intent re-broadcast)

---

## 6. Rust Crates: Preserved but Unused (Why and Where)

**The 8,671 LOC and 141 tests that represent real engine design, not code waste.**

The repository contains 6 Rust crates at `crates/` on the archive branch:

| Crate | LOC | Purpose | Status |
|---|---|---|---|
| `amplifier-orchestration` | ~2,400 | Delegation, sub-agent routing | Designed but never integrated into the app |
| `amplifier-streaming` | ~1,800 | Token streaming, event correlation | Core insight: matching streaming chunks to tool calls in parallel workloads |
| `amplifier-session` | ~2,100 | Crash-resumable session storage | Proven crash-recovery semantics (not replicated elsewhere) |
| `amplifier-error-handling` | ~800 | Custom error types, recovery semantics | Defensive design (rarely used) |
| `amplifier-model-routing` | ~800 | Swappable model selection | Mostly aspirational; routing was hardcoded in practice |
| `amplifier-testing-harness` | ~1,700 | Test utilities, mock agents | High-value testing infrastructure |

### Why They're Preserved but Unused in S4′

Assumption A1 (that `amplifier-agent` remains the active runtime) means a custom Rust engine is not needed. The S4′ design shifts computation to `vela-agentd` (a thin fork of `amplifier-agent` written in Python), not a new Rust kernel.

**But:** If A1 ever becomes false (amplifier-agent is abandoned, a new engine is needed), these crates are the **starting point for any rearchitecture**. Redesigning them from scratch would require re-learning the lessons baked into:

- **Streaming chunk correlation**: The implementation in `amplifier-streaming` encodes the `eb207e74` lesson (claim-first-unclaimed semantics for parallel delegates). Re-inventing this costs real debugging time.
- **Session persistence**: The crash-recovery patterns in `amplifier-session` are non-obvious and would be easy to get wrong.
- **Delegation routing**: `amplifier-orchestration` shows how to map sub-agent responsibility without hardcoding.

### Lesson for S4′

Do not try to integrate these crates into the rebuild. They're a fallback, not a shortcut. But **reference them when designing similar systems**:

- Building a new streaming orchestrator? Read `amplifier-streaming`'s approach to chunk correlation.
- Building a new session persistence layer? Read `amplifier-session`'s crash-recovery design.
- Building a delegation router? Read `amplifier-orchestration`'s model.

**If A1 becomes false mid-project**, these are the crates that get revisited. They're preserved so no one rediscovers these patterns.

---

## 7. Parallel-Delegate Streaming Fix: eb207e74

**The fix for correlating streaming chunks when multiple sub-agents run at the same time.**

**Commit:** `eb207e74` ("fix(streaming): claim first unclaimed ToolUse for parallel delegate chunks")

**The bug:** When two delegates run in parallel, streaming tokens arrive out-of-order and need to be assigned to the right tool call:

```
Turn: Call delegate A and delegate B in parallel
Event stream arrives:
  - content_block:delta token="hello" (which tool?)
  - content_block:delta token="from" (which tool?)
  - content_block:delta token="both" (which tool?)
  - tool_use complete for A
  - tool_use complete for B
```

The naive approach (assign deltas round-robin) is wrong. The correct approach (claim first unclaimed tool call) ensures tokens are attributed correctly.

### The Fix

```
For each incoming delta:
  - Find the first ToolUse block that hasn't claimed deltas yet
  - Assign this delta to that block
  - Mark the block as "claimed"
  - When a tool_use completes, unclaim it for the next set of deltas
```

This is documented in the commit message and the code. **The same bug will recur in C2 client event processing** if this lesson is not carried forward.

### Lesson for S4′

When building the C2 event client (Lane 3.2), implement the claim-first-unclaimed semantics **from the start**. Write a test case that deliberately sends parallel tool calls with out-of-order deltas and **asserts correct attribution**. Reference this commit in code comments so the next person knows this is a known problem with a proven solution.

---

## 8. Historical API Surface: docs/amplifierd-openapi.json

**What capabilities existed before, so the rebuild can check for unintentional regressions.**

This is the OpenAPI specification for the `amplifierd` HTTP face (the system being moved off of). It's useful for **two specific reasons:**

1. **Regression detection**: The new C1/C2/C3 surfaces should not accidentally drop a capability that was useful. (Example: steering was a regression initially; it was a feature in amplifierd that was lost in S4′ and had to be added back via Lane 4.1.)

2. **Capability reference**: When designing the new services, compare against what amplifierd offered. If the new design omits something useful, decide explicitly (deprecate it, replace it, redesign it) rather than dropping it by accident.

### Lesson for S4′

When finalizing the C1/C2/C3 API surfaces, run through `docs/reference/amplifierd-openapi.json` and list:
- What capabilities are intentionally removed (with reason)
- What capabilities are renamed/replaced (with mapping)
- What new capabilities are added (not in amplifierd)

This forces explicit decisions and prevents silent feature loss.

---

## Summary

These eight lessons cover:
1. **Tool-call serialization differences** (production data loss bug)
2. **Status string correctness** (active-session visibility bug)
3. **SSE stream ordering** (race condition, lost events)
4. **Multi-network connectivity** (practical reliability across LAN/Tailscale/SSH)
5. **Foreground-service lifecycle** (voice sessions that don't burn battery)
6. **Engine design fallback** (Rust crates as a plan-B reference, not code reuse)
7. **Parallel-delegate streaming** (known bug pattern with proven fix)
8. **API surface regression checking** (systematic capability auditing)

All of these apply to S4′ **regardless of language or architecture choice**. They are lessons, not code artifacts.
