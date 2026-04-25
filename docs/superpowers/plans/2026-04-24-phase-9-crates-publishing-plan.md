# Phase 9: crates.io Publishing Implementation Plan

> **Execution:** Use the `subagent-driven-development` workflow to implement this plan.
> **Plan Size:** This plan contains 30+ tasks. It is structured into six sub-phases (9A–9F). **The implementer should execute one sub-phase per session** to avoid timeouts. Each sub-phase ends in a clean commit.

**Goal:** Publish all 17 amplifier-rust library crates to crates.io under the `amplifier-module-*` and `amplifier-agent-*` naming convention, set up a tag-triggered GitHub Actions publish pipeline, and switch Vela's `amplifier-android` crate from git deps to versioned crates.io deps.

**Architecture:**
1. Harden every publishable crate's `Cargo.toml` with full crates.io metadata (description, license, repository, homepage, documentation, keywords, categories, readme).
2. Convert internal workspace `path = "..."` deps into combined `{ path = "...", version = "X.Y.Z" }` deps so cargo strips the path on publish.
3. Author a minimal but real `README.md` per crate.
4. Audit `pub` API and require `///` docs on every public item via a CI script.
5. Publish in topological tier order (Tier 1 has no internal workspace deps; Tier 2 depends on Tier 1) via tag-triggered GitHub Actions workflow using `CARGO_REGISTRY_TOKEN`.
6. Switch Vela's `amplifier-android` Cargo.toml from git deps to versioned deps and verify cross-compile + Android build still pass.

**Tech Stack:** Rust 2021, Cargo, crates.io, GitHub Actions, `act` (local Actions runner), `bash` verification scripts, Gradle (for final Vela check).

**Crate Inventory (17 publishable crates):**
- **Providers (4):** `amplifier-module-provider-anthropic`, `-openai`, `-gemini`, `-ollama`
- **Context (1):** `amplifier-module-context-simple`
- **Orchestrator (1):** `amplifier-module-orchestrator-loop-streaming`
- **Tools (8):** `amplifier-module-tool-filesystem`, `-bash`, `-web`, `-search`, `-task`, `-delegate`, `-skills`, `-todo`
- **Agent (2):** `amplifier-module-agent-runtime`, `amplifier-agent-foundation`
- **Session (1):** `amplifier-module-session-store` *(introduced in Phase 7; assumed present at Phase 9 start)*

> **Note on count discrepancy:** The original delegation said "16 crates" but enumerated 17. This plan publishes all 17 (the actual list).

**Sandbox is excluded:** `sandbox/amplifier-android-sandbox/Cargo.toml` already has `publish = false`. Leave it alone.

---

## Tier Map (Topological Publish Order)

**Tier 1 — Leaf crates (no internal workspace deps; only `amplifier-core` + crates.io deps):** publish in any order within the tier.
- `amplifier-module-context-simple`
- `amplifier-module-provider-anthropic`
- `amplifier-module-provider-openai`
- `amplifier-module-provider-gemini`
- `amplifier-module-provider-ollama`
- `amplifier-module-tool-bash`
- `amplifier-module-tool-filesystem`
- `amplifier-module-tool-search`
- `amplifier-module-tool-todo`
- `amplifier-module-tool-web`
- `amplifier-module-tool-task`
- `amplifier-module-agent-runtime` (no `amplifier-core` dep, just serde stack)
- `amplifier-module-session-store` *(verify in Phase 7 deliverable; if it depends on `context-simple`, it stays Tier 1 because that's also Tier 1)*

**Tier 2 — Crates that depend on Tier 1 only:** publish after all Tier 1 crates are live.
- `amplifier-module-tool-skills` (deps: `tool-task`)
- `amplifier-module-orchestrator-loop-streaming` (deps: `context-simple`, `tool-task`)
- `amplifier-module-tool-delegate` (deps: `tool-task`, `agent-runtime`)
- `amplifier-agent-foundation` (deps: `agent-runtime`)

There is **no Tier 3**: nothing in our workspace depends on a Tier 2 crate that isn't itself Tier 1.

---

## Pre-Phase 9 Blocker

### Task 0: Verify `amplifier-core` is published to crates.io

**Why this is a blocker:** `cargo publish` rejects any crate that has a `git = "..."` dependency. Our workspace currently uses:
```toml
amplifier-core = { git = "https://github.com/microsoft/amplifier-core", branch = "main" }
```
This means **no Phase 9 crate can publish until `amplifier-core` is on crates.io**.

**Files:**
- Read: `/Users/ken/workspace/amplifier-rust/Cargo.toml` (workspace root, line 24)

**Step 1: Check whether `amplifier-core` is already on crates.io**
Run: `cargo search amplifier-core --limit 5`
Expected: One of two outcomes —
  - **(a) Already published:** Output contains a line like `amplifier-core = "X.Y.Z"`. Note the version. Proceed to Step 2.
  - **(b) Not published:** Output is empty or only shows unrelated crates. **STOP.** This task cannot proceed until the upstream `microsoft/amplifier-core` repo publishes a release. File a tracking issue at `https://github.com/microsoft/amplifier-core/issues` titled "Publish to crates.io to unblock amplifier-rust Phase 9" and pause the entire plan.

**Step 2: Update workspace `Cargo.toml` to use the crates.io version**
Modify `/Users/ken/workspace/amplifier-rust/Cargo.toml` line 24 from:
```toml
amplifier-core = { git = "https://github.com/microsoft/amplifier-core", branch = "main" }
```
to (using the version found in Step 1):
```toml
amplifier-core = "X.Y.Z"   # replace with actual published version
```

**Step 3: Verify the workspace still builds**
Run: `cd /Users/ken/workspace/amplifier-rust && cargo build --workspace`
Expected: Successful build, no errors. If new compile errors appear, the published `amplifier-core` API differs from `main` — file an issue and pause.

**Step 4: Run all tests**
Run: `cargo test --workspace`
Expected: All tests pass.

**Step 5: Commit**
`git add Cargo.toml Cargo.lock && git commit -m "build: switch amplifier-core from git to crates.io for Phase 9"`

---

### Task 0.5: Add a `[workspace.package]` shared metadata block

**Why:** Most crates.io fields (license, repository, homepage, authors, edition) are identical across all 17 crates. Cargo supports a `[workspace.package]` section that all member crates can inherit from via `package.field.workspace = true`. This avoids duplicating 8 fields × 17 crates = 136 lines of Cargo.toml.

**Files:**
- Modify: `/Users/ken/workspace/amplifier-rust/Cargo.toml`

**Step 1: Add the `[workspace.package]` block**
Insert this block after the closing `]` of `members = [ ... ]` (around line 22) and before `[workspace.dependencies]`:
```toml
[workspace.package]
version = "0.1.0"
edition = "2021"
license = "MIT"
repository = "https://github.com/kenotron-ms/amplifier-rust"
homepage = "https://github.com/kenotron-ms/amplifier-rust"
authors = ["Amplifier Contributors"]
rust-version = "1.75"
```

**Step 2: Add version pinning to internal workspace dependencies**
Update lines 37–38 of the workspace `Cargo.toml`. Change from:
```toml
amplifier-module-agent-runtime = { path = "crates/amplifier-module-agent-runtime" }
amplifier-agent-foundation = { path = "amplifier-agent-foundation" }
```
to:
```toml
amplifier-module-agent-runtime = { path = "crates/amplifier-module-agent-runtime", version = "0.1.0" }
amplifier-agent-foundation = { path = "amplifier-agent-foundation", version = "0.1.0" }
```

**Step 3: Build to confirm nothing broke**
Run: `cargo build --workspace`
Expected: Successful build.

**Step 4: Commit**
`git add Cargo.toml && git commit -m "build: add [workspace.package] metadata + version-pin internal deps"`

---

# Phase 9A — Tier 1 Cargo.toml Hardening + READMEs (Tasks 1–13)

For each Tier 1 crate, this section has one task. Each task follows the same recipe:
1. Rewrite `Cargo.toml` to inherit from `[workspace.package]` and add crates.io fields.
2. Create a `README.md` with a fixed structure.
3. Run `cargo doc --no-deps -p <crate>` to confirm docs build clean.
4. Run `cargo publish --dry-run -p <crate>` to confirm crates.io would accept it (skip this on Tier 1 crates that depend on `amplifier-module-tool-task` until Tier 1 is fully published — but in Tier 1 nothing depends on another workspace crate, so all dry-runs work standalone here).
5. Commit.

**Shared README template** (replace `{{NAME}}`, `{{ONE_LINER}}`, `{{LONGER_DESCRIPTION}}`, `{{USAGE_EXAMPLE}}`):

```markdown
# {{NAME}}

{{ONE_LINER}}

Part of the [`amplifier-rust`](https://github.com/kenotron-ms/amplifier-rust) workspace — a Rust implementation of the Amplifier agent framework, used as the embedded runtime in the [Vela](https://github.com/kenotron-ms/vela) Android assistant.

## What it does

{{LONGER_DESCRIPTION}}

## Usage

```rust
{{USAGE_EXAMPLE}}
```

## Documentation

Full API docs: <https://docs.rs/{{NAME}}>

## License

MIT — see the [workspace LICENSE](https://github.com/kenotron-ms/amplifier-rust/blob/main/LICENSE).
```

---

### Task 1: Harden `amplifier-module-context-simple`

**Files:**
- Modify: `/Users/ken/workspace/amplifier-rust/crates/amplifier-module-context-simple/Cargo.toml`
- Create: `/Users/ken/workspace/amplifier-rust/crates/amplifier-module-context-simple/README.md`

**Step 1: Rewrite `Cargo.toml`**
Replace the entire file with:
```toml
[package]
name = "amplifier-module-context-simple"
description = "Simple context-window manager with tiktoken-based truncation for the Amplifier agent framework"
readme = "README.md"
keywords = ["amplifier", "ai", "llm", "agent", "context"]
categories = ["asynchronous", "api-bindings"]
documentation = "https://docs.rs/amplifier-module-context-simple"
version.workspace = true
edition.workspace = true
license.workspace = true
repository.workspace = true
homepage.workspace = true
authors.workspace = true
rust-version.workspace = true

[dependencies]
amplifier-core = { workspace = true }
serde = { workspace = true }
serde_json = { workspace = true }
async-trait = { workspace = true }
anyhow = { workspace = true }
thiserror = { workspace = true }
tiktoken-rs = { workspace = true }

[dev-dependencies]
tokio = { workspace = true }
```

**Step 2: Create `README.md`** using the shared template with:
- `{{NAME}}` = `amplifier-module-context-simple`
- `{{ONE_LINER}}` = "Simple context-window manager with tiktoken-based truncation."
- `{{LONGER_DESCRIPTION}}` = "Implements the `ContextManager` trait from `amplifier-core`. Maintains a rolling conversation history, counts tokens with `tiktoken-rs`, and truncates from the head when the configured limit is exceeded — preserving the system message."
- `{{USAGE_EXAMPLE}}` = (use real public API; verify by running `cargo doc --open -p amplifier-module-context-simple` first):
```rust
use amplifier_module_context_simple::SimpleContext;
let ctx = SimpleContext::new(8192); // max tokens
```

**Step 3: Verify docs build**
Run: `cd /Users/ken/workspace/amplifier-rust && cargo doc --no-deps -p amplifier-module-context-simple 2>&1 | tee /tmp/doc-context.log`
Expected: Exit code 0; no `warning:` lines in `/tmp/doc-context.log`. If warnings exist, add `///` docs to every offending public item before continuing.

**Step 4: Dry-run publish**
Run: `cargo publish --dry-run -p amplifier-module-context-simple`
Expected: `Finished` line, no `error:` lines. Common failures and fixes:
- `error: missing field 'description'` → Step 1 was incomplete.
- `error: dependencies must have a version specified` → some workspace dep is unversioned. Check `[workspace.dependencies]`.
- `error: only path or git dependency specified, missing version` → still pulling git `amplifier-core`. Task 0 was skipped.

**Step 5: Commit**
`git add crates/amplifier-module-context-simple && git commit -m "build(context-simple): crates.io metadata + README"`

---

### Task 2: Harden `amplifier-module-provider-anthropic`

**Files:**
- Modify: `/Users/ken/workspace/amplifier-rust/crates/amplifier-module-provider-anthropic/Cargo.toml`
- Create: `/Users/ken/workspace/amplifier-rust/crates/amplifier-module-provider-anthropic/README.md`

**Step 1: Rewrite `Cargo.toml`**
```toml
[package]
name = "amplifier-module-provider-anthropic"
description = "Anthropic Messages API provider (Claude models) for the Amplifier agent framework"
readme = "README.md"
keywords = ["amplifier", "ai", "anthropic", "claude", "llm"]
categories = ["api-bindings", "asynchronous"]
documentation = "https://docs.rs/amplifier-module-provider-anthropic"
version.workspace = true
edition.workspace = true
license.workspace = true
repository.workspace = true
homepage.workspace = true
authors.workspace = true
rust-version.workspace = true

[dependencies]
amplifier-core = { workspace = true }
serde = { workspace = true }
serde_json = { workspace = true }
async-trait = { workspace = true }
anyhow = { workspace = true }
thiserror = { workspace = true }
reqwest = { workspace = true }
tokio = { workspace = true }
futures = { workspace = true }

[dev-dependencies]
tokio = { workspace = true }
```

**Step 2: Create `README.md`** with:
- `{{ONE_LINER}}` = "Anthropic Messages API (Claude) provider for Amplifier."
- `{{LONGER_DESCRIPTION}}` = "Implements the `Provider` trait from `amplifier-core` against the Anthropic Messages API. Supports Claude 3.5 Sonnet, Haiku, and Opus models, with streaming via Server-Sent Events and tool-use round-trips."
- `{{USAGE_EXAMPLE}}` =
```rust
use amplifier_module_provider_anthropic::AnthropicProvider;
let provider = AnthropicProvider::new(std::env::var("ANTHROPIC_API_KEY")?, "claude-3-5-sonnet-20241022");
```

**Step 3:** `cargo doc --no-deps -p amplifier-module-provider-anthropic` — zero warnings.
**Step 4:** `cargo publish --dry-run -p amplifier-module-provider-anthropic` — passes.
**Step 5:** `git add crates/amplifier-module-provider-anthropic && git commit -m "build(provider-anthropic): crates.io metadata + README"`

---

### Task 3: Harden `amplifier-module-provider-openai`

**Files:**
- Modify: `/Users/ken/workspace/amplifier-rust/crates/amplifier-module-provider-openai/Cargo.toml`
- Create: `/Users/ken/workspace/amplifier-rust/crates/amplifier-module-provider-openai/README.md`

**Step 1: Rewrite `Cargo.toml`**

Note: The current file has non-workspace versions of `serde`, `serde_json`, and `tokio`. Standardize on workspace deps where possible. Keep the explicit `reqwest` line because it pins a specific feature set (`rustls-tls`).

```toml
[package]
name = "amplifier-module-provider-openai"
description = "OpenAI Responses API provider (GPT models) for the Amplifier agent framework"
readme = "README.md"
keywords = ["amplifier", "ai", "openai", "gpt", "llm"]
categories = ["api-bindings", "asynchronous"]
documentation = "https://docs.rs/amplifier-module-provider-openai"
version.workspace = true
edition.workspace = true
license.workspace = true
repository.workspace = true
homepage.workspace = true
authors.workspace = true
rust-version.workspace = true

[dependencies]
amplifier-core = { workspace = true }
reqwest = { version = "0.12", default-features = false, features = ["json", "rustls-tls", "stream"] }
serde = { workspace = true }
serde_json = { workspace = true }
tokio = { workspace = true }
log = "0.4"

[dev-dependencies]
wiremock = "0.6"
tokio = { workspace = true }
```

**Step 2: Create `README.md`** with:
- `{{ONE_LINER}}` = "OpenAI Responses API (GPT) provider for Amplifier."
- `{{LONGER_DESCRIPTION}}` = "Implements the `Provider` trait from `amplifier-core` against the OpenAI Responses API. Supports GPT-4o, GPT-4o-mini, and reasoning models, with streaming and tool calls."
- `{{USAGE_EXAMPLE}}` =
```rust
use amplifier_module_provider_openai::OpenAIProvider;
let provider = OpenAIProvider::new(std::env::var("OPENAI_API_KEY")?, "gpt-4o");
```

**Step 3–5:** `cargo doc --no-deps`, `cargo publish --dry-run`, commit:
`git commit -m "build(provider-openai): crates.io metadata + README"`

---

### Task 4: Harden `amplifier-module-provider-gemini`

**Files:**
- Modify: `/Users/ken/workspace/amplifier-rust/crates/amplifier-module-provider-gemini/Cargo.toml`
- Create: `/Users/ken/workspace/amplifier-rust/crates/amplifier-module-provider-gemini/README.md`

**Step 1: Rewrite `Cargo.toml`**
```toml
[package]
name = "amplifier-module-provider-gemini"
description = "Google Gemini Developer API provider for the Amplifier agent framework"
readme = "README.md"
keywords = ["amplifier", "ai", "gemini", "google", "llm"]
categories = ["api-bindings", "asynchronous"]
documentation = "https://docs.rs/amplifier-module-provider-gemini"
version.workspace = true
edition.workspace = true
license.workspace = true
repository.workspace = true
homepage.workspace = true
authors.workspace = true
rust-version.workspace = true

[dependencies]
amplifier-core = { workspace = true }
reqwest = { version = "0.12", default-features = false, features = ["json", "rustls-tls", "stream"] }
serde = { workspace = true }
serde_json = { workspace = true }
tokio = { workspace = true }
futures = { workspace = true }
uuid = { workspace = true }
log = "0.4"

[dev-dependencies]
wiremock = "0.6"
tokio = { workspace = true }
```

**Step 2: README** — `{{ONE_LINER}}` = "Google Gemini Developer API provider for Amplifier."; `{{LONGER_DESCRIPTION}}` = "Implements the `Provider` trait from `amplifier-core` against the Gemini Developer API. Supports `gemini-1.5-pro`, `gemini-1.5-flash`, and `gemini-2.0-flash` with streaming."; usage example modeled on the OpenAI one.

**Step 3–5:** Doc check, dry-run, commit `build(provider-gemini): crates.io metadata + README`.

---

### Task 5: Harden `amplifier-module-provider-ollama`

**Files:**
- Modify: `/Users/ken/workspace/amplifier-rust/crates/amplifier-module-provider-ollama/Cargo.toml`
- Create: `/Users/ken/workspace/amplifier-rust/crates/amplifier-module-provider-ollama/README.md`

**Step 1: Rewrite `Cargo.toml`**
```toml
[package]
name = "amplifier-module-provider-ollama"
description = "Ollama / OpenAI-compatible ChatCompletions provider for the Amplifier agent framework"
readme = "README.md"
keywords = ["amplifier", "ai", "ollama", "local", "llm"]
categories = ["api-bindings", "asynchronous"]
documentation = "https://docs.rs/amplifier-module-provider-ollama"
version.workspace = true
edition.workspace = true
license.workspace = true
repository.workspace = true
homepage.workspace = true
authors.workspace = true
rust-version.workspace = true

[dependencies]
amplifier-core = { workspace = true }
reqwest = { version = "0.12", default-features = false, features = ["json", "rustls-tls", "stream"] }
serde = { workspace = true }
serde_json = { workspace = true }
tokio = { workspace = true }
futures = { workspace = true }
log = "0.4"

[dev-dependencies]
wiremock = "0.6"
tokio = { workspace = true }
```

**Step 2: README** — `{{ONE_LINER}}` = "Ollama / OpenAI-compatible local-LLM provider for Amplifier."; `{{LONGER_DESCRIPTION}}` = "Implements the `Provider` trait via the OpenAI-compatible ChatCompletions endpoint exposed by Ollama, llama.cpp's `llama-server`, vLLM, and similar runtimes. Defaults to `http://localhost:11434`."

**Step 3–5:** Doc check, dry-run, commit `build(provider-ollama): crates.io metadata + README`.

---

### Task 6: Harden `amplifier-module-tool-bash`

**Files:**
- Modify: `/Users/ken/workspace/amplifier-rust/crates/amplifier-module-tool-bash/Cargo.toml`
- Create: `/Users/ken/workspace/amplifier-rust/crates/amplifier-module-tool-bash/README.md`

**Step 1: Rewrite `Cargo.toml`**
```toml
[package]
name = "amplifier-module-tool-bash"
description = "Sandboxed bash command execution tool for the Amplifier agent framework"
readme = "README.md"
keywords = ["amplifier", "ai", "agent", "tool", "bash"]
categories = ["command-line-utilities", "asynchronous"]
documentation = "https://docs.rs/amplifier-module-tool-bash"
version.workspace = true
edition.workspace = true
license.workspace = true
repository.workspace = true
homepage.workspace = true
authors.workspace = true
rust-version.workspace = true

[dependencies]
amplifier-core = { workspace = true }
tokio = { workspace = true }
serde_json = { workspace = true }

[dev-dependencies]
tokio = { workspace = true }
```

**Step 2: README** — `{{ONE_LINER}}` = "Sandboxed bash command execution tool for Amplifier agents."; describe vault-root scoping, timeout enforcement, and platform-conditional sandboxing.

**Step 3–5:** Doc check, dry-run, commit `build(tool-bash): crates.io metadata + README`.

---

### Task 7: Harden `amplifier-module-tool-filesystem`

**Files:**
- Modify: `/Users/ken/workspace/amplifier-rust/crates/amplifier-module-tool-filesystem/Cargo.toml`
- Create: `/Users/ken/workspace/amplifier-rust/crates/amplifier-module-tool-filesystem/README.md`

**Step 1: Rewrite `Cargo.toml`**
This crate already has `description` and `license` set explicitly — replace those lines too so everything inherits from workspace.
```toml
[package]
name = "amplifier-module-tool-filesystem"
description = "Vault-root-scoped filesystem tools (Read, Write, Edit, Glob) for the Amplifier agent framework"
readme = "README.md"
keywords = ["amplifier", "ai", "agent", "tool", "filesystem"]
categories = ["filesystem", "asynchronous"]
documentation = "https://docs.rs/amplifier-module-tool-filesystem"
version.workspace = true
edition.workspace = true
license.workspace = true
repository.workspace = true
homepage.workspace = true
authors.workspace = true
rust-version.workspace = true

[dependencies]
amplifier-core = { workspace = true }
tokio = { workspace = true }
serde_json = { workspace = true }
glob = "0.3"
regex = "1"
walkdir = "2"

[dev-dependencies]
tokio = { workspace = true }
tempfile = "3"
```

**Step 2: README** — `{{ONE_LINER}}` = "Vault-root-scoped Read/Write/Edit/Glob filesystem tools for Amplifier."

**Step 3–5:** Doc check, dry-run, commit `build(tool-filesystem): crates.io metadata + README`.

---

### Task 8: Harden `amplifier-module-tool-search`

**Files:**
- Modify: `/Users/ken/workspace/amplifier-rust/crates/amplifier-module-tool-search/Cargo.toml`
- Create: `/Users/ken/workspace/amplifier-rust/crates/amplifier-module-tool-search/README.md`

**Step 1: Rewrite `Cargo.toml`**
```toml
[package]
name = "amplifier-module-tool-search"
description = "Vault-root-scoped grep/find search tool for the Amplifier agent framework"
readme = "README.md"
keywords = ["amplifier", "ai", "agent", "tool", "search"]
categories = ["filesystem", "asynchronous"]
documentation = "https://docs.rs/amplifier-module-tool-search"
version.workspace = true
edition.workspace = true
license.workspace = true
repository.workspace = true
homepage.workspace = true
authors.workspace = true
rust-version.workspace = true

[dependencies]
amplifier-core = { workspace = true }
tokio = { workspace = true }
serde_json = { workspace = true }
regex = "1"
walkdir = "2"
glob = "0.3"

[dev-dependencies]
tokio = { workspace = true }
tempfile = "3"
```

**Step 2: README** — `{{ONE_LINER}}` = "Vault-root-scoped grep/find tool for Amplifier."

**Step 3–5:** Doc check, dry-run, commit `build(tool-search): crates.io metadata + README`.

---

### Task 9: Harden `amplifier-module-tool-todo`

**Files:**
- Modify: `/Users/ken/workspace/amplifier-rust/crates/amplifier-module-tool-todo/Cargo.toml`
- Create: `/Users/ken/workspace/amplifier-rust/crates/amplifier-module-tool-todo/README.md`

**Step 1: Rewrite `Cargo.toml`**
```toml
[package]
name = "amplifier-module-tool-todo"
description = "Persistent todo-list tool for the Amplifier agent framework"
readme = "README.md"
keywords = ["amplifier", "ai", "agent", "tool", "todo"]
categories = ["asynchronous"]
documentation = "https://docs.rs/amplifier-module-tool-todo"
version.workspace = true
edition.workspace = true
license.workspace = true
repository.workspace = true
homepage.workspace = true
authors.workspace = true
rust-version.workspace = true

[dependencies]
amplifier-core = { workspace = true }
serde = { workspace = true }
serde_json = { workspace = true }
tokio = { workspace = true }
uuid = { workspace = true }
log = "0.4"

[dev-dependencies]
tokio = { workspace = true }
```

**Step 2: README** — `{{ONE_LINER}}` = "Per-session todo-list tool for Amplifier agents."

**Step 3–5:** Doc check, dry-run, commit `build(tool-todo): crates.io metadata + README`.

---

### Task 10: Harden `amplifier-module-tool-web`

**Files:**
- Modify: `/Users/ken/workspace/amplifier-rust/crates/amplifier-module-tool-web/Cargo.toml`
- Create: `/Users/ken/workspace/amplifier-rust/crates/amplifier-module-tool-web/README.md`

**Step 1: Rewrite `Cargo.toml`**
```toml
[package]
name = "amplifier-module-tool-web"
description = "Web fetch and search tools for the Amplifier agent framework"
readme = "README.md"
keywords = ["amplifier", "ai", "agent", "tool", "web"]
categories = ["web-programming::http-client", "asynchronous"]
documentation = "https://docs.rs/amplifier-module-tool-web"
version.workspace = true
edition.workspace = true
license.workspace = true
repository.workspace = true
homepage.workspace = true
authors.workspace = true
rust-version.workspace = true

[dependencies]
amplifier-core = { workspace = true }
reqwest = { version = "0.12", default-features = false, features = ["rustls-tls"] }
serde = { workspace = true }
serde_json = { workspace = true }
tokio = { workspace = true }
regex = "1"
urlencoding = "2"
log = "0.4"

[dev-dependencies]
wiremock = "0.6"
tokio = { workspace = true }
```

**Step 2: README** — `{{ONE_LINER}}` = "WebFetch and WebSearch tools for Amplifier agents."

**Step 3–5:** Doc check, dry-run, commit `build(tool-web): crates.io metadata + README`.

---

### Task 11: Harden `amplifier-module-tool-task`

**Files:**
- Modify: `/Users/ken/workspace/amplifier-rust/crates/amplifier-module-tool-task/Cargo.toml`
- Create: `/Users/ken/workspace/amplifier-rust/crates/amplifier-module-tool-task/README.md`

**Step 1: Rewrite `Cargo.toml`**
```toml
[package]
name = "amplifier-module-tool-task"
description = "Task primitive (subagent invocation) for the Amplifier agent framework"
readme = "README.md"
keywords = ["amplifier", "ai", "agent", "tool", "subagent"]
categories = ["asynchronous"]
documentation = "https://docs.rs/amplifier-module-tool-task"
version.workspace = true
edition.workspace = true
license.workspace = true
repository.workspace = true
homepage.workspace = true
authors.workspace = true
rust-version.workspace = true

[dependencies]
amplifier-core = { workspace = true }
serde = { workspace = true }
serde_json = { workspace = true }
async-trait = { workspace = true }
anyhow = { workspace = true }
thiserror = { workspace = true }
uuid = { workspace = true }

[dev-dependencies]
tokio = { workspace = true }
```

**Step 2: README** — `{{ONE_LINER}}` = "Task primitive — subagent dispatch for Amplifier."

**Step 3–5:** Doc check, dry-run, commit `build(tool-task): crates.io metadata + README`.

---

### Task 12: Harden `amplifier-module-agent-runtime`

**Files:**
- Modify: `/Users/ken/workspace/amplifier-rust/crates/amplifier-module-agent-runtime/Cargo.toml`
- Create: `/Users/ken/workspace/amplifier-rust/crates/amplifier-module-agent-runtime/README.md`

**Step 1: Rewrite `Cargo.toml`**
```toml
[package]
name = "amplifier-module-agent-runtime"
description = "Agent definition loader and runtime registry for the Amplifier agent framework"
readme = "README.md"
keywords = ["amplifier", "ai", "agent", "runtime", "registry"]
categories = ["asynchronous", "config"]
documentation = "https://docs.rs/amplifier-module-agent-runtime"
version.workspace = true
edition.workspace = true
license.workspace = true
repository.workspace = true
homepage.workspace = true
authors.workspace = true
rust-version.workspace = true

[dependencies]
serde = { workspace = true }
serde_json = { workspace = true }
serde_yaml = { workspace = true }
anyhow = { workspace = true }

[dev-dependencies]
tempfile = "3"
```

**Step 2: README** — `{{ONE_LINER}}` = "Agent loader & registry for Amplifier."; `{{LONGER_DESCRIPTION}}` = "Reads YAML/Markdown agent definitions from disk, validates them, and exposes a registry that the Task and Delegate tools use to look up subagents by name or path."

**Step 3–5:** Doc check, dry-run, commit `build(agent-runtime): crates.io metadata + README`.

---

### Task 13: Harden `amplifier-module-session-store`

> **Verification first:** This crate is added in Phase 7. Before starting, confirm it exists at `/Users/ken/workspace/amplifier-rust/crates/amplifier-module-session-store/`. If not, **STOP** and complete Phase 7 first.

**Files:**
- Modify: `/Users/ken/workspace/amplifier-rust/crates/amplifier-module-session-store/Cargo.toml`
- Create: `/Users/ken/workspace/amplifier-rust/crates/amplifier-module-session-store/README.md`

**Step 1: Inspect existing `Cargo.toml`**
Run: `cat /Users/ken/workspace/amplifier-rust/crates/amplifier-module-session-store/Cargo.toml`
Note any internal workspace deps (especially `amplifier-module-context-simple`).

**Step 2: Rewrite `Cargo.toml`** following the Tier 1 pattern:
```toml
[package]
name = "amplifier-module-session-store"
description = "Persistent session storage with vault-rooted JSON serialization for the Amplifier agent framework"
readme = "README.md"
keywords = ["amplifier", "ai", "agent", "session", "storage"]
categories = ["filesystem", "asynchronous"]
documentation = "https://docs.rs/amplifier-module-session-store"
version.workspace = true
edition.workspace = true
license.workspace = true
repository.workspace = true
homepage.workspace = true
authors.workspace = true
rust-version.workspace = true

[dependencies]
# Copy the original [dependencies] block as-is, BUT for any workspace-internal
# path dep (e.g. amplifier-module-context-simple), convert from
#   amplifier-module-context-simple = { path = "../amplifier-module-context-simple" }
# to
#   amplifier-module-context-simple = { path = "../amplifier-module-context-simple", version = "0.1.0" }

[dev-dependencies]
# Copy original
```

**Step 3: README** — `{{ONE_LINER}}` = "Persistent session storage for Amplifier."

**Step 4–6:** Doc check, dry-run (will fail if `context-simple` is referenced and not yet on crates.io — that's expected; the dry-run for crates with internal deps must run **after** Tier 1 publish; mark this with a note in the commit), commit `build(session-store): crates.io metadata + README`.

---

### Task 14: Phase 9A acceptance gate

**Step 1: Run all Tier 1 dry-runs in sequence**
```bash
cd /Users/ken/workspace/amplifier-rust
for crate in \
  amplifier-module-context-simple \
  amplifier-module-provider-anthropic \
  amplifier-module-provider-openai \
  amplifier-module-provider-gemini \
  amplifier-module-provider-ollama \
  amplifier-module-tool-bash \
  amplifier-module-tool-filesystem \
  amplifier-module-tool-search \
  amplifier-module-tool-todo \
  amplifier-module-tool-web \
  amplifier-module-tool-task \
  amplifier-module-agent-runtime \
  amplifier-module-session-store ; do
  echo "=== $crate ==="
  cargo publish --dry-run -p "$crate" || { echo "FAIL: $crate"; exit 1; }
done
echo "ALL TIER 1 DRY-RUNS PASS"
```
Expected: Final line is `ALL TIER 1 DRY-RUNS PASS`. If any crate fails, fix and re-run from that crate.

**Step 2: Workspace doc build clean**
Run: `cargo doc --workspace --no-deps 2>&1 | tee /tmp/doc-all.log; grep -c "^warning" /tmp/doc-all.log`
Expected: `0`

**Step 3: All doc tests pass**
Run: `cargo test --workspace --doc`
Expected: All pass.

**Step 4: Commit + tag a checkpoint**
`git tag phase-9a-complete && git push origin phase-9a-complete`

---

# Phase 9B — Tier 2 Cargo.toml Hardening + READMEs (Tasks 15–18)

### Task 15: Harden `amplifier-module-tool-skills`

**Files:**
- Modify: `/Users/ken/workspace/amplifier-rust/crates/amplifier-module-tool-skills/Cargo.toml`
- Create: `/Users/ken/workspace/amplifier-rust/crates/amplifier-module-tool-skills/README.md`

**Step 1: Rewrite `Cargo.toml`**
Note the conversion of the internal `tool-task` path dep — it now needs a `version` alongside.
```toml
[package]
name = "amplifier-module-tool-skills"
description = "Skill loader and dispatch tool for the Amplifier agent framework"
readme = "README.md"
keywords = ["amplifier", "ai", "agent", "tool", "skills"]
categories = ["asynchronous"]
documentation = "https://docs.rs/amplifier-module-tool-skills"
version.workspace = true
edition.workspace = true
license.workspace = true
repository.workspace = true
homepage.workspace = true
authors.workspace = true
rust-version.workspace = true

[dependencies]
amplifier-core = { workspace = true }
amplifier-module-tool-task = { path = "../amplifier-module-tool-task", version = "0.1.0" }
serde = { workspace = true }
serde_json = { workspace = true }
serde_yaml = { workspace = true }
async-trait = { workspace = true }
anyhow = { workspace = true }
thiserror = { workspace = true }

[dev-dependencies]
tempfile = "3"
tokio = { workspace = true }
```

**Step 2: README** — `{{ONE_LINER}}` = "Skill loader & dispatch tool for Amplifier."

**Step 3:** `cargo doc --no-deps -p amplifier-module-tool-skills` → 0 warnings.
**Step 4:** `cargo publish --dry-run -p amplifier-module-tool-skills`.
> Expected to **succeed** on dry-run because dry-run uses the local `path =` resolution. It will only need crates.io `tool-task` at *real* publish time.

**Step 5:** `git commit -m "build(tool-skills): crates.io metadata + README"`

---

### Task 16: Harden `amplifier-module-orchestrator-loop-streaming`

**Files:**
- Modify: `/Users/ken/workspace/amplifier-rust/crates/amplifier-module-orchestrator-loop-streaming/Cargo.toml`
- Create: `/Users/ken/workspace/amplifier-rust/crates/amplifier-module-orchestrator-loop-streaming/README.md`

**Step 1: Rewrite `Cargo.toml`**
```toml
[package]
name = "amplifier-module-orchestrator-loop-streaming"
description = "Streaming agent-loop orchestrator with tool-call interleaving for the Amplifier agent framework"
readme = "README.md"
keywords = ["amplifier", "ai", "agent", "orchestrator", "streaming"]
categories = ["asynchronous"]
documentation = "https://docs.rs/amplifier-module-orchestrator-loop-streaming"
version.workspace = true
edition.workspace = true
license.workspace = true
repository.workspace = true
homepage.workspace = true
authors.workspace = true
rust-version.workspace = true

[dependencies]
amplifier-core = { workspace = true }
amplifier-module-context-simple = { path = "../amplifier-module-context-simple", version = "0.1.0" }
amplifier-module-tool-task = { path = "../amplifier-module-tool-task", version = "0.1.0" }
serde = { workspace = true }
serde_json = { workspace = true }
async-trait = { workspace = true }
anyhow = { workspace = true }
thiserror = { workspace = true }
tokio = { workspace = true }

[dev-dependencies]
tokio = { workspace = true }
```

**Step 2: README** — `{{ONE_LINER}}` = "Streaming agent-loop orchestrator for Amplifier."; `{{LONGER_DESCRIPTION}}` = "Implements `Orchestrator` from `amplifier-core`. Drives the prompt → provider stream → tool-call → tool-result loop, emitting events for UI consumers and stopping on `stop_reason = end_turn`."

**Step 3–5:** Doc check, dry-run, commit `build(orchestrator-loop-streaming): crates.io metadata + README`.

---

### Task 17: Harden `amplifier-module-tool-delegate`

**Files:**
- Modify: `/Users/ken/workspace/amplifier-rust/crates/amplifier-module-tool-delegate/Cargo.toml`
- Create: `/Users/ken/workspace/amplifier-rust/crates/amplifier-module-tool-delegate/README.md`

**Step 1: Rewrite `Cargo.toml`**
```toml
[package]
name = "amplifier-module-tool-delegate"
description = "Hierarchical agent delegation tool with context inheritance for the Amplifier agent framework"
readme = "README.md"
keywords = ["amplifier", "ai", "agent", "tool", "delegate"]
categories = ["asynchronous"]
documentation = "https://docs.rs/amplifier-module-tool-delegate"
version.workspace = true
edition.workspace = true
license.workspace = true
repository.workspace = true
homepage.workspace = true
authors.workspace = true
rust-version.workspace = true

[dependencies]
amplifier-core = { workspace = true }
amplifier-module-tool-task = { path = "../amplifier-module-tool-task", version = "0.1.0" }
amplifier-module-agent-runtime = { workspace = true }
serde = { workspace = true }
serde_json = { workspace = true }
async-trait = { workspace = true }
anyhow = { workspace = true }
rand = { workspace = true }

[dev-dependencies]
tokio = { workspace = true }
async-trait = { workspace = true }
```

**Step 2: README** — `{{ONE_LINER}}` = "Hierarchical delegation tool for Amplifier."; `{{LONGER_DESCRIPTION}}` = "Builds on `amplifier-module-tool-task` to add depth-tracked subagent invocation with optional context inheritance and namespace-scoped name resolution (`self`, `namespace:path`, registry)."

**Step 3–5:** Doc check, dry-run, commit `build(tool-delegate): crates.io metadata + README`.

---

### Task 18: Harden `amplifier-agent-foundation`

**Files:**
- Modify: `/Users/ken/workspace/amplifier-rust/amplifier-agent-foundation/Cargo.toml`
- Create: `/Users/ken/workspace/amplifier-rust/amplifier-agent-foundation/README.md`

**Step 1: Rewrite `Cargo.toml`**
```toml
[package]
name = "amplifier-agent-foundation"
description = "Foundation agent definitions (system prompts and tool bundles) for the Amplifier agent framework"
readme = "README.md"
keywords = ["amplifier", "ai", "agent", "foundation", "prompt"]
categories = ["asynchronous"]
documentation = "https://docs.rs/amplifier-agent-foundation"
version.workspace = true
edition.workspace = true
license.workspace = true
repository.workspace = true
homepage.workspace = true
authors.workspace = true
rust-version.workspace = true

[dependencies]
amplifier-module-agent-runtime = { workspace = true }
```

**Step 2: README** — `{{ONE_LINER}}` = "Stock 'foundation' agent definitions for Amplifier."; `{{LONGER_DESCRIPTION}}` = "Provides the canonical foundation system prompts, tool bundles, and agent definitions used by Amplifier hosts that don't ship their own."

**Step 3–5:** Doc check, dry-run, commit `build(agent-foundation): crates.io metadata + README`.

---

# Phase 9C — Verification Infrastructure (Tasks 19–21)

### Task 19: Create `scripts/check-pub-api.sh`

**Files:**
- Create: `/Users/ken/workspace/amplifier-rust/scripts/check-pub-api.sh`

**Step 1: Create the script**
```bash
#!/usr/bin/env bash
# scripts/check-pub-api.sh
# Fails (exit 1) if any public item in any publishable crate lacks a /// doc comment.
# Used by CI before publish.

set -euo pipefail

CRATES=(
  amplifier-module-context-simple
  amplifier-module-provider-anthropic
  amplifier-module-provider-openai
  amplifier-module-provider-gemini
  amplifier-module-provider-ollama
  amplifier-module-tool-bash
  amplifier-module-tool-filesystem
  amplifier-module-tool-search
  amplifier-module-tool-todo
  amplifier-module-tool-web
  amplifier-module-tool-task
  amplifier-module-tool-skills
  amplifier-module-tool-delegate
  amplifier-module-orchestrator-loop-streaming
  amplifier-module-agent-runtime
  amplifier-module-session-store
  amplifier-agent-foundation
)

# Resolve crate -> source dir
crate_path() {
  case "$1" in
    amplifier-agent-foundation) echo "amplifier-agent-foundation/src" ;;
    *) echo "crates/$1/src" ;;
  esac
}

fail=0
for crate in "${CRATES[@]}"; do
  src="$(crate_path "$crate")"
  if [[ ! -d "$src" ]]; then
    echo "::warning:: skipping $crate (no src dir at $src)"
    continue
  fi

  echo "=== $crate ==="

  # 1. Build doc with -D rustdoc::missing_docs treated as warnings -> errors
  RUSTDOCFLAGS="-D missing-docs" \
    cargo doc --no-deps -p "$crate" --lib --quiet 2>/tmp/doc-err.log || {
      echo "DOC FAIL: $crate"
      cat /tmp/doc-err.log
      fail=1
      continue
    }

  # 2. Sanity check via grep — any public item line without /// on the prior line?
  #    (rustdoc above is the source of truth; this is a belt-and-braces probe.)
  awk '
    /^[[:space:]]*\/\/\// { has_doc = 1; next }
    /^[[:space:]]*pub (fn|struct|enum|trait|mod|const|static|type) / {
      if (!has_doc) {
        print FILENAME ":" NR ": undocumented: " $0
        bad = 1
      }
      has_doc = 0; next
    }
    { has_doc = 0 }
    END { exit bad }
  ' "$src"/**/*.rs "$src"/*.rs 2>/dev/null || {
    echo "AWK PROBE FAIL: $crate"
    fail=1
  }
done

if [[ $fail -ne 0 ]]; then
  echo
  echo "FAIL: undocumented public API in one or more crates"
  exit 1
fi
echo
echo "PASS: all public API documented across ${#CRATES[@]} crates"
```

**Step 2: Make executable**
Run: `chmod +x /Users/ken/workspace/amplifier-rust/scripts/check-pub-api.sh`

**Step 3: Run it**
Run: `cd /Users/ken/workspace/amplifier-rust && ./scripts/check-pub-api.sh`
Expected: Final line `PASS: all public API documented across 17 crates`. If it fails, add `///` doc comments to the offending items in each named source location, then re-run.

**Step 4: Commit**
`git add scripts/check-pub-api.sh && git commit -m "ci: scripts/check-pub-api.sh — fail on undocumented pub items"`

---

### Task 20: Workspace-wide doc and dry-run gate

**Files:**
- Create: `/Users/ken/workspace/amplifier-rust/scripts/dryrun-all.sh`

**Step 1: Create the script**
```bash
#!/usr/bin/env bash
# scripts/dryrun-all.sh — runs cargo publish --dry-run for every publishable crate
# in topological tier order. CI uses this as a publish gate.
set -euo pipefail

TIER1=(
  amplifier-module-context-simple
  amplifier-module-provider-anthropic
  amplifier-module-provider-openai
  amplifier-module-provider-gemini
  amplifier-module-provider-ollama
  amplifier-module-tool-bash
  amplifier-module-tool-filesystem
  amplifier-module-tool-search
  amplifier-module-tool-todo
  amplifier-module-tool-web
  amplifier-module-tool-task
  amplifier-module-agent-runtime
  amplifier-module-session-store
)

TIER2=(
  amplifier-module-tool-skills
  amplifier-module-orchestrator-loop-streaming
  amplifier-module-tool-delegate
  amplifier-agent-foundation
)

run_tier() {
  local label="$1"; shift
  echo "=== $label ==="
  for c in "$@"; do
    echo "--- $c ---"
    cargo publish --dry-run -p "$c"
  done
}

run_tier "TIER 1" "${TIER1[@]}"
run_tier "TIER 2" "${TIER2[@]}"
echo "ALL DRY-RUNS PASS"
```

**Step 2: Make executable + run**
```
chmod +x scripts/dryrun-all.sh
./scripts/dryrun-all.sh
```
Expected: Last line `ALL DRY-RUNS PASS`.

**Step 3: Workspace doc-zero-warnings check**
Run:
```bash
cargo doc --workspace --no-deps 2>&1 | tee /tmp/doc-ws.log
test "$(grep -c '^warning' /tmp/doc-ws.log)" = "0"
```
Expected: Exit code 0 (no warnings).

**Step 4: Workspace doc tests**
Run: `cargo test --workspace --doc`
Expected: All pass.

**Step 5: Commit**
`git add scripts/dryrun-all.sh && git commit -m "ci: scripts/dryrun-all.sh — tier-ordered cargo publish dry-run"`

---

### Task 21: Phase 9C acceptance gate

**Step 1: Run the full triad**
```bash
cd /Users/ken/workspace/amplifier-rust
./scripts/check-pub-api.sh    # → PASS
./scripts/dryrun-all.sh       # → ALL DRY-RUNS PASS
cargo doc --workspace --no-deps 2>&1 | grep -c '^warning' | grep -qx 0
cargo test --workspace --doc  # → all pass
```

**Step 2: Tag checkpoint**
`git tag phase-9c-complete && git push origin phase-9c-complete`

---

# Phase 9D — CI/CD Publish Pipeline (Tasks 22–24)

### Task 22: Create `.github/workflows/publish.yml`

**Files:**
- Create: `/Users/ken/workspace/amplifier-rust/.github/workflows/publish.yml`

**Step 1: Write the workflow**
```yaml
name: Publish to crates.io

on:
  push:
    tags:
      - 'v*'        # e.g. v0.1.0, v0.2.0-rc.1

jobs:
  verify:
    name: Verify (build · test · clippy · fmt · docs · pub-api)
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Install Rust toolchain
        uses: dtolnay/rust-toolchain@stable
        with:
          components: clippy, rustfmt

      - name: Cache cargo
        uses: Swatinem/rust-cache@v2

      - run: cargo fmt --all -- --check
      - run: cargo clippy --workspace --all-targets -- -D warnings
      - run: cargo build --workspace
      - run: cargo test --workspace
      - run: cargo test --workspace --doc
      - run: ./scripts/check-pub-api.sh
      - run: ./scripts/dryrun-all.sh

  publish-tier-1:
    name: Publish Tier 1 (leaf crates)
    needs: verify
    runs-on: ubuntu-latest
    env:
      CARGO_REGISTRY_TOKEN: ${{ secrets.CARGO_REGISTRY_TOKEN }}
    steps:
      - uses: actions/checkout@v4
      - uses: dtolnay/rust-toolchain@stable
      - uses: Swatinem/rust-cache@v2

      # Each `cargo publish` blocks on the registry index update before returning,
      # but the index can lag a few seconds. The `sleep 15` between publishes
      # gives cdn.crates.io time to propagate so Tier 2 can resolve them.
      - name: Publish Tier 1 crates
        run: |
          set -euo pipefail
          for crate in \
            amplifier-module-context-simple \
            amplifier-module-provider-anthropic \
            amplifier-module-provider-openai \
            amplifier-module-provider-gemini \
            amplifier-module-provider-ollama \
            amplifier-module-tool-bash \
            amplifier-module-tool-filesystem \
            amplifier-module-tool-search \
            amplifier-module-tool-todo \
            amplifier-module-tool-web \
            amplifier-module-tool-task \
            amplifier-module-agent-runtime \
            amplifier-module-session-store ; do
              echo "=== publishing $crate ==="
              cargo publish -p "$crate" --token "$CARGO_REGISTRY_TOKEN"
              sleep 15
          done

  publish-tier-2:
    name: Publish Tier 2 (composed crates)
    needs: publish-tier-1
    runs-on: ubuntu-latest
    env:
      CARGO_REGISTRY_TOKEN: ${{ secrets.CARGO_REGISTRY_TOKEN }}
    steps:
      - uses: actions/checkout@v4
      - uses: dtolnay/rust-toolchain@stable
      - uses: Swatinem/rust-cache@v2

      # Tier 2 needs Tier 1 to be index-resolvable. Sleep 60s up front to let
      # the sparse index settle.
      - name: Wait for Tier 1 to land on crates.io index
        run: sleep 60

      - name: Publish Tier 2 crates
        run: |
          set -euo pipefail
          for crate in \
            amplifier-module-tool-skills \
            amplifier-module-orchestrator-loop-streaming \
            amplifier-module-tool-delegate \
            amplifier-agent-foundation ; do
              echo "=== publishing $crate ==="
              cargo publish -p "$crate" --token "$CARGO_REGISTRY_TOKEN"
              sleep 15
          done

  github-release:
    name: Create GitHub Release
    needs: publish-tier-2
    runs-on: ubuntu-latest
    permissions:
      contents: write
    steps:
      - uses: actions/checkout@v4
      - name: Create release
        uses: softprops/action-gh-release@v2
        with:
          generate_release_notes: true
```

**Step 2: Validate YAML syntax**
Run: `python3 -c "import yaml,sys; yaml.safe_load(open('.github/workflows/publish.yml'))" && echo OK`
Expected: `OK`

**Step 3: Commit**
`git add .github/workflows/publish.yml && git commit -m "ci: tag-triggered crates.io publish pipeline (tier 1 -> tier 2 -> release)"`

---

### Task 23: Validate the workflow with `act` (or fallback)

**Files:** None modified.

**Step 1: Try `act` (preferred)**
Run:
```bash
cd /Users/ken/workspace/amplifier-rust
act push -W .github/workflows/publish.yml -j verify --dryrun
```
Expected: prints the planned action graph with no parser errors. Note: actually executing `verify` locally may fail due to missing tools — `--dryrun` is what we want. If `act` is not installed, install with `brew install act` (macOS) or skip to Step 2.

**Step 2: Fallback — manual workflow lint**
If `act` is unavailable, manually verify:
1. **YAML parses:** Already confirmed in Task 22 Step 2.
2. **Tier ordering matches our tier map:**
   ```bash
   grep -A1 "amplifier-module-" .github/workflows/publish.yml | grep -v -- '--'
   ```
   Verify Tier 1 list contains exactly the 13 Tier 1 crates and Tier 2 contains the 4 Tier 2 crates.
3. **Required secret declared:** `grep CARGO_REGISTRY_TOKEN .github/workflows/publish.yml` returns at least 2 hits.

**Step 3: Confirm `CARGO_REGISTRY_TOKEN` secret is set in GitHub repo settings**
Manual check: Visit `https://github.com/kenotron-ms/amplifier-rust/settings/secrets/actions` and confirm a secret named `CARGO_REGISTRY_TOKEN` exists. If not, generate a token at <https://crates.io/settings/tokens> with `publish-new` and `publish-update` scopes restricted to the `amplifier-module-*` and `amplifier-agent-*` name patterns, then add it.

**Step 4: Commit if any tweaks were needed**
(Likely no commit — this task is verification-only.)

---

### Task 24: Phase 9D acceptance gate

**Step 1: Tag checkpoint**
`git tag phase-9d-complete && git push origin phase-9d-complete`

> **DO NOT** create a `v0.1.0` tag yet — that triggers the actual publish in Phase 9E.

---

# Phase 9E — Publish to crates.io (Tasks 25–27)

### Task 25: Owner pre-claim (one-time, manual)

> **Critical:** Crate names on crates.io are first-come-first-served. If anyone else publishes `amplifier-module-foo` first, that name is gone. To prevent squatting during the Phase 9E rollout, claim ownership of every name **before** triggering the workflow.

**Step 1: Verify token**
Run: `cargo login` (paste your token if prompted) — only needed locally.

**Step 2: Reserve each crate name with a placeholder publish**
> Skip this step if you trust the workflow will land within minutes of tagging — the workflow itself acts as the first publish. **The recommendation is to skip Step 2 and let the GitHub workflow be the first publish.** Documenting it here only as a fallback if the workflow is delayed.

**Step 3: Confirm none of the target names are taken**
For each of the 17 crates, run:
```bash
cargo search amplifier-module-context-simple --limit 1
# … repeat for all 17
```
Expected: Each search either returns nothing OR returns `amplifier-module-XXX = "..."` owned by you (check `https://crates.io/crates/amplifier-module-XXX/owners`). If any name is owned by someone else, **STOP** and rename that crate before proceeding.

---

### Task 26: Tag and trigger publish

**Files:** None modified.

**Step 1: Final pre-flight on `main`**
Run:
```bash
cd /Users/ken/workspace/amplifier-rust
git checkout main
git pull
cargo fmt --all -- --check
cargo clippy --workspace --all-targets -- -D warnings
./scripts/check-pub-api.sh
./scripts/dryrun-all.sh
```
Expected: All clean.

**Step 2: Tag the release**
Run:
```bash
git tag -a v0.1.0 -m "Phase 9: initial crates.io publish of all 17 crates"
git push origin v0.1.0
```

**Step 3: Watch the workflow run**
Open: `https://github.com/kenotron-ms/amplifier-rust/actions`
Expected progression:
1. `verify` job: ~5 min, all green.
2. `publish-tier-1` job: ~15 min (13 crates × ~30s + 15s sleeps).
3. `publish-tier-2` job: 1-min initial wait + ~3 min for 4 crates.
4. `github-release` job: <30s.

If `publish-tier-2` fails with `error: failed to select a version for the requirement amplifier-module-XXX = "^0.1.0"`, the index hadn't propagated. Re-run only the `publish-tier-2` job from the Actions UI. If `publish-tier-1` fails partway through, identify the failed crate, manually publish it (`cargo publish -p <crate>`), then re-run `publish-tier-2`.

**Step 4: Verify each crate is live on crates.io**
Run:
```bash
for crate in \
  amplifier-module-context-simple amplifier-module-provider-anthropic \
  amplifier-module-provider-openai amplifier-module-provider-gemini \
  amplifier-module-provider-ollama amplifier-module-tool-bash \
  amplifier-module-tool-filesystem amplifier-module-tool-search \
  amplifier-module-tool-todo amplifier-module-tool-web \
  amplifier-module-tool-task amplifier-module-agent-runtime \
  amplifier-module-session-store amplifier-module-tool-skills \
  amplifier-module-orchestrator-loop-streaming amplifier-module-tool-delegate \
  amplifier-agent-foundation ; do
    v=$(cargo search "$crate" --limit 1 | grep -E "^${crate}\s*=" | head -1 || true)
    if [[ -z "$v" ]]; then
      echo "MISSING: $crate"
    else
      echo "OK: $v"
    fi
done
```
Expected: 17 lines all starting with `OK:`. Any `MISSING:` line means publish failed for that crate.

**Step 5: Verify docs.rs picked them up**
Wait 10 minutes after publish, then check `https://docs.rs/amplifier-module-context-simple/0.1.0` (and a few others) returns rendered docs. If a crate is stuck in "build queued" for >30 min, check its build logs at `https://docs.rs/crate/<name>/0.1.0/builds`.

**Step 6: No commit needed** — the publish is the artifact. The next plan will start from a fresh commit history.

---

### Task 27: Phase 9E acceptance gate

**Step 1: Confirm all 17 crates live**
Final acceptance: the loop in Task 26 Step 4 prints 17 `OK:` lines.

**Step 2: Tag complete**
`git tag phase-9e-complete && git push origin phase-9e-complete`

---

# Phase 9F — Vela Switchover (Tasks 28–30)

### Task 28: Update `vela/app/src/main/rust/amplifier-android/Cargo.toml`

> **Verify path first:** Confirm `/Users/ken/workspace/vela/app/src/main/rust/amplifier-android/Cargo.toml` exists. If the actual location differs (the directory may be `/Users/ken/workspace/vela/app/rust/amplifier-android/` depending on Vela's layout — verify with `find /Users/ken/workspace/vela -name Cargo.toml -path '*amplifier-android*'`), use the correct path.

**Files:**
- Modify: `/Users/ken/workspace/vela/app/src/main/rust/amplifier-android/Cargo.toml` (path TBD per verification)

**Step 1: Read the current file**
Run: `cat /Users/ken/workspace/vela/app/src/main/rust/amplifier-android/Cargo.toml`
Identify every `git = "https://github.com/kenotron-ms/amplifier-rust"` dep.

**Step 2: Replace each git dep with a versioned dep**
For every line of the form:
```toml
amplifier-module-XXX = { git = "https://github.com/kenotron-ms/amplifier-rust", branch = "main" }
```
Change to:
```toml
amplifier-module-XXX = "0.1"
```

The full expected dep block in `amplifier-android/Cargo.toml` should look like:
```toml
[dependencies]
amplifier-core = "X.Y"     # whatever version Task 0 settled on
amplifier-module-context-simple = "0.1"
amplifier-module-provider-anthropic = "0.1"
amplifier-module-provider-openai = "0.1"
amplifier-module-provider-gemini = "0.1"
amplifier-module-provider-ollama = "0.1"
amplifier-module-tool-bash = "0.1"
amplifier-module-tool-filesystem = "0.1"
amplifier-module-tool-search = "0.1"
amplifier-module-tool-todo = "0.1"
amplifier-module-tool-web = "0.1"
amplifier-module-tool-task = "0.1"
amplifier-module-tool-skills = "0.1"
amplifier-module-tool-delegate = "0.1"
amplifier-module-orchestrator-loop-streaming = "0.1"
amplifier-module-agent-runtime = "0.1"
amplifier-module-session-store = "0.1"
amplifier-agent-foundation = "0.1"
# ...remaining non-amplifier deps unchanged
```

(Include only the modules `amplifier-android` actually uses. Do not blindly add unused deps — keep the dep list minimal to what Vela imports.)

**Step 3: Regenerate Cargo.lock**
Run:
```bash
cd /Users/ken/workspace/vela/app/src/main/rust/amplifier-android
cargo generate-lockfile
```

**Step 4: Build for the host (sanity)**
Run: `cargo build`
Expected: Successful build pulling each crate from crates.io (you'll see `Downloading amplifier-module-...` lines on first run).

**Step 5: Cross-compile for Android**
Run: `cargo build --target aarch64-linux-android` (or whatever target Vela uses; check `vela`'s build script).
Expected: Successful cross-compile, no errors.

**Step 6: Commit (in vela repo)**
```bash
cd /Users/ken/workspace/vela
git add app/src/main/rust/amplifier-android/Cargo.toml app/src/main/rust/amplifier-android/Cargo.lock
git commit -m "build(amplifier-android): switch from git deps to crates.io 0.1 deps"
```

---

### Task 29: Verify Android build still passes

**Files:** None modified.

**Step 1: Run the full Android build**
Run: `cd /Users/ken/workspace/vela && ./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`. If it fails:
- Inspect `app/build/outputs/logs/` for the rust-build subtask error.
- If a Vela Kotlin call site references an item that the published `amplifier-module-*` API no longer exposes, you have a published-API/Vela-call-site drift. Open a follow-up issue; do not roll back the publish.

**Step 2: Smoke test the APK on an emulator (manual)**
1. `./gradlew :app:installDebug`
2. Launch Vela on emulator.
3. Run a minimal conversation that exercises orchestrator + at least one provider + at least one tool.
4. Confirm response streams correctly.

**Step 3: Commit any fixup if the assembleDebug needed call-site changes**
If Kotlin call sites were updated, commit them: `git commit -am "fix(amplifier-android): adapt to crates.io 0.1 published API"`.

---

### Task 30: Phase 9 final acceptance gate

**Step 1: Confirm all six sub-phase tags exist**
Run (in `/Users/ken/workspace/amplifier-rust`):
```bash
git tag -l 'phase-9*'
```
Expected output:
```
phase-9a-complete
phase-9c-complete
phase-9d-complete
phase-9e-complete
v0.1.0
```

**Step 2: Confirm all 17 crates resolve from crates.io**
Run:
```bash
mkdir -p /tmp/crates-resolve-check && cd /tmp/crates-resolve-check
cat > Cargo.toml <<'EOF'
[package]
name = "resolve-check"
version = "0.0.0"
edition = "2021"

[dependencies]
amplifier-module-context-simple = "0.1"
amplifier-module-provider-anthropic = "0.1"
amplifier-module-provider-openai = "0.1"
amplifier-module-provider-gemini = "0.1"
amplifier-module-provider-ollama = "0.1"
amplifier-module-tool-bash = "0.1"
amplifier-module-tool-filesystem = "0.1"
amplifier-module-tool-search = "0.1"
amplifier-module-tool-todo = "0.1"
amplifier-module-tool-web = "0.1"
amplifier-module-tool-task = "0.1"
amplifier-module-tool-skills = "0.1"
amplifier-module-tool-delegate = "0.1"
amplifier-module-orchestrator-loop-streaming = "0.1"
amplifier-module-agent-runtime = "0.1"
amplifier-module-session-store = "0.1"
amplifier-agent-foundation = "0.1"
EOF
mkdir src && echo 'fn main() {}' > src/main.rs
cargo generate-lockfile
echo "RESOLVED OK"
```
Expected: `RESOLVED OK`. Any unresolved crate means publish was incomplete.

**Step 3: Confirm Vela Android build is green**
Run: `cd /Users/ken/workspace/vela && ./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

**Step 4: Tag final**
```bash
cd /Users/ken/workspace/amplifier-rust
git tag phase-9-complete
git push origin phase-9-complete
```

**Phase 9 is now complete.** All 17 crates are public on crates.io under the canonical names; the publish pipeline is wired and idempotent for future tag-triggered releases (`v0.2.0`, `v0.3.0`, ...); and Vela now consumes versioned, immutable crates instead of moving git refs.

---

## Appendix A: Pinning crate versions for future minor bumps

When releasing `v0.2.0`:
1. Bump `[workspace.package].version` in `/Users/ken/workspace/amplifier-rust/Cargo.toml` from `0.1.0` to `0.2.0`.
2. Bump every internal path-and-version dep accordingly (e.g. `amplifier-module-tool-task = { path = "...", version = "0.2.0" }`).
3. Bump `[workspace.dependencies] amplifier-module-agent-runtime` and `amplifier-agent-foundation` versions to `0.2.0`.
4. Run `./scripts/check-pub-api.sh && ./scripts/dryrun-all.sh`.
5. Commit, tag `v0.2.0`, push tag — workflow does the rest.
6. Update Vela `amplifier-android` deps from `"0.1"` to `"0.2"`.

## Appendix B: Recovering from a partial publish failure

If the workflow publishes 8 of 13 Tier 1 crates and then crashes:
- The 8 already-published crates are immutable on crates.io — you cannot re-publish `0.1.0`.
- Either: (a) re-run the workflow with `if: failure()` skipping already-published crates (cargo publish exits with error 101 — wrap in `|| true` and check), OR (b) manually publish the remaining 5 from your laptop with `cargo publish -p <name>`, then re-run only the `publish-tier-2` job from the GitHub Actions UI.
- For Tier 2 failures with index-not-found errors, sleep longer and retry — the sparse index lag can be up to 5 minutes for newly-published crates.
