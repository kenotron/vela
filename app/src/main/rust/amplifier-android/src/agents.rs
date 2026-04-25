//! Agent registry builder — wires foundation agents + vault-local agents.
    //!
    //! Foundation agents are embedded at compile time from the amplifier-rust
    //! `agents/` directory. Users can override any agent by placing a same-named
    //! `.md` file in `<vault_path>/.agents/`.

    use amplifier_module_agent_runtime::{parse_agent_content, AgentRegistry};
    use std::path::Path;
    use std::sync::Arc;

    /// Embedded foundation agent `.md` files (compiled into the binary).
    const FOUNDATION_AGENTS: &[&str] = &[
        include_str!("../../../../../../../amplifier-rust/agents/explorer.md"),
        include_str!("../../../../../../../amplifier-rust/agents/zen-architect.md"),
        include_str!("../../../../../../../amplifier-rust/agents/bug-hunter.md"),
        include_str!("../../../../../../../amplifier-rust/agents/git-ops.md"),
        include_str!("../../../../../../../amplifier-rust/agents/modular-builder.md"),
        include_str!("../../../../../../../amplifier-rust/agents/security-guardian.md"),
    ];

    /// Build an [`AgentRegistry`] populated with the six foundation agents plus any
    /// agents found in `<vault_path>/.agents/`.
    ///
    /// Non-existent vault directories are silently ignored.
    pub fn build_agent_registry(vault_path: &Path) -> Arc<AgentRegistry> {
        let mut registry = AgentRegistry::new();

        // Register all built-in foundation agents from embedded .md content.
        for content in FOUNDATION_AGENTS {
            if let Some(config) = parse_agent_content(content) {
                registry.register(config);
            }
        }

        // Load vault-local agents from <vault_path>/.agents/ — silently ignore errors.
        let vault_agents_dir = vault_path.join(".agents");
        let _ = registry.load_from_dir(&vault_agents_dir);

        Arc::new(registry)
    }

    /// Serialise the agent list as a JSON array.
    ///
    /// Returns `"[]"` if serialisation fails.
    pub fn list_agents_to_json(registry: &AgentRegistry) -> String {
        serde_json::to_string(&registry.list()).unwrap_or_else(|_| "[]".to_string())
    }

    // ---------------------------------------------------------------------------
    // Tests
    // ---------------------------------------------------------------------------

    #[cfg(test)]
    mod tests {
        use super::*;

        #[test]
        fn build_registry_includes_six_foundation_agents() {
            let dir = tempfile::tempdir().unwrap();
            let registry = build_agent_registry(dir.path());
            let names = registry.available_names();
            assert!(names.contains(&"explorer"), "missing: explorer");
            assert!(names.contains(&"zen-architect"), "missing: zen-architect");
            assert!(names.contains(&"bug-hunter"), "missing: bug-hunter");
            assert!(names.contains(&"git-ops"), "missing: git-ops");
            assert!(names.contains(&"modular-builder"), "missing: modular-builder");
            assert!(names.contains(&"security-guardian"), "missing: security-guardian");
        }

        #[test]
        fn build_registry_loads_vault_agents_directory() {
            let dir = tempfile::tempdir().unwrap();
            let agents_dir = dir.path().join(".agents");
            std::fs::create_dir_all(&agents_dir).unwrap();
            std::fs::write(
                agents_dir.join("custom.md"),
                "---\nmeta:\n  name: my-custom-agent\n  description: a vault agent\n---\n",
            ).unwrap();
            let registry = build_agent_registry(dir.path());
            assert!(
                registry.available_names().contains(&"my-custom-agent"),
                "custom agent not found in registry"
            );
        }

        #[test]
        fn list_agents_to_json_returns_valid_json_array() {
            let dir = tempfile::tempdir().unwrap();
            let registry = build_agent_registry(dir.path());
            let json = list_agents_to_json(&registry);
            let parsed: Vec<serde_json::Value> = serde_json::from_str(&json).unwrap();
            assert!(parsed.len() >= 6, "expected >=6 agents, got {}", parsed.len());
            for item in &parsed {
                assert!(item.get("name").is_some(), "item missing 'name' field");
                assert!(item.get("description").is_some(), "item missing 'description' field");
            }
        }

        #[test]
        fn build_registry_missing_vault_dir_is_not_an_error() {
            let registry = build_agent_registry(Path::new("/definitely/does/not/exist/vault"));
            assert_eq!(registry.available_names().len(), 6, "expected 6 foundation agents");
        }
    }
    