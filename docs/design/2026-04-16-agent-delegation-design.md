# Agent Delegation Design

**Date:** 2026-04-16  
**Status:** Draft — design session output, not yet implemented  
**Scope:** Multi-agent delegation, named agents, memory model

---

## Mental Model: Stack / Heap / Cold Storage

Three tiers, useful for thinking about information flow between agents:

```
STACK        Conversation history          Hot — already in the LLM's context window
             Per session, per agent        Zero retrieval cost
             Bounded by context window     Disappears when turn ends

COLD         Vault filesystem              Persistent — user's personal lifeos data
             Survives forever              Pay I/O cost on every access
             Git-tracked, user-owned       Sacred — never polluted by agent runtime
```

### The heap insight and why we dropped it

We considered an in-memory heap (Room DB KV store, session-scoped, GC'd at conversation end) for passing large intermediate data between agents without putting it on the stack.

**We dropped it because:**

1. **The LLM bottleneck kills the performance argument.** LLM inference is 500ms–30s. A vault file read is 1–10ms. The LLM cannot directly access RAM — it can only access data through the context window (tool calls). Whether data comes from RAM or disk is invisible to the LLM. Both cost tokens, not I/O time.

2. **The edge case basically never happens.** A well-designed sub-agent returns a summary — key findings, a conclusion, a few sentences. Not a data dump. If it's returning 8,000 tokens it's doing something wrong. When a sub-agent genuinely produces large output (a report, a processed document), `write_file` to the vault already handles it. The sub-agent returns `"written to vault:research/topicA.md"` — a tiny string on the stack. The existing tools cover this case.

3. **Amplifier doesn't have one and it works fine.** Amplifier's delegate has been production-validated without a heap. There's no evidence the missing heap is causing problems.

**What's worth keeping from the heap discussion:**

The vault is sacred. Operational runtime data (agent state, delegation results) must not go into the vault. This is still true. If we ever need session-scoped temp storage (evidence not yet present), it goes in Room DB — not the vault.

### The vault is sacred

The vault belongs to the user. It contains their notes, journals, documents — their life. Agent operational data must never pollute it. This is the same reason you don't store `/var/run` files in your home directory.

---

## Delegation Model: Follow Amplifier

Amplifier's delegation is production-validated and covers the core use case. Vela should implement the same shape.

```kotlin
delegate(
  agent         = "researcher",    // named agent bundle — has its own system prompt + identity
  instruction   = "find key findings on topic A",
  context_depth = "recent",        // none | recent | all — how much parent history flows down
  session_id    = "abc-123",       // optional — resume a prior sub-session
)
→ "Key finding: X because Y and Z"  // sub-agent's response, returned as tool result
```

### Why sync return is the right default

The return value lands directly in the parent's context window — the parent can immediately reason about it. "Given that finding, here's what I do next." No extra tool call to load the result. No state to manage.

The sub-agent's 15 internal tool calls happen in its own isolated stack frame. The parent never sees them. It gets one clean synthesis. Its context stays lean.

### Two return modes (Amplifier's approach)

**Small results (the common case):** response text comes back as the tool result string. Lands on the parent's stack. Parent reasons immediately. This is the default.

**Large results (already handled):** sub-agent calls `write_file` to vault, returns the path. `"Written to vault:research/topicA.md"` is a tiny string on the parent's stack. This uses existing tools. No special mechanism needed.

---

## Named Agent Bundles

The biggest gap in Vela's current design. The difference between:

```kotlin
delegate(instruction = "survey the codebase")   // blank Claude, no identity
delegate(agent = "code-reader", ...)             // specialist with defined behavior
```

With named agents, sub-agents have a defined system prompt, behavioral focus, and specialized capabilities. The parent hires a specialist, not a blank model.

**Agent bundle format** — matches what `SkillsEngine` already reads:

```
assets/agents/
  researcher/
    AGENT.md      # system prompt + description (same SKILL.md frontmatter format)
  summarizer/
    AGENT.md
  code-reader/
    AGENT.md
```

Or installed from a skill directory (agentskills.io format).

---

## Calling Convention

The sub-agent gets exactly what the parent hands it — not the parent's full history.

**Context flowing DOWN:**

```kotlin
// context_depth controls how much parent history the sub-agent receives
context_depth = "none"    // fresh start — sub-agent gets only the instruction
context_depth = "recent"  // last N turns of parent history
context_depth = "all"     // full parent history (use sparingly)
```

Default: `"none"`. The sub-agent should be self-contained given its instruction. Passing full parent history is expensive and usually unnecessary.

**Result flowing UP:**

Sub-agent's final response returns as a tool result string. It lands on the parent's stack. The parent can reason about it immediately.

The sub-agent's internal conversation history is ephemeral — it exists during the call and is discarded. Only the final response surfaces to the parent. The parent's context sees one clean result, not 15 intermediate tool calls.

---

## Comparison with Amplifier

| | Amplifier (existing) | Vela (proposed) |
|---|---|---|
| Agent identity | Named bundles (`agent="foundation:explorer"`) | Named bundles (to build) |
| Return | Response text on parent's stack | Same |
| Shared state | Conversation history via `context_depth` | Same |
| Heap / write_to | Not needed — not built | Not needed — not building |
| Session continuity | `session_id` resumes across turns | To build |
| Async | No — sync first | Later, only if a concrete case demands it |
| Context control | `context_depth` + `context_scope` | `context_depth` to start |

Amplifier has been running without a heap and without `write_to:` since production. That's evidence the complexity isn't needed.

---

## Two Bugs to Fix Immediately

Found during research into why agentic loops end without summaries.

### Bug 1: No forcing function for post-tool summaries

`SessionHarness.DEFAULT_FALLBACK` gives Claude zero guidance on post-tool behavior. Claude completes tool work and may return nothing or minimal text.

```kotlin
// current — no guidance
const val DEFAULT_FALLBACK =
    "You are a personal AI assistant. Use the vault configuration..."

// fix — add explicit completion instruction
const val DEFAULT_FALLBACK = """
    You are a personal AI assistant. Use the vault configuration above to
    determine what files and vaults are available.

    After completing any tool operations, ALWAYS write a clear natural language
    response summarizing what you did and what the results mean. Never end a
    turn with only tool calls — always follow with a helpful reply.
""".trimIndent()
```

This should also be prepended to every system prompt so vault sessions with their own SYSTEM.md also get it.

### Bug 2: Tool-only turns are invisible in history

In `InferenceEngine.buildHistory()`:

```kotlin
if (assistantText.isNotBlank()) {
    arr.put(JSONObject().put("role", "assistant").put("content", assistantText))
}
```

Turns where Claude only called tools (no text) produce no assistant message in history. In multi-turn conversations, Claude has no record of tool work it already completed — it repeats itself or loses context.

**Fix:** When a turn has tool events but no text, inject a synthetic assistant message listing what tools ran. The data is in `twe.sortedEvents` — construct a brief "I ran X, Y, Z" summary entry.

---

## Build Order

1. **Fix `DEFAULT_FALLBACK`** — one line, zero risk, immediate impact
2. **Fix `buildHistory()` for tool-only turns** — small, high value
3. **Sync `delegate` tool** — `DelegateTool` → `AmplifierSession.runTurn()` with fresh history
4. **Named agent bundles** — `assets/agents/` directory resolved by `delegate` tool
5. **`context_depth` parameter** — none / recent / all on what history flows down
6. **Session resumption** — `session_id` for sub-tasks spanning parent turns
7. **Async** — only if a concrete case demands it, not before

Items 1–2 are bugs. Items 3–5 are the core feature, matching Amplifier's existing capability. Items 6–7 are extensions.

---

## What This Enables

**With sync delegation and named bundles:**
- Claude hands off specialized work to purpose-built agents
- Sub-agents have clean isolated stack frames — parent context stays lean
- Named agents have defined behavior — "hire a specialist, not a blank model"

**With session resumption:**
- Long sub-tasks can span multiple parent turns
- Sub-agent picks up where it left off

**With async (future, only if needed):**
- Multiple agents work concurrently
- Long tasks run in background while conversation continues

---

*Designed through conversation — Ken + Amplifier, 2026-04-16*  
*Key decisions: dropped heap (LLM bottleneck kills performance argument, edge case covered by write_file), follow Amplifier's delegate shape, vault stays sacred*
