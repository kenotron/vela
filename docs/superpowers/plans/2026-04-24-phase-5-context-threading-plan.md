# Phase 5: Context Threading Implementation Plan

> **Execution:** Use the subagent-driven-development workflow to implement this plan.

**Goal:** Make `delegate` actually thread parent conversation history into the sub-agent's instruction so `context_depth`/`context_scope` filtering works end-to-end.

**Architecture:** The orchestrator hands the current message slice to context-aware tools immediately before dispatching them. We introduce a `ContextAwareTool` super-trait of `Tool` (placed in `amplifier-module-tool-task` — both the orchestrator and `tool-delegate` already depend on it, and `amplifier-core` is frozen so the super-trait must live in our workspace). `DelegateTool` implements both `Tool` and `ContextAwareTool`; it stashes the messages into an interior-mutable buffer that `execute()` reads to build the `[PARENT CONVERSATION CONTEXT]` block. The orchestrator keeps a parallel `context_aware_tools` registry alongside its existing `tools` registry; before each tool call it looks up by name and, if present, calls `set_execution_context(messages.clone())`. Context flows into the child as a prepended block in the instruction string only — `req.context` stays empty, so it is ephemeral to that one delegation.

**Tech Stack:** Rust 2021 (workspace `amplifier-rust`), tokio, async-trait, serde_json, proptest (new dev-dep on `tool-delegate`).

---

## Design Decision: Why Option B

Three approaches were considered:

| Option | Mechanism | Verdict |
|---|---|---|
| **A — `set_context()` + downcast** | Orchestrator downcasts `Arc<dyn Tool>` → `&DelegateTool` | **Rejected.** `Tool` is upstream and frozen; it does not extend `Any`, so no safe downcast is available. Adding `Any` would require modifying `amplifier-core`, which is a hard constraint violation. Also couples orchestrator to a concrete type. |
| **B — `ContextAwareTool` super-trait** | New trait `ContextAwareTool: Tool` in `tool-task`; orchestrator keeps a parallel registry of context-aware tools and dispatches `set_execution_context` before `execute()` | **Selected.** Type-safe, no `unsafe`, no `Any`, no hidden state, no upstream changes. Tools that don't care about context don't have to participate. Easy to test in isolation. |
| **C — Task-local context** | `tokio::task_local!` constant in shared crate; orchestrator wraps `execute().await` in `LOCAL.scope(...)`; tool reads via `LOCAL.with(...)` | Rejected. Hidden, ambient state. Implicit dependencies are harder to reason about, harder to test, and harder to discover (a future tool author would not know context is available unless they read the orchestrator). Silent fallback if scope is missing. The decoupling argument is moot because both sides already share `tool-task`. |

**Trait location:** `amplifier-module-tool-task` (not `orchestrator-loop-streaming` as originally sketched in Option B). Reason: `tool-delegate` already depends on `tool-task`; the orchestrator already depends on `tool-task`. Putting the trait there avoids forcing `tool-delegate` to take an upward dependency on the orchestrator crate, which would be a strange topology (tools knowing about orchestrators).

**Concurrency primitive:** `std::sync::Mutex<Vec<Value>>` inside an `Arc`. Cheap to set, only contended at tool-dispatch boundaries (sequential within a single agent loop), no `.await` while holding the lock.

---

## Files Affected

| File | Change |
|---|---|
| `crates/amplifier-module-tool-task/src/lib.rs` | Add `ContextAwareTool` trait |
| `crates/amplifier-module-tool-delegate/src/context.rs` | Implement `build_inherited_context` (currently a stub returning `None`) |
| `crates/amplifier-module-tool-delegate/src/lib.rs` | Add `context: Arc<Mutex<Vec<Value>>>`, `impl ContextAwareTool`, wire `execute()` to use parsed depth/scope and prepend the context block |
| `crates/amplifier-module-tool-delegate/Cargo.toml` | Add `proptest` dev-dep |
| `crates/amplifier-module-tool-delegate/tests/property_tests.rs` | New — proptest harness |
| `crates/amplifier-module-orchestrator-loop-streaming/src/lib.rs` | Add `context_aware_tools` registry + `register_context_aware_tool`; call `set_execution_context` before tool dispatch |
| `crates/amplifier-module-orchestrator-loop-streaming/Cargo.toml` | Add path dep on `amplifier-module-tool-delegate` (dev-dep only, for integration test) |
| `crates/amplifier-module-orchestrator-loop-streaming/tests/context_threading_e2e.rs` | New — end-to-end test |

**No changes** to `amplifier-core`. **No changes** to the public `Tool` trait.

---

## Working Directory

All commands assume `cd /Users/ken/workspace/amplifier-rust` unless stated.

---

## Task 1: Add `ContextAwareTool` trait to `tool-task` (test first)

**Files:**
- Modify: `crates/amplifier-module-tool-task/src/lib.rs`

**Step 1: Write the failing test**

Append to the bottom of `crates/amplifier-module-tool-task/src/lib.rs`, inside the existing `#[cfg(test)] mod tests { ... }` block (just before the closing brace):

```rust
    // --- Test 8: context_aware_tool_trait_is_object_safe ---

    /// Verify that ContextAwareTool can be used as a trait object and that
    /// set_execution_context takes &self (interior mutability required).
    #[test]
    fn context_aware_tool_trait_is_object_safe() {
        use std::sync::{Arc, Mutex};

        struct StubCAT {
            seen: Arc<Mutex<Vec<Value>>>,
        }

        impl Tool for StubCAT {
            fn name(&self) -> &str { "stub" }
            fn description(&self) -> &str { "stub" }
            fn get_spec(&self) -> ToolSpec {
                ToolSpec {
                    name: "stub".to_string(),
                    parameters: HashMap::new(),
                    description: None,
                    extensions: HashMap::new(),
                }
            }
            fn execute(
                &self,
                _input: Value,
            ) -> Pin<Box<dyn Future<Output = Result<ToolResult, ToolError>> + Send + '_>> {
                Box::pin(async { Ok(ToolResult { success: true, output: None, error: None }) })
            }
        }

        impl ContextAwareTool for StubCAT {
            fn set_execution_context(&self, messages: Vec<Value>) {
                *self.seen.lock().unwrap() = messages;
            }
        }

        let seen = Arc::new(Mutex::new(Vec::new()));
        let stub: Arc<dyn ContextAwareTool> = Arc::new(StubCAT { seen: seen.clone() });
        stub.set_execution_context(vec![json!({"role": "user", "content": "hi"})]);
        assert_eq!(seen.lock().unwrap().len(), 1);
    }
```

**Step 2: Run the test to verify it fails to compile**

Run: `cargo test -p amplifier-module-tool-task context_aware_tool_trait_is_object_safe`
Expected: compile error — `cannot find trait ContextAwareTool`.

**Step 3: Implement the trait**

In `crates/amplifier-module-tool-task/src/lib.rs`, just below the `SubagentRunner` block (after the `}` that closes `#[async_trait::async_trait] pub trait SubagentRunner ...`), insert:

```rust
// ---------------------------------------------------------------------------
// ContextAwareTool
// ---------------------------------------------------------------------------

/// Extension trait for tools that need access to the parent agent's
/// conversation history before they execute.
///
/// The orchestrator calls [`set_execution_context`](Self::set_execution_context)
/// with the current message slice immediately before dispatching such a tool.
/// Implementations must use interior mutability (e.g. `Arc<Mutex<Vec<Value>>>`)
/// because `&self` is the only handle the orchestrator has.
///
/// # Object safety
///
/// This trait is object-safe: store as `Arc<dyn ContextAwareTool>`.
pub trait ContextAwareTool: Tool {
    /// Provide the parent agent's current conversation history.
    ///
    /// The slice is in the same JSON shape the orchestrator already passes to
    /// providers — each `Value` has a `"role"` and a `"content"` field.
    fn set_execution_context(&self, messages: Vec<Value>);
}
```

**Step 4: Run the test to verify it passes**

Run: `cargo test -p amplifier-module-tool-task context_aware_tool_trait_is_object_safe`
Expected: PASS.

**Step 5: Commit**

```bash
git add crates/amplifier-module-tool-task/src/lib.rs
git commit -m "feat(tool-task): add ContextAwareTool super-trait for parent-history threading"
```

---

## Task 2: Implement `build_inherited_context` — `ContextDepth::None` returns `None`

**Files:**
- Modify: `crates/amplifier-module-tool-delegate/src/context.rs`

**Step 1: Write the failing test**

Append a `#[cfg(test)]` block to `crates/amplifier-module-tool-delegate/src/context.rs`:

```rust
#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn turn(role: &str, text: &str) -> Value {
        json!({"role": role, "content": text})
    }

    // --- Test: depth_none_returns_none ---
    #[test]
    fn depth_none_returns_none() {
        let msgs = vec![turn("user", "hi"), turn("assistant", "hello")];
        let out = build_inherited_context(&msgs, &ContextDepth::None, 5, &ContextScope::Conversation);
        assert!(out.is_none(), "depth=None must return None, got: {:?}", out);
    }

    // --- Test: empty_messages_returns_none ---
    #[test]
    fn empty_messages_returns_none() {
        let out = build_inherited_context(&[], &ContextDepth::All, 5, &ContextScope::Conversation);
        assert!(out.is_none(), "empty messages must return None, got: {:?}", out);
    }
}
```

**Step 2: Run to verify pass (the stub already returns `None`)**

Run: `cargo test -p amplifier-module-tool-delegate --lib context::tests`
Expected: both tests PASS (the stub already trivially satisfies them).

**Step 3: Commit**

```bash
git add crates/amplifier-module-tool-delegate/src/context.rs
git commit -m "test(delegate): pin behaviour for depth=None and empty input in build_inherited_context"
```

---

## Task 3: Implement `ContextDepth::Recent(n)` with `ContextScope::Conversation`

**Files:**
- Modify: `crates/amplifier-module-tool-delegate/src/context.rs`

**Step 1: Add failing tests**

Add to the same `mod tests` block:

```rust
    // --- Test: recent_keeps_last_n_turn_pairs ---
    #[test]
    fn recent_keeps_last_n_turn_pairs() {
        // 3 turn pairs (user/assistant) = 6 messages total
        let msgs = vec![
            turn("user", "u1"), turn("assistant", "a1"),
            turn("user", "u2"), turn("assistant", "a2"),
            turn("user", "u3"), turn("assistant", "a3"),
        ];
        let out = build_inherited_context(&msgs, &ContextDepth::Recent(2), 2, &ContextScope::Conversation)
            .expect("expected Some(context) for non-empty Recent");
        // Last 2 pairs = u2/a2/u3/a3. u1/a1 should be excluded.
        assert!(!out.contains("u1"), "u1 should be excluded, got: {out}");
        assert!(!out.contains("a1"), "a1 should be excluded, got: {out}");
        assert!(out.contains("u2") && out.contains("a2"), "u2/a2 should be present, got: {out}");
        assert!(out.contains("u3") && out.contains("a3"), "u3/a3 should be present, got: {out}");
    }

    // --- Test: recent_zero_yields_none ---
    #[test]
    fn recent_zero_yields_none() {
        let msgs = vec![turn("user", "u1"), turn("assistant", "a1")];
        let out = build_inherited_context(&msgs, &ContextDepth::Recent(0), 0, &ContextScope::Conversation);
        assert!(out.is_none(), "Recent(0) must return None, got: {:?}", out);
    }

    // --- Test: scope_conversation_drops_tool_results ---
    #[test]
    fn scope_conversation_drops_tool_results() {
        let msgs = vec![
            turn("user", "u1"),
            turn("assistant", "a1"),
            json!({"role": "user", "content": [{"type": "tool_result", "tool_call_id": "x", "output": "junk"}]}),
        ];
        let out = build_inherited_context(&msgs, &ContextDepth::All, 5, &ContextScope::Conversation)
            .expect("expected Some");
        assert!(!out.contains("junk"), "tool_result must be filtered out under Conversation scope, got: {out}");
        assert!(out.contains("u1") && out.contains("a1"));
    }
```

**Step 2: Run to verify they fail**

Run: `cargo test -p amplifier-module-tool-delegate --lib context::tests::recent`
Expected: tests fail (stub returns `None` always).

**Step 3: Implement**

Replace the entire body of `build_inherited_context` in `crates/amplifier-module-tool-delegate/src/context.rs` with:

```rust
//! Context building — produces the [PARENT CONVERSATION CONTEXT] block for delegated sub-agents.

use serde_json::Value;

use amplifier_module_tool_task::{ContextDepth, ContextScope};

/// Build inherited context from messages for a sub-agent.
///
/// # Arguments
/// * `messages` — Source messages to slice context from. Each value is a
///   `{"role": ..., "content": ...}` JSON object as produced by the orchestrator.
/// * `depth` — How much context to include.
/// * `turns` — Maximum turn pairs when depth is `Recent`. One turn = 1 user + 1 assistant
///   message, so `Recent(n)` keeps the last `2*n` filtered messages.
/// * `scope` — Which categories to include (see [`apply_scope`]).
///
/// Returns `None` when context is empty, `ContextDepth::None`, or `Recent(0)`.
pub fn build_inherited_context(
    messages: &[Value],
    depth: &ContextDepth,
    turns: usize,
    scope: &ContextScope,
) -> Option<String> {
    if matches!(depth, ContextDepth::None) {
        return None;
    }
    if messages.is_empty() {
        return None;
    }

    // Apply scope filter first, then depth slicing.
    let filtered: Vec<&Value> = messages.iter().filter(|m| keep_for_scope(m, scope)).collect();

    let sliced: &[&Value] = match depth {
        ContextDepth::None => return None,
        ContextDepth::All => &filtered,
        ContextDepth::Recent(n) => {
            let want = n.saturating_mul(2);
            if want == 0 || filtered.is_empty() {
                return None;
            }
            let start = filtered.len().saturating_sub(want);
            &filtered[start..]
        }
    };

    if sliced.is_empty() {
        return None;
    }

    let mut buf = String::new();
    for msg in sliced {
        let role = msg.get("role").and_then(|v| v.as_str()).unwrap_or("unknown");
        let rendered = render_content(msg.get("content").unwrap_or(&Value::Null));
        if rendered.is_empty() {
            continue;
        }
        buf.push_str(role);
        buf.push_str(": ");
        buf.push_str(&rendered);
        buf.push('\n');
    }

    if buf.is_empty() {
        None
    } else {
        Some(buf)
    }
}

/// Returns true if a message should be kept under the given scope.
fn keep_for_scope(msg: &Value, scope: &ContextScope) -> bool {
    let role = msg.get("role").and_then(|v| v.as_str()).unwrap_or("");
    let content = msg.get("content").unwrap_or(&Value::Null);
    let has_tool_result = content_has_block_type(content, "tool_result");
    let has_tool_call = content_has_block_type(content, "tool_call")
        || content_has_block_type(content, "tool_use");

    match scope {
        ContextScope::Conversation => {
            // Plain user / assistant text only.
            (role == "user" || role == "assistant" || role == "system")
                && !has_tool_result
                && !has_tool_call
        }
        ContextScope::Agents => {
            // Conversation + tool_results from delegate/spawn_agent only.
            if has_tool_result {
                tool_result_is_agent(content)
            } else if has_tool_call {
                tool_call_is_agent(content)
            } else {
                true
            }
        }
        ContextScope::Full => true,
    }
}

fn content_has_block_type(content: &Value, ty: &str) -> bool {
    if let Some(arr) = content.as_array() {
        arr.iter().any(|b| b.get("type").and_then(|t| t.as_str()) == Some(ty))
    } else {
        false
    }
}

/// True if every tool_result block in `content` corresponds to a known
/// agent-spawning tool (delegate or spawn_agent).
fn tool_result_is_agent(content: &Value) -> bool {
    let Some(arr) = content.as_array() else { return false };
    let mut any = false;
    for b in arr {
        if b.get("type").and_then(|t| t.as_str()) == Some("tool_result") {
            any = true;
            // The orchestrator stores tool results without the original tool name;
            // an "agent" tool_result is one whose tool_name (if present) matches.
            let name = b.get("tool_name").and_then(|n| n.as_str()).unwrap_or("");
            if !is_agent_tool_name(name) {
                return false;
            }
        }
    }
    any
}

fn tool_call_is_agent(content: &Value) -> bool {
    let Some(arr) = content.as_array() else { return false };
    let mut any = false;
    for b in arr {
        let ty = b.get("type").and_then(|t| t.as_str()).unwrap_or("");
        if ty == "tool_call" || ty == "tool_use" {
            any = true;
            let name = b.get("name").and_then(|n| n.as_str()).unwrap_or("");
            if !is_agent_tool_name(name) {
                return false;
            }
        }
    }
    any
}

fn is_agent_tool_name(name: &str) -> bool {
    matches!(name, "delegate" | "spawn_agent")
}

/// Render a message's `content` field as plain text for the context block.
fn render_content(content: &Value) -> String {
    match content {
        Value::String(s) => s.clone(),
        Value::Array(blocks) => {
            let mut parts: Vec<String> = Vec::new();
            for b in blocks {
                let ty = b.get("type").and_then(|t| t.as_str()).unwrap_or("");
                match ty {
                    "text" => {
                        if let Some(t) = b.get("text").and_then(|v| v.as_str()) {
                            parts.push(t.to_string());
                        }
                    }
                    "tool_call" | "tool_use" => {
                        let name = b.get("name").and_then(|n| n.as_str()).unwrap_or("?");
                        parts.push(format!("[tool_call:{name}]"));
                    }
                    "tool_result" => {
                        let id = b.get("tool_call_id").and_then(|n| n.as_str()).unwrap_or("?");
                        let out = b.get("output").map(|v| v.to_string()).unwrap_or_default();
                        parts.push(format!("[tool_result:{id} {out}]"));
                    }
                    _ => {}
                }
            }
            parts.join(" ")
        }
        _ => String::new(),
    }
}
```

**Step 4: Run tests to verify pass**

Run: `cargo test -p amplifier-module-tool-delegate --lib context::tests`
Expected: all 5 tests PASS.

**Step 5: Commit**

```bash
git add crates/amplifier-module-tool-delegate/src/context.rs
git commit -m "feat(delegate): implement build_inherited_context with depth + Conversation scope"
```

---

## Task 4: Add tests for `ContextScope::Agents` and `ContextScope::Full`

**Files:**
- Modify: `crates/amplifier-module-tool-delegate/src/context.rs`

**Step 1: Write failing tests**

Append to the `mod tests` block:

```rust
    // --- Test: scope_agents_keeps_only_delegate_tool_results ---
    #[test]
    fn scope_agents_keeps_only_delegate_tool_results() {
        let msgs = vec![
            turn("user", "u1"),
            turn("assistant", "a1"),
            json!({"role": "user", "content": [
                {"type": "tool_result", "tool_call_id": "1", "tool_name": "delegate", "output": "from_agent"}
            ]}),
            json!({"role": "user", "content": [
                {"type": "tool_result", "tool_call_id": "2", "tool_name": "bash", "output": "from_bash"}
            ]}),
        ];
        let out = build_inherited_context(&msgs, &ContextDepth::All, 5, &ContextScope::Agents)
            .expect("expected Some");
        assert!(out.contains("from_agent"), "delegate result must be kept, got: {out}");
        assert!(!out.contains("from_bash"), "bash result must be filtered, got: {out}");
        assert!(out.contains("u1") && out.contains("a1"));
    }

    // --- Test: scope_full_keeps_everything ---
    #[test]
    fn scope_full_keeps_everything() {
        let msgs = vec![
            turn("user", "u1"),
            json!({"role": "user", "content": [
                {"type": "tool_result", "tool_call_id": "1", "tool_name": "bash", "output": "bash_out"}
            ]}),
        ];
        let out = build_inherited_context(&msgs, &ContextDepth::All, 5, &ContextScope::Full)
            .expect("expected Some");
        assert!(out.contains("u1"));
        assert!(out.contains("bash_out"), "Full scope must keep bash result, got: {out}");
    }
```

**Step 2: Run to verify pass**

Run: `cargo test -p amplifier-module-tool-delegate --lib context::tests::scope`
Expected: both PASS (already implemented in Task 3).

**Step 3: Commit**

```bash
git add crates/amplifier-module-tool-delegate/src/context.rs
git commit -m "test(delegate): cover Agents/Full scopes in build_inherited_context"
```

---

## Task 5: Add proptest harness — never panics for arbitrary inputs

**Files:**
- Modify: `crates/amplifier-module-tool-delegate/Cargo.toml`
- Create: `crates/amplifier-module-tool-delegate/tests/property_tests.rs`

**Step 1: Add proptest dep**

Edit `crates/amplifier-module-tool-delegate/Cargo.toml`. Replace the `[dev-dependencies]` block with:

```toml
[dev-dependencies]
tokio = { workspace = true }
async-trait = { workspace = true }
proptest = "1"
```

**Step 2: Create the property test file**

Create `crates/amplifier-module-tool-delegate/tests/property_tests.rs`:

```rust
//! Property tests for build_inherited_context.
//!
//! Invariant under test: for any sequence of role/content messages,
//! `build_inherited_context(msgs, ContextDepth::All, _, ContextScope::Full)`
//! never panics, and the returned `Option<String>` is internally consistent
//! (Some implies non-empty, None implies all messages were empty/filtered).

use amplifier_module_tool_delegate::context::build_inherited_context;
use amplifier_module_tool_task::{ContextDepth, ContextScope};
use proptest::prelude::*;
use serde_json::{json, Value};

/// Strategy: arbitrary "role" string from a fixed set + arbitrary text content.
fn arb_message() -> impl Strategy<Value = Value> {
    let role = prop_oneof![
        Just("user".to_string()),
        Just("assistant".to_string()),
        Just("system".to_string()),
        "[a-z]{1,8}".prop_map(|s| s),
    ];
    (role, ".{0,64}").prop_map(|(r, c)| json!({"role": r, "content": c}))
}

proptest! {
    #[test]
    fn build_inherited_context_never_panics_full(msgs in proptest::collection::vec(arb_message(), 0..32)) {
        let _ = build_inherited_context(&msgs, &ContextDepth::All, 0, &ContextScope::Full);
    }

    #[test]
    fn build_inherited_context_never_panics_recent(
        msgs in proptest::collection::vec(arb_message(), 0..32),
        n in 0usize..16,
    ) {
        let _ = build_inherited_context(&msgs, &ContextDepth::Recent(n), n, &ContextScope::Conversation);
    }

    #[test]
    fn build_inherited_context_some_implies_non_empty(msgs in proptest::collection::vec(arb_message(), 0..16)) {
        if let Some(s) = build_inherited_context(&msgs, &ContextDepth::All, 0, &ContextScope::Full) {
            prop_assert!(!s.is_empty(), "Some(_) must wrap a non-empty string");
        }
    }
}
```

**Step 3: Run the tests to verify they pass**

Run: `cargo test -p amplifier-module-tool-delegate --test property_tests`
Expected: all three properties PASS (proptest will run 256 cases per property by default).

**Step 4: Commit**

```bash
git add crates/amplifier-module-tool-delegate/Cargo.toml crates/amplifier-module-tool-delegate/tests/property_tests.rs
git commit -m "test(delegate): proptest harness for build_inherited_context invariants"
```

---

## Task 6: Add `context` field + `ContextAwareTool` impl on `DelegateTool`

**Files:**
- Modify: `crates/amplifier-module-tool-delegate/src/lib.rs`

**Step 1: Write a failing test**

In the `#[cfg(test)] mod tests` block at the bottom of `crates/amplifier-module-tool-delegate/src/lib.rs`, add:

```rust
    // --- Test 5: delegate_tool_implements_context_aware ---

    /// Verify that DelegateTool can be used as `Arc<dyn ContextAwareTool>` and
    /// that set_execution_context stores the messages.
    #[test]
    fn delegate_tool_implements_context_aware() {
        use amplifier_module_tool_task::ContextAwareTool;
        use serde_json::json;

        let runner: Arc<dyn SubagentRunner> = Arc::new(NopRunner);
        let registry = Arc::new(AgentRegistry::new());
        let tool = Arc::new(DelegateTool::new(runner, registry, DelegateConfig::default()));
        let cat: Arc<dyn ContextAwareTool> = tool.clone();
        cat.set_execution_context(vec![json!({"role": "user", "content": "hi"})]);
        let snapshot = tool.snapshot_context();
        assert_eq!(snapshot.len(), 1);
        assert_eq!(snapshot[0]["role"], "user");
    }
```

**Step 2: Run the test to verify it fails**

Run: `cargo test -p amplifier-module-tool-delegate --lib delegate_tool_implements_context_aware`
Expected: compile error — `ContextAwareTool` not implemented and `snapshot_context` not found.

**Step 3: Implement**

In `crates/amplifier-module-tool-delegate/src/lib.rs`:

(a) Update the `DelegateTool` struct (replacing the existing definition):

```rust
/// Tool that enables an agent to delegate work to a named sub-agent.
pub struct DelegateTool {
    #[allow(dead_code)]
    runner: Arc<dyn SubagentRunner>,
    #[allow(dead_code)]
    registry: Arc<AgentRegistry>,
    #[allow(dead_code)]
    config: DelegateConfig,
    /// Parent conversation history, set by the orchestrator immediately before dispatch.
    context: Arc<std::sync::Mutex<Vec<Value>>>,
}
```

(b) Update `DelegateTool::new`:

```rust
impl DelegateTool {
    /// Create a new [`DelegateTool`].
    pub fn new(
        runner: Arc<dyn SubagentRunner>,
        registry: Arc<AgentRegistry>,
        config: DelegateConfig,
    ) -> Self {
        Self {
            runner,
            registry,
            config,
            context: Arc::new(std::sync::Mutex::new(Vec::new())),
        }
    }

    /// Test/debug helper: clone the current parent-context snapshot.
    pub fn snapshot_context(&self) -> Vec<Value> {
        self.context.lock().unwrap().clone()
    }
}
```

(c) Add the `ContextAwareTool` impl just below the `Tool for DelegateTool` block (after the closing `}` at line ~200):

```rust
impl amplifier_module_tool_task::ContextAwareTool for DelegateTool {
    fn set_execution_context(&self, messages: Vec<Value>) {
        *self.context.lock().unwrap() = messages;
    }
}
```

**Note:** `Value` is already imported at the top of the file (`use serde_json::Value;`).

**Step 4: Run the test to verify it passes**

Run: `cargo test -p amplifier-module-tool-delegate --lib delegate_tool_implements_context_aware`
Expected: PASS.

**Step 5: Commit**

```bash
git add crates/amplifier-module-tool-delegate/src/lib.rs
git commit -m "feat(delegate): implement ContextAwareTool on DelegateTool"
```

---

## Task 7: Wire `DelegateTool::execute` to use the context buffer

**Files:**
- Modify: `crates/amplifier-module-tool-delegate/src/lib.rs`

**Step 1: Write a failing test**

Append to the `mod tests` block:

```rust
    // --- Test 6: execute_prepends_parent_context_block ---

    /// When the orchestrator has called set_execution_context with prior turns
    /// and the tool input requests context_depth=recent, the runner must receive
    /// an instruction beginning with `[PARENT CONVERSATION CONTEXT]`.
    #[tokio::test]
    async fn execute_prepends_parent_context_block() {
        use amplifier_module_tool_task::ContextAwareTool;
        use serde_json::json;
        use std::sync::Mutex;

        struct Capturing {
            seen: Arc<Mutex<Option<String>>>,
        }
        #[async_trait::async_trait]
        impl SubagentRunner for Capturing {
            async fn run(&self, req: SpawnRequest) -> anyhow::Result<String> {
                *self.seen.lock().unwrap() = Some(req.instruction.clone());
                // Also assert that req.context stays empty (ephemeral)
                assert!(req.context.is_empty(), "req.context must stay empty; got: {:?}", req.context);
                Ok("ok".to_string())
            }
        }

        let seen = Arc::new(Mutex::new(None));
        let runner: Arc<dyn SubagentRunner> = Arc::new(Capturing { seen: seen.clone() });
        let registry = Arc::new(AgentRegistry::new());
        let tool = Arc::new(DelegateTool::new(runner, registry, DelegateConfig::default()));

        // Parent context: 1 turn pair.
        let cat: Arc<dyn ContextAwareTool> = tool.clone();
        cat.set_execution_context(vec![
            json!({"role": "user", "content": "tell me about rust"}),
            json!({"role": "assistant", "content": "rust is a systems language"}),
        ]);

        let input = json!({
            "agent": "explorer",
            "instruction": "summarise what we discussed",
            "context_depth": "recent"
        });
        let result = tool.execute(input).await.expect("execute ok");
        assert!(result.success);

        let captured = seen.lock().unwrap().clone().expect("runner not called");
        assert!(
            captured.contains("[PARENT CONVERSATION CONTEXT]"),
            "instruction must contain context block, got: {captured}"
        );
        assert!(captured.contains("rust is a systems language"));
        assert!(captured.contains("summarise what we discussed"));
    }

    // --- Test 7: execute_with_depth_none_omits_context_block ---
    #[tokio::test]
    async fn execute_with_depth_none_omits_context_block() {
        use amplifier_module_tool_task::ContextAwareTool;
        use serde_json::json;
        use std::sync::Mutex;

        struct Capturing { seen: Arc<Mutex<Option<String>>> }
        #[async_trait::async_trait]
        impl SubagentRunner for Capturing {
            async fn run(&self, req: SpawnRequest) -> anyhow::Result<String> {
                *self.seen.lock().unwrap() = Some(req.instruction.clone());
                Ok("ok".to_string())
            }
        }

        let seen = Arc::new(Mutex::new(None));
        let runner: Arc<dyn SubagentRunner> = Arc::new(Capturing { seen: seen.clone() });
        let registry = Arc::new(AgentRegistry::new());
        let tool = Arc::new(DelegateTool::new(runner, registry, DelegateConfig::default()));

        let cat: Arc<dyn ContextAwareTool> = tool.clone();
        cat.set_execution_context(vec![json!({"role": "user", "content": "should be ignored"})]);

        let input = json!({
            "agent": "explorer",
            "instruction": "do the thing",
            "context_depth": "none"
        });
        tool.execute(input).await.unwrap();
        let captured = seen.lock().unwrap().clone().unwrap();
        assert!(!captured.contains("[PARENT CONVERSATION CONTEXT]"), "got: {captured}");
        assert_eq!(captured, "do the thing");
    }
```

**Step 2: Run to verify they fail**

Run: `cargo test -p amplifier-module-tool-delegate --lib execute_prepends_parent_context_block execute_with_depth_none_omits_context_block`
Expected: tests FAIL — current `execute()` always passes `instruction` raw and `context_depth: ContextDepth::None`.

**Step 3: Update `execute()`**

In `crates/amplifier-module-tool-delegate/src/lib.rs`, replace the entire `fn execute(...)` body inside `impl Tool for DelegateTool` (lines ~152-199) with:

```rust
    fn execute(
        &self,
        input: Value,
    ) -> Pin<Box<dyn Future<Output = Result<ToolResult, ToolError>> + Send + '_>> {
        let runner = Arc::clone(&self.runner);
        let registry = Arc::clone(&self.registry);
        let context = Arc::clone(&self.context);
        let max_turns = self.config.max_context_turns;
        Box::pin(async move {
            let agent = input["agent"]
                .as_str()
                .ok_or_else(|| ToolError::Other { message: "agent is required".into() })?
                .to_string();
            let raw_instruction = input["instruction"]
                .as_str()
                .ok_or_else(|| ToolError::Other { message: "instruction is required".into() })?
                .to_string();

            // Parse context_depth (default: None)
            let context_depth = match input.get("context_depth").and_then(|v| v.as_str()) {
                Some("recent") | Some("recent_5") => {
                    amplifier_module_tool_task::ContextDepth::Recent(max_turns)
                }
                Some("all") => amplifier_module_tool_task::ContextDepth::All,
                _ => amplifier_module_tool_task::ContextDepth::None,
            };

            // Parse context_scope (default: Conversation)
            let context_scope = match input.get("context_scope").and_then(|v| v.as_str()) {
                Some("agents") => amplifier_module_tool_task::ContextScope::Agents,
                Some("full") => amplifier_module_tool_task::ContextScope::Full,
                _ => amplifier_module_tool_task::ContextScope::Conversation,
            };

            // Build context block from parent history (snapshot the buffer locally).
            let parent_messages = context.lock().unwrap().clone();
            let inherited = crate::context::build_inherited_context(
                &parent_messages,
                &context_depth,
                max_turns,
                &context_scope,
            );

            let effective_instruction = match inherited {
                Some(block) => format!(
                    "[PARENT CONVERSATION CONTEXT]\n{block}[END PARENT CONTEXT]\n\n{raw_instruction}"
                ),
                None => raw_instruction,
            };

            // Resolve agent system prompt from registry if available.
            let agent_system_prompt = registry.get(&agent).map(|c| c.instruction.clone());

            let req = SpawnRequest {
                instruction: effective_instruction,
                context_depth,
                context_scope,
                // Ephemeral: parent history is embedded in the instruction string only.
                context: vec![],
                session_id: None,
                agent_system_prompt,
                tool_filter: vec![],
            };

            let result = runner
                .run(req)
                .await
                .map_err(|e| ToolError::ExecutionFailed {
                    message: e.to_string(),
                    stdout: None,
                    stderr: None,
                    exit_code: None,
                })?;

            Ok(ToolResult {
                success: true,
                output: Some(serde_json::Value::String(result)),
                error: None,
            })
        })
    }
```

**Step 4: Run the tests to verify they pass**

Run: `cargo test -p amplifier-module-tool-delegate`
Expected: all delegate tests PASS.

**Step 5: Commit**

```bash
git add crates/amplifier-module-tool-delegate/src/lib.rs
git commit -m "feat(delegate): thread parent context into instruction; req.context stays empty"
```

---

## Task 8: Add parallel `context_aware_tools` registry to orchestrator

**Files:**
- Modify: `crates/amplifier-module-orchestrator-loop-streaming/src/lib.rs`

**Step 1: Write a failing test**

In the `#[cfg(test)] mod tests` block at the bottom of `crates/amplifier-module-orchestrator-loop-streaming/src/lib.rs`, add:

```rust
    // -----------------------------------------------------------------------
    // Test 4: register_context_aware_tool_inserts_into_both_registries
    // -----------------------------------------------------------------------

    use amplifier_core::errors::ToolError;
    use amplifier_core::models::ToolResult;
    use amplifier_module_tool_task::ContextAwareTool;
    use std::sync::Mutex as StdMutex;

    struct StubCAT {
        seen: Arc<StdMutex<Vec<Value>>>,
    }

    impl Tool for StubCAT {
        fn name(&self) -> &str { "stub" }
        fn description(&self) -> &str { "stub" }
        fn get_spec(&self) -> ToolSpec {
            ToolSpec {
                name: "stub".to_string(),
                parameters: HashMap::new(),
                description: None,
                extensions: HashMap::new(),
            }
        }
        fn execute(
            &self,
            _input: Value,
        ) -> Pin<Box<dyn Future<Output = Result<ToolResult, ToolError>> + Send + '_>> {
            Box::pin(async { Ok(ToolResult { success: true, output: None, error: None }) })
        }
    }

    impl ContextAwareTool for StubCAT {
        fn set_execution_context(&self, messages: Vec<Value>) {
            *self.seen.lock().unwrap() = messages;
        }
    }

    #[tokio::test]
    async fn register_context_aware_tool_inserts_into_both_registries() {
        let orchestrator = LoopOrchestrator::new(LoopConfig::default());
        let stub = Arc::new(StubCAT { seen: Arc::new(StdMutex::new(Vec::new())) });
        orchestrator.register_context_aware_tool(stub.clone()).await;

        let tools = orchestrator.snapshot_tools().await;
        assert!(tools.contains_key("stub"), "must be in regular tools registry");

        let cats = orchestrator.snapshot_context_aware_tools().await;
        assert!(cats.contains_key("stub"), "must be in context-aware registry");
    }
```

**Step 2: Run to verify it fails**

Run: `cargo test -p amplifier-module-orchestrator-loop-streaming register_context_aware_tool_inserts_into_both_registries`
Expected: compile error — `register_context_aware_tool` and `snapshot_context_aware_tools` don't exist.

**Step 3: Implement**

In `crates/amplifier-module-orchestrator-loop-streaming/src/lib.rs`:

(a) Add the import at the top, just below the existing `use amplifier_module_tool_task::{...}` line:

```rust
use amplifier_module_tool_task::ContextAwareTool;
```

(b) Update the imports line `use amplifier_module_tool_task::{SpawnRequest, SubagentRunner};` — leave it alone (no change needed; we add `ContextAwareTool` separately for clarity).

(c) Update the `LoopOrchestrator` struct (replace existing definition):

```rust
/// Agent-loop orchestrator with hook integration, step limit, and tool dispatch.
pub struct LoopOrchestrator {
    pub config: LoopConfig,
    pub providers: RwLock<HashMap<String, Arc<dyn Provider>>>,
    pub tools: RwLock<HashMap<String, Arc<dyn Tool>>>,
    pub context_aware_tools: RwLock<HashMap<String, Arc<dyn ContextAwareTool>>>,
}
```

(d) Update `LoopOrchestrator::new`:

```rust
    pub fn new(config: LoopConfig) -> Self {
        Self {
            config,
            providers: RwLock::new(HashMap::new()),
            tools: RwLock::new(HashMap::new()),
            context_aware_tools: RwLock::new(HashMap::new()),
        }
    }
```

(e) Add new registration + snapshot methods (insert just below the existing `pub async fn register_tool(&self, ...)`):

```rust
    /// Register a context-aware tool. The tool is inserted into BOTH the regular
    /// tools registry (so it dispatches normally) AND the context-aware registry
    /// (so the loop can call `set_execution_context` on it before dispatch).
    pub async fn register_context_aware_tool(&self, tool: Arc<dyn ContextAwareTool>) {
        let name = tool.get_spec().name.clone();
        // Cast to Arc<dyn Tool> via re-arc: tool already implements Tool by super-trait.
        self.tools
            .write()
            .await
            .insert(name.clone(), tool.clone() as Arc<dyn Tool>);
        self.context_aware_tools.write().await.insert(name, tool);
    }

    /// Return a snapshot clone of the context-aware tools map.
    pub async fn snapshot_context_aware_tools(&self) -> HashMap<String, Arc<dyn ContextAwareTool>> {
        self.context_aware_tools.read().await.clone()
    }
```

**Step 4: Run the test to verify it passes**

Run: `cargo test -p amplifier-module-orchestrator-loop-streaming register_context_aware_tool_inserts_into_both_registries`
Expected: PASS.

**Step 5: Commit**

```bash
git add crates/amplifier-module-orchestrator-loop-streaming/src/lib.rs
git commit -m "feat(orchestrator): add parallel context-aware tools registry"
```

---

## Task 9: Orchestrator dispatches context to context-aware tools before `execute()`

**Files:**
- Modify: `crates/amplifier-module-orchestrator-loop-streaming/src/lib.rs`

**Step 1: Write a failing test**

Append to the `mod tests` block (relies on `StubCAT` from Task 8):

```rust
    // -----------------------------------------------------------------------
    // Test 5: orchestrator_passes_messages_to_context_aware_tool_before_dispatch
    // -----------------------------------------------------------------------

    use amplifier_core::messages::ToolCall as CoreToolCall;
    use amplifier_module_context_simple::SimpleContext;

    /// Mock provider: returns a single tool_call to "stub" on the first call,
    /// then end_turn on the second.
    struct StubCallingProvider {
        call_count: Arc<StdMutex<usize>>,
    }
    impl StubCallingProvider {
        fn new() -> Self { Self { call_count: Arc::new(StdMutex::new(0)) } }
    }
    impl Provider for StubCallingProvider {
        fn name(&self) -> &str { "stub" }
        fn get_info(&self) -> ProviderInfo {
            ProviderInfo {
                id: "stub".into(),
                display_name: "stub".into(),
                credential_env_vars: vec![],
                capabilities: vec![],
                defaults: HashMap::new(),
                config_fields: vec![],
            }
        }
        fn list_models(&self) -> Pin<Box<dyn Future<Output = Result<Vec<ModelInfo>, ProviderError>> + Send + '_>> {
            Box::pin(async { Ok(vec![]) })
        }
        fn complete(&self, _req: ChatRequest) -> Pin<Box<dyn Future<Output = Result<ChatResponse, ProviderError>> + Send + '_>> {
            let count = self.call_count.clone();
            Box::pin(async move {
                let n = { let mut g = count.lock().unwrap(); *g += 1; *g };
                if n == 1 {
                    Ok(ChatResponse {
                        content: vec![ContentBlock::ToolCall {
                            id: "call-1".into(),
                            name: "stub".into(),
                            input: HashMap::new(),
                            visibility: None,
                            extensions: HashMap::new(),
                        }],
                        tool_calls: None,
                        usage: None,
                        degradation: None,
                        finish_reason: Some("tool_use".into()),
                        metadata: None,
                        extensions: HashMap::new(),
                    })
                } else {
                    Ok(ChatResponse {
                        content: vec![ContentBlock::Text {
                            text: "done".into(),
                            visibility: None,
                            extensions: HashMap::new(),
                        }],
                        tool_calls: None,
                        usage: None,
                        degradation: None,
                        finish_reason: Some("end_turn".into()),
                        metadata: None,
                        extensions: HashMap::new(),
                    })
                }
            })
        }
        fn parse_tool_calls(&self, response: &ChatResponse) -> Vec<CoreToolCall> {
            response.content.iter().filter_map(|b| {
                if let ContentBlock::ToolCall { id, name, input, .. } = b {
                    Some(CoreToolCall {
                        id: id.clone(),
                        name: name.clone(),
                        arguments: input.clone(),
                    })
                } else { None }
            }).collect()
        }
    }

    #[tokio::test]
    async fn orchestrator_passes_messages_to_context_aware_tool_before_dispatch() {
        let orchestrator = LoopOrchestrator::new(LoopConfig {
            max_steps: Some(5),
            system_prompt: String::new(),
        });
        let provider: Arc<dyn Provider> = Arc::new(StubCallingProvider::new());
        orchestrator.register_provider("anthropic".into(), provider).await;

        let seen = Arc::new(StdMutex::new(Vec::new()));
        let stub = Arc::new(StubCAT { seen: seen.clone() });
        orchestrator.register_context_aware_tool(stub).await;

        let mut ctx = SimpleContext::new(vec![]);
        let hooks = HookRegistry::new();
        let _ = orchestrator
            .execute("the user prompt".to_string(), &mut ctx, &hooks, |_| {})
            .await
            .expect("execute ok");

        let captured = seen.lock().unwrap().clone();
        assert!(
            !captured.is_empty(),
            "context-aware tool must have received at least one message before dispatch"
        );
        // The user prompt is in the message list.
        let has_user_prompt = captured.iter().any(|m| {
            m.get("role").and_then(|v| v.as_str()) == Some("user")
                && m.to_string().contains("the user prompt")
        });
        assert!(has_user_prompt, "captured messages must contain the user prompt; got: {captured:?}");
    }
```

**Step 2: Run to verify it fails**

Run: `cargo test -p amplifier-module-orchestrator-loop-streaming orchestrator_passes_messages_to_context_aware_tool_before_dispatch`
Expected: FAIL — `seen` is empty (orchestrator never calls `set_execution_context`).

**Step 3: Wire dispatch**

In `crates/amplifier-module-orchestrator-loop-streaming/src/lib.rs`, modify the `execute()` method body. Locate the `for call in &tool_calls { ... }` loop (starts around line 250). Make two changes:

(a) Just before that `for call in &tool_calls` loop, snapshot the context-aware tools map. Insert these lines immediately after `let tool_calls = provider.parse_tool_calls(&response);` and before `let mut result_blocks: Vec<Value> = Vec::new();`:

```rust
                    // Snapshot the context-aware tool map and the messages slice
                    // we'll thread to context-aware tools before dispatch.
                    let cat_snapshot = self.snapshot_context_aware_tools().await;
                    let context_messages: Vec<Value> = context
                        .get_messages_for_request(None, None)
                        .await
                        .map_err(|e| anyhow::anyhow!("get_messages_for_request failed: {e}"))?;
```

(b) Inside the per-call dispatch (the `else if let Some(tool) = tools.get(&call.name)` branch around line 277), insert the `set_execution_context` call **before** `tool.execute(args_value).await`. Replace:

```rust
                        let output = if let Some(reason) = denied {
                            json!(format!("Tool execution denied: {reason}"))
                        } else if let Some(tool) = tools.get(&call.name) {
                            match tool.execute(args_value).await {
                                Ok(result) => result.output.unwrap_or(json!("")),
                                Err(e) => json!(format!("Error: {e}")),
                            }
                        } else {
                            json!(format!("Unknown tool: {}", call.name))
                        };
```

with:

```rust
                        let output = if let Some(reason) = denied {
                            json!(format!("Tool execution denied: {reason}"))
                        } else if let Some(tool) = tools.get(&call.name) {
                            // Context-aware tools: hand them the parent message slice
                            // immediately before dispatch.
                            if let Some(cat) = cat_snapshot.get(&call.name) {
                                cat.set_execution_context(context_messages.clone());
                            }
                            match tool.execute(args_value).await {
                                Ok(result) => result.output.unwrap_or(json!("")),
                                Err(e) => json!(format!("Error: {e}")),
                            }
                        } else {
                            json!(format!("Unknown tool: {}", call.name))
                        };
```

**Step 4: Run the test to verify it passes**

Run: `cargo test -p amplifier-module-orchestrator-loop-streaming orchestrator_passes_messages_to_context_aware_tool_before_dispatch`
Expected: PASS.

**Step 5: Run the full orchestrator test suite to verify nothing regressed**

Run: `cargo test -p amplifier-module-orchestrator-loop-streaming`
Expected: all PASS.

**Step 6: Commit**

```bash
git add crates/amplifier-module-orchestrator-loop-streaming/src/lib.rs
git commit -m "feat(orchestrator): set_execution_context on context-aware tools before dispatch"
```

---

## Task 10: End-to-end integration test — full loop with delegate

**Files:**
- Modify: `crates/amplifier-module-orchestrator-loop-streaming/Cargo.toml`
- Create: `crates/amplifier-module-orchestrator-loop-streaming/tests/context_threading_e2e.rs`

**Step 1: Add dev-dep on tool-delegate**

Read the orchestrator's Cargo.toml first:

Run: `cat crates/amplifier-module-orchestrator-loop-streaming/Cargo.toml`

Then under `[dev-dependencies]`, add:

```toml
amplifier-module-tool-delegate = { path = "../amplifier-module-tool-delegate" }
amplifier-module-agent-runtime = { workspace = true }
```

(If `[dev-dependencies]` doesn't exist yet, add the section.)

**Step 2: Create the integration test**

Create `crates/amplifier-module-orchestrator-loop-streaming/tests/context_threading_e2e.rs`:

```rust
//! End-to-end test: a full orchestrator loop with a mock provider that emits a
//! `delegate` tool call. Asserts that the sub-agent's effective instruction
//! contains the `[PARENT CONVERSATION CONTEXT]` block — i.e. parent history
//! actually flowed through the orchestrator → DelegateTool → SubagentRunner chain.

use std::collections::HashMap;
use std::future::Future;
use std::pin::Pin;
use std::sync::{Arc, Mutex};

use amplifier_core::errors::ProviderError;
use amplifier_core::messages::{ChatRequest, ChatResponse, ContentBlock, ToolCall};
use amplifier_core::models::{ModelInfo, ProviderInfo};
use amplifier_core::traits::Provider;
use amplifier_module_agent_runtime::AgentRegistry;
use amplifier_module_context_simple::SimpleContext;
use amplifier_module_orchestrator_loop_streaming::{HookRegistry, LoopConfig, LoopOrchestrator};
use amplifier_module_tool_delegate::{DelegateConfig, DelegateTool};
use amplifier_module_tool_task::{SpawnRequest, SubagentRunner};

/// Provider that returns a single delegate tool_call on its first complete()
/// call, then end_turn forever after.
struct DelegateCallingProvider {
    n: Arc<Mutex<usize>>,
}
impl DelegateCallingProvider {
    fn new() -> Self { Self { n: Arc::new(Mutex::new(0)) } }
}
impl Provider for DelegateCallingProvider {
    fn name(&self) -> &str { "anthropic" }
    fn get_info(&self) -> ProviderInfo {
        ProviderInfo {
            id: "anthropic".into(),
            display_name: "anthropic".into(),
            credential_env_vars: vec![],
            capabilities: vec![],
            defaults: HashMap::new(),
            config_fields: vec![],
        }
    }
    fn list_models(&self) -> Pin<Box<dyn Future<Output = Result<Vec<ModelInfo>, ProviderError>> + Send + '_>> {
        Box::pin(async { Ok(vec![]) })
    }
    fn complete(&self, _req: ChatRequest) -> Pin<Box<dyn Future<Output = Result<ChatResponse, ProviderError>> + Send + '_>> {
        let n = self.n.clone();
        Box::pin(async move {
            let count = { let mut g = n.lock().unwrap(); *g += 1; *g };
            if count == 1 {
                let mut input = HashMap::new();
                input.insert("agent".to_string(), serde_json::json!("explorer"));
                input.insert("instruction".to_string(), serde_json::json!("summarise"));
                input.insert("context_depth".to_string(), serde_json::json!("recent"));
                Ok(ChatResponse {
                    content: vec![ContentBlock::ToolCall {
                        id: "c1".into(),
                        name: "delegate".into(),
                        input,
                        visibility: None,
                        extensions: HashMap::new(),
                    }],
                    tool_calls: None,
                    usage: None,
                    degradation: None,
                    finish_reason: Some("tool_use".into()),
                    metadata: None,
                    extensions: HashMap::new(),
                })
            } else {
                Ok(ChatResponse {
                    content: vec![ContentBlock::Text {
                        text: "all done".into(),
                        visibility: None,
                        extensions: HashMap::new(),
                    }],
                    tool_calls: None,
                    usage: None,
                    degradation: None,
                    finish_reason: Some("end_turn".into()),
                    metadata: None,
                    extensions: HashMap::new(),
                })
            }
        })
    }
    fn parse_tool_calls(&self, response: &ChatResponse) -> Vec<ToolCall> {
        response.content.iter().filter_map(|b| {
            if let ContentBlock::ToolCall { id, name, input, .. } = b {
                Some(ToolCall {
                    id: id.clone(),
                    name: name.clone(),
                    arguments: input.clone(),
                })
            } else { None }
        }).collect()
    }
}

/// SubagentRunner that captures the SpawnRequest it receives.
struct CapturingRunner {
    captured: Arc<Mutex<Option<SpawnRequest>>>,
}
#[async_trait::async_trait]
impl SubagentRunner for CapturingRunner {
    async fn run(&self, req: SpawnRequest) -> anyhow::Result<String> {
        *self.captured.lock().unwrap() = Some(req);
        Ok("subagent-result".to_string())
    }
}

#[tokio::test]
async fn delegate_receives_parent_context_through_orchestrator() {
    // Pre-seed conversation history: 3 prior turns.
    let mut ctx = SimpleContext::new(vec![
        serde_json::json!({"role": "user", "content": "what is rust"}),
        serde_json::json!({"role": "assistant", "content": "rust is a systems programming language"}),
        serde_json::json!({"role": "user", "content": "tell me about ownership"}),
        serde_json::json!({"role": "assistant", "content": "ownership tracks lifetimes statically"}),
        serde_json::json!({"role": "user", "content": "what about borrowing"}),
        serde_json::json!({"role": "assistant", "content": "borrowing lets you reference without moving"}),
    ]);

    // Build the orchestrator.
    let orchestrator = LoopOrchestrator::new(LoopConfig {
        max_steps: Some(5),
        system_prompt: String::new(),
    });
    let provider: Arc<dyn Provider> = Arc::new(DelegateCallingProvider::new());
    orchestrator.register_provider("anthropic".into(), provider).await;

    // Build the DelegateTool with a capturing runner.
    let captured = Arc::new(Mutex::new(None));
    let runner: Arc<dyn SubagentRunner> = Arc::new(CapturingRunner { captured: captured.clone() });
    let registry = Arc::new(AgentRegistry::new());
    let delegate = Arc::new(DelegateTool::new(runner, registry, DelegateConfig::default()));

    // Register as context-aware so the orchestrator threads messages.
    orchestrator.register_context_aware_tool(delegate).await;

    let hooks = HookRegistry::new();
    let _ = orchestrator
        .execute("now summarise our discussion".to_string(), &mut ctx, &hooks, |_| {})
        .await
        .expect("execute ok");

    // The runner must have been called.
    let req = captured
        .lock()
        .unwrap()
        .take()
        .expect("delegate runner was never called");

    // The instruction must contain the parent context block.
    assert!(
        req.instruction.contains("[PARENT CONVERSATION CONTEXT]"),
        "missing PARENT CONVERSATION CONTEXT block; instruction was:\n{}",
        req.instruction
    );
    assert!(
        req.instruction.contains("[END PARENT CONTEXT]"),
        "missing END PARENT CONTEXT marker; instruction was:\n{}",
        req.instruction
    );
    // Ephemeral: req.context must remain empty (parent history is in the instruction string only).
    assert!(
        req.context.is_empty(),
        "req.context must be empty (ephemeral); got: {:?}",
        req.context
    );
    // The original instruction string must follow the context block.
    assert!(
        req.instruction.contains("summarise"),
        "original instruction missing; got:\n{}",
        req.instruction
    );
    // At least one of the seeded conversation turns must appear in the block.
    assert!(
        req.instruction.contains("borrowing lets you reference without moving")
            || req.instruction.contains("what about borrowing"),
        "expected recent turn content in context block; got:\n{}",
        req.instruction
    );
}
```

**Step 3: Run the integration test**

Run: `cargo test -p amplifier-module-orchestrator-loop-streaming --test context_threading_e2e`
Expected: PASS.

**Step 4: Commit**

```bash
git add crates/amplifier-module-orchestrator-loop-streaming/Cargo.toml \
        crates/amplifier-module-orchestrator-loop-streaming/tests/context_threading_e2e.rs
git commit -m "test(orchestrator): e2e — delegate receives parent context through full loop"
```

---

## Task 11: Final verification sweep

**Files:** none (verification only)

**Step 1: Workspace build**

Run: `cargo build --workspace`
Expected: builds cleanly with no errors and no warnings.

If warnings appear, fix them before continuing. Common ones:
- Unused imports — remove them.
- Dead code on `context: Arc<Mutex<...>>` — should not occur because `set_execution_context` and `execute()` both touch it, but if it does, remove the offending `#[allow(dead_code)]`.

**Step 2: Clippy**

Run: `cargo clippy --workspace --all-targets -- -D warnings`
Expected: clean exit (status 0).

Common issues:
- `clippy::needless_clone` on `context_messages.clone()` inside the loop — keep the clone (each context-aware tool needs its own copy; the lint is a false positive here, suppress with `#[allow(clippy::redundant_clone)]` on that one statement if needed).
- Unused `Mutex` import — remove.

**Step 3: Format check**

Run: `cargo fmt --check`
Expected: clean exit (status 0). If it fails, run `cargo fmt` and re-commit.

**Step 4: Full test suite**

Run: `cargo test --workspace`
Expected: every test passes — including the existing 4 delegate tests, 3 orchestrator tests, the new tests added in Tasks 1–10, and all unrelated workspace tests.

**Step 5: Final commit if any cleanup was needed**

```bash
git add -A
git diff --cached --quiet || git commit -m "chore(phase-5): post-verification cleanup (fmt/clippy)"
```

---

## Acceptance Checklist

Confirm all of the following before marking Phase 5 complete:

- [ ] Task 1: `ContextAwareTool` trait exists in `tool-task` and is object-safe.
- [ ] Tasks 2–4: `build_inherited_context` returns `None` for `ContextDepth::None`/empty, slices last `2*n` filtered messages for `Recent(n)`, and applies `Conversation`/`Agents`/`Full` scope filters correctly.
- [ ] Task 5: proptest harness runs and finds no panics.
- [ ] Task 6: `DelegateTool` implements `ContextAwareTool` and `snapshot_context()` reflects what was set.
- [ ] Task 7: `DelegateTool::execute()` parses `context_depth`/`context_scope` from the JSON input, prepends `[PARENT CONVERSATION CONTEXT]\n…[END PARENT CONTEXT]\n\n` when the inherited context is non-empty, and sends `req.context = vec![]` (ephemeral).
- [ ] Tasks 8–9: `LoopOrchestrator` has a `context_aware_tools` registry; `register_context_aware_tool` populates both registries; the loop calls `set_execution_context(messages)` immediately before `tool.execute(...)`.
- [ ] Task 10: end-to-end integration test passes — a real orchestrator + real `DelegateTool` + mock provider produces a SpawnRequest whose `instruction` contains both markers.
- [ ] Task 11: `cargo build --workspace`, `cargo clippy --workspace --all-targets -- -D warnings`, `cargo fmt --check`, and `cargo test --workspace` all pass.
- [ ] No edits were made to `amplifier-core`. Confirm with `git diff main -- crates/ amplifier-agent-foundation sandbox` showing changes only in the four touched crates.
