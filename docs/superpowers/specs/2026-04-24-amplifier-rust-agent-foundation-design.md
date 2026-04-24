# Amplifier Rust Agent Foundation — Design Spec

**Date:** 2026-04-24  
**Status:** Draft — pending implementation plan  
**Scope:** New `amplifier-rust` workspace + refactor of `amplifier-android` + new `amplifier-android-sandbox` binary

---

## Problem

The Vela agent runtime is capable but shallow. The current Rust layer (`amplifier-android`, 5 files ~78KB) runs the LLM loop and dispatches tools, but every tool implementation, the hook system, skill loading, and context management live entirely in Kotlin. This creates two problems:

1. **Android-locked:** nothing in the agent stack is reusable outside of Android. Tools, hooks, and skills cannot be ported, shared with teammates, or run locally for development.
2. **Shallow agency:** without subagent dispatch, a real hook system in Rust, skill forking, and context window management, agents feel like smart assistants rather than deep agent systems — no context sink pattern, no delegation, no step limit in the live code.

The goal is to build the Rust equivalent of what `amplifier-core` + `amplifier-foundation` provide together: a portable, publishable harness that makes agents genuinely capable, without the Python-centric development patterns.

---

## Non-goals

- Modifying `amplifier-core` in any way — the kernel is controlled strictly; we use its existing public Rust types only
- Full Android emulation (ART runtime, JVM, Android SDK APIs)
- WASM plugin loading (correct long-term direction but premature now)
- Azure OpenAI or vLLM providers in Phase 1
- Multimodal (vision/audio) in providers in Phase 1

---

## Section 1 — The `amplifier-rust` workspace

### Repository

New repository: **`amplifier-rust`** — a single Cargo workspace, each crate published independently to crates.io.  
Naming follows the official Amplifier module taxonomy: `amplifier-module-{type}-{name}`.

```
amplifier-rust/
  Cargo.toml                                      ← workspace root
  crates/
    amplifier-module-provider-anthropic/
    amplifier-module-provider-openai/
    amplifier-module-provider-gemini/
    amplifier-module-provider-ollama/
    amplifier-module-context-simple/
    amplifier-module-orchestrator-loop-streaming/
    amplifier-module-tool-filesystem/
    amplifier-module-tool-bash/
    amplifier-module-tool-web/
    amplifier-module-tool-search/
    amplifier-module-tool-task/
    amplifier-module-tool-skills/
    amplifier-module-tool-todo/
  sandbox/
    amplifier-android-sandbox/                    ← binary, NOT published to crates.io
```

### Complete crate catalog

#### Providers (4 crates)

| Crate | Wire protocol | Auth | Key features |
|---|---|---|---|
| `amplifier-module-provider-anthropic` | Anthropic Messages API + SSE | `ANTHROPIC_API_KEY` | Real per-token streaming, tool format conversion, retry + exponential backoff |
| `amplifier-module-provider-openai` | OpenAI Responses API (`/v1/responses`) | `OPENAI_API_KEY` | Reasoning config (low/medium/high), encrypted reasoning state across turns, auto-continuation on `max_output_tokens`, deep research / background mode |
| `amplifier-module-provider-gemini` | Gemini Developer API | `GOOGLE_API_KEY` or `GEMINI_API_KEY` | Thinking config (budget: dynamic / off / fixed), synthetic tool-call IDs (Gemini doesn't return IDs natively), 1M token context window, `gemini-2.5-flash` default |
| `amplifier-module-provider-ollama` | ChatCompletions (`/v1/chat/completions`) | Optional API key | `base_url` configurable — targets Ollama, LM Studio, vLLM, OpenRouter, or any compatible endpoint; critical for the sandbox against local models |

#### Context (1 crate)

| Crate | Responsibility |
|---|---|
| `amplifier-module-context-simple` | In-memory turn history; token counting (tiktoken-rs); context compaction via LLM summarization when approaching window limit; ephemeral injection (pre-turn context not stored in history) |

#### Orchestrator (1 crate)

| Crate | Responsibility |
|---|---|
| `amplifier-module-orchestrator-loop-streaming` | Multi-turn agent loop with configurable step limit (default 10); real SSE streaming per token; dispatches hooks at all lifecycle events via `HookRegistry` from amplifier-core; tool loop with error recovery; uses `HookHandler`, `HookRegistry`, `HookResult`, and event constants directly from `amplifier_core` — no new hook types defined |

#### Tools (7 crates)

| Crate | Responsibility |
|---|---|
| `amplifier-module-tool-filesystem` | `read_file`, `write_file`, `edit_file`, `glob`, `grep` — vault-root-scoped, path allowlist configurable |
| `amplifier-module-tool-bash` | Restricted shell execution; safety profiles: `strict`, `standard`, `permissive`, `android` (toybox subset only); platform shim — on Android routes to Kotlin via JNI callback |
| `amplifier-module-tool-web` | Web fetch with HTML stripping, content size limit; DuckDuckGo web search with fallback |
| `amplifier-module-tool-search` | Codebase grep (ripgrep-powered) and semantic search against local vault embeddings |
| `amplifier-module-tool-task` | Subagent spawning — defines `SubagentRunner` trait; `SpawnRequest` with `context_depth` / `context_scope` / `session_id` / `provider_preferences`; recursion depth guard (`max_recursion_depth: 1` default) |
| `amplifier-module-tool-skills` | `SKILL.md` parsing (frontmatter + body via serde_yaml); operations: list, search, info, load; fork dispatch — when `context: fork`, invokes `SubagentRunner` with skill body as instructions |
| `amplifier-module-tool-todo` | AI task self-accountability; create/update/list/complete todo items scoped to session |

### Dependency graph (layered, no circular deps)

```
amplifier-core  (existing, untouched — provides Provider, Tool, Orchestrator, ContextManager)
      ↑
amplifier-module-provider-{anthropic,openai,gemini,ollama}   ← each depends only on core
amplifier-module-tool-{filesystem,bash,web,search,todo}      ← each depends only on core
      ↑
amplifier-module-context-simple                              ← depends on core
      ↑
amplifier-module-tool-task                                   ← defines SubagentRunner trait
      ↑                                                           (no import of orchestrator)
amplifier-module-tool-skills                                 ← depends on core + context
      ↑
amplifier-module-orchestrator-loop-streaming                 ← depends on core + context + tool-task
  │   └── uses HookHandler + HookRegistry + HookResult from amplifier-core (no redefinition)
  ↑
amplifier-android   (in Vela repo — JNI bridge)
amplifier-android-sandbox  (in amplifier-rust repo — CLI binary)
```

**Circular dependency avoidance for `tool-task`:** `SubagentRunner` is a trait defined in `amplifier-module-tool-task`. The orchestrator implements it and injects itself into `TaskTool` at startup via `Arc<dyn SubagentRunner>`. The task crate has no Cargo import of the orchestrator crate.

---

## Section 2 — Subagent dispatch and hook system

### Subagent dispatch (`amplifier-module-tool-task`)

When the LLM calls the task tool, a full child orchestrator session runs to completion and returns its final assistant text as a plain string. That string becomes the tool result in the parent — typically 100–300 tokens regardless of the child's internal work. This is the context sink pattern.

**`SubagentRunner` trait** (defined in `amplifier-module-tool-task`):

```rust
#[async_trait]
pub trait SubagentRunner: Send + Sync {
    async fn run(&self, req: SpawnRequest) -> Result<String>;
}

pub struct SpawnRequest {
    pub instruction:          String,
    pub context_depth:        ContextDepth,   // None | Recent(N) | All
    pub context_scope:        ContextScope,   // Conversation | Agents | Full
    pub context:              Vec<Message>,   // pre-filtered by caller
    pub session_id:           Option<String>, // resume a prior sub-session
    pub provider_preferences: Vec<ProviderPref>,
}
```

**Wiring at startup** (in `amplifier-android` or `amplifier-android-sandbox`):

```rust
let orch  = Arc::new(LoopOrchestrator::new(config));
let task  = TaskTool::new(Arc::clone(&orch) as Arc<dyn SubagentRunner>, current_depth: 0);
orch.register_tool(task);
```

**Context depth / scope semantics:**

| `context_depth` | What child sees |
|---|---|
| `None` | Fresh start — no parent history |
| `Recent(N)` | Last N turns from parent |
| `All` | Full parent conversation history |

| `context_scope` | Filter applied after depth |
|---|---|
| `Conversation` | User/assistant text only |
| `Agents` | + results from prior task tool calls |
| `Full` | + all tool results |

### Hook system

**Key finding:** `HookHandler`, `HookRegistry`, and `HookResult` already exist in the Rust side of `amplifier-core` (`crates/amplifier-core/src/traits.rs`, `hooks.rs`, and `models.rs`). Python simply re-exports the Rust types via PyO3. **We use these types directly — no new hook types defined anywhere in our workspace.** Event name string constants live in `amplifier_core::events`.

**Orchestrator hook usage** (`amplifier-module-orchestrator-loop-streaming` imports from amplifier-core):

```rust
use amplifier_core::{HookHandler, HookRegistry, HookResult};
use amplifier_core::events::{SESSION_START, PROVIDER_REQUEST, TOOL_PRE, TOOL_POST};

// At each lifecycle point — string-keyed events, JSON data, same as Python orchestrators
let result = hooks.emit(TOOL_PRE, json!({ "name": tool_name, "args": args })).await?;
match result.action.as_str() {
    "deny"           => { /* skip tool */ }
    "inject_context" => { self.context.push_ephemeral(result.context_injection); }
    _                => { /* continue */ }
}
```

**Orchestrator execute signature** (matches the Python `loop-basic` / `loop-streaming` pattern exactly):

```rust
pub async fn execute(
    &self,
    prompt:    String,
    context:   &dyn ContextManager,
    providers: &HashMap<String, Box<dyn Provider>>,
    tools:     &HashMap<String, Box<dyn Tool>>,
    hooks:     &HookRegistry,  // passed in — binary owns and creates it; same as Python
) -> Result<String>
```

The binary (not the orchestrator) creates the `HookRegistry`, registers handlers, and passes it into `execute()`. The orchestrator is a pure consumer — identical to how Python orchestrators receive it.

**JNI hook bridge** (`amplifier-android/src/jni_hooks.rs`):

```rust
use amplifier_module_orchestrator_loop_streaming::{Hook, HookEvent, HookResult};

struct KotlinHookBridge { callback: GlobalRef }

impl Hook for KotlinHookBridge {
    fn events(&self) -> &[HookEvent] { &self.registered_events }
    async fn handle(&self, event: HookEvent, ctx: &HookContext) -> HookResult {
        let json = jni_call_hook(&self.callback, event, ctx);
        parse_hook_result(&json)
    }
}
```

Existing Kotlin hooks (VaultSyncHook, StatusContextHook, TodoReminderHook, PersonalizationHook) require **zero logic changes** — they are wrapped in a new `HookCallback fun interface` and passed to Rust at startup.

---

## Section 3 — amplifier-android changes and amplifier-android-sandbox

### amplifier-android — before and after

**Current state (5 files, ~78KB, self-contained):**

| File | Size | Role |
|---|---|---|
| `lib.rs` | ~10KB | JNI entry, wiring |
| `orchestrator.rs` | ~15KB | SimpleOrchestrator — the agent loop |
| `provider.rs` | ~25KB | AnthropicProvider — HTTP calls |
| `context.rs` | ~8KB | SimpleContext — in-memory Vec |
| `jni_tools.rs` | ~20KB | KotlinToolBridge |

**After migration (3 files, ~20KB, thin wiring only):**

| File | Fate | Notes |
|---|---|---|
| `lib.rs` | Rewritten (~5KB) | Wires workspace crates, assembles HookRegistry |
| `orchestrator.rs` | **Deleted** | Replaced by `amplifier-module-orchestrator-loop-streaming` |
| `provider.rs` | **Deleted** | Replaced by `amplifier-module-provider-anthropic` |
| `context.rs` | **Deleted** | Replaced by `amplifier-module-context-simple` |
| `jni_tools.rs` | Unchanged | Still implements `Tool` from amplifier-core |
| `jni_hooks.rs` | **New** | `KotlinHookBridge` implementing `Hook` |

### Two-phase migration

**Phase 1 — mechanical swap (zero Kotlin changes, same external behavior):**
1. Add `amplifier-rust` workspace crates to `amplifier-android/Cargo.toml`
2. Delete `orchestrator.rs`, `provider.rs`, `context.rs`
3. Rewrite `lib.rs` to wire workspace crates
4. Bridge existing `ProviderRequestCallback` as a `PROVIDER_REQUEST` hook temporarily
5. Ship — identical behavior, now backed by portable crates
6. Step limit enforced (default 10 — currently missing from live orchestrator)

**Phase 2 — fuller integration:**
1. Add `HookCallback fun interface` to `AmplifierBridge.kt`
2. Add `jni_hooks.rs` with `KotlinHookBridge`
3. Wire all Kotlin hooks through `HookRegistry`
4. Remove `ProviderRequestCallback` (absorbed into hook system)
5. Real SSE streaming (currently fake — full response then token-by-token emit)
6. Migrate portable tools progressively to workspace crates (web, todo, skills first)

### What stays in Kotlin forever

These require Android APIs that have no portable Rust equivalent:

| Tool / Hook | Reason |
|---|---|
| `TranscribeAudioTool` | Android `MediaRecorder` / `SpeechRecognizer` |
| `CodeRunnerTool` | Chaquopy Python runtime |
| `GetBatteryTool` | `BatteryManager` |
| `GetTimeTool`, `GetDateTool` | Could move to Rust, but trivial |
| `RunInNodeTool` | JSch SSH — could eventually be a Rust crate |
| `VaultSyncHook` | `VaultGitSync` Kotlin class (could be ported later) |
| `VaultIndexHook`, `VaultEmbeddingHook` | ONNX MiniLM via Android ONNX runtime |
| `StatusContextHook` | Reads Room DB |

### amplifier-android-sandbox

A CLI binary that runs the full Amplifier agent stack on Linux/macOS in a restricted environment approximating Android's security model. Not an Android emulator — no ART, no JVM, no Android SDK. Just the Rust agent runtime with OS-enforced filesystem and syscall restrictions.

**Binary interface:**

```bash
# Interactive REPL (default when no --prompt)
amplifier-android-sandbox --vault ./my-vault --provider anthropic

# Single-turn
amplifier-android-sandbox --prompt "Write a to-do list" --model gemini-2.5-flash

# Against local Ollama — no cloud credentials required
amplifier-android-sandbox --provider ollama --model llama3.2

# Android-like restrictions (Linux only, kernel 5.13+)
amplifier-android-sandbox --sandbox --vault ./my-vault
```

**`--sandbox` flag (Linux only, no root required):**

Two restriction layers applied at process startup before any agent code runs:

1. **landlock** (kernel 5.13+, filesystem isolation):
   - Read + write: vault directory, `/tmp`
   - Read only: `/etc/ssl` (TLS), `/usr/lib` (dynamic libs), `/proc/self`
   - Blocked: `/home`, `/root`, `/var`, `/proc` (except self), `/sys`
   - Mirrors Android app's sandboxed filesystem view

2. **seccomp BPF** (syscall filter):
   - Blocked: `ptrace`, `mount`, `umount2`, `setuid`, `setgid`, `capset`, `kexec_load`, `chroot`
   - Allowed: all network syscalls, file I/O within allowed paths, normal process management
   - Matches Android's seccomp allowlist for untrusted app processes

**macOS:** `--sandbox` is a no-op (landlock/seccomp are Linux-only). Runs without OS restrictions. For full-fidelity restriction testing, use Docker:

```dockerfile
FROM rust:slim-bookworm
RUN apt-get install -y libseccomp-dev
COPY . .
RUN cargo build -p amplifier-android-sandbox --release
# landlock + seccomp apply inside container at process startup
```

**Tool surface in sandbox (all Rust, no JNI, no Android APIs):**

| Tool | Available | Notes |
|---|---|---|
| `amplifier-module-tool-filesystem` | ✅ | landlock keeps it in vault |
| `amplifier-module-tool-bash` | ✅ | `safety_profile: android` — toybox subset only |
| `amplifier-module-tool-web` | ✅ | network allowed (mirrors Android default) |
| `amplifier-module-tool-search` | ✅ | |
| `amplifier-module-tool-task` | ✅ | subagent spawning fully functional |
| `amplifier-module-tool-skills` | ✅ | reads from vault `skills/` |
| `amplifier-module-tool-todo` | ✅ | |
| `TranscribeAudioTool` | ❌ | Android audio API — no equivalent |
| `CodeRunnerTool` | ❌ | Chaquopy — could add pyo3 later |
| `GetBatteryTool` | ❌ | Android system API — meaningless on Linux |

**Android safety profile for `amplifier-module-tool-bash`:**

New safety profile `android` restricts bash execution to the toybox command subset:

```
allowed: ls, cat, echo, mkdir, rm, cp, mv, find, grep, sed, awk,
         sort, head, tail, tar, gzip, curl, date, sleep, env, id,
         pwd, wc, diff, unzip, zip, chmod, touch
blocked: sudo, mount, ptrace, chroot, setuid, kill (unrestricted),
         anything not in the toybox command set
```

**Hooks in sandbox (Rust-native):**

| Hook | Role |
|---|---|
| `LoggingHook` | Logs all lifecycle events — replaces StatusContextHook for local dev |
| `WatchHook` | Watches vault directory for changes (inotify/kqueue) — replaces VaultSyncHook |
| `TodoContextHook` | Reads vault todo files for `PROVIDER_REQUEST` injection |

---

## Phase ordering

### Phase 1 — Agent depth, immediately (no tool migration yet)

Delivers step limits, real hooks in Rust, skill fork dispatch, and the foundation for subagent spawning. Minimum Kotlin changes.

**Crates to build:**
- `amplifier-module-orchestrator-loop-streaming`
- `amplifier-module-context-simple`
- `amplifier-module-provider-anthropic` (streaming rewrite of current provider.rs)
- `amplifier-module-tool-task` (SubagentRunner trait + TaskTool)
- `amplifier-module-tool-skills` (SKILL.md parsing + fork dispatch)

**amplifier-android changes:** Phase 1 migration (delete 3 files, rewrite lib.rs)

### Phase 2 — Portable foundation + real streaming

Subagent spawning fully wired, context compaction live, streaming real.

**Crates to build:**
- `amplifier-module-provider-gemini`
- `amplifier-module-provider-openai`
- `amplifier-module-provider-ollama`
- `amplifier-module-tool-todo`
- `amplifier-module-tool-web` (migrates from Kotlin SearchWebTool + FetchUrlTool)

**amplifier-android changes:** Phase 2 migration (HookCallback interface, jni_hooks.rs, real streaming)

### Phase 3 — Portable tools + sandbox

Tool suite fully portable, sandbox binary runnable.

**Crates to build:**
- `amplifier-module-tool-filesystem` (migrates from Kotlin vault tools)
- `amplifier-module-tool-bash` (android safety profile)
- `amplifier-module-tool-search`
- `amplifier-android-sandbox` binary

---

## Success criteria

- [ ] All 13 crates compile independently and publish to crates.io under `amplifier-module-*` namespace
- [ ] `amplifier-android` has no self-contained agent logic — all loop/provider/context comes from workspace crates
- [ ] LLM can call the task tool and spawn a sub-session; child result appears as a tool result in parent
- [ ] Fork skills actually spawn a subagent (not just inject context)
- [ ] Hook system fires at `SESSION_START`, `PROVIDER_REQUEST`, `TOOL_PRE`, `TOOL_POST` from within Rust
- [ ] Step limit of 10 enforced in live orchestrator (currently missing)
- [ ] Real SSE streaming — tokens arrive per-chunk from provider, not post-response
- [ ] `amplifier-android-sandbox --sandbox` starts successfully on Linux kernel 5.13+ without root
- [ ] Sandbox can run a full multi-turn agent session against local Ollama
- [ ] Sandbox bash tool rejects non-toybox commands under `android` profile
