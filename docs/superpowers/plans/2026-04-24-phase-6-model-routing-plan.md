# Phase 6: Model Role Routing Implementation Plan

> **Execution:** Use the subagent-driven-development workflow to implement this plan.

**Goal:** Port Python `amplifier-module-hooks-routing` (from `microsoft/amplifier-bundle-routing-matrix`) to Rust as a new workspace crate `amplifier-module-hooks-routing` that lets agents declare a `model_role` (e.g. `"fast"`, `"reasoning"`) and have it resolved at session start to a concrete `(provider, model)` pair via a YAML routing matrix.

**Architecture:** A new crate ships seven bundled YAML matrices (balanced is the default). At construction, `HooksRouting::new()` walks a 3-level search path (cwd, `~/.amplifier`, bundled), loads the requested matrix, applies optional overrides via `compose_matrix()` (with the `"base"` splice keyword), and stores a frozen `MatrixConfig`. Two hooks are registered: a `SessionStart` hook that resolves every agent's `model_role` against the live `ProviderMap` (using fnmatch globs + a version-aware sort to pick newest models), and a `ProviderRequest` hook that injects the available-roles catalog into the system prompt. `AgentConfig` gains two new optional fields: `model_role: Option<ModelRole>` and `provider_preferences: Option<Vec<ResolvedProvider>>`. The resolver, composer, validator, and version sorter are all deterministic pure functions and are unit-tested in isolation.

**Tech Stack:** Rust 2021 (workspace `amplifier-rust`), tokio, async-trait, serde + serde_yaml + serde_json, anyhow, `globset` for fnmatch, `regex` for the date-suffix strip.

---

## Source-of-truth alignment

This phase implements the Python `amplifier-module-hooks-routing` module from `microsoft/amplifier-bundle-routing-matrix`. The contracts in this plan are sourced directly from the Python implementation. **Do not deviate**:

- Module entry-point key: `hooks-routing`
- Two hooks fire: `provider:request` (priority 15, system-prompt addendum) and `session:start` (priority 5, agent rewrite)
- Matrix file search order (first found wins): `<cwd>/.amplifier/routing/<name>.yaml` → `~/.amplifier/routing/<name>.yaml` → bundled `<crate>/routing/<name>.yaml`
- Override `"base"` token: 0 = full replace, 1 = splice base candidates at that position, 2+ = error
- Validation: top-level must have `roles`; `roles` must contain `general` and `fast`; each role must have `description` + `candidates`; matrix files (not overrides) must not contain literal `"base"` candidates
- Resolver loop: outer over `roles`, inner over `candidates`; first non-None wins; glob matches sorted by `version_sort_key` descending
- Provider type lookup tries 3 forms: `name`, `name.trim_start_matches("provider-")`, `format!("provider-{name}")`

---

## Files Affected

| File | Change |
|---|---|
| `Cargo.toml` (workspace) | Add new member; add `globset` and `regex` to workspace deps; add `amplifier-module-hooks-routing` path dep |
| `crates/amplifier-module-hooks-routing/Cargo.toml` | New |
| `crates/amplifier-module-hooks-routing/src/lib.rs` | New — `HooksRouting`, `RoutingConfig`, mount/register |
| `crates/amplifier-module-hooks-routing/src/matrix.rs` | New — types, validator, loader |
| `crates/amplifier-module-hooks-routing/src/composer.rs` | New — `compose_matrix` with `"base"` splice |
| `crates/amplifier-module-hooks-routing/src/resolver.rs` | New — `resolve_model_role`, `find_provider_by_type`, `_resolve_glob`, `version_sort_key` |
| `crates/amplifier-module-hooks-routing/routing/{balanced,quality,economy,anthropic,openai,gemini,copilot}.yaml` | New — 7 bundled matrices |
| `crates/amplifier-module-hooks-routing/tests/integration_test.rs` | New |
| `crates/amplifier-module-agent-runtime/src/lib.rs` | Add `ModelRole` enum, `ResolvedProvider`, two new optional `AgentConfig` fields, `set_provider_preferences()` setter; parse `model_role` in `parse_agent_file` |
| `sandbox/amplifier-android-sandbox/src/main.rs` | Construct `HooksRouting`, register hooks |

**No changes** to `amplifier-core`. **No changes** to the public `Hook` / `Provider` traits.

---

## Working Directory

All commands assume `cd /Users/ken/workspace/amplifier-rust` unless stated.

---

## Task 1: Extend `AgentConfig` with `ModelRole` and `provider_preferences` (test first)

**Files:**
- Modify: `crates/amplifier-module-agent-runtime/src/lib.rs`

**Step 1: Write the failing test**

Append inside the existing `#[cfg(test)] mod tests { ... }` block, just before its closing brace:

```rust
    #[test]
    fn agent_config_supports_model_role_single_and_chain() {
        let single = AgentConfig {
            name: "explorer".to_string(),
            description: String::new(),
            tools: vec![],
            instruction: String::new(),
            model_role: Some(ModelRole::Single("fast".to_string())),
            provider_preferences: None,
        };
        assert!(matches!(single.model_role, Some(ModelRole::Single(ref s)) if s == "fast"));

        let chain = AgentConfig {
            name: "zen-architect".to_string(),
            description: String::new(),
            tools: vec![],
            instruction: String::new(),
            model_role: Some(ModelRole::Chain(vec![
                "reasoning".to_string(),
                "general".to_string(),
            ])),
            provider_preferences: None,
        };
        assert!(matches!(&chain.model_role, Some(ModelRole::Chain(v)) if v.len() == 2));
    }

    #[test]
    fn parse_agent_file_reads_model_role_string() {
        let md = "---\nmeta:\n  name: explorer\n  description: x\nmodel_role: fast\n---\nbody";
        let cfg = parse_agent_file(md).expect("must parse");
        assert!(matches!(cfg.model_role, Some(ModelRole::Single(ref s)) if s == "fast"));
    }

    #[test]
    fn parse_agent_file_reads_model_role_list() {
        let md = "---\nmeta:\n  name: zen\n  description: x\nmodel_role:\n  - reasoning\n  - general\n---\nbody";
        let cfg = parse_agent_file(md).expect("must parse");
        assert!(matches!(&cfg.model_role, Some(ModelRole::Chain(v)) if v == &vec!["reasoning".to_string(), "general".to_string()]));
    }

    #[test]
    fn registry_set_provider_preferences_updates_existing_agent() {
        let mut registry = AgentRegistry::new();
        registry.register(AgentConfig {
            name: "a".to_string(),
            description: String::new(),
            tools: vec![],
            instruction: String::new(),
            model_role: Some(ModelRole::Single("fast".to_string())),
            provider_preferences: None,
        });
        let prefs = vec![ResolvedProvider {
            provider: "anthropic".to_string(),
            model: "claude-haiku-3".to_string(),
            config: serde_json::Value::Null,
        }];
        registry.set_provider_preferences("a", prefs.clone());
        assert_eq!(
            registry.get("a").unwrap().provider_preferences.as_ref().unwrap()[0].model,
            "claude-haiku-3"
        );
    }
```

**Step 2: Run tests to verify they fail**

Run: `cargo test -p amplifier-module-agent-runtime agent_config_supports_model_role_single_and_chain parse_agent_file_reads_model_role_string parse_agent_file_reads_model_role_list registry_set_provider_preferences_updates_existing_agent`
Expected: FAIL — `ModelRole` and `ResolvedProvider` undefined; missing fields on `AgentConfig`.

**Step 3: Add the types and field, update parser, add setter**

Replace the `AgentConfig` struct and add new types above it. The full diff:

After the existing `use` block (top of file) and before the `// AgentConfig` divider, add:

```rust
// ---------------------------------------------------------------------------
// ModelRole
// ---------------------------------------------------------------------------

/// A single role name or an ordered fallback chain of role names.
///
/// Mirrors the Python `amplifier-module-hooks-routing` shape: `model_role:`
/// in agent frontmatter may be either a string or a list of strings.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(untagged)]
pub enum ModelRole {
    Single(String),
    Chain(Vec<String>),
}

// ---------------------------------------------------------------------------
// ResolvedProvider
// ---------------------------------------------------------------------------

/// A `(provider, model)` pair plus opaque pass-through config, produced by
/// the routing resolver and stored on `AgentConfig::provider_preferences`.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ResolvedProvider {
    pub provider: String,
    pub model: String,
    #[serde(default)]
    pub config: serde_json::Value,
}
```

Replace the existing `AgentConfig` struct with:

```rust
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AgentConfig {
    pub name: String,
    pub description: String,
    pub tools: Vec<String>,
    pub instruction: String,

    /// Optional declared model role(s) — single name or fallback chain.
    /// Resolved by the routing hook at session start.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub model_role: Option<ModelRole>,

    /// Resolved `(provider, model, config)` set by the routing hook.
    /// `None` until SessionStart fires and resolution succeeds.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub provider_preferences: Option<Vec<ResolvedProvider>>,
}
```

Inside `parse_agent_file`, just before `Some(AgentConfig { ... })`, add:

```rust
    let model_role = yaml_value
        .get("model_role")
        .and_then(|v| serde_yaml::from_value::<ModelRole>(v.clone()).ok());
```

…and update the trailing struct literal to include the two new fields:

```rust
    Some(AgentConfig {
        name,
        description,
        tools,
        instruction,
        model_role,
        provider_preferences: None,
    })
```

Also fix the existing test `registry_register_and_get` and `registry_list_is_sorted` and any other test that constructs `AgentConfig` literally to add `model_role: None, provider_preferences: None`.

Add a setter on `AgentRegistry`:

```rust
    /// Set the resolved provider preferences on an existing agent.
    ///
    /// No-op if no agent with `name` is registered.
    pub fn set_provider_preferences(&mut self, name: &str, prefs: Vec<ResolvedProvider>) {
        if let Some(cfg) = self.agents.get_mut(name) {
            cfg.provider_preferences = Some(prefs);
        }
    }
```

**Step 4: Run tests to verify they pass**

Run: `cargo test -p amplifier-module-agent-runtime`
Expected: PASS — all old tests still green plus 4 new tests.

**Step 5: Confirm dependent crates still compile**

Run: `cargo build --workspace`
Expected: clean build (foundation_agents and any other constructor of `AgentConfig` may fail; if so, add `..Default::default()` is **not** an option since AgentConfig has no Default — instead, add `model_role: None, provider_preferences: None` at each call site). Fix until clean.

**Step 6: Commit**

```
git add crates/amplifier-module-agent-runtime amplifier-agent-foundation
git commit -m "feat(agent-runtime): add ModelRole and provider_preferences to AgentConfig"
```

---

## Task 2: Scaffold the `amplifier-module-hooks-routing` crate

**Files:**
- Create: `crates/amplifier-module-hooks-routing/Cargo.toml`
- Create: `crates/amplifier-module-hooks-routing/src/lib.rs` (skeleton)
- Modify: `Cargo.toml` (workspace root)

**Step 1: Add workspace member and shared deps**

Edit `Cargo.toml` — under `members`, append `"crates/amplifier-module-hooks-routing"`. Under `[workspace.dependencies]`, append:

```toml
amplifier-module-hooks-routing = { path = "crates/amplifier-module-hooks-routing" }
amplifier-module-orchestrator-loop-streaming = { path = "crates/amplifier-module-orchestrator-loop-streaming" }
globset = "0.4"
regex = "1"
```

(If `amplifier-module-orchestrator-loop-streaming` is already declared, leave the existing line alone.)

**Step 2: Create the crate manifest**

Create `crates/amplifier-module-hooks-routing/Cargo.toml`:

```toml
[package]
name = "amplifier-module-hooks-routing"
version = "0.1.0"
edition = "2021"
include = ["src/**", "routing/**", "Cargo.toml", "README.md"]

[dependencies]
amplifier-core = { workspace = true }
amplifier-module-agent-runtime = { workspace = true }
amplifier-module-orchestrator-loop-streaming = { workspace = true }
async-trait = { workspace = true }
serde = { workspace = true }
serde_json = { workspace = true }
serde_yaml = { workspace = true }
anyhow = { workspace = true }
tokio = { workspace = true }
globset = { workspace = true }
regex = { workspace = true }

[dev-dependencies]
tokio = { workspace = true, features = ["macros", "rt-multi-thread"] }
tempfile = "3"
```

**Step 3: Create stub `src/lib.rs`**

Create `crates/amplifier-module-hooks-routing/src/lib.rs`:

```rust
//! Model-role routing hook — Rust port of `amplifier-module-hooks-routing`.

pub mod composer;
pub mod matrix;
pub mod resolver;

// Public re-exports (filled in by later tasks).
pub use matrix::{Candidate, MatrixConfig, RoleConfig, RolesMap};
pub use resolver::{resolve_model_role, ProviderMap};
```

Create three empty stub modules (each one line `// stub` is fine — later tasks fill them):

```bash
printf '// stub - filled in by Task 3\n' > crates/amplifier-module-hooks-routing/src/matrix.rs
printf '// stub - filled in by Task 5\n' > crates/amplifier-module-hooks-routing/src/composer.rs
printf '// stub - filled in by Task 6\n' > crates/amplifier-module-hooks-routing/src/resolver.rs
mkdir -p crates/amplifier-module-hooks-routing/routing
mkdir -p crates/amplifier-module-hooks-routing/tests
```

The stub `lib.rs` re-exports types that don't exist yet — that's intentional for the test/fail/impl cycle. Comment them out for now:

```rust
//! Model-role routing hook — Rust port of `amplifier-module-hooks-routing`.

pub mod composer;
pub mod matrix;
pub mod resolver;
```

**Step 4: Verify the crate compiles in isolation**

Run: `cargo build -p amplifier-module-hooks-routing`
Expected: PASS — empty crate, three empty modules, no symbols.

**Step 5: Commit**

```
git add crates/amplifier-module-hooks-routing Cargo.toml
git commit -m "feat(hooks-routing): scaffold crate and workspace deps"
```

---

## Task 3: Implement matrix types and validator (test first)

**Files:**
- Modify: `crates/amplifier-module-hooks-routing/src/matrix.rs`

**Step 1: Write the failing tests**

Replace the contents of `src/matrix.rs` with:

```rust
//! Matrix types, YAML parsing, and validation.

use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct Candidate {
    pub provider: String,
    pub model: String,
    #[serde(default, skip_serializing_if = "serde_json::Value::is_null")]
    pub config: serde_json::Value,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct RoleConfig {
    pub description: String,
    pub candidates: Vec<serde_json::Value>, // raw — may contain "base" sentinel strings during composition
}

/// Roles map. `BTreeMap` for deterministic iteration order in tests and prompt output.
pub type RolesMap = BTreeMap<String, RoleConfig>;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MatrixConfig {
    pub name: String,
    #[serde(default)]
    pub description: String,
    #[serde(default)]
    pub updated: String,
    pub roles: RolesMap,
}

// ---------------------------------------------------------------------------
// Validation
// ---------------------------------------------------------------------------

/// Validate a matrix or override map.
///
/// `is_override = false` (matrix file): rejects literal `"base"` candidates;
/// requires both `general` and `fast` roles.
/// `is_override = true`: allows `"base"` candidates; does NOT require `general`/`fast`.
pub fn validate_matrix(roles: &RolesMap, is_override: bool) -> anyhow::Result<()> {
    if !is_override {
        if !roles.contains_key("general") {
            anyhow::bail!("matrix is missing required role 'general'");
        }
        if !roles.contains_key("fast") {
            anyhow::bail!("matrix is missing required role 'fast'");
        }
    }
    for (role_name, role) in roles {
        if role.description.is_empty() {
            anyhow::bail!("role '{role_name}' is missing 'description'");
        }
        if role.candidates.is_empty() {
            anyhow::bail!("role '{role_name}' has empty candidates");
        }
        if !is_override {
            for c in &role.candidates {
                if c.as_str() == Some("base") {
                    anyhow::bail!(
                        "matrix file may not contain literal 'base' candidates (role '{role_name}')"
                    );
                }
            }
        }
    }
    Ok(())
}

// ---------------------------------------------------------------------------
// Parse a candidate JSON value into a typed `Candidate` (skips "base" sentinel)
// ---------------------------------------------------------------------------

pub fn candidate_from_value(v: &serde_json::Value) -> Option<Candidate> {
    if v.as_str() == Some("base") {
        return None;
    }
    serde_json::from_value::<Candidate>(v.clone()).ok()
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn role(desc: &str, cands: Vec<serde_json::Value>) -> RoleConfig {
        RoleConfig {
            description: desc.to_string(),
            candidates: cands,
        }
    }

    #[test]
    fn validate_passes_for_minimal_valid_matrix() {
        let mut m: RolesMap = BTreeMap::new();
        m.insert(
            "general".into(),
            role("g", vec![json!({"provider": "x", "model": "y"})]),
        );
        m.insert(
            "fast".into(),
            role("f", vec![json!({"provider": "x", "model": "y"})]),
        );
        validate_matrix(&m, false).expect("should pass");
    }

    #[test]
    fn validate_rejects_missing_general() {
        let mut m: RolesMap = BTreeMap::new();
        m.insert(
            "fast".into(),
            role("f", vec![json!({"provider": "x", "model": "y"})]),
        );
        let err = validate_matrix(&m, false).unwrap_err().to_string();
        assert!(err.contains("general"), "got: {err}");
    }

    #[test]
    fn validate_rejects_missing_fast() {
        let mut m: RolesMap = BTreeMap::new();
        m.insert(
            "general".into(),
            role("g", vec![json!({"provider": "x", "model": "y"})]),
        );
        let err = validate_matrix(&m, false).unwrap_err().to_string();
        assert!(err.contains("fast"), "got: {err}");
    }

    #[test]
    fn validate_rejects_base_in_matrix_file() {
        let mut m: RolesMap = BTreeMap::new();
        m.insert(
            "general".into(),
            role("g", vec![json!("base"), json!({"provider": "x", "model": "y"})]),
        );
        m.insert(
            "fast".into(),
            role("f", vec![json!({"provider": "x", "model": "y"})]),
        );
        let err = validate_matrix(&m, false).unwrap_err().to_string();
        assert!(err.contains("base"), "got: {err}");
    }

    #[test]
    fn validate_allows_base_in_override() {
        let mut m: RolesMap = BTreeMap::new();
        m.insert(
            "reasoning".into(),
            role("r", vec![json!("base"), json!({"provider": "x", "model": "y"})]),
        );
        validate_matrix(&m, true).expect("override allows base");
    }

    #[test]
    fn validate_rejects_empty_candidates() {
        let mut m: RolesMap = BTreeMap::new();
        m.insert("general".into(), role("g", vec![]));
        m.insert(
            "fast".into(),
            role("f", vec![json!({"provider": "x", "model": "y"})]),
        );
        let err = validate_matrix(&m, false).unwrap_err().to_string();
        assert!(err.contains("empty candidates"), "got: {err}");
    }

    #[test]
    fn candidate_from_value_skips_base_sentinel() {
        assert!(candidate_from_value(&serde_json::json!("base")).is_none());
        let c = candidate_from_value(&serde_json::json!({"provider": "p", "model": "m"})).unwrap();
        assert_eq!(c.provider, "p");
        assert_eq!(c.model, "m");
    }
}
```

**Step 2: Run tests to verify they pass**

The tests are written so the impl above already satisfies them. Run:

`cargo test -p amplifier-module-hooks-routing matrix::`
Expected: 7/7 PASS.

**Step 3: Commit**

```
git add crates/amplifier-module-hooks-routing/src/matrix.rs
git commit -m "feat(hooks-routing): matrix types + validate_matrix"
```

---

## Task 4: Implement matrix file loader (search path)

**Files:**
- Modify: `crates/amplifier-module-hooks-routing/src/matrix.rs`

**Step 1: Write the failing test**

Append to `src/matrix.rs` (inside `mod tests`):

```rust
    #[test]
    fn loader_finds_bundled_matrix() {
        // The crate ships balanced.yaml; test it loads and validates.
        let bundled_dir = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("routing");
        let cfg = load_matrix_from_dirs("balanced", &[bundled_dir.as_path()])
            .expect("balanced.yaml should load");
        assert_eq!(cfg.name, "balanced");
        validate_matrix(&cfg.roles, false).expect("balanced must validate");
    }

    #[test]
    fn loader_returns_err_when_not_found() {
        let dir = tempfile::tempdir().unwrap();
        let err = load_matrix_from_dirs("does-not-exist", &[dir.path()]).unwrap_err().to_string();
        assert!(err.contains("does-not-exist"), "got: {err}");
    }

    #[test]
    fn loader_first_match_wins() {
        let dir1 = tempfile::tempdir().unwrap();
        let dir2 = tempfile::tempdir().unwrap();
        std::fs::write(
            dir1.path().join("test.yaml"),
            "name: test\nroles:\n  general:\n    description: g\n    candidates:\n      - {provider: a, model: b}\n  fast:\n    description: f\n    candidates:\n      - {provider: a, model: b}\n",
        ).unwrap();
        std::fs::write(
            dir2.path().join("test.yaml"),
            "name: SHOULD_NOT_LOAD\nroles: {}\n",
        ).unwrap();
        let cfg = load_matrix_from_dirs("test", &[dir1.path(), dir2.path()]).unwrap();
        assert_eq!(cfg.name, "test");
    }
```

**Step 2: Verify they fail**

Run: `cargo test -p amplifier-module-hooks-routing matrix::tests::loader`
Expected: FAIL — `load_matrix_from_dirs` undefined.

**Step 3: Implement the loader**

In `src/matrix.rs`, above the `#[cfg(test)]` block, add:

```rust
// ---------------------------------------------------------------------------
// File loader
// ---------------------------------------------------------------------------

/// Load `<name>.yaml` from the first directory in `dirs` that contains it.
/// Returns Err if not found in any dir, or if the file fails to parse.
pub fn load_matrix_from_dirs(
    name: &str,
    dirs: &[&std::path::Path],
) -> anyhow::Result<MatrixConfig> {
    for dir in dirs {
        let path = dir.join(format!("{name}.yaml"));
        if path.is_file() {
            let text = std::fs::read_to_string(&path)
                .map_err(|e| anyhow::anyhow!("failed to read {}: {e}", path.display()))?;
            let cfg: MatrixConfig = serde_yaml::from_str(&text)
                .map_err(|e| anyhow::anyhow!("failed to parse {}: {e}", path.display()))?;
            return Ok(cfg);
        }
    }
    anyhow::bail!("matrix '{name}' not found in any search directory")
}

/// Build the standard 3-level search path: `<cwd>/.amplifier/routing`,
/// `~/.amplifier/routing`, then the bundled `<crate>/routing`.
///
/// Returns owned `PathBuf`s so callers can hold them; non-existent dirs are
/// included (they simply yield no match in `load_matrix_from_dirs`).
pub fn default_search_dirs() -> Vec<std::path::PathBuf> {
    let mut out = Vec::with_capacity(3);
    if let Ok(cwd) = std::env::current_dir() {
        out.push(cwd.join(".amplifier").join("routing"));
    }
    if let Some(home) = std::env::var_os("HOME").map(std::path::PathBuf::from) {
        out.push(home.join(".amplifier").join("routing"));
    }
    out.push(std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("routing"));
    out
}
```

**Step 4: Run tests to verify pass — but balanced.yaml does not exist yet**

The first test `loader_finds_bundled_matrix` will still fail. That's intentional — it forces Task 7 (writing the YAML files) before this task is fully green.

Run only the unit tests that don't need the YAML:
`cargo test -p amplifier-module-hooks-routing matrix::tests::loader_returns_err_when_not_found matrix::tests::loader_first_match_wins`
Expected: PASS.

**Step 5: Commit**

```
git add crates/amplifier-module-hooks-routing/src/matrix.rs
git commit -m "feat(hooks-routing): matrix loader + default search path"
```

---

## Task 5: Implement `compose_matrix` (test first)

**Files:**
- Modify: `crates/amplifier-module-hooks-routing/src/composer.rs`

**Step 1: Write the failing tests**

Replace `src/composer.rs` with:

```rust
//! Compose a base matrix with overrides, supporting the `"base"` splice keyword.

use serde_json::Value;

use crate::matrix::{validate_matrix, RoleConfig, RolesMap};

/// Compose `overrides` onto `base`, returning a new `RolesMap`.
///
/// Rules (mirror Python `compose_matrix`):
/// * Roles not in `overrides` are inherited unchanged.
/// * Override candidates with **0** `"base"` tokens fully replace the base role's
///   candidate list.
/// * Override candidates with **exactly 1** `"base"` token splice the base role's
///   candidates at that position.
/// * Override candidates with **2+** `"base"` tokens return Err.
/// * `description` follows the override if provided, else inherits from base.
pub fn compose_matrix(base: &RolesMap, overrides: &Value) -> anyhow::Result<RolesMap> {
    let mut out = base.clone();

    let Some(over_roles) = overrides.get("roles").and_then(|v| v.as_object()) else {
        // Empty / missing overrides — return base unchanged.
        return Ok(out);
    };

    for (role_name, role_value) in over_roles {
        let cands_value = role_value
            .get("candidates")
            .ok_or_else(|| anyhow::anyhow!("override role '{role_name}' missing candidates"))?;
        let cands = cands_value
            .as_array()
            .ok_or_else(|| anyhow::anyhow!("override role '{role_name}' candidates must be array"))?;

        let base_count = cands.iter().filter(|c| c.as_str() == Some("base")).count();
        if base_count > 1 {
            anyhow::bail!(
                "override role '{role_name}' has {base_count} 'base' tokens (max 1)"
            );
        }

        let base_cands: Vec<Value> = base
            .get(role_name)
            .map(|r| r.candidates.clone())
            .unwrap_or_default();

        let new_candidates: Vec<Value> = if base_count == 0 {
            cands.clone()
        } else {
            // Splice base candidates at the position of the "base" token.
            let mut spliced = Vec::with_capacity(cands.len() + base_cands.len());
            for c in cands {
                if c.as_str() == Some("base") {
                    spliced.extend(base_cands.iter().cloned());
                } else {
                    spliced.push(c.clone());
                }
            }
            spliced
        };

        let description = role_value
            .get("description")
            .and_then(|v| v.as_str())
            .map(|s| s.to_string())
            .or_else(|| base.get(role_name).map(|r| r.description.clone()))
            .unwrap_or_default();

        out.insert(
            role_name.clone(),
            RoleConfig {
                description,
                candidates: new_candidates,
            },
        );
    }

    // Composed result is a matrix (not an override); validate strictly.
    validate_matrix(&out, false)?;
    Ok(out)
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    use std::collections::BTreeMap;

    fn base() -> RolesMap {
        let mut m: RolesMap = BTreeMap::new();
        m.insert(
            "general".into(),
            RoleConfig {
                description: "g".into(),
                candidates: vec![json!({"provider": "anthropic", "model": "claude-sonnet-*"})],
            },
        );
        m.insert(
            "fast".into(),
            RoleConfig {
                description: "f".into(),
                candidates: vec![json!({"provider": "anthropic", "model": "claude-haiku-*"})],
            },
        );
        m.insert(
            "reasoning".into(),
            RoleConfig {
                description: "r".into(),
                candidates: vec![json!({"provider": "anthropic", "model": "claude-opus-*"})],
            },
        );
        m
    }

    #[test]
    fn no_base_token_replaces_role_candidates() {
        let overrides = json!({
            "roles": {
                "reasoning": {
                    "candidates": [{"provider": "openai", "model": "gpt-5-pro"}]
                }
            }
        });
        let out = compose_matrix(&base(), &overrides).unwrap();
        let r = &out["reasoning"];
        assert_eq!(r.candidates.len(), 1);
        assert_eq!(r.candidates[0]["provider"], "openai");
    }

    #[test]
    fn single_base_token_splices_base_candidates() {
        let overrides = json!({
            "roles": {
                "reasoning": {
                    "candidates": [
                        {"provider": "openai", "model": "gpt-5-pro"},
                        "base",
                        {"provider": "ollama", "model": "llama3.2"}
                    ]
                }
            }
        });
        let out = compose_matrix(&base(), &overrides).unwrap();
        let cands = &out["reasoning"].candidates;
        assert_eq!(cands.len(), 3);
        assert_eq!(cands[0]["provider"], "openai");
        assert_eq!(cands[1]["provider"], "anthropic"); // spliced from base
        assert_eq!(cands[2]["provider"], "ollama");
    }

    #[test]
    fn double_base_token_errors() {
        let overrides = json!({
            "roles": {
                "reasoning": {
                    "candidates": ["base", {"provider": "x", "model": "y"}, "base"]
                }
            }
        });
        let err = compose_matrix(&base(), &overrides).unwrap_err().to_string();
        assert!(err.contains("'base'"), "got: {err}");
    }

    #[test]
    fn untouched_roles_inherit_from_base() {
        let overrides = json!({
            "roles": {
                "reasoning": { "candidates": [{"provider": "x", "model": "y"}] }
            }
        });
        let out = compose_matrix(&base(), &overrides).unwrap();
        assert_eq!(out["general"].candidates.len(), 1);
        assert_eq!(out["fast"].candidates.len(), 1);
        assert_eq!(out["general"].description, "g");
    }

    #[test]
    fn empty_overrides_returns_base() {
        let out = compose_matrix(&base(), &json!({})).unwrap();
        assert_eq!(out.len(), 3);
    }
}
```

**Step 2: Run tests**

Run: `cargo test -p amplifier-module-hooks-routing composer::`
Expected: 5/5 PASS.

**Step 3: Commit**

```
git add crates/amplifier-module-hooks-routing/src/composer.rs
git commit -m "feat(hooks-routing): compose_matrix with base-splice keyword"
```

---

## Task 6: Implement resolver (`version_sort_key`, `find_provider_by_type`, `_resolve_glob`, `resolve_model_role`) — test first

**Files:**
- Modify: `crates/amplifier-module-hooks-routing/src/resolver.rs`

**Step 1: Write the failing tests**

Replace `src/resolver.rs` with:

```rust
//! Resolver — turns a list of role names into a single `(provider, model)` pair.

use std::collections::HashMap;
use std::sync::Arc;

use amplifier_core::traits::Provider;
use amplifier_module_agent_runtime::ResolvedProvider;
use globset::{Glob, GlobMatcher};
use regex::Regex;

use crate::matrix::{candidate_from_value, RolesMap};

/// Map of provider short-name → `Arc<dyn Provider>`. Keys may be either
/// `"anthropic"` or `"provider-anthropic"`; `find_provider_by_type` handles both.
pub type ProviderMap = HashMap<String, Arc<dyn Provider>>;

// ---------------------------------------------------------------------------
// version_sort_key
// ---------------------------------------------------------------------------

/// One element of a version sort key — either a numeric run or a text run.
#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
pub enum VersionPart {
    /// A run of text. Compared lexicographically before numbers.
    Text(String),
    /// A run of digits, parsed as i64.
    Number(i64),
}

/// Mixed key: parts from name, then a final `i64` tiebreak = -name.len()
/// (shorter is "better": alias > snapshot).
#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
pub struct VersionKey {
    parts: Vec<VersionPart>,
    // Negative-length tiebreak. Wrapped in a struct so derive ordering applies fields in order.
    neg_len: i64,
}

/// Build a sortable key for a model name.
///
/// Algorithm (matches Python):
/// 1. Strip trailing date suffix `-YYYYMMDD` or `-YYYY-MM-DD`.
/// 2. Split on digit runs; digit runs become `Number(n)`, text runs become `Text(s)`.
/// 3. Append a final tiebreak `-(name.len() as i64)` (shorter = better).
///
/// Sort **descending** to put the highest version first.
pub fn version_sort_key(name: &str) -> VersionKey {
    // Strip date suffix.
    let date_re = Regex::new(r"-(?:\d{4}-\d{2}-\d{2}|\d{8})$").expect("regex compiles");
    let stripped = date_re.replace(name, "").into_owned();

    // Split into alternating text and digit runs.
    let digit_re = Regex::new(r"\d+").expect("regex compiles");
    let mut parts: Vec<VersionPart> = Vec::new();
    let mut last = 0;
    for m in digit_re.find_iter(&stripped) {
        if m.start() > last {
            parts.push(VersionPart::Text(stripped[last..m.start()].to_string()));
        }
        parts.push(VersionPart::Number(
            m.as_str().parse::<i64>().unwrap_or(0),
        ));
        last = m.end();
    }
    if last < stripped.len() {
        parts.push(VersionPart::Text(stripped[last..].to_string()));
    }

    VersionKey {
        parts,
        neg_len: -(name.len() as i64),
    }
}

// ---------------------------------------------------------------------------
// find_provider_by_type
// ---------------------------------------------------------------------------

/// Look up a provider in `providers` by trying three name forms in order:
/// `name`, `name.trim_start_matches("provider-")`, `format!("provider-{name}")`.
pub fn find_provider_by_type<'a>(
    providers: &'a ProviderMap,
    name: &str,
) -> Option<&'a Arc<dyn Provider>> {
    if let Some(p) = providers.get(name) {
        return Some(p);
    }
    let stripped = name.trim_start_matches("provider-");
    if stripped != name {
        if let Some(p) = providers.get(stripped) {
            return Some(p);
        }
    }
    let prefixed = format!("provider-{name}");
    if let Some(p) = providers.get(&prefixed) {
        return Some(p);
    }
    None
}

// ---------------------------------------------------------------------------
// _resolve_glob
// ---------------------------------------------------------------------------

/// Returns `true` if `s` contains any fnmatch metachar.
fn is_glob(s: &str) -> bool {
    s.contains('*') || s.contains('?') || s.contains('[')
}

/// Resolve a glob pattern against a provider's `list_models()` output.
///
/// Returns `Some(highest_version)` or `None` if no models match or the
/// provider call fails (errors are swallowed — never propagate).
pub async fn resolve_glob(pattern: &str, provider: &Arc<dyn Provider>) -> Option<String> {
    let glob: GlobMatcher = match Glob::new(pattern) {
        Ok(g) => g.compile_matcher(),
        Err(_) => return None,
    };

    // Catch ALL errors — return None on any failure.
    let models = match provider.list_models().await {
        Ok(m) => m,
        Err(_) => return None,
    };

    let names: Vec<String> = models.into_iter().map(|m| m.id).collect();

    let mut matched: Vec<String> = names
        .into_iter()
        .filter(|n| glob.is_match(n))
        .collect();

    // Sort descending (newest first).
    matched.sort_by(|a, b| version_sort_key(b).cmp(&version_sort_key(a)));
    matched.into_iter().next()
}

// ---------------------------------------------------------------------------
// resolve_model_role
// ---------------------------------------------------------------------------

/// Walk `roles` (prioritised list); for each, walk its candidates; return the
/// first one that resolves successfully. Returns an empty Vec if nothing matches.
pub async fn resolve_model_role(
    roles: &[String],
    matrix_roles: &RolesMap,
    providers: &ProviderMap,
) -> Vec<ResolvedProvider> {
    for role in roles {
        let Some(role_cfg) = matrix_roles.get(role) else {
            continue;
        };
        for cand_value in &role_cfg.candidates {
            let Some(cand) = candidate_from_value(cand_value) else {
                continue; // skip "base" sentinel if it slipped through
            };
            let Some(provider) = find_provider_by_type(providers, &cand.provider) else {
                continue;
            };
            let resolved_model = if is_glob(&cand.model) {
                match resolve_glob(&cand.model, provider).await {
                    Some(m) => m,
                    None => continue,
                }
            } else {
                cand.model.clone()
            };
            return vec![ResolvedProvider {
                provider: cand.provider,
                model: resolved_model,
                config: cand.config,
            }];
        }
    }
    Vec::new()
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;
    use amplifier_core::traits::ProviderError;
    use amplifier_core::types::{ModelInfo, ProviderInfo};
    use async_trait::async_trait;
    use std::pin::Pin;

    // ---- version_sort_key ----

    #[test]
    fn version_key_numeric_higher_wins() {
        let a = version_sort_key("claude-opus-4-10");
        let b = version_sort_key("claude-opus-4-7");
        assert!(a > b, "10 should sort above 7");
    }

    #[test]
    fn version_key_alias_beats_snapshot() {
        let alias = version_sort_key("gpt-5.4");
        let snapshot = version_sort_key("gpt-5.4-2026-03-05");
        assert!(alias > snapshot, "shorter alias should sort above pinned snapshot");
    }

    #[test]
    fn version_key_clean_version_above_dated() {
        let clean = version_sort_key("claude-opus-4-7");
        let dated = version_sort_key("claude-opus-4-20250514");
        assert!(clean > dated, "clean -4-7 should sort above date-suffixed sibling");
    }

    #[test]
    fn version_key_strips_compact_date_suffix() {
        let with_date = version_sort_key("foo-bar-20240101");
        let without = version_sort_key("foo-bar");
        // After strip, parts are equal; tiebreak picks shorter raw string.
        assert!(without > with_date);
    }

    // ---- find_provider_by_type ----

    struct StubProvider {
        name: &'static str,
        models: Vec<&'static str>,
        fail: bool,
    }

    #[async_trait]
    impl Provider for StubProvider {
        fn name(&self) -> &str {
            self.name
        }
        fn get_info(&self) -> ProviderInfo {
            ProviderInfo {
                name: self.name.to_string(),
                ..ProviderInfo::default()
            }
        }
        fn list_models(
            &self,
        ) -> Pin<Box<dyn std::future::Future<Output = Result<Vec<ModelInfo>, ProviderError>> + Send + '_>>
        {
            let fail = self.fail;
            let models: Vec<ModelInfo> = self
                .models
                .iter()
                .map(|id| ModelInfo {
                    id: id.to_string(),
                    ..ModelInfo::default()
                })
                .collect();
            Box::pin(async move {
                if fail {
                    Err(ProviderError::Other("stub".into()))
                } else {
                    Ok(models)
                }
            })
        }
        fn complete(
            &self,
            _request: amplifier_core::types::ChatRequest,
        ) -> Pin<Box<dyn std::future::Future<Output = Result<amplifier_core::types::ChatResponse, ProviderError>> + Send + '_>>
        {
            Box::pin(async { Err(ProviderError::Other("not used in tests".into())) })
        }
        fn parse_tool_calls(
            &self,
            _response: &amplifier_core::types::ChatResponse,
        ) -> Vec<amplifier_core::types::ToolCall> {
            Vec::new()
        }
    }

    fn map_with(name: &'static str, models: Vec<&'static str>) -> ProviderMap {
        let mut m = ProviderMap::new();
        m.insert(
            name.to_string(),
            Arc::new(StubProvider {
                name,
                models,
                fail: false,
            }) as Arc<dyn Provider>,
        );
        m
    }

    #[test]
    fn find_by_short_name() {
        let m = map_with("anthropic", vec![]);
        assert!(find_provider_by_type(&m, "anthropic").is_some());
    }

    #[test]
    fn find_by_provider_prefix_lookup() {
        let m = map_with("anthropic", vec![]);
        // Lookup with "provider-anthropic" should hit "anthropic".
        assert!(find_provider_by_type(&m, "provider-anthropic").is_some());
    }

    #[test]
    fn find_with_provider_prefix_in_map() {
        let mut m = ProviderMap::new();
        m.insert(
            "provider-foo".to_string(),
            Arc::new(StubProvider {
                name: "foo",
                models: vec![],
                fail: false,
            }) as Arc<dyn Provider>,
        );
        // Lookup with bare "foo" should hit "provider-foo".
        assert!(find_provider_by_type(&m, "foo").is_some());
    }

    #[test]
    fn find_returns_none_when_missing() {
        let m = map_with("anthropic", vec![]);
        assert!(find_provider_by_type(&m, "openai").is_none());
    }

    // ---- resolve_glob ----

    #[tokio::test]
    async fn resolve_glob_picks_highest_version() {
        let m = map_with(
            "anthropic",
            vec!["claude-opus-4-7", "claude-opus-4-10", "claude-opus-4-20250514"],
        );
        let provider = m.get("anthropic").unwrap();
        let chosen = resolve_glob("claude-opus-*", provider).await;
        assert_eq!(chosen.as_deref(), Some("claude-opus-4-10"));
    }

    #[tokio::test]
    async fn resolve_glob_returns_none_when_no_match() {
        let m = map_with("anthropic", vec!["claude-haiku-3"]);
        let provider = m.get("anthropic").unwrap();
        assert!(resolve_glob("gpt-*", provider).await.is_none());
    }

    #[tokio::test]
    async fn resolve_glob_swallows_provider_error() {
        let mut m = ProviderMap::new();
        m.insert(
            "boom".to_string(),
            Arc::new(StubProvider {
                name: "boom",
                models: vec![],
                fail: true,
            }) as Arc<dyn Provider>,
        );
        let provider = m.get("boom").unwrap();
        assert!(resolve_glob("anything-*", provider).await.is_none());
    }

    // ---- resolve_model_role ----

    #[tokio::test]
    async fn resolve_model_role_exact_name_passes_through() {
        use crate::matrix::RoleConfig;
        use std::collections::BTreeMap;

        let m = map_with("anthropic", vec!["claude-haiku-3"]);
        let mut roles: RolesMap = BTreeMap::new();
        roles.insert(
            "fast".into(),
            RoleConfig {
                description: "f".into(),
                candidates: vec![serde_json::json!({"provider": "anthropic", "model": "claude-haiku-3"})],
            },
        );

        let result = resolve_model_role(&["fast".to_string()], &roles, &m).await;
        assert_eq!(result.len(), 1);
        assert_eq!(result[0].provider, "anthropic");
        assert_eq!(result[0].model, "claude-haiku-3");
    }

    #[tokio::test]
    async fn resolve_model_role_glob_picks_highest() {
        use crate::matrix::RoleConfig;
        use std::collections::BTreeMap;

        let m = map_with(
            "anthropic",
            vec!["claude-haiku-3", "claude-haiku-4", "claude-haiku-2-20240101"],
        );
        let mut roles: RolesMap = BTreeMap::new();
        roles.insert(
            "fast".into(),
            RoleConfig {
                description: "f".into(),
                candidates: vec![serde_json::json!({"provider": "anthropic", "model": "claude-haiku-*"})],
            },
        );

        let result = resolve_model_role(&["fast".to_string()], &roles, &m).await;
        assert_eq!(result[0].model, "claude-haiku-4");
    }

    #[tokio::test]
    async fn resolve_model_role_falls_through_to_chain_member() {
        use crate::matrix::RoleConfig;
        use std::collections::BTreeMap;

        let m = map_with("anthropic", vec!["claude-haiku-3"]);
        let mut roles: RolesMap = BTreeMap::new();
        // "missing" has no candidates a provider can satisfy; "fast" can.
        roles.insert(
            "missing".into(),
            RoleConfig {
                description: "m".into(),
                candidates: vec![serde_json::json!({"provider": "openai", "model": "gpt-5"})],
            },
        );
        roles.insert(
            "fast".into(),
            RoleConfig {
                description: "f".into(),
                candidates: vec![serde_json::json!({"provider": "anthropic", "model": "claude-haiku-3"})],
            },
        );

        let result = resolve_model_role(
            &["missing".to_string(), "fast".to_string()],
            &roles,
            &m,
        )
        .await;
        assert_eq!(result.len(), 1);
        assert_eq!(result[0].provider, "anthropic");
    }

    #[tokio::test]
    async fn resolve_model_role_returns_empty_when_unresolvable() {
        use crate::matrix::RoleConfig;
        use std::collections::BTreeMap;

        let m = map_with("anthropic", vec!["claude-haiku-3"]);
        let mut roles: RolesMap = BTreeMap::new();
        roles.insert(
            "fast".into(),
            RoleConfig {
                description: "f".into(),
                candidates: vec![serde_json::json!({"provider": "openai", "model": "gpt-5"})],
            },
        );

        let result = resolve_model_role(&["fast".to_string()], &roles, &m).await;
        assert!(result.is_empty());
    }
}
```

**Step 2: Run tests**

Run: `cargo test -p amplifier-module-hooks-routing resolver::`
Expected: 13/13 PASS.

If any fail because `ModelInfo` / `ProviderInfo` / `ProviderError` / `ChatRequest` / `ChatResponse` / `ToolCall` are missing required fields, inspect their definitions in `amplifier-core/src/types.rs` and add the missing fields with `..Default::default()` if the types implement `Default`, or set them explicitly to minimal values. Do **not** change semantics of the algorithm.

**Step 3: Commit**

```
git add crates/amplifier-module-hooks-routing/src/resolver.rs
git commit -m "feat(hooks-routing): version_sort_key + glob + resolve_model_role"
```

---

## Task 7: Write the 7 bundled matrix YAML files (verbatim)

**Files:**
- Create: `crates/amplifier-module-hooks-routing/routing/balanced.yaml`
- Create: `crates/amplifier-module-hooks-routing/routing/quality.yaml`
- Create: `crates/amplifier-module-hooks-routing/routing/economy.yaml`
- Create: `crates/amplifier-module-hooks-routing/routing/anthropic.yaml`
- Create: `crates/amplifier-module-hooks-routing/routing/openai.yaml`
- Create: `crates/amplifier-module-hooks-routing/routing/gemini.yaml`
- Create: `crates/amplifier-module-hooks-routing/routing/copilot.yaml`

**Step 1: Write `balanced.yaml`** (the default — this MUST validate)

```yaml
name: balanced
description: "Quality/cost balance for mixed workloads. Default matrix."
updated: "2026-04-22"
roles:
  general:
    description: "Versatile catch-all, no specialization needed"
    candidates:
      - provider: anthropic
        model: claude-sonnet-*
      - provider: openai
        model: gpt-5.5
      - provider: ollama
        model: llama3.2
  fast:
    description: "Quick utility tasks — parsing, classification, file ops, bulk work"
    candidates:
      - provider: anthropic
        model: claude-haiku-*
      - provider: openai
        model: gpt-5-mini
      - provider: ollama
        model: llama3.2
  coding:
    description: "Code generation, implementation, debugging"
    candidates:
      - provider: anthropic
        model: claude-sonnet-*
      - provider: openai
        model: gpt-5.5
  ui-coding:
    description: "Frontend/UI code — components, layouts, styling, spatial reasoning"
    candidates:
      - provider: anthropic
        model: claude-sonnet-*
      - provider: openai
        model: gpt-5.5
  security-audit:
    description: "Vulnerability assessment, attack surface analysis, code auditing"
    candidates:
      - provider: anthropic
        model: claude-opus-*
        config:
          reasoning_effort: high
      - provider: openai
        model: gpt-5-pro*
  reasoning:
    description: "Deep architectural reasoning, system design, complex multi-step analysis"
    candidates:
      - provider: anthropic
        model: claude-opus-*
        config:
          reasoning_effort: high
      - provider: openai
        model: gpt-?.?-pro*
        config:
          reasoning_effort: high
  critique:
    description: "Analytical evaluation — finding flaws in existing work, not generating solutions"
    candidates:
      - provider: anthropic
        model: claude-opus-*
      - provider: openai
        model: gpt-5-pro*
  creative:
    description: "Design direction, aesthetic judgment, high-quality creative output"
    candidates:
      - provider: anthropic
        model: claude-opus-*
      - provider: openai
        model: gpt-5.5
  writing:
    description: "Long-form content — documentation, marketing, case studies, storytelling"
    candidates:
      - provider: anthropic
        model: claude-sonnet-*
      - provider: openai
        model: gpt-5.5
  research:
    description: "Deep investigation, information synthesis across multiple sources"
    candidates:
      - provider: anthropic
        model: claude-sonnet-*
      - provider: openai
        model: gpt-5.5
  vision:
    description: "Understanding visual input — screenshots, diagrams, UI mockups"
    candidates:
      - provider: anthropic
        model: claude-sonnet-*
      - provider: openai
        model: gpt-5.5
      - provider: gemini
        model: gemini-2.5-pro*
  image-gen:
    description: "Image generation, visual mockup creation, visual ideation"
    candidates:
      - provider: openai
        model: gpt-image-1
      - provider: gemini
        model: imagen-*
  critical-ops:
    description: "High-reliability operational tasks — infrastructure, orchestration, coordination"
    candidates:
      - provider: anthropic
        model: claude-opus-*
        config:
          reasoning_effort: high
      - provider: openai
        model: gpt-5-pro*
```

**Step 2: Write `anthropic.yaml`** (Anthropic-only matrix)

```yaml
name: anthropic
description: "Anthropic-only routing — for users who exclusively use Claude."
updated: "2026-04-22"
roles:
  general:
    description: "Versatile catch-all, no specialization needed"
    candidates:
      - provider: anthropic
        model: claude-sonnet-*
  fast:
    description: "Quick utility tasks — parsing, classification, file ops, bulk work"
    candidates:
      - provider: anthropic
        model: claude-haiku-*
  coding:
    description: "Code generation, implementation, debugging"
    candidates:
      - provider: anthropic
        model: claude-sonnet-*
  ui-coding:
    description: "Frontend/UI code — components, layouts, styling, spatial reasoning"
    candidates:
      - provider: anthropic
        model: claude-sonnet-*
  security-audit:
    description: "Vulnerability assessment, attack surface analysis, code auditing"
    candidates:
      - provider: anthropic
        model: claude-opus-*
        config:
          reasoning_effort: high
  reasoning:
    description: "Deep architectural reasoning, system design, complex multi-step analysis"
    candidates:
      - provider: anthropic
        model: claude-opus-*
        config:
          reasoning_effort: high
  critique:
    description: "Analytical evaluation — finding flaws in existing work, not generating solutions"
    candidates:
      - provider: anthropic
        model: claude-opus-*
  creative:
    description: "Design direction, aesthetic judgment, high-quality creative output"
    candidates:
      - provider: anthropic
        model: claude-opus-*
  writing:
    description: "Long-form content — documentation, marketing, case studies, storytelling"
    candidates:
      - provider: anthropic
        model: claude-sonnet-*
  research:
    description: "Deep investigation, information synthesis across multiple sources"
    candidates:
      - provider: anthropic
        model: claude-sonnet-*
  vision:
    description: "Understanding visual input — screenshots, diagrams, UI mockups"
    candidates:
      - provider: anthropic
        model: claude-sonnet-*
  image-gen:
    description: "Image generation (Anthropic does not generate images — falls back to sonnet for description tasks)"
    candidates:
      - provider: anthropic
        model: claude-sonnet-*
  critical-ops:
    description: "High-reliability operational tasks — infrastructure, orchestration, coordination"
    candidates:
      - provider: anthropic
        model: claude-opus-*
        config:
          reasoning_effort: high
```

**Step 3: Write `economy.yaml`** (cheapest viable)

```yaml
name: economy
description: "Minimum-cost routing — uses fast/cheap models everywhere viable."
updated: "2026-04-22"
roles:
  general:
    description: "Versatile catch-all, no specialization needed"
    candidates:
      - provider: anthropic
        model: claude-haiku-*
      - provider: openai
        model: gpt-5-mini
      - provider: ollama
        model: llama3.2
  fast:
    description: "Quick utility tasks — parsing, classification, file ops, bulk work"
    candidates:
      - provider: ollama
        model: llama3.2
      - provider: anthropic
        model: claude-haiku-*
      - provider: openai
        model: gpt-5-mini
  coding:
    description: "Code generation, implementation, debugging"
    candidates:
      - provider: anthropic
        model: claude-haiku-*
      - provider: openai
        model: gpt-5-mini
  ui-coding:
    description: "Frontend/UI code — components, layouts, styling, spatial reasoning"
    candidates:
      - provider: anthropic
        model: claude-haiku-*
      - provider: openai
        model: gpt-5-mini
  security-audit:
    description: "Vulnerability assessment, attack surface analysis, code auditing"
    candidates:
      - provider: anthropic
        model: claude-sonnet-*
      - provider: openai
        model: gpt-5.5
  reasoning:
    description: "Deep architectural reasoning, system design, complex multi-step analysis"
    candidates:
      - provider: anthropic
        model: claude-sonnet-*
      - provider: openai
        model: gpt-5.5
  critique:
    description: "Analytical evaluation — finding flaws in existing work, not generating solutions"
    candidates:
      - provider: anthropic
        model: claude-haiku-*
      - provider: openai
        model: gpt-5-mini
  creative:
    description: "Design direction, aesthetic judgment, high-quality creative output"
    candidates:
      - provider: anthropic
        model: claude-haiku-*
      - provider: openai
        model: gpt-5-mini
  writing:
    description: "Long-form content — documentation, marketing, case studies, storytelling"
    candidates:
      - provider: anthropic
        model: claude-haiku-*
      - provider: openai
        model: gpt-5-mini
  research:
    description: "Deep investigation, information synthesis across multiple sources"
    candidates:
      - provider: anthropic
        model: claude-haiku-*
      - provider: openai
        model: gpt-5-mini
  vision:
    description: "Understanding visual input — screenshots, diagrams, UI mockups"
    candidates:
      - provider: openai
        model: gpt-5-mini
      - provider: gemini
        model: gemini-2.5-flash*
  image-gen:
    description: "Image generation, visual mockup creation, visual ideation"
    candidates:
      - provider: gemini
        model: imagen-*
      - provider: openai
        model: gpt-image-1
  critical-ops:
    description: "High-reliability operational tasks — infrastructure, orchestration, coordination"
    candidates:
      - provider: anthropic
        model: claude-sonnet-*
      - provider: openai
        model: gpt-5.5
```

**Step 4: Write the remaining four files**

Each must satisfy `validate_matrix` (have `general` + `fast`, every role has description + non-empty candidates, no literal `"base"` candidates).

`quality.yaml` — like balanced but every role uses the strongest available model:

```yaml
name: quality
description: "Maximum-quality routing — uses top-tier models for every role."
updated: "2026-04-22"
roles:
  general:
    description: "Versatile catch-all, no specialization needed"
    candidates:
      - provider: anthropic
        model: claude-opus-*
      - provider: openai
        model: gpt-5-pro*
  fast:
    description: "Quick utility tasks — parsing, classification, file ops, bulk work"
    candidates:
      - provider: anthropic
        model: claude-sonnet-*
      - provider: openai
        model: gpt-5.5
  coding:
    description: "Code generation, implementation, debugging"
    candidates:
      - provider: anthropic
        model: claude-opus-*
      - provider: openai
        model: gpt-5-pro*
  ui-coding:
    description: "Frontend/UI code — components, layouts, styling, spatial reasoning"
    candidates:
      - provider: anthropic
        model: claude-opus-*
  security-audit:
    description: "Vulnerability assessment, attack surface analysis, code auditing"
    candidates:
      - provider: anthropic
        model: claude-opus-*
        config: { reasoning_effort: high }
  reasoning:
    description: "Deep architectural reasoning, system design, complex multi-step analysis"
    candidates:
      - provider: anthropic
        model: claude-opus-*
        config: { reasoning_effort: high }
      - provider: openai
        model: gpt-?.?-pro*
        config: { reasoning_effort: high }
  critique:
    description: "Analytical evaluation — finding flaws in existing work, not generating solutions"
    candidates:
      - provider: anthropic
        model: claude-opus-*
  creative:
    description: "Design direction, aesthetic judgment, high-quality creative output"
    candidates:
      - provider: anthropic
        model: claude-opus-*
  writing:
    description: "Long-form content — documentation, marketing, case studies, storytelling"
    candidates:
      - provider: anthropic
        model: claude-opus-*
  research:
    description: "Deep investigation, information synthesis across multiple sources"
    candidates:
      - provider: anthropic
        model: claude-opus-*
  vision:
    description: "Understanding visual input — screenshots, diagrams, UI mockups"
    candidates:
      - provider: anthropic
        model: claude-opus-*
      - provider: gemini
        model: gemini-2.5-pro*
  image-gen:
    description: "Image generation, visual mockup creation, visual ideation"
    candidates:
      - provider: gemini
        model: imagen-*
      - provider: openai
        model: gpt-image-1
  critical-ops:
    description: "High-reliability operational tasks — infrastructure, orchestration, coordination"
    candidates:
      - provider: anthropic
        model: claude-opus-*
        config: { reasoning_effort: high }
```

`openai.yaml`, `gemini.yaml`, `copilot.yaml` — replace `anthropic.yaml`'s candidate sections with `openai`, `gemini`, and `copilot` (Copilot exposes OpenAI-compatible model names like `gpt-4.1`, `o3-mini`) provider names respectively. Each role MUST contain at least one candidate, and every file MUST contain `general` + `fast`. The exact model glob choices are left to the implementer's judgment but should be sensible defaults that exist in those providers' typical model catalogs.

For `openai.yaml`, use `gpt-5-mini` for fast, `gpt-5.5` for general, `gpt-?.?-pro*` for reasoning/security/critical-ops, `gpt-image-1` for image-gen.

For `gemini.yaml`, use `gemini-2.5-flash*` for fast, `gemini-2.5-pro*` for general/reasoning/etc., `imagen-*` for image-gen.

For `copilot.yaml`, use `gpt-4o-mini` for fast, `gpt-4.1` for general, `o3*` for reasoning, `gpt-image-1` for image-gen, all under `provider: copilot`.

**Step 5: Validate every YAML file**

Run: `cargo test -p amplifier-module-hooks-routing matrix::tests::loader_finds_bundled_matrix`
Expected: PASS — confirms `balanced.yaml` parses and validates.

Add a test that loads ALL 7 (append in `matrix.rs` test module):

```rust
    #[test]
    fn all_bundled_matrices_validate() {
        let dir = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("routing");
        for name in ["balanced", "quality", "economy", "anthropic", "openai", "gemini", "copilot"] {
            let cfg = load_matrix_from_dirs(name, &[dir.as_path()])
                .unwrap_or_else(|e| panic!("{name}.yaml: {e}"));
            validate_matrix(&cfg.roles, false)
                .unwrap_or_else(|e| panic!("{name}.yaml: {e}"));
        }
    }
```

Run: `cargo test -p amplifier-module-hooks-routing all_bundled_matrices_validate`
Expected: PASS.

**Step 6: Commit**

```
git add crates/amplifier-module-hooks-routing/routing crates/amplifier-module-hooks-routing/src/matrix.rs
git commit -m "feat(hooks-routing): bundle 7 matrix YAML files (balanced is default)"
```

---

## Task 8: `HooksRouting` struct + `RoutingConfig` + `new()` (test first)

**Files:**
- Modify: `crates/amplifier-module-hooks-routing/src/lib.rs`

**Step 1: Write the failing tests**

Append to `tests/integration_test.rs` (create the file):

```rust
//! Integration tests for HooksRouting.

use std::sync::Arc;
use tokio::sync::RwLock;

use amplifier_module_agent_runtime::AgentRegistry;
use amplifier_module_hooks_routing::{HooksRouting, RoutingConfig};

#[test]
fn new_loads_balanced_by_default() {
    let registry = Arc::new(RwLock::new(AgentRegistry::new()));
    let routing = HooksRouting::new(RoutingConfig::default(), registry).expect("should load");
    assert_eq!(routing.matrix_name(), "balanced");
    assert!(routing.role_names().iter().any(|s| s == "general"));
    assert!(routing.role_names().iter().any(|s| s == "fast"));
}

#[test]
fn new_applies_overrides() {
    let registry = Arc::new(RwLock::new(AgentRegistry::new()));
    let cfg = RoutingConfig {
        default_matrix: "balanced".into(),
        overrides: Some(serde_json::json!({
            "roles": {
                "fast": {
                    "candidates": [{"provider": "ollama", "model": "llama3.2"}]
                }
            }
        })),
    };
    let routing = HooksRouting::new(cfg, registry).expect("should load with overrides");
    let fast = routing.role("fast").expect("fast must exist");
    assert_eq!(fast.candidates.len(), 1);
    assert_eq!(fast.candidates[0]["provider"], "ollama");
}

#[test]
fn new_errors_on_unknown_matrix() {
    let registry = Arc::new(RwLock::new(AgentRegistry::new()));
    let cfg = RoutingConfig {
        default_matrix: "does-not-exist-anywhere".into(),
        overrides: None,
    };
    assert!(HooksRouting::new(cfg, registry).is_err());
}
```

**Step 2: Verify they fail**

Run: `cargo test -p amplifier-module-hooks-routing --test integration_test`
Expected: FAIL — `HooksRouting`, `RoutingConfig` undefined.

**Step 3: Implement in `src/lib.rs`**

Replace `src/lib.rs`:

```rust
//! Model-role routing hook — Rust port of `amplifier-module-hooks-routing`.

pub mod composer;
pub mod matrix;
pub mod resolver;

use std::sync::Arc;

use tokio::sync::RwLock;

use amplifier_module_agent_runtime::AgentRegistry;

pub use matrix::{Candidate, MatrixConfig, RoleConfig, RolesMap};
pub use resolver::{resolve_model_role, ProviderMap, ResolvedProvider};

// ---------------------------------------------------------------------------
// RoutingConfig
// ---------------------------------------------------------------------------

#[derive(Debug, Clone)]
pub struct RoutingConfig {
    /// Matrix name (file basename without `.yaml`). Defaults to `"balanced"`.
    pub default_matrix: String,
    /// Optional override JSON in the same shape as a matrix file.
    pub overrides: Option<serde_json::Value>,
}

impl Default for RoutingConfig {
    fn default() -> Self {
        Self {
            default_matrix: "balanced".into(),
            overrides: None,
        }
    }
}

// ---------------------------------------------------------------------------
// HooksRouting
// ---------------------------------------------------------------------------

pub struct HooksRouting {
    matrix: MatrixConfig,
    agent_registry: Arc<RwLock<AgentRegistry>>,
    providers: Arc<RwLock<ProviderMap>>,
}

impl HooksRouting {
    /// Construct by loading the named matrix from the standard search path
    /// and applying any overrides via `compose_matrix`.
    pub fn new(
        config: RoutingConfig,
        agent_registry: Arc<RwLock<AgentRegistry>>,
    ) -> anyhow::Result<Self> {
        let dirs = matrix::default_search_dirs();
        let dir_refs: Vec<&std::path::Path> = dirs.iter().map(|p| p.as_path()).collect();
        let mut matrix = matrix::load_matrix_from_dirs(&config.default_matrix, &dir_refs)?;

        if let Some(overrides) = &config.overrides {
            matrix.roles = composer::compose_matrix(&matrix.roles, overrides)?;
        } else {
            matrix::validate_matrix(&matrix.roles, false)?;
        }

        Ok(Self {
            matrix,
            agent_registry,
            providers: Arc::new(RwLock::new(ProviderMap::new())),
        })
    }

    /// Inject the live `ProviderMap` (set by the sandbox after providers register).
    pub async fn set_providers(&self, providers: ProviderMap) {
        *self.providers.write().await = providers;
    }

    pub fn matrix_name(&self) -> &str {
        &self.matrix.name
    }

    pub fn role(&self, name: &str) -> Option<&RoleConfig> {
        self.matrix.roles.get(name)
    }

    pub fn role_names(&self) -> Vec<String> {
        self.matrix.roles.keys().cloned().collect()
    }
}
```

**Step 4: Run tests**

Run: `cargo test -p amplifier-module-hooks-routing --test integration_test`
Expected: PASS for the 3 tests added.

**Step 5: Commit**

```
git add crates/amplifier-module-hooks-routing
git commit -m "feat(hooks-routing): HooksRouting::new with override composition"
```

---

## Task 9: Implement the two hooks (`SessionStart` rewriter, `ProviderRequest` injector)

**Files:**
- Modify: `crates/amplifier-module-hooks-routing/src/lib.rs`

**Step 1: Write failing tests**

Append to `tests/integration_test.rs`:

```rust
use amplifier_core::traits::{Provider, ProviderError};
use amplifier_core::types::{ChatRequest, ChatResponse, ModelInfo, ProviderInfo, ToolCall};
use amplifier_module_agent_runtime::{AgentConfig, ModelRole};
use amplifier_module_orchestrator_loop_streaming::{HookEvent, HookRegistry, HookResult};
use async_trait::async_trait;
use std::pin::Pin;

struct MockProvider {
    name: &'static str,
    models: Vec<&'static str>,
}

#[async_trait]
impl Provider for MockProvider {
    fn name(&self) -> &str { self.name }
    fn get_info(&self) -> ProviderInfo {
        ProviderInfo { name: self.name.to_string(), ..ProviderInfo::default() }
    }
    fn list_models(
        &self,
    ) -> Pin<Box<dyn std::future::Future<Output = Result<Vec<ModelInfo>, ProviderError>> + Send + '_>> {
        let models: Vec<ModelInfo> = self.models.iter()
            .map(|id| ModelInfo { id: id.to_string(), ..ModelInfo::default() })
            .collect();
        Box::pin(async move { Ok(models) })
    }
    fn complete(
        &self,
        _request: ChatRequest,
    ) -> Pin<Box<dyn std::future::Future<Output = Result<ChatResponse, ProviderError>> + Send + '_>> {
        Box::pin(async { Err(ProviderError::Other("not used".into())) })
    }
    fn parse_tool_calls(&self, _r: &ChatResponse) -> Vec<ToolCall> { Vec::new() }
}

#[tokio::test]
async fn session_start_rewrites_provider_preferences_for_three_agents() {
    // 1. Build registry with three agents, each with a different model_role.
    let mut reg = AgentRegistry::new();
    reg.register(AgentConfig {
        name: "explorer".into(),
        description: String::new(),
        tools: vec![],
        instruction: String::new(),
        model_role: Some(ModelRole::Single("fast".into())),
        provider_preferences: None,
    });
    reg.register(AgentConfig {
        name: "zen-architect".into(),
        description: String::new(),
        tools: vec![],
        instruction: String::new(),
        model_role: Some(ModelRole::Chain(vec!["reasoning".into(), "general".into()])),
        provider_preferences: None,
    });
    reg.register(AgentConfig {
        name: "bug-hunter".into(),
        description: String::new(),
        tools: vec![],
        instruction: String::new(),
        model_role: Some(ModelRole::Single("coding".into())),
        provider_preferences: None,
    });
    let registry = Arc::new(RwLock::new(reg));

    // 2. Build HooksRouting (balanced).
    let routing = HooksRouting::new(RoutingConfig::default(), Arc::clone(&registry))
        .expect("balanced loads");

    // 3. Provide mock providers offering the right models.
    let mut providers = ProviderMap::new();
    providers.insert(
        "anthropic".into(),
        Arc::new(MockProvider {
            name: "anthropic",
            models: vec!["claude-haiku-3", "claude-sonnet-4-5", "claude-opus-4-7"],
        }) as Arc<dyn Provider>,
    );
    providers.insert(
        "openai".into(),
        Arc::new(MockProvider { name: "openai", models: vec!["gpt-5.5", "gpt-5-mini"] }) as Arc<dyn Provider>,
    );
    providers.insert(
        "ollama".into(),
        Arc::new(MockProvider { name: "ollama", models: vec!["llama3.2"] }) as Arc<dyn Provider>,
    );
    routing.set_providers(providers).await;

    // 4. Register hooks and emit SessionStart.
    let mut hooks = HookRegistry::new();
    routing.register_on(&mut hooks);
    let _ = hooks.emit(HookEvent::SessionStart, serde_json::json!({})).await;

    // 5. Verify each agent now has provider_preferences set.
    let r = registry.read().await;

    let explorer = r.get("explorer").unwrap();
    let prefs = explorer.provider_preferences.as_ref().expect("explorer prefs set");
    assert_eq!(prefs[0].provider, "anthropic");
    assert_eq!(prefs[0].model, "claude-haiku-3");

    let zen = r.get("zen-architect").unwrap();
    let prefs = zen.provider_preferences.as_ref().expect("zen prefs set");
    assert_eq!(prefs[0].provider, "anthropic");
    assert_eq!(prefs[0].model, "claude-opus-4-7");

    let bug = r.get("bug-hunter").unwrap();
    let prefs = bug.provider_preferences.as_ref().expect("bug-hunter prefs set");
    assert_eq!(prefs[0].provider, "anthropic");
    assert_eq!(prefs[0].model, "claude-sonnet-4-5");
}

#[tokio::test]
async fn provider_request_injects_role_catalog() {
    let registry = Arc::new(RwLock::new(AgentRegistry::new()));
    let routing = HooksRouting::new(RoutingConfig::default(), Arc::clone(&registry)).unwrap();

    let mut hooks = HookRegistry::new();
    routing.register_on(&mut hooks);
    let results = hooks.emit(HookEvent::ProviderRequest, serde_json::json!({})).await;
    assert_eq!(results.len(), 1);

    match &results[0] {
        HookResult::InjectContext(s) => {
            assert!(s.contains("Active routing matrix: balanced"));
            assert!(s.contains("general"));
            assert!(s.contains("fast"));
            assert!(s.contains("Versatile catch-all"));
        }
        other => panic!("expected InjectContext, got {other:?}"),
    }
}
```

**Step 2: Verify failure**

Run: `cargo test -p amplifier-module-hooks-routing --test integration_test`
Expected: FAIL — `register_on` undefined.

**Step 3: Implement the two hooks**

Append to `src/lib.rs`:

```rust
use amplifier_module_agent_runtime::ModelRole;
use amplifier_module_orchestrator_loop_streaming::{
    Hook, HookContext, HookEvent, HookRegistry, HookResult,
};
use async_trait::async_trait;

impl HooksRouting {
    /// Register both routing hooks on the given registry.
    pub fn register_on(&self, registry: &mut HookRegistry) {
        registry.register(Box::new(SessionStartHook {
            matrix: self.matrix.clone(),
            agent_registry: Arc::clone(&self.agent_registry),
            providers: Arc::clone(&self.providers),
        }));
        registry.register(Box::new(ProviderRequestHook {
            matrix_name: self.matrix.name.clone(),
            roles: self.matrix.roles.clone(),
        }));
    }
}

// ---------------------------------------------------------------------------
// SessionStartHook
// ---------------------------------------------------------------------------

struct SessionStartHook {
    matrix: MatrixConfig,
    agent_registry: Arc<RwLock<AgentRegistry>>,
    providers: Arc<RwLock<ProviderMap>>,
}

const SESSION_START_EVENTS: &[HookEvent] = &[HookEvent::SessionStart];

#[async_trait]
impl Hook for SessionStartHook {
    fn events(&self) -> &[HookEvent] {
        SESSION_START_EVENTS
    }

    async fn handle(&self, _ctx: &HookContext) -> HookResult {
        let providers = self.providers.read().await;
        // Snapshot agent name + model_role pairs while holding the read lock.
        let pending: Vec<(String, Vec<String>)> = {
            let reg = self.agent_registry.read().await;
            reg.list()
                .into_iter()
                .filter_map(|cfg| {
                    cfg.model_role.as_ref().map(|mr| {
                        let roles = match mr {
                            ModelRole::Single(s) => vec![s.clone()],
                            ModelRole::Chain(v) => v.clone(),
                        };
                        (cfg.name.clone(), roles)
                    })
                })
                .collect()
        };

        // Resolve each agent's roles against the live provider map.
        let mut updates: Vec<(String, Vec<ResolvedProvider>)> = Vec::new();
        for (name, roles) in pending {
            let resolved =
                resolve_model_role(&roles, &self.matrix.roles, &providers).await;
            if !resolved.is_empty() {
                updates.push((name, resolved));
            }
        }

        // Apply all updates under a single write lock.
        if !updates.is_empty() {
            let mut reg = self.agent_registry.write().await;
            for (name, prefs) in updates {
                reg.set_provider_preferences(&name, prefs);
            }
        }

        HookResult::Continue
    }
}

// ---------------------------------------------------------------------------
// ProviderRequestHook
// ---------------------------------------------------------------------------

struct ProviderRequestHook {
    matrix_name: String,
    roles: RolesMap,
}

const PROVIDER_REQUEST_EVENTS: &[HookEvent] = &[HookEvent::ProviderRequest];

#[async_trait]
impl Hook for ProviderRequestHook {
    fn events(&self) -> &[HookEvent] {
        PROVIDER_REQUEST_EVENTS
    }

    async fn handle(&self, _ctx: &HookContext) -> HookResult {
        let mut buf = String::new();
        buf.push_str(&format!("Active routing matrix: {}\n\n", self.matrix_name));
        buf.push_str("Available model roles (use model_role parameter when delegating):\n");
        for (role_name, role_cfg) in &self.roles {
            buf.push_str(&format!("  {role_name} — {}\n", role_cfg.description));
        }
        HookResult::InjectContext(buf)
    }
}
```

**Step 4: Run tests**

Run: `cargo test -p amplifier-module-hooks-routing`
Expected: ALL PASS — both new integration tests + all matrix/composer/resolver tests.

**Step 5: Re-export the agent-runtime `ResolvedProvider`**

Note that the resolver module already imports `ResolvedProvider` from `amplifier_module_agent_runtime`. Update the `lib.rs` re-export so external users get the canonical type:

```rust
pub use amplifier_module_agent_runtime::ResolvedProvider;
```

(Remove the `pub use resolver::ResolvedProvider` line if you accidentally added one; the canonical type lives in agent-runtime.)

Run again: `cargo test -p amplifier-module-hooks-routing`
Expected: still PASS.

**Step 6: Commit**

```
git add crates/amplifier-module-hooks-routing
git commit -m "feat(hooks-routing): SessionStart resolver + ProviderRequest injector"
```

---

## Task 10: Wire into sandbox `main.rs`

**Files:**
- Modify: `sandbox/amplifier-android-sandbox/Cargo.toml`
- Modify: `sandbox/amplifier-android-sandbox/src/main.rs`

**Step 1: Add the dep**

Add to `sandbox/amplifier-android-sandbox/Cargo.toml` under `[dependencies]`:

```toml
amplifier-module-hooks-routing = { workspace = true }
```

**Step 2: Update agent-registry storage to `Arc<RwLock<AgentRegistry>>`**

In `main.rs`, after the line `let registry = std::sync::Arc::new(agent_registry);`, change to:

```rust
let registry = Arc::new(tokio::sync::RwLock::new(agent_registry));
```

Update the `DelegateTool::new()` call to pass `Arc::clone(&registry)`. If `DelegateTool` currently expects `Arc<AgentRegistry>` (not `Arc<RwLock<…>>`), keep a separate `delegate_view` clone — but inspect first:

Run: `grep -n 'AgentRegistry' crates/amplifier-module-tool-delegate/src/lib.rs | head`

If `DelegateTool` takes `Arc<AgentRegistry>`, the simplest non-invasive fix is: introduce a second `Arc<AgentRegistry>` snapshot **after** `HooksRouting` has run SessionStart. But since hooks fire asynchronously at session start, an easier fix is:

- Change `DelegateTool` to accept `Arc<RwLock<AgentRegistry>>` and read-lock at execute time. If that requires touching `tool-delegate`, do it as part of this task and update its tests with `..Default::default()`-style construction wrapped in an `Arc<RwLock<…>>`.

Document the chosen path in the commit. The plan recommends: change `DelegateTool` to `Arc<RwLock<AgentRegistry>>` for consistency.

**Step 3: Construct and register `HooksRouting`**

After the existing hook-registry build (Step 4 in the file) but before the orchestrator runs, insert:

```rust
// Build the hook registry, then attach routing hooks.
let mut hook_registry = hooks::build_registry();

let routing = amplifier_module_hooks_routing::HooksRouting::new(
    amplifier_module_hooks_routing::RoutingConfig::default(),
    Arc::clone(&registry),
)
.context("failed to load routing matrix")?;
routing.register_on(&mut hook_registry);
```

After the provider is built and registered with the orchestrator, also push it into the routing's provider map:

```rust
let mut provider_map = amplifier_module_hooks_routing::ProviderMap::new();
provider_map.insert(args.provider.clone(), Arc::clone(&provider_arc));
routing.set_providers(provider_map).await;
```

(Note: this requires retaining `provider_arc: Arc<dyn Provider>` instead of moving the box directly into `register_provider`. Adjust the existing `Box<dyn Provider> → Arc::from(...)` line so the same `Arc` is shared with both the orchestrator and the routing hook.)

**Step 4: Build**

Run: `cargo build --workspace`
Expected: clean.

**Step 5: Smoke-run with an LLM-free path**

If sandbox has a no-LLM smoke test, run it. Otherwise skip — `cargo test --workspace` will catch regressions.

Run: `cargo test --workspace`
Expected: all green.

**Step 6: Commit**

```
git add sandbox/amplifier-android-sandbox crates/amplifier-module-tool-delegate
git commit -m "feat(sandbox): wire HooksRouting and switch registry to RwLock"
```

---

## Task 11: Workspace verification (build, clippy, fmt, full test)

**Step 1: Format check**

Run: `cargo fmt --check`
Expected: no diff. If diff, run `cargo fmt --all` and re-run.

**Step 2: Clippy**

Run: `cargo clippy --workspace --all-targets -- -D warnings`
Expected: zero warnings. Fix any that appear without disabling lints.

**Step 3: Full test suite**

Run: `cargo test --workspace`
Expected: ALL pass — count breakdown:

| Crate | Expected new tests |
|---|---|
| `amplifier-module-agent-runtime` | +4 (Task 1) |
| `amplifier-module-hooks-routing::matrix` | 8 (Tasks 3+4+7) |
| `amplifier-module-hooks-routing::composer` | 5 (Task 5) |
| `amplifier-module-hooks-routing::resolver` | 13 (Task 6) |
| `amplifier-module-hooks-routing::tests::integration_test` | 5 (Tasks 8+9) |

**Step 4: Build the release variant**

Run: `cargo build --workspace --release`
Expected: clean.

**Step 5: Commit any fmt/clippy fixes**

```
git add -A
git commit -m "chore: fmt + clippy clean for routing phase" --allow-empty
```

---

## Verification Checklist

- [ ] `cargo build --workspace` clean
- [ ] `cargo clippy --workspace -- -D warnings` clean
- [ ] `cargo fmt --check` clean
- [ ] `cargo test --workspace` all green
- [ ] All 7 bundled YAML matrices load and pass `validate_matrix`
- [ ] `version_sort_key` returns expected ordering for: `claude-opus-4-10` > `claude-opus-4-7` > `claude-opus-4-20250514`; `gpt-5.4` > `gpt-5.4-2026-03-05`
- [ ] `compose_matrix` correctly: replaces (0 base), splices (1 base), errors (2+ base)
- [ ] `find_provider_by_type` finds providers via all 3 name forms
- [ ] Integration test: 3 agents resolved against mock providers; explorer→haiku, zen-architect→opus-4-7, bug-hunter→sonnet-4-5
- [ ] Integration test: ProviderRequest hook returns `InjectContext` containing `"Active routing matrix: balanced"` and the role catalog
- [ ] `AgentConfig::model_role` parses from agent-bundle frontmatter as both string and list
- [ ] Sandbox `main.rs` constructs `HooksRouting`, registers both hooks, and feeds the live provider into its `ProviderMap`

---

## Anti-Patterns to Avoid

1. **Don't swallow errors silently in the loader.** `load_matrix_from_dirs` must error if no matrix is found in any search dir; only `resolve_glob` swallows `list_models()` errors (mirrors Python).
2. **Don't change `version_sort_key` to use `i64` parts only.** Mixed text+numeric runs are essential (`claude-opus-4-7` vs `claude-opus-4-10`).
3. **Don't treat `"base"` token in matrix files as silent no-op.** Reject during validation — only overrides may use it.
4. **Don't add `Default` to `AgentConfig`.** Other call sites construct it explicitly; opt-out fields use `Option` + `serde(default)`.
5. **Don't hold a write lock across `resolve_model_role().await`.** The `SessionStartHook::handle` snapshots the names under a read lock, releases, awaits resolution, then takes a write lock to apply updates. Holding a write lock across `.await` will deadlock if any other code tries to read the registry concurrently.
6. **Don't assume `BTreeMap` for `serde_json::Value::as_object()` ordering.** That returns `Map<String, Value>` whose iteration order is insertion order (preserve_order feature). Test order-sensitive assertions only against `RolesMap` (which IS a `BTreeMap`).
7. **Don't drop the `set_providers` step in sandbox.** Without it, every `resolve_glob` call returns `None` (empty provider map → no candidate matches) and every agent's `provider_preferences` stays `None` — the hook silently does nothing.
