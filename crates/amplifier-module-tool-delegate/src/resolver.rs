// resolver.rs — translate the `agent` parameter string into a ResolvedAgent.

use amplifier_module_agent_runtime::{AgentConfig, AgentRegistry};

// ---------------------------------------------------------------------------
// Public types
// ---------------------------------------------------------------------------

/// The result of resolving an `agent` parameter string.
#[derive(Debug)]
pub enum ResolvedAgent {
    /// The caller requested `"self"` — spawn a copy of the current agent.
    SelfDelegate,
    /// A concrete agent config found in the registry.
    FoundAgent(AgentConfig),
}

// ---------------------------------------------------------------------------
// Public entry point
// ---------------------------------------------------------------------------

/// Resolve an agent name string into a [`ResolvedAgent`].
///
/// Resolution rules:
/// 1. `"self"` → [`ResolvedAgent::SelfDelegate`]
/// 2. Names containing `':'` (bundle-path syntax) → unsupported error
/// 3. All other names → registry lookup; error with available list if missing
pub fn resolve_agent(name: &str, registry: &AgentRegistry) -> anyhow::Result<ResolvedAgent> {
    // Rule 1: self-delegation
    if name == "self" {
        return Ok(ResolvedAgent::SelfDelegate);
    }

    // Rule 2: bundle-path syntax not supported in Rust runtime
    if name.contains(':') {
        anyhow::bail!(
            "Bundle path agents not yet supported in Rust runtime: '{}'. \
             Use a plain agent name (e.g. 'explorer') or 'self'.",
            name
        );
    }

    // Rule 3: registry lookup
    match registry.get(name) {
        Some(config) => Ok(ResolvedAgent::FoundAgent(config.clone())),
        None => {
            let names = registry.available_names();
            let list = if names.is_empty() {
                "(none registered)".to_string()
            } else {
                names.join(", ")
            };
            anyhow::bail!("Agent '{}' not found. Available agents: [{}]", name, list);
        }
    }
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;
    use amplifier_module_agent_runtime::AgentRegistry;

    /// Helper: build a registry pre-populated with a single "explorer" agent.
    fn make_registry_with_explorer() -> AgentRegistry {
        let mut registry = AgentRegistry::new();
        registry.register(AgentConfig {
            name: "explorer".to_string(),
            description: "Deep local-context reconnaissance agent.".to_string(),
            model_role: None,
            provider_preferences: None,
            tools: vec![],
            instruction: "Explore the codebase.".to_string(),
        });
        registry
    }

    // -----------------------------------------------------------------------
    // 1. resolve_self_returns_self_delegate
    // -----------------------------------------------------------------------
    #[test]
    fn resolve_self_returns_self_delegate() {
        let registry = AgentRegistry::new();
        let result = resolve_agent("self", &registry).expect("should succeed for 'self'");
        assert!(
            matches!(result, ResolvedAgent::SelfDelegate),
            "expected SelfDelegate"
        );
    }

    // -----------------------------------------------------------------------
    // 2. resolve_known_agent_returns_found_agent
    // -----------------------------------------------------------------------
    #[test]
    fn resolve_known_agent_returns_found_agent() {
        let registry = make_registry_with_explorer();
        let result = resolve_agent("explorer", &registry).expect("should find 'explorer'");
        match result {
            ResolvedAgent::FoundAgent(config) => {
                assert_eq!(config.name, "explorer", "config name mismatch");
            }
            _ => panic!("expected FoundAgent, got something else"),
        }
    }

    // -----------------------------------------------------------------------
    // 3. resolve_unknown_agent_errors_with_available_list
    // -----------------------------------------------------------------------
    #[test]
    fn resolve_unknown_agent_errors_with_available_list() {
        let registry = make_registry_with_explorer();
        let err = resolve_agent("nonexistent", &registry)
            .expect_err("should error for unknown agent name");
        let msg = err.to_string();
        assert!(
            msg.contains("nonexistent"),
            "error should mention the unknown name; got: {msg}"
        );
        assert!(
            msg.contains("explorer"),
            "error should list available agents; got: {msg}"
        );
    }

    // -----------------------------------------------------------------------
    // 4. resolve_bundle_path_returns_unsupported_error
    // -----------------------------------------------------------------------
    #[test]
    fn resolve_bundle_path_returns_unsupported_error() {
        let registry = AgentRegistry::new();
        let err = resolve_agent("foundation:explorer", &registry)
            .expect_err("should error for bundle-path syntax");
        let msg = err.to_string();
        assert!(
            msg.contains("not yet supported") || msg.contains("Bundle"),
            "error should mention unsupported bundle path; got: {msg}"
        );
    }

    // -----------------------------------------------------------------------
    // 5. resolve_with_empty_registry_lists_none_registered
    // -----------------------------------------------------------------------
    #[test]
    fn resolve_with_empty_registry_lists_none_registered() {
        let registry = AgentRegistry::new();
        let err =
            resolve_agent("anything", &registry).expect_err("should error when registry is empty");
        let msg = err.to_string();
        assert!(
            msg.contains("none registered") || msg.contains("[]"),
            "error should mention empty registry; got: {msg}"
        );
    }
}
