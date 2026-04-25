//! Integration tests for AgentRegistry + loader pipeline using real .md files.

use std::fs;
use tempfile::TempDir;

use amplifier_module_agent_runtime::{AgentConfig, AgentRegistry, ModelRole};

// ---------------------------------------------------------------------------
// Fixtures
// ---------------------------------------------------------------------------

const EXPLORER_AGENT_MD: &str = r#"---
meta:
  name: explorer
  description: "Deep local-context reconnaissance agent. Conducts structured sweeps of code, docs, and config to answer open-ended questions about a codebase."
model_role: fast
tools:
  - filesystem
  - bash
  - glob
  - grep
---

You are a deep codebase explorer. Conduct thorough reconnaissance, map the territory, and return structured findings. Always read multiple files before drawing conclusions.
"#;

const ZEN_ARCHITECT_AGENT_MD: &str = r#"---
meta:
  name: zen-architect
  description: "Ruthless simplicity architect. Analyzes problems in ANALYZE mode, designs systems in ARCHITECT mode, and reviews code in REVIEW mode."
model_role:
  - reasoning
  - general
tools:
  - filesystem
  - bash
---

You are a zen architect who embodies ruthless simplicity. You operate in three modes: ANALYZE (break down problems), ARCHITECT (design systems), and REVIEW (assess code quality).
"#;

// ---------------------------------------------------------------------------
// Integration Tests
// ---------------------------------------------------------------------------

#[test]
fn registry_loads_agents_from_directory() {
    let dir = TempDir::new().expect("create temp dir");
    fs::write(dir.path().join("explorer.md"), EXPLORER_AGENT_MD).expect("write explorer.md");
    fs::write(
        dir.path().join("zen-architect.md"),
        ZEN_ARCHITECT_AGENT_MD,
    )
    .expect("write zen-architect.md");

    let mut registry = AgentRegistry::new();
    let count = registry
        .load_from_dir(dir.path())
        .expect("load_from_dir should succeed");

    assert_eq!(count, 2, "expected 2 agents loaded");

    // Verify explorer agent.
    let explorer = registry.get("explorer").expect("explorer should be registered");
    assert_eq!(
        explorer.model_role,
        Some(ModelRole::Single("fast".to_string())),
        "explorer model_role should be Single(\"fast\")"
    );
    assert!(
        explorer.tools.contains(&"filesystem".to_string()),
        "explorer tools should contain 'filesystem', got: {:?}",
        explorer.tools
    );
    assert!(
        !explorer.instruction.is_empty(),
        "explorer instruction should be non-empty"
    );

    // Verify zen-architect agent.
    let zen = registry
        .get("zen-architect")
        .expect("zen-architect should be registered");
    assert_eq!(
        zen.model_role,
        Some(ModelRole::Chain(vec![
            "reasoning".to_string(),
            "general".to_string()
        ])),
        "zen-architect model_role should be Chain([\"reasoning\", \"general\"])"
    );
}

#[test]
fn available_names_lists_all_registered() {
    let dir = TempDir::new().expect("create temp dir");
    fs::write(dir.path().join("explorer.md"), EXPLORER_AGENT_MD).expect("write explorer.md");
    fs::write(
        dir.path().join("zen-architect.md"),
        ZEN_ARCHITECT_AGENT_MD,
    )
    .expect("write zen-architect.md");

    let mut registry = AgentRegistry::new();
    registry
        .load_from_dir(dir.path())
        .expect("load_from_dir should succeed");

    let names = registry.available_names();
    assert!(
        names.contains(&"explorer"),
        "available_names should contain 'explorer', got: {:?}",
        names
    );
    assert!(
        names.contains(&"zen-architect"),
        "available_names should contain 'zen-architect', got: {:?}",
        names
    );
}

#[test]
fn registry_accumulates_manual_and_loaded_agents() {
    let dir = TempDir::new().expect("create temp dir");
    fs::write(dir.path().join("explorer.md"), EXPLORER_AGENT_MD).expect("write explorer.md");

    let mut registry = AgentRegistry::new();

    // Register a manually-built agent first.
    registry.register(AgentConfig {
        name: "manual-agent".to_string(),
        description: "A manually registered test agent.".to_string(),
        model_role: Some(ModelRole::Single("general".to_string())),
        tools: vec!["bash".to_string()],
        instruction: "You are a manual test agent.".to_string(),
    });

    // Load from directory (adds explorer).
    registry
        .load_from_dir(dir.path())
        .expect("load_from_dir should succeed");

    // Both agents should be present.
    let all = registry.list();
    assert_eq!(all.len(), 2, "expected 2 agents total (manual + loaded)");

    let names: Vec<&str> = all.iter().map(|c| c.name.as_str()).collect();
    assert!(
        names.contains(&"manual-agent"),
        "list should contain 'manual-agent', got: {:?}",
        names
    );
    assert!(
        names.contains(&"explorer"),
        "list should contain 'explorer', got: {:?}",
        names
    );
}
