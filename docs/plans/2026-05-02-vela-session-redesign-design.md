# Vela Session State & Notification-First Redesign

## Executive Summary

Vela is a mobile-first AI orchestration hub that talks to a remote `amplifierd` daemon over HTTP + SSE. Today, its session state flow is broken in four fundamental ways: sessions go invisible after `amplifierd` restarts, session-list status chips display stale information, SSE streams die whenever the user navigates away, and two divergent rendering paths produce visually inconsistent UIs.

This design fixes those root causes by introducing a single Android **foreground service** (`SessionStreamingService`) that owns all SSE connections, a unified `SessionState` data model rendered through one Compose code path, and a **notification-first attention system** where AI tasks run in the background and the phone surfaces them only when the user's attention is required. The session detail screen becomes a beautiful drill-down — not the primary interaction point.

The redesign also collapses the historical "dormant vs live-idle" distinction into a single `IDLE` state with idempotent resume, adds a dedicated todo-progress widget driven by structured tool calls, gives errored turns a visible retry button, and lets users delete projects via a long-press M3 bottom sheet.

---

## Goal

Fix the broken session state flow in Vela and reshape it as a **notification-first attention system**: AI tasks run in the background, the phone notifies you when something needs your attention, and the session detail screen is a beautiful drill-down — not the primary interaction point.

---

## Background

### Problem Statement

Four root causes drive the current breakage:

1. **Sessions invisible after `amplifierd` restart** — the app does not handle the dormant vs live-idle distinction.
2. **Session list status chips lie** — there are no live updates, so chips are stale on entry.
3. **SSE stream dies on navigation** — the streaming subscription lives inside the screen ViewModel, which lives in the navigation backstack; navigating away kills the stream.
4. **Two rendering paths diverge** — SSE events and transcript JSON produce different `contentBlocks`, yielding two different-looking UIs for the same conversation.

Additional pain points:

- Todo tool calls receive no special treatment (they render as a generic tool block).
- Errors offer no retry button.
- Projects cannot be deleted easily.

---

## Approach

The chosen architecture is an **Android Foreground Service** that owns all SSE connections for the lifetime of the app process. ViewModels never own a stream — they subscribe to a `StateFlow<SessionState>` published by a `SessionStreamingManager` interface backed by the service.

This delivers four concrete properties:

- **Stream survival** across navigation, app backgrounding, and low-memory events.
- **One source of truth** per session, enabling live, push-driven updates everywhere (session list, detail screen, notifications).
- **One rendering path** — both transcript loads and live SSE events flow into the same `SessionState.turns` model and render through the same Compose code.
- **System-level attention** — the service posts Android notifications when sessions need the user, fulfilling the notification-first vision.

A second foundational choice: **collapse `dormant` and `live-idle` into a single `IDLE` state**. Both conditions get the same treatment — show transcript + call `POST /sessions/{id}/resume`. Resume is idempotent, so the call is safe on already-live sessions. One code path, no condition check needed.

---

## Architecture

The system is layered as follows:

```
┌────────────────────────────────────────────────────────────┐
│  Compose UI (Session List, Session Detail, Project Sheet)  │
└────────────────────────────────────────────────────────────┘
                          ▲
                          │  StateFlow<SessionDetailUiState>
                          │
┌────────────────────────────────────────────────────────────┐
│  ViewModels (SessionDetailViewModel, SessionListViewModel) │
└────────────────────────────────────────────────────────────┘
                          ▲
                          │  StateFlow<SessionState>
                          │
┌────────────────────────────────────────────────────────────┐
│  SessionStreamingManager (interface)                       │
└────────────────────────────────────────────────────────────┘
                          ▲
                          │  implemented by
                          │
┌────────────────────────────────────────────────────────────┐
│  SessionStreamingService (Android Foreground Service)      │
│   • Map<sessionId, ActiveStream>                           │
│   • SessionTranscriptNormalizer                            │
│   • SessionSseNormalizer                                   │
│   • Notification posting                                   │
└────────────────────────────────────────────────────────────┘
                          ▲
                          │  HTTP + SSE
                          │
┌────────────────────────────────────────────────────────────┐
│  amplifierd (remote daemon, vela plugin)                   │
└────────────────────────────────────────────────────────────┘
```

Key invariants:

- ViewModels never open SSE connections directly.
- Streams outlive ViewModels.
- Both transcript JSON and SSE events normalize to the same `TurnContent` model.
- Compose UI renders `List<TurnContent>` without knowing the source.

---

## Components

### Section 1 — The Streaming Layer

#### `SessionStreamingService` (Android Foreground Service)

- Owns all active SSE connections in a `Map<sessionId, ActiveStream>`.
- Each `ActiveStream` is a coroutine running an SSE subscription plus a `MutableStateFlow<SessionState>`.
- The coroutine scope is tied to the service lifecycle, not the screen lifecycle.
- The service listens to each stream internally and fires notifications when relevant events arrive.
- Starts on app launch; enters **standby** (no foreground notification) when all streams are idle.
- Required by Android to remain a foreground service whenever any session is `EXECUTING`.

#### `SessionStreamingManager` (the interface ViewModels see)

```kotlin
interface SessionStreamingManager {
    fun getSessionFlow(sessionId: String): StateFlow<SessionState>
    fun startStreaming(sessionId: String, nodeInfo: NodeInfo)
    fun stopStreaming(sessionId: String)
    fun resumeSession(sessionId: String)            // POST /sessions/{id}/resume
    fun retryLastMessage(sessionId: String)         // re-execute SessionState.lastUserMessage
}
```

#### `SessionState` — unified data model

```kotlin
data class SessionState(
    val sessionId: String,
    val status: SessionStatus,        // EXECUTING | IDLE | ERROR | RESUMING
    val turns: List<TurnContent>,     // unified — same model for transcript + SSE
    val activeTurnIndex: Int?,        // index of currently-streaming turn, null if not streaming
    val pendingApproval: ApprovalRequest?,
    val lastUserMessage: String?,     // stored for retry support
)
```

#### `TurnContent` — unified turn model

```kotlin
data class TurnContent(
    val text: String,
    val isUser: Boolean,
    val isStreaming: Boolean = false,
    val contentBlocks: List<ContentBlock> = emptyList(),
)

sealed class ContentBlock {
    data class Text(val markdown: String) : ContentBlock()
    data class Thinking(val text: String) : ContentBlock()
    data class ToolUse(
        val id: String,
        val name: String,
        val inputJson: String,
        val outputJson: String? = null,
    ) : ContentBlock()
    data class TodoProgress(val todos: List<TodoItem>) : ContentBlock()
}

data class TodoItem(
    val content: String,
    val status: TodoStatus,           // PENDING | IN_PROGRESS | COMPLETED
    val activeForm: String,
)
```

#### Normalizers

Both produce identical `TurnContent`:

- **`SessionTranscriptNormalizer`** — parses the `GET /sessions/{id}/transcript` API response into `List<TurnContent>`.
- **`SessionSseNormalizer`** — processes incoming SSE events as mutations to `SessionState.turns`.

#### How state flows

ViewModels call `manager.getSessionFlow(id)` and collect a `StateFlow<SessionState>`. Navigate away — the ViewModel dies, but the stream lives on inside the service. Return — a new ViewModel subscribes to the same flow and immediately receives the latest state.

#### Foreground notification

Android requires a foreground notification while any session is executing.

- **Content:** `Vela — [currentTodoActiveForm] · [projectName]` (e.g., "Vela — Running tests · Alpha").
- **Updates live** as todo items advance.
- **Removed** (service enters standby) when all sessions are idle.

---

### Section 2 — Session Lifecycle

#### Key insight: collapse dormant into `IDLE`

Vela does not need to distinguish "live-idle" from "dormant" (`amplifierd` restarted). Both get the same treatment: show transcript + call `POST /sessions/{id}/resume`. Resume is idempotent and safe on live sessions. One code path, fewer bugs.

#### State machine

```
IDLE       ── user taps ──────────────►  RESUMING
RESUMING   ── resume ok ──────────────►  IDLE       (input enabled)
RESUMING   ── resume failed ──────────►  ERROR
IDLE       ── user sends message ─────►  EXECUTING
EXECUTING  ── orchestrator:complete ──►  IDLE
EXECUTING  ── retries exhausted ──────►  ERROR
ERROR      ── user taps retry ────────►  EXECUTING
```

#### Auto-resume flow (on session tap)

1. **Immediate.** Open the session detail screen. Load the transcript via `GET /sessions/{id}/transcript` → render via `SessionTranscriptNormalizer`. The user sees history instantly.
2. **Background.** If `status == IDLE`, call `POST /sessions/{id}/resume`. The input bar shows a "Resuming…" spinner; history remains visible.
3. **Unlock.** Resume succeeds → the service opens the SSE subscription → the input bar activates.
4. **If `EXECUTING`.** Skip steps 2–3 and subscribe directly to the already-running stream.
5. **If resume fails.** Toast "Session could not be resumed" and leave the input disabled. History remains visible (read-only).

#### `SessionState.lastUserMessage`

Stored whenever a user message is sent via `execute/stream`. Consumed by `retryLastMessage()`. On transcript load, populated from the last user message in the turn list.

---

### Section 3 — Session Detail Screen

#### `SessionDetailViewModel`

- On `init`: calls `manager.getSessionFlow(sessionId)` and `collect`s the resulting `SessionState`.
- If `status == IDLE`, triggers resume (sets `status = RESUMING` locally while waiting).
- Exposes a `StateFlow<SessionDetailUiState>` to the Compose UI.
- Performs no direct SSE handling — everything arrives via `SessionStreamingManager`.

#### Unified rendering

The Compose UI renders `List<TurnContent>` without knowing whether the data came from a transcript fetch or a live SSE stream. Both normalizers produce the same structure. There is exactly **one** rendering code path.

#### Todo Progress Widget (`TodoProgressCard`)

- Triggered when `ContentBlock.ToolUse` has `name == "todo"` — rendered as a `TodoProgressCard` instead of a generic tool block.
- Shows the full task plan:
  - ✓ completed (struck through)
  - ▶ active item with a pulsing "NOW" badge
  - ○ pending items
- Updates **in-place** when a subsequent todo call arrives — only one `TodoProgressCard` per turn; last state wins.
- The `activeForm` of the in-progress todo item is also surfaced in the foreground service notification.

#### Tool call cards (non-todo)

- All non-todo `ContentBlock.ToolUse` entries render as **collapsed cards**: tool name + truncated input on one line.
- Tap to expand: full input JSON + output JSON.
- No more inline walls of JSON.

#### Retry button

- Appears inline below the last assistant turn when `SessionState.status == ERROR`.
- Centered outlined pill button: **"↺ Try again"**.
- Calls `manager.retryLastMessage(sessionId)` → transitions `ERROR → EXECUTING`.
- The error turn remains visible above the retry button.

#### Input bar states

| State       | Behavior                                                                |
| ----------- | ----------------------------------------------------------------------- |
| `RESUMING`  | Disabled, spinner + "Resuming session…"                                 |
| `IDLE`      | Active, normal input                                                    |
| `EXECUTING` | Disabled (or steer mode if typing while running — shows "→ Steer" hint) |
| `ERROR`     | Disabled until retry is tapped                                          |

#### Approval request card

- Renders inline in the chat as an `approval-card` with an amber border.
- "Needs your input" eyebrow + question text + Approve / Deny buttons.
- Inline chat buttons and notification inline actions both call the same approval endpoint.
- State updates via `SessionState.pendingApproval` and clears when resolved.

---

### Section 4 — Notifications, Session List & Project Management

#### Notification design (Material 3 Expressive)

Three notification types, posted on the **"Vela Sessions"** channel:

**Turn complete (green):**

- Title: `[ProjectName]: Done — N of N tasks` (or "Ready" if no todos)
- Body: the original user prompt, truncated to 60 chars
- Tap → deep link to session detail

**Approval needed (amber):**

- Title: `[ProjectName]: Needs your input`
- Body: the approval question text
- Inline actions: **Approve** (filled button) + **Deny** (outline button) — work without opening the app
- Tap → deep link to session detail

**Error (red):**

- Title: `[ProjectName]: Task may have failed`
- Body: `Connection error after N retries · tap to retry`
- Tap → deep link to session detail (retry button shown immediately)

**Foreground service (persistent, blue/purple):**

- Content: `Vela — [todoActiveForm] · [projectName]` — e.g., "Vela — Running tests · Alpha"
- Updates live as todos advance
- Removed when all sessions go idle (service enters standby)

#### Session list — live state

`SessionListViewModel` subscribes to all session state flows from `SessionStreamingManager`. Live updates, no polling.

**Session card anatomy (M3 tonal system):**

| Status      | Icon slot          | Pill     | Subtitle                     |
| ----------- | ------------------ | -------- | ---------------------------- |
| `EXECUTING` | green `#C8E6C9`    | "● Live" | `todoActiveForm` (green)     |
| `IDLE`      | lavender `#E8DEF8` | "Done"   | "Done · N ago"               |
| `ERROR`     | coral `#FFDAD6`    | "Error"  | "Failed · tap to retry"      |

Sessions group under project headers. Tapping any session triggers the auto-resume flow.

#### Project deletion

- Long-press a project row → M3 bottom sheet (`#FEF7FF` surface, 28dp top corners, drag handle).
- Sheet shows project name and "N sessions · last active [time]".
- "🗑 Delete project" button in error tonal container (`#FFDAD6` background, `#93000A` text).
- Confirmation dialog before actual delete.
- API call: `DELETE /projects/{id}` via the vela plugin.
- Also removes associated sessions from the local Room DB.
- Swipe-to-delete on the project row is offered as an alternative trigger.

#### Visual design system (Material You M3 Expressive)

| Token              | Value                          |
| ------------------ | ------------------------------ |
| Background         | `#F3F0F5`                      |
| Cards              | `#FFFFFF` with subtle shadow   |
| Primary            | `#6750A4` (M3 purple)          |
| Primary container  | `#E8DEF8`                      |
| Error              | `#93000A`                      |
| Error container    | `#FFDAD6`                      |
| Success / running  | `#2E7D32`                      |
| Running container  | `#C8E6C9`                      |
| Warning / approval | `#7C4F00`                      |
| Warning container  | `#FFF8F0`                      |
| Corner radius      | 16dp cards, 20dp chips, 28dp sheets / phone corners |
| Typography         | Google Sans / Roboto Flex      |

---

## Data Flow

### Transcript load (cold open)

```
User taps session
   → SessionDetailViewModel.init
   → SessionStreamingManager.getSessionFlow(id)            (cached or new MutableStateFlow)
   → Service: GET /sessions/{id}/transcript
   → SessionTranscriptNormalizer → List<TurnContent>
   → MutableStateFlow.update { state.copy(turns = ..., status = IDLE) }
   → ViewModel collects → Compose renders
```

### Resume (idempotent)

```
status == IDLE detected
   → manager.resumeSession(id)
   → state.status = RESUMING
   → POST /sessions/{id}/resume
   → on success: open SSE subscription, state.status = IDLE
   → on failure: state.status = ERROR, toast
```

### Live SSE event

```
amplifierd → SSE event
   → SessionSseNormalizer
   → mutation to SessionState.turns / activeTurnIndex / pendingApproval
   → MutableStateFlow.update { ... }
   → All collectors (detail VM, list VM) re-render
   → If event is "needs approval" / "complete" / "error" → service posts notification
```

### Retry

```
User taps "↺ Try again"
   → manager.retryLastMessage(id)
   → re-issues the stored SessionState.lastUserMessage via execute/stream
   → state.status = EXECUTING
```

### Project delete

```
Long-press → bottom sheet → confirmation dialog → confirm
   → DELETE /projects/{id}
   → on success: remove project + its sessions from Room DB
   → state flows for those sessions stop (service evicts entries)
```

---

## Error Handling

- **Resume failure** → `state.status = ERROR`, toast "Session could not be resumed", input bar stays disabled, transcript remains visible (read-only).
- **SSE stream drop** → service attempts reconnect with exponential backoff; on retries-exhausted → `state.status = ERROR` and an error notification fires.
- **Execute failure** → same `ERROR` state; the inline retry button calls `retryLastMessage()`.
- **Delete failure** → toast surfaced from the bottom sheet; no DB mutation occurs.
- **Service killed by OS** → on next app launch the service restarts and re-opens streams for any session whose last known status was `EXECUTING` (best-effort; status is reconciled via transcript fetch).
- **Unknown SSE event types** → ignored by `SessionSseNormalizer` (forward-compat, log only).

---

## Testing Strategy

- **Unit tests for normalizers.** Build a corpus of representative transcript JSON and SSE event sequences; assert that both `SessionTranscriptNormalizer` and `SessionSseNormalizer` produce identical `List<TurnContent>` for equivalent conversations. This is the central guarantee of the unified rendering path.
- **State-machine tests for `SessionState`.** Drive each documented transition (`IDLE → RESUMING`, `RESUMING → ERROR`, `EXECUTING → IDLE`, etc.) and assert the resulting `SessionState`.
- **Service lifecycle tests.** Verify that a stream survives ViewModel destruction, that the foreground notification appears when any session is `EXECUTING` and is removed when all return to `IDLE`, and that standby mode is entered correctly.
- **Idempotent resume tests.** Call `POST /sessions/{id}/resume` against a live session and confirm no disruption; against a dormant session and confirm reactivation.
- **Notification tests.** Trigger turn-complete, approval-needed, and error events; assert the correct notification type, content, and inline actions.
- **UI tests (Compose).**
  - Auto-resume flow: open session → verify transcript renders, "Resuming…" spinner appears, then input activates.
  - Retry button: force `ERROR` state → assert button presence → tap → assert `EXECUTING`.
  - Todo widget: feed sequential todo tool calls → assert in-place update of one `TodoProgressCard` per turn.
  - Project delete: long-press → bottom sheet → confirm → assert sessions removed.
- **Integration test against `amplifierd`.** End-to-end run with the real vela plugin: create session, send message, observe live updates in list + detail, background the app, return, observe state preserved.

---

## Open Questions

1. Should the foreground notification show Approve / Deny inline actions? (Direct reply actions require Android 13+.)
2. What is the `amplifierd` `DELETE /projects/{id}` endpoint signature? Need to verify it exists in the vela plugin.
3. Should `ERROR`-state sessions auto-clear from the list after N days, or persist indefinitely?
4. Is there a maximum number of concurrent SSE streams the foreground service should support?
