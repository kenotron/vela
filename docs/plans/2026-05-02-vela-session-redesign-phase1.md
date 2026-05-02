# Vela Session Redesign — Phase 1: Core Streaming Infrastructure

> **For agentic workers:** Use superpowers:subagent-driven-development or execute-plan to run this plan. No TDD — verification is done by building (`./gradlew compileDebugKotlin`) and deploying to device.

**Goal:** Create the Android Foreground Service streaming infrastructure, unified `SessionState` model, and normalizers that drive session state from both transcript API and live SSE events.

**Architecture:** A Hilt-scoped `SessionStreamingService` (Android Foreground Service) owns all SSE connections. `SessionStreamingManagerImpl` (@Singleton) exposes a clean interface to ViewModels via `SessionStreamingManager`. Both `SessionTranscriptNormalizer` and `SessionSseNormalizer` produce identical `TurnContent` structures. Phase 2 will wire ViewModels to consume this infrastructure.

**Tech Stack:** Kotlin, Hilt, Coroutines/StateFlow, OkHttp (SSE), Android Foreground Service, NotificationCompat

---

## Orientation

All new files land in the `streaming/` package:
```
app/src/main/kotlin/com/vela/app/streaming/
```

Files that are **modified** in this phase:
- `ui/sessiondetail/SessionModels.kt` — enum rename + add `isStreaming` field
- `ui/sessiondetail/ContentBlock.kt` — add `TodoProgress`, `TodoItem`, `TodoStatus`
- `ui/sessionlist/SessionCard.kt` — update color maps to new enum values
- `amplifierd/AmplifierdClient.kt` — add 3 new API methods
- `amplifierd/AmplifierdStreamClient.kt` — add `subscribeEvents()` function
- `notifications/ApprovalNotificationHelper.kt` — add 3 new notification methods + channel
- `di/AppModule.kt` — add `StreamingModule` Hilt binding
- `AndroidManifest.xml` — add permission + service declaration
- `VelaApplication.kt` — start service + create new notification channel

The existing `SessionDetailViewModel.kt` is **not touched** in Phase 1. ViewModels are rewired in Phase 2.

---

## Task 1: Update `SessionStatus` enum + `SessionCard` color maps

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/ui/sessiondetail/SessionModels.kt`
- Modify: `app/src/main/kotlin/com/vela/app/ui/sessionlist/SessionCard.kt`

### Step 1: Rewrite `SessionModels.kt`

Replace the entire file content. The only semantic changes are: rename the enum values (`RUNNING→EXECUTING`, `DONE→IDLE`, `WAITING` removed, `RESUMING` added) and add `isStreaming: Boolean = false` to `TurnContent`.

```kotlin
package com.vela.app.ui.sessiondetail

// ── Session List models ───────────────────────────────────────────────────────

/**
 * Summary of a session for display in the session list.
 * Data source: amplifierd HTTP API.
 */
data class SessionSummary(
    val id: String,
    val title: String,
    val status: SessionStatus,
    val modelName: String,
    val stepCount: Int,
    val lastActiveMs: Long,
    /** First user message (preview of what the session is about), loaded lazily from transcript. */
    val preview: String = "",
    /** Last user message — shown when session is EXECUTING so you see what the AI is working on. */
    val lastUserMessage: String = "",
)

/**
 * Lifecycle status for an amplifierd session.
 *
 * EXECUTING — AI is actively running; SSE stream open, foreground service active.
 * IDLE      — Session is live-idle or dormant; resume is idempotent (safe to call either way).
 * RESUMING  — POST /resume in flight; transcript already loaded, input bar disabled.
 * ERROR     — Retries exhausted or resume failed; retry button shown inline.
 */
enum class SessionStatus { EXECUTING, IDLE, RESUMING, ERROR }

// ── Session Detail models ─────────────────────────────────────────────────────

/**
 * A single turn in the session turn history.
 * [isUser] = true for user prompts, false for agent responses.
 * [isStreaming] = true while this turn is actively being streamed via SSE.
 */
data class TurnContent(
    val text: String,
    val toolCalls: List<ToolCall> = emptyList(),
    val isUser: Boolean,
    val isStreaming: Boolean = false,
    val contentBlocks: List<ContentBlock> = emptyList(),
)

/**
 * A tool invocation within an agent turn.
 * Shows tool name, optional result, duration, and live/done state.
 */
data class ToolCall(
    val name: String,
    val result: String? = null,
    val isDone: Boolean,
    val isRunning: Boolean,
    val durationMs: Long? = null,
)
```

### Step 2: Update `SessionCard.kt` — replace the three color-mapping functions and the status variable declarations inside the composable

Replace the three `internal fun` color maps at the top of the file:

```kotlin
internal fun cardBackgroundFor(status: SessionStatus): Color = when (status) {
    SessionStatus.EXECUTING -> Color(0xFF1C1A0E)
    SessionStatus.RESUMING  -> Color(0xFF1A1234)
    SessionStatus.IDLE      -> VelaColors.SurfaceSub
    SessionStatus.ERROR     -> Color(0xFF1C1117)
}

internal fun chipContainerFor(status: SessionStatus): Color = when (status) {
    SessionStatus.EXECUTING -> VelaColors.RunningContainer
    SessionStatus.RESUMING  -> VelaColors.WaitingContainer
    SessionStatus.IDLE      -> VelaColors.DoneContainer
    SessionStatus.ERROR     -> VelaColors.ErrorContainer
}

internal fun chipOnContainerFor(status: SessionStatus): Color = when (status) {
    SessionStatus.EXECUTING -> VelaColors.RunningOnContainer
    SessionStatus.RESUMING  -> VelaColors.WaitingOnContainer
    SessionStatus.IDLE      -> VelaColors.DoneOnContainer
    SessionStatus.ERROR     -> VelaColors.ErrorOnContainer
}
```

Inside the `SessionCard` composable, replace the two `val is*` declarations:

```kotlin
    val isRunning  = session.status == SessionStatus.EXECUTING
    val isResuming = session.status == SessionStatus.RESUMING
```

Replace the `borderStroke` declaration:

```kotlin
    val borderStroke = if (isResuming) {
        BorderStroke(1.dp, VelaColors.Waiting)
    } else {
        null
    }
```

Replace both `isWaiting` references in the composable body — one in the `WAITING` affordance block at the bottom, and one in the `borderStroke` conditional. The "▶ Decide" block becomes "↻ Resuming…":

```kotlin
            if (isResuming) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text  = "↻ Resuming…",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.5.sp),
                    color = VelaColors.Waiting,
                )
            }
```

### Step 3: Verify compilation

```bash
cd /Users/ken/workspace/vela && ./gradlew :app:compileDebugKotlin 2>&1 | grep -E "error:|BUILD"
```

Expected: `BUILD SUCCESSFUL`

### Step 4: Commit

```bash
git add app/src/main/kotlin/com/vela/app/ui/sessiondetail/SessionModels.kt \
        app/src/main/kotlin/com/vela/app/ui/sessionlist/SessionCard.kt
git commit -m "feat(models): rename SessionStatus enum RUNNING→EXECUTING, DONE→IDLE, WAITING→RESUMING"
```

---

## Task 2: Add `TodoProgress`, `TodoItem`, `TodoStatus` to `ContentBlock.kt`

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/ui/sessiondetail/ContentBlock.kt`

### Step 1: Rewrite `ContentBlock.kt`

```kotlin
package com.vela.app.ui.sessiondetail

/** Typed content blocks parsed from an amplifierd assistant message. */
sealed class ContentBlock {
    /** Regular markdown text */
    data class Text(val markdown: String) : ContentBlock()
    /** Internal reasoning — shown as compact inline strip */
    data class Thinking(val text: String) : ContentBlock()
    /** Tool invocation card — isRunning=true while in-flight, false after result arrives */
    data class ToolUse(
        val id: String,
        val name: String,
        val inputJson: String,
        val isRunning: Boolean = true,
    ) : ContentBlock()
    /** Tool result (paired with ToolUse by id) */
    data class ToolResult(
        val toolUseId: String,
        val output: String,
        val isError: Boolean = false,
    ) : ContentBlock()
    /**
     * Live todo progress widget — renders as TodoProgressCard instead of a generic tool block.
     * Updated in-place: only one TodoProgress per turn; last state wins.
     * The activeForm of the IN_PROGRESS item is surfaced in the foreground service notification.
     */
    data class TodoProgress(val todos: List<TodoItem>) : ContentBlock()
}

/** Status of a single todo list item. */
enum class TodoStatus { PENDING, IN_PROGRESS, COMPLETED }

/** A single item in a todo list tool call. */
data class TodoItem(
    val content: String,
    val status: TodoStatus,
    /** Present-continuous form of the task, e.g. "Running tests" — used in notification text. */
    val activeForm: String,
)
```

### Step 2: Verify compilation

```bash
cd /Users/ken/workspace/vela && ./gradlew :app:compileDebugKotlin 2>&1 | grep -E "error:|BUILD"
```

Expected: `BUILD SUCCESSFUL`

### Step 3: Commit

```bash
git add app/src/main/kotlin/com/vela/app/ui/sessiondetail/ContentBlock.kt
git commit -m "feat(models): add ContentBlock.TodoProgress, TodoItem, TodoStatus"
```

---

## Task 3: Create `SessionState.kt`

**Files:**
- Create: `app/src/main/kotlin/com/vela/app/streaming/SessionState.kt`

### Step 1: Create the file

```kotlin
package com.vela.app.streaming

import com.vela.app.ui.sessiondetail.SessionStatus
import com.vela.app.ui.sessiondetail.TurnContent

/**
 * Unified, source-of-truth data model for a single amplifierd session.
 *
 * Produced and owned by [SessionStreamingManagerImpl].
 * ViewModels subscribe via [SessionStreamingManager.getSessionFlow].
 * Both transcript loads and live SSE events normalize to this model.
 */
data class SessionState(
    val sessionId: String,
    /** ID of the SshNode (amplifierd instance) that owns this session. */
    val nodeId: String,
    val status: SessionStatus,
    /** Full turn history — same structure whether loaded from transcript or built from SSE. */
    val turns: List<TurnContent>,
    /** Index into [turns] of the currently-streaming assistant turn, null if not streaming. */
    val activeTurnIndex: Int?,
    /** Non-null while the session is waiting for user approval. Cleared on orchestrator:complete. */
    val pendingApproval: ApprovalRequest?,
    /** Last message sent by the user — stored for retry support. */
    val lastUserMessage: String?,
    /** activeForm of the currently in-progress todo item — drives the foreground notification text. */
    val currentTodoActiveForm: String?,
    /** Project name for notification titles and session card subtitles. */
    val projectName: String?,
)

/** Pending approval request surfaced by the amplifierd approval:request SSE event. */
data class ApprovalRequest(
    val id: String,
    val question: String,
)
```

### Step 2: Verify compilation

```bash
cd /Users/ken/workspace/vela && ./gradlew :app:compileDebugKotlin 2>&1 | grep -E "error:|BUILD"
```

Expected: `BUILD SUCCESSFUL`

### Step 3: Commit

```bash
git add app/src/main/kotlin/com/vela/app/streaming/SessionState.kt
git commit -m "feat(streaming): add SessionState unified data model"
```

---

## Task 4: Create `SessionStreamingManager.kt` interface

**Files:**
- Create: `app/src/main/kotlin/com/vela/app/streaming/SessionStreamingManager.kt`

### Step 1: Create the file

```kotlin
package com.vela.app.streaming

import kotlinx.coroutines.flow.StateFlow

/**
 * Interface that ViewModels use to interact with session streaming.
 *
 * Implemented by [SessionStreamingManagerImpl] (@Singleton, backed by [SessionStreamingService]).
 * ViewModels must NEVER open SSE connections directly — all streaming goes through this interface.
 *
 * Lifecycle:
 *  - Call [startStreaming] when entering the session detail screen.
 *  - Call [stopStreaming] when leaving permanently (the flow survives ViewModel death on its own).
 *  - Call [resumeSession] if [SessionState.status] == IDLE to re-activate the session.
 *  - Call [sendMessage] to submit a new user prompt.
 *  - Call [retryLastMessage] from the ERROR retry button.
 */
interface SessionStreamingManager {

    /**
     * Returns a StateFlow for the given session.
     * Emits null until [startStreaming] has been called for this sessionId.
     * The flow continues emitting after ViewModel destruction — resubscribe on re-entry.
     */
    fun getSessionFlow(sessionId: String): StateFlow<SessionState?>

    /**
     * Returns a StateFlow of ALL known session states (keyed by sessionId).
     * Consumed by SessionListViewModel for live status chips and by SessionStreamingService
     * for foreground lifecycle management.
     */
    fun getAllSessionFlows(): StateFlow<Map<String, SessionState>>

    /**
     * Loads the transcript and opens the SSE event subscription for [sessionId].
     * Idempotent — cancels any existing stream before starting a new one.
     *
     * @param sessionId   The amplifierd session UUID.
     * @param nodeId      The SshNode ID whose URL/token to use.
     * @param projectName Optional project name for notification text.
     */
    suspend fun startStreaming(sessionId: String, nodeId: String, projectName: String?)

    /** Cancel the SSE subscription for [sessionId] and free associated resources. */
    fun stopStreaming(sessionId: String)

    /**
     * POST /sessions/{id}/resume.
     * Idempotent — safe to call on already-live sessions.
     * Returns true if the server accepted the request.
     */
    suspend fun resumeSession(sessionId: String): Boolean

    /**
     * Re-send [SessionState.lastUserMessage] via execute/stream.
     * Transitions ERROR → EXECUTING.
     * Returns false if there is no stored last message.
     */
    suspend fun retryLastMessage(sessionId: String): Boolean

    /**
     * Submit [message] to the session via POST /sessions/{id}/execute/stream.
     * The already-open SSE subscription will receive the resulting events.
     * Stores [message] as [SessionState.lastUserMessage] for retry support.
     */
    suspend fun sendMessage(sessionId: String, message: String): Boolean
}
```

### Step 2: Verify compilation

```bash
cd /Users/ken/workspace/vela && ./gradlew :app:compileDebugKotlin 2>&1 | grep -E "error:|BUILD"
```

Expected: `BUILD SUCCESSFUL`

### Step 3: Commit

```bash
git add app/src/main/kotlin/com/vela/app/streaming/SessionStreamingManager.kt
git commit -m "feat(streaming): add SessionStreamingManager interface"
```

---

## Task 5: Add API methods to `AmplifierdClient` + `subscribeEvents` to `AmplifierdStreamClient`

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/amplifierd/AmplifierdClient.kt`
- Modify: `app/src/main/kotlin/com/vela/app/amplifierd/AmplifierdStreamClient.kt`

### Step 1: Add three methods to `AmplifierdClient.kt`

Insert the following three methods inside `AmplifierdClient`, after the existing `approveSession` method (around line 396):

```kotlin
    /**
     * GET /sessions/:id/transcript — raw JSON string.
     * Used by SessionTranscriptNormalizer for full content-block parsing.
     */
    suspend fun getTranscriptJson(sessionId: String): String =
        get("/sessions/$sessionId/transcript")

    /**
     * POST /sessions/:id/resume — wake a dormant or live-idle session.
     * Idempotent: safe to call on already-running sessions.
     * Returns true if the server accepted (2xx), false on any error.
     */
    suspend fun resumeSession(sessionId: String): Boolean {
        return try {
            post("/sessions/$sessionId/resume", org.json.JSONObject())
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * POST /sessions/:id/execute/stream — submit a prompt for streaming execution.
     * The caller is responsible for having an open SSE subscription via
     * [AmplifierdStreamClient.subscribeEvents] to receive the resulting events.
     * Returns the raw response body (contains correlation_id).
     */
    suspend fun executeStream(sessionId: String, message: String): String {
        val body = org.json.JSONObject().apply { put("prompt", message) }
        return post("/sessions/$sessionId/execute/stream", body)
    }
```

### Step 2: Add `subscribeEvents` to `AmplifierdStreamClient.kt`

Append the following function inside `AmplifierdStreamClient`, after the closing brace of `stream()` (before the final `}`):

```kotlin
    /**
     * Subscribe to a session's event stream WITHOUT sending a new prompt.
     *
     * Useful for [SessionStreamingManagerImpl] to watch an existing or idle session
     * for live events (approval requests, turn completions, etc.).
     *
     * IMPORTANT: amplifierd replays all past events from seq 1 when subscribing.
     * Handle emitted events idempotently. Replayed events are harmless because:
     *  - TextDelta/TextBlock: guarded by activeTurnIndex != null (null after transcript load)
     *  - ApprovalRequest: only applied by normalizer when status == EXECUTING
     *  - orchestrator:complete: sets status IDLE, which is idempotent
     */
    fun subscribeEvents(sessionId: String): Flow<StreamEvent> = flow {
        Log.d(TAG, "subscribeEvents: opening SSE for session=$sessionId")

        val eventsRequest = Request.Builder()
            .url("$baseUrl/events?session=$sessionId")
            .header("x-amplifier-token", token)
            .header("Accept", "text/event-stream")
            .get()
            .build()

        val sseResponse = http.newCall(eventsRequest).execute()
        if (!sseResponse.isSuccessful) {
            Log.e(TAG, "subscribeEvents: GET /events failed HTTP ${sseResponse.code}")
            emit(StreamEvent.Error("Events stream failed: HTTP ${sseResponse.code}"))
            return@flow
        }
        Log.d(TAG, "subscribeEvents: SSE connection open (${sseResponse.code})")

        val source = sseResponse.body?.source() ?: run {
            emit(StreamEvent.Error("Empty SSE response body"))
            return@flow
        }

        var currentEventName = ""
        var isDone = false

        while (!isDone && !source.exhausted()) {
            val line = source.readUtf8Line() ?: break
            when {
                line.startsWith("event: ") -> currentEventName = line.removePrefix("event: ").trim()
                line.startsWith("data: ")  -> {
                    val dataStr = line.removePrefix("data: ").trim()
                    if (dataStr == "[DONE]") { emit(StreamEvent.Done); isDone = true; break }
                    try {
                        val obj     = JSONObject(dataStr)
                        val dataObj = obj.optJSONObject("data") ?: JSONObject()
                        val event: StreamEvent? = when (currentEventName) {
                            "execution:start"               -> StreamEvent.Thinking
                            "content_block:delta"           -> {
                                val token      = dataObj.optString("token", "")
                                val blockIndex = dataObj.optInt("block_index", 0)
                                if (token.isNotEmpty()) StreamEvent.TextDelta(token, blockIndex) else null
                            }
                            "content_block:end"             -> {
                                val block      = dataObj.optJSONObject("block")
                                val blockIndex = dataObj.optInt("block_index", 0)
                                when (block?.optString("type")) {
                                    "text"     -> {
                                        val text = block.optString("text", "")
                                        if (text.isNotBlank()) StreamEvent.TextBlock(text, blockIndex) else null
                                    }
                                    "tool_use" -> StreamEvent.ToolUse(
                                        id        = block.optString("id", ""),
                                        name      = block.optString("name", ""),
                                        inputJson = block.optJSONObject("input")?.toString() ?: "{}",
                                    )
                                    else       -> null
                                }
                            }
                            "provider:retry"                -> StreamEvent.ProviderRetry(
                                attempt      = dataObj.optInt("attempt", 1),
                                maxRetries   = dataObj.optInt("max_retries", 5),
                                errorMessage = dataObj.optString("error_message", "Connection error"),
                                delaySecs    = dataObj.optDouble("delay", 0.0),
                            )
                            "execution:end",
                            "orchestrator:complete"         -> {
                                Log.d(TAG, "subscribeEvents: Done from $currentEventName")
                                emit(StreamEvent.Done)
                                isDone = true
                                null
                            }
                            "approval:request",
                            "approval_request"              -> StreamEvent.ApprovalRequest(
                                id       = dataObj.optString("approval_id", dataObj.optString("id", "")),
                                question = dataObj.optString("question", ""),
                                context  = dataObj.optString("context", ""),
                            )
                            "session:named",
                            "hooks:session-naming:complete" -> {
                                val name = dataObj.optString("name", dataObj.optString("session_name", ""))
                                if (name.isNotBlank()) StreamEvent.Named(name) else null
                            }
                            else                            -> null
                        }
                        event?.let { emit(it) }
                    } catch (e: Exception) {
                        Log.w(TAG, "subscribeEvents: parse error: ${e.message}")
                    }
                }
                line.isBlank()            -> currentEventName = ""
            }
        }

        Log.d(TAG, "subscribeEvents: loop exited isDone=$isDone")
        if (!isDone) emit(StreamEvent.Done)
    }.flowOn(Dispatchers.IO)
```

### Step 3: Verify compilation

```bash
cd /Users/ken/workspace/vela && ./gradlew :app:compileDebugKotlin 2>&1 | grep -E "error:|BUILD"
```

Expected: `BUILD SUCCESSFUL`

### Step 4: Commit

```bash
git add app/src/main/kotlin/com/vela/app/amplifierd/AmplifierdClient.kt \
        app/src/main/kotlin/com/vela/app/amplifierd/AmplifierdStreamClient.kt
git commit -m "feat(amplifierd): add getTranscriptJson, resumeSession, executeStream, subscribeEvents"
```

---

## Task 6: Create `SessionTranscriptNormalizer.kt`

**Files:**
- Create: `app/src/main/kotlin/com/vela/app/streaming/SessionTranscriptNormalizer.kt`

### Step 1: Create the file

This replaces the logic in `AmplifierdClient.getTranscriptWithBlocks()` with a version that: (a) takes raw JSON string, (b) handles `todo` tool calls as `ContentBlock.TodoProgress` instead of `ContentBlock.ToolUse`.

```kotlin
package com.vela.app.streaming

import com.vela.app.ui.sessiondetail.ContentBlock
import com.vela.app.ui.sessiondetail.TodoItem
import com.vela.app.ui.sessiondetail.TodoStatus
import com.vela.app.ui.sessiondetail.TurnContent
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Converts the amplifierd transcript API response (GET /sessions/:id/transcript)
 * into the unified List<TurnContent> model used by [SessionState].
 *
 * Amplifierd transcript format (verified 2026-05-01):
 *  - assistant tool calls: type = "tool_call" (NOT "tool_use")
 *  - tool results:         role = "tool" with string content
 *  - tool input:           "input" JSONObject
 *
 * Special handling: `todo` tool calls produce [ContentBlock.TodoProgress]
 * instead of [ContentBlock.ToolUse], so they render as the todo widget.
 */
@Singleton
class SessionTranscriptNormalizer @Inject constructor() {

    fun normalize(transcriptJson: String): List<TurnContent> {
        return try {
            val messages = JSONObject(transcriptJson).optJSONArray("messages")
                ?: return emptyList()
            buildTurns(messages)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun buildTurns(messages: JSONArray): List<TurnContent> {
        val result = mutableListOf<TurnContent>()
        var i = 0
        while (i < messages.length()) {
            val msg  = messages.getJSONObject(i)
            val role = msg.optString("role")
            when (role) {
                "user"      -> {
                    val content = msg.opt("content")
                    if (content is String && content.isNotBlank()) {
                        result.add(TurnContent(text = content, isUser = true))
                    }
                    i++
                }
                "assistant" -> {
                    val (turn, nextIdx) = parseAssistantTurn(messages, i)
                    turn?.let { result.add(it) }
                    i = nextIdx
                }
                else        -> i++
            }
        }
        return result
    }

    /**
     * Parse an assistant message and any immediately following role="tool" result messages.
     * Returns the assembled [TurnContent] and the index of the next unprocessed message.
     */
    private fun parseAssistantTurn(messages: JSONArray, startIdx: Int): Pair<TurnContent?, Int> {
        val msg        = messages.getJSONObject(startIdx)
        val contentArr = msg.opt("content")
        val blocks     = mutableListOf<ContentBlock>()
        var plainText  = ""

        when {
            contentArr is JSONArray -> {
                for (j in 0 until contentArr.length()) {
                    val block = contentArr.getJSONObject(j)
                    when (block.optString("type")) {
                        "text"              -> {
                            val t = block.optString("text", "")
                            if (t.isNotBlank()) {
                                blocks.add(ContentBlock.Text(t))
                                if (plainText.isBlank()) plainText = t
                            }
                        }
                        "thinking"          -> {
                            val t = block.optString("thinking", "")
                            if (t.isNotBlank()) blocks.add(ContentBlock.Thinking(t))
                        }
                        "tool_call",
                        "tool_use"          -> {
                            val name      = block.optString("name")
                            val inputJson = block.optJSONObject("input")?.toString() ?: "{}"
                            blocks.add(
                                if (name == "todo") parseTodoBlock(inputJson)
                                else ContentBlock.ToolUse(
                                    id        = block.optString("id"),
                                    name      = name,
                                    inputJson = inputJson,
                                    isRunning = false,   // from transcript — tool has completed
                                )
                            )
                        }
                    }
                }
            }
            contentArr is String && contentArr.isNotBlank() -> {
                plainText = contentArr
                blocks.add(ContentBlock.Text(contentArr))
            }
        }

        // Collect following role="tool" messages — each corresponds (by position) to a ToolUse block
        val toolUses = blocks.filterIsInstance<ContentBlock.ToolUse>()
        var k        = startIdx + 1
        var toolIdx  = 0
        while (k < messages.length() && toolIdx < toolUses.size) {
            val next = messages.getJSONObject(k)
            if (next.optString("role") != "tool") break
            val output = when (val c = next.opt("content")) {
                is String    -> c
                is JSONArray -> (0 until c.length()).joinToString("\n") { idx ->
                    val item = c.getJSONObject(idx)
                    if (item.optString("type") == "text") item.optString("text") else ""
                }
                else         -> ""
            }
            blocks.add(ContentBlock.ToolResult(
                toolUseId = toolUses[toolIdx].id,
                output    = output,
                isError   = next.optBoolean("is_error", false),
            ))
            toolIdx++
            k++
        }

        if (blocks.isEmpty() && plainText.isBlank()) return Pair(null, k)
        return Pair(
            TurnContent(text = plainText, isUser = false, contentBlocks = blocks),
            k,
        )
    }

    /** Parse a todo tool call's input JSON into a [ContentBlock.TodoProgress]. */
    internal fun parseTodoBlock(inputJson: String): ContentBlock.TodoProgress {
        return try {
            val todosArr = JSONObject(inputJson).optJSONArray("todos")
                ?: return ContentBlock.TodoProgress(emptyList())
            val todos = (0 until todosArr.length()).map { i ->
                val todo = todosArr.getJSONObject(i)
                TodoItem(
                    content    = todo.optString("content", ""),
                    status     = when (todo.optString("status", "pending")) {
                        "in_progress" -> TodoStatus.IN_PROGRESS
                        "completed"   -> TodoStatus.COMPLETED
                        else          -> TodoStatus.PENDING
                    },
                    activeForm = todo.optString("activeForm", todo.optString("active_form", "")),
                )
            }
            ContentBlock.TodoProgress(todos)
        } catch (_: Exception) {
            ContentBlock.TodoProgress(emptyList())
        }
    }
}
```

### Step 2: Verify compilation

```bash
cd /Users/ken/workspace/vela && ./gradlew :app:compileDebugKotlin 2>&1 | grep -E "error:|BUILD"
```

Expected: `BUILD SUCCESSFUL`

### Step 3: Commit

```bash
git add app/src/main/kotlin/com/vela/app/streaming/SessionTranscriptNormalizer.kt
git commit -m "feat(streaming): add SessionTranscriptNormalizer (transcript JSON → TurnContent)"
```

---

## Task 7: Create `SessionSseNormalizer.kt`

**Files:**
- Create: `app/src/main/kotlin/com/vela/app/streaming/SessionSseNormalizer.kt`

### Step 1: Create the file

```kotlin
package com.vela.app.streaming

import com.vela.app.amplifierd.StreamEvent
import com.vela.app.ui.sessiondetail.ContentBlock
import com.vela.app.ui.sessiondetail.SessionStatus
import com.vela.app.ui.sessiondetail.TodoItem
import com.vela.app.ui.sessiondetail.TodoStatus
import com.vela.app.ui.sessiondetail.TurnContent
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pure function: applies a single [StreamEvent] to a [SessionState] and returns the new state.
 * No side effects — notification posting and service lifecycle happen in [SessionStreamingService].
 *
 * Event → state mapping:
 *  execution:start        → add new streaming turn, status = EXECUTING
 *  content_block:delta    → append token to active turn's last Text block
 *  content_block:end/text → replace active turn's last Text block with authoritative text
 *  content_block:end/tool → append ToolUse block (or replace TodoProgress in-place)
 *  approval:request       → set pendingApproval (only when EXECUTING, to ignore replays)
 *  orchestrator:complete  → status = IDLE, clear activeTurnIndex + pendingApproval
 *  provider:retry         → state unchanged (triggers notification externally)
 *  error                  → status = ERROR, clear activeTurnIndex
 */
@Singleton
class SessionSseNormalizer @Inject constructor() {

    fun applyEvent(state: SessionState, event: StreamEvent): SessionState = when (event) {

        // execution:start — new assistant turn beginning
        StreamEvent.Thinking -> {
            val newTurn = TurnContent(text = "", isUser = false, isStreaming = true)
            state.copy(
                status          = SessionStatus.EXECUTING,
                turns           = state.turns + newTurn,
                activeTurnIndex = state.turns.size,
            )
        }

        // Per-token streaming delta — append to the last Text block in the active turn
        is StreamEvent.TextDelta -> {
            val idx     = state.activeTurnIndex ?: return state
            val turns   = state.turns.toMutableList()
            val current = turns.getOrNull(idx) ?: return state
            val blocks  = current.contentBlocks.toMutableList()
            val lastTextIdx = blocks.indexOfLast { it is ContentBlock.Text }
            if (lastTextIdx >= 0) {
                val prev = blocks[lastTextIdx] as ContentBlock.Text
                blocks[lastTextIdx] = prev.copy(markdown = prev.markdown + event.token)
            } else {
                blocks.add(ContentBlock.Text(event.token))
            }
            turns[idx] = current.copy(text = current.text + event.token, contentBlocks = blocks)
            state.copy(turns = turns)
        }

        // content_block:end with type=text — authoritative final text replaces streaming text
        is StreamEvent.TextBlock -> {
            val idx     = state.activeTurnIndex ?: return state
            val turns   = state.turns.toMutableList()
            val current = turns.getOrNull(idx) ?: return state
            val blocks  = current.contentBlocks.toMutableList()
            val lastTextIdx = blocks.indexOfLast { it is ContentBlock.Text }
            if (lastTextIdx >= 0) {
                blocks[lastTextIdx] = ContentBlock.Text(event.text)
            } else {
                blocks.add(ContentBlock.Text(event.text))
            }
            turns[idx] = current.copy(text = event.text, contentBlocks = blocks)
            state.copy(turns = turns)
        }

        // content_block:end with type=tool_use — append tool block (todo: replace in-place)
        is StreamEvent.ToolUse -> {
            val idx     = state.activeTurnIndex ?: return state
            val turns   = state.turns.toMutableList()
            val current = turns.getOrNull(idx) ?: return state
            val blocks  = current.contentBlocks.toMutableList()

            val newBlock: ContentBlock = if (event.name == "todo") {
                parseTodoInput(event.inputJson)
            } else {
                ContentBlock.ToolUse(
                    id        = event.id,
                    name      = event.name,
                    inputJson = event.inputJson,
                    isRunning = true,
                )
            }

            // For todo: replace existing TodoProgress in this turn (only one per turn; last wins)
            if (newBlock is ContentBlock.TodoProgress) {
                val todoIdx = blocks.indexOfFirst { it is ContentBlock.TodoProgress }
                if (todoIdx >= 0) blocks[todoIdx] = newBlock else blocks.add(newBlock)
            } else {
                blocks.add(newBlock)
            }

            // Extract activeForm of IN_PROGRESS item for the foreground notification
            val todoActiveForm = (newBlock as? ContentBlock.TodoProgress)
                ?.todos?.firstOrNull { it.status == TodoStatus.IN_PROGRESS }?.activeForm

            turns[idx] = current.copy(contentBlocks = blocks)
            state.copy(
                turns                 = turns,
                currentTodoActiveForm = todoActiveForm ?: state.currentTodoActiveForm,
            )
        }

        // Approval request — only applied when EXECUTING (guards against SSE replay)
        is StreamEvent.ApprovalRequest -> {
            if (state.status != SessionStatus.EXECUTING) return state
            state.copy(
                pendingApproval = ApprovalRequest(id = event.id, question = event.question),
            )
        }

        // Provider retry — state unchanged; notification posted by SessionStreamingService
        is StreamEvent.ProviderRetry -> state

        // Session named — no state mutation; handled in ViewModel layer (Phase 2)
        is StreamEvent.Named -> state

        // orchestrator:complete or execution:end
        StreamEvent.Done -> {
            val idx   = state.activeTurnIndex
            val turns = if (idx != null) {
                state.turns.toMutableList().also { list ->
                    list.getOrNull(idx)?.let { t -> list[idx] = t.copy(isStreaming = false) }
                }
            } else {
                state.turns
            }
            state.copy(
                status          = SessionStatus.IDLE,
                activeTurnIndex = null,
                turns           = turns,
                pendingApproval = null,
            )
        }

        is StreamEvent.Error -> state.copy(
            status          = SessionStatus.ERROR,
            activeTurnIndex = null,
        )
    }

    private fun parseTodoInput(inputJson: String): ContentBlock.TodoProgress {
        return try {
            val todosArr = JSONObject(inputJson).optJSONArray("todos")
                ?: return ContentBlock.TodoProgress(emptyList())
            val todos = (0 until todosArr.length()).map { i ->
                val todo = todosArr.getJSONObject(i)
                TodoItem(
                    content    = todo.optString("content", ""),
                    status     = when (todo.optString("status", "pending")) {
                        "in_progress" -> TodoStatus.IN_PROGRESS
                        "completed"   -> TodoStatus.COMPLETED
                        else          -> TodoStatus.PENDING
                    },
                    activeForm = todo.optString("activeForm", todo.optString("active_form", "")),
                )
            }
            ContentBlock.TodoProgress(todos)
        } catch (_: Exception) {
            ContentBlock.TodoProgress(emptyList())
        }
    }
}
```

### Step 2: Verify compilation

```bash
cd /Users/ken/workspace/vela && ./gradlew :app:compileDebugKotlin 2>&1 | grep -E "error:|BUILD"
```

Expected: `BUILD SUCCESSFUL`

### Step 3: Commit

```bash
git add app/src/main/kotlin/com/vela/app/streaming/SessionSseNormalizer.kt
git commit -m "feat(streaming): add SessionSseNormalizer (StreamEvent → SessionState mutations)"
```

---

## Task 8: Create `SessionStreamingManagerImpl.kt`

**Files:**
- Create: `app/src/main/kotlin/com/vela/app/streaming/SessionStreamingManagerImpl.kt`

### Step 1: Create the file

```kotlin
package com.vela.app.streaming

import android.util.Log
import com.vela.app.amplifierd.AmplifierdRepository
import com.vela.app.ssh.SshNodeRegistry
import com.vela.app.ui.sessiondetail.SessionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton implementation of [SessionStreamingManager].
 *
 * Owns all SSE subscriptions and [SessionState] flows for the lifetime of the app process.
 * ViewModels never open SSE connections — they subscribe to [getSessionFlow].
 * [SessionStreamingService] watches [getAllSessionFlows] to manage foreground state + notifications.
 *
 * Threading: [sessionFlows] and [streamJobs] use ConcurrentHashMap for safe cross-thread access.
 * The internal coroutine scope uses [SupervisorJob] so one failed stream doesn't cancel others.
 */
@Singleton
class SessionStreamingManagerImpl @Inject constructor(
    private val amplifierd: AmplifierdRepository,
    private val nodeRegistry: SshNodeRegistry,
    private val transcriptNormalizer: SessionTranscriptNormalizer,
    private val sseNormalizer: SessionSseNormalizer,
) : SessionStreamingManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── State storage ─────────────────────────────────────────────────────────

    /** All known session states, keyed by sessionId. Drives the session list + service lifecycle. */
    private val _allFlows = MutableStateFlow<Map<String, SessionState>>(emptyMap())
    override fun getAllSessionFlows(): StateFlow<Map<String, SessionState>> = _allFlows.asStateFlow()

    /** Per-session nullable StateFlow — null until startStreaming has been called. */
    private val sessionFlows = ConcurrentHashMap<String, MutableStateFlow<SessionState?>>()

    /** Coroutine jobs for active SSE subscriptions, one per sessionId. */
    private val streamJobs = ConcurrentHashMap<String, Job>()

    // ── Interface implementation ───────────────────────────────────────────────

    override fun getSessionFlow(sessionId: String): StateFlow<SessionState?> =
        sessionFlows.getOrPut(sessionId) { MutableStateFlow(null) }.asStateFlow()

    override suspend fun startStreaming(sessionId: String, nodeId: String, projectName: String?) {
        stopStreaming(sessionId) // cancel any existing subscription first

        val node         = nodeRegistry.cache.find { it.id == nodeId } ?: run {
            Log.w(TAG, "startStreaming: node $nodeId not found in registry cache")
            return
        }
        val client       = amplifierd.clientForNode(node) ?: return
        val streamClient = amplifierd.streamClientForNode(node) ?: return

        // 1. Load transcript to populate initial turns (user sees history immediately)
        val transcriptJson = try {
            client.getTranscriptJson(sessionId)
        } catch (e: Exception) {
            Log.w(TAG, "startStreaming: transcript load failed for $sessionId — ${e.message}")
            null
        }
        val initialTurns    = transcriptJson?.let { transcriptNormalizer.normalize(it) } ?: emptyList()
        val lastUserMessage = initialTurns.lastOrNull { it.isUser }?.text

        updateState(
            sessionId, SessionState(
                sessionId             = sessionId,
                nodeId                = nodeId,
                status                = SessionStatus.IDLE,
                turns                 = initialTurns,
                activeTurnIndex       = null,
                pendingApproval       = null,
                lastUserMessage       = lastUserMessage,
                currentTodoActiveForm = null,
                projectName           = projectName,
            )
        )

        // 2. Open SSE subscription — process live events as they arrive
        val job = scope.launch {
            try {
                streamClient.subscribeEvents(sessionId).collect { event ->
                    val current = sessionFlows[sessionId]?.value ?: return@collect
                    val updated = sseNormalizer.applyEvent(current, event)
                    updateState(sessionId, updated)
                }
            } catch (e: Exception) {
                Log.e(TAG, "startStreaming: SSE stream error for $sessionId", e)
                sessionFlows[sessionId]?.update { s -> s?.copy(status = SessionStatus.ERROR) }
            }
        }
        streamJobs[sessionId] = job
        Log.d(TAG, "startStreaming: streaming started for $sessionId on node $nodeId")
    }

    override fun stopStreaming(sessionId: String) {
        streamJobs.remove(sessionId)?.cancel()
        Log.d(TAG, "stopStreaming: cancelled stream for $sessionId")
    }

    override suspend fun resumeSession(sessionId: String): Boolean {
        val nodeId  = sessionFlows[sessionId]?.value?.nodeId ?: return false
        val node    = nodeRegistry.cache.find { it.id == nodeId } ?: return false
        val client  = amplifierd.clientForNode(node) ?: return false
        val success = client.resumeSession(sessionId)
        Log.d(TAG, "resumeSession: $sessionId → success=$success")
        return success
    }

    override suspend fun sendMessage(sessionId: String, message: String): Boolean {
        val state  = sessionFlows[sessionId]?.value ?: return false
        val node   = nodeRegistry.cache.find { it.id == state.nodeId } ?: return false
        val client = amplifierd.clientForNode(node) ?: return false

        // Optimistically update status and store the message for retry support
        updateState(sessionId, state.copy(
            lastUserMessage = message,
            status          = SessionStatus.EXECUTING,
        ))

        return try {
            client.executeStream(sessionId, message)
            Log.d(TAG, "sendMessage: accepted for $sessionId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "sendMessage: failed for $sessionId — ${e.message}")
            updateState(sessionId, state.copy(status = SessionStatus.ERROR))
            false
        }
    }

    override suspend fun retryLastMessage(sessionId: String): Boolean {
        val message = sessionFlows[sessionId]?.value?.lastUserMessage
        if (message.isNullOrBlank()) {
            Log.w(TAG, "retryLastMessage: no stored message for $sessionId")
            return false
        }
        return sendMessage(sessionId, message)
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun updateState(sessionId: String, state: SessionState) {
        sessionFlows.getOrPut(sessionId) { MutableStateFlow(null) }.value = state
        _allFlows.update { current -> current + (sessionId to state) }
    }

    companion object {
        private const val TAG = "SessionStreamingMgr"
    }
}
```

### Step 2: Verify compilation

```bash
cd /Users/ken/workspace/vela && ./gradlew :app:compileDebugKotlin 2>&1 | grep -E "error:|BUILD"
```

Expected: `BUILD SUCCESSFUL`

### Step 3: Commit

```bash
git add app/src/main/kotlin/com/vela/app/streaming/SessionStreamingManagerImpl.kt
git commit -m "feat(streaming): add SessionStreamingManagerImpl (@Singleton SSE + state owner)"
```

---

## Task 9: Create `SessionStreamingService.kt`

**Files:**
- Create: `app/src/main/kotlin/com/vela/app/streaming/SessionStreamingService.kt`

### Step 1: Create the file

```kotlin
package com.vela.app.streaming

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.vela.app.MainActivity
import com.vela.app.R
import com.vela.app.notifications.ApprovalNotificationHelper
import com.vela.app.ui.sessiondetail.SessionStatus
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Android Foreground Service that keeps the process alive while sessions are EXECUTING.
 *
 * Responsibilities:
 *  1. Observe [SessionStreamingManagerImpl.getAllSessionFlows] for state changes.
 *  2. Enter foreground (persistent notification) when any session is EXECUTING.
 *  3. Return to standby (no foreground notification) when all sessions are IDLE/ERROR.
 *  4. Post turn-complete, approval, and error notifications on state transitions.
 *
 * Started from [com.vela.app.VelaApplication.onCreate] via startService().
 * Uses START_STICKY so Android restarts it if killed.
 */
@AndroidEntryPoint
class SessionStreamingService : Service() {

    @Inject lateinit var streamingManager: SessionStreamingManagerImpl

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Previous snapshot used to detect state transitions for notification triggers. */
    private var prevStates: Map<String, SessionState> = emptyMap()

    inner class StreamingBinder : Binder() {
        fun getService() = this@SessionStreamingService
    }

    private val binder = StreamingBinder()
    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createChannels()
        scope.launch {
            streamingManager.getAllSessionFlows().collect { sessions ->
                handleStateSnapshot(sessions)
                prevStates = sessions
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ── Foreground / standby ──────────────────────────────────────────────────

    private fun handleStateSnapshot(sessions: Map<String, SessionState>) {
        val executing = sessions.values.filter { it.status == SessionStatus.EXECUTING }
        if (executing.isNotEmpty()) {
            val label = buildRunningLabel(executing.first(), executing.size)
            startForeground(NOTIF_FOREGROUND_ID, buildForegroundNotification(label))
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        // Check for notification-worthy state transitions
        for ((sessionId, newState) in sessions) {
            checkNotifications(prevStates[sessionId], newState)
        }
    }

    private fun buildRunningLabel(session: SessionState, totalExecuting: Int): String {
        if (totalExecuting > 1) return "Vela — $totalExecuting sessions running"
        val todoText = session.currentTodoActiveForm
        val project  = session.projectName
        return when {
            todoText != null && project != null -> "Vela — $todoText · $project"
            todoText != null                    -> "Vela — $todoText"
            project != null                     -> "Vela — Working… · $project"
            else                                -> "Vela — Working…"
        }
    }

    private fun buildForegroundNotification(contentText: String): android.app.Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_RUNNING)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(contentText)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    // ── Notification triggers ─────────────────────────────────────────────────

    private fun checkNotifications(prev: SessionState?, next: SessionState) {
        val sid = next.sessionId

        // Turn complete: EXECUTING → IDLE
        if (prev?.status == SessionStatus.EXECUTING && next.status == SessionStatus.IDLE) {
            ApprovalNotificationHelper.postTurnComplete(
                context         = this,
                sessionId       = sid,
                projectName     = next.projectName ?: "Vela",
                lastUserMessage = next.lastUserMessage,
            )
        }

        // Approval needed: pendingApproval became non-null (or changed)
        val newApproval = next.pendingApproval
        if (newApproval != null && prev?.pendingApproval?.id != newApproval.id) {
            ApprovalNotificationHelper.postApproval(
                context     = this,
                sessionId   = sid,
                projectName = next.projectName ?: "Vela",
                approvalId  = newApproval.id,
                question    = newApproval.question,
            )
        }

        // Error: any → ERROR
        if (prev?.status != SessionStatus.ERROR && next.status == SessionStatus.ERROR) {
            ApprovalNotificationHelper.postError(
                context     = this,
                sessionId   = sid,
                projectName = next.projectName ?: "Vela",
            )
        }
    }

    // ── Channel creation ──────────────────────────────────────────────────────

    private fun createChannels() {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RUNNING,
                "Vela — Active",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Persistent notification while Vela is working" }
        )
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SESSIONS,
                "Vela — Session Events",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Turn complete, approval needed, and error notifications"
                enableVibration(true)
            }
        )
    }

    companion object {
        const val CHANNEL_RUNNING   = "vela_running"
        const val CHANNEL_SESSIONS  = "vela_sessions"
        const val NOTIF_FOREGROUND_ID = 0x5555
    }
}
```

### Step 2: Verify compilation

```bash
cd /Users/ken/workspace/vela && ./gradlew :app:compileDebugKotlin 2>&1 | grep -E "error:|BUILD"
```

Expected: `BUILD SUCCESSFUL`

### Step 3: Commit

```bash
git add app/src/main/kotlin/com/vela/app/streaming/SessionStreamingService.kt
git commit -m "feat(streaming): add SessionStreamingService (foreground service + notification dispatcher)"
```

---

## Task 10: Update `ApprovalNotificationHelper.kt`

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/notifications/ApprovalNotificationHelper.kt`

### Step 1: Rewrite the file

The existing `notify()` method is kept for backward compatibility. Three new methods are added (`postTurnComplete`, `postApproval`, `postError`), plus a `createSessionChannel()` for the new unified sessions channel, plus private helpers extracted from the duplicated permission/intent logic.

```kotlin
package com.vela.app.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.vela.app.MainActivity
import com.vela.app.R

/**
 * Posts Android notifications for session events.
 *
 * Channels:
 *  CHANNEL_APPROVALS ("vela_approvals") — legacy approval channel, HIGH importance.
 *  CHANNEL_SESSIONS  ("vela_sessions")  — turn-complete, approval, error events.
 *
 * The foreground service channel ("vela_running") is created by [SessionStreamingService].
 */
object ApprovalNotificationHelper {

    const val CHANNEL_APPROVALS  = "vela_approvals"
    const val CHANNEL_SESSIONS   = "vela_sessions"

    private const val NOTIFICATION_ID_BASE   = 0x4150   // "AP" — legacy approval IDs
    private const val NOTIF_COMPLETE_BASE    = 0x5500   // turn-complete notifications
    private const val NOTIF_ERROR_BASE       = 0x5600   // error notifications

    // ── Channel creation ──────────────────────────────────────────────────────

    /** Create the legacy approvals channel. Call once from [Application.onCreate]. */
    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_APPROVALS,
            "Vela — Approval Requests",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Notifies when an amplifierd session needs your approval"
            enableVibration(true)
        }
        context.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }

    /** Create the unified session-events channel. Call once from [Application.onCreate]. */
    fun createSessionChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_SESSIONS,
            "Vela — Session Events",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Turn complete, approval needed, and error notifications"
            enableVibration(true)
        }
        context.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }

    // ── Legacy API (kept for backward compat with SessionDetailViewModel) ─────

    /**
     * Post a high-priority notification for an approval request.
     * Kept for ViewModel-level usage until Phase 2 migration is complete.
     */
    fun notify(context: Context, sessionId: String, question: String) {
        if (!hasPermission(context)) return
        val notification = NotificationCompat.Builder(context, CHANNEL_APPROVALS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Vela — Action needed")
            .setContentText(question.take(200))
            .setStyle(NotificationCompat.BigTextStyle().bigText(question))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(tapIntent(context, sessionId))
            .build()
        NotificationManagerCompat.from(context)
            .notify(NOTIFICATION_ID_BASE + sessionId.hashCode(), notification)
    }

    // ── New notification API (used by SessionStreamingService) ────────────────

    /** Post a "turn complete" notification when EXECUTING → IDLE. */
    fun postTurnComplete(
        context: Context,
        sessionId: String,
        projectName: String,
        lastUserMessage: String?,
    ) {
        if (!hasPermission(context)) return
        val body = lastUserMessage?.take(60) ?: ""
        val notification = NotificationCompat.Builder(context, CHANNEL_SESSIONS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("$projectName: Done")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(tapIntent(context, sessionId))
            .build()
        NotificationManagerCompat.from(context)
            .notify(NOTIF_COMPLETE_BASE + sessionId.hashCode(), notification)
    }

    /**
     * Post an "approval needed" notification.
     * Uses CHANNEL_APPROVALS (HIGH importance) so the user gets alerted even if DND.
     */
    fun postApproval(
        context: Context,
        sessionId: String,
        projectName: String,
        approvalId: String,
        question: String,
    ) {
        if (!hasPermission(context)) return
        val notification = NotificationCompat.Builder(context, CHANNEL_APPROVALS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("$projectName: Needs your input")
            .setContentText(question.take(200))
            .setStyle(NotificationCompat.BigTextStyle().bigText(question))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(tapIntent(context, sessionId))
            .build()
        NotificationManagerCompat.from(context)
            .notify(NOTIFICATION_ID_BASE + sessionId.hashCode(), notification)
    }

    /** Post an "error" notification when a session transitions to ERROR. */
    fun postError(
        context: Context,
        sessionId: String,
        projectName: String,
    ) {
        if (!hasPermission(context)) return
        val notification = NotificationCompat.Builder(context, CHANNEL_SESSIONS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("$projectName: Task may have failed")
            .setContentText("Connection error — tap to retry")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(tapIntent(context, sessionId))
            .build()
        NotificationManagerCompat.from(context)
            .notify(NOTIF_ERROR_BASE + sessionId.hashCode(), notification)
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun hasPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    private fun tapIntent(context: Context, sessionId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            sessionId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
```

### Step 2: Verify compilation

```bash
cd /Users/ken/workspace/vela && ./gradlew :app:compileDebugKotlin 2>&1 | grep -E "error:|BUILD"
```

Expected: `BUILD SUCCESSFUL`

### Step 3: Commit

```bash
git add app/src/main/kotlin/com/vela/app/notifications/ApprovalNotificationHelper.kt
git commit -m "feat(notifications): add postTurnComplete, postApproval, postError, vela_sessions channel"
```

---

## Task 11: Update `AppModule.kt` — add Hilt binding for `SessionStreamingManager`

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/di/AppModule.kt`

### Step 1: Add the imports at the top of `AppModule.kt`

Add these imports to the existing import block (after the existing `dagger` imports):

```kotlin
import com.vela.app.streaming.SessionStreamingManager
import com.vela.app.streaming.SessionStreamingManagerImpl
import dagger.Binds
```

### Step 2: Append `StreamingModule` at the bottom of `AppModule.kt` (after the closing `}` of `AppModule`)

```kotlin

/**
 * Hilt module that binds [SessionStreamingManager] → [SessionStreamingManagerImpl].
 *
 * Separate abstract class required because [AppModule] is an `object` (non-abstract).
 * Both are installed in [SingletonComponent] so the binding is app-scoped.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class StreamingModule {

    @Binds
    @Singleton
    abstract fun bindSessionStreamingManager(
        impl: SessionStreamingManagerImpl,
    ): SessionStreamingManager
}
```

### Step 3: Verify compilation

```bash
cd /Users/ken/workspace/vela && ./gradlew :app:compileDebugKotlin 2>&1 | grep -E "error:|BUILD"
```

Expected: `BUILD SUCCESSFUL`

### Step 4: Commit

```bash
git add app/src/main/kotlin/com/vela/app/di/AppModule.kt
git commit -m "feat(di): bind SessionStreamingManager → SessionStreamingManagerImpl via Hilt"
```

---

## Task 12: Update `AndroidManifest.xml`

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`

### Step 1: Add `FOREGROUND_SERVICE_DATA_SYNC` permission

Add this line after the existing `<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />` line (around line 11):

```xml
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
```

### Step 2: Register `SessionStreamingService`

Add this service declaration inside `<application>`, after the existing `RecordingService` entry (around line 86):

```xml
        <service
            android:name=".streaming.SessionStreamingService"
            android:exported="false"
            android:foregroundServiceType="dataSync" />
```

The relevant section of the manifest will look like:

```xml
        <service
            android:name=".recording.RecordingService"
            android:exported="false"
            android:foregroundServiceType="microphone" />

        <service
            android:name=".streaming.SessionStreamingService"
            android:exported="false"
            android:foregroundServiceType="dataSync" />
```

### Step 3: Verify compilation

```bash
cd /Users/ken/workspace/vela && ./gradlew :app:compileDebugKotlin 2>&1 | grep -E "error:|BUILD"
```

Expected: `BUILD SUCCESSFUL`

### Step 4: Commit

```bash
git add app/src/main/AndroidManifest.xml
git commit -m "feat(manifest): register SessionStreamingService with dataSync foreground type"
```

---

## Task 13: Update `VelaApplication.kt` — start service + create channels

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/VelaApplication.kt`

### Step 1: Rewrite `VelaApplication.kt`

```kotlin
package com.vela.app

import android.app.Application
import android.content.Intent
import com.vela.app.notifications.ApprovalNotificationHelper
import com.vela.app.streaming.SessionStreamingService
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.vela.app.server.VelaMiniAppCleaner
import com.vela.app.server.VelaMiniAppServer
import com.vela.app.workers.ProfileWorkerScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class VelaApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var profileWorkerScheduler: ProfileWorkerScheduler

    @Inject
    lateinit var miniAppServer: VelaMiniAppServer

    @Inject
    lateinit var miniAppCleaner: VelaMiniAppCleaner

    override fun onCreate() {
        super.onCreate()

        // Create notification channels before any notification can be posted
        ApprovalNotificationHelper.createChannel(this)
        ApprovalNotificationHelper.createSessionChannel(this)

        // Start the streaming service so it survives across navigation and app backgrounding.
        // The service enters standby (no foreground notification) immediately and only
        // calls startForeground() when a session transitions to EXECUTING.
        startService(Intent(this, SessionStreamingService::class.java))

        profileWorkerScheduler.schedule()
        miniAppCleaner.clearStaleRenderersIfNeeded()
        miniAppServer.start()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
```

### Step 2: Verify compilation

```bash
cd /Users/ken/workspace/vela && ./gradlew :app:compileDebugKotlin 2>&1 | grep -E "error:|BUILD"
```

Expected: `BUILD SUCCESSFUL`

### Step 3: Commit

```bash
git add app/src/main/kotlin/com/vela/app/VelaApplication.kt
git commit -m "feat(app): start SessionStreamingService on launch, create vela_sessions channel"
```

---

## Task 14: Full build, install, and smoke test

### Step 1: Full build, install, and launch

```bash
cd /Users/ken/workspace/vela && \
  ./gradlew assembleDebug -x test && \
  adb connect 10.0.0.106:45299 && \
  adb -s 10.0.0.106:45299 install -r app/build/outputs/apk/debug/app-debug.apk && \
  adb -s 10.0.0.106:45299 shell am force-stop com.vela.app && \
  adb -s 10.0.0.106:45299 shell am start --user 0 -n com.vela.app/.MainActivity
```

### Step 2: Smoke test

Verify the following manually on the device:

1. **App launches without crash** — the session list screen appears.
2. **Session list is visible** — existing sessions are listed (same behavior as before Phase 1).
3. **Session cards render** — status chips show correct labels. Sessions that were previously "RUNNING" now show "EXECUTING"; sessions that were "DONE" now show "IDLE".
4. **No crash on cold start** — check logcat for any Hilt injection errors or missing service registration:
   ```bash
   adb -s 10.0.0.106:45299 logcat -s SessionStreamingMgr:D SessionStreamingService:D | head -30
   ```
5. **Service starts** — verify in logcat that `SessionStreamingService` started (no `IllegalStateException: Not allowed to start service Intent` or similar).
6. **No ANR / no crash** — navigate to a session detail screen and back; app should remain stable.

### Step 3: Final commit

```bash
git add -A
git commit -m "chore: Phase 1 complete — core streaming infrastructure (no UI changes)"
```

---

## Summary of new files created

| File | Purpose |
|------|---------|
| `streaming/SessionState.kt` | Unified data model: `SessionState`, `ApprovalRequest` |
| `streaming/SessionStreamingManager.kt` | Interface exposed to ViewModels |
| `streaming/SessionStreamingManagerImpl.kt` | @Singleton: SSE connections + StateFlow store |
| `streaming/SessionStreamingService.kt` | Android Foreground Service: lifecycle + notifications |
| `streaming/SessionTranscriptNormalizer.kt` | `GET /transcript` JSON → `List<TurnContent>` |
| `streaming/SessionSseNormalizer.kt` | `StreamEvent` → `SessionState` mutations (pure function) |

## What Phase 2 will do (not in this plan)

- Wire `SessionDetailViewModel` to inject and use `SessionStreamingManager` instead of owning its own SSE stream
- Wire `SessionListViewModel` to subscribe to `getAllSessionFlows()` for live status chips
- Implement the auto-resume flow (open session → detect IDLE → call `resumeSession`)
- Build the `TodoProgressCard` Compose component
- Add the retry button to the error state UI
- Add the approval card inline in the chat
