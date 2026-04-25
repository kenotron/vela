# Phase 1: Core Agent Depth — Implementation Plan

> **Execution:** Use the subagent-driven-development workflow to implement this plan.

**Goal:** Build the `amplifier-rust` Cargo workspace (5 crates) and migrate `amplifier-android` to wire through them, delivering step limits, a Rust hook system, skill fork dispatch, and the subagent spawning foundation.

**Architecture:** A new standalone repository at `/Users/ken/workspace/amplifier-rust/` holds 5 library crates following a strict layering rule — `context-simple` → `tool-task` → `provider-anthropic` → `orchestrator-loop-streaming` → `tool-skills`. Each crate's public API is frozen at the end of its sub-phase. `amplifier-android` in the Vela repo becomes a ~5KB thin JNI wiring layer: its three self-contained source files (`orchestrator.rs`, `provider.rs`, `context.rs`) are deleted and replaced with path-dep references to the new workspace crates.

**Tech Stack:** Rust 2021, Cargo workspace with `resolver = "2"`, tokio async runtime, `async-trait` for trait impls, `reqwest` for real SSE streaming, `tiktoken-rs` (cl100k_base) for token counting, `serde_yaml` for SKILL.md frontmatter parsing, `amplifier-core` git dependency (Provider / Tool / ContextManager traits)

---

> **Size note:** This plan has 29 tasks across 7 sub-phases. Work in strict sub-phase order — each sub-phase builds the foundation for the next.

> **amplifier-core trait signatures:** Before implementing any `impl` block for a core trait, run `cargo doc -p amplifier-core --no-deps 2>/dev/null && open target/doc/amplifier_core/index.html` from the workspace root to verify the exact method signatures. The signatures in this plan are derived from the existing `amplifier-android` source and should be accurate, but the generated docs are ground truth.

---

## Sub-phase A — Bootstrap

### Task 1: Initialize the `amplifier-rust` repository and workspace skeleton

**Files:**
- Create: `/Users/ken/workspace/amplifier-rust/Cargo.toml`
- Create: `/Users/ken/workspace/amplifier-rust/.gitignore`
- Create: skeleton `src/lib.rs` stubs (one per crate — required for workspace to resolve)

**Step 1: Create the repo directory and initialize git**

```bash
cd /Users/ken/workspace
mkdir amplifier-rust
cd amplifier-rust
git init
echo '/target\n.env\n*.swp' > .gitignore
```

Expected: `Initialized empty Git repository in /Users/ken/workspace/amplifier-rust/.git/`

**Step 2: Create the workspace `Cargo.toml`**

Create `/Users/ken/workspace/amplifier-rust/Cargo.toml`:

```toml
[workspace]
members = [
    "crates/amplifier-module-context-simple",
    "crates/amplifier-module-orchestrator-loop-streaming",
    "crates/amplifier-module-provider-anthropic",
    "crates/amplifier-module-tool-task",
    "crates/amplifier-module-tool-skills",
]
resolver = "2"

[workspace.dependencies]
amplifier-core = { git = "https://github.com/microsoft/amplifier-core", branch = "main" }
tokio        = { version = "1",    features = ["full"] }
serde        = { version = "1",    features = ["derive"] }
serde_json   = "1"
async-trait  = "0.1"
anyhow       = "1"
thiserror    = "1"
reqwest      = { version = "0.12", features = ["json", "stream"] }
tiktoken-rs  = "0.5"
serde_yaml   = "0.9"
futures      = "0.3"
uuid         = { version = "1",    features = ["v4"] }
```

**Step 3: Create crate directory trees**

```bash
cd /Users/ken/workspace/amplifier-rust

for crate in context-simple orchestrator-loop-streaming provider-anthropic tool-task tool-skills; do
  mkdir -p crates/amplifier-module-$crate/src
  mkdir -p crates/amplifier-module-$crate/tests
  echo '// placeholder' > crates/amplifier-module-$crate/src/lib.rs
  echo '' > crates/amplifier-module-$crate/tests/integration_test.rs
done
```

**Step 4: Commit the skeleton**

```bash
cd /Users/ken/workspace/amplifier-rust
git add .
git commit -m "chore: initialize amplifier-rust workspace skeleton"
```

---

### Task 2: Write per-crate `Cargo.toml` files and verify workspace compiles

**Files:**
- Create: `crates/amplifier-module-context-simple/Cargo.toml`
- Create: `crates/amplifier-module-tool-task/Cargo.toml`
- Create: `crates/amplifier-module-provider-anthropic/Cargo.toml`
- Create: `crates/amplifier-module-orchestrator-loop-streaming/Cargo.toml`
- Create: `crates/amplifier-module-tool-skills/Cargo.toml`

**Step 1: Write `amplifier-module-context-simple/Cargo.toml`**

```toml
[package]
name    = "amplifier-module-context-simple"
version = "0.1.0"
edition = "2021"

[dependencies]
amplifier-core = { workspace = true }
serde          = { workspace = true }
serde_json     = { workspace = true }
async-trait    = { workspace = true }
anyhow         = { workspace = true }
thiserror      = { workspace = true }
tiktoken-rs    = { workspace = true }

[dev-dependencies]
tokio = { workspace = true }
```

**Step 2: Write `amplifier-module-tool-task/Cargo.toml`**

```toml
[package]
name    = "amplifier-module-tool-task"
version = "0.1.0"
edition = "2021"

[dependencies]
amplifier-core = { workspace = true }
serde          = { workspace = true }
serde_json     = { workspace = true }
async-trait    = { workspace = true }
anyhow         = { workspace = true }
thiserror      = { workspace = true }
uuid           = { workspace = true }

[dev-dependencies]
tokio = { workspace = true }
```

**Step 3: Write `amplifier-module-provider-anthropic/Cargo.toml`**

```toml
[package]
name    = "amplifier-module-provider-anthropic"
version = "0.1.0"
edition = "2021"

[dependencies]
amplifier-core = { workspace = true }
serde          = { workspace = true }
serde_json     = { workspace = true }
async-trait    = { workspace = true }
anyhow         = { workspace = true }
thiserror      = { workspace = true }
reqwest        = { workspace = true }
tokio          = { workspace = true }
futures        = { workspace = true }

[dev-dependencies]
tokio = { workspace = true }
```

**Step 4: Write `amplifier-module-orchestrator-loop-streaming/Cargo.toml`**

```toml
[package]
name    = "amplifier-module-orchestrator-loop-streaming"
version = "0.1.0"
edition = "2021"

[dependencies]
amplifier-core                    = { workspace = true }
amplifier-module-context-simple   = { path = "../amplifier-module-context-simple" }
amplifier-module-tool-task        = { path = "../amplifier-module-tool-task" }
serde          = { workspace = true }
serde_json     = { workspace = true }
async-trait    = { workspace = true }
anyhow         = { workspace = true }
thiserror      = { workspace = true }
tokio          = { workspace = true }

[dev-dependencies]
tokio = { workspace = true }
```

**Step 5: Write `amplifier-module-tool-skills/Cargo.toml`**

```toml
[package]
name    = "amplifier-module-tool-skills"
version = "0.1.0"
edition = "2021"

[dependencies]
amplifier-core              = { workspace = true }
amplifier-module-tool-task  = { path = "../amplifier-module-tool-task" }
serde          = { workspace = true }
serde_json     = { workspace = true }
serde_yaml     = { workspace = true }
async-trait    = { workspace = true }
anyhow         = { workspace = true }
thiserror      = { workspace = true }

[dev-dependencies]
tokio = { workspace = true }
```

**Step 6: Verify workspace compiles**

```bash
cd /Users/ken/workspace/amplifier-rust
cargo check --workspace
```

Expected output (order may vary):
```
   Compiling amplifier-core v...
   Compiling amplifier-module-context-simple v0.1.0
   Compiling amplifier-module-tool-task v0.1.0
   Compiling amplifier-module-provider-anthropic v0.1.0
   Compiling amplifier-module-orchestrator-loop-streaming v0.1.0
   Compiling amplifier-module-tool-skills v0.1.0
    Checking amplifier-module-context-simple v0.1.0
    ...
    Finished `dev` profile [unoptimized + debuginfo] target(s) in ...s
```

If amplifier-core fails to download: check your internet connection and that `https://github.com/microsoft/amplifier-core` is accessible with your git credentials.

**Step 7: Commit**

```bash
cd /Users/ken/workspace/amplifier-rust
git add .
git commit -m "chore: add per-crate Cargo.toml files, workspace resolves cleanly"
```

---

## Sub-phase B — `amplifier-module-context-simple`

### Task 3: `SimpleContext` history operations

**Files:**
- Modify: `crates/amplifier-module-context-simple/src/lib.rs`
- Modify: `crates/amplifier-module-context-simple/tests/integration_test.rs`

**Step 1: Write the failing test**

Replace the contents of `crates/amplifier-module-context-simple/src/lib.rs` with:

```rust
//! In-memory context manager for amplifier-core sessions.
//! Tracks conversation history, an ephemeral pre-turn injection queue,
//! and provides token counting + compaction.

use std::sync::Mutex;

use amplifier_core::traits::ContextManager;
use serde_json::Value;

/// In-memory context manager.
///
/// `history`   — persisted between turns, returned by `get_messages()`
/// `ephemeral` — injected before each provider call, cleared by `messages_for_provider()`
pub struct SimpleContext {
    history:   Mutex<Vec<Value>>,
    ephemeral: Mutex<Vec<Value>>,
}

impl SimpleContext {
    pub fn new(history: Vec<Value>) -> Self {
        Self {
            history:   Mutex::new(history),
            ephemeral: Mutex::new(Vec::new()),
        }
    }
}

// ─────────────────────────── ContextManager impl ────────────────────────────

impl ContextManager for SimpleContext {
    fn add_message(
        &self,
        message: Value,
    ) -> std::pin::Pin<Box<dyn std::future::Future<Output = Result<(), amplifier_core::ContextError>> + Send + '_>>
    {
        self.history.lock().expect("history mutex poisoned").push(message);
        Box::pin(std::future::ready(Ok(())))
    }

    fn get_messages(
        &self,
    ) -> std::pin::Pin<Box<dyn std::future::Future<Output = Result<Vec<Value>, amplifier_core::ContextError>> + Send + '_>>
    {
        let msgs = self.history.lock().expect("history mutex poisoned").clone();
        Box::pin(std::future::ready(Ok(msgs)))
    }

    fn get_messages_for_request(
        &self,
        _token_budget: Option<i64>,
        _provider: Option<std::sync::Arc<dyn amplifier_core::traits::Provider>>,
    ) -> std::pin::Pin<Box<dyn std::future::Future<Output = Result<Vec<Value>, amplifier_core::ContextError>> + Send + '_>>
    {
        let msgs = self.history.lock().expect("history mutex poisoned").clone();
        Box::pin(std::future::ready(Ok(msgs)))
    }

    fn set_messages(
        &self,
        messages: Vec<Value>,
    ) -> std::pin::Pin<Box<dyn std::future::Future<Output = Result<(), amplifier_core::ContextError>> + Send + '_>>
    {
        *self.history.lock().expect("history mutex poisoned") = messages;
        Box::pin(std::future::ready(Ok(())))
    }

    fn clear(
        &self,
    ) -> std::pin::Pin<Box<dyn std::future::Future<Output = Result<(), amplifier_core::ContextError>> + Send + '_>>
    {
        self.history.lock().expect("history mutex poisoned").clear();
        Box::pin(std::future::ready(Ok(())))
    }
}

// ─────────────────────────── SimpleContext extras ────────────────────────────

impl SimpleContext {
    /// Append a user+assistant turn to history.
    pub fn push_turn(&self, user_msg: Value, assistant_msg: Value) {
        let mut h = self.history.lock().expect("history mutex poisoned");
        h.push(user_msg);
        h.push(assistant_msg);
    }

    /// Queue a message for ephemeral injection.
    /// Ephemeral messages are included by `messages_for_provider()` then cleared.
    /// They are NOT stored in history.
    pub fn push_ephemeral(&self, msg: Value) {
        self.ephemeral.lock().expect("ephemeral mutex poisoned").push(msg);
    }

    /// Returns history + ephemeral combined, then clears the ephemeral queue.
    /// Call this once per provider round-trip, immediately before sending to the LLM.
    pub fn messages_for_provider(&self) -> Vec<Value> {
        let history = self.history.lock().expect("history mutex poisoned").clone();
        let mut ephemeral = self.ephemeral.lock().expect("ephemeral mutex poisoned");
        let result: Vec<Value> = history.into_iter().chain(ephemeral.drain(..)).collect();
        result
    }

    /// Count tokens in history + ephemeral using tiktoken cl100k_base encoding.
    pub fn token_count(&self) -> usize {
        let bpe = tiktoken_rs::cl100k_base().expect("cl100k_base encoding should load");
        let h = self.history.lock().expect("history mutex poisoned").clone();
        let e = self.ephemeral.lock().expect("ephemeral mutex poisoned").clone();
        let all: Vec<Value> = h.into_iter().chain(e).collect();
        let text = serde_json::to_string(&all).unwrap_or_default();
        bpe.encode_with_special_tokens(&text).len()
    }

    /// Drop the oldest messages from history until `token_count() <= threshold`.
    /// Drops in 50%-of-current-length chunks to avoid O(n²) scanning.
    /// No-ops if history is already under the threshold or empty.
    pub fn compact_if_needed(&self, threshold: usize) {
        loop {
            if self.token_count() <= threshold {
                break;
            }
            let mut h = self.history.lock().expect("history mutex poisoned");
            if h.is_empty() {
                break;
            }
            let drop_n = (h.len() / 2).max(1);
            h.drain(..drop_n);
        }
    }
}

// ──────────────────────────────── Unit tests ─────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[tokio::test]
    async fn new_with_history_returns_history() {
        let ctx = SimpleContext::new(vec![
            json!({"role": "user",      "content": "Hi"}),
            json!({"role": "assistant", "content": "Hello!"}),
        ]);
        let msgs = ctx.get_messages().await.unwrap();
        assert_eq!(msgs.len(), 2);
        assert_eq!(msgs[0]["content"], "Hi");
    }

    #[tokio::test]
    async fn new_empty_starts_empty() {
        let ctx = SimpleContext::new(vec![]);
        assert_eq!(ctx.get_messages().await.unwrap().len(), 0);
    }

    #[tokio::test]
    async fn add_message_appends_to_history() {
        let ctx = SimpleContext::new(vec![]);
        ctx.add_message(json!({"role": "user", "content": "First"})).await.unwrap();
        ctx.add_message(json!({"role": "assistant", "content": "Second"})).await.unwrap();
        let msgs = ctx.get_messages().await.unwrap();
        assert_eq!(msgs.len(), 2);
        assert_eq!(msgs[1]["content"], "Second");
    }

    #[tokio::test]
    async fn push_turn_adds_two_messages() {
        let ctx = SimpleContext::new(vec![]);
        ctx.push_turn(
            json!({"role": "user",      "content": "question"}),
            json!({"role": "assistant", "content": "answer"}),
        );
        let msgs = ctx.get_messages().await.unwrap();
        assert_eq!(msgs.len(), 2);
    }

    #[tokio::test]
    async fn clear_empties_history() {
        let ctx = SimpleContext::new(vec![json!({"role": "user", "content": "x"})]);
        ctx.clear().await.unwrap();
        assert!(ctx.get_messages().await.unwrap().is_empty());
    }

    #[tokio::test]
    async fn set_messages_replaces_history() {
        let ctx = SimpleContext::new(vec![json!({"role": "user", "content": "old"})]);
        ctx.set_messages(vec![
            json!({"role": "user",      "content": "new"}),
            json!({"role": "assistant", "content": "resp"}),
        ]).await.unwrap();
        let msgs = ctx.get_messages().await.unwrap();
        assert_eq!(msgs.len(), 2);
        assert_eq!(msgs[0]["content"], "new");
    }
}
```

**Step 2: Run the test to verify it compiles and passes** (all history tests pass because the implementation is complete above — this is an exception: we write history + ephemeral in one step since they share the same struct)

```bash
cd /Users/ken/workspace/amplifier-rust
cargo test -p amplifier-module-context-simple -- tests 2>&1 | tail -20
```

Expected:
```
test tests::new_with_history_returns_history ... ok
test tests::new_empty_starts_empty ... ok
test tests::add_message_appends_to_history ... ok
test tests::push_turn_adds_two_messages ... ok
test tests::clear_empties_history ... ok
test tests::set_messages_replaces_history ... ok

test result: ok. 6 passed; 0 failed; 0 ignored
```

**Step 3: Commit**

```bash
cd /Users/ken/workspace/amplifier-rust
git add crates/amplifier-module-context-simple/src/lib.rs
git commit -m "feat(context-simple): SimpleContext with history operations + ContextManager impl"
```

---

### Task 4: `push_ephemeral`, `messages_for_provider` (clears ephemeral after use)

**Files:**
- Modify: `crates/amplifier-module-context-simple/src/lib.rs` (add unit tests only — implementation already present from Task 3)

**Step 1: Add ephemeral tests to the `#[cfg(test)]` block**

Add these tests inside the existing `mod tests { ... }` block in `src/lib.rs`:

```rust
    #[test]
    fn ephemeral_not_in_get_messages() {
        let ctx = SimpleContext::new(vec![]);
        ctx.push_ephemeral(json!({"role": "user", "content": "injected"}));
        // get_messages returns history only — ephemeral is NOT included
        let msgs = ctx.history.lock().unwrap().clone();
        assert!(msgs.is_empty(), "history should be empty; ephemeral must not leak into history");
    }

    #[test]
    fn messages_for_provider_includes_ephemeral() {
        let ctx = SimpleContext::new(vec![
            json!({"role": "user", "content": "history"}),
        ]);
        ctx.push_ephemeral(json!({"role": "user", "content": "ephemeral"}));
        let combined = ctx.messages_for_provider();
        assert_eq!(combined.len(), 2);
        assert_eq!(combined[1]["content"], "ephemeral");
    }

    #[test]
    fn messages_for_provider_clears_ephemeral_after_call() {
        let ctx = SimpleContext::new(vec![]);
        ctx.push_ephemeral(json!({"role": "user", "content": "temp"}));

        let first_call = ctx.messages_for_provider();
        assert_eq!(first_call.len(), 1, "first call should include ephemeral");

        let second_call = ctx.messages_for_provider();
        assert_eq!(second_call.len(), 0, "second call must not see ephemeral — it was cleared");
    }

    #[test]
    fn messages_for_provider_history_not_cleared() {
        let ctx = SimpleContext::new(vec![
            json!({"role": "user", "content": "persisted"}),
        ]);
        ctx.messages_for_provider(); // first call — clears ephemeral (empty here)
        ctx.messages_for_provider(); // second call — history should still be there
        let h = ctx.history.lock().unwrap().clone();
        assert_eq!(h.len(), 1, "history must survive messages_for_provider calls");
    }
```

**Step 2: Run the tests**

```bash
cd /Users/ken/workspace/amplifier-rust
cargo test -p amplifier-module-context-simple 2>&1 | tail -20
```

Expected: all tests pass, including the 4 new ephemeral tests.

**Step 3: Commit**

```bash
git add crates/amplifier-module-context-simple/src/lib.rs
git commit -m "test(context-simple): add ephemeral queue tests"
```

---

### Task 5: `token_count` using tiktoken-rs

**Step 1: Add token-count tests to `mod tests`**

```rust
    #[test]
    fn token_count_is_zero_for_empty_context() {
        let ctx = SimpleContext::new(vec![]);
        assert_eq!(ctx.token_count(), 0);
    }

    #[test]
    fn token_count_is_nonzero_for_nonempty_context() {
        let ctx = SimpleContext::new(vec![
            json!({"role": "user", "content": "Hello, how are you today?"}),
        ]);
        let count = ctx.token_count();
        assert!(count > 0, "token_count must be > 0 for non-empty context, got {count}");
        assert!(count < 100, "a short message should be < 100 tokens, got {count}");
    }

    #[test]
    fn token_count_grows_with_more_messages() {
        let ctx = SimpleContext::new(vec![]);
        let before = ctx.token_count();
        ctx.push_turn(
            json!({"role": "user",      "content": "What is the capital of France?"}),
            json!({"role": "assistant", "content": "The capital of France is Paris."}),
        );
        let after = ctx.token_count();
        assert!(after > before, "token count should increase after push_turn");
    }
```

**Step 2: Run the tests**

```bash
cd /Users/ken/workspace/amplifier-rust
cargo test -p amplifier-module-context-simple -- tests::token 2>&1 | tail -15
```

Expected:
```
test tests::token_count_is_zero_for_empty_context ... ok
test tests::token_count_is_nonzero_for_nonempty_context ... ok
test tests::token_count_grows_with_more_messages ... ok
```

**Step 3: Commit**

```bash
git add crates/amplifier-module-context-simple/src/lib.rs
git commit -m "test(context-simple): token_count tests via tiktoken-rs cl100k_base"
```

---

### Task 6: `compact_if_needed` drops oldest messages to stay under threshold

**Step 1: Add compaction tests to `mod tests`**

```rust
    #[test]
    fn compact_if_needed_noop_when_under_threshold() {
        let ctx = SimpleContext::new(vec![
            json!({"role": "user", "content": "hi"}),
        ]);
        let before = ctx.history.lock().unwrap().len();
        ctx.compact_if_needed(100_000); // threshold vastly larger than content
        let after = ctx.history.lock().unwrap().len();
        assert_eq!(before, after, "nothing should be dropped when under threshold");
    }

    #[test]
    fn compact_if_needed_drops_messages_when_over_threshold() {
        // Build a context with enough messages to exceed a tiny threshold
        let msgs: Vec<Value> = (0..20)
            .map(|i| json!({"role": "user", "content": format!("message number {i} with padding text")}))
            .collect();
        let ctx = SimpleContext::new(msgs);

        let threshold = 5; // 5 tokens — impossible to satisfy unless nearly empty
        ctx.compact_if_needed(threshold);

        let remaining = ctx.history.lock().unwrap().len();
        assert!(
            remaining < 20,
            "compaction should have dropped some messages; still have {remaining}"
        );
    }

    #[test]
    fn compact_if_needed_noop_on_empty_context() {
        let ctx = SimpleContext::new(vec![]);
        ctx.compact_if_needed(0); // threshold=0, but nothing to drop
        assert_eq!(ctx.history.lock().unwrap().len(), 0);
    }
```

**Step 2: Run the tests**

```bash
cd /Users/ken/workspace/amplifier-rust
cargo test -p amplifier-module-context-simple 2>&1 | tail -20
```

Expected: all tests pass (implementation was already written in Task 3).

**Step 3: Run the full integration test file (currently empty — just verifies it compiles)**

```bash
cargo test -p amplifier-module-context-simple --test integration_test 2>&1 | tail -5
```

Expected: `test result: ok. 0 passed; 0 failed` (no tests yet — that's fine)

**Step 4: Commit**

```bash
git add crates/amplifier-module-context-simple/
git commit -m "feat(context-simple): complete — token_count, compact_if_needed, ephemeral queue"
```

---

## Sub-phase C — `amplifier-module-tool-task`

### Task 7: `SubagentRunner` trait and `SpawnRequest` types

**Files:**
- Modify: `crates/amplifier-module-tool-task/src/lib.rs`

**Step 1: Write the failing test**

Replace `src/lib.rs` with:

```rust
//! `amplifier-module-tool-task` — defines the `SubagentRunner` trait and the
//! `TaskTool` which wraps it as an `amplifier_core::Tool`.
//!
//! **Circular-dependency note:** This crate has NO dependency on the orchestrator
//! crate. The orchestrator implements `SubagentRunner` and injects itself at startup.

use serde_json::Value;

// ───────────────────────────── SubagentRunner ────────────────────────────────

/// How much parent-conversation history the child agent receives.
#[derive(Debug, Clone, PartialEq)]
pub enum ContextDepth {
    /// No parent history — fresh start.
    None,
    /// Last N turns from parent.
    Recent(usize),
    /// Full parent conversation.
    All,
}

/// Which message types from the parent history the child can see.
#[derive(Debug, Clone, PartialEq)]
pub enum ContextScope {
    /// User/assistant text only.
    Conversation,
    /// + results from prior task tool calls.
    Agents,
    /// + all tool results.
    Full,
}

/// A request to spawn a child agent session.
#[derive(Debug, Clone)]
pub struct SpawnRequest {
    pub instruction:   String,
    pub context_depth: ContextDepth,
    pub context_scope: ContextScope,
    /// Pre-filtered parent context messages passed to the child.
    pub context:       Vec<Value>,
    /// Resume a prior sub-session by ID. `None` = fresh session.
    pub session_id:    Option<String>,
}

/// Implemented by anything that can run a subagent (e.g. `LoopOrchestrator`).
/// The orchestrator crate implements this; the tool crate merely defines it.
#[async_trait::async_trait]
pub trait SubagentRunner: Send + Sync {
    async fn run(&self, req: SpawnRequest) -> anyhow::Result<String>;
}

// ──────────────────────────────── TaskTool ───────────────────────────────────

/// `amplifier_core::Tool` implementation that spawns subagents.
/// Tool name: `spawn_agent`
pub struct TaskTool {
    runner:              std::sync::Arc<dyn SubagentRunner>,
    max_recursion_depth: usize,
    current_depth:       usize,
}

impl TaskTool {
    /// Create a new `TaskTool`.
    ///
    /// * `runner`              — anything that implements `SubagentRunner`
    /// * `max_recursion_depth` — how deep subagents may spawn further subagents
    /// * `current_depth`       — depth of the session that *owns* this tool (0 for top-level)
    pub fn new(
        runner:              std::sync::Arc<dyn SubagentRunner>,
        max_recursion_depth: usize,
        current_depth:       usize,
    ) -> Self {
        Self { runner, max_recursion_depth, current_depth }
    }
}

// ──────────────────────────────── Tool impl ──────────────────────────────────

impl amplifier_core::traits::Tool for TaskTool {
    fn get_spec(&self) -> amplifier_core::messages::ToolSpec {
        let mut properties = std::collections::HashMap::new();
        properties.insert("instruction".to_string(), serde_json::json!({
            "type": "string",
            "description": "The complete instruction for the subagent. Be explicit and self-contained."
        }));
        properties.insert("context_depth".to_string(), serde_json::json!({
            "type": "string",
            "enum": ["none", "recent_5", "all"],
            "description": "How much parent conversation history to pass to the child."
        }));
        properties.insert("context_scope".to_string(), serde_json::json!({
            "type": "string",
            "enum": ["conversation", "agents", "full"],
            "description": "Which message types from the history to include."
        }));
        properties.insert("session_id".to_string(), serde_json::json!({
            "type": "string",
            "description": "Optional: resume a prior sub-session by ID."
        }));

        let mut parameters = std::collections::HashMap::new();
        parameters.insert("type".to_string(),       serde_json::json!("object"));
        parameters.insert("properties".to_string(), serde_json::json!(properties));
        parameters.insert("required".to_string(),   serde_json::json!(["instruction"]));

        amplifier_core::messages::ToolSpec {
            name:        "spawn_agent".to_string(),
            description: Some("Spawn a child agent session that runs to completion and returns its final response as a string.".to_string()),
            parameters,
            extensions:  std::collections::HashMap::new(),
        }
    }

    fn execute(
        &self,
        input: Value,
    ) -> std::pin::Pin<Box<dyn std::future::Future<Output = Result<amplifier_core::ToolResult, amplifier_core::ToolError>> + Send + '_>>
    {
        Box::pin(async move {
            // ── Recursion guard ──────────────────────────────────────────────
            if self.current_depth >= self.max_recursion_depth {
                return Err(amplifier_core::ToolError::ExecutionError {
                    message: format!(
                        "spawn_agent: recursion depth {} exceeds max {}",
                        self.current_depth, self.max_recursion_depth
                    ),
                    tool_name: Some("spawn_agent".to_string()),
                    recoverable: false,
                });
            }

            // ── Parse input ──────────────────────────────────────────────────
            let instruction = input["instruction"]
                .as_str()
                .ok_or_else(|| amplifier_core::ToolError::InvalidInput {
                    message: "spawn_agent: 'instruction' is required and must be a string".to_string(),
                    tool_name: Some("spawn_agent".to_string()),
                })?
                .to_string();

            let context_depth = match input["context_depth"].as_str().unwrap_or("none") {
                "recent_5" => ContextDepth::Recent(5),
                "all"      => ContextDepth::All,
                _          => ContextDepth::None,
            };

            let context_scope = match input["context_scope"].as_str().unwrap_or("conversation") {
                "agents" => ContextScope::Agents,
                "full"   => ContextScope::Full,
                _        => ContextScope::Conversation,
            };

            let session_id = input["session_id"].as_str().map(str::to_string);

            let req = SpawnRequest {
                instruction,
                context_depth,
                context_scope,
                context: vec![],   // caller supplies context via hooks; empty for Phase 1
                session_id,
            };

            // ── Run subagent ─────────────────────────────────────────────────
            let result = self.runner.run(req).await.map_err(|e| {
                amplifier_core::ToolError::ExecutionError {
                    message:    format!("spawn_agent: subagent failed: {e}"),
                    tool_name:  Some("spawn_agent".to_string()),
                    recoverable: false,
                }
            })?;

            Ok(amplifier_core::ToolResult {
                output:     Some(Value::String(result)),
                metadata:   None,
                extensions: std::collections::HashMap::new(),
            })
        })
    }
}

// ──────────────────────────────── Unit tests ─────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    struct AlwaysSucceedsRunner;

    #[async_trait::async_trait]
    impl SubagentRunner for AlwaysSucceedsRunner {
        async fn run(&self, req: SpawnRequest) -> anyhow::Result<String> {
            Ok(format!("subagent completed: {}", req.instruction))
        }
    }

    struct AlwaysFailsRunner;

    #[async_trait::async_trait]
    impl SubagentRunner for AlwaysFailsRunner {
        async fn run(&self, _req: SpawnRequest) -> anyhow::Result<String> {
            anyhow::bail!("runner exploded")
        }
    }

    fn make_tool(runner: std::sync::Arc<dyn SubagentRunner>, max: usize, current: usize) -> TaskTool {
        TaskTool::new(runner, max, current)
    }

    #[test]
    fn spawn_request_default_depth_is_none() {
        let req = SpawnRequest {
            instruction:   "do work".to_string(),
            context_depth: ContextDepth::None,
            context_scope: ContextScope::Conversation,
            context:       vec![],
            session_id:    None,
        };
        assert_eq!(req.context_depth, ContextDepth::None);
        assert_eq!(req.context_scope, ContextScope::Conversation);
        assert!(req.session_id.is_none());
    }

    #[test]
    fn get_spec_name_is_spawn_agent() {
        let tool = make_tool(std::sync::Arc::new(AlwaysSucceedsRunner), 1, 0);
        let spec = tool.get_spec();
        assert_eq!(spec.name, "spawn_agent");
        assert!(spec.description.is_some());
        assert!(spec.parameters.contains_key("properties"));
    }

    #[tokio::test]
    async fn execute_returns_runner_output_on_success() {
        let tool = make_tool(std::sync::Arc::new(AlwaysSucceedsRunner), 1, 0);
        let input = serde_json::json!({"instruction": "write a haiku"});
        let result = tool.execute(input).await.unwrap();
        let out = result.output.unwrap();
        assert!(
            out.as_str().unwrap().contains("write a haiku"),
            "output should echo instruction: {out}"
        );
    }

    #[tokio::test]
    async fn execute_missing_instruction_returns_invalid_input_error() {
        let tool = make_tool(std::sync::Arc::new(AlwaysSucceedsRunner), 1, 0);
        let result = tool.execute(serde_json::json!({})).await;
        assert!(result.is_err());
        // Should be InvalidInput, not ExecutionError
        assert!(matches!(
            result.unwrap_err(),
            amplifier_core::ToolError::InvalidInput { .. }
        ));
    }

    #[tokio::test]
    async fn execute_respects_recursion_limit() {
        // current_depth == max_recursion_depth → should be blocked
        let tool = make_tool(std::sync::Arc::new(AlwaysSucceedsRunner), 1, 1);
        let result = tool.execute(serde_json::json!({"instruction": "nest"})).await;
        assert!(result.is_err());
        assert!(matches!(
            result.unwrap_err(),
            amplifier_core::ToolError::ExecutionError { .. }
        ));
    }

    #[tokio::test]
    async fn execute_propagates_runner_failure() {
        let tool = make_tool(std::sync::Arc::new(AlwaysFailsRunner), 1, 0);
        let result = tool.execute(serde_json::json!({"instruction": "fail me"})).await;
        assert!(result.is_err());
    }

    #[tokio::test]
    async fn execute_parses_context_depth_recent() {
        struct CapturingRunner {
            captured: std::sync::Mutex<Option<SpawnRequest>>,
        }
        #[async_trait::async_trait]
        impl SubagentRunner for CapturingRunner {
            async fn run(&self, req: SpawnRequest) -> anyhow::Result<String> {
                *self.captured.lock().unwrap() = Some(req);
                Ok("captured".to_string())
            }
        }
        let runner = std::sync::Arc::new(CapturingRunner { captured: std::sync::Mutex::new(None) });
        let tool = make_tool(runner.clone(), 1, 0);
        tool.execute(serde_json::json!({
            "instruction": "task",
            "context_depth": "recent_5",
            "context_scope": "agents"
        })).await.unwrap();
        let req = runner.captured.lock().unwrap().clone().unwrap();
        assert_eq!(req.context_depth, ContextDepth::Recent(5));
        assert_eq!(req.context_scope, ContextScope::Agents);
    }
}
```

**Step 2: Run the failing test (before implementation exists)**

Actually, the test file above already includes the implementation. This is intentional for this task — the trait definition and implementation go together. Run to verify everything compiles:

```bash
cd /Users/ken/workspace/amplifier-rust
cargo test -p amplifier-module-tool-task 2>&1 | tail -25
```

Expected:
```
test tests::spawn_request_default_depth_is_none ... ok
test tests::get_spec_name_is_spawn_agent ... ok
test tests::execute_returns_runner_output_on_success ... ok
test tests::execute_missing_instruction_returns_invalid_input_error ... ok
test tests::execute_respects_recursion_limit ... ok
test tests::execute_propagates_runner_failure ... ok
test tests::execute_parses_context_depth_recent ... ok

test result: ok. 7 passed; 0 failed
```

> **If the build fails with unknown variants on `amplifier_core::ToolError`:** Open the amplifier-core docs (`cargo doc -p amplifier-core --no-deps --open`) and check the actual `ToolError` variant names. Common alternatives: `ToolError::Other { message }` or `ToolError::Failed { message }`. Adjust the match arms accordingly.

> **If `amplifier_core::ToolResult` doesn't have `metadata` or `extensions` fields:** Use `ToolResult { output: Some(result) }` directly (tuple struct or fewer fields).

**Step 3: Commit**

```bash
cd /Users/ken/workspace/amplifier-rust
git add crates/amplifier-module-tool-task/
git commit -m "feat(tool-task): SubagentRunner trait, SpawnRequest types, TaskTool with recursion guard"
```

---

## Sub-phase D — `amplifier-module-provider-anthropic`

### Task 8: `AnthropicConfig`, `AnthropicProvider::new`, and message-conversion helpers

**Files:**
- Modify: `crates/amplifier-module-provider-anthropic/src/lib.rs`
- Create: `crates/amplifier-module-provider-anthropic/src/streaming.rs`

**Step 1: Write the failing test (message conversion only — no HTTP)**

Replace `src/lib.rs` with:

```rust
//! Anthropic Messages API provider with real SSE streaming.
//!
//! ## Wire format conversions (amplifier-core ↔ Anthropic)
//! | amplifier-core          | Anthropic wire           |
//! |-------------------------|--------------------------|
//! | `ContentBlock::ToolCall`| `{"type":"tool_use",...}`|
//! | `tool_call_id`          | `tool_use_id`            |
//! | `output`                | `content`                |
//! | `finish_reason`         | `stop_reason`            |

pub mod streaming;

use std::collections::HashMap;
use std::pin::Pin;
use std::time::Duration;

use amplifier_core::{
    ChatRequest, ChatResponse, ContentBlock, ModelInfo, ProviderError, ProviderInfo,
    Role, ToolCall, Usage,
};
use amplifier_core::traits::Provider;
use serde_json::{json, Value};

const ANTHROPIC_API_URL: &str = "https://api.anthropic.com/v1/messages";
const ANTHROPIC_VERSION: &str = "2023-06-01";
const DEFAULT_MAX_TOKENS: u64  = 32_768;

/// Configuration for `AnthropicProvider`.
#[derive(Debug, Clone)]
pub struct AnthropicConfig {
    pub api_key:     String,
    pub model:       String,
    pub max_tokens:  u32,
    pub max_retries: u32,
    pub base_url:    String,
}

impl Default for AnthropicConfig {
    fn default() -> Self {
        Self {
            api_key:     String::new(),
            model:       "claude-sonnet-4-5".to_string(),
            max_tokens:  32_768,
            max_retries: 3,
            base_url:    ANTHROPIC_API_URL.to_string(),
        }
    }
}

/// Anthropic Messages API provider implementing `amplifier_core::Provider`.
pub struct AnthropicProvider {
    config: AnthropicConfig,
    client: reqwest::Client,
}

impl AnthropicProvider {
    pub fn new(config: AnthropicConfig) -> Self {
        Self {
            config,
            client: reqwest::Client::new(),
        }
    }

    // ─────────────────────────── Message conversion ──────────────────────────

    /// Convert an amplifier-core `Message` to Anthropic wire-format JSON.
    pub(crate) fn message_to_anthropic(msg: &amplifier_core::Message) -> Value {
        let role = match msg.role {
            Role::User | Role::Tool | Role::Function => "user",
            Role::Assistant => "assistant",
            Role::System | Role::Developer => "system",
        };

        let content = match &msg.content {
            amplifier_core::MessageContent::Text(text) if text.starts_with('[') => {
                serde_json::from_str::<Value>(text).unwrap_or_else(|_| json!(text))
            }
            amplifier_core::MessageContent::Text(text) => json!(text),
            amplifier_core::MessageContent::Blocks(blocks) => {
                let arr: Vec<Value> = blocks
                    .iter()
                    .map(Self::content_block_to_anthropic)
                    .filter(|v| !v.is_null())
                    .collect();
                json!(arr)
            }
        };

        json!({ "role": role, "content": content })
    }

    /// Convert a single `ContentBlock` to Anthropic wire format.
    pub(crate) fn content_block_to_anthropic(block: &ContentBlock) -> Value {
        match block {
            ContentBlock::Text { text, .. } => json!({ "type": "text", "text": text }),
            ContentBlock::ToolCall { id, name, input, .. } => json!({
                "type":  "tool_use",
                "id":    id,
                "name":  name,
                "input": input,
            }),
            ContentBlock::ToolResult { tool_call_id, output, .. } => json!({
                "type":        "tool_result",
                "tool_use_id": tool_call_id,
                "content":     output,
            }),
            ContentBlock::Thinking { thinking, .. } => json!({ "type": "thinking", "thinking": thinking }),
            ContentBlock::Image { source, .. }       => json!({ "type": "image",    "source": source }),
            _ => Value::Null,
        }
    }

    /// Convert an Anthropic content array back to `ContentBlock`s.
    pub(crate) fn parse_content_blocks(raw: &[Value]) -> Vec<ContentBlock> {
        raw.iter()
            .filter_map(|block| {
                let block_type = block.get("type").and_then(|t| t.as_str())?;
                match block_type {
                    "text" => Some(ContentBlock::Text {
                        text:       block.get("text").and_then(|t| t.as_str()).unwrap_or("").to_string(),
                        visibility: None,
                        extensions: HashMap::new(),
                    }),
                    "tool_use" | "server_tool_use" => {
                        let id   = block.get("id").and_then(|v| v.as_str()).unwrap_or("").to_string();
                        let name = block.get("name").and_then(|v| v.as_str()).unwrap_or("").to_string();
                        let input: HashMap<String, Value> = block
                            .get("input").and_then(|v| v.as_object())
                            .map(|o| o.iter().map(|(k, v)| (k.clone(), v.clone())).collect())
                            .unwrap_or_default();
                        Some(ContentBlock::ToolCall { id, name, input, visibility: None, extensions: HashMap::new() })
                    }
                    "web_search_tool_result" => {
                        let tool_call_id = block.get("tool_use_id").and_then(|v| v.as_str()).unwrap_or("").to_string();
                        let output = block.get("content").cloned().unwrap_or(json!([]));
                        Some(ContentBlock::ToolResult { tool_call_id, output, visibility: None, extensions: HashMap::new() })
                    }
                    "thinking" => Some(ContentBlock::Thinking {
                        thinking:   block.get("thinking").and_then(|t| t.as_str()).unwrap_or("").to_string(),
                        signature:  None,
                        visibility: None,
                        content:    None,
                        extensions: HashMap::new(),
                    }),
                    _ => None,
                }
            })
            .collect()
    }

    /// Build Anthropic-format tool array from `ToolSpec`s.
    pub(crate) fn build_anthropic_tools(specs: &[amplifier_core::ToolSpec]) -> Vec<Value> {
        specs.iter().map(|spec| {
            let schema = serde_json::to_value(&spec.parameters).unwrap_or_else(|_| json!({"type":"object","properties":{}}));
            json!({
                "name":         spec.name,
                "description":  spec.description,
                "input_schema": schema,
            })
        }).collect()
    }

    // ─────────────────────────── HTTP + retry ────────────────────────────────

    async fn call_api(&self, body: Value) -> Result<Value, ProviderError> {
        let response = self.client
            .post(&self.config.base_url)
            .header("x-api-key",          &self.config.api_key)
            .header("anthropic-version",   ANTHROPIC_VERSION)
            .header("content-type",        "application/json")
            .json(&body)
            .send()
            .await
            .map_err(|e| ProviderError::Unavailable {
                message: e.to_string(), provider: Some("anthropic".to_string()),
                model: None, retry_after: None, status_code: None, delay_multiplier: None,
            })?;

        let status = response.status();
        let raw: Value = response.json().await.map_err(|e| ProviderError::Other {
            message: format!("failed to parse Anthropic response: {e}"),
            provider: Some("anthropic".to_string()),
            model: None, retry_after: None, status_code: None, retryable: false, delay_multiplier: None,
        })?;

        if !status.is_success() {
            let msg = raw.get("error").and_then(|e| e.get("message"))
                .and_then(|m| m.as_str()).unwrap_or("unknown error");
            return Err(ProviderError::Other {
                message:      format!("Anthropic API error {status}: {msg}"),
                provider:     Some("anthropic".to_string()),
                model:        None,
                retry_after:  None,
                status_code:  Some(status.as_u16()),
                retryable:    matches!(status.as_u16(), 429 | 529 | 500 | 502 | 503 | 504),
                delay_multiplier: None,
            });
        }
        Ok(raw)
    }

    async fn call_api_with_retry(&self, body: Value) -> Result<Value, ProviderError> {
        let mut attempt = 0u32;
        loop {
            match self.call_api(body.clone()).await {
                Ok(v) => return Ok(v),
                Err(e) => {
                    let retryable = match &e {
                        ProviderError::Other { status_code: Some(c), .. } => matches!(c, 429 | 529 | 500 | 502 | 503 | 504),
                        ProviderError::Unavailable { .. } => true,
                        _ => false,
                    };
                    if retryable && attempt < self.config.max_retries {
                        let delay_ms = 1_000u64 * (1u64 << attempt);
                        tokio::time::sleep(Duration::from_millis(delay_ms)).await;
                        attempt += 1;
                    } else {
                        return Err(e);
                    }
                }
            }
        }
    }
}

// ──────────────────────────────── Provider impl ──────────────────────────────

impl Provider for AnthropicProvider {
    fn name(&self) -> &str { "anthropic" }

    fn get_info(&self) -> ProviderInfo {
        ProviderInfo {
            id:                  "anthropic".to_string(),
            display_name:        "Anthropic Claude".to_string(),
            credential_env_vars: vec!["ANTHROPIC_API_KEY".to_string()],
            capabilities:        vec!["tools".to_string(), "streaming".to_string()],
            defaults:            HashMap::new(),
            config_fields:       vec![],
        }
    }

    fn list_models(
        &self,
    ) -> Pin<Box<dyn std::future::Future<Output = Result<Vec<ModelInfo>, ProviderError>> + Send + '_>>
    {
        Box::pin(async {
            Ok(vec![
                ModelInfo {
                    id:                "claude-sonnet-4-5".to_string(),
                    display_name:      "Claude Sonnet 4.5".to_string(),
                    context_window:    200_000,
                    max_output_tokens: 32_768,
                    capabilities:      vec!["tools".to_string(), "vision".to_string()],
                    defaults:          HashMap::new(),
                },
                ModelInfo {
                    id:                "claude-haiku-4-5".to_string(),
                    display_name:      "Claude Haiku 4.5".to_string(),
                    context_window:    200_000,
                    max_output_tokens: 8_192,
                    capabilities:      vec!["tools".to_string()],
                    defaults:          HashMap::new(),
                },
            ])
        })
    }

    fn complete(
        &self,
        request: ChatRequest,
    ) -> Pin<Box<dyn std::future::Future<Output = Result<ChatResponse, ProviderError>> + Send + '_>>
    {
        Box::pin(async move {
            // Filter system messages — they go in the top-level "system" field
            let system_prompt: Option<String> = request.messages.iter()
                .find(|m| matches!(m.role, Role::System | Role::Developer))
                .and_then(|m| if let amplifier_core::MessageContent::Text(t) = &m.content { Some(t.clone()) } else { None });

            let anthropic_messages: Vec<Value> = request.messages.iter()
                .filter(|m| !matches!(m.role, Role::System | Role::Developer))
                .map(Self::message_to_anthropic)
                .collect();

            let mut body = json!({
                "model":      self.config.model,
                "max_tokens": self.config.max_tokens,
                "messages":   anthropic_messages,
            });
            if let Some(sys) = system_prompt {
                body["system"] = json!(sys);
            }

            if let Some(tools) = &request.tools {
                if !tools.is_empty() {
                    body["tools"] = json!(Self::build_anthropic_tools(tools));
                }
            }

            let raw = self.call_api_with_retry(body).await?;

            let content_blocks = raw.get("content")
                .and_then(|c| c.as_array())
                .map(|arr| Self::parse_content_blocks(arr))
                .unwrap_or_default();

            let usage = raw.get("usage").map(|u| {
                let input  = u.get("input_tokens").and_then(|v| v.as_i64()).unwrap_or(0);
                let output = u.get("output_tokens").and_then(|v| v.as_i64()).unwrap_or(0);
                Usage {
                    input_tokens:        input,
                    output_tokens:       output,
                    total_tokens:        input + output,
                    reasoning_tokens:    None,
                    cache_read_tokens:   u.get("cache_read_input_tokens").and_then(|v| v.as_i64()),
                    cache_write_tokens:  u.get("cache_creation_input_tokens").and_then(|v| v.as_i64()),
                    extensions:          HashMap::new(),
                }
            });

            let finish_reason = raw.get("stop_reason")
                .and_then(|v| v.as_str())
                .map(|s| s.to_string());

            Ok(ChatResponse {
                content:     content_blocks,
                tool_calls:  None,
                usage,
                degradation: None,
                finish_reason,
                metadata:    None,
                extensions:  HashMap::new(),
            })
        })
    }

    fn parse_tool_calls(&self, response: &ChatResponse) -> Vec<ToolCall> {
        response.content.iter().filter_map(|block| {
            if let ContentBlock::ToolCall { id, name, input, .. } = block {
                Some(ToolCall {
                    id:         id.clone(),
                    name:       name.clone(),
                    arguments:  input.clone(),
                    extensions: HashMap::new(),
                })
            } else {
                None
            }
        }).collect()
    }
}

// ──────────────────────────────── Unit tests ─────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;
    use amplifier_core::{Message, MessageContent};

    fn make_provider() -> AnthropicProvider {
        AnthropicProvider::new(AnthropicConfig {
            api_key: "test-key".to_string(),
            ..Default::default()
        })
    }

    #[test]
    fn config_default_model_is_sonnet_4_5() {
        let cfg = AnthropicConfig::default();
        assert_eq!(cfg.model, "claude-sonnet-4-5");
        assert_eq!(cfg.max_retries, 3);
    }

    #[test]
    fn message_to_anthropic_user_text() {
        let msg = Message {
            role:         Role::User,
            content:      MessageContent::Text("Hello".to_string()),
            name:         None,
            tool_call_id: None,
            metadata:     None,
            extensions:   HashMap::new(),
        };
        let v = AnthropicProvider::message_to_anthropic(&msg);
        assert_eq!(v["role"],    "user");
        assert_eq!(v["content"], "Hello");
    }

    #[test]
    fn message_to_anthropic_tool_call_uses_tool_use_type() {
        let mut input = HashMap::new();
        input.insert("query".to_string(), json!("test"));
        let msg = Message {
            role:    Role::Assistant,
            content: MessageContent::Blocks(vec![ContentBlock::ToolCall {
                id: "call_1".to_string(), name: "search".to_string(),
                input, visibility: None, extensions: HashMap::new(),
            }]),
            name: None, tool_call_id: None, metadata: None, extensions: HashMap::new(),
        };
        let v = AnthropicProvider::message_to_anthropic(&msg);
        assert_eq!(v["content"][0]["type"], "tool_use");
        assert_eq!(v["content"][0]["id"],   "call_1");
    }

    #[test]
    fn message_to_anthropic_tool_result_uses_tool_use_id() {
        let msg = Message {
            role:    Role::User,
            content: MessageContent::Blocks(vec![ContentBlock::ToolResult {
                tool_call_id: "call_1".to_string(),
                output:       json!("result text"),
                visibility:   None,
                extensions:   HashMap::new(),
            }]),
            name: None, tool_call_id: None, metadata: None, extensions: HashMap::new(),
        };
        let v = AnthropicProvider::message_to_anthropic(&msg);
        assert_eq!(v["content"][0]["type"],        "tool_result");
        assert_eq!(v["content"][0]["tool_use_id"], "call_1");
        assert_eq!(v["content"][0]["content"],     "result text");
    }

    #[test]
    fn parse_content_blocks_handles_tool_use() {
        let raw = vec![
            json!({"type": "text",     "text": "Let me search."}),
            json!({"type": "tool_use", "id": "toolu_01", "name": "search", "input": {"query": "foo"}}),
        ];
        let blocks = AnthropicProvider::parse_content_blocks(&raw);
        assert_eq!(blocks.len(), 2);
        assert!(matches!(&blocks[0], ContentBlock::Text { text, .. } if text == "Let me search."));
        assert!(matches!(&blocks[1], ContentBlock::ToolCall { id, .. } if id == "toolu_01"));
    }

    #[test]
    fn build_anthropic_tools_converts_spec() {
        let mut params = HashMap::new();
        params.insert("type".to_string(),       json!("object"));
        params.insert("properties".to_string(), json!({"q": {"type": "string"}}));
        let spec = amplifier_core::ToolSpec {
            name:        "search".to_string(),
            description: Some("Search the web".to_string()),
            parameters:  params,
            extensions:  HashMap::new(),
        };
        let tools = AnthropicProvider::build_anthropic_tools(&[spec]);
        assert_eq!(tools[0]["name"],        "search");
        assert_eq!(tools[0]["description"], "Search the web");
        assert!(tools[0]["input_schema"].is_object());
    }

    #[test]
    fn parse_tool_calls_extracts_from_content() {
        let mut input = HashMap::new();
        input.insert("q".to_string(), json!("rust"));
        let response = ChatResponse {
            content: vec![ContentBlock::ToolCall {
                id:         "id1".to_string(),
                name:       "search".to_string(),
                input,
                visibility: None,
                extensions: HashMap::new(),
            }],
            tool_calls:   None,
            usage:        None,
            degradation:  None,
            finish_reason: Some("tool_use".to_string()),
            metadata:     None,
            extensions:   HashMap::new(),
        };
        let provider = make_provider();
        let calls = provider.parse_tool_calls(&response);
        assert_eq!(calls.len(), 1);
        assert_eq!(calls[0].name, "search");
    }
}
```

**Step 2: Write `src/streaming.rs` for SSE parsing**

Create `crates/amplifier-module-provider-anthropic/src/streaming.rs`:

```rust
//! SSE streaming support for the Anthropic Messages API.
//!
//! Anthropic SSE event flow for a streaming request:
//!
//!   event: message_start       — message metadata
//!   event: content_block_start — starts a new content block
//!   event: content_block_delta — text chunk (call `on_token` here)
//!   event: content_block_stop  — block finished
//!   event: message_delta       — stop_reason appears here
//!   event: message_stop        — stream complete
//!
//! The only event we act on is `content_block_delta` with `delta.type == "text_delta"`.

/// Parse a single raw SSE line pair and return any text content.
///
/// `line` is expected to start with `"data: "`. Returns `Some(text)` if
/// the event is a `content_block_delta` text chunk, `None` otherwise.
pub fn extract_text_from_sse_line(line: &str) -> Option<String> {
    let data = line.strip_prefix("data: ")?;
    if data == "[DONE]" {
        return None;
    }
    let v: serde_json::Value = serde_json::from_str(data).ok()?;
    if v.get("type").and_then(|t| t.as_str()) != Some("content_block_delta") {
        return None;
    }
    let delta = v.get("delta")?;
    if delta.get("type").and_then(|t| t.as_str()) != Some("text_delta") {
        return None;
    }
    delta.get("text").and_then(|t| t.as_str()).map(str::to_string)
}

/// Extract `stop_reason` from a `message_delta` SSE event, if present.
pub fn extract_stop_reason_from_sse_line(line: &str) -> Option<String> {
    let data = line.strip_prefix("data: ")?;
    let v: serde_json::Value = serde_json::from_str(data).ok()?;
    if v.get("type").and_then(|t| t.as_str()) != Some("message_delta") {
        return None;
    }
    v.get("delta")
        .and_then(|d| d.get("stop_reason"))
        .and_then(|r| r.as_str())
        .map(str::to_string)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn extract_text_from_content_block_delta() {
        let line = r#"data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}"#;
        assert_eq!(extract_text_from_sse_line(line), Some("Hello".to_string()));
    }

    #[test]
    fn ignores_non_content_block_delta_events() {
        let line = r#"data: {"type":"message_start","message":{"id":"msg_1"}}"#;
        assert_eq!(extract_text_from_sse_line(line), None);
    }

    #[test]
    fn ignores_non_text_delta_types() {
        // input_json_delta is for tool call arguments, not text
        let line = r#"data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"q\":"}}"#;
        assert_eq!(extract_text_from_sse_line(line), None);
    }

    #[test]
    fn ignores_done_sentinel() {
        assert_eq!(extract_text_from_sse_line("data: [DONE]"), None);
    }

    #[test]
    fn extract_stop_reason_from_message_delta() {
        let line = r#"data: {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null}}"#;
        assert_eq!(extract_stop_reason_from_sse_line(line), Some("end_turn".to_string()));
    }

    #[test]
    fn extract_stop_reason_returns_none_for_other_events() {
        let line = r#"data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hi"}}"#;
        assert_eq!(extract_stop_reason_from_sse_line(line), None);
    }
}
```

**Step 3: Run all provider tests**

```bash
cd /Users/ken/workspace/amplifier-rust
cargo test -p amplifier-module-provider-anthropic 2>&1 | tail -25
```

Expected:
```
test tests::config_default_model_is_sonnet_4_5 ... ok
test tests::message_to_anthropic_user_text ... ok
test tests::message_to_anthropic_tool_call_uses_tool_use_type ... ok
test tests::message_to_anthropic_tool_result_uses_tool_use_id ... ok
test tests::parse_content_blocks_handles_tool_use ... ok
test tests::build_anthropic_tools_converts_spec ... ok
test tests::parse_tool_calls_extracts_from_content ... ok
test streaming::tests::extract_text_from_content_block_delta ... ok
test streaming::tests::ignores_non_content_block_delta_events ... ok
test streaming::tests::ignores_non_text_delta_types ... ok
test streaming::tests::ignores_done_sentinel ... ok
test streaming::tests::extract_stop_reason_from_message_delta ... ok
test streaming::tests::extract_stop_reason_returns_none_for_other_events ... ok

test result: ok. 13 passed; 0 failed
```

**Step 4: Commit**

```bash
cd /Users/ken/workspace/amplifier-rust
git add crates/amplifier-module-provider-anthropic/
git commit -m "feat(provider-anthropic): AnthropicProvider, message conversion, SSE parser"
```

---

## Sub-phase E — `amplifier-module-orchestrator-loop-streaming`

### Task 9: `HookEvent`, `HookResult`, `Hook` trait, `HookRegistry`

**Files:**
- Create: `crates/amplifier-module-orchestrator-loop-streaming/src/hooks.rs`
- Modify: `crates/amplifier-module-orchestrator-loop-streaming/src/lib.rs`

**Step 1: Write the failing test first**

Create `crates/amplifier-module-orchestrator-loop-streaming/src/hooks.rs` with just the test scaffolding:

```rust
// src/hooks.rs — empty for now, will cause compile failure
```

Add to `src/lib.rs`:
```rust
pub mod hooks;
```

Run — expect compile failure (module exists but is empty):
```bash
cd /Users/ken/workspace/amplifier-rust
cargo build -p amplifier-module-orchestrator-loop-streaming 2>&1 | head -10
```

Expected: compiles (empty module is fine). Now add the tests, then the implementation.

**Step 2: Replace `src/hooks.rs` with full implementation and tests**

```rust
//! Hook system for the orchestrator loop.
//!
//! **Design note:** `amplifier-core` may already export `HookHandler`, `HookRegistry`,
//! and `HookResult`. If it does, remove this file and import those types directly.
//! This file is a self-contained fallback for when those types are not yet public.

use serde_json::Value;

// ─────────────────────────────── Event types ─────────────────────────────────

/// Lifecycle events the orchestrator emits to hooks.
#[derive(Debug, Clone, PartialEq)]
pub enum HookEvent {
    /// Fired once at the start of the session, before the user message is added.
    SessionStart,
    /// Fired before each LLM call. Hooks may inject ephemeral context.
    ProviderRequest,
    /// Fired before a tool executes. Hooks may deny execution.
    ToolPre,
    /// Fired after a tool executes. Hooks receive the result.
    ToolPost,
    /// Fired after each complete turn (LLM responded with end_turn).
    TurnEnd,
}

/// Data passed to a hook when an event fires.
pub struct HookContext {
    pub event: HookEvent,
    /// Event-specific JSON payload. See each `HookEvent` variant for schema.
    pub data:  Value,
}

/// What a hook returns to the orchestrator.
pub enum HookResult {
    /// Continue normally — no changes.
    Continue,
    /// Append this text to the system prompt for this turn only.
    SystemPromptAddendum(String),
    /// Inject this text as an ephemeral user message (cleared after the provider call).
    InjectContext(String),
    /// Deny tool execution (only valid from `ToolPre` hooks).
    Deny(String),
}

// ─────────────────────────────── Hook trait ──────────────────────────────────

/// Implement this trait to observe and react to orchestrator lifecycle events.
#[async_trait::async_trait]
pub trait Hook: Send + Sync {
    /// Which events this hook wants to receive. Hooks only receive listed events.
    fn events(&self) -> &[HookEvent];
    /// Handle the event. Must not panic.
    async fn handle(&self, ctx: &HookContext) -> HookResult;
}

// ─────────────────────────────── HookRegistry ────────────────────────────────

/// Collects hooks and emits events to matching subscribers.
pub struct HookRegistry {
    hooks: Vec<Box<dyn Hook>>,
}

impl HookRegistry {
    pub fn new() -> Self {
        Self { hooks: Vec::new() }
    }

    /// Register a hook. Order of registration = order of invocation.
    pub fn register(&mut self, hook: Box<dyn Hook>) {
        self.hooks.push(hook);
    }

    /// Fire `event` with `data` to all subscribed hooks.
    /// Returns every `HookResult` in registration order.
    pub async fn emit(&self, event: HookEvent, data: Value) -> Vec<HookResult> {
        let ctx = HookContext { event: event.clone(), data };
        let mut results = Vec::new();
        for hook in &self.hooks {
            if hook.events().contains(&event) {
                results.push(hook.handle(&ctx).await);
            }
        }
        results
    }
}

impl Default for HookRegistry {
    fn default() -> Self { Self::new() }
}

// ──────────────────────────────── Unit tests ─────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicUsize, Ordering};
    use std::sync::Arc;

    struct CountingHook {
        events_subscribed: Vec<HookEvent>,
        call_count:        Arc<AtomicUsize>,
    }

    #[async_trait::async_trait]
    impl Hook for CountingHook {
        fn events(&self) -> &[HookEvent] { &self.events_subscribed }
        async fn handle(&self, _ctx: &HookContext) -> HookResult {
            self.call_count.fetch_add(1, Ordering::SeqCst);
            HookResult::Continue
        }
    }

    struct InjectingHook;

    #[async_trait::async_trait]
    impl Hook for InjectingHook {
        fn events(&self) -> &[HookEvent] { &[HookEvent::ProviderRequest] }
        async fn handle(&self, _ctx: &HookContext) -> HookResult {
            HookResult::InjectContext("injected content".to_string())
        }
    }

    struct DenyingHook;

    #[async_trait::async_trait]
    impl Hook for DenyingHook {
        fn events(&self) -> &[HookEvent] { &[HookEvent::ToolPre] }
        async fn handle(&self, _ctx: &HookContext) -> HookResult {
            HookResult::Deny("access denied".to_string())
        }
    }

    #[tokio::test]
    async fn empty_registry_returns_empty_results() {
        let registry = HookRegistry::new();
        let results = registry.emit(HookEvent::SessionStart, serde_json::json!({})).await;
        assert!(results.is_empty());
    }

    #[tokio::test]
    async fn hook_only_receives_subscribed_events() {
        let count = Arc::new(AtomicUsize::new(0));
        let mut registry = HookRegistry::new();
        registry.register(Box::new(CountingHook {
            events_subscribed: vec![HookEvent::SessionStart],
            call_count:        count.clone(),
        }));
        // Fire an event the hook is NOT subscribed to
        registry.emit(HookEvent::ToolPre, serde_json::json!({})).await;
        assert_eq!(count.load(Ordering::SeqCst), 0, "hook should not fire for unsubscribed events");
    }

    #[tokio::test]
    async fn hook_fires_for_subscribed_event() {
        let count = Arc::new(AtomicUsize::new(0));
        let mut registry = HookRegistry::new();
        registry.register(Box::new(CountingHook {
            events_subscribed: vec![HookEvent::ProviderRequest],
            call_count:        count.clone(),
        }));
        registry.emit(HookEvent::ProviderRequest, serde_json::json!({})).await;
        assert_eq!(count.load(Ordering::SeqCst), 1);
    }

    #[tokio::test]
    async fn emit_returns_inject_context_result() {
        let mut registry = HookRegistry::new();
        registry.register(Box::new(InjectingHook));
        let results = registry.emit(HookEvent::ProviderRequest, serde_json::json!({})).await;
        assert_eq!(results.len(), 1);
        assert!(matches!(&results[0], HookResult::InjectContext(s) if s == "injected content"));
    }

    #[tokio::test]
    async fn emit_returns_deny_result() {
        let mut registry = HookRegistry::new();
        registry.register(Box::new(DenyingHook));
        let results = registry.emit(HookEvent::ToolPre, serde_json::json!({"name": "bash"})).await;
        assert_eq!(results.len(), 1);
        assert!(matches!(&results[0], HookResult::Deny(_)));
    }

    #[tokio::test]
    async fn multiple_hooks_all_fire() {
        let count = Arc::new(AtomicUsize::new(0));
        let mut registry = HookRegistry::new();
        for _ in 0..3 {
            registry.register(Box::new(CountingHook {
                events_subscribed: vec![HookEvent::TurnEnd],
                call_count:        count.clone(),
            }));
        }
        registry.emit(HookEvent::TurnEnd, serde_json::json!({})).await;
        assert_eq!(count.load(Ordering::SeqCst), 3);
    }
}
```

**Step 3: Run the tests**

```bash
cd /Users/ken/workspace/amplifier-rust
cargo test -p amplifier-module-orchestrator-loop-streaming -- hooks 2>&1 | tail -20
```

Expected:
```
test hooks::tests::empty_registry_returns_empty_results ... ok
test hooks::tests::hook_only_receives_subscribed_events ... ok
test hooks::tests::hook_fires_for_subscribed_event ... ok
test hooks::tests::emit_returns_inject_context_result ... ok
test hooks::tests::emit_returns_deny_result ... ok
test hooks::tests::multiple_hooks_all_fire ... ok

test result: ok. 6 passed; 0 failed
```

**Step 4: Commit**

```bash
cd /Users/ken/workspace/amplifier-rust
git add crates/amplifier-module-orchestrator-loop-streaming/
git commit -m "feat(orchestrator): HookEvent, HookResult, Hook trait, HookRegistry"
```

---

### Task 10: `LoopConfig`, `LoopOrchestrator` struct, registration methods

**Files:**
- Modify: `crates/amplifier-module-orchestrator-loop-streaming/src/lib.rs`

**Step 1: Replace `src/lib.rs` with the struct definition and tests**

```rust
//! Multi-turn agent loop orchestrator with SSE streaming and a hook system.
//!
//! ## Usage
//!
//! ```rust,no_run
//! let mut orch = LoopOrchestrator::new(LoopConfig {
//!     max_steps:     10,
//!     system_prompt: "You are a helpful assistant.".to_string(),
//! });
//! orch.register_provider("anthropic", Arc::new(anthropic_provider));
//! orch.register_tool("spawn_agent",   Arc::new(task_tool));
//!
//! let mut ctx = SimpleContext::new(vec![]);
//! let hooks   = HookRegistry::new();
//! let result  = orch.execute("hello", &mut ctx, &hooks, |token| print!("{token}")).await?;
//! ```

pub mod hooks;

use std::collections::HashMap;
use std::sync::{Arc, RwLock};

use amplifier_core::traits::{ContextManager, Provider, Tool};
use serde_json::Value;

pub use hooks::{Hook, HookContext, HookEvent, HookRegistry, HookResult};

// ─────────────────────────────── LoopConfig ──────────────────────────────────

/// Configuration for `LoopOrchestrator`.
#[derive(Debug, Clone)]
pub struct LoopConfig {
    /// Maximum number of provider round-trips before the loop aborts with an error.
    /// Default: 10
    pub max_steps:     usize,
    /// System prompt injected on every request.
    pub system_prompt: String,
}

impl Default for LoopConfig {
    fn default() -> Self {
        Self {
            max_steps:     10,
            system_prompt: String::new(),
        }
    }
}

// ─────────────────────────── LoopOrchestrator ────────────────────────────────

/// Agent loop orchestrator. Stores providers + tools for subagent spawning.
///
/// This struct is typically wrapped in `Arc<LoopOrchestrator>` so it can be
/// shared as a `SubagentRunner` while also owning the tool map.
pub struct LoopOrchestrator {
    pub config:    LoopConfig,
    /// Provider map. Key = provider name (e.g. "anthropic"). Arc so SubagentRunner can clone.
    providers: RwLock<HashMap<String, Arc<dyn Provider>>>,
    /// Tool map. Key = tool name (e.g. "spawn_agent"). Arc so SubagentRunner can clone.
    tools:     RwLock<HashMap<String, Arc<dyn Tool>>>,
}

impl LoopOrchestrator {
    pub fn new(config: LoopConfig) -> Self {
        Self {
            config,
            providers: RwLock::new(HashMap::new()),
            tools:     RwLock::new(HashMap::new()),
        }
    }

    /// Register a provider under `name`. If a provider already exists with this name, it is replaced.
    pub fn register_provider(&self, name: impl Into<String>, provider: Arc<dyn Provider>) {
        self.providers.write().expect("providers rwlock poisoned").insert(name.into(), provider);
    }

    /// Register a tool under its spec name. Retrieves the name from `tool.get_spec().name`.
    pub fn register_tool(&self, tool: Arc<dyn Tool>) {
        let name = tool.get_spec().name.clone();
        self.tools.write().expect("tools rwlock poisoned").insert(name, tool);
    }

    /// Snapshot the current provider map (for passing to `execute` or subagents).
    pub fn snapshot_providers(&self) -> HashMap<String, Arc<dyn Provider>> {
        self.providers.read().expect("providers rwlock poisoned").clone()
    }

    /// Snapshot the current tool map (for passing to `execute` or subagents).
    pub fn snapshot_tools(&self) -> HashMap<String, Arc<dyn Tool>> {
        self.tools.read().expect("tools rwlock poisoned").clone()
    }

    // ─────────────────────────────── Agent loop ──────────────────────────────

    /// Run the agent loop for a single user prompt.
    ///
    /// Fires hooks at `SessionStart`, `ProviderRequest`, `ToolPre`, `ToolPost`, `TurnEnd`.
    /// Calls `on_token` for every text chunk as it arrives from the provider.
    /// Aborts with an error if `max_steps` is exceeded.
    pub async fn execute(
        &self,
        prompt:   impl Into<String>,
        context:  &mut dyn ContextManager,
        hooks:    &HookRegistry,
        on_token: impl Fn(&str) + Send,
    ) -> anyhow::Result<String> {
        let prompt = prompt.into();

        // ── SessionStart hook ────────────────────────────────────────────────
        hooks.emit(HookEvent::SessionStart, serde_json::json!({"prompt": prompt})).await;

        // ── Pick provider (prefer "anthropic", else first registered) ────────
        let providers = self.snapshot_providers();
        let provider  = providers.get("anthropic")
            .or_else(|| providers.values().next())
            .ok_or_else(|| anyhow::anyhow!("no provider registered"))?
            .clone();

        let tools = self.snapshot_tools();

        // ── Add user message ─────────────────────────────────────────────────
        context.add_message(serde_json::json!({"role": "user", "content": prompt})).await
            .map_err(|e| anyhow::anyhow!("context.add_message failed: {e}"))?;

        // ── Tool specs for provider ──────────────────────────────────────────
        let tool_specs: Vec<amplifier_core::messages::ToolSpec> =
            tools.values().map(|t| t.get_spec()).collect();

        // ── Agent loop ───────────────────────────────────────────────────────
        for step in 0..self.config.max_steps {
            // 1. ProviderRequest hook → collect ephemeral injections
            let hook_results = hooks.emit(HookEvent::ProviderRequest, serde_json::json!({"step": step})).await;
            let mut ephemeral: Vec<Value> = Vec::new();
            for result in &hook_results {
                if let HookResult::InjectContext(text) = result {
                    ephemeral.push(serde_json::json!({"role": "user", "content": text}));
                }
            }

            // 2. Get context messages + ephemeral
            let mut msgs = context.get_messages_for_request(None, None).await
                .map_err(|e| anyhow::anyhow!("get_messages_for_request failed: {e}"))?;
            msgs.extend(ephemeral);

            // 3. Build system prompt (config + any addenda from hooks)
            let mut system = self.config.system_prompt.clone();
            for result in &hook_results {
                if let HookResult::SystemPromptAddendum(addendum) = result {
                    system.push('\n');
                    system.push_str(addendum);
                }
            }

            // 4. Build ChatRequest
            let messages: Vec<amplifier_core::messages::Message> = msgs.iter()
                .filter_map(|v| serde_json::from_value::<amplifier_core::messages::Message>(v.clone()).ok())
                .collect();

            let mut all_messages = messages;
            if !system.is_empty() {
                // Prepend system message
                all_messages.insert(0, amplifier_core::messages::Message {
                    role:         amplifier_core::messages::Role::System,
                    content:      amplifier_core::MessageContent::Text(system),
                    name:         None,
                    tool_call_id: None,
                    metadata:     None,
                    extensions:   HashMap::new(),
                });
            }

            let request = amplifier_core::ChatRequest {
                messages:         all_messages,
                tools:            if tool_specs.is_empty() { None } else { Some(tool_specs.clone()) },
                model:            None,
                response_format:  None,
                temperature:      None,
                top_p:            None,
                max_output_tokens: None,
                conversation_id:  None,
                stream:           None,
                metadata:         None,
                tool_choice:      None,
                stop:             None,
                reasoning_effort: None,
                timeout:          None,
                extensions:       HashMap::new(),
            };

            // 5. Call provider
            let response = provider.complete(request).await
                .map_err(|e| anyhow::anyhow!("provider.complete failed: {e}"))?;

            let stop_reason = response.finish_reason.as_deref().unwrap_or("end_turn");

            match stop_reason {
                // ── Turn complete ────────────────────────────────────────────
                "end_turn" | "stop_sequence" | "stop" => {
                    let text = Self::extract_text(&response.content);
                    if !text.is_empty() {
                        on_token(&text);
                    }
                    hooks.emit(HookEvent::TurnEnd, serde_json::json!({"text": text, "step": step})).await;
                    return Ok(text);
                }

                // ── Tool calls ───────────────────────────────────────────────
                "tool_use" | "tool_calls" => {
                    // Emit preamble text immediately
                    let preamble = Self::extract_text(&response.content);
                    if !preamble.is_empty() {
                        on_token(&preamble);
                    }

                    // Persist assistant turn (includes tool_use blocks)
                    let asst_msg = Self::response_to_message(&response.content);
                    context.add_message(asst_msg).await
                        .map_err(|e| anyhow::anyhow!("add assistant message failed: {e}"))?;

                    let tool_calls = provider.parse_tool_calls(&response);
                    let mut result_blocks: Vec<Value> = Vec::new();

                    for call in &tool_calls {
                        let input = serde_json::to_value(&call.arguments).unwrap_or(Value::Null);

                        // ToolPre hook
                        let pre_results = hooks.emit(HookEvent::ToolPre, serde_json::json!({
                            "name": call.name, "id": call.id, "args": input
                        })).await;

                        let denied = pre_results.iter().find_map(|r| {
                            if let HookResult::Deny(reason) = r { Some(reason.clone()) } else { None }
                        });

                        let output = if let Some(reason) = denied {
                            serde_json::json!(format!("Tool execution denied: {reason}"))
                        } else if let Some(tool) = tools.get(&call.name) {
                            match tool.execute(input).await {
                                Ok(r)  => r.output.unwrap_or(serde_json::json!("")),
                                Err(e) => serde_json::json!(format!("Error: {e}")),
                            }
                        } else {
                            serde_json::json!(format!("Unknown tool: {}", call.name))
                        };

                        // ToolPost hook
                        hooks.emit(HookEvent::ToolPost, serde_json::json!({
                            "name": call.name, "id": call.id, "output": output
                        })).await;

                        result_blocks.push(serde_json::json!({
                            "type":         "tool_result",
                            "tool_call_id": call.id,
                            "output":       output,
                        }));
                    }

                    context.add_message(serde_json::json!({
                        "role":    "user",
                        "content": result_blocks,
                    })).await.map_err(|e| anyhow::anyhow!("add tool results failed: {e}"))?;
                }

                other => {
                    let text = Self::extract_text(&response.content);
                    if !text.is_empty() {
                        on_token(&text);
                        return Ok(text);
                    }
                    return Err(anyhow::anyhow!("unexpected stop_reason: {other}"));
                }
            }
        }

        Err(anyhow::anyhow!(
            "max_steps ({}) exceeded without reaching end_turn",
            self.config.max_steps
        ))
    }

    // ─────────────────────────── Helpers ─────────────────────────────────────

    fn extract_text(content: &[amplifier_core::messages::ContentBlock]) -> String {
        content.iter().filter_map(|b| {
            if let amplifier_core::messages::ContentBlock::Text { text, .. } = b { Some(text.as_str()) } else { None }
        }).collect::<Vec<_>>().join("")
    }

    fn response_to_message(content: &[amplifier_core::messages::ContentBlock]) -> Value {
        let blocks: Vec<Value> = content.iter()
            .filter_map(|b| serde_json::to_value(b).ok())
            .collect();
        serde_json::json!({"role": "assistant", "content": blocks})
    }
}

// SubagentRunner impl — LoopOrchestrator runs child sessions
#[async_trait::async_trait]
impl amplifier_module_tool_task::SubagentRunner for LoopOrchestrator {
    async fn run(
        &self,
        req: amplifier_module_tool_task::SpawnRequest,
    ) -> anyhow::Result<String> {
        let mut ctx = amplifier_module_context_simple::SimpleContext::new(req.context);
        let empty_hooks = HookRegistry::new();
        self.execute(req.instruction, &mut ctx, &empty_hooks, |_| {}).await
    }
}

// ──────────────────────────────── Unit tests ─────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn loop_config_default_max_steps_is_10() {
        let cfg = LoopConfig::default();
        assert_eq!(cfg.max_steps, 10);
        assert!(cfg.system_prompt.is_empty());
    }

    #[test]
    fn register_provider_and_snapshot() {
        use amplifier_core::{ChatRequest, ChatResponse, ModelInfo, ProviderError, ProviderInfo};
        use std::pin::Pin;

        struct MockProvider;
        impl amplifier_core::traits::Provider for MockProvider {
            fn name(&self) -> &str { "mock" }
            fn get_info(&self) -> ProviderInfo {
                ProviderInfo { id: "mock".to_string(), display_name: "Mock".to_string(),
                    credential_env_vars: vec![], capabilities: vec![], defaults: HashMap::new(), config_fields: vec![] }
            }
            fn list_models(&self) -> Pin<Box<dyn std::future::Future<Output = Result<Vec<ModelInfo>, ProviderError>> + Send + '_>> {
                Box::pin(async { Ok(vec![]) })
            }
            fn complete(&self, _req: ChatRequest) -> Pin<Box<dyn std::future::Future<Output = Result<ChatResponse, ProviderError>> + Send + '_>> {
                Box::pin(async {
                    Ok(ChatResponse {
                        content: vec![], tool_calls: None, usage: None,
                        degradation: None, finish_reason: Some("end_turn".to_string()),
                        metadata: None, extensions: HashMap::new(),
                    })
                })
            }
            fn parse_tool_calls(&self, _: &ChatResponse) -> Vec<amplifier_core::ToolCall> { vec![] }
        }

        let orch = LoopOrchestrator::new(LoopConfig::default());
        orch.register_provider("mock", Arc::new(MockProvider));
        let providers = orch.snapshot_providers();
        assert!(providers.contains_key("mock"), "provider should be retrievable after registration");
    }

    #[test]
    fn extract_text_joins_text_blocks() {
        let content = vec![
            amplifier_core::messages::ContentBlock::Text {
                text: "Hello ".to_string(), visibility: None, extensions: HashMap::new(),
            },
            amplifier_core::messages::ContentBlock::ToolCall {
                id: "x".to_string(), name: "foo".to_string(), input: HashMap::new(),
                visibility: None, extensions: HashMap::new(),
            },
            amplifier_core::messages::ContentBlock::Text {
                text: "world".to_string(), visibility: None, extensions: HashMap::new(),
            },
        ];
        assert_eq!(LoopOrchestrator::extract_text(&content), "Hello world");
    }
}
```

**Step 2: Run the tests**

```bash
cd /Users/ken/workspace/amplifier-rust
cargo test -p amplifier-module-orchestrator-loop-streaming 2>&1 | tail -15
```

Expected:
```
test tests::loop_config_default_max_steps_is_10 ... ok
test tests::register_provider_and_snapshot ... ok
test tests::extract_text_joins_text_blocks ... ok
test hooks::tests::... (6 hook tests) ... ok

test result: ok. 9 passed; 0 failed
```

**Step 3: Commit**

```bash
cd /Users/ken/workspace/amplifier-rust
git add crates/amplifier-module-orchestrator-loop-streaming/
git commit -m "feat(orchestrator): LoopOrchestrator with hooks, step limit, tool dispatch, SubagentRunner impl"
```

---

### Task 11: Integration test — orchestrator runs to completion with mock provider

**Files:**
- Modify: `crates/amplifier-module-orchestrator-loop-streaming/tests/integration_test.rs`

**Step 1: Write the failing integration test**

```rust
//! Integration tests for LoopOrchestrator.
//! Uses mock providers/tools — no network required.

use std::collections::HashMap;
use std::pin::Pin;
use std::sync::{Arc, Mutex};

use amplifier_core::{ChatRequest, ChatResponse, ContentBlock, ModelInfo, ProviderError, ProviderInfo, ToolCall};
use amplifier_core::traits::Provider;
use amplifier_module_context_simple::SimpleContext;
use amplifier_module_orchestrator_loop_streaming::{
    HookEvent, HookRegistry, HookResult, Hook, HookContext, LoopConfig, LoopOrchestrator,
};
use serde_json::json;

// ─────────────────────────────── Fixtures ────────────────────────────────────

/// A provider that immediately returns "end_turn" with a fixed text response.
struct EndTurnProvider {
    response_text: String,
}

impl Provider for EndTurnProvider {
    fn name(&self) -> &str { "end_turn" }
    fn get_info(&self) -> ProviderInfo {
        ProviderInfo { id: "end_turn".to_string(), display_name: "Mock".to_string(),
            credential_env_vars: vec![], capabilities: vec![], defaults: HashMap::new(), config_fields: vec![] }
    }
    fn list_models(&self) -> Pin<Box<dyn std::future::Future<Output = Result<Vec<ModelInfo>, ProviderError>> + Send + '_>> {
        Box::pin(async { Ok(vec![]) })
    }
    fn complete(&self, _req: ChatRequest) -> Pin<Box<dyn std::future::Future<Output = Result<ChatResponse, ProviderError>> + Send + '_>> {
        let text = self.response_text.clone();
        Box::pin(async move {
            Ok(ChatResponse {
                content: vec![ContentBlock::Text { text, visibility: None, extensions: HashMap::new() }],
                tool_calls: None, usage: None, degradation: None,
                finish_reason: Some("end_turn".to_string()),
                metadata: None, extensions: HashMap::new(),
            })
        })
    }
    fn parse_tool_calls(&self, _: &ChatResponse) -> Vec<ToolCall> { vec![] }
}

/// A provider that always returns `tool_use` for N steps, then `end_turn`.
struct ToolCallingProvider {
    tool_name:  String,
    steps_left: Mutex<usize>,
}

impl Provider for ToolCallingProvider {
    fn name(&self) -> &str { "tool_caller" }
    fn get_info(&self) -> ProviderInfo {
        ProviderInfo { id: "tool_caller".to_string(), display_name: "ToolCaller".to_string(),
            credential_env_vars: vec![], capabilities: vec![], defaults: HashMap::new(), config_fields: vec![] }
    }
    fn list_models(&self) -> Pin<Box<dyn std::future::Future<Output = Result<Vec<ModelInfo>, ProviderError>> + Send + '_>> {
        Box::pin(async { Ok(vec![]) })
    }
    fn complete(&self, _req: ChatRequest) -> Pin<Box<dyn std::future::Future<Output = Result<ChatResponse, ProviderError>> + Send + '_>> {
        let mut steps = self.steps_left.lock().unwrap();
        if *steps > 0 {
            *steps -= 1;
            let name = self.tool_name.clone();
            drop(steps);
            Box::pin(async move {
                Ok(ChatResponse {
                    content: vec![ContentBlock::ToolCall {
                        id: "call_1".to_string(), name,
                        input: HashMap::new(), visibility: None, extensions: HashMap::new(),
                    }],
                    tool_calls:    None, usage: None, degradation: None,
                    finish_reason: Some("tool_use".to_string()),
                    metadata:      None, extensions: HashMap::new(),
                })
            })
        } else {
            drop(steps);
            Box::pin(async {
                Ok(ChatResponse {
                    content: vec![ContentBlock::Text {
                        text: "done".to_string(), visibility: None, extensions: HashMap::new(),
                    }],
                    tool_calls: None, usage: None, degradation: None,
                    finish_reason: Some("end_turn".to_string()),
                    metadata: None, extensions: HashMap::new(),
                })
            })
        }
    }
    fn parse_tool_calls(&self, response: &ChatResponse) -> Vec<ToolCall> {
        response.content.iter().filter_map(|b| {
            if let ContentBlock::ToolCall { id, name, input, .. } = b {
                Some(ToolCall { id: id.clone(), name: name.clone(), arguments: input.clone(), extensions: HashMap::new() })
            } else { None }
        }).collect()
    }
}

/// A tool that always succeeds with a fixed output.
struct EchoTool { name: String }

impl amplifier_core::traits::Tool for EchoTool {
    fn get_spec(&self) -> amplifier_core::messages::ToolSpec {
        amplifier_core::messages::ToolSpec {
            name: self.name.clone(),
            description: Some("echo tool".to_string()),
            parameters: HashMap::new(),
            extensions: HashMap::new(),
        }
    }
    fn execute(&self, input: serde_json::Value) -> Pin<Box<dyn std::future::Future<Output = Result<amplifier_core::ToolResult, amplifier_core::ToolError>> + Send + '_>> {
        Box::pin(async move {
            Ok(amplifier_core::ToolResult { output: Some(json!("echo ok")), metadata: None, extensions: HashMap::new() })
        })
    }
}

// ─────────────────────────────── Tests ───────────────────────────────────────

#[tokio::test]
async fn execute_returns_provider_text_on_end_turn() {
    let orch = LoopOrchestrator::new(LoopConfig::default());
    orch.register_provider("anthropic", Arc::new(EndTurnProvider {
        response_text: "The answer is 42.".to_string(),
    }));

    let mut ctx   = SimpleContext::new(vec![]);
    let hooks     = HookRegistry::new();
    let mut tokens = String::new();

    let result = orch.execute("What is 6 × 7?", &mut ctx, &hooks, |t| tokens.push_str(t)).await;

    assert!(result.is_ok(), "should succeed: {:?}", result);
    assert_eq!(result.unwrap(), "The answer is 42.");
    assert_eq!(tokens, "The answer is 42.", "on_token callback must receive the text");
}

#[tokio::test]
async fn execute_adds_user_message_to_context() {
    let orch = LoopOrchestrator::new(LoopConfig::default());
    orch.register_provider("anthropic", Arc::new(EndTurnProvider { response_text: "ok".to_string() }));

    let mut ctx = SimpleContext::new(vec![]);
    let hooks   = HookRegistry::new();
    orch.execute("ping", &mut ctx, &hooks, |_| {}).await.unwrap();

    let msgs = ctx.get_messages().await.unwrap();
    assert!(msgs.iter().any(|m| m["content"] == "ping"), "user message should be in context");
}

#[tokio::test]
async fn execute_enforces_max_steps() {
    // Provider always returns tool_use — will exceed step limit
    let orch = LoopOrchestrator::new(LoopConfig { max_steps: 3, ..Default::default() });
    orch.register_provider("anthropic", Arc::new(ToolCallingProvider {
        tool_name:  "nonexistent_tool".to_string(),
        steps_left: Mutex::new(999),
    }));

    let mut ctx = SimpleContext::new(vec![]);
    let hooks   = HookRegistry::new();
    let result  = orch.execute("loop forever", &mut ctx, &hooks, |_| {}).await;

    assert!(result.is_err(), "should fail when max_steps exceeded");
    let msg = result.unwrap_err().to_string();
    assert!(msg.contains("max_steps") || msg.contains("3"), "error should mention step limit: {msg}");
}

#[tokio::test]
async fn tool_pre_deny_prevents_tool_execution() {
    struct DenyAll;
    #[async_trait::async_trait]
    impl Hook for DenyAll {
        fn events(&self) -> &[HookEvent] { &[HookEvent::ToolPre] }
        async fn handle(&self, _ctx: &HookContext) -> HookResult {
            HookResult::Deny("not allowed".to_string())
        }
    }

    let orch = LoopOrchestrator::new(LoopConfig::default());
    // Provider calls the tool once, then ends
    orch.register_provider("anthropic", Arc::new(ToolCallingProvider {
        tool_name:  "echo".to_string(),
        steps_left: Mutex::new(1),
    }));
    orch.register_tool(Arc::new(EchoTool { name: "echo".to_string() }));

    let mut registry = HookRegistry::new();
    registry.register(Box::new(DenyAll));

    let mut ctx  = SimpleContext::new(vec![]);
    let result   = orch.execute("run echo", &mut ctx, &registry, |_| {}).await;

    // The loop should complete (not error) — deny just skips the tool
    assert!(result.is_ok(), "deny should skip tool gracefully, not abort loop: {:?}", result);
}

#[tokio::test]
async fn provider_request_hook_inject_context_is_included_in_messages() {
    struct CountingProvider { call_count: Mutex<usize>, last_msg_count: Mutex<usize> }
    impl Provider for CountingProvider {
        fn name(&self) -> &str { "counter" }
        fn get_info(&self) -> ProviderInfo {
            ProviderInfo { id: "counter".to_string(), display_name: "Counter".to_string(),
                credential_env_vars: vec![], capabilities: vec![], defaults: HashMap::new(), config_fields: vec![] }
        }
        fn list_models(&self) -> Pin<Box<dyn std::future::Future<Output = Result<Vec<ModelInfo>, ProviderError>> + Send + '_>> {
            Box::pin(async { Ok(vec![]) })
        }
        fn complete(&self, req: ChatRequest) -> Pin<Box<dyn std::future::Future<Output = Result<ChatResponse, ProviderError>> + Send + '_>> {
            let count = req.messages.len();
            *self.last_msg_count.lock().unwrap() = count;
            Box::pin(async {
                Ok(ChatResponse {
                    content: vec![ContentBlock::Text { text: "ok".to_string(), visibility: None, extensions: HashMap::new() }],
                    tool_calls: None, usage: None, degradation: None,
                    finish_reason: Some("end_turn".to_string()),
                    metadata: None, extensions: HashMap::new(),
                })
            })
        }
        fn parse_tool_calls(&self, _: &ChatResponse) -> Vec<ToolCall> { vec![] }
    }

    struct InjectHook;
    #[async_trait::async_trait]
    impl Hook for InjectHook {
        fn events(&self) -> &[HookEvent] { &[HookEvent::ProviderRequest] }
        async fn handle(&self, _ctx: &HookContext) -> HookResult {
            HookResult::InjectContext("extra context injected".to_string())
        }
    }

    let counter = Arc::new(CountingProvider {
        call_count: Mutex::new(0),
        last_msg_count: Mutex::new(0),
    });
    let orch = LoopOrchestrator::new(LoopConfig::default());
    orch.register_provider("anthropic", counter.clone());

    let mut registry = HookRegistry::new();
    registry.register(Box::new(InjectHook));

    let mut ctx = SimpleContext::new(vec![]);
    orch.execute("hello", &mut ctx, &registry, |_| {}).await.unwrap();

    // With 1 user message + 1 injected ephemeral = 2 messages sent to provider
    let msg_count = *counter.last_msg_count.lock().unwrap();
    assert!(
        msg_count >= 2,
        "provider should receive user message + injected ephemeral; got {msg_count} messages"
    );

    // Ephemeral must NOT be stored in context history
    let ctx_msgs = ctx.get_messages().await.unwrap();
    assert!(!ctx_msgs.iter().any(|m| m["content"] == "extra context injected"),
        "injected ephemeral should not appear in context history");
}
```

**Step 2: Run the integration tests**

```bash
cd /Users/ken/workspace/amplifier-rust
cargo test -p amplifier-module-orchestrator-loop-streaming --test integration_test 2>&1 | tail -20
```

Expected:
```
test execute_returns_provider_text_on_end_turn ... ok
test execute_adds_user_message_to_context ... ok
test execute_enforces_max_steps ... ok
test tool_pre_deny_prevents_tool_execution ... ok
test provider_request_hook_inject_context_is_included_in_messages ... ok

test result: ok. 5 passed; 0 failed
```

**Step 3: Commit**

```bash
cd /Users/ken/workspace/amplifier-rust
git add crates/amplifier-module-orchestrator-loop-streaming/
git commit -m "test(orchestrator): integration tests — end_turn, step limit, ToolPre deny, ephemeral injection"
```

---

## Sub-phase F — `amplifier-module-tool-skills`

### Task 12: SKILL.md parser — frontmatter + body

**Files:**
- Create: `crates/amplifier-module-tool-skills/src/parser.rs`
- Modify: `crates/amplifier-module-tool-skills/src/lib.rs`

**Step 1: Write the failing test**

Create `src/parser.rs` containing only a failing test:

```rust
// FAILING — SkillFrontmatter not defined yet
#[cfg(test)]
mod tests {
    #[test]
    fn parse_skill_md_placeholder() {
        // placeholder — will be replaced in Step 2
        panic!("not implemented");
    }
}
```

Run:
```bash
cd /Users/ken/workspace/amplifier-rust
cargo test -p amplifier-module-tool-skills -- parser::tests::parse_skill_md_placeholder 2>&1 | tail -5
```

Expected: `FAILED` with `panicked at 'not implemented'`

**Step 2: Replace `src/parser.rs` with full implementation**

```rust
//! SKILL.md parser.
//!
//! A valid SKILL.md file looks like:
//!
//! ```markdown
//! ---
//! name: my-skill
//! description: Does something useful
//! context: inject        # or "fork"
//! user_invocable: false  # optional, default false
//! ---
//!
//! The skill body goes here. It is returned as-is.
//! ```
//!
//! The `---` delimiters must be on their own lines.
//! Everything between the first and second `---` is YAML.
//! Everything after the second `---` is the body.

use std::path::PathBuf;
use serde::{Deserialize, Serialize};

/// Context strategy for a skill.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum SkillContext {
    /// Inject the skill body into the current session as system prompt addendum.
    Inject,
    /// Fork — spawn a new subagent with the skill body as instruction.
    Fork,
}

impl Default for SkillContext {
    fn default() -> Self { SkillContext::Inject }
}

/// Parsed SKILL.md frontmatter.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SkillFrontmatter {
    pub name:           String,
    pub description:    String,
    #[serde(default)]
    pub context:        SkillContext,
    #[serde(default)]
    pub user_invocable: bool,
    /// Absolute path to the directory containing the SKILL.md file.
    /// Not parsed from YAML — populated by the skill loader.
    #[serde(skip)]
    pub directory:      PathBuf,
}

/// A fully parsed skill: frontmatter + body.
#[derive(Debug, Clone)]
pub struct ParsedSkill {
    pub frontmatter: SkillFrontmatter,
    pub body:        String,
}

/// Parse a SKILL.md string. Returns `Err` if the file has no valid frontmatter.
pub fn parse_skill_md(content: &str) -> anyhow::Result<ParsedSkill> {
    // Strip a leading UTF-8 BOM if present
    let content = content.strip_prefix('\u{feff}').unwrap_or(content);

    // Split on "---" section markers.
    // A well-formed file looks like:
    //   (empty or whitespace)\n---\n<yaml>\n---\n<body>
    // We find the first and second "---" lines.
    let lines: Vec<&str> = content.lines().collect();
    let mut first_sep  = None;
    let mut second_sep = None;

    for (i, line) in lines.iter().enumerate() {
        if line.trim() == "---" {
            if first_sep.is_none() {
                first_sep = Some(i);
            } else {
                second_sep = Some(i);
                break;
            }
        }
    }

    let (first, second) = match (first_sep, second_sep) {
        (Some(f), Some(s)) => (f, s),
        _ => anyhow::bail!("SKILL.md is missing frontmatter delimiters (---)")
    };

    let yaml_lines  = &lines[first + 1..second];
    let body_lines  = &lines[second + 1..];
    let yaml_str    = yaml_lines.join("\n");
    let body        = body_lines.join("\n").trim().to_string();

    let frontmatter: SkillFrontmatter = serde_yaml::from_str(&yaml_str)
        .map_err(|e| anyhow::anyhow!("SKILL.md YAML parse error: {e}"))?;

    Ok(ParsedSkill { frontmatter, body })
}

// ──────────────────────────────── Unit tests ─────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    const SIMPLE_SKILL: &str = r#"---
name: my-skill
description: Does something useful
context: inject
user_invocable: false
---

The skill body goes here.
It can span multiple lines.
"#;

    const FORK_SKILL: &str = r#"---
name: fork-skill
description: Forks a subagent
context: fork
user_invocable: true
---
Run this as a separate agent.
"#;

    const MINIMAL_SKILL: &str = r#"---
name: minimal
description: Minimal skill
---
body
"#;

    #[test]
    fn parse_simple_skill_name_and_description() {
        let skill = parse_skill_md(SIMPLE_SKILL).unwrap();
        assert_eq!(skill.frontmatter.name,        "my-skill");
        assert_eq!(skill.frontmatter.description, "Does something useful");
    }

    #[test]
    fn parse_simple_skill_context_inject() {
        let skill = parse_skill_md(SIMPLE_SKILL).unwrap();
        assert_eq!(skill.frontmatter.context, SkillContext::Inject);
        assert!(!skill.frontmatter.user_invocable);
    }

    #[test]
    fn parse_fork_skill_context_is_fork() {
        let skill = parse_skill_md(FORK_SKILL).unwrap();
        assert_eq!(skill.frontmatter.context, SkillContext::Fork);
        assert!(skill.frontmatter.user_invocable);
    }

    #[test]
    fn parse_extracts_body() {
        let skill = parse_skill_md(SIMPLE_SKILL).unwrap();
        assert!(skill.body.contains("The skill body goes here."), "body: {:?}", skill.body);
        assert!(skill.body.contains("multiple lines"), "body: {:?}", skill.body);
    }

    #[test]
    fn parse_minimal_skill_defaults_context_to_inject() {
        let skill = parse_skill_md(MINIMAL_SKILL).unwrap();
        assert_eq!(skill.frontmatter.name, "minimal");
        assert_eq!(skill.frontmatter.context, SkillContext::Inject);
        assert!(!skill.frontmatter.user_invocable);
    }

    #[test]
    fn parse_returns_error_for_missing_frontmatter() {
        let no_frontmatter = "Just some text without delimiters\n";
        assert!(parse_skill_md(no_frontmatter).is_err());
    }

    #[test]
    fn parse_returns_error_for_invalid_yaml() {
        let bad_yaml = "---\nname: [unclosed bracket\n---\nbody\n";
        assert!(parse_skill_md(bad_yaml).is_err());
    }

    #[test]
    fn parse_returns_error_for_missing_required_field() {
        // 'name' is required (no default in serde)
        let missing_name = "---\ndescription: something\n---\nbody\n";
        assert!(parse_skill_md(missing_name).is_err());
    }
}
```

**Step 3: Add `pub mod parser;` to `src/lib.rs`**

```rust
// src/lib.rs
pub mod parser;
```

**Step 4: Run the parser tests**

```bash
cd /Users/ken/workspace/amplifier-rust
cargo test -p amplifier-module-tool-skills -- parser 2>&1 | tail -20
```

Expected:
```
test parser::tests::parse_simple_skill_name_and_description ... ok
test parser::tests::parse_simple_skill_context_inject ... ok
test parser::tests::parse_fork_skill_context_is_fork ... ok
test parser::tests::parse_extracts_body ... ok
test parser::tests::parse_minimal_skill_defaults_context_to_inject ... ok
test parser::tests::parse_returns_error_for_missing_frontmatter ... ok
test parser::tests::parse_returns_error_for_invalid_yaml ... ok
test parser::tests::parse_returns_error_for_missing_required_field ... ok

test result: ok. 8 passed; 0 failed
```

**Step 5: Commit**

```bash
cd /Users/ken/workspace/amplifier-rust
git add crates/amplifier-module-tool-skills/
git commit -m "feat(tool-skills): SKILL.md parser — frontmatter, body, context strategy"
```

---

### Task 13: `SkillEngine` — list, search, info, load (inject)

**Files:**
- Modify: `crates/amplifier-module-tool-skills/src/lib.rs`

**Step 1: Write the failing test first**

Add to `src/lib.rs` (after `pub mod parser;`):

```rust
// FAILING — SkillEngine not defined yet
#[cfg(test)]
mod tests {
    #[test]
    fn skill_engine_placeholder() {
        panic!("not implemented");
    }
}
```

Run:
```bash
cd /Users/ken/workspace/amplifier-rust
cargo test -p amplifier-module-tool-skills -- tests::skill_engine_placeholder 2>&1 | tail -5
```

Expected: `FAILED` — `panicked at 'not implemented'`

**Step 2: Replace `src/lib.rs` with the full `SkillEngine` implementation**

```rust
//! `amplifier-module-tool-skills` — SKILL.md discovery, parsing, and dispatch.
//!
//! ## Tool interface
//!
//! Tool name: `load_skill`
//! Operations (via `"operation"` field in JSON input):
//!   - `"list"`   — return all known skill names and descriptions
//!   - `"search"` — filter skills by keyword in name or description
//!   - `"info"`   — return full frontmatter for a named skill
//!   - `"load"`   — load and inject (or fork) a skill
//!
//! ## Skill search paths
//!
//! 1. `<vault_path>/skills/`
//! 2. `~/.amplifier/skills/`
//!
//! Each directory is scanned for `SKILL.md` files (one level deep, one per subdirectory).

pub mod parser;

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::pin::Pin;
use std::sync::Arc;

use amplifier_core::traits::Tool;
use parser::{parse_skill_md, ParsedSkill, SkillContext};
use serde_json::{json, Value};

// ───────────────────────────── SkillEngine ───────────────────────────────────

/// Amplifier tool that loads, lists, searches, and dispatches skills.
pub struct SkillEngine {
    /// Ordered list of directories to search for skills.
    search_paths: Vec<PathBuf>,
    /// Optional subagent runner for `context: fork` skills.
    runner:       Option<Arc<dyn amplifier_module_tool_task::SubagentRunner>>,
}

impl SkillEngine {
    /// Create a `SkillEngine` with the given vault path.
    ///
    /// Automatically adds `<vault_path>/skills/` and `~/.amplifier/skills/` to the search path.
    pub fn new(vault_path: PathBuf) -> Self {
        let mut search_paths = vec![vault_path.join("skills")];
        if let Some(home) = dirs_next_home() {
            search_paths.push(home.join(".amplifier").join("skills"));
        }
        Self { search_paths, runner: None }
    }

    /// Attach a `SubagentRunner` for handling `context: fork` skills.
    pub fn with_runner(mut self, runner: Arc<dyn amplifier_module_tool_task::SubagentRunner>) -> Self {
        self.runner = Some(runner);
        self
    }

    // ─────────────────────── Skill discovery ─────────────────────────────────

    /// Scan all search paths and return all parseable skills.
    pub fn discover_skills(&self) -> Vec<ParsedSkill> {
        let mut skills = Vec::new();
        for path in &self.search_paths {
            if !path.is_dir() { continue; }
            if let Ok(entries) = std::fs::read_dir(path) {
                for entry in entries.flatten() {
                    let skill_dir = entry.path();
                    let skill_md  = skill_dir.join("SKILL.md");
                    if skill_md.is_file() {
                        if let Ok(content) = std::fs::read_to_string(&skill_md) {
                            if let Ok(mut skill) = parse_skill_md(&content) {
                                skill.frontmatter.directory = skill_dir;
                                skills.push(skill);
                            }
                        }
                    }
                }
            }
        }
        skills
    }

    // ─────────────────────── Operation handlers ───────────────────────────────

    fn handle_list(&self) -> Value {
        let skills = self.discover_skills();
        let list: Vec<Value> = skills.iter().map(|s| json!({
            "name":           s.frontmatter.name,
            "description":    s.frontmatter.description,
            "context":        format!("{:?}", s.frontmatter.context).to_lowercase(),
            "user_invocable": s.frontmatter.user_invocable,
        })).collect();
        json!(list)
    }

    fn handle_search(&self, query: &str) -> Value {
        let q = query.to_lowercase();
        let skills = self.discover_skills();
        let results: Vec<Value> = skills.iter()
            .filter(|s| {
                s.frontmatter.name.to_lowercase().contains(&q)
                    || s.frontmatter.description.to_lowercase().contains(&q)
            })
            .map(|s| json!({
                "name":        s.frontmatter.name,
                "description": s.frontmatter.description,
            }))
            .collect();
        json!(results)
    }

    fn handle_info(&self, skill_name: &str) -> anyhow::Result<Value> {
        let skills = self.discover_skills();
        let skill  = skills.iter().find(|s| s.frontmatter.name == skill_name)
            .ok_or_else(|| anyhow::anyhow!("skill not found: {skill_name}"))?;
        Ok(json!({
            "name":           skill.frontmatter.name,
            "description":    skill.frontmatter.description,
            "context":        format!("{:?}", skill.frontmatter.context).to_lowercase(),
            "user_invocable": skill.frontmatter.user_invocable,
            "body_preview":   &skill.body[..skill.body.len().min(200)],
        }))
    }

    async fn handle_load(
        &self,
        skill_name: &str,
    ) -> anyhow::Result<Value> {
        let skills = self.discover_skills();
        let skill  = skills.iter().find(|s| s.frontmatter.name == skill_name)
            .ok_or_else(|| anyhow::anyhow!("skill not found: {skill_name}"))?;

        match skill.frontmatter.context {
            SkillContext::Inject => {
                // Return the skill body — the orchestrator injects it
                Ok(json!({
                    "skill_name":    skill.frontmatter.name,
                    "context":       "inject",
                    "body":          skill.body,
                }))
            }
            SkillContext::Fork => {
                // Spawn a subagent with the skill body as instruction
                let runner = self.runner.as_ref()
                    .ok_or_else(|| anyhow::anyhow!("no SubagentRunner configured — cannot fork skill"))?;
                let result = runner.run(amplifier_module_tool_task::SpawnRequest {
                    instruction:   skill.body.clone(),
                    context_depth: amplifier_module_tool_task::ContextDepth::None,
                    context_scope: amplifier_module_tool_task::ContextScope::Conversation,
                    context:       vec![],
                    session_id:    None,
                }).await?;
                Ok(json!({
                    "skill_name": skill.frontmatter.name,
                    "context":    "fork",
                    "result":     result,
                }))
            }
        }
    }
}

// ──────────────────────────────── Tool impl ──────────────────────────────────

impl Tool for SkillEngine {
    fn get_spec(&self) -> amplifier_core::messages::ToolSpec {
        let mut properties = HashMap::new();
        properties.insert("operation".to_string(), json!({
            "type": "string",
            "enum": ["list", "search", "info", "load"],
            "description": "Operation to perform."
        }));
        properties.insert("skill_name".to_string(), json!({
            "type": "string",
            "description": "Skill name (required for info and load operations)."
        }));
        properties.insert("query".to_string(), json!({
            "type": "string",
            "description": "Search query (required for search operation)."
        }));

        let mut parameters = HashMap::new();
        parameters.insert("type".to_string(),       json!("object"));
        parameters.insert("properties".to_string(), json!(properties));
        parameters.insert("required".to_string(),   json!(["operation"]));

        amplifier_core::messages::ToolSpec {
            name:        "load_skill".to_string(),
            description: Some("List, search, inspect, and load reusable skill definitions.".to_string()),
            parameters,
            extensions:  HashMap::new(),
        }
    }

    fn execute(
        &self,
        input: Value,
    ) -> Pin<Box<dyn std::future::Future<Output = Result<amplifier_core::ToolResult, amplifier_core::ToolError>> + Send + '_>>
    {
        Box::pin(async move {
            let op = input.get("operation").and_then(|v| v.as_str()).unwrap_or("list");

            let result = match op {
                "list"   => Ok(self.handle_list()),
                "search" => {
                    let q = input.get("query").and_then(|v| v.as_str()).unwrap_or("");
                    Ok(self.handle_search(q))
                }
                "info"   => {
                    let name = input.get("skill_name").and_then(|v| v.as_str())
                        .ok_or_else(|| anyhow::anyhow!("'skill_name' required for info operation"))?;
                    self.handle_info(name)
                }
                "load"   => {
                    let name = input.get("skill_name").and_then(|v| v.as_str())
                        .ok_or_else(|| anyhow::anyhow!("'skill_name' required for load operation"))?;
                    self.handle_load(name).await
                }
                other => Err(anyhow::anyhow!("unknown operation: {other}")),
            };

            result
                .map(|v| amplifier_core::ToolResult {
                    output:     Some(v),
                    metadata:   None,
                    extensions: HashMap::new(),
                })
                .map_err(|e| amplifier_core::ToolError::ExecutionError {
                    message:    e.to_string(),
                    tool_name:  Some("load_skill".to_string()),
                    recoverable: false,
                })
        })
    }
}

// Tiny helper — avoids pulling in the `dirs` crate just for home dir
fn dirs_next_home() -> Option<PathBuf> {
    std::env::var("HOME").ok().map(PathBuf::from)
}

// ──────────────────────────────── Unit tests ─────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    /// Create a temporary skill directory with one SKILL.md file.
    fn make_temp_skill(dir: &Path, skill_name: &str, description: &str, context: &str, body: &str) {
        let skill_dir = dir.join(skill_name);
        fs::create_dir_all(&skill_dir).unwrap();
        let content = format!(
            "---\nname: {skill_name}\ndescription: {description}\ncontext: {context}\n---\n{body}\n"
        );
        fs::write(skill_dir.join("SKILL.md"), content).unwrap();
    }

    #[test]
    fn get_spec_name_is_load_skill() {
        let engine = SkillEngine::new(PathBuf::from("/nonexistent"));
        assert_eq!(engine.get_spec().name, "load_skill");
    }

    #[test]
    fn discover_skills_empty_for_nonexistent_path() {
        let engine = SkillEngine::new(PathBuf::from("/nonexistent/vault"));
        assert!(engine.discover_skills().is_empty());
    }

    #[test]
    fn discover_skills_finds_skill_md_files() {
        let dir = tempfile::tempdir().unwrap();
        let vault = dir.path().to_path_buf();
        let skills_dir = vault.join("skills");
        fs::create_dir_all(&skills_dir).unwrap();
        make_temp_skill(&skills_dir, "test-skill", "A test skill", "inject", "Do the test.");

        let engine = SkillEngine::new(vault);
        let skills = engine.discover_skills();
        assert_eq!(skills.len(), 1);
        assert_eq!(skills[0].frontmatter.name, "test-skill");
    }

    #[test]
    fn handle_list_returns_all_skills() {
        let dir = tempfile::tempdir().unwrap();
        let vault = dir.path().to_path_buf();
        let skills_dir = vault.join("skills");
        fs::create_dir_all(&skills_dir).unwrap();
        make_temp_skill(&skills_dir, "skill-a", "First skill",  "inject", "Do A.");
        make_temp_skill(&skills_dir, "skill-b", "Second skill", "fork",   "Do B.");

        let engine = SkillEngine::new(vault);
        let list = engine.handle_list();
        let arr  = list.as_array().unwrap();
        assert_eq!(arr.len(), 2);
        let names: Vec<&str> = arr.iter().map(|v| v["name"].as_str().unwrap()).collect();
        assert!(names.contains(&"skill-a"), "names: {names:?}");
        assert!(names.contains(&"skill-b"), "names: {names:?}");
    }

    #[test]
    fn handle_search_filters_by_keyword() {
        let dir = tempfile::tempdir().unwrap();
        let vault = dir.path().to_path_buf();
        let skills_dir = vault.join("skills");
        fs::create_dir_all(&skills_dir).unwrap();
        make_temp_skill(&skills_dir, "git-workflow", "Git branch workflow helper", "inject", "…");
        make_temp_skill(&skills_dir, "code-review",  "Automated code reviewer",   "inject", "…");

        let engine  = SkillEngine::new(vault);
        let results = engine.handle_search("git");
        let arr     = results.as_array().unwrap();
        assert_eq!(arr.len(), 1);
        assert_eq!(arr[0]["name"], "git-workflow");
    }

    #[test]
    fn handle_info_returns_frontmatter() {
        let dir = tempfile::tempdir().unwrap();
        let vault = dir.path().to_path_buf();
        let skills_dir = vault.join("skills");
        fs::create_dir_all(&skills_dir).unwrap();
        make_temp_skill(&skills_dir, "my-skill", "Does things", "inject", "Here is how.");

        let engine = SkillEngine::new(vault);
        let info   = engine.handle_info("my-skill").unwrap();
        assert_eq!(info["name"],        "my-skill");
        assert_eq!(info["description"], "Does things");
        assert_eq!(info["context"],     "inject");
    }

    #[test]
    fn handle_info_returns_error_for_unknown_skill() {
        let engine = SkillEngine::new(PathBuf::from("/nonexistent"));
        assert!(engine.handle_info("no-such-skill").is_err());
    }

    #[tokio::test]
    async fn handle_load_inject_returns_body() {
        let dir = tempfile::tempdir().unwrap();
        let vault = dir.path().to_path_buf();
        let skills_dir = vault.join("skills");
        fs::create_dir_all(&skills_dir).unwrap();
        make_temp_skill(&skills_dir, "injected-skill", "Inject me", "inject", "This is the skill body.");

        let engine = SkillEngine::new(vault);
        let result = engine.handle_load("injected-skill").await.unwrap();
        assert_eq!(result["context"], "inject");
        assert!(result["body"].as_str().unwrap().contains("This is the skill body."));
    }
}
```

**Step 3: Add `tempfile` to dev-dependencies**

Add to `crates/amplifier-module-tool-skills/Cargo.toml`:

```toml
[dev-dependencies]
tokio    = { workspace = true }
tempfile = "3"
```

**Step 4: Run the skill engine tests**

```bash
cd /Users/ken/workspace/amplifier-rust
cargo test -p amplifier-module-tool-skills 2>&1 | tail -20
```

Expected:
```
test parser::tests::parse_simple_skill_name_and_description ... ok
test parser::tests::parse_extracts_body ... ok
... (all 8 parser tests) ... ok
test tests::get_spec_name_is_load_skill ... ok
test tests::discover_skills_empty_for_nonexistent_path ... ok
test tests::discover_skills_finds_skill_md_files ... ok
test tests::handle_list_returns_all_skills ... ok
test tests::handle_search_filters_by_keyword ... ok
test tests::handle_info_returns_frontmatter ... ok
test tests::handle_info_returns_error_for_unknown_skill ... ok
test tests::handle_load_inject_returns_body ... ok

test result: ok. 16 passed; 0 failed
```

**Step 5: Run the full workspace test suite to verify no regressions**

```bash
cd /Users/ken/workspace/amplifier-rust
cargo test --workspace 2>&1 | tail -10
```

Expected: all tests pass across all 5 crates.

**Step 6: Commit**

```bash
cd /Users/ken/workspace/amplifier-rust
git add crates/amplifier-module-tool-skills/
git commit -m "feat(tool-skills): SkillEngine — list/search/info/load with inject and fork dispatch"
```

---

## Sub-phase G — `amplifier-android` Phase 1 migration

> **All commands in this sub-phase run from the Vela repo root.**  
> **All file paths are relative to `/Users/ken/workspace/vela/`.**

### Task 14: Update `amplifier-android/Cargo.toml` with path dependencies

**Files:**
- Modify: `app/src/main/rust/amplifier-android/Cargo.toml`

**Step 1: Read the existing `Cargo.toml` before editing**

```bash
cat /Users/ken/workspace/vela/app/src/main/rust/amplifier-android/Cargo.toml
```

Verify it matches what you expect (matches the file shown earlier in this plan).

**Step 2: Replace `Cargo.toml` with the updated version**

```toml
[package]
name    = "amplifier-android"
version = "0.1.0"
edition = "2021"

[lib]
crate-type = ["cdylib"]

[dependencies]
# ── amplifier-rust workspace crates (Phase 1 migration) ──────────────────────
amplifier-module-orchestrator-loop-streaming = { path = "/Users/ken/workspace/amplifier-rust/crates/amplifier-module-orchestrator-loop-streaming" }
amplifier-module-provider-anthropic          = { path = "/Users/ken/workspace/amplifier-rust/crates/amplifier-module-provider-anthropic" }
amplifier-module-context-simple              = { path = "/Users/ken/workspace/amplifier-rust/crates/amplifier-module-context-simple" }
amplifier-module-tool-task                   = { path = "/Users/ken/workspace/amplifier-rust/crates/amplifier-module-tool-task" }
amplifier-module-tool-skills                 = { path = "/Users/ken/workspace/amplifier-rust/crates/amplifier-module-tool-skills" }

# ── amplifier-core (still needed directly for JNI wiring types) ──────────────
amplifier-core = { git = "https://github.com/microsoft/amplifier-core", branch = "main" }

# ── JNI bridge ────────────────────────────────────────────────────────────────
jni = "0.21"

# ── Async runtime ─────────────────────────────────────────────────────────────
tokio = { version = "1", features = ["rt-multi-thread", "macros"] }

# ── Serialisation ─────────────────────────────────────────────────────────────
serde      = { version = "1", features = ["derive"] }
serde_json = "1"

# ── Static runtime initialisation ─────────────────────────────────────────────
once_cell = "1"

# ── Android logging ───────────────────────────────────────────────────────────
android_logger = "0.14"
log            = "0.4"

[profile.release]
opt-level = "z"   # minimise binary size
strip     = true
lto       = true
```

**Step 3: Verify the new Cargo.toml is parseable**

```bash
cd /Users/ken/workspace/vela/app/src/main/rust/amplifier-android
cargo metadata --no-deps 2>&1 | head -5
```

Expected: JSON output with no errors (even if it can't build without proper Android targets).

**Step 4: Commit Cargo.toml change before touching source files**

```bash
cd /Users/ken/workspace/vela
git add app/src/main/rust/amplifier-android/Cargo.toml
git commit -m "feat(amplifier-android): update Cargo.toml — add amplifier-rust workspace path deps"
```

---

### Task 15: Rewrite `lib.rs` as thin JNI wiring

**Files:**
- Modify: `app/src/main/rust/amplifier-android/src/lib.rs`

> This is the most complex task. Read the existing `lib.rs` carefully before overwriting — you need to preserve the JNI method signatures exactly so Kotlin does not require any changes.

**Step 1: Note the critical JNI method signature to preserve**

From the existing `lib.rs`, the Kotlin-visible entry point is:

```
Java_com_vela_app_ai_AmplifierBridge_nativeRun
```

Parameters (in order): `api_key`, `model`, `tools_json`, `history_json`, `user_input`, `system_prompt`, `token_cb`, `tool_cb`

The return value is a `jstring` (the final assistant text).

The signature of `ProviderRequestCallback` callback and `ServerToolCallback` must also be preserved.

**Step 2: Rewrite `src/lib.rs`**

```rust
//! `amplifier-android` — JNI bridge from Kotlin to the amplifier-rust agent stack.
//!
//! This file is intentionally thin: it converts JNI types to Rust types,
//! wires the workspace crates, and calls the orchestrator loop. All agent logic
//! lives in the `amplifier-rust` workspace crates.
//!
//! ## Kotlin entry point
//!
//! Package: `com.vela.app.ai`  Class: `AmplifierBridge`
//!
//! ```kotlin
//! external fun nativeRun(
//!     apiKey: String, model: String, toolsJson: String, historyJson: String,
//!     userInput: String, systemPrompt: String,
//!     tokenCb: TokenCallback, toolCb: ToolCallback
//! ): String
//! ```

#![allow(non_snake_case)]

use std::collections::HashMap;
use std::sync::Arc;

use amplifier_module_context_simple::SimpleContext;
use amplifier_module_orchestrator_loop_streaming::{
    LoopConfig, LoopOrchestrator, HookRegistry,
};
use amplifier_module_provider_anthropic::{AnthropicConfig, AnthropicProvider};

use jni::objects::{GlobalRef, JClass, JObject, JString, JValue};
use jni::sys::jstring;
use jni::JNIEnv;
use log::{error, info, LevelFilter};
use once_cell::sync::Lazy;
use serde_json::{json, Value};
use tokio::runtime::Runtime;

mod jni_tools;
use jni_tools::build_tool_map;

// ─────────────────────────── Shared Tokio runtime ────────────────────────────

static RT: Lazy<Runtime> = Lazy::new(|| {
    Runtime::new().expect("failed to create Tokio runtime")
});

// ──────────────────────────────── JNI entry ──────────────────────────────────

#[no_mangle]
pub extern "system" fn Java_com_vela_app_ai_AmplifierBridge_nativeRun(
    mut env:          JNIEnv,
    _class:           JClass,
    api_key:          JString,
    model:            JString,
    tools_json:       JString,
    history_json:     JString,
    user_input:       JString,
    system_prompt:    JString,
    token_cb:         JObject,
    provider_req_cb:  JObject,
    server_tool_cb:   JObject,
) -> jstring {
    // ── Init Android logging once ─────────────────────────────────────────────
    let _ = android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(LevelFilter::Debug)
            .with_tag("amplifier"),
    );

    // ── Extract JNI strings ───────────────────────────────────────────────────
    let api_key:       String = jstring_to_rust(&env, api_key);
    let model:         String = jstring_to_rust(&env, model);
    let tools_json:    String = jstring_to_rust(&env, tools_json);
    let history_json:  String = jstring_to_rust(&env, history_json);
    let user_input:    String = jstring_to_rust(&env, user_input);
    let system_prompt: String = jstring_to_rust(&env, system_prompt);

    // ── Pin callbacks as GlobalRefs so they survive the async runtime ─────────
    let jvm         = env.get_java_vm().expect("failed to get JavaVM");
    let jvm         = Arc::new(jvm);
    let token_cb    = Arc::new(env.new_global_ref(token_cb).expect("failed to GlobalRef token_cb"));
    let prov_req_cb = Arc::new(env.new_global_ref(provider_req_cb).expect("failed to GlobalRef prov_req_cb"));
    let srv_tool_cb = Arc::new(env.new_global_ref(server_tool_cb).expect("failed to GlobalRef server_tool_cb"));

    // ── Run the agent loop on the shared Tokio runtime ────────────────────────
    let result = RT.block_on(async move {
        run_agent_loop(
            api_key, model, tools_json, history_json, user_input, system_prompt,
            jvm, token_cb, prov_req_cb, srv_tool_cb,
        ).await
    });

    // ── Return result to Kotlin ───────────────────────────────────────────────
    let output = match result {
        Ok(text)  => text,
        Err(e)    => { error!("amplifier-android: agent loop failed: {e}"); format!("Error: {e}") }
    };

    env.new_string(&output)
        .expect("failed to create return jstring")
        .into_raw()
}

// ──────────────────────────────── Agent loop wiring ──────────────────────────

async fn run_agent_loop(
    api_key:       String,
    model:         String,
    tools_json:    String,
    history_json:  String,
    user_input:    String,
    system_prompt: String,
    jvm:           Arc<jni::JavaVM>,
    token_cb:      Arc<GlobalRef>,
    prov_req_cb:   Arc<GlobalRef>,
    srv_tool_cb:   Arc<GlobalRef>,
) -> anyhow::Result<String> {
    // ── Parse history from Kotlin (Anthropic wire format) → amplifier-core format
    let raw_history: Vec<Value> = serde_json::from_str(&history_json)
        .unwrap_or_default();
    let history = convert_history_to_core_format(raw_history);

    // ── Build provider ────────────────────────────────────────────────────────
    let provider = AnthropicProvider::new(AnthropicConfig {
        api_key:  api_key.clone(),
        model:    if model.is_empty() { "claude-sonnet-4-5".to_string() } else { model },
        ..Default::default()
    });

    // ── Build Kotlin tool bridge ──────────────────────────────────────────────
    let kotlin_tools = build_tool_map(&tools_json, jvm.clone(), srv_tool_cb.clone());

    // ── Wire the orchestrator ─────────────────────────────────────────────────
    let config = LoopConfig {
        max_steps:     10,
        system_prompt: system_prompt.clone(),
    };
    let orch = Arc::new(LoopOrchestrator::new(config));
    orch.register_provider("anthropic", Arc::new(provider));
    for (name, tool) in kotlin_tools {
        orch.register_tool(tool);
    }

    // ── Build context with pre-loaded history ─────────────────────────────────
    let mut ctx = SimpleContext::new(history);

    // ── Build hooks (Phase 1: provider-request hook bridges to Kotlin callback)
    let mut hooks = HookRegistry::new();
    hooks.register(Box::new(ProviderRequestHook {
        jvm:         jvm.clone(),
        callback:    prov_req_cb.clone(),
    }));

    // ── Token streaming callback ──────────────────────────────────────────────
    let jvm_for_token = jvm.clone();
    let token_cb_for_stream = token_cb.clone();
    let on_token = move |text: &str| {
        emit_token_to_kotlin(&jvm_for_token, &token_cb_for_stream, text);
    };

    // ── Execute ───────────────────────────────────────────────────────────────
    orch.execute(&user_input, &mut ctx, &hooks, on_token).await
}

// ─────────────────────── Provider-request hook bridge ────────────────────────

use amplifier_module_orchestrator_loop_streaming::{Hook, HookContext, HookEvent, HookResult};

struct ProviderRequestHook {
    jvm:      Arc<jni::JavaVM>,
    callback: Arc<GlobalRef>,
}

#[async_trait::async_trait]
impl Hook for ProviderRequestHook {
    fn events(&self) -> &[HookEvent] {
        &[HookEvent::ProviderRequest]
    }

    async fn handle(&self, _ctx: &HookContext) -> HookResult {
        let mut env = match self.jvm.attach_current_thread() {
            Ok(e)  => e,
            Err(_) => return HookResult::Continue,
        };
        let result = env.call_method(
            self.callback.as_ref(),
            "onProviderRequest",
            "()Ljava/lang/String;",
            &[],
        );
        let jvalue = match result {
            Ok(v)  => v,
            Err(_) => { let _ = env.exception_clear(); return HookResult::Continue; }
        };
        let jobject = match jvalue.l() {
            Ok(o) if !o.is_null() => o,
            _ => return HookResult::Continue,
        };
        let jstr = jni::objects::JString::from(jobject);
        match env.get_string(&jstr).map(String::from) {
            Ok(s) if !s.is_empty() => HookResult::InjectContext(s),
            _                      => HookResult::Continue,
        }
    }
}

// ──────────────────────────────── Token emission ─────────────────────────────

fn emit_token_to_kotlin(jvm: &jni::JavaVM, callback: &GlobalRef, text: &str) {
    let Ok(mut env) = jvm.attach_current_thread() else { return; };
    let Ok(j_text)  = env.new_string(text)          else { return; };
    if let Err(e) = env.call_method(
        callback.as_ref(),
        "onToken",
        "(Ljava/lang/String;)V",
        &[JValue::Object(&j_text)],
    ) {
        log::warn!("emit_token_to_kotlin: onToken failed: {e:?}");
        let _ = env.exception_clear();
    }
}

// ─────────────────────── History format conversion ───────────────────────────

/// Convert Anthropic wire-format history (as stored by Kotlin) to amplifier-core format.
///
/// | Anthropic field  | amplifier-core field |
/// |------------------|----------------------|
/// | `type: tool_use` | `type: tool_call`    |
/// | `tool_use_id`    | `tool_call_id`       |
/// | `content`        | `output`             |
fn convert_history_to_core_format(raw: Vec<Value>) -> Vec<Value> {
    raw.into_iter().map(|msg| {
        let role = msg["role"].as_str().unwrap_or("user");
        let content = &msg["content"];

        if let Some(blocks) = content.as_array() {
            let converted: Vec<Value> = blocks.iter().map(|block| {
                let block_type = block["type"].as_str().unwrap_or("");
                match block_type {
                    "tool_use" => json!({
                        "type":    "tool_call",
                        "id":      block["id"],
                        "name":    block["name"],
                        "input":   block["input"],
                    }),
                    "tool_result" => json!({
                        "type":         "tool_result",
                        "tool_call_id": block["tool_use_id"],
                        "output":       block["content"],
                    }),
                    _ => block.clone(),
                }
            }).collect();
            json!({ "role": role, "content": converted })
        } else {
            msg
        }
    }).collect()
}

// ───────────────────────────────── Helpers ───────────────────────────────────

fn jstring_to_rust(env: &JNIEnv, s: JString) -> String {
    env.get_string(&s)
        .map(String::from)
        .unwrap_or_default()
}
```

**Step 3: Commit lib.rs rewrite**

```bash
cd /Users/ken/workspace/vela
git add app/src/main/rust/amplifier-android/src/lib.rs
git commit -m "feat(amplifier-android): rewrite lib.rs — thin JNI wiring through amplifier-rust workspace crates"
```

---

### Task 16: Delete old source files and verify `cargo build`

**Files:**
- Delete: `app/src/main/rust/amplifier-android/src/orchestrator.rs`
- Delete: `app/src/main/rust/amplifier-android/src/provider.rs`
- Delete: `app/src/main/rust/amplifier-android/src/context.rs`

**Step 1: Delete the three files that have been replaced by workspace crates**

```bash
cd /Users/ken/workspace/vela/app/src/main/rust/amplifier-android/src
rm orchestrator.rs provider.rs context.rs
```

**Step 2: Verify `lib.rs` no longer references the deleted modules**

```bash
grep -n "mod orchestrator\|mod provider\|mod context\|use orchestrator\|use provider\|use context" \
  /Users/ken/workspace/vela/app/src/main/rust/amplifier-android/src/lib.rs
```

Expected: **no output** — the new `lib.rs` does not reference these modules.

**Step 3: Verify the crate compiles for the host target (x86_64/aarch64 macOS)**

```bash
cd /Users/ken/workspace/vela/app/src/main/rust/amplifier-android
cargo build -p amplifier-android 2>&1 | tail -20
```

> **Expected outcome for a clean build:** `Finished dev profile [unoptimized + debuginfo] target(s)`
>
> **If you see `error[E0425]: cannot find value \`build_tool_map\` in module \`jni_tools\``:**  
> The `jni_tools.rs` file (unchanged from the original codebase) still works — the `build_tool_map` function is already defined there. This error means the `mod jni_tools` declaration was accidentally dropped from `lib.rs`. Add it back: `mod jni_tools; use jni_tools::build_tool_map;`
>
> **If you see linker errors about `_android_log_print`:**  
> Those are normal for a macOS build — the `android_logger` crate expects Android libc. The crate structure is correct. Build verification for the actual Android targets (aarch64-linux-android) happens in the Android Gradle build, not here.
>
> **If you see errors about missing fields on `AnthropicConfig` or `LoopConfig`:**  
> Check the actual field names in the workspace crates you built in sub-phases B–F. The field names in `lib.rs` must match exactly.

**Step 4: Run workspace tests one final time to confirm nothing regressed**

```bash
cd /Users/ken/workspace/amplifier-rust
cargo test --workspace 2>&1 | tail -10
```

Expected: all tests pass.

**Step 5: Commit the file deletions**

```bash
cd /Users/ken/workspace/vela
git add app/src/main/rust/amplifier-android/src/
git commit -m "feat(amplifier-android): Phase 1 migration complete — delete orchestrator.rs, provider.rs, context.rs; all logic in workspace crates"
```

---

## Final verification checklist

Before calling Phase 1 done, confirm each of the following:

```bash
# 1. All workspace crate tests pass
cd /Users/ken/workspace/amplifier-rust
cargo test --workspace
# Expected: test result: ok. N passed; 0 failed (across all 5 crates)

# 2. LoopConfig.max_steps default is 10
cargo test -p amplifier-module-orchestrator-loop-streaming -- tests::loop_config_default_max_steps
# Expected: ok

# 3. Task tool respects recursion depth guard
cargo test -p amplifier-module-tool-task -- tests::execute_respects_recursion_limit
# Expected: ok

# 4. SSE text delta extraction works
cargo test -p amplifier-module-provider-anthropic -- streaming::tests
# Expected: ok (6 tests)

# 5. SKILL.md parser handles all cases
cargo test -p amplifier-module-tool-skills -- parser::tests
# Expected: ok (8 tests)

# 6. Fork skill requires SubagentRunner
cd /Users/ken/workspace/amplifier-rust
cargo test -p amplifier-module-tool-skills -- tests::handle_load_inject_returns_body
# Expected: ok

# 7. amplifier-android references no deleted modules
grep -rn "mod orchestrator\|mod provider\|mod context" \
  /Users/ken/workspace/vela/app/src/main/rust/amplifier-android/src/
# Expected: no output
```

---

## Phase 1 success criteria

| Criterion | Verified by |
|---|---|
| 5 workspace crates compile with `cargo check --workspace` | Task 2 |
| `LoopOrchestrator` enforces `max_steps: 10` | `execute_enforces_max_steps` integration test |
| Hook system fires at `SessionStart`, `ProviderRequest`, `ToolPre`, `ToolPost`, `TurnEnd` | Hook unit tests + integration tests |
| `ToolPre` `Deny` result skips tool execution | `tool_pre_deny_prevents_tool_execution` test |
| Ephemeral context injected by hooks is NOT stored in history | `provider_request_hook_inject_context_is_included_in_messages` test |
| Anthropic SSE parser extracts text from `content_block_delta` | `streaming::tests` |
| SKILL.md parser reads frontmatter + body | `parser::tests` |
| `SkillEngine` dispatches fork skills to `SubagentRunner` | Wired in `handle_load` |
| `TaskTool` recursion guard prevents infinite subagent depth | `execute_respects_recursion_limit` test |
| `amplifier-android` `lib.rs` is thin wiring only (~150 lines) | File diff — 3 files deleted |
| `orchestrator.rs`, `provider.rs`, `context.rs` deleted from Vela repo | Task 16 Step 2 grep |
