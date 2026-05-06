# SSE Streaming Unification & Delegate Tool Visibility

**Date**: 2026-05-06  
**Status**: Ready to implement  
**Branch**: main

---

## Problem Statement

The streaming pipeline has two visibility gaps, both rooted in the same architectural decision: the normalizer treats the SSE stream as a *presence indicator* rather than a *content authority*.

### Gap 1 — Delegate agent tool calls are invisible during streaming

When a delegate agent runs `bash`, `filesystem`, `web_search`, etc., those `tool_call`/`tool:result` SSE events arrive tagged with the child's `session_id` and are silently dropped at the client. The user sees the delegate's prose streaming in (via `DelegateDelta`) but is completely blind to what the delegate is *doing*.

### Gap 2 — Root tool result text has a "dead air" window

Root-session `tool:result` SSE events carry the actual output, but the normalizer discards it and only flips `isRunning=false`. The result text only appears after the transcript reloads post-execution. Users see the spinner stop but no result — then text appears all at once.

---

## Current Architecture

### Event flow
```
SSE stream → AmplifierdStreamClient (parses to StreamEvent sealed class)
           → SessionSseNormalizer (folds into SessionState)
           → SessionDetailViewModel (emits to UI)
           → Compose UI (renders ContentBlocks)
```

### The `isChildEvent` filter (in `AmplifierdStreamClient`)
```kotlin
val isChildEvent = eventSessionId.isNotBlank() && eventSessionId != sessionId
```
Child events currently passed through:
- `DelegateDelta` — child prose tokens (the ONLY one)

Child events currently dropped:
- `ToolUse` — child tool calls ← **the gap**
- `ToolResult` — child tool results ← **the gap**
- `TextBlock`, `ThinkingBlock` — dropped intentionally
- `Done` — dropped intentionally (would prematurely close parent turn)

### The two SSE field-naming conventions (already handled)
These are NOT a separate problem — dual-field reads already normalize them:
- amplifierd live: `type="tool_call"`, input under `"arguments"`
- Anthropic-compatible: `type="tool_use"`, input under `"input"`

### Current tool call lifecycle
1. `content_block:end` → `ToolUse(isRunning=true)` → spinner
2. `tool:result` → `ToolUse.isRunning=false`, output **discarded** → spinner gone, no result text
3. Transcript reload (post-execution) → `ContentBlock.ToolResult` created → result text appears

### Current delegate lifecycle
1. Parent `delegate(...)` call → `ContentBlock.ToolUse(name="delegate", isRunning=true)`
2. Child prose → `DelegateDelta` → accumulated in `ToolUse.streamingText`
3. Child's own tool calls → **DROPPED** (invisible)
4. Parent `tool:result` for delegate → `isRunning=false`, output discarded
5. Transcript reload → `ContentBlock.ToolResult` with final result

---

## Solution: Two PRs

### PR 1 — Stop discarding ToolResult output (small, low risk, ~30 lines)

**File**: `app/src/main/kotlin/com/vela/app/streaming/SessionSseNormalizer.kt`

**Change**: In the `StreamEvent.ToolResult` handler, stop discarding `event.output`. Build and attach a `ContentBlock.ToolResult` directly from the SSE payload.

```kotlin
// BEFORE
is StreamEvent.ToolResult -> {
    val toolIdx = blocks.indexOfFirst {
        it is ContentBlock.ToolUse && it.id == event.toolCallId
    }
    if (toolIdx >= 0) {
        val old = blocks[toolIdx] as ContentBlock.ToolUse
        blocks[toolIdx] = old.copy(isRunning = false)
        turns[idx] = turn.copy(contentBlocks = blocks)
        state.copy(turns = turns)
    } else {
        state
    }
}

// AFTER
is StreamEvent.ToolResult -> {
    val toolIdx = blocks.indexOfFirst {
        it is ContentBlock.ToolUse && it.id == event.toolCallId
    }
    if (toolIdx >= 0) {
        val old = blocks[toolIdx] as ContentBlock.ToolUse
        blocks[toolIdx] = old.copy(isRunning = false)
        // Attach result immediately — transcript reload will overwrite idempotently
        val alreadyHasResult = blocks.any {
            it is ContentBlock.ToolResult && it.toolUseId == event.toolCallId
        }
        if (!alreadyHasResult && event.output.isNotBlank()) {
            blocks.add(ContentBlock.ToolResult(
                toolUseId = event.toolCallId,
                output    = event.output,
                isError   = false,
            ))
        }
        turns[idx] = turn.copy(contentBlocks = blocks)
        state.copy(turns = turns)
    } else {
        state
    }
}
```

The transcript reload post-execution remains untouched — it replaces the entire turn state with the canonical version, so this is fully idempotent.

---

### PR 2 — Surface delegate agent tool calls (medium, ~200 lines)

Four coordinated changes across four files.

#### Step 1: Add `childSessionId` to StreamEvent variants

**File**: `app/src/main/kotlin/com/vela/app/amplifierd/AmplifierdStreamClient.kt`

Add `childSessionId: String? = null` to `StreamEvent.ToolUse` and `StreamEvent.ToolResult`. Stop dropping child `ToolUse`/`ToolResult` events; pass them through with `childSessionId` populated. Continue dropping child `Done`, `ThinkingBlock`, `TextBlock`.

```kotlin
// StreamEvent sealed class changes:
data class ToolUse(
    val id: String,
    val name: String,
    val inputJson: String,
    val childSessionId: String? = null,   // NEW — non-null for child events
) : StreamEvent()

data class ToolResult(
    val toolCallId: String,
    val toolName: String,
    val output: String,
    val childSessionId: String? = null,   // NEW — non-null for child events
) : StreamEvent()

// In the dispatch table, for child ToolUse/ToolResult — instead of null:
"tool_call", "tool_use" -> StreamEvent.ToolUse(
    id            = ...,
    name          = ...,
    inputJson     = ...,
    childSessionId = if (isChildEvent) eventSessionId else null,
)
```

#### Step 2: Add `childBlocks` to `ContentBlock.ToolUse` for delegate calls

**File**: wherever `ContentBlock` is defined (likely `SessionModels.kt` or `TurnContent.kt`)

Introduce a dedicated `DelegateToolUse` content block that can hold nested child activity, OR add `childBlocks` specifically to the delegate `ToolUse`. The zen-architect recommends the former for type safety, but adding `childBlocks: List<ContentBlock> = emptyList()` to the existing `ToolUse` is pragmatically simpler and avoids a UI refactor.

Decision: add `childBlocks` to `ContentBlock.ToolUse`:

```kotlin
data class ToolUse(
    val id: String,
    val name: String,
    val inputJson: String,
    val isRunning: Boolean = false,
    val streamingText: String = "",
    val childBlocks: List<ContentBlock> = emptyList(),   // NEW
) : ContentBlock()
```

#### Step 3: Route child tool events in the normalizer

**File**: `app/src/main/kotlin/com/vela/app/streaming/SessionSseNormalizer.kt`

Maintain a `childSessionId → parentToolUseId` map. Use the `childSessionId` already carried in `DelegateDelta` to establish the mapping when a delegate starts.

```kotlin
// Normalizer internal state (mutable, not in SessionState):
private val childToParentBlock = mutableMapOf<String, String>()  // childSessionId → ToolUse.id

// DelegateDelta handler — establish/maintain mapping:
is StreamEvent.DelegateDelta -> {
    val toolIdx = blocks.indexOfLast { it is ContentBlock.ToolUse && it.isRunning }
    if (toolIdx >= 0) {
        val old = blocks[toolIdx] as ContentBlock.ToolUse
        // Register this child session → parent block mapping
        childToParentBlock[event.childSessionId] = old.id
        blocks[toolIdx] = old.copy(streamingText = old.streamingText + event.token)
    }
}

// ToolUse handler — route child events into parent's childBlocks:
is StreamEvent.ToolUse -> {
    if (event.childSessionId != null) {
        // Find parent delegate block and append child tool call
        val parentId = childToParentBlock[event.childSessionId]
        val parentIdx = blocks.indexOfFirst { it is ContentBlock.ToolUse && it.id == parentId }
        if (parentIdx >= 0) {
            val parent = blocks[parentIdx] as ContentBlock.ToolUse
            val childBlock = ContentBlock.ToolUse(
                id        = event.id,
                name      = event.name,
                inputJson = event.inputJson,
                isRunning = true,
            )
            blocks[parentIdx] = parent.copy(childBlocks = parent.childBlocks + childBlock)
        }
        // ... update state
    } else {
        // existing root-session ToolUse handling (unchanged)
    }
}

// ToolResult handler — for child results, update the nested child block:
is StreamEvent.ToolResult -> {
    if (event.childSessionId != null) {
        val parentId = childToParentBlock[event.childSessionId]
        val parentIdx = blocks.indexOfFirst { it is ContentBlock.ToolUse && it.id == parentId }
        if (parentIdx >= 0) {
            val parent = blocks[parentIdx] as ContentBlock.ToolUse
            val updatedChildren = parent.childBlocks.map { child ->
                if (child is ContentBlock.ToolUse && child.id == event.toolCallId) {
                    child.copy(isRunning = false)
                } else child
            }.toMutableList()
            // Attach result to child blocks
            if (event.output.isNotBlank()) {
                updatedChildren.add(ContentBlock.ToolResult(
                    toolUseId = event.toolCallId,
                    output    = event.output,
                    isError   = false,
                ))
            }
            blocks[parentIdx] = parent.copy(childBlocks = updatedChildren)
        }
    } else {
        // existing root-session ToolResult handling (from PR 1, unchanged)
    }
}
```

#### Step 4: Render `childBlocks` in the DelegateBlock composable

**File**: `app/src/main/kotlin/com/vela/app/ui/sessiondetail/SessionDetailScreen.kt` (or wherever `DelegateBlock` is defined)

Extend `DelegateBlock` to render `childBlocks` using the same `CollapsibleToolCard` composable, with a modest left-padding indent:

```kotlin
// Inside DelegateBlock composable, after streamingText, before final result:
if (block.childBlocks.isNotEmpty()) {
    Column(modifier = Modifier.padding(start = 12.dp)) {
        block.childBlocks.filterIsInstance<ContentBlock.ToolUse>().forEach { childTool ->
            val childResult = block.childBlocks
                .filterIsInstance<ContentBlock.ToolResult>()
                .find { it.toolUseId == childTool.id }
            CollapsibleToolCard(childTool, childResult)
        }
    }
}
```

No new widget types — reuses existing `CollapsibleToolCard` renderer recursively.

---

## What Does NOT Change

- **Dual-field parsing** (`arguments`/`input`, `tool_call`/`tool_use`) — already correct, not touched
- **Child `Done` filtering** — must stay dropped, would prematurely close parent turn
- **Child `ThinkingBlock` filtering** — intentional, child internal reasoning is verbose noise
- **Transcript reload post-execution** — remains as canonical reconciliation, fully idempotent with PR 1
- **`todo` tool special-casing** — unrelated, untouched
- **`stream()` vs `subscribeEvents()` behavioral split** — untouched

---

## Files Changed Per PR

### PR 1
- `app/src/main/kotlin/com/vela/app/streaming/SessionSseNormalizer.kt` — stop discarding ToolResult output

### PR 2
- `app/src/main/kotlin/com/vela/app/amplifierd/AmplifierdStreamClient.kt` — add childSessionId to ToolUse/ToolResult, unfilter child tool events
- `app/src/main/kotlin/com/vela/app/streaming/SessionSseNormalizer.kt` — route child events into parent's childBlocks
- `app/src/main/kotlin/com/vela/app/[models file]` — add childBlocks to ContentBlock.ToolUse
- `app/src/main/kotlin/com/vela/app/ui/sessiondetail/[DelegateBlock file]` — render childBlocks

---

## Related Work Also Committed

**Session list live rename** (`2026-05-06`): `SessionListViewModel` streaming overlay now propagates `sessionName` from `SessionState` into `SessionSummary.title`, so the session list card title updates live when a `session:named` SSE event fires — same as the session detail top bar already did.
