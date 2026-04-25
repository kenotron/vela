//! Integration tests for the built-in foundation agents.

use amplifier_agent_foundation::foundation_agents;
use amplifier_module_agent_runtime::ModelRole;

#[test]
fn foundation_agents_returns_six_agents() {
    let agents = foundation_agents();
    assert_eq!(agents.len(), 6, "expected exactly 6 foundation agents, got {}", agents.len());
}

#[test]
fn all_agents_have_unique_names() {
    let agents = foundation_agents();
    let mut names: Vec<&str> = agents.iter().map(|a| a.name.as_str()).collect();
    let total = names.len();
    names.dedup();
    // Sort first so dedup works correctly
    let mut sorted_names: Vec<&str> = agents.iter().map(|a| a.name.as_str()).collect();
    sorted_names.sort_unstable();
    sorted_names.dedup();
    assert_eq!(
        sorted_names.len(),
        total,
        "agent names are not unique: {:?}",
        agents.iter().map(|a| a.name.as_str()).collect::<Vec<_>>()
    );
}

#[test]
fn all_agents_have_non_empty_instructions() {
    let agents = foundation_agents();
    for agent in &agents {
        assert!(
            !agent.instruction.is_empty(),
            "agent '{}' has empty instruction",
            agent.name
        );
    }
}

#[test]
fn all_agents_have_non_empty_tool_lists() {
    let agents = foundation_agents();
    for agent in &agents {
        assert!(
            !agent.tools.is_empty(),
            "agent '{}' has empty tool list",
            agent.name
        );
    }
}

#[test]
fn all_agents_have_model_roles() {
    let agents = foundation_agents();
    for agent in &agents {
        assert!(
            agent.model_role.is_some(),
            "agent '{}' has no model_role",
            agent.name
        );
    }
}

#[test]
fn expected_agent_names_are_present() {
    let agents = foundation_agents();
    let names: Vec<&str> = agents.iter().map(|a| a.name.as_str()).collect();

    let expected = [
        "explorer",
        "zen-architect",
        "bug-hunter",
        "git-ops",
        "modular-builder",
        "security-guardian",
    ];

    for expected_name in &expected {
        assert!(
            names.contains(expected_name),
            "expected agent '{}' not found; available: {:?}",
            expected_name,
            names
        );
    }
}

#[test]
fn explorer_uses_fast_model_role() {
    let agents = foundation_agents();
    let explorer = agents
        .iter()
        .find(|a| a.name == "explorer")
        .expect("explorer agent should be present");

    assert_eq!(
        explorer.model_role,
        Some(ModelRole::Single("fast".to_string())),
        "explorer should use ModelRole::Single(\"fast\"), got: {:?}",
        explorer.model_role
    );
}

#[test]
fn zen_architect_uses_reasoning_chain() {
    let agents = foundation_agents();
    let zen = agents
        .iter()
        .find(|a| a.name == "zen-architect")
        .expect("zen-architect agent should be present");

    assert_eq!(
        zen.model_role,
        Some(ModelRole::Chain(vec![
            "reasoning".to_string(),
            "general".to_string()
        ])),
        "zen-architect should use ModelRole::Chain([\"reasoning\", \"general\"]), got: {:?}",
        zen.model_role
    );
}
