# Phase 3: Portable Tools & Android Sandbox — Implementation Plan

> **Execution:** Use the subagent-driven-development workflow to implement this plan.
> **This plan has 16 tasks and is split into two execution sessions:**
> - **Phase 3a (Tasks 1–10):** Run first — builds the three tool crates
> - **Phase 3b (Tasks 11–16):** Run after 3a compiles cleanly — builds the sandbox binary

**Goal:** Deliver three portable tool crates (`filesystem`, `bash`, `search`) and the `amplifier-android-sandbox` CLI binary that wires all workspace crates into a single runnable agent.

**Architecture:** Each tool crate exposes one or more structs implementing the `amplifier-core` `Tool` trait, configured via a `*Config` struct and constructed with `Arc<Config>` for cheap cloning. The sandbox binary is a `[[bin]]` target (not published to crates.io) that assembles all 7 tool crates + 4 provider crates + orchestrator into a REPL or single-turn CLI, with optional Linux landlock + seccomp restrictions applied at startup via conditional compilation.

**Tech Stack:** Rust 2021 edition, Tokio async runtime, `amplifier-core` traits via git dep, `glob 0.3`, `regex 1`, `walkdir 2`, `tempfile 3` (tests), `clap 4` (binary), `landlock 0.4` + `libseccomp 0.3` (Linux only)

**Workspace root:** `/Users/ken/workspace/amplifier-rust/`

---

## Codebase orientation

- **Workspace Cargo.toml:** `/Users/ken/workspace/amplifier-rust/Cargo.toml` — add `members` entries here
- **Existing crates:** `crates/amplifier-module-{context-simple,orchestrator-loop-streaming,provider-*,tool-{task,skills,web,todo}}`
- **New crate dirs:** `crates/amplifier-module-tool-filesystem/`, `crates/amplifier-module-tool-bash/`, `crates/amplifier-module-tool-search/`
- **New binary dir:** `sandbox/amplifier-android-sandbox/`
- **Core trait imports:** `amplifier_core::traits::Tool`, `amplifier_core::messages::ToolSpec`, `amplifier_core::models::ToolResult`, `amplifier_core::errors::ToolError`
- **Test command pattern:** `cargo test -p <crate-name>` from workspace root
- **Build command:** `cargo build -p amplifier-android-sandbox` from workspace root

---

## Phase 3a — Tool Crates

---

### Task 1: Register new workspace members in root Cargo.toml

**Files:**
- Modify: `/Users/ken/workspace/amplifier-rust/Cargo.toml`

**Step 1: Add the four new members to the workspace `members` array**

Open `/Users/ken/workspace/amplifier-rust/Cargo.toml`. Find the `[workspace]` section with the `members` array. Add four new entries to the list:

```toml
members = [
    # ... all existing members stay unchanged ...
    "crates/amplifier-module-tool-filesystem",
    "crates/amplifier-module-tool-bash",
    "crates/amplifier-module-tool-search",
    "sandbox/amplifier-android-sandbox",
]
```

Also ensure `[workspace.dependencies]` contains the shared deps that the new crates will use. Add any that are missing:

```toml
[workspace.dependencies]
amplifier-core = { git = "https://github.com/microsoft/amplifier-core" }
tokio = { version = "1", features = ["full"] }
serde = { version = "1", features = ["derive"] }
serde_json = "1"
anyhow = "1"
async-trait = "0.1"
```

**Step 2: Create stub `lib.rs` files so the workspace compiles before we write any logic**

Create `/Users/ken/workspace/amplifier-rust/crates/amplifier-module-tool-filesystem/src/lib.rs`:
```rust
// stub — filled in Task 2
```

Create `/Users/ken/workspace/amplifier-rust/crates/amplifier-module-tool-bash/src/lib.rs`:
```rust
// stub — filled in Task 8
```

Create `/Users/ken/workspace/amplifier-rust/crates/amplifier-module-tool-search/src/lib.rs`:
```rust
// stub — filled in Task 10
```

Create `/Users/ken/workspace/amplifier-rust/sandbox/amplifier-android-sandbox/src/main.rs`:
```rust
fn main() {}
```

Each needs a minimal `Cargo.toml` (shown in the relevant task below). For now, create bare-minimum versions:

`/Users/ken/workspace/amplifier-rust/crates/amplifier-module-tool-filesystem/Cargo.toml`:
```toml
[package]
name = "amplifier-module-tool-filesystem"
version = "0.1.0"
edition = "2021"
```

`/Users/ken/workspace/amplifier-rust/crates/amplifier-module-tool-bash/Cargo.toml`:
```toml
[package]
name = "amplifier-module-tool-bash"
version = "0.1.0"
edition = "2021"
```

`/Users/ken/workspace/amplifier-rust/crates/amplifier-module-tool-search/Cargo.toml`:
```toml
[package]
name = "amplifier-module-tool-search"
version = "0.1.0"
edition = "2021"
```

`/Users/ken/workspace/amplifier-rust/sandbox/amplifier-android-sandbox/Cargo.toml`:
```toml
[package]
name = "amplifier-android-sandbox"
version = "0.1.0"
edition = "2021"
publish = false

[[bin]]
name = "amplifier-android-sandbox"
path = "src/main.rs"
```

**Step 3: Verify the workspace compiles with all stubs**

```bash
cd /Users/ken/workspace/amplifier-rust && cargo check --workspace
```

Expected: no errors. All four new members compile as empty stubs.

**Step 4: Commit**

```bash
git add -A && git commit -m "chore: register tool-filesystem, tool-bash, tool-search, android-sandbox in workspace"
```

---

### Task 2: `amplifier-module-tool-filesystem` — crate scaffold

**Files:**
- Replace: `/Users/ken/workspace/amplifier-rust/crates/amplifier-module-tool-filesystem/Cargo.toml`
- Create: `/Users/ken/workspace/amplifier-rust/crates/amplifier-module-tool-filesystem/src/lib.rs`
- Create: `/Users/ken/workspace/amplifier-rust/crates/amplifier-module-tool-filesystem/src/read.rs`
- Create: `/Users/ken/workspace/amplifier-rust/crates/amplifier-module-tool-filesystem/src/write.rs`
- Create: `/Users/ken/workspace/amplifier-rust/crates/amplifier-module-tool-filesystem/src/glob_tool.rs`
- Create: `/Users/ken/workspace/amplifier-rust/crates/amplifier-module-tool-filesystem/src/grep_tool.rs`
- Test: `/Users/ken/workspace/amplifier-rust/crates/amplifier-module-tool-filesystem/tests/integration_test.rs`

**Step 1: Replace `Cargo.toml` with full dependencies**

```toml
[package]
name = "amplifier-module-tool-filesystem"
version = "0.1.0"
edition = "2021"
description = "Vault-root-scoped filesystem tools for the Amplifier agent framework"
license = "MIT"

[dependencies]
amplifier-core = { workspace = true }
tokio = { workspace = true }
serde_json = { workspace = true }
glob = "0.3"
regex = "1"
walkdir = "2"

[dev-dependencies]
tokio = { version = "1", features = ["rt-multi-thread", "macros"] }
tempfile = "3"
```

**Step 2: Write `src/lib.rs`**

```rust
//! `amplifier-module-tool-filesystem` — vault-root-scoped file operations.
//!
//! Exposes five tools that implement `amplifier_core::traits::Tool`:
//! - [`ReadFileTool`]  — read file contents with optional offset/limit
//! - [`WriteFileTool`] — write (or overwrite) a file, creating parent dirs
//! - [`EditFileTool`]  — exact string replacement within a file
//! - [`GlobTool`]      — find files matching a glob pattern
//! - [`GrepTool`]      — search file contents with a regex

use std::path::PathBuf;
use std::sync::Arc;

pub mod glob_tool;
pub mod grep_tool;
pub mod read;
pub mod write;

pub use glob_tool::GlobTool;
pub use grep_tool::GrepTool;
pub use read::ReadFileTool;
pub use write::{EditFileTool, WriteFileTool};

/// Shared configuration for all filesystem tools.
///
/// All paths are resolved relative to `vault_root`. Write operations are
/// restricted to `allowed_write_paths` (default: just `vault_root`).
/// Read operations are restricted to `allowed_read_paths` when `Some`;
/// `None` means all reads within the process's filesystem view are allowed.
#[derive(Debug, Clone)]
pub struct FilesystemConfig {
    pub vault_root: PathBuf,
    /// Paths under which writes are permitted. Defaults to `[vault_root]`.
    pub allowed_write_paths: Vec<PathBuf>,
    /// Optional read allowlist. `None` = allow all reads.
    pub allowed_read_paths: Option<Vec<PathBuf>>,
}

impl FilesystemConfig {
    /// Create a config locked to `vault_root` for both reads and writes.
    pub fn new(vault_root: PathBuf) -> Arc<Self> {
        let allowed_write_paths = vec![vault_root.clone()];
        Arc::new(Self {
            vault_root,
            allowed_write_paths,
            allowed_read_paths: None,
        })
    }
}
```

**Step 3: Write the failing scaffold test in `tests/integration_test.rs`**

```rust
use amplifier_module_tool_filesystem::FilesystemConfig;
use tempfile::TempDir;

/// Verify the config struct is constructible — catches import/compile issues.
#[test]
fn config_constructs() {
    let dir = TempDir::new().unwrap();
    let cfg = FilesystemConfig::new(dir.path().to_path_buf());
    assert_eq!(cfg.vault_root, dir.path());
    assert_eq!(cfg.allowed_write_paths.len(), 1);
    assert!(cfg.allowed_read_paths.is_none());
}
```

**Step 4: Run the test to verify it compiles and passes**

```bash
cd /Users/ken/workspace/amplifier-rust && cargo test -p amplifier-module-tool-filesystem config_constructs
```

Expected:
```
test config_constructs ... ok
```

**Step 5: Commit**

```bash
git add -A && git commit -m "feat: amplifier-module-tool-filesystem crate scaffold"
```

---

### Task 3: `read_file` tool — TDD cycle

**Files:**
- Create: `crates/amplifier-module-tool-filesystem/src/read.rs`
- Modify: `tests/integration_test.rs`

**Step 1: Write the failing test — add to `tests/integration_test.rs`**

```rust
use amplifier_core::traits::Tool;
use amplifier_module_tool_filesystem::{FilesystemConfig, ReadFileTool};
use serde_json::json;
use std::fs;
use tempfile::TempDir;

#[tokio::test]
async fn read_file_returns_numbered_lines() {
    let dir = TempDir::new().unwrap();
    fs::write(dir.path().join("hello.txt"), "alpha\nbeta\ngamma\n").unwrap();

    let cfg = FilesystemConfig::new(dir.path().to_path_buf());
    let tool = ReadFileTool::new(cfg);

    let result = tool.execute(json!({ "path": "hello.txt" })).await.unwrap();
    assert!(result.output.contains("   1\talpha"));
    assert!(result.output.contains("   2\tbeta"));
    assert!(result.output.contains("   3\tgamma"));
}

#[tokio::test]
async fn read_file_respects_offset_and_limit() {
    let dir = TempDir::new().unwrap();
    fs::write(dir.path().join("lines.txt"), "one\ntwo\nthree\nfour\nfive\n").unwrap();

    let cfg = FilesystemConfig::new(dir.path().to_path_buf());
    let tool = ReadFileTool::new(cfg);

    // offset=1 (0-indexed: skip first line), limit=2 → lines 2 and 3
    let result = tool
        .execute(json!({ "path": "lines.txt", "offset": 1, "limit": 2 }))
        .await
        .unwrap();
    assert!(result.output.contains("   2\ttwo"));
    assert!(result.output.contains("   3\tthree"));
    assert!(!result.output.contains("one"));
    assert!(!result.output.contains("four"));
}

#[tokio::test]
async fn read_file_error_on_missing_file() {
    let dir = TempDir::new().unwrap();
    let cfg = FilesystemConfig::new(dir.path().to_path_buf());
    let tool = ReadFileTool::new(cfg);

    let result = tool.execute(json!({ "path": "does_not_exist.txt" })).await;
    assert!(result.is_err());
}
```

**Step 2: Run the tests to verify they fail**

```bash
cargo test -p amplifier-module-tool-filesystem read_file
```

Expected: compile error — `ReadFileTool` is not yet defined in `read.rs`.

**Step 3: Write `src/read.rs`**

```rust
//! `read_file` tool — reads a vault file with optional line range.

use std::future::Future;
use std::pin::Pin;
use std::sync::Arc;

use amplifier_core::errors::ToolError;
use amplifier_core::messages::ToolSpec;
use amplifier_core::models::ToolResult;
use amplifier_core::traits::Tool;
use serde_json::{json, Value};

use crate::FilesystemConfig;

pub struct ReadFileTool {
    config: Arc<FilesystemConfig>,
}

impl ReadFileTool {
    pub fn new(config: Arc<FilesystemConfig>) -> Self {
        Self { config }
    }
}

impl Tool for ReadFileTool {
    fn spec(&self) -> ToolSpec {
        ToolSpec {
            name: "read_file".into(),
            description: "Read file contents with line numbers. Optionally specify offset \
                          (0-based line index to start from) and limit (max lines to return)."
                .into(),
            parameters: json!({
                "type": "object",
                "properties": {
                    "path": {
                        "type": "string",
                        "description": "File path relative to vault root"
                    },
                    "offset": {
                        "type": "integer",
                        "description": "0-based line index to start reading from"
                    },
                    "limit": {
                        "type": "integer",
                        "description": "Maximum number of lines to return"
                    }
                },
                "required": ["path"]
            }),
        }
    }

    fn execute(
        &self,
        input: Value,
    ) -> Pin<Box<dyn Future<Output = Result<ToolResult, ToolError>> + Send + '_>> {
        let config = Arc::clone(&self.config);
        Box::pin(async move {
            let path = input["path"]
                .as_str()
                .ok_or_else(|| ToolError::InvalidInput("path is required".into()))?;
            let offset = input["offset"].as_u64().map(|n| n as usize);
            let limit = input["limit"].as_u64().map(|n| n as usize);

            read_file_impl(&config, path, offset, limit)
                .await
                .map(|output| ToolResult { output })
                .map_err(ToolError::ExecutionFailed)
        })
    }
}

async fn read_file_impl(
    config: &FilesystemConfig,
    path: &str,
    offset: Option<usize>,
    limit: Option<usize>,
) -> Result<String, String> {
    let abs_path = config.vault_root.join(path);

    // Check read allowlist
    if let Some(allowed) = &config.allowed_read_paths {
        if !allowed.iter().any(|p| abs_path.starts_with(p)) {
            return Err(format!("Read access denied for path: {path}"));
        }
    }

    let content = tokio::fs::read_to_string(&abs_path)
        .await
        .map_err(|e| format!("Failed to read '{path}': {e}"))?;

    let lines: Vec<&str> = content.lines().collect();
    let start = offset.unwrap_or(0);
    let end = limit
        .map(|l| (start + l).min(lines.len()))
        .unwrap_or(lines.len());

    let output: String = lines[start..end]
        .iter()
        .enumerate()
        .map(|(i, line)| format!("{:>4}\t{line}\n", start + i + 1))
        .collect();

    Ok(output)
}
```

**Step 4: Run the tests to verify they pass**

```bash
cargo test -p amplifier-module-tool-filesystem read_file
```

Expected:
```
test read_file_returns_numbered_lines ... ok
test read_file_respects_offset_and_limit ... ok
test read_file_error_on_missing_file ... ok
```

**Step 5: Commit**

```bash
git add -A && git commit -m "feat: read_file tool with line-numbered output and offset/limit"
```

---

### Task 4: `write_file` and `edit_file` tools — TDD cycle

**Files:**
- Create: `crates/amplifier-module-tool-filesystem/src/write.rs`
- Modify: `tests/integration_test.rs`

**Step 1: Write the failing tests — add to `tests/integration_test.rs`**

```rust
use amplifier_module_tool_filesystem::{EditFileTool, WriteFileTool};

#[tokio::test]
async fn write_file_creates_file_and_parent_dirs() {
    let dir = TempDir::new().unwrap();
    let cfg = FilesystemConfig::new(dir.path().to_path_buf());
    let tool = WriteFileTool::new(cfg);

    let result = tool
        .execute(json!({
            "path": "subdir/nested/new.txt",
            "content": "hello world"
        }))
        .await
        .unwrap();

    assert!(result.output.contains("11")); // "hello world" is 11 bytes
    let written = fs::read_to_string(dir.path().join("subdir/nested/new.txt")).unwrap();
    assert_eq!(written, "hello world");
}

#[tokio::test]
async fn write_file_denied_outside_vault() {
    let dir = TempDir::new().unwrap();
    let cfg = FilesystemConfig::new(dir.path().to_path_buf());
    let tool = WriteFileTool::new(cfg);

    // Attempt path traversal outside vault
    let result = tool
        .execute(json!({ "path": "../outside.txt", "content": "evil" }))
        .await;
    assert!(result.is_err());
}

#[tokio::test]
async fn edit_file_replaces_single_occurrence() {
    let dir = TempDir::new().unwrap();
    fs::write(dir.path().join("code.rs"), "fn foo() {}\nfn bar() {}\n").unwrap();

    let cfg = FilesystemConfig::new(dir.path().to_path_buf());
    let tool = EditFileTool::new(cfg);

    let result = tool
        .execute(json!({
            "path": "code.rs",
            "old_string": "fn foo()",
            "new_string": "fn qux()"
        }))
        .await
        .unwrap();

    assert!(result.output.contains("1")); // 1 replacement
    let content = fs::read_to_string(dir.path().join("code.rs")).unwrap();
    assert!(content.contains("fn qux()"));
    assert!(content.contains("fn bar()"));
    assert!(!content.contains("fn foo()"));
}

#[tokio::test]
async fn edit_file_replace_all_replaces_all_occurrences() {
    let dir = TempDir::new().unwrap();
    fs::write(dir.path().join("doc.md"), "foo foo foo\n").unwrap();

    let cfg = FilesystemConfig::new(dir.path().to_path_buf());
    let tool = EditFileTool::new(cfg);

    tool.execute(json!({
        "path": "doc.md",
        "old_string": "foo",
        "new_string": "bar",
        "replace_all": true
    }))
    .await
    .unwrap();

    let content = fs::read_to_string(dir.path().join("doc.md")).unwrap();
    assert_eq!(content, "bar bar bar\n");
}

#[tokio::test]
async fn edit_file_errors_when_old_string_not_found() {
    let dir = TempDir::new().unwrap();
    fs::write(dir.path().join("file.txt"), "actual content\n").unwrap();

    let cfg = FilesystemConfig::new(dir.path().to_path_buf());
    let tool = EditFileTool::new(cfg);

    let result = tool
        .execute(json!({
            "path": "file.txt",
            "old_string": "does not exist",
            "new_string": "replacement"
        }))
        .await;
    assert!(result.is_err());
    let err_msg = format!("{}", result.unwrap_err());
    assert!(err_msg.contains("not found") || err_msg.contains("old_string"));
}
```

**Step 2: Run to verify compile failure**

```bash
cargo test -p amplifier-module-tool-filesystem write_file edit_file
```

Expected: compile error — `WriteFileTool` and `EditFileTool` not found.

**Step 3: Write `src/write.rs`**

```rust
//! `write_file` and `edit_file` tools — vault-scoped write operations.

use std::future::Future;
use std::pin::Pin;
use std::sync::Arc;

use amplifier_core::errors::ToolError;
use amplifier_core::messages::ToolSpec;
use amplifier_core::models::ToolResult;
use amplifier_core::traits::Tool;
use serde_json::{json, Value};

use crate::FilesystemConfig;

// ─────────────────────────── WriteFileTool ────────────────────────────────

pub struct WriteFileTool {
    config: Arc<FilesystemConfig>,
}

impl WriteFileTool {
    pub fn new(config: Arc<FilesystemConfig>) -> Self {
        Self { config }
    }
}

impl Tool for WriteFileTool {
    fn spec(&self) -> ToolSpec {
        ToolSpec {
            name: "write_file".into(),
            description: "Write content to a file. Creates parent directories if needed. \
                          Restricted to vault root."
                .into(),
            parameters: json!({
                "type": "object",
                "properties": {
                    "path": { "type": "string", "description": "Path relative to vault root" },
                    "content": { "type": "string", "description": "File content to write" }
                },
                "required": ["path", "content"]
            }),
        }
    }

    fn execute(
        &self,
        input: Value,
    ) -> Pin<Box<dyn Future<Output = Result<ToolResult, ToolError>> + Send + '_>> {
        let config = Arc::clone(&self.config);
        Box::pin(async move {
            let path = input["path"]
                .as_str()
                .ok_or_else(|| ToolError::InvalidInput("path is required".into()))?;
            let content = input["content"]
                .as_str()
                .ok_or_else(|| ToolError::InvalidInput("content is required".into()))?;

            // Resolve and boundary-check the path
            let abs_path = config.vault_root.join(path);
            // Canonicalize the parent to catch `..` traversal attempts
            let canonical_root = config
                .vault_root
                .canonicalize()
                .map_err(|e| ToolError::ExecutionFailed(format!("vault_root invalid: {e}")))?;

            // We can't canonicalize a file that doesn't exist yet, so check the
            // nearest existing ancestor instead.
            let check_path = abs_path
                .ancestors()
                .find(|p| p.exists())
                .unwrap_or(&abs_path);
            if let Ok(canonical_check) = check_path.canonicalize() {
                if !canonical_check.starts_with(&canonical_root) {
                    return Err(ToolError::ExecutionFailed(format!(
                        "Write access denied: '{path}' is outside vault root"
                    )));
                }
            }

            // Also check against allowed_write_paths list
            if !config
                .allowed_write_paths
                .iter()
                .any(|p| abs_path.starts_with(p))
            {
                return Err(ToolError::ExecutionFailed(format!(
                    "Write access denied for path: {path}"
                )));
            }

            // Create parent directories
            if let Some(parent) = abs_path.parent() {
                tokio::fs::create_dir_all(parent)
                    .await
                    .map_err(|e| ToolError::ExecutionFailed(format!("mkdir -p failed: {e}")))?;
            }

            tokio::fs::write(&abs_path, content)
                .await
                .map_err(|e| ToolError::ExecutionFailed(format!("write failed: {e}")))?;

            Ok(ToolResult {
                output: format!("Wrote {} bytes to {path}", content.len()),
            })
        })
    }
}

// ─────────────────────────── EditFileTool ─────────────────────────────────

pub struct EditFileTool {
    config: Arc<FilesystemConfig>,
}

impl EditFileTool {
    pub fn new(config: Arc<FilesystemConfig>) -> Self {
        Self { config }
    }
}

impl Tool for EditFileTool {
    fn spec(&self) -> ToolSpec {
        ToolSpec {
            name: "edit_file".into(),
            description: "Exact string replacement within a file. \
                          Errors if old_string is not found. \
                          Set replace_all=true to replace every occurrence."
                .into(),
            parameters: json!({
                "type": "object",
                "properties": {
                    "path": { "type": "string" },
                    "old_string": { "type": "string", "description": "Exact text to find" },
                    "new_string": { "type": "string", "description": "Replacement text" },
                    "replace_all": {
                        "type": "boolean",
                        "description": "Replace all occurrences (default: false = replace first only)"
                    }
                },
                "required": ["path", "old_string", "new_string"]
            }),
        }
    }

    fn execute(
        &self,
        input: Value,
    ) -> Pin<Box<dyn Future<Output = Result<ToolResult, ToolError>> + Send + '_>> {
        let config = Arc::clone(&self.config);
        Box::pin(async move {
            let path = input["path"]
                .as_str()
                .ok_or_else(|| ToolError::InvalidInput("path is required".into()))?;
            let old_string = input["old_string"]
                .as_str()
                .ok_or_else(|| ToolError::InvalidInput("old_string is required".into()))?;
            let new_string = input["new_string"]
                .as_str()
                .ok_or_else(|| ToolError::InvalidInput("new_string is required".into()))?;
            let replace_all = input["replace_all"].as_bool().unwrap_or(false);

            // Check write permissions
            let abs_path = config.vault_root.join(path);
            if !config
                .allowed_write_paths
                .iter()
                .any(|p| abs_path.starts_with(p))
            {
                return Err(ToolError::ExecutionFailed(format!(
                    "Write access denied for path: {path}"
                )));
            }

            let content = tokio::fs::read_to_string(&abs_path)
                .await
                .map_err(|e| ToolError::ExecutionFailed(format!("read failed: {e}")))?;

            if !content.contains(old_string) {
                return Err(ToolError::ExecutionFailed(format!(
                    "old_string not found in {path}"
                )));
            }

            let count = content.matches(old_string).count();
            let new_content = if replace_all {
                content.replace(old_string, new_string)
            } else {
                content.replacen(old_string, new_string, 1)
            };

            tokio::fs::write(&abs_path, &new_content)
                .await
                .map_err(|e| ToolError::ExecutionFailed(format!("write failed: {e}")))?;

            let replaced = if replace_all { count } else { 1 };
            Ok(ToolResult {
                output: format!("Replaced {replaced} occurrence(s) in {path}"),
            })
        })
    }
}
```

**Step 4: Run the tests to verify they pass**

```bash
cargo test -p amplifier-module-tool-filesystem
```

Expected: all tests pass including `write_file_*` and `edit_file_*`.

**Step 5: Commit**

```bash
git add -A && git commit -m "feat: write_file and edit_file tools with path boundary checks"
```

---

### Task 5: `glob` tool — TDD cycle

**Files:**
- Create: `crates/amplifier-module-tool-filesystem/src/glob_tool.rs`
- Modify: `tests/integration_test.rs`

**Step 1: Write the failing tests**

Add to `tests/integration_test.rs`:

```rust
use amplifier_module_tool_filesystem::GlobTool;

#[tokio::test]
async fn glob_finds_matching_files() {
    let dir = TempDir::new().unwrap();
    fs::write(dir.path().join("main.rs"), "").unwrap();
    fs::write(dir.path().join("lib.rs"), "").unwrap();
    fs::write(dir.path().join("README.md"), "").unwrap();

    let cfg = FilesystemConfig::new(dir.path().to_path_buf());
    let tool = GlobTool::new(cfg);

    let result = tool
        .execute(json!({ "pattern": "*.rs" }))
        .await
        .unwrap();

    let matches: Vec<String> = serde_json::from_str(&result.output).unwrap();
    assert_eq!(matches.len(), 2);
    assert!(matches.iter().any(|m| m.ends_with("main.rs")));
    assert!(matches.iter().any(|m| m.ends_with("lib.rs")));
    assert!(!matches.iter().any(|m| m.ends_with("README.md")));
}

#[tokio::test]
async fn glob_returns_relative_paths() {
    let dir = TempDir::new().unwrap();
    fs::create_dir(dir.path().join("src")).unwrap();
    fs::write(dir.path().join("src/tool.rs"), "").unwrap();

    let cfg = FilesystemConfig::new(dir.path().to_path_buf());
    let tool = GlobTool::new(cfg);

    let result = tool
        .execute(json!({ "pattern": "**/*.rs" }))
        .await
        .unwrap();

    let matches: Vec<String> = serde_json::from_str(&result.output).unwrap();
    assert_eq!(matches.len(), 1);
    // Must be relative, not absolute
    assert!(!matches[0].starts_with('/'));
    assert!(matches[0].contains("tool.rs"));
}
```

**Step 2: Run to verify compile failure**

```bash
cargo test -p amplifier-module-tool-filesystem glob
```

Expected: compile error — `GlobTool` not defined.

**Step 3: Write `src/glob_tool.rs`**

```rust
//! `glob` tool — find files matching a glob pattern within the vault.

use std::future::Future;
use std::pin::Pin;
use std::sync::Arc;

use amplifier_core::errors::ToolError;
use amplifier_core::messages::ToolSpec;
use amplifier_core::models::ToolResult;
use amplifier_core::traits::Tool;
use serde_json::{json, Value};

use crate::FilesystemConfig;

pub struct GlobTool {
    config: Arc<FilesystemConfig>,
}

impl GlobTool {
    pub fn new(config: Arc<FilesystemConfig>) -> Self {
        Self { config }
    }
}

impl Tool for GlobTool {
    fn spec(&self) -> ToolSpec {
        ToolSpec {
            name: "glob".into(),
            description: "Find files matching a glob pattern within the vault. \
                          Returns a JSON array of relative paths."
                .into(),
            parameters: json!({
                "type": "object",
                "properties": {
                    "pattern": {
                        "type": "string",
                        "description": "Glob pattern, e.g. '**/*.rs' or 'src/*.toml'"
                    },
                    "path": {
                        "type": "string",
                        "description": "Optional subdirectory to search within (relative to vault root). \
                                        Default: vault root."
                    }
                },
                "required": ["pattern"]
            }),
        }
    }

    fn execute(
        &self,
        input: Value,
    ) -> Pin<Box<dyn Future<Output = Result<ToolResult, ToolError>> + Send + '_>> {
        let config = Arc::clone(&self.config);
        Box::pin(async move {
            let pattern = input["pattern"]
                .as_str()
                .ok_or_else(|| ToolError::InvalidInput("pattern is required".into()))?;

            let base = match input["path"].as_str() {
                Some(p) => config.vault_root.join(p),
                None => config.vault_root.clone(),
            };

            let full_pattern = base.join(pattern);
            let pattern_str = full_pattern.to_string_lossy().into_owned();

            // glob::glob is synchronous — run in blocking thread pool
            let vault_root = config.vault_root.clone();
            let matches = tokio::task::spawn_blocking(move || {
                glob::glob(&pattern_str)
                    .map_err(|e| format!("Invalid glob pattern: {e}"))?
                    .filter_map(|entry| entry.ok())
                    .filter_map(|p| {
                        p.strip_prefix(&vault_root)
                            .ok()
                            .map(|rel| rel.to_string_lossy().replace('\\', "/"))
                    })
                    .collect::<Vec<String>>()
                    .pipe(Ok::<_, String>)
            })
            .await
            .map_err(|e| ToolError::ExecutionFailed(format!("spawn_blocking failed: {e}")))?
            .map_err(ToolError::ExecutionFailed)?;

            Ok(ToolResult {
                output: serde_json::to_string(&matches).unwrap(),
            })
        })
    }
}

// Helper trait to allow `.pipe()` chaining in the closure above
trait Pipe: Sized {
    fn pipe<F, R>(self, f: F) -> R
    where
        F: FnOnce(Self) -> R,
    {
        f(self)
    }
}
impl<T> Pipe for T {}
```

**Step 4: Run the tests to verify they pass**

```bash
cargo test -p amplifier-module-tool-filesystem glob
```

Expected:
```
test glob_finds_matching_files ... ok
test glob_returns_relative_paths ... ok
```

**Step 5: Commit**

```bash
git add -A && git commit -m "feat: glob tool with vault-relative path output"
```

---

### Task 6: `grep` tool (filesystem) — TDD cycle

**Files:**
- Create: `crates/amplifier-module-tool-filesystem/src/grep_tool.rs`
- Modify: `tests/integration_test.rs`

**Step 1: Write the failing tests**

Add to `tests/integration_test.rs`:

```rust
use amplifier_module_tool_filesystem::GrepTool;

#[tokio::test]
async fn grep_finds_matching_lines() {
    let dir = TempDir::new().unwrap();
    fs::write(
        dir.path().join("source.rs"),
        "fn main() {\n    println!(\"hello\");\n    println!(\"world\");\n}\n",
    )
    .unwrap();

    let cfg = FilesystemConfig::new(dir.path().to_path_buf());
    let tool = GrepTool::new(cfg);

    let result = tool
        .execute(json!({ "pattern": "println" }))
        .await
        .unwrap();

    let parsed: serde_json::Value = serde_json::from_str(&result.output).unwrap();
    let matches = parsed["matches"].as_array().unwrap();
    assert_eq!(matches.len(), 2);
    assert!(matches[0]["content"].as_str().unwrap().contains("println"));
}

#[tokio::test]
async fn grep_truncates_at_200_matches() {
    let dir = TempDir::new().unwrap();
    // Write a file with 250 matching lines
    let content = (0..250).map(|i| format!("match line {i}\n")).collect::<String>();
    fs::write(dir.path().join("big.txt"), content).unwrap();

    let cfg = FilesystemConfig::new(dir.path().to_path_buf());
    let tool = GrepTool::new(cfg);

    let result = tool
        .execute(json!({ "pattern": "match line" }))
        .await
        .unwrap();

    let parsed: serde_json::Value = serde_json::from_str(&result.output).unwrap();
    let matches = parsed["matches"].as_array().unwrap();
    assert_eq!(matches.len(), 200);
    // Total count reported when truncated
    assert_eq!(parsed["total_matches"].as_u64().unwrap(), 250);
    assert_eq!(parsed["truncated"].as_bool().unwrap(), true);
}

#[tokio::test]
async fn grep_invalid_regex_returns_error() {
    let dir = TempDir::new().unwrap();
    let cfg = FilesystemConfig::new(dir.path().to_path_buf());
    let tool = GrepTool::new(cfg);

    let result = tool.execute(json!({ "pattern": "[unclosed" })).await;
    assert!(result.is_err());
}
```

**Step 2: Run to verify compile failure**

```bash
cargo test -p amplifier-module-tool-filesystem grep
```

**Step 3: Write `src/grep_tool.rs`**

```rust
//! `grep` tool — regex search across vault files.
//!
//! Limits output to 200 matches. When truncated, includes `total_matches`
//! and `truncated: true` in the JSON response.

use std::fs;
use std::future::Future;
use std::pin::Pin;
use std::sync::Arc;

use amplifier_core::errors::ToolError;
use amplifier_core::messages::ToolSpec;
use amplifier_core::models::ToolResult;
use amplifier_core::traits::Tool;
use regex::Regex;
use serde_json::{json, Value};
use walkdir::WalkDir;

use crate::FilesystemConfig;

const MAX_RESULTS: usize = 200;

pub struct GrepTool {
    config: Arc<FilesystemConfig>,
}

impl GrepTool {
    pub fn new(config: Arc<FilesystemConfig>) -> Self {
        Self { config }
    }
}

impl Tool for GrepTool {
    fn spec(&self) -> ToolSpec {
        ToolSpec {
            name: "grep".into(),
            description: "Search file contents with a regex pattern. \
                          Returns up to 200 matches as JSON. \
                          Includes total_matches when truncated."
                .into(),
            parameters: json!({
                "type": "object",
                "properties": {
                    "pattern": { "type": "string", "description": "Regex pattern" },
                    "path": {
                        "type": "string",
                        "description": "Subdirectory to search (relative to vault root). Default: vault root."
                    },
                    "glob": {
                        "type": "string",
                        "description": "Optional filename glob filter, e.g. '*.rs'"
                    },
                    "-A": { "type": "integer", "description": "Lines to show after match" },
                    "-B": { "type": "integer", "description": "Lines to show before match" },
                    "-C": { "type": "integer", "description": "Lines to show before and after match" }
                },
                "required": ["pattern"]
            }),
        }
    }

    fn execute(
        &self,
        input: Value,
    ) -> Pin<Box<dyn Future<Output = Result<ToolResult, ToolError>> + Send + '_>> {
        let config = Arc::clone(&self.config);
        Box::pin(async move {
            let pattern_str = input["pattern"]
                .as_str()
                .ok_or_else(|| ToolError::InvalidInput("pattern is required".into()))?
                .to_owned();

            let re = Regex::new(&pattern_str)
                .map_err(|e| ToolError::ExecutionFailed(format!("Invalid regex: {e}")))?;

            let search_root = match input["path"].as_str() {
                Some(p) => config.vault_root.join(p),
                None => config.vault_root.clone(),
            };

            let glob_filter = input["glob"].as_str().map(|s| s.to_owned());

            // Walk is synchronous — offload to blocking thread pool
            let vault_root = config.vault_root.clone();
            let output = tokio::task::spawn_blocking(move || {
                let mut results: Vec<Value> = Vec::new();
                let mut total: usize = 0;

                'outer: for entry in WalkDir::new(&search_root)
                    .follow_links(false)
                    .into_iter()
                    .filter_map(|e| e.ok())
                    .filter(|e| e.file_type().is_file())
                {
                    let file_path = entry.path();

                    // Apply optional filename glob filter
                    if let Some(ref glob_pat) = glob_filter {
                        let filename = file_path
                            .file_name()
                            .and_then(|n| n.to_str())
                            .unwrap_or("");
                        if let Ok(pat) = glob::Pattern::new(glob_pat) {
                            if !pat.matches(filename) {
                                continue;
                            }
                        }
                    }

                    let content = match fs::read_to_string(file_path) {
                        Ok(c) => c,
                        Err(_) => continue, // skip binary files
                    };

                    for (i, line) in content.lines().enumerate() {
                        if re.is_match(line) {
                            total += 1;
                            if results.len() < MAX_RESULTS {
                                let rel = file_path
                                    .strip_prefix(&vault_root)
                                    .map(|p| p.to_string_lossy().replace('\\', "/"))
                                    .unwrap_or_else(|_| file_path.to_string_lossy().into_owned());
                                results.push(json!({
                                    "file": rel,
                                    "line": i + 1,
                                    "content": line,
                                }));
                            }
                            if total > MAX_RESULTS && results.len() == MAX_RESULTS {
                                // Keep counting total but stop collecting
                                continue 'outer; // won't actually skip, but makes intent clear
                            }
                        }
                    }
                }

                let mut output = json!({ "matches": results });
                if total > MAX_RESULTS {
                    output["total_matches"] = json!(total);
                    output["truncated"] = json!(true);
                }
                serde_json::to_string(&output).unwrap()
            })
            .await
            .map_err(|e| ToolError::ExecutionFailed(format!("spawn_blocking failed: {e}")))?;

            Ok(ToolResult { output })
        })
    }
}
```

**Step 4: Run the tests to verify they pass**

```bash
cargo test -p amplifier-module-tool-filesystem
```

Expected: all tests pass. Full suite should show ~14 passing tests.

**Step 5: Commit**

```bash
git add -A && git commit -m "feat: grep tool with 200-match cap and truncation reporting"
```

---

### Task 7: Verify full filesystem crate test suite

**Step 1: Run all filesystem tests**

```bash
cd /Users/ken/workspace/amplifier-rust && cargo test -p amplifier-module-tool-filesystem -- --nocapture
```

Expected: all tests pass with output like:
```
running N tests
test config_constructs ... ok
test read_file_returns_numbered_lines ... ok
test read_file_respects_offset_and_limit ... ok
test read_file_error_on_missing_file ... ok
test write_file_creates_file_and_parent_dirs ... ok
test write_file_denied_outside_vault ... ok
test edit_file_replaces_single_occurrence ... ok
test edit_file_replace_all_replaces_all_occurrences ... ok
test edit_file_errors_when_old_string_not_found ... ok
test glob_finds_matching_files ... ok
test glob_returns_relative_paths ... ok
test grep_finds_matching_lines ... ok
test grep_truncates_at_200_matches ... ok
test grep_invalid_regex_returns_error ... ok

test result: ok. N passed; 0 failed
```

**Step 2: Commit the complete crate**

```bash
git add -A && git commit -m "feat: amplifier-module-tool-filesystem complete — 5 tools, all tests pass"
```

---

### Task 8: `amplifier-module-tool-bash` — scaffold + Android profile TDD

**Files:**
- Replace: `crates/amplifier-module-tool-bash/Cargo.toml`
- Create: `crates/amplifier-module-tool-bash/src/lib.rs`
- Create: `crates/amplifier-module-tool-bash/src/profiles.rs`
- Test: `crates/amplifier-module-tool-bash/tests/integration_test.rs`

**Step 1: Replace `Cargo.toml` with full dependencies**

```toml
[package]
name = "amplifier-module-tool-bash"
version = "0.1.0"
edition = "2021"
description = "Restricted shell execution with safety profiles for the Amplifier agent framework"
license = "MIT"

[dependencies]
amplifier-core = { workspace = true }
tokio = { workspace = true }
serde_json = { workspace = true }

[dev-dependencies]
tokio = { version = "1", features = ["rt-multi-thread", "macros"] }
```

**Step 2: Write `src/profiles.rs`**

```rust
//! Safety profiles for the bash tool.
//!
//! ## Profile hierarchy (most to least restrictive)
//!
//! | Profile      | Mechanism                                | Use case              |
//! |---|---|---|
//! | Android      | Allowlist — only toybox commands allowed | sandbox binary        |
//! | Strict       | Denylist — blocks rm -rf, sudo, mount    | workstations          |
//! | Standard     | Denylist — blocks sudo, mount            | trusted environments  |
//! | Permissive   | Denylist — blocks mount only             | containers / VMs      |
//! | Unrestricted | No restrictions                          | dedicated hardware    |

/// Commands available in Android's toybox (the exhaustive allowlist).
pub const ANDROID_TOYBOX_ALLOWLIST: &[&str] = &[
    "ls", "cat", "echo", "mkdir", "rm", "cp", "mv", "find", "grep", "sed", "awk", "sort",
    "head", "tail", "tar", "gzip", "curl", "date", "sleep", "env", "id", "pwd", "wc", "diff",
    "unzip", "zip", "chmod", "touch", "which", "dirname", "basename",
];

/// Patterns that are always blocked in `Strict` profile.
const STRICT_DENY: &[&str] = &["rm -rf", "sudo", "mount", "umount", "dd ", "mkfs"];

/// Patterns blocked in `Standard` profile.
const STANDARD_DENY: &[&str] = &["sudo", "mount", "umount"];

/// Patterns blocked in `Permissive` profile.
const PERMISSIVE_DENY: &[&str] = &["mount", "umount"];

#[derive(Debug, Clone, PartialEq)]
pub enum SafetyProfile {
    /// Toybox allowlist only — use in the Android sandbox binary.
    Android,
    /// Blocks `rm -rf`, `sudo`, `mount` — use on workstations.
    Strict,
    /// Blocks `sudo`, `mount` — use in trusted CI / dev environments.
    Standard,
    /// Blocks `mount` only — use inside containers or VMs.
    Permissive,
    /// No restrictions — use only on dedicated hardware you own.
    Unrestricted,
}

/// Check if a command is permitted under this profile.
///
/// Returns `Ok(())` if allowed, `Err(reason)` if blocked.
pub fn check_command(profile: &SafetyProfile, command: &str) -> Result<(), String> {
    match profile {
        SafetyProfile::Unrestricted => Ok(()),

        SafetyProfile::Android => {
            let cmd_name = command.split_whitespace().next().unwrap_or("");
            if ANDROID_TOYBOX_ALLOWLIST.contains(&cmd_name) {
                Ok(())
            } else {
                Err(format!(
                    "Command '{cmd_name}' is not in the Android toybox allowlist"
                ))
            }
        }

        SafetyProfile::Strict => check_deny_list(command, STRICT_DENY),
        SafetyProfile::Standard => check_deny_list(command, STANDARD_DENY),
        SafetyProfile::Permissive => check_deny_list(command, PERMISSIVE_DENY),
    }
}

fn check_deny_list(command: &str, deny_patterns: &[&str]) -> Result<(), String> {
    let cmd_lower = command.to_lowercase();
    for pattern in deny_patterns {
        if cmd_lower.contains(pattern) {
            return Err(format!(
                "Command blocked by safety profile: contains '{pattern}'"
            ));
        }
    }
    Ok(())
}
```

**Step 3: Write `src/lib.rs`**

```rust
//! `amplifier-module-tool-bash` — restricted shell execution with safety profiles.
//!
//! The key addition in Phase 3 is the `Android` safety profile, which restricts
//! execution to the toybox command subset available on Android devices.

use std::future::Future;
use std::path::PathBuf;
use std::pin::Pin;
use std::sync::Arc;

use amplifier_core::errors::ToolError;
use amplifier_core::messages::ToolSpec;
use amplifier_core::models::ToolResult;
use amplifier_core::traits::Tool;
use serde_json::{json, Value};
use tokio::process::Command;
use tokio::time::{timeout, Duration};

pub mod profiles;
pub use profiles::SafetyProfile;

/// Configuration for the bash tool.
#[derive(Debug, Clone)]
pub struct BashConfig {
    pub safety_profile: SafetyProfile,
    /// Working directory for spawned commands. Default: current dir.
    pub working_dir: PathBuf,
    /// Timeout in seconds before the command is killed. Default: 30.
    pub timeout_secs: u64,
}

impl Default for BashConfig {
    fn default() -> Self {
        Self {
            safety_profile: SafetyProfile::Strict,
            working_dir: std::env::current_dir().unwrap_or_else(|_| PathBuf::from(".")),
            timeout_secs: 30,
        }
    }
}

pub struct BashTool {
    config: Arc<BashConfig>,
}

impl BashTool {
    pub fn new(config: BashConfig) -> Self {
        Self {
            config: Arc::new(config),
        }
    }
}

impl Tool for BashTool {
    fn spec(&self) -> ToolSpec {
        ToolSpec {
            name: "bash".into(),
            description: format!(
                "Execute a shell command. Safety profile: {:?}. Timeout: {}s.",
                self.config.safety_profile, self.config.timeout_secs
            ),
            parameters: json!({
                "type": "object",
                "properties": {
                    "command": {
                        "type": "string",
                        "description": "Shell command to execute"
                    },
                    "timeout": {
                        "type": "integer",
                        "description": "Override timeout in seconds (max: configured limit)"
                    }
                },
                "required": ["command"]
            }),
        }
    }

    fn execute(
        &self,
        input: Value,
    ) -> Pin<Box<dyn Future<Output = Result<ToolResult, ToolError>> + Send + '_>> {
        let config = Arc::clone(&self.config);
        Box::pin(async move {
            let command = input["command"]
                .as_str()
                .ok_or_else(|| ToolError::InvalidInput("command is required".into()))?;

            // Safety check
            profiles::check_command(&config.safety_profile, command)
                .map_err(ToolError::ExecutionFailed)?;

            let timeout_secs = input["timeout"]
                .as_u64()
                .unwrap_or(config.timeout_secs)
                .min(config.timeout_secs);

            let output = timeout(
                Duration::from_secs(timeout_secs),
                Command::new("sh")
                    .arg("-c")
                    .arg(command)
                    .current_dir(&config.working_dir)
                    .output(),
            )
            .await
            .map_err(|_| {
                ToolError::ExecutionFailed(format!("Command timed out after {timeout_secs}s"))
            })?
            .map_err(|e| ToolError::ExecutionFailed(format!("Failed to spawn command: {e}")))?;

            if output.status.success() {
                Ok(ToolResult {
                    output: String::from_utf8_lossy(&output.stdout).into_owned(),
                })
            } else {
                Err(ToolError::ExecutionFailed(
                    String::from_utf8_lossy(&output.stderr).into_owned(),
                ))
            }
        })
    }
}
```

**Step 4: Write the failing tests in `tests/integration_test.rs`**

```rust
use amplifier_core::traits::Tool;
use amplifier_module_tool_bash::{BashConfig, BashTool, SafetyProfile};
use serde_json::json;
use std::path::PathBuf;

fn make_tool(profile: SafetyProfile) -> BashTool {
    BashTool::new(BashConfig {
        safety_profile: profile,
        working_dir: PathBuf::from("/tmp"),
        timeout_secs: 5,
    })
}

// ─────────────────────── Android profile ──────────────────────────────────

#[tokio::test]
async fn android_profile_allows_toybox_commands() {
    let tool = make_tool(SafetyProfile::Android);

    // `echo` is in the toybox allowlist
    let result = tool
        .execute(json!({ "command": "echo hello" }))
        .await
        .unwrap();
    assert!(result.output.trim() == "hello");
}

#[tokio::test]
async fn android_profile_rejects_sudo() {
    let tool = make_tool(SafetyProfile::Android);
    let result = tool
        .execute(json!({ "command": "sudo ls" }))
        .await;
    assert!(result.is_err());
    let msg = format!("{}", result.unwrap_err());
    assert!(msg.contains("toybox allowlist"), "Error message was: {msg}");
}

#[tokio::test]
async fn android_profile_rejects_python() {
    let tool = make_tool(SafetyProfile::Android);
    let result = tool
        .execute(json!({ "command": "python3 -c 'print(1)'" }))
        .await;
    assert!(result.is_err());
}

#[tokio::test]
async fn android_profile_allows_ls_with_args() {
    let tool = make_tool(SafetyProfile::Android);
    // "ls" is the first token — allowed even with args
    let result = tool
        .execute(json!({ "command": "ls /tmp" }))
        .await;
    // Should not fail with a profile error (may fail if /tmp doesn't exist, but won't be a profile error)
    if let Err(e) = &result {
        let msg = format!("{e}");
        assert!(!msg.contains("toybox allowlist"), "Should not be a profile error, was: {msg}");
    }
}

// ─────────────────────── Strict profile ───────────────────────────────────

#[tokio::test]
async fn strict_profile_blocks_rm_rf() {
    let tool = make_tool(SafetyProfile::Strict);
    let result = tool
        .execute(json!({ "command": "rm -rf /tmp/test" }))
        .await;
    assert!(result.is_err());
    let msg = format!("{}", result.unwrap_err());
    assert!(msg.contains("blocked") || msg.contains("rm -rf"), "was: {msg}");
}

#[tokio::test]
async fn strict_profile_blocks_sudo() {
    let tool = make_tool(SafetyProfile::Strict);
    let result = tool
        .execute(json!({ "command": "sudo apt-get update" }))
        .await;
    assert!(result.is_err());
}

#[tokio::test]
async fn strict_profile_allows_echo() {
    let tool = make_tool(SafetyProfile::Strict);
    let result = tool
        .execute(json!({ "command": "echo strict_ok" }))
        .await
        .unwrap();
    assert!(result.output.contains("strict_ok"));
}
```

**Step 5: Run to verify compile failure**

```bash
cargo test -p amplifier-module-tool-bash android_profile
```

Expected: compile error — `BashTool` etc. not yet defined in `lib.rs` (they are defined, but the test file can't import if cargo.toml stubs are present — if it compiles, the tests will run and some may fail).

**Step 6: Run all tests to verify they pass**

```bash
cargo test -p amplifier-module-tool-bash
```

Expected:
```
test android_profile_allows_toybox_commands ... ok
test android_profile_rejects_sudo ... ok
test android_profile_rejects_python ... ok
test android_profile_allows_ls_with_args ... ok
test strict_profile_blocks_rm_rf ... ok
test strict_profile_blocks_sudo ... ok
test strict_profile_allows_echo ... ok

test result: ok. 7 passed; 0 failed
```

**Step 7: Commit**

```bash
git add -A && git commit -m "feat: amplifier-module-tool-bash with Android toybox allowlist profile"
```

---

### Task 9: `amplifier-module-tool-search` — ripgrep + pure Rust fallback TDD

**Files:**
- Replace: `crates/amplifier-module-tool-search/Cargo.toml`
- Create: `crates/amplifier-module-tool-search/src/lib.rs`
- Create: `crates/amplifier-module-tool-search/src/ripgrep.rs`
- Test: `crates/amplifier-module-tool-search/tests/integration_test.rs`

**Step 1: Replace `Cargo.toml`**

```toml
[package]
name = "amplifier-module-tool-search"
version = "0.1.0"
edition = "2021"
description = "Codebase grep tool backed by ripgrep subprocess with pure-Rust fallback"
license = "MIT"

[dependencies]
amplifier-core = { workspace = true }
tokio = { workspace = true }
serde_json = { workspace = true }
regex = "1"
walkdir = "2"

[dev-dependencies]
tokio = { version = "1", features = ["rt-multi-thread", "macros"] }
tempfile = "3"
```

**Step 2: Write `src/ripgrep.rs`**

```rust
//! Grep implementation — tries `rg` binary first, falls back to pure Rust.
//!
//! Both modes return the same JSON structure:
//! `[{"file": "src/lib.rs", "line": 42, "content": "matching line"}]`

use regex::Regex;
use serde_json::{json, Value};
use std::fs;
use walkdir::WalkDir;

/// Try ripgrep first; fall back to walkdir + regex if `rg` is not installed.
pub async fn grep(
    pattern: &str,
    path: &str,
    glob_filter: Option<&str>,
    max_results: usize,
) -> Result<Vec<Value>, String> {
    // Try rg first
    match grep_ripgrep(pattern, path, glob_filter, max_results).await {
        Ok(results) => return Ok(results),
        Err(_) => {} // rg not installed or failed — fall through to pure Rust
    }

    grep_fallback(pattern, path, glob_filter, max_results).await
}

/// Attempt grep via the `rg` binary with `--json` output.
async fn grep_ripgrep(
    pattern: &str,
    path: &str,
    glob_filter: Option<&str>,
    max_results: usize,
) -> Result<Vec<Value>, String> {
    let mut cmd = tokio::process::Command::new("rg");
    cmd.args(["--json", "--", pattern, path]);

    if let Some(glob) = glob_filter {
        cmd.args(["-g", glob]);
    }

    let output = cmd
        .output()
        .await
        .map_err(|e| format!("rg not available: {e}"))?;

    // Exit code 1 = no matches (not an error). Other non-zero = rg error.
    if !output.status.success() && output.status.code() != Some(1) {
        return Err(format!(
            "rg failed with code {:?}",
            output.status.code()
        ));
    }

    let stdout = String::from_utf8_lossy(&output.stdout);
    let mut results = Vec::new();

    for line in stdout.lines() {
        if results.len() >= max_results {
            break;
        }
        if let Ok(v) = serde_json::from_str::<Value>(line) {
            if v["type"] == "match" {
                let data = &v["data"];
                let file = data["path"]["text"]
                    .as_str()
                    .unwrap_or("")
                    .to_owned();
                let line_num = data["line_number"].as_u64().unwrap_or(0);
                let content = data["lines"]["text"]
                    .as_str()
                    .unwrap_or("")
                    .trim_end_matches('\n')
                    .to_owned();
                results.push(json!({
                    "file": file,
                    "line": line_num,
                    "content": content,
                }));
            }
        }
    }

    Ok(results)
}

/// Pure Rust fallback using walkdir + regex.
async fn grep_fallback(
    pattern: &str,
    path: &str,
    glob_filter: Option<&str>,
    max_results: usize,
) -> Result<Vec<Value>, String> {
    let re = Regex::new(pattern).map_err(|e| format!("Invalid regex: {e}"))?;
    let search_path = path.to_owned();
    let glob_filter = glob_filter.map(|s| s.to_owned());

    tokio::task::spawn_blocking(move || {
        let mut results = Vec::new();

        for entry in WalkDir::new(&search_path)
            .follow_links(false)
            .into_iter()
            .filter_map(|e| e.ok())
            .filter(|e| e.file_type().is_file())
        {
            if results.len() >= max_results {
                break;
            }

            let file_path = entry.path();

            // Apply optional filename glob filter
            if let Some(ref glob_pat) = glob_filter {
                let filename = file_path
                    .file_name()
                    .and_then(|n| n.to_str())
                    .unwrap_or("");
                if let Ok(pat) = glob::Pattern::new(glob_pat) {
                    if !pat.matches(filename) {
                        continue;
                    }
                }
            }

            let content = match fs::read_to_string(file_path) {
                Ok(c) => c,
                Err(_) => continue,
            };

            for (i, line) in content.lines().enumerate() {
                if results.len() >= max_results {
                    break;
                }
                if re.is_match(line) {
                    results.push(json!({
                        "file": file_path.to_string_lossy().replace('\\', "/"),
                        "line": i + 1,
                        "content": line,
                    }));
                }
            }
        }

        Ok::<_, String>(results)
    })
    .await
    .map_err(|e| format!("spawn_blocking failed: {e}"))?
}
```

**Note:** The `grep_fallback` function references `glob::Pattern`. Add `glob = "0.3"` to the crate's `[dependencies]` in the Cargo.toml above (it is intentionally included).

**Step 3: Write `src/lib.rs`**

```rust
//! `amplifier-module-tool-search` — ripgrep-backed codebase grep tool.

use std::future::Future;
use std::path::PathBuf;
use std::pin::Pin;
use std::sync::Arc;

use amplifier_core::errors::ToolError;
use amplifier_core::messages::ToolSpec;
use amplifier_core::models::ToolResult;
use amplifier_core::traits::Tool;
use serde_json::{json, Value};

pub mod ripgrep;

#[derive(Debug, Clone)]
pub struct SearchConfig {
    /// Base directory for searches when no `path` param is provided.
    pub base_path: PathBuf,
    /// Maximum number of results returned. Default: 200.
    pub max_results: usize,
}

impl SearchConfig {
    pub fn new(base_path: PathBuf) -> Arc<Self> {
        Arc::new(Self {
            base_path,
            max_results: 200,
        })
    }
}

pub struct GrepCodebaseTool {
    config: Arc<SearchConfig>,
}

impl GrepCodebaseTool {
    pub fn new(config: Arc<SearchConfig>) -> Self {
        Self { config }
    }
}

impl Tool for GrepCodebaseTool {
    fn spec(&self) -> ToolSpec {
        ToolSpec {
            name: "grep_codebase".into(),
            description: "Search file contents across the codebase using regex. \
                          Uses ripgrep if available, falls back to pure Rust. \
                          Returns JSON array of {file, line, content}."
                .into(),
            parameters: json!({
                "type": "object",
                "properties": {
                    "pattern": { "type": "string", "description": "Regex pattern to search for" },
                    "path": {
                        "type": "string",
                        "description": "Directory to search (absolute or relative to base_path)"
                    },
                    "glob": {
                        "type": "string",
                        "description": "Optional filename glob filter, e.g. '*.rs'"
                    },
                    "max_results": {
                        "type": "integer",
                        "description": "Max results to return (default: 200)"
                    }
                },
                "required": ["pattern"]
            }),
        }
    }

    fn execute(
        &self,
        input: Value,
    ) -> Pin<Box<dyn Future<Output = Result<ToolResult, ToolError>> + Send + '_>> {
        let config = Arc::clone(&self.config);
        Box::pin(async move {
            let pattern = input["pattern"]
                .as_str()
                .ok_or_else(|| ToolError::InvalidInput("pattern is required".into()))?;

            let search_path = match input["path"].as_str() {
                Some(p) => {
                    let p = PathBuf::from(p);
                    if p.is_absolute() {
                        p
                    } else {
                        config.base_path.join(p)
                    }
                }
                None => config.base_path.clone(),
            };

            let glob_filter = input["glob"].as_str();
            let max_results = input["max_results"]
                .as_u64()
                .map(|n| n as usize)
                .unwrap_or(config.max_results);

            let results = ripgrep::grep(
                pattern,
                search_path.to_str().unwrap_or("."),
                glob_filter,
                max_results,
            )
            .await
            .map_err(ToolError::ExecutionFailed)?;

            Ok(ToolResult {
                output: serde_json::to_string(&results).unwrap(),
            })
        })
    }
}
```

**Step 4: Write the failing tests in `tests/integration_test.rs`**

```rust
use amplifier_core::traits::Tool;
use amplifier_module_tool_search::{GrepCodebaseTool, SearchConfig};
use serde_json::json;
use std::fs;
use tempfile::TempDir;

#[tokio::test]
async fn grep_codebase_finds_matches_in_files() {
    let dir = TempDir::new().unwrap();
    fs::write(
        dir.path().join("main.rs"),
        "fn main() {\n    let x = 42;\n    println!(\"{x}\");\n}\n",
    )
    .unwrap();

    let cfg = SearchConfig::new(dir.path().to_path_buf());
    let tool = GrepCodebaseTool::new(cfg);

    let result = tool
        .execute(json!({ "pattern": "println" }))
        .await
        .unwrap();

    let matches: Vec<serde_json::Value> = serde_json::from_str(&result.output).unwrap();
    assert!(!matches.is_empty());
    assert!(matches[0]["content"].as_str().unwrap().contains("println"));
    assert!(matches[0]["line"].as_u64().unwrap() > 0);
}

#[tokio::test]
async fn grep_codebase_returns_empty_array_for_no_matches() {
    let dir = TempDir::new().unwrap();
    fs::write(dir.path().join("file.rs"), "no match here\n").unwrap();

    let cfg = SearchConfig::new(dir.path().to_path_buf());
    let tool = GrepCodebaseTool::new(cfg);

    let result = tool
        .execute(json!({ "pattern": "xyzzy_not_found" }))
        .await
        .unwrap();

    let matches: Vec<serde_json::Value> = serde_json::from_str(&result.output).unwrap();
    assert!(matches.is_empty());
}

#[tokio::test]
async fn grep_codebase_glob_filters_file_types() {
    let dir = TempDir::new().unwrap();
    fs::write(dir.path().join("code.rs"), "fn search_target() {}\n").unwrap();
    fs::write(dir.path().join("notes.md"), "search_target is documented here\n").unwrap();

    let cfg = SearchConfig::new(dir.path().to_path_buf());
    let tool = GrepCodebaseTool::new(cfg);

    let result = tool
        .execute(json!({ "pattern": "search_target", "glob": "*.rs" }))
        .await
        .unwrap();

    let matches: Vec<serde_json::Value> = serde_json::from_str(&result.output).unwrap();
    assert_eq!(matches.len(), 1);
    assert!(matches[0]["file"].as_str().unwrap().ends_with(".rs"));
}
```

**Step 5: Run to verify compile failure**

```bash
cargo test -p amplifier-module-tool-search
```

Expected: compile errors until `lib.rs` and `ripgrep.rs` are in place.

**Step 6: Run all tests to verify they pass**

```bash
cargo test -p amplifier-module-tool-search
```

Expected:
```
test grep_codebase_finds_matches_in_files ... ok
test grep_codebase_returns_empty_array_for_no_matches ... ok
test grep_codebase_glob_filters_file_types ... ok

test result: ok. 3 passed; 0 failed
```

**Step 7: Run the full workspace to confirm nothing broke**

```bash
cd /Users/ken/workspace/amplifier-rust && cargo test --workspace
```

Expected: all tests pass.

**Step 8: Commit**

```bash
git add -A && git commit -m "feat: amplifier-module-tool-search with ripgrep subprocess and pure-Rust fallback"
```

---

## Phase 3b — Android Sandbox Binary

> **Start a new execution session here.** Phase 3a must be fully committed before starting Phase 3b.

---

### Task 10: Sandbox binary skeleton — compiles on all platforms

**Files:**
- Replace: `sandbox/amplifier-android-sandbox/Cargo.toml`
- Replace: `sandbox/amplifier-android-sandbox/src/main.rs`
- Create: `sandbox/amplifier-android-sandbox/src/sandbox.rs`
- Create: `sandbox/amplifier-android-sandbox/src/hooks.rs`
- Create: `sandbox/amplifier-android-sandbox/src/tools.rs`

**Step 1: Write the full `Cargo.toml`**

```toml
[package]
name = "amplifier-android-sandbox"
version = "0.1.0"
edition = "2021"
publish = false      # NOT published to crates.io

[[bin]]
name = "amplifier-android-sandbox"
path = "src/main.rs"

[dependencies]
# ── Workspace crates ──────────────────────────────────────────────────────
amplifier-module-orchestrator-loop-streaming = { path = "../../crates/amplifier-module-orchestrator-loop-streaming" }
amplifier-module-context-simple              = { path = "../../crates/amplifier-module-context-simple" }
amplifier-module-provider-anthropic          = { path = "../../crates/amplifier-module-provider-anthropic" }
amplifier-module-provider-ollama             = { path = "../../crates/amplifier-module-provider-ollama" }
amplifier-module-provider-gemini             = { path = "../../crates/amplifier-module-provider-gemini" }
amplifier-module-provider-openai             = { path = "../../crates/amplifier-module-provider-openai" }
amplifier-module-tool-filesystem             = { path = "../../crates/amplifier-module-tool-filesystem" }
amplifier-module-tool-bash                   = { path = "../../crates/amplifier-module-tool-bash" }
amplifier-module-tool-web                    = { path = "../../crates/amplifier-module-tool-web" }
amplifier-module-tool-search                 = { path = "../../crates/amplifier-module-tool-search" }
amplifier-module-tool-task                   = { path = "../../crates/amplifier-module-tool-task" }
amplifier-module-tool-skills                 = { path = "../../crates/amplifier-module-tool-skills" }
amplifier-module-tool-todo                   = { path = "../../crates/amplifier-module-tool-todo" }

# ── External deps ─────────────────────────────────────────────────────────
clap       = { version = "4", features = ["derive"] }
tokio      = { version = "1", features = ["full"] }
anyhow     = "1"
serde_json = "1"
async-trait = "0.1"

# ── Linux-only deps (sandboxing) ──────────────────────────────────────────
# These compile only when targeting Linux — macro-OS builds skip them cleanly.
[target.'cfg(target_os = "linux")'.dependencies]
landlock   = "0.4"
libseccomp = "0.3"
```

> **macOS note:** The `[target.'cfg(target_os = "linux")'.dependencies]` block means
> `landlock` and `libseccomp` are **never downloaded or compiled on macOS**. You will
> never see linker errors for these on macOS regardless of the `--sandbox` flag.

**Step 2: Write the minimal `src/main.rs` skeleton (compiles immediately)**

```rust
//! `amplifier-android-sandbox` — CLI binary for the full Amplifier agent stack.
//!
//! Assembles all workspace crates into a single runnable agent with:
//! - Optional Linux sandbox (landlock + seccomp) via `--sandbox`
//! - REPL mode (default) or single-turn mode (`--prompt`)
//! - Provider selection: anthropic | gemini | openai | ollama

#![allow(unused_imports)] // during skeleton phase — remove before final commit

use anyhow::Result;
use clap::Parser;
use std::path::PathBuf;

mod hooks;
mod sandbox;
mod tools;

/// Amplifier Android Sandbox — portable agent runtime with OS-enforced restrictions.
#[derive(Parser, Debug)]
#[command(name = "amplifier-android-sandbox", version, about)]
struct Args {
    /// Vault directory (agent's read/write workspace)
    #[arg(long, default_value = "./vault")]
    vault: PathBuf,

    /// LLM provider: anthropic | gemini | openai | ollama
    #[arg(long, default_value = "anthropic")]
    provider: String,

    /// Model name (uses provider default if not specified)
    #[arg(long)]
    model: Option<String>,

    /// Single-turn prompt. If omitted, starts interactive REPL.
    #[arg(long)]
    prompt: Option<String>,

    /// Apply landlock + seccomp restrictions (Linux only, requires kernel 5.13+)
    #[arg(long, default_value_t = false)]
    sandbox: bool,

    /// Maximum agent loop steps before aborting
    #[arg(long, default_value_t = 10)]
    max_steps: usize,
}

#[tokio::main]
async fn main() -> Result<()> {
    let args = Args::parse();
    eprintln!("[sandbox] starting — provider={} vault={}", args.provider, args.vault.display());

    // Sandbox setup
    if args.sandbox {
        sandbox::apply(&args.vault)?;
    }

    // Vault directory
    std::fs::create_dir_all(&args.vault)?;

    eprintln!("[sandbox] skeleton OK — full wiring in Task 14");
    Ok(())
}
```

**Step 3: Write stub `src/sandbox.rs`**

```rust
//! OS-level sandbox restrictions (Linux only).
//!
//! On macOS/Windows this module compiles to a no-op. The full landlock + seccomp
//! implementation lives inside `#[cfg(target_os = "linux")]` blocks.

#[cfg(target_os = "linux")]
pub fn apply(vault_path: &std::path::Path) -> anyhow::Result<()> {
    eprintln!("[sandbox] Linux sandbox not yet wired — see Task 11");
    let _ = vault_path;
    Ok(())
}

#[cfg(not(target_os = "linux"))]
pub fn apply(_vault_path: &std::path::Path) -> anyhow::Result<()> {
    eprintln!("[sandbox] Note: --sandbox has no effect on this platform (Linux only)");
    Ok(())
}
```

**Step 4: Write stub `src/hooks.rs`**

```rust
//! Rust-native hooks for the sandbox binary.

// Full implementation in Task 12
pub fn build_registry() {
    // stub
}
```

**Step 5: Write stub `src/tools.rs`**

```rust
//! Tool registry assembly for the sandbox binary.

// Full implementation in Task 13
pub fn build_registry(_vault: &std::path::Path) -> anyhow::Result<()> {
    Ok(())
}
```

**Step 6: Verify the skeleton compiles on your platform (macOS or Linux)**

```bash
cd /Users/ken/workspace/amplifier-rust && cargo build -p amplifier-android-sandbox
```

Expected:
```
Compiling amplifier-android-sandbox v0.1.0
 Finished `dev` profile [unoptimized + debuginfo] target(s)
```

**Step 7: Verify `--help` works**

```bash
cargo run -p amplifier-android-sandbox -- --help
```

Expected output (exact text may vary but must include all arg names):
```
Amplifier Android Sandbox — portable agent runtime with OS-enforced restrictions

Usage: amplifier-android-sandbox [OPTIONS]

Options:
      --vault <VAULT>        [default: ./vault]
      --provider <PROVIDER>  [default: anthropic]
      --model <MODEL>
      --prompt <PROMPT>
      --sandbox
      --max-steps <MAX_STEPS>  [default: 10]
  -h, --help                Print help
  -V, --version             Print version
```

**Step 8: Commit the skeleton**

```bash
git add -A && git commit -m "feat: amplifier-android-sandbox binary skeleton — clap CLI, compiles on macOS + Linux"
```

---

### Task 11: `sandbox.rs` — Linux landlock + seccomp with conditional compilation

**Files:**
- Replace: `sandbox/amplifier-android-sandbox/src/sandbox.rs`

**Critical — read this before writing code:**
The `landlock` and `libseccomp` crates are only available on Linux. The entire Linux
implementation lives inside a `#[cfg(target_os = "linux")]` block. The `#[cfg(not(...))]`
block is the macOS/Windows path. **Never put Linux-only imports at the file top level** —
they must be inside `#[cfg(target_os = "linux")]` functions or blocks.

**Step 1: Write the failing test (in a separate file — tests are cross-platform)**

Create `sandbox/amplifier-android-sandbox/src/sandbox.rs` test section:

```rust
#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::TempDir;

    #[test]
    fn apply_is_callable_on_all_platforms() {
        // The non-Linux path should always succeed immediately.
        // On Linux it may succeed or warn depending on kernel version.
        let dir = TempDir::new().unwrap();
        // Just verify it doesn't panic or return a hard error on the
        // non-Linux path. On Linux it is allowed to print a warning
        // but must not propagate an error unless the kernel is < 5.13.
        #[cfg(not(target_os = "linux"))]
        {
            let result = apply(dir.path());
            assert!(result.is_ok(), "non-Linux apply() must always succeed");
        }
    }
}
```

**Step 2: Run the test to confirm it currently passes with the stub**

```bash
cargo test -p amplifier-android-sandbox apply_is_callable
```

Expected: `test apply_is_callable_on_all_platforms ... ok`

**Step 3: Replace `src/sandbox.rs` with the full implementation**

```rust
//! OS-level sandbox restrictions mimicking Android's security model.
//!
//! ## What this does (Linux only)
//!
//! 1. **landlock** (kernel 5.13+, no root required): Restricts filesystem access
//!    to vault + /tmp (read/write), /etc/ssl (read only for TLS).
//!    Blocks /home, /root, /var, /proc (except /proc/self), /sys.
//!
//! 2. **seccomp BPF** (no root required): Blocks dangerous syscalls:
//!    ptrace, mount, umount2, setuid, setgid, capset, kexec_load, chroot.
//!    All network and file I/O syscalls remain permitted.
//!
//! ## macOS / Windows
//!
//! `apply()` is a documented no-op that prints a note and returns Ok(()).
//! For full-fidelity testing of the sandbox, use Docker on Linux.
//!
//! ## Docker recipe for full sandbox testing
//!
//! ```dockerfile
//! FROM rust:slim-bookworm
//! RUN apt-get update && apt-get install -y libseccomp-dev
//! COPY . .
//! RUN cargo build -p amplifier-android-sandbox --release
//! # Run with --sandbox inside the container:
//! CMD ["./target/release/amplifier-android-sandbox", "--sandbox", "--provider", "ollama"]
//! ```

// ─────────────────────────── Linux implementation ─────────────────────────

#[cfg(target_os = "linux")]
pub fn apply(vault_path: &std::path::Path) -> anyhow::Result<()> {
    apply_landlock(vault_path)?;
    apply_seccomp()?;
    Ok(())
}

#[cfg(target_os = "linux")]
fn apply_landlock(vault_path: &std::path::Path) -> anyhow::Result<()> {
    use landlock::{
        Access, AccessFs, PathBeneath, PathFd, ABI,
        Ruleset, RulesetAttr, RulesetCreatedAttr, RulesetStatus,
    };

    let abi = ABI::V3;

    let status = Ruleset::default()
        .handle_access(AccessFs::from_all(abi))?
        .create()?
        // Vault: full read + write
        .add_rule(PathBeneath::new(
            PathFd::new(vault_path)?,
            AccessFs::from_all(abi),
        ))?
        // /tmp: full read + write (needed for tool temp files)
        .add_rule(PathBeneath::new(
            PathFd::new("/tmp")?,
            AccessFs::from_all(abi),
        ))?
        // /etc/ssl: read only (TLS certificate verification)
        .add_rule(PathBeneath::new(
            PathFd::new("/etc/ssl")?,
            AccessFs::ReadFile | AccessFs::ReadDir,
        ))?
        .restrict_self()?;

    if status.ruleset == RulesetStatus::NotEnforced {
        eprintln!(
            "[sandbox] Warning: landlock not enforced — kernel too old (need 5.13+). \
             Running without filesystem restrictions."
        );
    } else {
        eprintln!("[sandbox] landlock applied — vault-only filesystem access active");
    }

    Ok(())
}

#[cfg(target_os = "linux")]
fn apply_seccomp() -> anyhow::Result<()> {
    use libseccomp::{ScmpAction, ScmpFilterContext, ScmpSyscall};

    let mut ctx = ScmpFilterContext::new_filter(ScmpAction::Allow)?;

    // Block syscalls that could escape the sandbox or escalate privileges
    for syscall_name in &[
        "ptrace",
        "mount",
        "umount2",
        "setuid",
        "setgid",
        "capset",
        "kexec_load",
        "chroot",
    ] {
        ctx.add_rule(
            ScmpAction::Errno(1), // EPERM
            ScmpSyscall::from_name(syscall_name)?,
        )?;
    }

    ctx.load()?;
    eprintln!("[sandbox] seccomp BPF loaded — dangerous syscalls blocked");
    Ok(())
}

// ─────────────────────────── Non-Linux stub ───────────────────────────────

#[cfg(not(target_os = "linux"))]
pub fn apply(_vault_path: &std::path::Path) -> anyhow::Result<()> {
    eprintln!(
        "[sandbox] Note: --sandbox flag has no effect on this platform. \
         landlock and seccomp are Linux-only (kernel 5.13+). \
         Use Docker on Linux for full-fidelity sandbox testing."
    );
    Ok(())
}

// ─────────────────────────────── Tests ───────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::TempDir;

    #[test]
    fn apply_is_callable_on_all_platforms() {
        #[cfg(not(target_os = "linux"))]
        {
            let dir = TempDir::new().unwrap();
            let result = apply(dir.path());
            assert!(result.is_ok(), "non-Linux apply() must always succeed");
        }
        // On Linux: skip here — sandbox applies process-wide restrictions
        // which would interfere with the test suite. Test on Linux via
        // `cargo run -p amplifier-android-sandbox -- --sandbox --help`
    }
}
```

**Step 4: Add `tempfile` to dev-dependencies in `Cargo.toml`**

Open `sandbox/amplifier-android-sandbox/Cargo.toml` and add:

```toml
[dev-dependencies]
tempfile = "3"
```

**Step 5: Verify the binary still compiles on your platform**

```bash
cd /Users/ken/workspace/amplifier-rust && cargo build -p amplifier-android-sandbox
```

Expected: no errors on macOS. No mention of `landlock` or `libseccomp` in the output.

**Step 6: Run the test**

```bash
cargo test -p amplifier-android-sandbox
```

Expected: `test sandbox::tests::apply_is_callable_on_all_platforms ... ok`

**Step 7: Commit**

```bash
git add -A && git commit -m "feat: sandbox.rs — landlock + seccomp (Linux) with #[cfg] conditional compilation"
```

---

### Task 12: `hooks.rs` — LoggingHook

**Files:**
- Replace: `sandbox/amplifier-android-sandbox/src/hooks.rs`

**Background:** The orchestrator crate exposes `Hook`, `HookEvent`, `HookResult`, `HookContext`,
and `HookRegistry`. Import them directly from `amplifier_module_orchestrator_loop_streaming`.

**Step 1: Write the failing test — update `hooks.rs`**

Temporarily write a test at the bottom of `hooks.rs` to verify the type structure:

```rust
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn registry_builds_without_panic() {
        let registry = build_registry();
        // Registry should contain at least one hook
        assert!(registry.len() >= 1);
    }
}
```

**Step 2: Run to verify compile failure**

```bash
cargo test -p amplifier-android-sandbox registry_builds
```

Expected: compile error — `build_registry()` currently returns `()`, not a registry with `.len()`.

**Step 3: Replace `src/hooks.rs` with the full implementation**

```rust
//! Rust-native hooks for the sandbox binary.
//!
//! The sandbox does not have a Kotlin side, so hooks are implemented in pure Rust.
//! Currently provides `LoggingHook` which logs all lifecycle events to stderr.

use amplifier_module_orchestrator_loop_streaming::{
    Hook, HookContext, HookEvent, HookRegistry, HookResult,
};
use async_trait::async_trait;

/// Logs all lifecycle events to stderr.
///
/// This replaces `StatusContextHook` for local development — it shows
/// every hook firing without any Android system dependencies.
pub struct LoggingHook;

#[async_trait]
impl Hook for LoggingHook {
    fn events(&self) -> &[HookEvent] {
        &[
            HookEvent::SessionStart,
            HookEvent::ProviderRequest,
            HookEvent::ToolPre,
            HookEvent::ToolPost,
            HookEvent::TurnEnd,
        ]
    }

    async fn handle(&self, event: HookEvent, ctx: &HookContext) -> HookResult {
        eprintln!("[hook] {:?}: {}", event, ctx.data);
        HookResult::Continue
    }
}

/// Build the hook registry for the sandbox binary.
pub fn build_registry() -> HookRegistry {
    let mut registry = HookRegistry::new();
    registry.register(Box::new(LoggingHook));
    registry
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn registry_builds_without_panic() {
        let registry = build_registry();
        assert!(registry.len() >= 1);
    }
}
```

**Step 4: Run the test**

```bash
cargo test -p amplifier-android-sandbox registry_builds
```

Expected: `test hooks::tests::registry_builds_without_panic ... ok`

**Step 5: Commit**

```bash
git add -A && git commit -m "feat: LoggingHook — Rust-native lifecycle event logger for sandbox"
```

---

### Task 13: `tools.rs` — assemble full tool registry

**Files:**
- Replace: `sandbox/amplifier-android-sandbox/src/tools.rs`

**Step 1: Write the failing test**

Add this to `tools.rs` temporarily:

```rust
#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::TempDir;

    #[test]
    fn registry_has_nine_tools() {
        let dir = TempDir::new().unwrap();
        let tools = build_registry(dir.path()).unwrap();
        assert_eq!(tools.len(), 9, "expected 9 tools, got: {:?}", tools.keys().collect::<Vec<_>>());
    }
}
```

**Step 2: Run to verify failure (wrong tool count with stub)**

```bash
cargo test -p amplifier-android-sandbox registry_has_nine_tools
```

Expected: test fails or panics — current `build_registry` returns `Ok(())`.

**Step 3: Replace `src/tools.rs` with the full implementation**

```rust
//! Tool registry for the sandbox binary.
//!
//! Assembles 9 tools from 5 workspace crates. Note: `TaskTool` is wired
//! separately in `main.rs` after the orchestrator is created (it requires
//! `Arc<dyn SubagentRunner>`). Skills tool is also added there.

use std::collections::HashMap;
use std::path::Path;
use std::sync::Arc;

use amplifier_core::traits::Tool;
use amplifier_module_tool_bash::{BashConfig, BashTool, SafetyProfile};
use amplifier_module_tool_filesystem::{
    EditFileTool, FilesystemConfig, GlobTool, GrepTool, ReadFileTool, WriteFileTool,
};
use amplifier_module_tool_search::{GrepCodebaseTool, SearchConfig};
use amplifier_module_tool_todo::TodoTool;
use amplifier_module_tool_web::WebFetchTool;

/// A tool map that main.rs can extend (e.g., with TaskTool after orchestrator creation).
pub type ToolMap = HashMap<String, Box<dyn Tool + Send + Sync>>;

/// Build the core tool registry for the sandbox binary.
///
/// Returns 9 tools. TaskTool (subagent spawning) and SkillsTool are added in
/// `main.rs` after the orchestrator is wired as a `SubagentRunner`.
pub fn build_registry(vault: &Path) -> anyhow::Result<ToolMap> {
    let vault_buf = vault.to_path_buf();

    // ── Filesystem tools (5) ────────────────────────────────────────────
    let fs_config = FilesystemConfig::new(vault_buf.clone());

    // ── Bash tool (Android profile) ─────────────────────────────────────
    let bash_config = BashConfig {
        safety_profile: SafetyProfile::Android,
        working_dir: vault_buf.clone(),
        timeout_secs: 30,
    };

    // ── Search tool ─────────────────────────────────────────────────────
    let search_config = SearchConfig::new(vault_buf.clone());

    let mut tools: ToolMap = HashMap::new();

    // 1–5: filesystem
    tools.insert("read_file".into(), Box::new(ReadFileTool::new(Arc::clone(&fs_config))));
    tools.insert("write_file".into(), Box::new(WriteFileTool::new(Arc::clone(&fs_config))));
    tools.insert("edit_file".into(), Box::new(EditFileTool::new(Arc::clone(&fs_config))));
    tools.insert("glob".into(), Box::new(GlobTool::new(Arc::clone(&fs_config))));
    tools.insert("grep".into(), Box::new(GrepTool::new(Arc::clone(&fs_config))));

    // 6: bash (toybox allowlist only)
    tools.insert("bash".into(), Box::new(BashTool::new(bash_config)));

    // 7: web fetch
    tools.insert("web_fetch".into(), Box::new(WebFetchTool::new()));

    // 8: codebase grep (ripgrep or fallback)
    tools.insert("grep_codebase".into(), Box::new(GrepCodebaseTool::new(search_config)));

    // 9: todo
    tools.insert("todo".into(), Box::new(TodoTool::new()));

    Ok(tools)
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::TempDir;

    #[test]
    fn registry_has_nine_tools() {
        let dir = TempDir::new().unwrap();
        let tools = build_registry(dir.path()).unwrap();
        assert_eq!(
            tools.len(),
            9,
            "expected 9 tools, got: {:?}",
            tools.keys().collect::<Vec<_>>()
        );
    }

    #[test]
    fn all_expected_tool_names_present() {
        let dir = TempDir::new().unwrap();
        let tools = build_registry(dir.path()).unwrap();
        for name in &[
            "read_file", "write_file", "edit_file", "glob", "grep",
            "bash", "web_fetch", "grep_codebase", "todo",
        ] {
            assert!(tools.contains_key(*name), "missing tool: {name}");
        }
    }
}
```

**Step 4: Run the tests**

```bash
cargo test -p amplifier-android-sandbox
```

Expected:
```
test hooks::tests::registry_builds_without_panic ... ok
test sandbox::tests::apply_is_callable_on_all_platforms ... ok
test tools::tests::registry_has_nine_tools ... ok
test tools::tests::all_expected_tool_names_present ... ok
```

**Step 5: Commit**

```bash
git add -A && git commit -m "feat: tools.rs — 9-tool registry for sandbox binary"
```

---

### Task 14: Complete `main.rs` — wire orchestrator, providers, REPL

**Files:**
- Replace: `sandbox/amplifier-android-sandbox/src/main.rs`

**Step 1: Replace the skeleton `main.rs` with the full wiring**

```rust
//! `amplifier-android-sandbox` — CLI binary for the full Amplifier agent stack.
//!
//! ## Startup sequence
//!
//! 1. Parse CLI args (clap)
//! 2. If `--sandbox` and Linux: apply landlock + seccomp
//! 3. Create vault directory
//! 4. Build HookRegistry (LoggingHook)
//! 5. Build core tool registry (9 tools)
//! 6. Create orchestrator + wire TaskTool (subagent spawning)
//! 7. Add SkillsTool (reads from vault/skills/)
//! 8. Select provider from `--provider`
//! 9. If `--prompt`: single turn + exit. Else: REPL loop.

use anyhow::{Context, Result};
use clap::Parser;
use std::collections::HashMap;
use std::io::{self, Write};
use std::path::PathBuf;
use std::sync::Arc;

use amplifier_core::traits::Provider;
use amplifier_module_context_simple::SimpleContext;
use amplifier_module_orchestrator_loop_streaming::{LoopOrchestrator, OrchestratorConfig};
use amplifier_module_provider_anthropic::AnthropicProvider;
use amplifier_module_provider_gemini::GeminiProvider;
use amplifier_module_provider_ollama::OllamaProvider;
use amplifier_module_provider_openai::OpenAIProvider;
use amplifier_module_tool_skills::SkillsTool;
use amplifier_module_tool_task::{SubagentRunner, TaskTool};

mod hooks;
mod sandbox;
mod tools;

/// Amplifier Android Sandbox — portable agent runtime with OS-enforced restrictions.
#[derive(Parser, Debug)]
#[command(name = "amplifier-android-sandbox", version, about)]
struct Args {
    /// Vault directory (agent's read/write workspace)
    #[arg(long, default_value = "./vault")]
    vault: PathBuf,

    /// LLM provider: anthropic | gemini | openai | ollama
    #[arg(long, default_value = "anthropic")]
    provider: String,

    /// Model name (uses provider default if not specified)
    #[arg(long)]
    model: Option<String>,

    /// Single-turn prompt. If omitted, starts interactive REPL.
    #[arg(long)]
    prompt: Option<String>,

    /// Apply landlock + seccomp restrictions (Linux only, requires kernel 5.13+)
    #[arg(long, default_value_t = false)]
    sandbox: bool,

    /// Maximum agent loop steps before aborting
    #[arg(long, default_value_t = 10)]
    max_steps: usize,
}

#[tokio::main]
async fn main() -> Result<()> {
    let args = Args::parse();

    // ── Step 1: Apply sandbox ────────────────────────────────────────────
    if args.sandbox {
        sandbox::apply(&args.vault)?;
    }

    // ── Step 2: Create vault directory ──────────────────────────────────
    std::fs::create_dir_all(&args.vault)
        .with_context(|| format!("Failed to create vault: {}", args.vault.display()))?;

    // ── Step 3: Build hook registry ─────────────────────────────────────
    let hook_registry = hooks::build_registry();

    // ── Step 4: Build core tool registry ────────────────────────────────
    let mut tool_map = tools::build_registry(&args.vault)?;

    // ── Step 5: Create orchestrator ─────────────────────────────────────
    let orch = Arc::new(LoopOrchestrator::new(OrchestratorConfig {
        max_steps: args.max_steps,
        primary_provider: args.provider.clone(),
    }));

    // ── Step 6: Wire TaskTool (requires Arc<dyn SubagentRunner>) ────────
    let task_tool = TaskTool::new(
        Arc::clone(&orch) as Arc<dyn SubagentRunner>,
        0, // current recursion depth
    );
    tool_map.insert("task".into(), Box::new(task_tool));

    // ── Step 7: Wire SkillsTool ──────────────────────────────────────────
    let skills_dir = args.vault.join("skills");
    std::fs::create_dir_all(&skills_dir)?;
    let skills_tool = SkillsTool::new(skills_dir);
    tool_map.insert("skills".into(), Box::new(skills_tool));

    // ── Step 8: Build provider map ───────────────────────────────────────
    let mut providers: HashMap<String, Box<dyn Provider>> = HashMap::new();
    match args.provider.as_str() {
        "anthropic" => {
            let key = std::env::var("ANTHROPIC_API_KEY")
                .context("ANTHROPIC_API_KEY not set")?;
            providers.insert(
                "anthropic".into(),
                Box::new(AnthropicProvider::new(key, args.model.clone())),
            );
        }
        "gemini" => {
            let key = std::env::var("GEMINI_API_KEY")
                .or_else(|_| std::env::var("GOOGLE_API_KEY"))
                .context("Set GEMINI_API_KEY or GOOGLE_API_KEY")?;
            providers.insert(
                "gemini".into(),
                Box::new(GeminiProvider::new(key, args.model.clone())),
            );
        }
        "openai" => {
            let key = std::env::var("OPENAI_API_KEY")
                .context("OPENAI_API_KEY not set")?;
            providers.insert(
                "openai".into(),
                Box::new(OpenAIProvider::new(key, args.model.clone())),
            );
        }
        "ollama" => {
            // Ollama: no API key required
            providers.insert(
                "ollama".into(),
                Box::new(OllamaProvider::new(args.model.clone())),
            );
        }
        other => {
            anyhow::bail!(
                "Unknown provider '{other}'. Valid options: anthropic | gemini | openai | ollama"
            );
        }
    }

    // ── Step 9: Run single-turn or REPL ─────────────────────────────────
    let context = SimpleContext::new(vec![]);

    if let Some(prompt) = args.prompt {
        let response = orch
            .execute(prompt, &context, &providers, &tool_map, &hook_registry)
            .await?;
        println!("{response}");
    } else {
        // Interactive REPL
        eprintln!("[sandbox] REPL mode — type your prompt, Ctrl-D to exit");
        let stdin = io::stdin();
        loop {
            print!("> ");
            io::stdout().flush()?;

            let mut line = String::new();
            match stdin.read_line(&mut line) {
                Ok(0) => break, // EOF (Ctrl-D)
                Ok(_) => {}
                Err(e) => {
                    eprintln!("Input error: {e}");
                    break;
                }
            }

            let prompt = line.trim();
            if prompt.is_empty() {
                continue;
            }

            match orch
                .execute(
                    prompt.to_owned(),
                    &context,
                    &providers,
                    &tool_map,
                    &hook_registry,
                )
                .await
            {
                Ok(response) => println!("{response}"),
                Err(e) => eprintln!("[error] {e}"),
            }
        }
    }

    Ok(())
}
```

**Step 2: Build to verify complete compilation**

```bash
cd /Users/ken/workspace/amplifier-rust && cargo build -p amplifier-android-sandbox
```

Expected: compiles cleanly. Zero errors. Any warnings about unused imports can be cleaned up.

**Step 3: Run `--help` to verify the full CLI**

```bash
cargo run -p amplifier-android-sandbox -- --help
```

Expected: shows all 6 options with descriptions.

**Step 4: Run all sandbox tests**

```bash
cargo test -p amplifier-android-sandbox
```

Expected: all tests pass.

**Step 5: Commit**

```bash
git add -A && git commit -m "feat: main.rs complete wiring — orchestrator, providers, task tool, REPL"
```

---

### Task 15: End-to-end smoke test (Ollama, marked `#[ignore]`)

**Files:**
- Create: `sandbox/amplifier-android-sandbox/tests/e2e_test.rs`

**Background:** This test requires a running Ollama instance. It is marked `#[ignore]`
so it does not run in CI unless explicitly enabled. Run it manually when Ollama is
available to verify the full stack.

**Step 1: Create `tests/e2e_test.rs`**

```rust
//! End-to-end integration tests for the sandbox binary.
//!
//! These tests require external services (Ollama) and are marked `#[ignore]`.
//!
//! Run manually with:
//!   cargo test -p amplifier-android-sandbox -- --ignored --nocapture

use std::process::Command;

/// Full stack smoke test: launch sandbox with Ollama, ask a simple question.
///
/// Requires: Ollama running on localhost:11434 with llama3.2 model loaded.
/// Start Ollama: `ollama serve` and `ollama pull llama3.2`
#[test]
#[ignore = "requires Ollama running on localhost:11434 with llama3.2 model"]
fn e2e_ollama_single_turn_says_hello() {
    let output = Command::new(env!("CARGO_BIN_EXE_amplifier-android-sandbox"))
        .args([
            "--provider", "ollama",
            "--model", "llama3.2",
            "--prompt", "Reply with exactly: HELLO_SANDBOX",
            "--vault", "/tmp/sandbox-e2e-test",
        ])
        .output()
        .expect("Failed to launch sandbox binary");

    let stdout = String::from_utf8_lossy(&output.stdout);
    let stderr = String::from_utf8_lossy(&output.stderr);

    println!("stdout: {stdout}");
    println!("stderr: {stderr}");

    assert!(
        output.status.success(),
        "sandbox exited with non-zero: {}",
        output.status
    );
    assert!(
        stdout.contains("HELLO_SANDBOX"),
        "expected 'HELLO_SANDBOX' in output, got:\n{stdout}"
    );
}

/// Verify android bash profile rejects sudo even in the sandbox binary.
#[test]
#[ignore = "requires Ollama running on localhost:11434 with llama3.2 model"]
fn e2e_android_bash_rejects_sudo_via_llm() {
    // This test asks the agent to run sudo — the bash tool should reject it.
    let output = Command::new(env!("CARGO_BIN_EXE_amplifier-android-sandbox"))
        .args([
            "--provider", "ollama",
            "--model", "llama3.2",
            "--prompt", "Run this bash command: sudo ls /root",
            "--vault", "/tmp/sandbox-e2e-test",
        ])
        .output()
        .expect("Failed to launch sandbox binary");

    let stdout = String::from_utf8_lossy(&output.stdout);
    println!("stdout: {stdout}");

    // The agent should report the command was blocked, not succeed silently
    assert!(
        stdout.contains("toybox") || stdout.contains("blocked") || stdout.contains("denied"),
        "Expected sandbox rejection message, got:\n{stdout}"
    );
}
```

**Step 2: Verify the test compiles (but does not run)**

```bash
cargo test -p amplifier-android-sandbox
```

Expected: all existing tests pass; the `#[ignore]` tests are listed as "ignored":
```
running N tests
test hooks::tests::registry_builds_without_panic ... ok
test sandbox::tests::apply_is_callable_on_all_platforms ... ok
test tools::tests::registry_has_nine_tools ... ok
test tools::tests::all_expected_tool_names_present ... ok
test e2e_ollama_single_turn_says_hello ... ignored
test e2e_android_bash_rejects_sudo_via_llm ... ignored
```

**Step 3: Run the ignored tests manually if Ollama is available**

```bash
# First: start Ollama and pull the model
ollama pull llama3.2

# Then run the ignored tests
cargo test -p amplifier-android-sandbox -- --ignored --nocapture
```

Expected (if Ollama is running):
```
test e2e_ollama_single_turn_says_hello ... ok
test e2e_android_bash_rejects_sudo_via_llm ... ok
```

**Step 4: Run the full workspace test suite one final time**

```bash
cd /Users/ken/workspace/amplifier-rust && cargo test --workspace
```

Expected: all non-`#[ignore]` tests pass. Zero failures.

**Step 5: Final commit**

```bash
git add -A && git commit -m "feat: Phase 3 complete — filesystem+bash+search crates, android-sandbox binary with landlock+seccomp"
```

---

## Verification Checklist

After completing all 15 tasks (10 + 5), verify:

| Check | Command | Expected |
|---|---|---|
| All workspace tests pass | `cargo test --workspace` | zero failures |
| Filesystem crate: 14+ tests | `cargo test -p amplifier-module-tool-filesystem` | all pass |
| Bash crate: android profile | `cargo test -p amplifier-module-tool-bash -- android_profile` | all pass |
| Bash crate: strict blocks rm -rf | `cargo test -p amplifier-module-tool-bash -- strict_profile` | all pass |
| Search crate: fallback grep | `cargo test -p amplifier-module-tool-search` | all pass |
| Binary compiles (macOS) | `cargo build -p amplifier-android-sandbox` | no landlock/seccomp errors |
| Binary `--help` works | `cargo run -p amplifier-android-sandbox -- --help` | shows all 6 flags |
| Binary tool count | `cargo test -p amplifier-android-sandbox` | `registry_has_nine_tools` passes |
| Sandbox no-op on macOS | `cargo run -p amplifier-android-sandbox -- --sandbox --prompt "hi" --provider ollama` | prints "Note: --sandbox has no effect" |

---

## Platform notes for Linux developers

If you are on Linux (including CI or Docker), two things change:

1. **Sandbox deps are compiled.** `landlock` and `libseccomp` must be installed:
   ```bash
   apt-get install -y libseccomp-dev   # Debian/Ubuntu
   dnf install -y libseccomp-devel     # Fedora/RHEL
   ```

2. **`#[cfg(target_os = "linux")]` tests are active.** The `apply_is_callable_on_all_platforms` test skips on Linux to avoid applying process-wide restrictions. To test the full sandbox path on Linux:
   ```bash
   cargo build -p amplifier-android-sandbox --release
   ./target/release/amplifier-android-sandbox --sandbox --provider ollama --prompt "hello"
   ```

3. **Building for Linux cross-compilation from macOS:**
   ```bash
   # Add the target
   rustup target add x86_64-unknown-linux-gnu

   # Build (requires linux-musl cross toolchain)
   cargo build -p amplifier-android-sandbox --target x86_64-unknown-linux-gnu
   ```
   Note: `libseccomp` is a C library — cross-compilation requires a sysroot. Use Docker for production Linux builds.
