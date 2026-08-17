# Phase 7: Session Persistence + Resume — Implementation Plan

> **Execution:** Use the subagent-driven-development workflow to implement this plan.

**Goal:** Add disk-backed session persistence so sub-agent transcripts survive process exit, and wire the `delegate` tool's existing `session_id` parameter end-to-end so callers can resume prior sub-sessions.

**Architecture:** A new `amplifier-module-session-store` crate defines a `SessionStore` trait and a `FileSessionStore` impl that writes a JSONL transcript to `~/.amplifier/sessions/{session_id}/events.jsonl` and maintains an `index.jsonl` of all sessions. `LoopOrchestrator` gains an optional `Arc<dyn SessionStore>` field; when set it persists `session_start`, `turn`, `tool_call`, and `session_end` events as the loop runs. The `SubagentRunner` trait gains a `resume()` method; `LoopOrchestrator::resume()` loads the prior transcript, replays it as conversation history into a `SimpleContext`, and runs one more turn. `DelegateTool` parses `session_id`, calls `runner.resume()` instead of `runner.run()` when present, and surfaces "session not found" errors cleanly.

**Tech Stack:** Rust 2021, tokio (async + `tokio::sync::Mutex` for index serialization), serde/serde_json (JSONL events), anyhow, async-trait, chrono (timestamps), dirs (home dir lookup), tempfile (tests).

**Workspace root:** `/Users/ken/workspace/amplifier-rust/`

---

## Codebase orientation — read these before touching any file

| Location | Role |
|---|---|
| `Cargo.toml` (workspace) | Add new member `crates/amplifier-module-session-store`; add `chrono` and `dirs` to `[workspace.dependencies]` |
| `crates/amplifier-module-tool-task/src/lib.rs` | Defines `SubagentRunner` trait + `SpawnRequest` — Task 10 extends both |
| `crates/amplifier-module-orchestrator-loop-streaming/src/lib.rs` | `LoopOrchestrator` impl + its `SubagentRunner for LoopOrchestrator` impl — Tasks 11–12 |
| `crates/amplifier-module-tool-delegate/src/lib.rs` | `DelegateTool::execute` — Task 13 wires `session_id` |
| `crates/amplifier-module-context-simple/src/lib.rs` | `SimpleContext::new(history)` is how we replay prior events into a context |
| `sandbox/amplifier-android-sandbox/src/main.rs` | Sandbox startup wiring — Task 15 mounts `FileSessionStore` |

**New crate layout:**
```
crates/amplifier-module-session-store/
├── Cargo.toml
├── src/
│   ├── lib.rs            ← SessionStore trait + re-exports
│   ├── format.rs         ← SessionEvent / SessionMetadata / IndexEntry
│   └── file.rs           ← FileSessionStore impl
└── tests/
    └── integration_test.rs
```

**Test commands (run from workspace root):**
- Per-crate: `cargo test -p amplifier-module-session-store`
- Trait wiring: `cargo test -p amplifier-module-tool-task -p amplifier-module-orchestrator-loop-streaming -p amplifier-module-tool-delegate`
- Full suite: `cargo test --workspace`
- Lints: `cargo clippy --workspace --all-targets -- -D warnings`
- Format: `cargo fmt --check`

**Storage layout produced at runtime:**
```
~/.amplifier/sessions/
├── index.jsonl                       ← one IndexEntry per line
└── {session_id}/
    └── events.jsonl                  ← one SessionEvent per line
```

---

## Group 1 — Workspace setup + transcript format

---

### Task 1: Register the new crate and scaffold files

**Files:**
- Modify: `/Users/ken/workspace/amplifier-rust/Cargo.toml`
- Create: `crates/amplifier-module-session-store/Cargo.toml`
- Create: `crates/amplifier-module-session-store/src/lib.rs`
- Create: `crates/amplifier-module-session-store/src/format.rs`
- Create: `crates/amplifier-module-session-store/src/file.rs`
- Create: `crates/amplifier-module-session-store/tests/integration_test.rs`

**Step 1: Add the workspace member and two new workspace dependencies.**

In `/Users/ken/workspace/amplifier-rust/Cargo.toml`, append `"crates/amplifier-module-session-store"` to `members` (place it just before `"amplifier-agent-foundation"`), and add two lines to `[workspace.dependencies]`:

```toml
chrono = { version = "0.4", features = ["serde"] }
dirs = "5"
```

**Step 2: Write `crates/amplifier-module-session-store/Cargo.toml` (exact contents):**

```toml
[package]
name = "amplifier-module-session-store"
version = "0.1.0"
edition = "2021"

[dependencies]
serde = { workspace = true }
serde_json = { workspace = true }
async-trait = { workspace = true }
anyhow = { workspace = true }
thiserror = { workspace = true }
tokio = { workspace = true }
chrono = { workspace = true }
dirs = { workspace = true }

[dev-dependencies]
tempfile = "3"
```

**Step 3: Write `crates/amplifier-module-session-store/src/lib.rs` (stub):**

```rust
//! amplifier-module-session-store — disk-backed transcripts and resume support.

pub mod file;
pub mod format;

pub use file::FileSessionStore;
pub use format::{IndexEntry, SessionEvent, SessionMetadata};

use async_trait::async_trait;

/// Storage abstraction for sub-agent session transcripts.
#[async_trait]
pub trait SessionStore: Send + Sync {
    async fn begin(&self, session_id: &str, metadata: SessionMetadata) -> anyhow::Result<()>;
    async fn append(&self, session_id: &str, event: SessionEvent) -> anyhow::Result<()>;
    async fn finish(&self, session_id: &str, status: &str, turn_count: usize) -> anyhow::Result<()>;
    async fn load(&self, session_id: &str) -> anyhow::Result<Vec<SessionEvent>>;
    async fn list(&self) -> anyhow::Result<Vec<SessionMetadata>>;
    async fn exists(&self, session_id: &str) -> bool;
}
```

**Step 4: Write `src/format.rs` and `src/file.rs` as one-line `// stub` placeholders so the crate compiles.**

`src/format.rs`:
```rust
// stub — implemented in Task 2
```
`src/file.rs`:
```rust
// stub — implemented in Task 4
```

**Step 5: Write `tests/integration_test.rs` placeholder:**
```rust
//! Integration tests — populated in Tasks 13–14.

#[test]
fn placeholder() {}
```

**Step 6: Verify the workspace compiles.**

Run: `cargo check -p amplifier-module-session-store`
Expected: `Finished` with no errors.

**Step 7: Commit.**

`git add Cargo.toml crates/amplifier-module-session-store && git commit -m "feat(session-store): scaffold new crate"`

---

### Task 2: Define `SessionEvent`, `SessionMetadata`, `IndexEntry`

**Files:**
- Modify: `crates/amplifier-module-session-store/src/format.rs`

**Step 1: Write the failing tests.**

Replace the contents of `src/format.rs` with the test module first (we'll add the types in step 3):

```rust
//! Transcript format — stub for tests.

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn session_start_round_trip() {
        let evt = SessionEvent::SessionStart {
            session_id: "abc".into(),
            parent_id: Some("root".into()),
            agent_name: "explorer".into(),
            timestamp: "2026-04-24T00:00:00Z".into(),
        };
        let json = serde_json::to_string(&evt).unwrap();
        assert!(json.contains("\"type\":\"session_start\""), "got {json}");
        assert!(json.contains("\"session_id\":\"abc\""));
        let back: SessionEvent = serde_json::from_str(&json).unwrap();
        assert_eq!(back, evt);
    }

    #[test]
    fn turn_round_trip() {
        let evt = SessionEvent::Turn {
            role: "user".into(),
            content: "hello".into(),
            timestamp: "t".into(),
        };
        let json = serde_json::to_string(&evt).unwrap();
        assert!(json.contains("\"type\":\"turn\""), "got {json}");
        let back: SessionEvent = serde_json::from_str(&json).unwrap();
        assert_eq!(back, evt);
    }

    #[test]
    fn tool_call_round_trip() {
        let evt = SessionEvent::ToolCall {
            tool: "bash".into(),
            args: serde_json::json!({"cmd":"ls"}),
            result: "out".into(),
            timestamp: "t".into(),
        };
        let json = serde_json::to_string(&evt).unwrap();
        assert!(json.contains("\"type\":\"tool_call\""));
        let back: SessionEvent = serde_json::from_str(&json).unwrap();
        assert_eq!(back, evt);
    }

    #[test]
    fn session_end_round_trip() {
        let evt = SessionEvent::SessionEnd {
            status: "success".into(),
            turn_count: 3,
            timestamp: "t".into(),
        };
        let json = serde_json::to_string(&evt).unwrap();
        assert!(json.contains("\"type\":\"session_end\""));
        let back: SessionEvent = serde_json::from_str(&json).unwrap();
        assert_eq!(back, evt);
    }

    #[test]
    fn index_entry_round_trip() {
        let entry = IndexEntry {
            session_id: "s".into(),
            agent_name: "a".into(),
            parent_id: None,
            created: "t".into(),
            status: "active".into(),
        };
        let json = serde_json::to_string(&entry).unwrap();
        let back: IndexEntry = serde_json::from_str(&json).unwrap();
        assert_eq!(back, entry);
    }
}
```

**Step 2: Run the tests to verify they fail.**

Run: `cargo test -p amplifier-module-session-store --lib`
Expected: FAIL — `cannot find type SessionEvent` etc.

**Step 3: Add the type definitions above the test module.**

Prepend at the top of `src/format.rs`:

```rust
use serde::{Deserialize, Serialize};
use serde_json::Value;

/// One event in a session transcript (one JSONL line in `events.jsonl`).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "type")]
pub enum SessionEvent {
    #[serde(rename = "session_start")]
    SessionStart {
        session_id: String,
        #[serde(skip_serializing_if = "Option::is_none")]
        parent_id: Option<String>,
        agent_name: String,
        timestamp: String,
    },
    #[serde(rename = "turn")]
    Turn {
        role: String,
        content: String,
        timestamp: String,
    },
    #[serde(rename = "tool_call")]
    ToolCall {
        tool: String,
        args: Value,
        result: String,
        timestamp: String,
    },
    #[serde(rename = "session_end")]
    SessionEnd {
        status: String,
        turn_count: usize,
        timestamp: String,
    },
}

/// Metadata describing a session — produced from the `session_start` event
/// and surfaced by `SessionStore::list`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct SessionMetadata {
    pub session_id: String,
    pub agent_name: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub parent_id: Option<String>,
    pub created: String,
    /// `"active"` while in progress, then `"success"` or `"error"` after `finish()`.
    pub status: String,
}

/// One line in the global `~/.amplifier/sessions/index.jsonl` file.
///
/// Identical fields to [`SessionMetadata`] — the index is the canonical
/// list source, so the two types share a shape.
pub type IndexEntry = SessionMetadata;
```

Also delete the leading `//! Transcript format — stub for tests.` line so the file's doc-comment becomes the type definitions.

**Step 4: Run the tests to verify they pass.**

Run: `cargo test -p amplifier-module-session-store --lib`
Expected: PASS — 5 tests run, 5 pass.

**Step 5: Commit.**

`git add crates/amplifier-module-session-store/src/format.rs && git commit -m "feat(session-store): SessionEvent/SessionMetadata/IndexEntry"`

---

## Group 2 — `FileSessionStore` impl, one method at a time

---

### Task 3: `FileSessionStore::new` + path resolution

**Files:**
- Modify: `crates/amplifier-module-session-store/src/file.rs`

**Step 1: Write the failing tests first.**

Replace `src/file.rs` with:

```rust
//! Disk-backed [`SessionStore`] implementation — stub for tests.

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::TempDir;

    #[test]
    fn new_with_root_uses_supplied_path() {
        let tmp = TempDir::new().unwrap();
        let store = FileSessionStore::new_with_root(tmp.path().to_path_buf());
        assert_eq!(store.root(), tmp.path());
    }

    #[test]
    fn session_dir_path_is_under_root() {
        let tmp = TempDir::new().unwrap();
        let store = FileSessionStore::new_with_root(tmp.path().to_path_buf());
        let p = store.session_dir("abc-123");
        assert!(p.starts_with(tmp.path()));
        assert!(p.ends_with("abc-123"));
    }

    #[test]
    fn events_file_path_ends_with_events_jsonl() {
        let tmp = TempDir::new().unwrap();
        let store = FileSessionStore::new_with_root(tmp.path().to_path_buf());
        let p = store.events_file("abc");
        assert!(p.ends_with("events.jsonl"));
    }

    #[test]
    fn index_file_is_root_index_jsonl() {
        let tmp = TempDir::new().unwrap();
        let store = FileSessionStore::new_with_root(tmp.path().to_path_buf());
        assert_eq!(store.index_file(), tmp.path().join("index.jsonl"));
    }
}
```

**Step 2: Run to verify failure.**

Run: `cargo test -p amplifier-module-session-store --lib file::tests`
Expected: FAIL — `FileSessionStore` not found.

**Step 3: Implement the struct and the four path helpers above the tests:**

```rust
use std::path::{Path, PathBuf};
use tokio::sync::Mutex;

/// Disk-backed [`crate::SessionStore`] that writes JSONL transcripts under
/// `{root}/{session_id}/events.jsonl` plus a global `{root}/index.jsonl`.
pub struct FileSessionStore {
    root: PathBuf,
    /// Serializes writes to the shared `index.jsonl` file.
    index_lock: Mutex<()>,
}

impl FileSessionStore {
    /// Create a store rooted at `~/.amplifier/sessions/`.
    ///
    /// Returns an error if the home directory cannot be determined.
    pub fn new() -> anyhow::Result<Self> {
        let home = dirs::home_dir()
            .ok_or_else(|| anyhow::anyhow!("could not determine home directory"))?;
        Ok(Self::new_with_root(home.join(".amplifier").join("sessions")))
    }

    /// Create a store rooted at an arbitrary directory (used in tests).
    pub fn new_with_root(root: PathBuf) -> Self {
        Self {
            root,
            index_lock: Mutex::new(()),
        }
    }

    /// Return the configured root directory.
    pub fn root(&self) -> &Path {
        &self.root
    }

    /// Path to a session's directory.
    pub fn session_dir(&self, session_id: &str) -> PathBuf {
        self.root.join(session_id)
    }

    /// Path to a session's events.jsonl file.
    pub fn events_file(&self, session_id: &str) -> PathBuf {
        self.session_dir(session_id).join("events.jsonl")
    }

    /// Path to the global index.jsonl file.
    pub fn index_file(&self) -> PathBuf {
        self.root.join("index.jsonl")
    }
}
```

**Step 4: Run tests to verify pass.**

Run: `cargo test -p amplifier-module-session-store --lib file::tests`
Expected: PASS — 4 tests pass.

**Step 5: Commit.**

`git add crates/amplifier-module-session-store/src/file.rs && git commit -m "feat(session-store): FileSessionStore::new + path helpers"`

---

### Task 4: `begin()` — create session dir + write `session_start` + index entry

**Files:**
- Modify: `crates/amplifier-module-session-store/src/file.rs`

**Step 1: Add the failing test to the `mod tests` block:**

```rust
#[tokio::test]
async fn begin_creates_session_dir_writes_session_start_and_index() {
    let tmp = TempDir::new().unwrap();
    let store = FileSessionStore::new_with_root(tmp.path().to_path_buf());

    let meta = crate::SessionMetadata {
        session_id: "s1".into(),
        agent_name: "explorer".into(),
        parent_id: Some("root".into()),
        created: "2026-04-24T00:00:00Z".into(),
        status: "active".into(),
    };
    crate::SessionStore::begin(&store, "s1", meta).await.unwrap();

    // Session dir + events.jsonl exist.
    assert!(tmp.path().join("s1").is_dir());
    let events = std::fs::read_to_string(tmp.path().join("s1").join("events.jsonl")).unwrap();
    assert_eq!(events.lines().count(), 1, "exactly one event after begin()");
    assert!(events.contains("\"type\":\"session_start\""));
    assert!(events.contains("\"agent_name\":\"explorer\""));

    // Index has one line.
    let idx = std::fs::read_to_string(tmp.path().join("index.jsonl")).unwrap();
    assert_eq!(idx.lines().count(), 1);
    assert!(idx.contains("\"status\":\"active\""));
}
```

**Step 2: Run to verify failure.**

Run: `cargo test -p amplifier-module-session-store --lib begin_creates_session_dir_writes_session_start_and_index`
Expected: FAIL — `SessionStore` not implemented for `FileSessionStore`.

**Step 3: Implement `SessionStore::begin` (and a private helper `append_jsonl`).**

Append to `src/file.rs`, just below the `impl FileSessionStore { … }` block:

```rust
use crate::{SessionEvent, SessionMetadata, SessionStore};
use async_trait::async_trait;
use std::io::Write;

/// Append a single JSON line to the file at `path`, creating it if missing.
fn append_jsonl<T: serde::Serialize>(path: &Path, value: &T) -> anyhow::Result<()> {
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    let mut f = std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(path)?;
    let line = serde_json::to_string(value)?;
    writeln!(f, "{}", line)?;
    f.flush()?;
    Ok(())
}

#[async_trait]
impl SessionStore for FileSessionStore {
    async fn begin(&self, session_id: &str, metadata: SessionMetadata) -> anyhow::Result<()> {
        // 1. Ensure the session directory exists.
        std::fs::create_dir_all(self.session_dir(session_id))?;

        // 2. Append a session_start event to events.jsonl.
        let evt = SessionEvent::SessionStart {
            session_id: session_id.to_string(),
            parent_id: metadata.parent_id.clone(),
            agent_name: metadata.agent_name.clone(),
            timestamp: metadata.created.clone(),
        };
        append_jsonl(&self.events_file(session_id), &evt)?;

        // 3. Append an index entry under the index lock.
        let _guard = self.index_lock.lock().await;
        append_jsonl(&self.index_file(), &metadata)?;
        Ok(())
    }

    async fn append(&self, _session_id: &str, _event: SessionEvent) -> anyhow::Result<()> {
        anyhow::bail!("append not implemented yet")
    }

    async fn finish(
        &self,
        _session_id: &str,
        _status: &str,
        _turn_count: usize,
    ) -> anyhow::Result<()> {
        anyhow::bail!("finish not implemented yet")
    }

    async fn load(&self, _session_id: &str) -> anyhow::Result<Vec<SessionEvent>> {
        anyhow::bail!("load not implemented yet")
    }

    async fn list(&self) -> anyhow::Result<Vec<SessionMetadata>> {
        anyhow::bail!("list not implemented yet")
    }

    async fn exists(&self, _session_id: &str) -> bool {
        false
    }
}
```

**Step 4: Run the new test.**

Run: `cargo test -p amplifier-module-session-store --lib begin_creates_session_dir`
Expected: PASS.

**Step 5: Commit.**

`git add crates/amplifier-module-session-store/src/file.rs && git commit -m "feat(session-store): FileSessionStore::begin"`

---

### Task 5: `append()` and `exists()`

**Files:**
- Modify: `crates/amplifier-module-session-store/src/file.rs`

**Step 1: Add failing tests to `mod tests`:**

```rust
#[tokio::test]
async fn append_adds_event_line() {
    let tmp = TempDir::new().unwrap();
    let store = FileSessionStore::new_with_root(tmp.path().to_path_buf());
    let meta = crate::SessionMetadata {
        session_id: "s1".into(),
        agent_name: "a".into(),
        parent_id: None,
        created: "t0".into(),
        status: "active".into(),
    };
    crate::SessionStore::begin(&store, "s1", meta).await.unwrap();

    crate::SessionStore::append(
        &store,
        "s1",
        crate::SessionEvent::Turn {
            role: "user".into(),
            content: "hi".into(),
            timestamp: "t1".into(),
        },
    )
    .await
    .unwrap();

    let body = std::fs::read_to_string(tmp.path().join("s1").join("events.jsonl")).unwrap();
    assert_eq!(body.lines().count(), 2, "session_start + 1 turn");
    assert!(body.lines().nth(1).unwrap().contains("\"type\":\"turn\""));
}

#[tokio::test]
async fn exists_true_after_begin_false_otherwise() {
    let tmp = TempDir::new().unwrap();
    let store = FileSessionStore::new_with_root(tmp.path().to_path_buf());
    assert!(!crate::SessionStore::exists(&store, "missing").await);

    let meta = crate::SessionMetadata {
        session_id: "s1".into(),
        agent_name: "a".into(),
        parent_id: None,
        created: "t0".into(),
        status: "active".into(),
    };
    crate::SessionStore::begin(&store, "s1", meta).await.unwrap();
    assert!(crate::SessionStore::exists(&store, "s1").await);
}
```

**Step 2: Run to verify failure.**

Run: `cargo test -p amplifier-module-session-store --lib`
Expected: 2 new tests FAIL with `not implemented yet` / `false`.

**Step 3: Implement.** Replace the placeholder `append` and `exists` bodies in the impl block:

```rust
async fn append(&self, session_id: &str, event: SessionEvent) -> anyhow::Result<()> {
    append_jsonl(&self.events_file(session_id), &event)
}

async fn exists(&self, session_id: &str) -> bool {
    self.events_file(session_id).is_file()
}
```

**Step 4: Run.**

Run: `cargo test -p amplifier-module-session-store --lib`
Expected: all tests PASS.

**Step 5: Commit.**

`git add crates/amplifier-module-session-store/src/file.rs && git commit -m "feat(session-store): append + exists"`

---

### Task 6: `finish()` — append `session_end` and update index status

**Files:**
- Modify: `crates/amplifier-module-session-store/src/file.rs`

**Step 1: Add the failing test:**

```rust
#[tokio::test]
async fn finish_appends_session_end_and_rewrites_index_status() {
    let tmp = TempDir::new().unwrap();
    let store = FileSessionStore::new_with_root(tmp.path().to_path_buf());
    let meta = crate::SessionMetadata {
        session_id: "s1".into(),
        agent_name: "a".into(),
        parent_id: None,
        created: "t0".into(),
        status: "active".into(),
    };
    crate::SessionStore::begin(&store, "s1", meta).await.unwrap();

    crate::SessionStore::finish(&store, "s1", "success", 4).await.unwrap();

    // events.jsonl has session_start + session_end.
    let events = std::fs::read_to_string(tmp.path().join("s1").join("events.jsonl")).unwrap();
    let last = events.lines().last().unwrap();
    assert!(last.contains("\"type\":\"session_end\""));
    assert!(last.contains("\"status\":\"success\""));
    assert!(last.contains("\"turn_count\":4"));

    // Index entry status was rewritten in place.
    let idx = std::fs::read_to_string(tmp.path().join("index.jsonl")).unwrap();
    assert_eq!(idx.lines().count(), 1, "still one entry, not duplicated");
    assert!(idx.contains("\"status\":\"success\""));
    assert!(!idx.contains("\"status\":\"active\""));
}
```

**Step 2: Run.** Expected: FAIL with `finish not implemented yet`.

**Step 3: Implement.** Replace the placeholder `finish` body with:

```rust
async fn finish(
    &self,
    session_id: &str,
    status: &str,
    turn_count: usize,
) -> anyhow::Result<()> {
    let now = chrono::Utc::now().to_rfc3339();

    // 1. Append session_end event.
    let evt = SessionEvent::SessionEnd {
        status: status.to_string(),
        turn_count,
        timestamp: now,
    };
    append_jsonl(&self.events_file(session_id), &evt)?;

    // 2. Rewrite the index with this session's status updated in place.
    let _guard = self.index_lock.lock().await;
    let idx_path = self.index_file();
    let body = match std::fs::read_to_string(&idx_path) {
        Ok(s) => s,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => String::new(),
        Err(e) => return Err(e.into()),
    };
    let mut out = String::new();
    for line in body.lines() {
        if line.trim().is_empty() {
            continue;
        }
        let mut entry: SessionMetadata = serde_json::from_str(line)?;
        if entry.session_id == session_id {
            entry.status = status.to_string();
        }
        out.push_str(&serde_json::to_string(&entry)?);
        out.push('\n');
    }
    std::fs::write(&idx_path, out)?;
    Ok(())
}
```

**Step 4: Run.**

Run: `cargo test -p amplifier-module-session-store --lib finish_appends_session_end_and_rewrites_index_status`
Expected: PASS.

**Step 5: Commit.**

`git add crates/amplifier-module-session-store/src/file.rs && git commit -m "feat(session-store): finish() updates index status in place"`

---

### Task 7: `load()` (with malformed-line handling) and `list()`

**Files:**
- Modify: `crates/amplifier-module-session-store/src/file.rs`

**Step 1: Add three failing tests:**

```rust
#[tokio::test]
async fn load_returns_events_in_order() {
    let tmp = TempDir::new().unwrap();
    let store = FileSessionStore::new_with_root(tmp.path().to_path_buf());
    let meta = crate::SessionMetadata {
        session_id: "s1".into(),
        agent_name: "a".into(),
        parent_id: None,
        created: "t0".into(),
        status: "active".into(),
    };
    crate::SessionStore::begin(&store, "s1", meta).await.unwrap();
    crate::SessionStore::append(
        &store,
        "s1",
        crate::SessionEvent::Turn {
            role: "user".into(),
            content: "hi".into(),
            timestamp: "t1".into(),
        },
    )
    .await
    .unwrap();
    crate::SessionStore::finish(&store, "s1", "success", 1).await.unwrap();

    let events = crate::SessionStore::load(&store, "s1").await.unwrap();
    assert_eq!(events.len(), 3, "session_start + turn + session_end");
    assert!(matches!(events[0], crate::SessionEvent::SessionStart { .. }));
    assert!(matches!(events[1], crate::SessionEvent::Turn { .. }));
    assert!(matches!(events[2], crate::SessionEvent::SessionEnd { .. }));
}

#[tokio::test]
async fn load_returns_clear_error_on_malformed_jsonl() {
    let tmp = TempDir::new().unwrap();
    let store = FileSessionStore::new_with_root(tmp.path().to_path_buf());
    std::fs::create_dir_all(tmp.path().join("bad")).unwrap();
    std::fs::write(
        tmp.path().join("bad").join("events.jsonl"),
        "{\"type\":\"session_start\",\"session_id\":\"bad\",\"agent_name\":\"a\",\"timestamp\":\"t\"}\nNOT JSON\n",
    )
    .unwrap();

    let err = crate::SessionStore::load(&store, "bad").await.unwrap_err();
    let msg = err.to_string();
    assert!(
        msg.contains("malformed") || msg.contains("line 2"),
        "expected clear malformed-line error, got: {msg}"
    );
    // No panic — we got here.
}

#[tokio::test]
async fn list_returns_all_sessions_in_order() {
    let tmp = TempDir::new().unwrap();
    let store = FileSessionStore::new_with_root(tmp.path().to_path_buf());
    for id in ["s1", "s2", "s3"] {
        let meta = crate::SessionMetadata {
            session_id: id.into(),
            agent_name: "a".into(),
            parent_id: None,
            created: format!("t-{id}"),
            status: "active".into(),
        };
        crate::SessionStore::begin(&store, id, meta).await.unwrap();
    }
    let metas = crate::SessionStore::list(&store).await.unwrap();
    let ids: Vec<&str> = metas.iter().map(|m| m.session_id.as_str()).collect();
    assert_eq!(ids, vec!["s1", "s2", "s3"]);
}
```

**Step 2: Run to verify failure.**

Run: `cargo test -p amplifier-module-session-store --lib`
Expected: 3 new tests FAIL.

**Step 3: Implement `load` and `list`.** Replace their placeholder bodies:

```rust
async fn load(&self, session_id: &str) -> anyhow::Result<Vec<SessionEvent>> {
    let path = self.events_file(session_id);
    let body = std::fs::read_to_string(&path)
        .map_err(|e| anyhow::anyhow!("could not read {}: {}", path.display(), e))?;
    let mut out = Vec::new();
    for (i, line) in body.lines().enumerate() {
        if line.trim().is_empty() {
            continue;
        }
        let evt: SessionEvent = serde_json::from_str(line).map_err(|e| {
            anyhow::anyhow!("malformed JSONL at {} line {}: {}", path.display(), i + 1, e)
        })?;
        out.push(evt);
    }
    Ok(out)
}

async fn list(&self) -> anyhow::Result<Vec<SessionMetadata>> {
    let path = self.index_file();
    let body = match std::fs::read_to_string(&path) {
        Ok(s) => s,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(vec![]),
        Err(e) => return Err(e.into()),
    };
    let mut out = Vec::new();
    for (i, line) in body.lines().enumerate() {
        if line.trim().is_empty() {
            continue;
        }
        let m: SessionMetadata = serde_json::from_str(line).map_err(|e| {
            anyhow::anyhow!("malformed index.jsonl line {}: {}", i + 1, e)
        })?;
        out.push(m);
    }
    Ok(out)
}
```

**Step 4: Run.**

Run: `cargo test -p amplifier-module-session-store --lib`
Expected: all tests PASS.

**Step 5: Commit.**

`git add crates/amplifier-module-session-store/src/file.rs && git commit -m "feat(session-store): load + list with malformed-line errors"`

---

## Group 3 — Resume support in `SubagentRunner` and `LoopOrchestrator`

---

### Task 8: Extend `SubagentRunner` trait with `resume()` and add `SpawnResult`

**Files:**
- Modify: `crates/amplifier-module-tool-task/src/lib.rs`

**Step 1: Write the failing test.**

Append a new test to `mod tests` in `crates/amplifier-module-tool-task/src/lib.rs`:

```rust
// --- Test 8: subagent_runner_resume_default_returns_unsupported ---

/// Verify the default `resume()` impl returns an error mentioning
/// "resume not supported". Implementors must override.
#[tokio::test]
async fn subagent_runner_resume_default_returns_unsupported() {
    let runner = SuccessRunner { response: "ignored".into() };
    let res = runner.resume("sid", "do more".to_string()).await;
    let err = res.expect_err("default resume must return Err");
    assert!(
        err.to_string().to_lowercase().contains("resume not supported"),
        "expected 'resume not supported', got: {err}"
    );
}

// --- Test 9: spawn_result_constructible ---

#[test]
fn spawn_result_constructible() {
    let r = SpawnResult {
        response: "hi".into(),
        session_id: "s".into(),
    };
    assert_eq!(r.response, "hi");
    assert_eq!(r.session_id, "s");
}
```

**Step 2: Run to verify failure.**

Run: `cargo test -p amplifier-module-tool-task subagent_runner_resume_default_returns_unsupported spawn_result_constructible`
Expected: FAIL — `resume` and `SpawnResult` not found.

**Step 3: Add `SpawnResult` and extend the trait.**

Insert above the `SubagentRunner` trait definition:

```rust
// ---------------------------------------------------------------------------
// SpawnResult
// ---------------------------------------------------------------------------

/// Result of a sub-agent run or resume. Carries both the response text and
/// the session ID so callers can persist or resume later.
#[derive(Debug, Clone, PartialEq)]
pub struct SpawnResult {
    pub response: String,
    pub session_id: String,
}
```

Replace the existing `SubagentRunner` trait body with:

```rust
#[async_trait::async_trait]
pub trait SubagentRunner: Send + Sync {
    /// Run a sub-agent with the given request.
    async fn run(&self, req: SpawnRequest) -> anyhow::Result<String>;

    /// Resume an existing session by `session_id`, appending `instruction`
    /// as the next user turn.
    ///
    /// The default implementation returns `Err("resume not supported")` so
    /// existing implementors keep compiling. Implementors that have a
    /// session store should override this.
    async fn resume(
        &self,
        _session_id: &str,
        _instruction: String,
    ) -> anyhow::Result<SpawnResult> {
        anyhow::bail!("resume not supported by this runner")
    }
}
```

**Step 4: Run.**

Run: `cargo test -p amplifier-module-tool-task`
Expected: all tests PASS, including the two new ones.

**Step 5: Verify the workspace still compiles.**

Run: `cargo check --workspace`
Expected: clean.

**Step 6: Commit.**

`git add crates/amplifier-module-tool-task/src/lib.rs && git commit -m "feat(tool-task): add SubagentRunner::resume + SpawnResult"`

---

### Task 9: Wire `SessionStore` into `LoopOrchestrator` and persist events from `execute()`

**Files:**
- Modify: `crates/amplifier-module-orchestrator-loop-streaming/Cargo.toml`
- Modify: `crates/amplifier-module-orchestrator-loop-streaming/src/lib.rs`

**Step 1: Add the dependency.** In the orchestrator's `Cargo.toml` under `[dependencies]`:

```toml
amplifier-module-session-store = { path = "../amplifier-module-session-store" }
chrono = { workspace = true }
```

**Step 2: Write the failing test.** Append at the bottom of `mod tests` in `crates/amplifier-module-orchestrator-loop-streaming/src/lib.rs`:

```rust
// -----------------------------------------------------------------------
// Test: execute persists events when a SessionStore is attached
// -----------------------------------------------------------------------

#[tokio::test]
async fn execute_persists_events_when_store_attached() {
    use amplifier_module_session_store::{
        FileSessionStore, SessionEvent, SessionMetadata, SessionStore,
    };
    use amplifier_module_context_simple::SimpleContext;

    let tmp = tempfile::TempDir::new().unwrap();
    let store = Arc::new(FileSessionStore::new_with_root(tmp.path().to_path_buf()));
    let session_id = "test-session-1".to_string();

    let mut orch = LoopOrchestrator::new(LoopConfig::default());
    orch.attach_store(store.clone(), session_id.clone(), "test-agent".into(), None);

    let provider: Arc<dyn Provider> = Arc::new(MockProvider::new("anthropic"));
    orch.register_provider("anthropic".into(), provider).await;

    // Pre-create the session.
    let meta = SessionMetadata {
        session_id: session_id.clone(),
        agent_name: "test-agent".into(),
        parent_id: None,
        created: chrono::Utc::now().to_rfc3339(),
        status: "active".into(),
    };
    store.begin(&session_id, meta).await.unwrap();

    let mut ctx = SimpleContext::new(vec![]);
    let hooks = HookRegistry::new();
    let _ = orch
        .execute("hello".to_string(), &mut ctx, &hooks, |_| {})
        .await
        .unwrap();
    orch.finish_store("success").await.unwrap();

    let events: Vec<SessionEvent> = store.load(&session_id).await.unwrap();
    // session_start (from begin) + at least one user Turn + at least one assistant Turn + session_end
    assert!(events.len() >= 4, "expected ≥4 events, got {}", events.len());
    assert!(matches!(events.first().unwrap(), SessionEvent::SessionStart { .. }));
    assert!(events.iter().any(|e| matches!(e, SessionEvent::Turn { role, .. } if role == "user")));
    assert!(events.iter().any(|e| matches!(e, SessionEvent::Turn { role, .. } if role == "assistant")));
    assert!(matches!(events.last().unwrap(), SessionEvent::SessionEnd { .. }));
}
```

Add this to `[dev-dependencies]` of the orchestrator's Cargo.toml:
```toml
amplifier-module-session-store = { path = "../amplifier-module-session-store" }
amplifier-module-context-simple = { path = "../amplifier-module-context-simple" }
tempfile = "3"
chrono = { workspace = true }
```

**Step 3: Run to verify failure.**

Run: `cargo test -p amplifier-module-orchestrator-loop-streaming execute_persists_events_when_store_attached`
Expected: FAIL — `attach_store` / `finish_store` not found.

**Step 4: Implement persistence in `LoopOrchestrator`.**

Edit `LoopOrchestrator` and `execute()` in `src/lib.rs`:

a) Add an import near the existing imports:

```rust
use amplifier_module_session_store::{SessionEvent, SessionStore};
```

b) Extend the struct (replace its current definition):

```rust
pub struct LoopOrchestrator {
    pub config: LoopConfig,
    pub providers: RwLock<HashMap<String, Arc<dyn Provider>>>,
    pub tools: RwLock<HashMap<String, Arc<dyn Tool>>>,

    /// Optional session store for persisting transcripts.
    session_store: RwLock<Option<Arc<dyn SessionStore>>>,
    session_id: RwLock<Option<String>>,
    #[allow(dead_code)]
    agent_name: RwLock<Option<String>>,
    #[allow(dead_code)]
    parent_id: RwLock<Option<String>>,
}
```

Update `LoopOrchestrator::new` to initialize the new fields with `RwLock::new(None)`.

c) Add three helper methods inside `impl LoopOrchestrator`:

```rust
/// Attach a session store. After this call, `execute()` and `resume()`
/// will persist `Turn` and `ToolCall` events.
pub fn attach_store(
    &self,
    store: Arc<dyn SessionStore>,
    session_id: String,
    agent_name: String,
    parent_id: Option<String>,
) {
    *self.session_store.try_write().expect("attach_store contention") = Some(store);
    *self.session_id.try_write().expect("attach_store contention") = Some(session_id);
    *self.agent_name.try_write().expect("attach_store contention") = Some(agent_name);
    *self.parent_id.try_write().expect("attach_store contention") = parent_id;
}

/// Persist a single event to the attached store, if any. Errors are
/// propagated so callers can decide whether to continue.
async fn persist(&self, event: SessionEvent) -> anyhow::Result<()> {
    let store = self.session_store.read().await.clone();
    let sid = self.session_id.read().await.clone();
    if let (Some(store), Some(sid)) = (store, sid) {
        store.append(&sid, event).await?;
    }
    Ok(())
}

/// Finalize the attached store with `status` and the persisted turn count.
pub async fn finish_store(&self, status: &str) -> anyhow::Result<()> {
    let store = self.session_store.read().await.clone();
    let sid = self.session_id.read().await.clone();
    if let (Some(store), Some(sid)) = (store, sid) {
        // We don't track turn_count internally; pass 0 — clients query load()
        // directly for the real count. The status is what matters for resume.
        store.finish(&sid, status, 0).await?;
    }
    Ok(())
}
```

d) Inside `execute()`, persist the user prompt right after step 4 (`context.add_message(user)`):

```rust
self.persist(SessionEvent::Turn {
    role: "user".into(),
    content: prompt.clone(),
    timestamp: chrono::Utc::now().to_rfc3339(),
})
.await?;
```

e) Just before `return Ok(text);` in the `end_turn`/`stop_sequence`/`stop` arm, persist the assistant turn:

```rust
self.persist(SessionEvent::Turn {
    role: "assistant".into(),
    content: text.clone(),
    timestamp: chrono::Utc::now().to_rfc3339(),
})
.await?;
```

f) After each tool execution, just after the `ToolPost` hook emit, persist a `ToolCall`:

```rust
self.persist(SessionEvent::ToolCall {
    tool: call.name.clone(),
    args: args_value.clone(),
    result: output.to_string(),
    timestamp: chrono::Utc::now().to_rfc3339(),
})
.await?;
```

**Step 5: Run.**

Run: `cargo test -p amplifier-module-orchestrator-loop-streaming execute_persists_events_when_store_attached`
Expected: PASS.

**Step 6: Confirm the rest of the suite still passes.**

Run: `cargo test -p amplifier-module-orchestrator-loop-streaming`
Expected: all tests PASS.

**Step 7: Commit.**

`git add crates/amplifier-module-orchestrator-loop-streaming && git commit -m "feat(orchestrator): persist events to attached SessionStore"`

---

### Task 10: Implement `LoopOrchestrator::resume()` + `SubagentRunner::resume` impl

**Files:**
- Modify: `crates/amplifier-module-orchestrator-loop-streaming/src/lib.rs`

**Step 1: Write the failing test.** Append to `mod tests`:

```rust
#[tokio::test]
async fn resume_replays_prior_turns_into_context() {
    use amplifier_module_session_store::{
        FileSessionStore, SessionEvent, SessionMetadata, SessionStore,
    };
    use amplifier_module_tool_task::SubagentRunner;

    let tmp = tempfile::TempDir::new().unwrap();
    let store = Arc::new(FileSessionStore::new_with_root(tmp.path().to_path_buf()));
    let sid = "resume-1".to_string();

    // Seed a finished prior session with one user/assistant pair.
    store
        .begin(
            &sid,
            SessionMetadata {
                session_id: sid.clone(),
                agent_name: "explorer".into(),
                parent_id: None,
                created: "t0".into(),
                status: "active".into(),
            },
        )
        .await
        .unwrap();
    store
        .append(
            &sid,
            SessionEvent::Turn {
                role: "user".into(),
                content: "list rust files".into(),
                timestamp: "t1".into(),
            },
        )
        .await
        .unwrap();
    store
        .append(
            &sid,
            SessionEvent::Turn {
                role: "assistant".into(),
                content: "found: a.rs, b.rs, c.rs".into(),
                timestamp: "t2".into(),
            },
        )
        .await
        .unwrap();
    store.finish(&sid, "success", 1).await.unwrap();

    // Build a fresh orchestrator (simulating a process restart).
    let orch = LoopOrchestrator::new(LoopConfig::default());
    orch.attach_store(store.clone(), sid.clone(), "explorer".into(), None);
    orch.register_provider(
        "anthropic".into(),
        Arc::new(MockProvider::new("anthropic")) as Arc<dyn Provider>,
    )
    .await;

    let result: amplifier_module_tool_task::SpawnResult =
        SubagentRunner::resume(&orch, &sid, "now count them".to_string())
            .await
            .unwrap();
    assert_eq!(result.session_id, sid);

    // After resume, events.jsonl includes the prior 4 events + new user/assistant.
    let events = store.load(&sid).await.unwrap();
    let user_turns: Vec<_> = events
        .iter()
        .filter_map(|e| {
            if let SessionEvent::Turn { role, content, .. } = e {
                if role == "user" {
                    return Some(content.as_str());
                }
            }
            None
        })
        .collect();
    assert!(user_turns.iter().any(|c| c.contains("list rust files")));
    assert!(user_turns.iter().any(|c| c.contains("now count them")));
}
```

**Step 2: Run to verify failure.**

Run: `cargo test -p amplifier-module-orchestrator-loop-streaming resume_replays_prior_turns_into_context`
Expected: FAIL — default `resume` returns `not supported`.

**Step 3: Add `resume()` to `LoopOrchestrator` (the inherent impl), and override the `SubagentRunner::resume` method.**

Inside `impl LoopOrchestrator { … }`, add:

```rust
/// Load `session_id`'s prior events, replay them as conversation history,
/// then run one more turn with `instruction` as the new user message.
pub async fn resume(
    &self,
    session_id: &str,
    instruction: String,
) -> anyhow::Result<amplifier_module_tool_task::SpawnResult> {
    let store = self
        .session_store
        .read()
        .await
        .clone()
        .ok_or_else(|| anyhow::anyhow!("resume requires an attached SessionStore"))?;

    if !store.exists(session_id).await {
        anyhow::bail!("session not found: {session_id}");
    }

    // 1. Load prior events.
    let prior = store.load(session_id).await?;

    // 2. Re-attach (in case attach_store was called with a different sid).
    *self.session_id.write().await = Some(session_id.to_string());

    // 3. Build a SimpleContext from prior Turn events.
    let mut history: Vec<serde_json::Value> = Vec::new();
    for evt in &prior {
        if let SessionEvent::Turn { role, content, .. } = evt {
            history.push(serde_json::json!({
                "role": role,
                "content": content,
            }));
        }
    }
    let mut ctx = amplifier_module_context_simple::SimpleContext::new(history);
    let hooks = HookRegistry::new();

    // 4. Run one more turn — execute() will persist the new turns.
    let response = self.execute(instruction, &mut ctx, &hooks, |_| {}).await?;

    Ok(amplifier_module_tool_task::SpawnResult {
        response,
        session_id: session_id.to_string(),
    })
}
```

Below the existing `impl SubagentRunner for LoopOrchestrator` block, override `resume`:

```rust
// Inside the existing `#[async_trait] impl SubagentRunner for LoopOrchestrator { … }`:
async fn resume(
    &self,
    session_id: &str,
    instruction: String,
) -> anyhow::Result<amplifier_module_tool_task::SpawnResult> {
    LoopOrchestrator::resume(self, session_id, instruction).await
}
```

**Step 4: Run.**

Run: `cargo test -p amplifier-module-orchestrator-loop-streaming resume_replays_prior_turns_into_context`
Expected: PASS.

**Step 5: Run the full crate suite.**

Run: `cargo test -p amplifier-module-orchestrator-loop-streaming`
Expected: all tests PASS.

**Step 6: Commit.**

`git add crates/amplifier-module-orchestrator-loop-streaming/src/lib.rs && git commit -m "feat(orchestrator): LoopOrchestrator::resume replays prior transcript"`

---

## Group 4 — `DelegateTool` end-to-end resume path

---

### Task 11: Wire `SessionStore` and `session_id` resume into `DelegateTool`

**Files:**
- Modify: `crates/amplifier-module-tool-delegate/Cargo.toml`
- Modify: `crates/amplifier-module-tool-delegate/src/lib.rs`

**Step 1: Add the dependency.** In `Cargo.toml`, under `[dependencies]`:

```toml
amplifier-module-session-store = { path = "../amplifier-module-session-store" }
chrono = { workspace = true }
```

And under `[dev-dependencies]`:

```toml
tempfile = "3"
```

**Step 2: Write the failing test.** Append to the existing `mod tests` in `crates/amplifier-module-tool-delegate/src/lib.rs`:

```rust
// --- Test 5: delegate_returns_error_when_session_id_unknown ---

#[tokio::test]
async fn delegate_returns_error_when_session_id_unknown() {
    use amplifier_module_session_store::FileSessionStore;
    use std::sync::Arc as StdArc;

    let tmp = tempfile::TempDir::new().unwrap();
    let store = StdArc::new(FileSessionStore::new_with_root(tmp.path().to_path_buf()));
    let runner: Arc<dyn SubagentRunner> = Arc::new(NopRunner);
    let registry = Arc::new(AgentRegistry::new());
    let tool = DelegateTool::new_with_store(
        runner,
        registry,
        DelegateConfig::default(),
        store,
    );

    let input = serde_json::json!({
        "agent": "explorer",
        "instruction": "do something",
        "session_id": "does-not-exist",
    });
    let res = tool.execute(input).await;
    let err = res.expect_err("missing session must error");
    assert!(
        err.to_string().contains("session not found")
            || err.to_string().contains("does-not-exist"),
        "expected 'session not found' error, got: {err}"
    );
}

// --- Test 6: delegate_calls_resume_when_session_id_present ---

#[tokio::test]
async fn delegate_calls_resume_when_session_id_present() {
    use amplifier_module_session_store::{FileSessionStore, SessionMetadata, SessionStore};
    use amplifier_module_tool_task::SpawnResult;
    use std::sync::{Arc as StdArc, Mutex};

    /// Records which method was called on the runner.
    struct ResumeRecorder {
        called: StdArc<Mutex<Option<String>>>,
    }
    #[async_trait::async_trait]
    impl SubagentRunner for ResumeRecorder {
        async fn run(&self, _req: SpawnRequest) -> anyhow::Result<String> {
            *self.called.lock().unwrap() = Some("run".into());
            Ok("from run".into())
        }
        async fn resume(
            &self,
            session_id: &str,
            _instruction: String,
        ) -> anyhow::Result<SpawnResult> {
            *self.called.lock().unwrap() = Some(format!("resume:{session_id}"));
            Ok(SpawnResult {
                response: "from resume".into(),
                session_id: session_id.into(),
            })
        }
    }

    let tmp = tempfile::TempDir::new().unwrap();
    let store = StdArc::new(FileSessionStore::new_with_root(tmp.path().to_path_buf()));
    // Pre-create a real session so `exists()` returns true.
    store
        .begin(
            "real-sid",
            SessionMetadata {
                session_id: "real-sid".into(),
                agent_name: "explorer".into(),
                parent_id: None,
                created: chrono::Utc::now().to_rfc3339(),
                status: "active".into(),
            },
        )
        .await
        .unwrap();

    let called = StdArc::new(Mutex::new(None));
    let runner: Arc<dyn SubagentRunner> = Arc::new(ResumeRecorder {
        called: called.clone(),
    });
    let registry = Arc::new(AgentRegistry::new());
    let tool = DelegateTool::new_with_store(
        runner,
        registry,
        DelegateConfig::default(),
        store,
    );

    let input = serde_json::json!({
        "agent": "explorer",
        "instruction": "continue",
        "session_id": "real-sid",
    });
    let res = tool.execute(input).await.unwrap();
    assert!(res.success);
    assert_eq!(res.output, Some(serde_json::Value::String("from resume".into())));
    assert_eq!(*called.lock().unwrap(), Some("resume:real-sid".into()));
}
```

Update the existing `NopRunner` so the trait change still compiles (no change needed — default `resume` is provided).

**Step 3: Run to verify failure.**

Run: `cargo test -p amplifier-module-tool-delegate delegate_returns_error_when_session_id_unknown delegate_calls_resume_when_session_id_present`
Expected: FAIL — `new_with_store` not found, `session_id` not parsed.

**Step 4: Implement.** Edit `crates/amplifier-module-tool-delegate/src/lib.rs`:

a) Add the import:
```rust
use amplifier_module_session_store::SessionStore;
```

b) Extend `DelegateTool`:
```rust
pub struct DelegateTool {
    #[allow(dead_code)]
    runner: Arc<dyn SubagentRunner>,
    #[allow(dead_code)]
    registry: Arc<AgentRegistry>,
    #[allow(dead_code)]
    config: DelegateConfig,
    /// Optional session store; required for `session_id` resume path.
    store: Option<Arc<dyn SessionStore>>,
}
```

Update `DelegateTool::new` to set `store: None`. Add a second constructor:

```rust
pub fn new_with_store(
    runner: Arc<dyn SubagentRunner>,
    registry: Arc<AgentRegistry>,
    config: DelegateConfig,
    store: Arc<dyn SessionStore>,
) -> Self {
    Self {
        runner,
        registry,
        config,
        store: Some(store),
    }
}
```

c) Inside `execute()`, after parsing `instruction`, parse `session_id`:

```rust
let session_id = input.get("session_id").and_then(|v| v.as_str()).map(String::from);
```

d) Replace the existing `let result = runner.run(req).await…` block with branching logic:

```rust
let response_text: String = if let Some(sid) = session_id {
    // Resume path — needs an attached store.
    let store = self.store.as_ref().ok_or_else(|| ToolError::Other {
        message: "session_id provided but no SessionStore configured".into(),
    })?;
    if !store.exists(&sid).await {
        return Err(ToolError::Other {
            message: format!("session not found: {sid}"),
        });
    }
    runner
        .resume(&sid, instruction)
        .await
        .map_err(|e| ToolError::ExecutionFailed {
            message: e.to_string(),
            stdout: None,
            stderr: None,
            exit_code: None,
        })?
        .response
} else {
    runner
        .run(req)
        .await
        .map_err(|e| ToolError::ExecutionFailed {
            message: e.to_string(),
            stdout: None,
            stderr: None,
            exit_code: None,
        })?
};

Ok(ToolResult {
    success: true,
    output: Some(serde_json::Value::String(response_text)),
    error: None,
})
```

(Note: capture `self.store` for the async block: `let store = self.store.clone();` near `let runner = …`.)

**Step 5: Run.**

Run: `cargo test -p amplifier-module-tool-delegate`
Expected: all 6 tests PASS (4 existing + 2 new).

**Step 6: Confirm the workspace still builds.**

Run: `cargo check --workspace`
Expected: clean.

**Step 7: Commit.**

`git add crates/amplifier-module-tool-delegate && git commit -m "feat(delegate): wire session_id → SubagentRunner::resume"`

---

## Group 5 — Real integration tests

---

### Task 12: Crash-resume integration test (the key verification)

**Files:**
- Modify: `crates/amplifier-module-session-store/tests/integration_test.rs`

**Step 1: Replace the placeholder with a real end-to-end test.**

```rust
//! Phase 7 integration tests — exercise the full persistence + resume flow.

use std::sync::Arc;

use amplifier_module_context_simple::SimpleContext;
use amplifier_module_orchestrator_loop_streaming::{HookRegistry, LoopConfig, LoopOrchestrator};
use amplifier_module_session_store::{
    FileSessionStore, SessionEvent, SessionMetadata, SessionStore,
};
use amplifier_module_tool_task::SubagentRunner;

mod common {
    //! A scripted MockProvider whose responses are queued up front.
    use amplifier_core::errors::ProviderError;
    use amplifier_core::messages::{ChatRequest, ChatResponse, ContentBlock, ToolCall};
    use amplifier_core::models::{ModelInfo, ProviderInfo};
    use amplifier_core::traits::Provider;
    use std::collections::HashMap;
    use std::future::Future;
    use std::pin::Pin;
    use std::sync::Mutex;

    pub struct ScriptedProvider {
        pub replies: Mutex<Vec<String>>,
    }
    impl ScriptedProvider {
        pub fn new(replies: Vec<&'static str>) -> Self {
            Self {
                replies: Mutex::new(replies.into_iter().map(String::from).collect()),
            }
        }
    }
    impl Provider for ScriptedProvider {
        fn name(&self) -> &str { "scripted" }
        fn get_info(&self) -> ProviderInfo {
            ProviderInfo {
                id: "scripted".into(),
                display_name: "scripted".into(),
                credential_env_vars: vec![],
                capabilities: vec![],
                defaults: HashMap::new(),
                config_fields: vec![],
            }
        }
        fn list_models(&self) -> Pin<Box<dyn Future<Output = Result<Vec<ModelInfo>, ProviderError>> + Send + '_>> {
            Box::pin(async move { Ok(vec![]) })
        }
        fn complete(
            &self,
            _r: ChatRequest,
        ) -> Pin<Box<dyn Future<Output = Result<ChatResponse, ProviderError>> + Send + '_>> {
            let next = self.replies.lock().unwrap().remove(0);
            Box::pin(async move {
                Ok(ChatResponse {
                    content: vec![ContentBlock::Text {
                        text: next,
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
            })
        }
        fn parse_tool_calls(&self, _r: &ChatResponse) -> Vec<ToolCall> { vec![] }
    }
}

#[tokio::test]
async fn crash_resume_end_to_end() {
    use amplifier_core::traits::Provider;

    let tmp = tempfile::TempDir::new().unwrap();
    let store: Arc<dyn SessionStore> =
        Arc::new(FileSessionStore::new_with_root(tmp.path().to_path_buf()));
    let session_id = "explorer-1".to_string();

    // -------- Phase A: first orchestrator instance --------
    {
        let orch = LoopOrchestrator::new(LoopConfig::default());
        orch.attach_store(
            store.clone(),
            session_id.clone(),
            "explorer".into(),
            None,
        );
        let provider: Arc<dyn Provider> =
            Arc::new(common::ScriptedProvider::new(vec!["found: a.rs, b.rs, c.rs"]));
        orch.register_provider("anthropic".into(), provider).await;

        store
            .begin(
                &session_id,
                SessionMetadata {
                    session_id: session_id.clone(),
                    agent_name: "explorer".into(),
                    parent_id: None,
                    created: chrono::Utc::now().to_rfc3339(),
                    status: "active".into(),
                },
            )
            .await
            .unwrap();

        let mut ctx = SimpleContext::new(vec![]);
        let _ = orch
            .execute(
                "list all Rust files".into(),
                &mut ctx,
                &HookRegistry::new(),
                |_| {},
            )
            .await
            .unwrap();
        orch.finish_store("success").await.unwrap();
        // Drop `orch` — simulate process exit.
    }

    // -------- Verify on-disk state --------
    let path = tmp.path().join(&session_id).join("events.jsonl");
    assert!(path.is_file(), "events.jsonl must exist");
    let body = std::fs::read_to_string(&path).unwrap();
    for line in body.lines() {
        let _: SessionEvent = serde_json::from_str(line).expect("each line valid JSON");
    }
    assert!(body.contains("list all Rust files"));
    assert!(body.contains("found: a.rs, b.rs, c.rs"));

    let idx = std::fs::read_to_string(tmp.path().join("index.jsonl")).unwrap();
    assert_eq!(idx.lines().count(), 1);
    assert!(idx.contains("\"status\":\"success\""));

    // -------- Phase B: second orchestrator instance — process restart --------
    let orch2 = LoopOrchestrator::new(LoopConfig::default());
    orch2.attach_store(
        store.clone(),
        session_id.clone(),
        "explorer".into(),
        None,
    );
    let provider2: Arc<dyn amplifier_core::traits::Provider> =
        Arc::new(common::ScriptedProvider::new(vec!["3 files"]));
    orch2
        .register_provider("anthropic".into(), provider2)
        .await;

    let result = SubagentRunner::resume(&orch2, &session_id, "now count them".into())
        .await
        .unwrap();
    assert_eq!(result.session_id, session_id, "resume returns same session_id");
    assert!(result.response.contains("3 files"));

    // -------- Verify resumed transcript contains BOTH turns --------
    let final_events = store.load(&session_id).await.unwrap();
    let user_msgs: Vec<&str> = final_events
        .iter()
        .filter_map(|e| match e {
            SessionEvent::Turn { role, content, .. } if role == "user" => Some(content.as_str()),
            _ => None,
        })
        .collect();
    assert!(
        user_msgs.iter().any(|m| m.contains("list all Rust files")),
        "resumed session must include prior user turn, got: {user_msgs:?}"
    );
    assert!(
        user_msgs.iter().any(|m| m.contains("now count them")),
        "resumed session must include new user turn, got: {user_msgs:?}"
    );
}
```

**Step 2: Add dev-dependencies to `crates/amplifier-module-session-store/Cargo.toml`:**

```toml
[dev-dependencies]
tempfile = "3"
chrono = { workspace = true }
amplifier-core = { workspace = true }
amplifier-module-context-simple = { path = "../amplifier-module-context-simple" }
amplifier-module-orchestrator-loop-streaming = { path = "../amplifier-module-orchestrator-loop-streaming" }
amplifier-module-tool-task = { path = "../amplifier-module-tool-task" }
tokio = { workspace = true }
serde_json = { workspace = true }
async-trait = { workspace = true }
```

**Step 3: Run the test.**

Run: `cargo test -p amplifier-module-session-store --test integration_test crash_resume_end_to_end`
Expected: PASS, with output mentioning the resumed `3 files` response.

**Step 4: Commit.**

`git add crates/amplifier-module-session-store && git commit -m "test(session-store): crash-resume end-to-end integration"`

---

### Task 13: Concurrent sessions + malformed JSONL integration tests

**Files:**
- Modify: `crates/amplifier-module-session-store/tests/integration_test.rs`

**Step 1: Append two more failing tests to the same file:**

```rust
#[tokio::test]
async fn three_concurrent_sessions_dont_corrupt_each_other() {
    let tmp = tempfile::TempDir::new().unwrap();
    let store: Arc<dyn SessionStore> =
        Arc::new(FileSessionStore::new_with_root(tmp.path().to_path_buf()));

    // Begin 3 sessions concurrently and append 50 turns each, all in parallel.
    let mut handles = vec![];
    for i in 0..3 {
        let store = store.clone();
        let sid = format!("sess-{i}");
        handles.push(tokio::spawn(async move {
            store
                .begin(
                    &sid,
                    SessionMetadata {
                        session_id: sid.clone(),
                        agent_name: format!("agent-{i}"),
                        parent_id: None,
                        created: chrono::Utc::now().to_rfc3339(),
                        status: "active".into(),
                    },
                )
                .await
                .unwrap();
            for n in 0..50 {
                store
                    .append(
                        &sid,
                        SessionEvent::Turn {
                            role: "user".into(),
                            content: format!("msg {i}-{n}"),
                            timestamp: chrono::Utc::now().to_rfc3339(),
                        },
                    )
                    .await
                    .unwrap();
            }
            store.finish(&sid, "success", 50).await.unwrap();
        }));
    }
    for h in handles {
        h.await.unwrap();
    }

    // Verify each session's transcript is intact (51 events: start + 50 turns + end).
    for i in 0..3 {
        let sid = format!("sess-{i}");
        let events = store.load(&sid).await.unwrap();
        assert_eq!(events.len(), 52, "session {sid} should have 52 events");
        // First is session_start, last is session_end.
        assert!(matches!(events.first().unwrap(), SessionEvent::SessionStart { .. }));
        assert!(matches!(events.last().unwrap(), SessionEvent::SessionEnd { .. }));
        // No cross-contamination — every Turn content references this session's index.
        for evt in &events[1..51] {
            if let SessionEvent::Turn { content, .. } = evt {
                assert!(
                    content.starts_with(&format!("msg {i}-")),
                    "session {sid} contaminated with: {content}"
                );
            }
        }
    }

    // Index has all 3 sessions, each with status=success.
    let idx = store.list().await.unwrap();
    assert_eq!(idx.len(), 3);
    for entry in &idx {
        assert_eq!(entry.status, "success");
    }
}

#[tokio::test]
async fn malformed_events_jsonl_returns_clear_error_no_panic() {
    let tmp = tempfile::TempDir::new().unwrap();
    let store: Arc<dyn SessionStore> =
        Arc::new(FileSessionStore::new_with_root(tmp.path().to_path_buf()));
    let sid = "broken";
    std::fs::create_dir_all(tmp.path().join(sid)).unwrap();
    std::fs::write(
        tmp.path().join(sid).join("events.jsonl"),
        "{\"type\":\"session_start\",\"session_id\":\"broken\",\"agent_name\":\"a\",\"timestamp\":\"t\"}\n\
         not json at all\n\
         {\"type\":\"turn\",\"role\":\"user\",\"content\":\"x\",\"timestamp\":\"t\"}\n",
    )
    .unwrap();

    let res = store.load(sid).await;
    let err = res.expect_err("malformed JSONL must produce an error");
    let msg = err.to_string();
    assert!(
        msg.contains("malformed") && msg.contains("line 2"),
        "expected message to identify line 2, got: {msg}"
    );
}
```

**Step 2: Run.**

Run: `cargo test -p amplifier-module-session-store --test integration_test`
Expected: 3 tests PASS (crash-resume + concurrent + malformed).

**Step 3: Commit.**

`git add crates/amplifier-module-session-store/tests && git commit -m "test(session-store): concurrent sessions + malformed JSONL"`

---

## Group 6 — Sandbox wiring + final verification

---

### Task 14: Mount `FileSessionStore` in the sandbox binary

**Files:**
- Modify: `sandbox/amplifier-android-sandbox/Cargo.toml`
- Modify: `sandbox/amplifier-android-sandbox/src/main.rs`

**Step 1: Add the dep.** In `Cargo.toml` under `[dependencies]`:

```toml
amplifier-module-session-store = { path = "../../crates/amplifier-module-session-store" }
```

**Step 2: Read the existing `main.rs`** to find where `DelegateTool::new(...)` is called and where the `LoopOrchestrator` is constructed.

Run: `grep -n "DelegateTool::new\|LoopOrchestrator::new\|attach_store" sandbox/amplifier-android-sandbox/src/main.rs`

**Step 3: Replace the `DelegateTool::new(...)` call with `DelegateTool::new_with_store(...)` and create a single shared store.**

Add near the top of `main()` (or wherever the orchestrator is constructed):

```rust
use amplifier_module_session_store::FileSessionStore;
use std::sync::Arc;

let session_store: Arc<dyn amplifier_module_session_store::SessionStore> =
    Arc::new(FileSessionStore::new()?);
```

Then change the call site from:
```rust
let delegate = DelegateTool::new(runner.clone(), registry.clone(), DelegateConfig::default());
```
to:
```rust
let delegate = DelegateTool::new_with_store(
    runner.clone(),
    registry.clone(),
    DelegateConfig::default(),
    session_store.clone(),
);
```

**Step 4: Build the sandbox to confirm it compiles.**

Run: `cargo build -p amplifier-android-sandbox`
Expected: clean build.

**Step 5: Run the existing sandbox smoke tests.**

Run: `cargo test -p amplifier-android-sandbox`
Expected: all PASS (no regressions).

**Step 6: Commit.**

`git add sandbox/amplifier-android-sandbox && git commit -m "feat(sandbox): wire FileSessionStore into DelegateTool"`

---

### Task 15: Final workspace verification

**Files:** none (verification only).

**Step 1: Full build.**

Run: `cargo build --workspace`
Expected: `Finished` with no errors or warnings about Phase 7 crates.

**Step 2: Full test suite.**

Run: `cargo test --workspace`
Expected: every test passes, including:
- `crash_resume_end_to_end`
- `three_concurrent_sessions_dont_corrupt_each_other`
- `malformed_events_jsonl_returns_clear_error_no_panic`
- `resume_replays_prior_turns_into_context`
- `execute_persists_events_when_store_attached`
- `delegate_returns_error_when_session_id_unknown`
- `delegate_calls_resume_when_session_id_present`
- All Phase 1–6 tests still green.

**Step 3: Lints.**

Run: `cargo clippy --workspace --all-targets -- -D warnings`
Expected: no warnings.

**Step 4: Format check.**

Run: `cargo fmt --check`
Expected: no diff. If anything is reported, run `cargo fmt --all` and re-verify.

**Step 5: Spot-check the on-disk artefacts (manual sanity check).**

Run:
```
cargo test -p amplifier-module-session-store --test integration_test crash_resume_end_to_end -- --nocapture
```
Expected: log output mentions the temp dir path; the test passes.

**Step 6: Commit final cleanup if any.**

`git add -A && git commit -m "chore(phase-7): cargo fmt + clippy clean" --allow-empty`

---

## Acceptance criteria summary

Phase 7 is complete when **all** of the following are true:

1. New crate `amplifier-module-session-store` exists, with `SessionStore` trait + `FileSessionStore` impl + `SessionEvent`/`SessionMetadata` types.
2. Storage layout matches the spec: `~/.amplifier/sessions/{id}/events.jsonl` + `~/.amplifier/sessions/index.jsonl`.
3. `SubagentRunner` trait has a `resume()` method (with default that errors).
4. `LoopOrchestrator` persists `session_start` / `Turn` / `ToolCall` / `session_end` events when a store is attached.
5. `LoopOrchestrator::resume()` loads prior `Turn` events into a fresh `SimpleContext` and runs one more loop.
6. `DelegateTool` parses `session_id`, errors clearly when the session is unknown, and dispatches to `runner.resume()` when present.
7. Crash-resume integration test demonstrates: persist → drop orchestrator → re-create orchestrator → resume → see prior context in transcript.
8. Concurrent session test (3 parallel) shows no cross-contamination and 3 distinct successful index entries.
9. Malformed JSONL test produces a line-numbered error, no panic.
10. `cargo build --workspace` clean.
11. `cargo test --workspace` all green.
12. `cargo clippy --workspace --all-targets -- -D warnings` clean.
13. `cargo fmt --check` clean.
