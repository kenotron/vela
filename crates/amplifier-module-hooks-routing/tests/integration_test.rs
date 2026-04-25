//! Integration tests for HooksRouting.

use std::sync::Arc;

use tokio::sync::RwLock;

use amplifier_module_agent_runtime::AgentRegistry;
use amplifier_module_hooks_routing::{HooksRouting, RoutingConfig};

#[test]
fn new_loads_balanced_by_default() {
    let registry = Arc::new(RwLock::new(AgentRegistry::new()));
    let routing =
        HooksRouting::new(RoutingConfig::default(), registry).expect("should load balanced matrix");
    assert_eq!(routing.matrix_name(), "balanced");
    assert!(
        routing.role("general").is_some(),
        "balanced matrix should have a 'general' role"
    );
    assert!(
        routing.role("fast").is_some(),
        "balanced matrix should have a 'fast' role"
    );
    let names = routing.role_names();
    assert!(names.contains(&"general".to_string()));
}

#[test]
fn new_applies_overrides() {
    let registry = Arc::new(RwLock::new(AgentRegistry::new()));
    let overrides = serde_json::json!({
        "roles": {
            "general": {
                "description": "Overridden general",
                "candidates": [{"provider": "openai", "model": "gpt-4o"}]
            }
        }
    });
    let config = RoutingConfig {
        default_matrix: "balanced".into(),
        overrides: Some(overrides),
    };
    let routing = HooksRouting::new(config, registry).expect("should load balanced with overrides");
    let general = routing.role("general").expect("general role should exist");
    assert_eq!(
        general.description, "Overridden general",
        "override description should be applied"
    );
    assert_eq!(general.candidates.len(), 1, "should have exactly 1 candidate from override");
    // Other roles from balanced matrix should still be present
    assert!(routing.role("fast").is_some(), "fast role should be inherited from base matrix");
}

#[test]
fn new_errors_on_unknown_matrix() {
    let registry = Arc::new(RwLock::new(AgentRegistry::new()));
    let config = RoutingConfig {
        default_matrix: "does-not-exist".into(),
        overrides: None,
    };
    let result = HooksRouting::new(config, registry);
    assert!(result.is_err(), "should error when matrix name not found");
    let err_msg = result.err().unwrap().to_string();
    assert!(
        err_msg.contains("does-not-exist"),
        "error message should mention the matrix name, got: {err_msg}"
    );
}
