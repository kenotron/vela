//! Integration tests for HooksRouting.

use std::future::Future;
use std::pin::Pin;
use std::sync::Arc;

use tokio::sync::RwLock;

use amplifier_core::errors::ProviderError;
use amplifier_core::messages::{ChatResponse, ToolCall};
use amplifier_core::models::{ModelInfo, ProviderInfo};
use amplifier_core::traits::Provider;
use amplifier_module_agent_runtime::{AgentConfig, AgentRegistry, ModelRole};
use amplifier_module_hooks_routing::{
    HookContext, HookEvent, HookRegistry, HookResult, HooksRouting, ProviderMap, RoutingConfig,
};

// ---------------------------------------------------------------------------
// MockProvider
// ---------------------------------------------------------------------------

struct MockProvider {
    name: String,
    models: Vec<String>,
}

impl Provider for MockProvider {
    fn name(&self) -> &str {
        &self.name
    }

    fn get_info(&self) -> ProviderInfo {
        ProviderInfo {
            id: self.name.clone(),
            display_name: self.name.clone(),
            credential_env_vars: vec![],
            capabilities: vec![],
            defaults: Default::default(),
            config_fields: vec![],
        }
    }

    fn list_models(
        &self,
    ) -> Pin<Box<dyn Future<Output = Result<Vec<ModelInfo>, ProviderError>> + Send + '_>> {
        let models = self.models.clone();
        Box::pin(async move {
            Ok(models
                .into_iter()
                .map(|id| ModelInfo {
                    id: id.clone(),
                    display_name: id,
                    context_window: 8192,
                    max_output_tokens: 4096,
                    capabilities: vec![],
                    defaults: Default::default(),
                })
                .collect())
        })
    }

    fn complete(
        &self,
        _request: amplifier_core::messages::ChatRequest,
    ) -> Pin<
        Box<
            dyn Future<Output = Result<amplifier_core::messages::ChatResponse, ProviderError>>
                + Send
                + '_,
        >,
    > {
        Box::pin(async move {
            Err(ProviderError::Other {
                message: "not implemented in mock".to_string(),
                provider: None,
                model: None,
                retry_after: None,
                status_code: None,
                retryable: false,
                delay_multiplier: None,
            })
        })
    }

    fn parse_tool_calls(&self, _response: &ChatResponse) -> Vec<ToolCall> {
        vec![]
    }
}

// ---------------------------------------------------------------------------
// Existing integration tests (preserved)
// ---------------------------------------------------------------------------

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

// ---------------------------------------------------------------------------
// New hook integration tests
// ---------------------------------------------------------------------------

/// Build a minimal AgentConfig with a given name and model_role.
fn make_agent(name: &str, model_role: ModelRole) -> AgentConfig {
    AgentConfig {
        name: name.to_string(),
        description: format!("{} agent", name),
        model_role: Some(model_role),
        provider_preferences: None,
        tools: vec![],
        instruction: String::new(),
    }
}

/// Build a ProviderMap from (provider_name, models) pairs.
fn make_providers(entries: Vec<(&str, Vec<&str>)>) -> ProviderMap {
    entries
        .into_iter()
        .map(|(name, models)| {
            let provider = MockProvider {
                name: name.to_string(),
                models: models.into_iter().map(|m| m.to_string()).collect(),
            };
            (name.to_string(), Arc::new(provider) as Arc<dyn Provider>)
        })
        .collect()
}

#[tokio::test]
async fn session_start_rewrites_provider_preferences_for_three_agents() {
    // 1. Build registry with 3 agents
    let agent_registry = Arc::new(RwLock::new(AgentRegistry::new()));
    {
        let mut reg = agent_registry.write().await;
        reg.register(make_agent("explorer", ModelRole::Single("fast".to_string())));
        reg.register(make_agent(
            "zen-architect",
            ModelRole::Chain(vec!["reasoning".to_string(), "general".to_string()]),
        ));
        reg.register(make_agent("bug-hunter", ModelRole::Single("coding".to_string())));
    }

    // 2. Build HooksRouting (balanced)
    let routing = HooksRouting::new(RoutingConfig::default(), Arc::clone(&agent_registry))
        .expect("should load balanced matrix");

    // 3. Build provider map
    let providers = make_providers(vec![
        ("anthropic", vec!["claude-haiku-3", "claude-sonnet-4-5", "claude-opus-4-7"]),
        ("openai", vec!["gpt-5.5", "gpt-5-mini"]),
        ("ollama", vec!["llama3.2"]),
    ]);

    // 4. Set providers on the routing instance
    routing.set_providers(providers).await;

    // 5. Register hooks and emit SessionStart
    let mut hooks = HookRegistry::new();
    routing.register_on(&mut hooks);
    hooks.emit(HookEvent::SessionStart, &HookContext::default()).await;

    // 6. Verify provider preferences
    let reg = agent_registry.read().await;

    let explorer = reg.get("explorer").expect("explorer should exist");
    let explorer_prefs = explorer.provider_preferences.as_ref().expect("should have prefs");
    assert_eq!(
        explorer_prefs[0].provider, "anthropic",
        "explorer: expected provider 'anthropic'"
    );
    assert_eq!(
        explorer_prefs[0].model, "claude-haiku-3",
        "explorer: expected model 'claude-haiku-3'"
    );

    let zen = reg.get("zen-architect").expect("zen-architect should exist");
    let zen_prefs = zen.provider_preferences.as_ref().expect("should have prefs");
    assert_eq!(
        zen_prefs[0].provider, "anthropic",
        "zen-architect: expected provider 'anthropic'"
    );
    assert_eq!(
        zen_prefs[0].model, "claude-opus-4-7",
        "zen-architect: expected model 'claude-opus-4-7'"
    );

    let bug = reg.get("bug-hunter").expect("bug-hunter should exist");
    let bug_prefs = bug.provider_preferences.as_ref().expect("should have prefs");
    assert_eq!(
        bug_prefs[0].provider, "anthropic",
        "bug-hunter: expected provider 'anthropic'"
    );
    assert_eq!(
        bug_prefs[0].model, "claude-sonnet-4-5",
        "bug-hunter: expected model 'claude-sonnet-4-5'"
    );
}

#[tokio::test]
async fn provider_request_injects_role_catalog() {
    // Empty registry
    let agent_registry = Arc::new(RwLock::new(AgentRegistry::new()));
    let routing = HooksRouting::new(RoutingConfig::default(), agent_registry)
        .expect("should load balanced matrix");

    // Register hooks
    let mut hooks = HookRegistry::new();
    routing.register_on(&mut hooks);

    // Emit ProviderRequest
    let results = hooks.emit(HookEvent::ProviderRequest, &HookContext::default()).await;

    // Should have exactly one result and it should be InjectContext
    assert_eq!(results.len(), 1, "expected exactly one result from ProviderRequest");

    match &results[0] {
        HookResult::InjectContext(buf) => {
            assert!(
                buf.contains("Active routing matrix: balanced"),
                "should contain matrix name; got: {buf}"
            );
            assert!(
                buf.contains("general"),
                "should contain 'general' role; got: {buf}"
            );
            assert!(
                buf.contains("fast"),
                "should contain 'fast' role; got: {buf}"
            );
            assert!(
                buf.contains("Versatile catch-all"),
                "should contain general role description; got: {buf}"
            );
        }
        other => panic!("expected InjectContext, got: {:?}", other),
    }
}
