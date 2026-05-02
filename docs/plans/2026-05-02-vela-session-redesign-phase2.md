# Vela Session Redesign — Phase 2: Session Detail Screen

> **For agentic workers:** Use `superpowers:subagent-driven-development` or `execute-plan`. NO TDD — verify via compilation and device deploy only.

**Goal:** Wire `SessionDetailViewModel` to `SessionStreamingManager` (replacing direct SSE), add `TodoProgressCard`, collapsible tool call cards, retry button, inline approval card, and RESUMING input state.

**Architecture:** ViewModel subscribes to `manager.getSessionFlow()` `StateFlow`; all state changes flow through `SessionState`. UI renders `TurnContent` list uniformly regardless of whether data came from transcript or live SSE. Session naming, approval sync, and loading state are all derived from the single `SessionState` source.

**Tech Stack:** Kotlin, Hilt, Jetpack Compose, Material3, Coroutines/StateFlow

---

## Key codebase facts (verified before writing this plan)

- `SessionStatus` enum lives in `com.vela.app.ui.sessiondetail.SessionModels` (same package as ViewModel/Screen)
- `TurnContent` has `toolCalls: List<ToolCall>` (old model) AND `contentBlocks: List<ContentBlock>` (new model) — both coexist; `AgentTurnItem` branches on `contentBlocks.isNotEmpty()`
- `ContentBlock.ToolUse` has `isRunning: Boolean` (default `true`)
- `ContentBlock.ToolResult` has `toolUseId`, `output`, `isError`
- `SessionState` currently has NO `sessionName` field — adding it in Task 1
- `SessionSseNormalizer` line 131 says `is StreamEvent.Named -> state  // handled in ViewModel layer Phase 2` — fixing in Task 1
- `SessionStreamingManagerImpl.sendMessage()` does NOT add a user turn to `state.turns` — fixing in Task 1
- `AgentTurnItem` has `is ContentBlock.TodoProgress -> { /* TODO: render TodoProgressCard */ }` — wiring in Task 3
- Existing `ToolCallBlock` composable in `TurnItems.kt` is already collapsible but uses different styling than the new spec — Task 3 adds a new `CollapsibleToolCard` alongside it (both can coexist)
- `ApprovalNotificationHelper.notify()` is currently called from within the ViewModel's SSE collect block — must be migrated to the new state-collection loop

---

## Task 1: Fix sendMessage() user-turn gap + add sessionName to SessionState

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/streaming/SessionState.kt`
- Modify: `app/src/main/kotlin/com/vela/app/streaming/SessionSseNormalizer.kt`
- Modify: `app/src/main/kotlin/com/vela/app/streaming/SessionStreamingManagerImpl.kt`

### Step 1: Add `sessionName` to `SessionState.kt`

Add `val sessionName: String? = null` as the last field with a default so all existing call sites (which use positional or named args) continue to compile without changes.

In `SessionState.kt`, replace the data class body:

```kotlin
data class SessionState(
    val sessionId: String,
    val nodeId: String,
    val status: SessionStatus,
    val turns: List<TurnContent>,
    val activeTurnIndex: Int?,
    val pendingApproval: ApprovalRequest?,
    val lastUserMessage: String?,
    val currentTodoActiveForm: String?,
    val projectName: String?,
    val sessionName: String? = null,   // ← NEW: set by SessionSseNormalizer on Named event
)
```

Also update the KDoc `@param` block — append below `projectName`:

```kotlin
 * @param sessionName         Human-readable session name set by the `session:named` SSE event;
 *                            null until the naming hook fires. Persisted to amplifierd on change.
```

### Step 2: Handle `Named` event in `SessionSseNormalizer.kt`

Find line 131:
```kotlin
            is StreamEvent.Named -> state           // handled in ViewModel layer Phase 2
```

Replace with:
```kotlin
            is StreamEvent.Named -> state.copy(sessionName = event.name)
```

### Step 3: Fix `sendMessage()` in `SessionStreamingManagerImpl.kt` to add user turn

Find the optimistic update in `sendMessage()` (around line 136):
```kotlin
        // Optimistic update: persist the user message so retry is possible even on failure
        updateState(sessionId, state.copy(lastUserMessage = message, status = SessionStatus.EXECUTING))
```

Replace with:
```kotlin
        // Optimistic update: add user turn to turns list + persist message for retry
        val userTurn = TurnContent(text = message, isUser = true)
        updateState(
            sessionId,
            state.copy(
                lastUserMessage = message,
                status = SessionStatus.EXECUTING,
                turns = state.turns + userTurn,
            ),
        )
```

### Step 4: Compile

```bash
cd /Users/ken/workspace/vela && ./gradlew :app:compileDebugKotlin 2>&1 | grep -E "error:|BUILD"
```

Expected: `BUILD SUCCESSFUL`

### Step 5: Commit

```bash
git add app/src/main/kotlin/com/vela/app/streaming/SessionState.kt \
        app/src/main/kotlin/com/vela/app/streaming/SessionSseNormalizer.kt \
        app/src/main/kotlin/com/vela/app/streaming/SessionStreamingManagerImpl.kt && \
git commit -m "feat(streaming): add sessionName to SessionState, handle Named event, fix sendMessage user turn"
```

---

## Task 2: Create `TodoProgressCard.kt`

**Files:**
- Create: `app/src/main/kotlin/com/vela/app/ui/sessiondetail/TodoProgressCard.kt`

### Step 1: Create the file with the complete composable

```kotlin
package com.vela.app.ui.sessiondetail

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vela.app.ui.theme.VelaColors

// Private color tokens for this widget
private val VelaPurple     = Color(0xFF6750A4)
private val VelaDarkSurface = Color(0xFF252438)
private val VelaMutedPurple = Color(0xFF9A86D2)

/**
 * Structured task-plan widget rendered inside an agent turn when a
 * [ContentBlock.TodoProgress] block is present.
 *
 * Shows COMPLETED items struck-through, the IN_PROGRESS item with a
 * pulsing purple dot and "NOW" badge (when [isStreaming]), and PENDING
 * items as outline circles.
 *
 * Only one [TodoProgressCard] appears per agent turn — the normalizer
 * replaces the previous state in-place, so the list always shows the
 * latest snapshot.
 */
@Composable
fun TodoProgressCard(
    todos: List<TodoItem>,
    isStreaming: Boolean = false,
    modifier: Modifier = Modifier,
) {
    // Always create the transition — only use animated value when isStreaming
    val infiniteTransition = rememberInfiniteTransition(label = "todoPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.45f,
        targetValue  = 1f,
        animationSpec = infiniteRepeatable(
            animation  = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "todoPulse",
    )

    Surface(
        color    = VelaDarkSurface,
        shape    = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier            = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // ── Header ────────────────────────────────────────────────────
            Text(
                text  = "TASK PLAN",
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp),
                color = VelaMutedPurple,
            )

            // ── Items ─────────────────────────────────────────────────────
            todos.forEach { item ->
                Row(
                    verticalAlignment  = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    when (item.status) {

                        // ── COMPLETED ───────────────────────────────────
                        TodoStatus.COMPLETED -> {
                            Icon(
                                imageVector        = Icons.Default.Check,
                                contentDescription = "Done",
                                tint               = VelaPurple,
                                modifier           = Modifier.size(14.dp),
                            )
                            Text(
                                text     = item.content,
                                style    = MaterialTheme.typography.bodySmall.copy(
                                    textDecoration = TextDecoration.LineThrough,
                                ),
                                color    = VelaColors.TextSecondary,
                                modifier = Modifier.weight(1f),
                            )
                        }

                        // ── IN_PROGRESS ─────────────────────────────────
                        TodoStatus.IN_PROGRESS -> {
                            val dotAlpha = if (isStreaming) pulseAlpha else 1f
                            Box(
                                modifier = Modifier
                                    .size(14.dp)
                                    .clip(CircleShape)
                                    .background(VelaPurple.copy(alpha = dotAlpha)),
                            )
                            Text(
                                text     = item.content,
                                style    = MaterialTheme.typography.bodySmall.copy(
                                    fontWeight = FontWeight.Bold,
                                ),
                                color    = Color.White,
                                modifier = Modifier.weight(1f),
                            )
                            // "NOW" badge
                            Surface(
                                color  = VelaPurple,
                                shape  = RoundedCornerShape(6.dp),
                            ) {
                                Text(
                                    text     = "NOW",
                                    style    = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                    color    = Color.White,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                )
                            }
                        }

                        // ── PENDING ─────────────────────────────────────
                        TodoStatus.PENDING -> {
                            Surface(
                                modifier = Modifier.size(14.dp),
                                shape    = CircleShape,
                                color    = Color.Transparent,
                                border   = BorderStroke(1.dp, VelaColors.TextTertiary),
                            ) {}
                            Text(
                                text     = item.content,
                                style    = MaterialTheme.typography.bodySmall,
                                color    = VelaColors.TextTertiary,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}
```

### Step 2: Compile

```bash
cd /Users/ken/workspace/vela && ./gradlew :app:compileDebugKotlin 2>&1 | grep -E "error:|BUILD"
```

Expected: `BUILD SUCCESSFUL`

### Step 3: Commit

```bash
git add app/src/main/kotlin/com/vela/app/ui/sessiondetail/TodoProgressCard.kt && \
git commit -m "feat(ui): add TodoProgressCard composable with pulsing IN_PROGRESS dot and NOW badge"
```

---

## Task 3: Wire `TodoProgressCard` + new `CollapsibleToolCard` in `TurnItems.kt`

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/ui/sessiondetail/TurnItems.kt`

### Step 1: Add `import androidx.compose.ui.graphics.Color` to `TurnItems.kt`

The new `CollapsibleToolCard` uses raw `Color(0xFF...)` values. Add the import after the existing `androidx.compose.ui.unit.sp` import:

```kotlin
import androidx.compose.ui.graphics.Color
```

### Step 2: Add `CollapsibleToolCard` composable to `TurnItems.kt`

Add this new private composable **before** the existing `ToolCallCard` composable (around line 437). It replaces the `ToolCallBlock` call site in `AgentTurnItem` (the existing `ToolCallBlock` function stays in the file for backward compat).

```kotlin
/**
 * Collapsible card for non-delegate [ContentBlock.ToolUse] blocks.
 *
 * Collapsed: single-line chip showing tool name + truncated input + expand chevron.
 * Expanded: full input JSON + result JSON (if [result] is non-null).
 * Shows spinner while in-flight ([block.isRunning] && [result] == null).
 */
@Composable
private fun CollapsibleToolCard(
    block: ContentBlock.ToolUse,
    result: ContentBlock.ToolResult? = null,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val truncatedInput = remember(block.inputJson) {
        block.inputJson.take(60).let { if (block.inputJson.length > 60) "$it…" else it }
    }

    Surface(
        color    = Color(0xFF181825),
        shape    = RoundedCornerShape(8.dp),
        border   = BorderStroke(1.dp, Color(0xFF313244)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            // ── Header row (always visible) ────────────────────────────
            Row(
                modifier          = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text  = "⚙ ${block.name}",
                    style = MaterialTheme.typography.labelSmall,
                    color = VelaColors.TextSecondary,
                )
                if (!expanded) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text     = truncatedInput,
                        style    = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        color    = VelaColors.TextTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.width(4.dp))
                // In-flight spinner
                if (block.isRunning && result == null) {
                    CircularProgressIndicator(
                        modifier    = Modifier.size(10.dp),
                        color       = VelaColors.Running,
                        strokeWidth = 1.5.dp,
                        progress    = { 0.25f },
                    )
                    Spacer(Modifier.width(4.dp))
                }
                // Done / error icon
                if (result != null) {
                    Icon(
                        imageVector        = if (result.isError) Icons.Default.Error
                                             else Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint               = if (result.isError) VelaColors.Error
                                             else VelaColors.Done,
                        modifier           = Modifier.size(12.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Icon(
                    imageVector        = if (expanded) Icons.Default.ExpandLess
                                         else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint               = VelaColors.TextTertiary,
                    modifier           = Modifier.size(14.dp),
                )
            }

            // ── Expanded content ───────────────────────────────────────
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text  = "Input:",
                    style = MaterialTheme.typography.labelSmall,
                    color = VelaColors.TextTertiary,
                )
                Text(
                    text  = block.inputJson,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    color = VelaColors.TextSecondary,
                )
                if (result != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text  = "Result:",
                        style = MaterialTheme.typography.labelSmall,
                        color = VelaColors.TextTertiary,
                    )
                    Text(
                        text  = result.output,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        color = if (result.isError) VelaColors.Error
                                else VelaColors.TextSecondary,
                    )
                }
            }
        }
    }
}
```

### Step 3: Update `AgentTurnItem` — wire `TodoProgressCard` and `CollapsibleToolCard`

Inside `AgentTurnItem`, find the `when (block)` switch in the `contentBlocks.isNotEmpty()` branch. There are two cases to change:

**Change 1:** The `TodoProgress` case (currently a comment). Replace:

```kotlin
                        is ContentBlock.TodoProgress -> { /* TODO: render TodoProgressCard */ }
```

With:

```kotlin
                        is ContentBlock.TodoProgress -> TodoProgressCard(
                            todos       = block.todos,
                            isStreaming = content.isStreaming,
                        )
```

**Change 2:** The `ToolUse` non-delegate case. Replace the call to `ToolCallBlock(...)` (inside the `else` branch of the delegate check) with `CollapsibleToolCard(...)`. The full updated `ToolUse` arm looks like this:

```kotlin
                        is ContentBlock.ToolUse  -> {
                            val result = content.contentBlocks
                                .filterIsInstance<ContentBlock.ToolResult>()
                                .find { it.toolUseId == block.id }
                            // Delegate tool → indented subagent card
                            if (block.name == "delegate" || block.name.contains("delegate", ignoreCase = true)) {
                                DelegateBlock(
                                    inputJson = block.inputJson,
                                    result    = result?.output,
                                    isRunning = block.isRunning,
                                )
                            } else {
                                CollapsibleToolCard(block = block, result = result)
                            }
                        }
```

The `ToolResult` arm is unchanged (`/* rendered via matching ToolUse block above */`).

### Step 4: Compile

```bash
cd /Users/ken/workspace/vela && ./gradlew :app:compileDebugKotlin 2>&1 | grep -E "error:|BUILD"
```

Expected: `BUILD SUCCESSFUL`

### Step 5: Commit

```bash
git add app/src/main/kotlin/com/vela/app/ui/sessiondetail/TurnItems.kt && \
git commit -m "feat(ui): wire TodoProgressCard + CollapsibleToolCard in AgentTurnItem"
```

---

## Task 4: Refactor `SessionDetailViewModel.kt`

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/ui/sessiondetail/SessionDetailViewModel.kt`

This is a full rewrite of the streaming section. Voice, attachments, steer, and approval gate methods are preserved verbatim. The direct-SSE collect loop, `loadTranscript()`, and `awaitNode()` are removed.

### Step 1: Replace the entire file

Write the following complete file:

```kotlin
package com.vela.app.ui.sessiondetail

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vela.app.amplifierd.AmplifierdRepository
import com.vela.app.notifications.ApprovalNotificationHelper
import com.vela.app.settings.ApiKeyStore
import com.vela.app.ssh.SshNodeRegistry
import com.vela.app.streaming.SessionStreamingManager
import com.vela.app.voice.AudioRecorder
import com.vela.app.voice.WhisperClient
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for Screen 4: Session Detail — Turn History + Input.
 *
 * In Phase 2 the ViewModel no longer owns SSE connections. It subscribes to
 * [SessionStreamingManager.getSessionFlow] and projects [SessionState] into
 * the individual [StateFlow]s the Compose UI needs.
 *
 * Responsibilities:
 *  - Call [SessionStreamingManager.startStreaming] on init
 *  - Map incoming [SessionState] to turns, status, approval, session name
 *  - Send messages via [SessionStreamingManager.sendMessage]
 *  - Retry via [SessionStreamingManager.retryLastMessage]
 *  - Voice recording via AudioRecorder + Whisper transcription
 *  - Image attachment state
 *  - Approval gate (approve / deny calls to amplifierd directly)
 *  - Steer (mid-loop inject via amplifierd client)
 */
@HiltViewModel
class SessionDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val ctx: Context,
    private val registry: SshNodeRegistry,
    private val amplifierd: AmplifierdRepository,
    private val apiKeyStore: ApiKeyStore,
    private val streamingManager: SessionStreamingManager,
) : ViewModel() {

    val sessionId: String = checkNotNull(savedStateHandle["sessionId"])
    val nodeId: String    = savedStateHandle["nodeId"] ?: ""

    val hasOpenAiKey: Boolean get() = apiKeyStore.openAiKey.isNotBlank()

    // ── Turn list ────────────────────────────────────────────────────────────

    private val _turns = MutableStateFlow<List<TurnContent>>(emptyList())
    val turns: StateFlow<List<TurnContent>> = _turns

    // ── Session status ───────────────────────────────────────────────────────

    private val _sessionStatus = MutableStateFlow(SessionStatus.IDLE)
    val sessionStatus: StateFlow<SessionStatus> = _sessionStatus

    /** isLoading = EXECUTING or RESUMING. Drives TypingIndicator and SessionInputBar. */
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    // ── Session name (from session:named SSE event) ──────────────────────────

    private val _sessionName = MutableStateFlow("")
    val sessionName: StateFlow<String> = _sessionName

    // ── Status message (provider retry info, steer confirmation) ────────────

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage

    // ── Input text ───────────────────────────────────────────────────────────

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText

    fun updateInputText(v: String) { _inputText.value = v }
    fun clearInputText() { _inputText.value = "" }

    // ── Image attachments ────────────────────────────────────────────────────

    private val _attachments = MutableStateFlow<List<Uri>>(emptyList())
    val attachments: StateFlow<List<Uri>> = _attachments

    fun addAttachment(uri: Uri) { _attachments.update { it + uri } }
    fun removeAttachment(uri: Uri) { _attachments.update { it - uri } }
    fun clearAttachments() { _attachments.value = emptyList() }

    // ── Approval request ─────────────────────────────────────────────────────

    /** Pair of (approvalId, question). Non-null while waiting for user approval. */
    private val _approvalRequest = MutableStateFlow<Pair<String, String>?>(null)
    val approvalRequest: StateFlow<Pair<String, String>?> = _approvalRequest

    /** Dismiss the approval overlay without resolving (e.g. tapping outside the sheet). */
    fun dismissApproval() { _approvalRequest.value = null }

    // ── Voice recording ──────────────────────────────────────────────────────

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording

    private val audioRecorder = AudioRecorder(ctx)

    fun startVoiceRecording() {
        audioRecorder.start()
        _isRecording.value = true
    }

    fun stopVoiceRecording() {
        val file = audioRecorder.stop()
        _isRecording.value = false
        if (file == null || !file.exists()) return
        val openAiKey = apiKeyStore.openAiKey
        if (openAiKey.isBlank()) {
            _inputText.update { it + "[Set OPENAI_API_KEY in Settings to transcribe]" }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val text = WhisperClient(openAiKey).transcribe(file)
                _inputText.update { existing ->
                    if (existing.isBlank()) text else "$existing $text"
                }
            } catch (e: Exception) {
                Log.w(TAG, "Whisper transcription failed: ${e.message}")
            }
        }
    }

    // ── Init: start streaming + subscribe to session state ───────────────────

    init {
        if (sessionId.isNotBlank() && nodeId.isNotBlank()) {
            // Start streaming: loads transcript then opens SSE subscription
            viewModelScope.launch(Dispatchers.IO) {
                streamingManager.startStreaming(sessionId, nodeId, projectName = null)
            }

            // Subscribe to all state changes from the manager
            viewModelScope.launch {
                streamingManager.getSessionFlow(sessionId).collect { state ->
                    state ?: return@collect

                    // Turns and status
                    _turns.value = state.turns
                    _sessionStatus.value = state.status
                    _isLoading.value = state.status == SessionStatus.EXECUTING ||
                            state.status == SessionStatus.RESUMING

                    // Approval request — fire notification only when a NEW approval arrives
                    val pending = state.pendingApproval
                    val prevApproval = _approvalRequest.value
                    if (pending != null &&
                        (prevApproval == null || prevApproval.first != pending.id)
                    ) {
                        _approvalRequest.value = Pair(pending.id, pending.question)
                        ApprovalNotificationHelper.notify(ctx, sessionId, pending.question)
                    } else if (pending == null && state.status == SessionStatus.IDLE) {
                        _approvalRequest.value = null
                    }

                    // Session naming — persist when name changes
                    val prevName = _sessionName.value
                    if (state.sessionName != null && state.sessionName != prevName) {
                        _sessionName.value = state.sessionName
                        viewModelScope.launch(Dispatchers.IO) {
                            val node = registry.cache.find { it.id == nodeId } ?: return@launch
                            val client = amplifierd.clientForNode(node) ?: return@launch
                            try {
                                client.updateSessionName(sessionId, state.sessionName)
                            } catch (_: Exception) {}
                        }
                    }
                }
            }
        }
    }

    // ── Send message ─────────────────────────────────────────────────────────

    fun sendMessage(message: String = _inputText.value, uris: List<Uri> = _attachments.value) {
        if (_sessionStatus.value != SessionStatus.IDLE || message.isBlank()) return
        clearInputText()
        clearAttachments()
        viewModelScope.launch(Dispatchers.IO) {
            streamingManager.sendMessage(sessionId, message)
        }
    }

    // ── Retry ────────────────────────────────────────────────────────────────

    /** Re-sends the stored lastUserMessage. Transitions ERROR → EXECUTING on success. */
    fun retry() {
        viewModelScope.launch(Dispatchers.IO) {
            streamingManager.retryLastMessage(sessionId)
        }
    }

    // ── Approval gate ────────────────────────────────────────────────────────

    fun approveRequest(approvalId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val node = registry.cache.find { it.id == nodeId } ?: return@launch
                val client = amplifierd.clientForNode(node) ?: return@launch
                client.approveSession(sessionId, approvalId, approved = true)
            } catch (e: Exception) {
                Log.w(TAG, "Approve failed: ${e.message}")
            }
            _approvalRequest.value = null
        }
    }

    fun denyRequest(approvalId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val node = registry.cache.find { it.id == nodeId } ?: return@launch
                val client = amplifierd.clientForNode(node) ?: return@launch
                client.approveSession(sessionId, approvalId, approved = false)
            } catch (e: Exception) {
                Log.w(TAG, "Deny failed: ${e.message}")
            }
            _approvalRequest.value = null
        }
    }

    // ── Steer (mid-loop inject) ──────────────────────────────────────────────

    /**
     * Inject a steering message into the currently-running loop-vela session.
     * The message is queued at the orchestrator level and injected as a user
     * turn at the next tool-call boundary. Visible as a status message if
     * the session is streaming; silently dropped if not.
     */
    fun steer(message: String) {
        if (message.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            val node = registry.cache.find { it.id == nodeId } ?: return@launch
            val client = amplifierd.clientForNode(node) ?: return@launch
            try {
                val queued = client.steer(sessionId, message)
                if (queued) {
                    _statusMessage.value = "Steering: \"${message.take(40)}${if (message.length > 40) "…" else ""}\""
                }
            } catch (e: Exception) {
                Log.w(TAG, "Steer failed: ${e.message}")
            }
        }
    }

    companion object { private const val TAG = "SessionDetailVM" }
}
```

### Step 2: Compile

```bash
cd /Users/ken/workspace/vela && ./gradlew :app:compileDebugKotlin 2>&1 | grep -E "error:|BUILD"
```

Expected: `BUILD SUCCESSFUL`

If you see `Unresolved reference: SessionStreamingManager`, check that the Hilt binding in `AppModule.kt` is present (it was added in Phase 1).

### Step 3: Commit

```bash
git add app/src/main/kotlin/com/vela/app/ui/sessiondetail/SessionDetailViewModel.kt && \
git commit -m "feat(vm): refactor SessionDetailViewModel — subscribe to manager, remove direct SSE, add retry()"
```

---

## Task 5: Update `SessionDetailScreen.kt`

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/ui/sessiondetail/SessionDetailScreen.kt`

### Step 1: Add missing imports

Add these imports to the existing import block in `SessionDetailScreen.kt`:

```kotlin
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.graphics.Color
```

### Step 2: Collect `sessionStatus` from the ViewModel

In the `SessionDetailScreen` composable, after the existing state collection lines (around line 88), add:

```kotlin
    val sessionStatus by viewModel.sessionStatus.collectAsStateWithLifecycle()
```

### Step 3: Fix `isRunning` derivation

Replace the existing `isRunning` line:

```kotlin
    val isRunning = isLoading || turns.any { !it.isUser && it.toolCalls.any { tc -> tc.isRunning } }
```

With:

```kotlin
    // isLoading already reflects EXECUTING | RESUMING from the manager
    val isRunning = isLoading
```

The old `toolCalls`-based check is no longer needed because `isLoading` is now driven by `SessionStatus` directly.

### Step 4: Add RESUMING status strip

Add this block **immediately above** the `statusMessage?.let { … }` block (around line 202). It shows a subtle spinner + text when the session is reconnecting:

```kotlin
            // ── RESUMING strip ─────────────────────────────────────────────
            if (sessionStatus == SessionStatus.RESUMING) {
                Surface(
                    color    = VelaColors.SurfaceRaised,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier              = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier    = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color       = VelaColors.Running,
                        )
                        Text(
                            text  = "Resuming session…",
                            style = MaterialTheme.typography.labelSmall,
                            color = VelaColors.TextSecondary,
                        )
                    }
                }
            }
```

### Step 5: Add retry button

Add this block **immediately above** the `statusMessage?.let { … }` block and **below** the RESUMING strip. Shows when the session has errored:

```kotlin
            // ── Error retry button ─────────────────────────────────────────
            if (sessionStatus == SessionStatus.ERROR) {
                OutlinedButton(
                    onClick  = { viewModel.retry() },
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(vertical = 8.dp),
                    shape    = RoundedCornerShape(20.dp),
                    border   = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    Text("↺ Try again")
                }
            }
```

> **Layout note:** The `Column` containing the input bar area already uses `Alignment.CenterHorizontally` via its children, but the `OutlinedButton` wraps in a `Box` inside the `Column`. Use `Modifier.align(Alignment.CenterHorizontally)` — this works because the enclosing `Column` provides the `ColumnScope`.

### Step 6: Add inline approval card above the input bar

Add this block **immediately above** the `SessionInputBar(...)` call and **below** the steer strip. This is in addition to the existing `ApprovalGateSheet` modal — both can be visible simultaneously:

```kotlin
            // ── Inline approval card ───────────────────────────────────────
            approvalReq?.let { (approvalId, question) ->
                Surface(
                    color    = Color(0xFF2A2000),
                    modifier = Modifier.fillMaxWidth(),
                    border   = BorderStroke(1.dp, Color(0xFFFAB387)),
                    shape    = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text  = "⚡ Needs your input",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFFAB387),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text  = question,
                            style = MaterialTheme.typography.bodySmall,
                            color = VelaColors.TextPrimary,
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { viewModel.approveRequest(approvalId) },
                                colors  = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFFAB387),
                                    contentColor   = Color(0xFF1C1B1F),
                                ),
                            ) {
                                Text("Approve")
                            }
                            OutlinedButton(
                                onClick = { viewModel.denyRequest(approvalId) },
                            ) {
                                Text("Deny")
                            }
                        }
                    }
                }
            }
```

### Step 7: Compile

```bash
cd /Users/ken/workspace/vela && ./gradlew :app:compileDebugKotlin 2>&1 | grep -E "error:|BUILD"
```

Expected: `BUILD SUCCESSFUL`

Common issues:
- `Unresolved reference: SessionStatus` — it's in the same package, no import needed; check spelling
- `Unresolved reference: width` — ensure `import androidx.compose.foundation.layout.width` was added in Step 1
- `None of the following candidates...` on `OutlinedButton` — ensure `import androidx.compose.material3.OutlinedButton` was added

### Step 8: Commit

```bash
git add app/src/main/kotlin/com/vela/app/ui/sessiondetail/SessionDetailScreen.kt && \
git commit -m "feat(ui): add retry button, RESUMING strip, inline approval card to SessionDetailScreen"
```

---

## Task 6: Full build + device deploy verification

### Step 1: Full assembleDebug build (skipping unit tests)

```bash
cd /Users/ken/workspace/vela && \
  ./gradlew assembleDebug -x test 2>&1 | grep -E "error:|BUILD"
```

Expected: `BUILD SUCCESSFUL`

If the build fails, run the full output to see the error:
```bash
cd /Users/ken/workspace/vela && ./gradlew assembleDebug -x test 2>&1 | tail -50
```

### Step 2: Install and launch on device

```bash
adb connect 10.0.0.106:45299 && \
adb -s 10.0.0.106:45299 install -r app/build/outputs/apk/debug/app-debug.apk && \
adb -s 10.0.0.106:45299 shell am force-stop com.vela.app && \
adb -s 10.0.0.106:45299 shell am start --user 0 -n com.vela.app/.MainActivity
```

### Step 3: Smoke tests (manual, on device)

Verify each behaviour in order:

1. **Transcript loads immediately** — open an existing session. Turn history should appear without waiting for SSE. The session title hero should show the first user message.

2. **User turn appears on send** — type a message and tap Send. The user turn should appear in the list immediately (before any AI response), because `sendMessage()` in the manager now adds it optimistically.

3. **AI response streams** — the TypingIndicator should appear, then text should stream in. The `CollapsibleToolCard` should appear for any non-todo tool calls. The `TodoProgressCard` should appear if the AI uses the `todo` tool, with IN_PROGRESS item pulsing and a "NOW" badge.

4. **RESUMING strip** — if a session is in IDLE state and the manager calls `resumeSession()`, the "Resuming session…" spinner strip should appear above the input bar. (May be brief if the node is fast.)

5. **Retry button** — to test: send a message to a session on an offline node, so the stream errors. The "↺ Try again" button should appear above the input bar. Tapping it should call `retry()` which calls `retryLastMessage()`.

6. **Inline approval card** — if the AI issues an `approval:request` SSE event, the amber inline card ("⚡ Needs your input") should appear above the input bar with Approve/Deny buttons. The existing modal sheet should also appear.

### Step 4: Final commit tag

```bash
git tag phase2-complete
```

---

## Known behaviour differences from Phase 1

**ToolResult blocks after streaming:** The old ViewModel re-fetched the full transcript on `Done` to fill in `ToolResult` blocks (which carry the tool output). The new ViewModel does not do this — `ToolResult` blocks will be missing until the stream normalizer or a post-stream transcript refresh is added in a future phase. Tool call cards will show results only from transcript pre-loads, not from live SSE streams. This is an accepted Phase 2 limitation.

**sessionStatus guard on sendMessage:** The old guard was `_isStreaming.value` (boolean). The new guard is `_sessionStatus.value != SessionStatus.IDLE`. This means `sendMessage()` now rejects sends in `RESUMING` and `ERROR` states in addition to `EXECUTING`, which is the correct behaviour per the state machine design.
