# Phase 8: Android Agent Surface Implementation Plan

> **Execution:** Use the `subagent-driven-development` workflow to implement this plan.

**Goal:** Make the `delegate` tool and the foundation agent system available in the Vela Android app, with an agent picker chip row in `ConversationScreen` and zero-friction install of new agents by dropping `.md` files in `vault/.agents/`.

**Architecture:** The `amplifier-android` Rust crate gains an `AgentRegistry` (foundation built-ins + filesystem scan of `vault/.agents/`) and registers a `DelegateTool` alongside the existing Kotlin tool bridges. Two JNI surface changes: (1) `nativeRun` gains a `vaultPath: String` parameter so the Rust side knows where to find agents; (2) a new `nativeListAgents(vaultPath)` returns agents as JSON for the Kotlin UI. The Compose UI adds an `AgentChipRow` above the composer; tapping a chip sets the active agent, and the `[+]` chip opens a vault file browser so the user can install agents by tapping their `.md` files.

**Tech Stack:** Rust 1.x (cdylib via JNI 0.21) · Kotlin 1.9+ / Compose Material3 · Hilt · JUnit4/Truth (JVM) · AndroidX Test (instrumented)

**Phase splits (executed in order):**
- **Phase 8A** — Rust agent registry + DelegateTool wiring + JNI surface (Tasks 1–6)
- **Phase 8B** — Kotlin bridge + ViewModel state (Tasks 7–11)
- **Phase 8C** — Compose UI (chip row, install flow) + verification gates (Tasks 12–16)

Total: **16 tasks**.

---

## Pre-flight

Before Task 1, verify your working directory is `/Users/ken/workspace/vela` and the workspace cousin `/Users/ken/workspace/amplifier-rust` exists with the `crates/amplifier-module-tool-delegate`, `crates/amplifier-module-agent-runtime`, and `amplifier-agent-foundation` directories present. The plan uses **relative path deps** to the cousin workspace (matching the existing pattern for `amplifier-module-tool-todo` / `amplifier-module-tool-web` in `app/src/main/rust/amplifier-android/Cargo.toml`).

```
ls /Users/ken/workspace/amplifier-rust/crates/amplifier-module-tool-delegate/Cargo.toml
ls /Users/ken/workspace/amplifier-rust/crates/amplifier-module-agent-runtime/Cargo.toml
ls /Users/ken/workspace/amplifier-rust/amplifier-agent-foundation/Cargo.toml
```
Expected: all three files exist. If any is missing, **stop and surface the issue** — do not proceed.

---

# PHASE 8A — Rust agent registry + JNI surface

### Task 1: Add the three new Cargo dependencies to amplifier-android

**Files:**
- Modify: `app/src/main/rust/amplifier-android/Cargo.toml`

**Step 1: Read the file**
Run: `cat app/src/main/rust/amplifier-android/Cargo.toml`
Expected: matches the layout shown in the plan header (lines 9–17 list `amplifier-module-*` deps).

**Step 2: Add the three new dependencies**
Edit `app/src/main/rust/amplifier-android/Cargo.toml`. After line 17 (the `amplifier-module-tool-web` line), add:

```toml
amplifier-module-tool-delegate                = { path = "../../../../../../amplifier-rust/crates/amplifier-module-tool-delegate" }
amplifier-module-agent-runtime                = { path = "../../../../../../amplifier-rust/crates/amplifier-module-agent-runtime" }
amplifier-agent-foundation                    = { path = "../../../../../../amplifier-rust/amplifier-agent-foundation" }
```

(Indent style and `=` alignment match the surrounding lines exactly.)

**Step 3: Verify Cargo can resolve the dep graph**
Run: `cd app/src/main/rust/amplifier-android && cargo check --offline 2>&1 | tail -20`
Expected: errors about `tool_map` / `DelegateTool` may appear later, but **no error mentioning "could not find" / "no matching package" for the three new crates**. If the path resolves wrongly, fix the relative path before continuing.

**Step 4: Commit**
```
git add app/src/main/rust/amplifier-android/Cargo.toml
git commit -m "build(amplifier-android): add delegate, agent-runtime, agent-foundation deps"
```

---

### Task 2: Add `build_agent_registry` helper (TDD: failing Rust unit test first)

**Files:**
- Create: `app/src/main/rust/amplifier-android/src/agents.rs`
- Modify: `app/src/main/rust/amplifier-android/src/lib.rs` (add `mod agents;`)

**Step 1: Write the failing test**
Create `app/src/main/rust/amplifier-android/src/agents.rs` with **only** these contents (test + minimal module skeleton, no impl):

```rust
//! Agent registry construction + JSON listing for the Android JNI surface.
//!
//! Builds an [`AgentRegistry`] populated with:
//! 1. The six [`amplifier_agent_foundation::foundation_agents`] built-ins
//! 2. Any `*.md` agent bundles found in `<vault>/.agents/`
//!
//! Used by both `nativeRun` (to back [`DelegateTool`]) and `nativeListAgents`.

use std::path::Path;
use std::sync::Arc;

use amplifier_agent_foundation::foundation_agents;
use amplifier_module_agent_runtime::AgentRegistry;

/// Build an [`AgentRegistry`] containing foundation agents plus any agents
/// loaded from `<vault_path>/.agents/`.
///
/// Non-existent directories are silently ignored — the registry will still
/// contain the foundation agents.
pub fn build_agent_registry(vault_path: &Path) -> Arc<AgentRegistry> {
    let mut registry = AgentRegistry::new();
    for agent in foundation_agents() {
        registry.register(agent);
    }
    let _ = registry.load_from_dir(&vault_path.join(".agents"));
    Arc::new(registry)
}

/// Serialize all agents in the registry to a JSON array of objects.
///
/// Each element has shape `{"name":"...","description":"...","tools":[...]}`.
/// Returns `"[]"` for an empty registry.
pub fn list_agents_to_json(registry: &AgentRegistry) -> String {
    serde_json::to_string(&registry.list()).unwrap_or_else(|_| "[]".to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::tempdir;

    #[test]
    fn build_registry_includes_six_foundation_agents() {
        let tmp = tempdir().unwrap();
        let registry = build_agent_registry(tmp.path());
        let names: Vec<&str> = registry.available_names();
        // Must include all 6 foundation agents
        for expected in &[
            "explorer",
            "zen-architect",
            "bug-hunter",
            "git-ops",
            "modular-builder",
            "security-guardian",
        ] {
            assert!(
                names.contains(expected),
                "registry missing foundation agent '{expected}', has: {names:?}"
            );
        }
    }

    #[test]
    fn build_registry_loads_vault_agents_directory() {
        let tmp = tempdir().unwrap();
        let agents_dir = tmp.path().join(".agents");
        std::fs::create_dir_all(&agents_dir).unwrap();
        std::fs::write(
            agents_dir.join("custom.md"),
            "---\nmeta:\n  name: my-custom-agent\n  description: a vault agent\n---\nDo things.\n",
        )
        .unwrap();

        let registry = build_agent_registry(tmp.path());
        let names: Vec<&str> = registry.available_names();
        assert!(
            names.contains(&"my-custom-agent"),
            "vault agent not loaded, registry has: {names:?}"
        );
    }

    #[test]
    fn list_agents_to_json_returns_valid_json_array() {
        let tmp = tempdir().unwrap();
        let registry = build_agent_registry(tmp.path());
        let json = list_agents_to_json(&registry);

        // Must parse as JSON array
        let parsed: serde_json::Value = serde_json::from_str(&json).expect("must be valid JSON");
        let arr = parsed.as_array().expect("must be JSON array");
        assert!(arr.len() >= 6, "expected at least 6 agents, got {}", arr.len());

        // Each element must have name + description fields
        for el in arr {
            assert!(el.get("name").and_then(|v| v.as_str()).is_some());
            assert!(el.get("description").is_some());
        }
    }

    #[test]
    fn build_registry_missing_vault_dir_is_not_an_error() {
        // Use a path that does not exist — must still return foundation agents.
        let bogus = std::path::Path::new("/definitely/does/not/exist/vault");
        let registry = build_agent_registry(bogus);
        assert_eq!(registry.available_names().len(), 6);
    }
}
```

Then add `mod agents;` to `app/src/main/rust/amplifier-android/src/lib.rs`. Insert it on a new line right after the existing `mod jni_tools;` (around line 62 of the current file):

```rust
mod agents;
mod jni_hooks;
mod jni_tools;
```

**Step 2: Add `tempfile` to dev-dependencies**
Append to `app/src/main/rust/amplifier-android/Cargo.toml`:

```toml

[dev-dependencies]
tempfile = "3"
```

**Step 3: Run the tests to verify they pass (this is a GREEN test from the start — the impl is in the same file)**
Run: `cd app/src/main/rust/amplifier-android && cargo test --lib agents::tests 2>&1 | tail -20`
Expected: `test result: ok. 4 passed; 0 failed`.

If any test fails, fix the implementation in `agents.rs` before continuing — do **not** edit the tests.

**Step 4: Commit**
```
git add app/src/main/rust/amplifier-android/Cargo.toml app/src/main/rust/amplifier-android/src/agents.rs app/src/main/rust/amplifier-android/src/lib.rs
git commit -m "feat(amplifier-android): build_agent_registry + list_agents_to_json with tests"
```

---

### Task 3: Add `vaultPath` parameter to `nativeRun` JNI signature

**Files:**
- Modify: `app/src/main/rust/amplifier-android/src/lib.rs` (extend `Java_..._nativeRun` signature, propagate to `run_agent_loop`)

**Step 1: Re-read the existing JNI entry point**
Run: `sed -n '100,180p' app/src/main/rust/amplifier-android/src/lib.rs`
Note the parameter list lines 102–115 and the `run_agent_loop` call lines 160–171.

**Step 2: Insert `vault_path: JString` between `system_prompt` and `token_cb`**
In `app/src/main/rust/amplifier-android/src/lib.rs`, replace the parameter list of `Java_com_vela_app_ai_AmplifierBridge_nativeRun`. The new parameter order, matching the Kotlin signature change in Task 7, is:

```rust
pub extern "C" fn Java_com_vela_app_ai_AmplifierBridge_nativeRun(
    mut env: JNIEnv,
    _class: JClass,
    api_key: JString,
    model: JString,
    tools_json: JString,
    history_json: JString,
    user_input: JString,
    _user_content_json: JObject,
    system_prompt: JString,
    vault_path: JString,            // ← NEW: passed from Kotlin AmplifierSession
    token_cb: JObject,
    tool_cb: JObject,
    hook_callbacks: JObject,
    _server_tool_cb: JObject,
) -> jstring {
```

**Step 3: Extract the new string and thread it through to `run_agent_loop`**
Right after the existing `let system_prompt = jstring_to_rust(...)` line (~129), add:

```rust
    let vault_path = jstring_to_rust(&mut env, &vault_path, "vault_path");
```

Then update the `RT.block_on(run_agent_loop(...))` invocation (~160) to pass `vault_path` as the **last** argument *before* `hook_registrations`:

```rust
    let result = RT.block_on(run_agent_loop(
        api_key,
        model,
        tools_json,
        history_json,
        user_input,
        system_prompt,
        vault_path,           // ← NEW
        jvm,
        token_cb_global,
        tool_cb_global,
        hook_registrations,
    ));
```

And update the `async fn run_agent_loop(...)` signature (~271):

```rust
async fn run_agent_loop(
    api_key: String,
    model: String,
    tools_json: String,
    history_json: String,
    user_input: String,
    system_prompt: String,
    vault_path: String,          // ← NEW
    jvm: Arc<JavaVM>,
    token_cb: Arc<GlobalRef>,
    tool_cb: Arc<GlobalRef>,
    hook_registrations: Vec<(Arc<GlobalRef>, Vec<String>)>,
) -> anyhow::Result<String> {
```

For now leave `vault_path` unused (`let _vault_path = vault_path;` is fine; Task 4 wires it). Also update the doc comment header for `nativeRun` (the `# Parameters` table) to add a row:
```
/// * `vault_path`         – Filesystem path to the active vault root (used to load `.agents/`).
```

**Step 4: Verify the Rust crate still compiles**
Run: `cd app/src/main/rust/amplifier-android && cargo check 2>&1 | tail -15`
Expected: clean compile (no errors). Warnings about unused `vault_path` are acceptable at this point.

**Step 5: Commit**
```
git add app/src/main/rust/amplifier-android/src/lib.rs
git commit -m "feat(amplifier-android): add vault_path parameter to nativeRun JNI signature"
```

---

### Task 4: Wire `DelegateTool` into the orchestrator inside `run_agent_loop`

**Files:**
- Modify: `app/src/main/rust/amplifier-android/src/lib.rs` (`run_agent_loop` body)

**Step 1: Add the new imports at the top of `lib.rs`**
Locate the existing `use amplifier_module_*` import block (lines 45–49) and append:

```rust
use amplifier_module_agent_runtime::AgentRegistry;
use amplifier_module_tool_delegate::{DelegateConfig, DelegateTool, SubagentRunner};
```

**Step 2: Inside `run_agent_loop`, build the registry and register the tool**
After the `for tool in tool_map.into_values() { orch.register_tool(tool).await; }` loop (~line 311), insert:

```rust
    // ── Build agent registry (foundation + vault/.agents/) and register DelegateTool ──
    let agent_registry: Arc<AgentRegistry> =
        crate::agents::build_agent_registry(std::path::Path::new(&vault_path));

    let delegate_tool = DelegateTool::new(
        Arc::clone(&orch) as Arc<dyn SubagentRunner>,
        Arc::clone(&agent_registry),
        DelegateConfig::default(),
    );
    orch.register_tool(Arc::new(delegate_tool)).await;
    log::info!(
        "[amplifier-android] DelegateTool registered with {} agents",
        agent_registry.available_names().len()
    );
```

(Note: `Arc::clone(&orch) as Arc<dyn SubagentRunner>` mirrors the coercion used in `sandbox/amplifier-android-sandbox/src/main.rs` — `LoopOrchestrator` already implements `SubagentRunner` from `amplifier-module-tool-task`, which `amplifier-module-tool-delegate` re-exports.)

Also remove the now-unused `_vault_path` shadow if you added one in Task 3.

**Step 3: Verify clean compile + Rust unit tests still pass**
Run: `cd app/src/main/rust/amplifier-android && cargo check 2>&1 | tail -10`
Expected: clean.

Run: `cd app/src/main/rust/amplifier-android && cargo test --lib 2>&1 | tail -10`
Expected: all existing tests + the 4 from Task 2 pass.

**Step 4: Commit**
```
git add app/src/main/rust/amplifier-android/src/lib.rs
git commit -m "feat(amplifier-android): register DelegateTool backed by AgentRegistry"
```

---

### Task 5: Add `nativeListAgents` JNI entry point (TDD: write a Rust unit test for the helper first)

**Files:**
- Modify: `app/src/main/rust/amplifier-android/src/lib.rs` (add `Java_..._nativeListAgents` extern fn)

**Step 1: Write the failing test in `agents.rs`**
The pure-Rust slice of behavior was already covered by `list_agents_to_json_returns_valid_json_array` in Task 2 — that test stands in for the JSON contract. The JNI shell is verified by the Android instrumented test in Task 16. **No new Rust test in this task.**

**Step 2: Add the JNI extern function**
Append to `app/src/main/rust/amplifier-android/src/lib.rs` (place it directly *after* the closing `}` of the `Java_com_vela_app_ai_AmplifierBridge_nativeRun` function, around line 178):

```rust
// ───────────────────────────── nativeListAgents JNI entry ─────────────────────────────

/// Build an [`AgentRegistry`] for the given vault and return all agents as a JSON array.
///
/// Wire format: `[{"name":"...","description":"...","tools":[...]}, ...]`
/// On any failure, returns `"[]"`.
///
/// # Parameters
/// * `vault_path` – Filesystem path to the active vault root.
#[no_mangle]
pub extern "C" fn Java_com_vela_app_ai_AmplifierBridge_nativeListAgents(
    mut env: JNIEnv,
    _class: JClass,
    vault_path: JString,
) -> jstring {
    let vault_path = jstring_to_rust(&mut env, &vault_path, "vault_path");
    let registry = crate::agents::build_agent_registry(std::path::Path::new(&vault_path));
    let json = crate::agents::list_agents_to_json(&registry);
    env.new_string(json)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}
```

**Step 3: Verify clean compile**
Run: `cd app/src/main/rust/amplifier-android && cargo check 2>&1 | tail -10`
Expected: clean.

**Step 4: Commit**
```
git add app/src/main/rust/amplifier-android/src/lib.rs
git commit -m "feat(amplifier-android): nativeListAgents JNI entry returning agent JSON"
```

---

### Task 6: Build the Android target to confirm the cdylib still links

**Files:** none (build verification only)

**Step 1: Detect the Android target triple in use**
Run: `find app/src/main/rust/amplifier-android -name '.cargo' -o -name 'config.toml' 2>/dev/null; cat app/src/main/rust/amplifier-android/.cargo/config.toml 2>/dev/null`
Note the configured `[target.<triple>]` (commonly `aarch64-linux-android`). If no `.cargo/config.toml`, the gradle plugin handles target selection — proceed using the gradle path in step 2 instead of `cargo build --target ...`.

**Step 2: Build for Android via gradle (preferred)**
Run: `./gradlew :app:assembleDebug 2>&1 | tail -40`
Expected: `BUILD SUCCESSFUL`. The gradle task invokes the cargo-ndk integration that compiles the cdylib for the configured Android ABIs. If it fails on Rust errors, fix them. If it fails on Kotlin (Tasks 7–11 not yet done), that's expected — the *Rust* portion succeeding is what matters here. To confirm Rust succeeded, grep:

```
./gradlew :app:assembleDebug 2>&1 | grep -E "(cargo|error|Compiling amplifier-android)"
```
Expected: see `Compiling amplifier-android` followed by no `error[`.

**Step 3: Commit (if anything changed in step 1's investigation)**
No changes expected — skip commit. Move to Phase 8B.

---

# PHASE 8B — Kotlin bridge + ViewModel state

### Task 7: Extend `AmplifierBridge` with `vaultPath` param and new `nativeListAgents` external

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/ai/AmplifierBridge.kt`

**Step 1: Read the existing bridge file**
Run: `cat app/src/main/kotlin/com/vela/app/ai/AmplifierBridge.kt`
Confirm it matches the form in the plan header.

**Step 2: Edit the `nativeRun` signature and add `nativeListAgents`**
Replace the `external fun nativeRun(...)` block (lines 22–34) with:

```kotlin
    external fun nativeRun(
        apiKey:          String,
        model:           String,
        toolsJson:       String,
        historyJson:     String,
        userInput:       String,
        userContentJson: String?,           // null = plain text; non-null = content blocks JSON
        systemPrompt:    String,
        vaultPath:       String,            // path to active vault root; "" if no vault
        tokenCb:         TokenCallback,
        toolCb:          ToolCallback,
        hookCallbacks:   Array<HookRegistration>,
        serverToolCb:    ServerToolCallback,
    ): String

    /**
     * Returns a JSON array of the agents currently visible to the agent runtime
     * for the given vault: foundation built-ins plus anything in `<vaultPath>/.agents/`.
     *
     * Wire format: `[{"name":"explorer","description":"...","tools":[...]}, ...]`
     * Returns `"[]"` if `vaultPath` is empty or the registry build fails.
     */
    external fun nativeListAgents(vaultPath: String): String
```

**Step 3: Verify Kotlin compiles**
Run: `./gradlew :app:compileDebugKotlin 2>&1 | tail -20`
Expected: errors will appear in `AmplifierSession.kt` (it doesn't yet pass `vaultPath`). Note them — they are **expected** and will be fixed in Task 8. The error must specifically be about the missing `vaultPath` argument, not any other Kotlin-level mistake.

**Step 4: Commit**
```
git add app/src/main/kotlin/com/vela/app/ai/AmplifierBridge.kt
git commit -m "feat(AmplifierBridge): add vaultPath param + nativeListAgents external"
```

---

### Task 8: Update `AmplifierSession` to accept and forward `vaultPath`

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/ai/AmplifierSession.kt`
- Modify: `app/src/main/kotlin/com/vela/app/engine/InferenceSession.kt` (interface — add the param)

**Step 1: Read the InferenceSession interface**
Run: `cat app/src/main/kotlin/com/vela/app/engine/InferenceSession.kt`
You need to know the exact `runTurn` signature — `AmplifierSession.runTurn` overrides it.

**Step 2: Add `vaultPath: String` to `InferenceSession.runTurn`**
In `InferenceSession.kt`, add `vaultPath: String,` as a new parameter immediately after `systemPrompt: String,` (matching the position in `AmplifierBridge.nativeRun`). Keep the default `= ""` if the interface uses defaults; otherwise leave required.

**Step 3: Edit `AmplifierSession.runTurn` to accept and forward the parameter**
In `app/src/main/kotlin/com/vela/app/ai/AmplifierSession.kt`, edit the `runTurn` signature (lines 40–50) to include `vaultPath`:

```kotlin
    override suspend fun runTurn(
        historyJson:       String,
        userInput:         String,
        userContentJson:   String?,
        systemPrompt:      String,
        vaultPath:         String,
        onToolStart:       (suspend (name: String, argsJson: String) -> String),
        onToolEnd:         (suspend (stableId: String, result: String) -> Unit),
        onToken:           (suspend (token: String) -> Unit),
        onProviderRequest: (suspend () -> String?),
        onServerTool:      (suspend (name: String, argsJson: String) -> Unit),
    ) {
```

Then in the `AmplifierBridge.nativeRun(...)` call (lines 56–93), add `vaultPath = vaultPath,` immediately after `systemPrompt = systemPrompt,`:

```kotlin
            systemPrompt      = systemPrompt,
            vaultPath         = vaultPath,
            tokenCb           = { token ->
```

**Step 4: Update every caller of `runTurn` to pass `vaultPath`**
Run: `grep -rn "runTurn(" app/src/main/kotlin/`
For each caller (likely `InferenceEngine.kt`), pass the active vault's `localPath`. The canonical lookup is:
```kotlin
val vaultPath = vaultRegistry.enabledVaults.value
    .firstOrNull { it.id in activeVaultIds }
    ?.localPath
    ?: ""
```
Inject `vaultPath` at the call site. If `InferenceEngine` already takes `activeVaultIds`, derive `vaultPath` there from its existing `vaultRegistry` injection.

**Step 5: Verify clean Kotlin compile**
Run: `./gradlew :app:compileDebugKotlin 2>&1 | tail -20`
Expected: `BUILD SUCCESSFUL` for the `compileDebugKotlin` task. No new warnings in changed files.

**Step 6: Commit**
```
git add app/src/main/kotlin/com/vela/app/ai/AmplifierSession.kt app/src/main/kotlin/com/vela/app/engine/InferenceSession.kt app/src/main/kotlin/com/vela/app/engine/InferenceEngine.kt
git commit -m "feat(AmplifierSession): thread vaultPath through runTurn to JNI"
```

---

### Task 9: Add an `AgentRef` data class + JSON parser (TDD: failing JVM test first)

**Files:**
- Create: `app/src/main/kotlin/com/vela/app/ai/AgentRef.kt`
- Create: `app/src/test/kotlin/com/vela/app/ai/AgentRefTest.kt`

**Step 1: Write the failing test first**
Create `app/src/test/kotlin/com/vela/app/ai/AgentRefTest.kt`:

```kotlin
package com.vela.app.ai

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AgentRefTest {

    @Test
    fun `parses empty JSON array`() {
        assertThat(AgentRef.parseJsonArray("[]")).isEmpty()
    }

    @Test
    fun `parses a single agent`() {
        val json = """[{"name":"explorer","description":"recon","tools":["filesystem"]}]"""
        val parsed = AgentRef.parseJsonArray(json)
        assertThat(parsed).hasSize(1)
        assertThat(parsed[0].name).isEqualTo("explorer")
        assertThat(parsed[0].description).isEqualTo("recon")
        assertThat(parsed[0].tools).containsExactly("filesystem")
    }

    @Test
    fun `parses multiple foundation agents`() {
        val json = """[
            {"name":"explorer","description":"a","tools":[]},
            {"name":"zen-architect","description":"b","tools":[]},
            {"name":"bug-hunter","description":"c","tools":[]}
        ]"""
        val parsed = AgentRef.parseJsonArray(json)
        assertThat(parsed.map { it.name })
            .containsExactly("explorer", "zen-architect", "bug-hunter")
            .inOrder()
    }

    @Test
    fun `tolerates missing tools field`() {
        val json = """[{"name":"x","description":"y"}]"""
        val parsed = AgentRef.parseJsonArray(json)
        assertThat(parsed[0].tools).isEmpty()
    }

    @Test
    fun `returns empty list on malformed input`() {
        assertThat(AgentRef.parseJsonArray("not json")).isEmpty()
        assertThat(AgentRef.parseJsonArray("")).isEmpty()
    }
}
```

**Step 2: Run the test — verify it fails (compile error: `AgentRef` does not exist)**
Run: `./gradlew :app:testDebugUnitTest --tests com.vela.app.ai.AgentRefTest 2>&1 | tail -15`
Expected: compilation failure mentioning `AgentRef`.

**Step 3: Write the minimal implementation**
Create `app/src/main/kotlin/com/vela/app/ai/AgentRef.kt`:

```kotlin
package com.vela.app.ai

import org.json.JSONArray
import org.json.JSONObject

/**
 * UI-side mirror of [`amplifier_module_agent_runtime::AgentConfig`].
 *
 * Populated by parsing the JSON returned by [`AmplifierBridge.nativeListAgents`].
 * [tools] is empty when the agent inherits all available tools.
 */
data class AgentRef(
    val name: String,
    val description: String,
    val tools: List<String>,
) {
    companion object {
        /**
         * Parse the JSON array returned by `nativeListAgents`. Returns an
         * empty list for any malformed input — the caller treats "no agents"
         * and "parse failed" the same way.
         */
        fun parseJsonArray(json: String): List<AgentRef> = try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                AgentRef(
                    name        = obj.getString("name"),
                    description = obj.optString("description", ""),
                    tools       = obj.optJSONArray("tools")
                        ?.let { ts -> (0 until ts.length()).map { ts.getString(it) } }
                        ?: emptyList(),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
```

**Step 4: Run the test — verify GREEN**
Run: `./gradlew :app:testDebugUnitTest --tests com.vela.app.ai.AgentRefTest 2>&1 | tail -10`
Expected: `Tests: 5 passed`.

**Step 5: Commit**
```
git add app/src/main/kotlin/com/vela/app/ai/AgentRef.kt app/src/test/kotlin/com/vela/app/ai/AgentRefTest.kt
git commit -m "feat(AgentRef): JSON parser for nativeListAgents output + tests"
```

---

### Task 10: Extend `ConversationViewModel` with agent state (TDD)

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/ui/conversation/ConversationViewModel.kt`
- Create: `app/src/test/kotlin/com/vela/app/ui/conversation/AgentStateTest.kt` (pure helper test — keeps coverage even though we don't unit-test the VM directly)

**Step 1: Write the failing helper test**
The agent-message wrapping is pure logic worth its own test. Create `app/src/test/kotlin/com/vela/app/ui/conversation/AgentStateTest.kt`:

```kotlin
package com.vela.app.ui.conversation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AgentStateTest {

    @Test
    fun `null agent returns input unchanged`() {
        assertThat(buildAgentScopedInput(null, "hello")).isEqualTo("hello")
    }

    @Test
    fun `non-null agent wraps input with delegate directive`() {
        val out = buildAgentScopedInput("explorer", "find the README")
        assertThat(out).contains("delegate")
        assertThat(out).contains("agent=\"explorer\"")
        assertThat(out).contains("find the README")
    }

    @Test
    fun `agent name with special chars is preserved verbatim`() {
        val out = buildAgentScopedInput("foundation:explorer", "go")
        assertThat(out).contains("foundation:explorer")
    }

    @Test
    fun `empty user input still produces a directive when agent set`() {
        val out = buildAgentScopedInput("explorer", "")
        assertThat(out).contains("delegate")
        assertThat(out).contains("explorer")
    }
}
```

**Step 2: Run the test — verify failure (compile error: `buildAgentScopedInput` not defined)**
Run: `./gradlew :app:testDebugUnitTest --tests com.vela.app.ui.conversation.AgentStateTest 2>&1 | tail -15`
Expected: compile error.

**Step 3: Write the minimal helper at the top of `ConversationScreen.kt`**
Add the helper to `app/src/main/kotlin/com/vela/app/ui/conversation/ConversationScreen.kt` directly under the existing `internal fun buildAttachedMessage(...)` (around line 48):

```kotlin
/**
 * Pure helper: when an agent chip is active, wrap the user's message into a
 * directive instructing the assistant to call `delegate` with that agent.
 *
 * Returns [input] unchanged when [agentName] is null.
 */
internal fun buildAgentScopedInput(agentName: String?, input: String): String =
    if (agentName == null) input else
        "Use the `delegate` tool with agent=\"$agentName\" and instruction set to the " +
        "following user message, then return its result. Do not respond directly first.\n\n" +
        "User message:\n$input"
```

**Step 4: Run the helper test — GREEN**
Run: `./gradlew :app:testDebugUnitTest --tests com.vela.app.ui.conversation.AgentStateTest 2>&1 | tail -10`
Expected: `Tests: 4 passed`.

**Step 5: Add agent state + actions to `ConversationViewModel`**
In `app/src/main/kotlin/com/vela/app/ui/conversation/ConversationViewModel.kt`, after the `_sessionActiveVaultIds` block (~line 110), add:

```kotlin
    // ── Agent picker state ────────────────────────────────────────────
    private val _availableAgents = MutableStateFlow<List<com.vela.app.ai.AgentRef>>(emptyList())
    val availableAgents: StateFlow<List<com.vela.app.ai.AgentRef>> = _availableAgents.asStateFlow()

    private val _activeAgentName = MutableStateFlow<String?>(null)
    val activeAgentName: StateFlow<String?> = _activeAgentName.asStateFlow()

    /** Toggle: tapping the active agent clears it; tapping a different one selects it. */
    fun setActiveAgent(name: String?) {
        _activeAgentName.value = if (_activeAgentName.value == name) null else name
    }

    /**
     * Refresh the agent registry from the current active vault.
     * Called on session start and after the user installs a new agent file.
     */
    fun refreshAgents() {
        viewModelScope.launch(Dispatchers.IO) {
            val path = activeVaultPath() ?: run {
                _availableAgents.value = emptyList()
                return@launch
            }
            val json = com.vela.app.ai.AmplifierBridge.nativeListAgents(path)
            _availableAgents.value = com.vela.app.ai.AgentRef.parseJsonArray(json)
        }
    }

    /** Active vault filesystem root, or null if no vault is selected for the session. */
    fun activeVaultPath(): String? {
        val activeId = _sessionActiveVaultIds.value.firstOrNull() ?: return null
        return allVaults.value.firstOrNull { it.id == activeId }?.localPath
    }
```

Also: hook `refreshAgents()` into the existing session-start path. Find the line that sets `_sessionActiveVaultIds.value = loadVaultSelection(convId)` (~line 129) and append `refreshAgents()` immediately after it. Likewise inside `toggleVaultForSession` after the `update { ... }` block (~line 176): call `refreshAgents()` so changing the vault updates the chip row.

**Step 6: Update `sendMessage` to apply the agent wrapper**
Locate the existing `sendMessage` method (around line 240–270 — it calls `inferenceEngine.startTurn(...)`). Wrap the user input via the helper before passing it down:

```kotlin
    val effectiveInput = buildAgentScopedInput(_activeAgentName.value, input)
    val turnId = inferenceEngine.startTurn(convId, effectiveInput, activeVaultIds = _sessionActiveVaultIds.value)
```
(Use whatever the existing variable name is for the user input — replace `input` with the actual local. The point: pass the wrapped string into `startTurn`.)

**Step 7: Verify Kotlin compiles + all unit tests pass**
Run: `./gradlew :app:testDebugUnitTest 2>&1 | tail -15`
Expected: `BUILD SUCCESSFUL`. All previously passing tests + the new `AgentStateTest` and `AgentRefTest` are green.

**Step 8: Commit**
```
git add app/src/main/kotlin/com/vela/app/ui/conversation/ConversationViewModel.kt app/src/main/kotlin/com/vela/app/ui/conversation/ConversationScreen.kt app/src/test/kotlin/com/vela/app/ui/conversation/AgentStateTest.kt
git commit -m "feat(ConversationViewModel): agent registry state + setActiveAgent/refreshAgents"
```

---

### Task 11: Lifecycle wiring — refresh agents on session start

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/ui/conversation/ConversationViewModel.kt` (init block)

**Step 1: Add an init block (or extend existing one) to call `refreshAgents` once `allVaults` is populated**
Locate the `class ConversationViewModel @Inject constructor(...) : ViewModel() {` body. Add or extend an `init { ... }` block:

```kotlin
    init {
        // Trigger an initial agent list as soon as the vault list resolves to non-empty.
        viewModelScope.launch {
            allVaults.first { it.isNotEmpty() }
            refreshAgents()
        }
    }
```

(Make sure `kotlinx.coroutines.flow.first` is imported.)

**Step 2: Verify clean compile**
Run: `./gradlew :app:compileDebugKotlin 2>&1 | tail -10`
Expected: clean.

**Step 3: Commit**
```
git add app/src/main/kotlin/com/vela/app/ui/conversation/ConversationViewModel.kt
git commit -m "feat(ConversationViewModel): initial agent list on first non-empty vaults"
```

---

# PHASE 8C — Compose UI + verification gates

### Task 12: Add the `AgentChipRow` composable

**Files:**
- Create: `app/src/main/kotlin/com/vela/app/ui/conversation/AgentChipRow.kt`

**Step 1: Write the new composable file**
Create `app/src/main/kotlin/com/vela/app/ui/conversation/AgentChipRow.kt`:

```kotlin
package com.vela.app.ui.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.vela.app.ai.AgentRef

/**
 * Horizontal chip row showing every available agent for the active vault, plus
 * a trailing `[+]` chip that opens the agent install browser.
 *
 * Tapping a chip selects that agent for the next message; tapping the active
 * chip again clears the selection.
 *
 * Renders nothing when [agents] is empty (no vault active or no agents loaded).
 */
@Composable
internal fun AgentChipRow(
    agents: List<AgentRef>,
    activeAgent: String?,
    onAgentClick: (String?) -> Unit,
    onInstallClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (agents.isEmpty()) return

    LazyRow(
        modifier            = modifier,
        contentPadding      = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(items = agents, key = { it.name }) { agent ->
            FilterChip(
                selected = agent.name == activeAgent,
                onClick  = { onAgentClick(agent.name) },
                label    = {
                    Text(
                        text     = agent.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor     = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        }
        // Trailing [+] chip — opens the install picker.
        item(key = "__install__") {
            FilterChip(
                selected = false,
                onClick  = onInstallClick,
                label    = { Icon(Icons.Default.Add, contentDescription = "Install agent") },
            )
        }
    }
}

// ─────────────────────────────────────────── Preview ───────────────────────────────────────────

@Preview(showBackground = true, name = "AgentChipRow — foundation agents")
@Composable
private fun AgentChipRowPreview() {
    val foundation = listOf(
        AgentRef("explorer",         "recon", emptyList()),
        AgentRef("zen-architect",    "design", emptyList()),
        AgentRef("bug-hunter",       "debug", emptyList()),
        AgentRef("git-ops",          "git",   emptyList()),
        AgentRef("modular-builder",  "build", emptyList()),
        AgentRef("security-guardian","sec",   emptyList()),
    )
    MaterialTheme {
        AgentChipRow(
            agents         = foundation,
            activeAgent    = "explorer",
            onAgentClick   = {},
            onInstallClick = {},
        )
    }
}
```

**Step 2: Verify clean compile**
Run: `./gradlew :app:compileDebugKotlin 2>&1 | tail -10`
Expected: clean.

**Step 3: Commit**
```
git add app/src/main/kotlin/com/vela/app/ui/conversation/AgentChipRow.kt
git commit -m "feat(ui): AgentChipRow composable + preview"
```

---

### Task 13: Wire `AgentChipRow` into `ConversationScreen` above the composer

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/ui/conversation/ConversationScreen.kt`

**Step 1: Read the current `bottomBar` block (~lines 259–274)**

**Step 2: Replace `bottomBar = { ComposerBox(...) }` with a `Column` containing the chip row + composer**
Edit `app/src/main/kotlin/com/vela/app/ui/conversation/ConversationScreen.kt`. First add the new state collectors near the top of `ConversationScreen` (right after the `var showVaultMenu` declaration ~line 88):

```kotlin
    val availableAgents by viewModel.availableAgents.collectAsState()
    val activeAgentName by viewModel.activeAgentName.collectAsState()
    var showAgentInstaller by remember { mutableStateOf(false) }
```

Then replace the `bottomBar = { ComposerBox(...) }` lambda (lines 259–274) with:

```kotlin
        bottomBar = {
            androidx.compose.foundation.layout.Column {
                // Agent chip row — only renders when vault is active and agents loaded.
                if (sessionActiveVaultIds.isNotEmpty()) {
                    AgentChipRow(
                        agents         = availableAgents,
                        activeAgent    = activeAgentName,
                        onAgentClick   = { viewModel.setActiveAgent(it) },
                        onInstallClick = { showAgentInstaller = true },
                    )
                }
                ComposerBox(
                    value              = textInput,
                    onValueChange      = { textInput = it },
                    onSend             = { handleSend() },
                    onRecord           = onRecord,
                    speechTranscriber  = speechTranscriber,
                    isListening        = isListening,
                    onMicClick         = { handleMic() },
                    attachments        = attachments,
                    onRemoveAttachment = { id -> attachments.removeIf { it.id == id } },
                    onPickPhoto        = { photoLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    onPickFile         = { fileLauncher.launch(arrayOf("*/*")) },
                    onCamera           = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
                )
            }
        },
```

Then, just *before* the closing `} // closes outer imePadding Box` (line 300), render the installer dialog:

```kotlin
    if (showAgentInstaller) {
        AgentInstallerDialog(
            vaultPath  = viewModel.activeVaultPath() ?: "",
            onDismiss  = { showAgentInstaller = false },
            onInstalled = {
                viewModel.refreshAgents()
                showAgentInstaller = false
            },
        )
    }
```

(`AgentInstallerDialog` is added in Task 14.)

**Step 3: Verify Kotlin compiles**
Run: `./gradlew :app:compileDebugKotlin 2>&1 | tail -15`
Expected: one compile error: `Unresolved reference: AgentInstallerDialog`. That's expected — Task 14 fixes it.

**Step 4: Commit**
```
git add app/src/main/kotlin/com/vela/app/ui/conversation/ConversationScreen.kt
git commit -m "feat(ui): wire AgentChipRow into ConversationScreen bottomBar"
```

---

### Task 14: Add the `AgentInstallerDialog` (vault `.agents/` browser)

**Files:**
- Create: `app/src/main/kotlin/com/vela/app/ui/conversation/AgentInstallerDialog.kt`

**Step 1: Write the dialog**
Create `app/src/main/kotlin/com/vela/app/ui/conversation/AgentInstallerDialog.kt`:

```kotlin
package com.vela.app.ui.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ListItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Lists every `.md` file in `<vaultPath>/.agents/`. Tapping a file dismisses
 * the dialog and triggers [onInstalled] — which the parent uses to refresh the
 * agent registry. ("Install" = the file is already on disk; this exposes it.)
 *
 * Renders an empty-state message if the directory is missing or empty.
 */
@Composable
internal fun AgentInstallerDialog(
    vaultPath: String,
    onDismiss: () -> Unit,
    onInstalled: () -> Unit,
) {
    var files by remember { mutableStateOf<List<File>>(emptyList()) }

    LaunchedEffect(vaultPath) {
        files = withContext(Dispatchers.IO) {
            val dir = File(vaultPath, ".agents")
            if (!dir.isDirectory) emptyList()
            else dir.listFiles { f -> f.isFile && f.name.endsWith(".md") }
                ?.toList()
                ?.sortedBy { it.name }
                ?: emptyList()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Install agent") },
        text  = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Drop `.md` agent bundles into ${'$'}vaultPath/.agents/ — they'll appear here.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (files.isEmpty()) {
                    Text(
                        "No agent files found in .agents/",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(items = files, key = { it.absolutePath }) { file ->
                            ListItem(
                                headlineContent = { Text(file.name) },
                                modifier        = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                supportingContent = {
                                    Text(
                                        text  = "${file.length()} bytes",
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onInstalled) { Text("Refresh") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss)   { Text("Close") }
        },
    )
}
```

**Step 2: Verify clean compile**
Run: `./gradlew :app:compileDebugKotlin 2>&1 | tail -10`
Expected: clean (no `Unresolved reference: AgentInstallerDialog`).

**Step 3: Commit**
```
git add app/src/main/kotlin/com/vela/app/ui/conversation/AgentInstallerDialog.kt
git commit -m "feat(ui): AgentInstallerDialog browser for vault/.agents/"
```

---

### Task 15: Full build + clippy + workspace test verification gates

**Files:** none (verification only)

**Step 1: `cargo build` for the Android target via gradle**
Run: `./gradlew :app:assembleDebug 2>&1 | tail -30`
Expected: `BUILD SUCCESSFUL`. The cdylib must compile; Kotlin must compile; resources must merge.

**Step 2: Kotlin warnings check**
Run: `./gradlew :app:compileDebugKotlin --warning-mode all 2>&1 | grep -E "(warning:|w:)" | grep -v "deprecation" | head -20`
Expected: no **new** warnings introduced by Phase 8 files (`AgentRef.kt`, `AgentChipRow.kt`, `AgentInstallerDialog.kt`, the modified `AmplifierBridge.kt` / `AmplifierSession.kt` / `ConversationScreen.kt` / `ConversationViewModel.kt`). Pre-existing warnings elsewhere are out of scope.

**Step 3: Full JVM unit-test suite**
Run: `./gradlew :app:testDebugUnitTest 2>&1 | tail -20`
Expected: `BUILD SUCCESSFUL` and all previously passing tests + Phase 8 tests (`AgentRefTest`, `AgentStateTest`) green.

**Step 4: Cross-workspace Rust verification**
Run: `cd /Users/ken/workspace/amplifier-rust && cargo test --workspace 2>&1 | tail -10`
Expected: `test result: ok` across the workspace (the new agent-runtime/foundation/delegate crates have their own test suites that must remain green).

Run: `cd /Users/ken/workspace/amplifier-rust && cargo clippy --workspace -- -D warnings 2>&1 | tail -20`
Expected: clean (zero warnings → zero errors at `-D warnings`).

Run: `cd /Users/ken/workspace/vela/app/src/main/rust/amplifier-android && cargo test --lib 2>&1 | tail -10`
Expected: `test result: ok` for the amplifier-android crate's own tests.

Run: `cd /Users/ken/workspace/vela/app/src/main/rust/amplifier-android && cargo clippy --lib -- -D warnings 2>&1 | tail -10`
Expected: clean.

**Step 5: If any gate fails, stop and fix before continuing.** No commit at this gate — verification only.

---

### Task 16: Android instrumented test for `nativeListAgents` JNI surface

**Files:**
- Create: `app/src/androidTest/kotlin/com/vela/app/ai/AmplifierBridgeAgentsTest.kt`

(Note: This is the *real* JNI verification the Phase 8 spec requires. JVM unit tests in `app/src/test/` cannot load the cdylib, which is built only for Android ABIs by the gradle plugin. Android instrumented tests run on an emulator/device with the actual `libamplifier_android.so` loaded.)

**Step 1: Write the failing instrumented test**
Create `app/src/androidTest/kotlin/com/vela/app/ai/AmplifierBridgeAgentsTest.kt`:

```kotlin
package com.vela.app.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AmplifierBridgeAgentsTest {

    private val cacheDir: File
        get() = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir

    @Test
    fun nativeListAgents_returns_six_foundation_agents_for_empty_vault() {
        val emptyVault = File(cacheDir, "vault_empty_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            val json = AmplifierBridge.nativeListAgents(emptyVault.absolutePath)
            val agents = AgentRef.parseJsonArray(json)

            // All six foundation agents must be present
            val names = agents.map { it.name }.toSet()
            for (expected in listOf(
                "explorer", "zen-architect", "bug-hunter",
                "git-ops", "modular-builder", "security-guardian",
            )) {
                assertThat(names).contains(expected)
            }
        } finally {
            emptyVault.deleteRecursively()
        }
    }

    @Test
    fun nativeListAgents_picks_up_vault_agents_directory() {
        val vault = File(cacheDir, "vault_with_agents_${System.currentTimeMillis()}").apply { mkdirs() }
        val agentsDir = File(vault, ".agents").apply { mkdirs() }
        File(agentsDir, "smoke.md").writeText(
            """
            ---
            meta:
              name: smoke-agent
              description: A test agent installed in the vault.
            ---
            You are a smoke-test agent.
            """.trimIndent()
        )
        try {
            val json   = AmplifierBridge.nativeListAgents(vault.absolutePath)
            val agents = AgentRef.parseJsonArray(json)
            assertThat(agents.map { it.name }).contains("smoke-agent")
            // Foundation agents must still be there alongside.
            assertThat(agents.map { it.name }).contains("explorer")
        } finally {
            vault.deleteRecursively()
        }
    }

    @Test
    fun nativeListAgents_returns_valid_json_for_blank_vault_path() {
        val json = AmplifierBridge.nativeListAgents("")
        // Must not crash; must parse; must contain the foundation agents (registry built with "")
        val agents = AgentRef.parseJsonArray(json)
        assertThat(agents.map { it.name }).contains("explorer")
    }
}
```

**Step 2: Verify the test class compiles**
Run: `./gradlew :app:compileDebugAndroidTestKotlin 2>&1 | tail -10`
Expected: clean.

**Step 3: Run the instrumented test on a connected device/emulator**
Pre-req: an Android emulator or device is running. Verify:
```
adb devices
```
Expected: at least one `device` (not `offline`).

Run the tests:
```
./gradlew :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=com.vela.app.ai.AmplifierBridgeAgentsTest \
    2>&1 | tail -30
```
Expected: `BUILD SUCCESSFUL` and `Tests passed: 3` reported by gradle.

If no emulator is available, document this as a manual gate: the instrumented tests must be run before merge. Do **not** mark Phase 8 complete without this gate green.

**Step 4: Document the manual UI verification step**
Append the following to `docs/superpowers/plans/2026-04-24-phase-8-android-agent-surface-plan.md` under a new `## Manual UI verification` section (this very file — at the end, after committing Task 16):

```
## Manual UI verification

After installing the debug APK on a device with at least one vault active:

1. Open ConversationScreen with a vault selected.
2. Verify the agent chip row renders above the composer with the six foundation
   agents (explorer, zen-architect, bug-hunter, git-ops, modular-builder,
   security-guardian) plus a trailing [+] chip.
3. Tap an agent chip — it must show a filled background indicating active state.
4. Tap the same chip again — it must clear to unselected.
5. Type "what files are here?" and send. Open the tool execution log
   (or logcat for "amplifier") — verify a `delegate` tool call appears with
   `agent="<selected>"` and the user message as `instruction`.
6. From a desktop, drop a new `.md` agent bundle into `<vault>/.agents/`,
   sync the vault, then tap the [+] chip in the chip row → "Refresh".
   The new agent must appear as a chip.
```

**Step 5: Commit**
```
git add app/src/androidTest/kotlin/com/vela/app/ai/AmplifierBridgeAgentsTest.kt docs/superpowers/plans/2026-04-24-phase-8-android-agent-surface-plan.md
git commit -m "test(amplifier-android): instrumented test for nativeListAgents + manual UI gate"
```

---

## Done definition

Phase 8 is complete when **all** of the following are true:

- ☐ `./gradlew :app:assembleDebug` → `BUILD SUCCESSFUL`
- ☐ `./gradlew :app:testDebugUnitTest` → all tests pass, including `AgentRefTest` and `AgentStateTest`
- ☐ `./gradlew :app:connectedDebugAndroidTest --tests com.vela.app.ai.AmplifierBridgeAgentsTest` → 3 tests pass
- ☐ `cd amplifier-rust && cargo test --workspace && cargo clippy --workspace -- -D warnings` → clean
- ☐ `cd app/src/main/rust/amplifier-android && cargo test --lib && cargo clippy --lib -- -D warnings` → clean
- ☐ Manual UI verification steps 1–6 above all pass on a real device

If any item is unchecked, Phase 8 is incomplete — surface the failure and stop. Do not declare success without evidence.
