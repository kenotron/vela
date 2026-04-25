pub mod loader;
pub mod parser;

use std::collections::HashMap;
use std::path::Path;

use serde::{Deserialize, Serialize};

// ---------------------------------------------------------------------------
// ModelRole
// ---------------------------------------------------------------------------

/// Specifies which model role(s) an agent should use.
///
/// - `Single("fast")` → YAML `model_role: fast`
/// - `Chain(["fast", "general"])` → YAML `model_role: [fast, general]`
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(untagged)]
pub enum ModelRole {
    Single(String),
    Chain(Vec<String>),
}

// ---------------------------------------------------------------------------
// ResolvedProvider
// ---------------------------------------------------------------------------

/// A concrete provider+model combination with optional config overrides.
///
/// Used in [`AgentConfig::provider_preferences`] to specify an ordered list
/// of providers to try when dispatching this agent.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ResolvedProvider {
    /// Provider identifier (e.g. `"anthropic"`, `"openai"`).
    pub provider: String,

    /// Model name or glob pattern (e.g. `"claude-opus-4-5"`, `"claude-haiku-*"`).
    pub model: String,

    /// Optional extra config blob passed through to the provider.
    #[serde(default)]
    pub config: serde_json::Value,
}

// ---------------------------------------------------------------------------
// AgentConfig
// ---------------------------------------------------------------------------

/// Configuration for a single agent bundle.
///
/// Populated by parsing a `.md`-formatted agent file: YAML front-matter
/// provides the metadata fields; the Markdown body becomes `instruction`.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AgentConfig {
    /// Unique agent name (used as the registry key).
    pub name: String,

    /// Human-readable description surfaced in agent listings.
    pub description: String,

    /// Optional model role override; `None` means inherit from context.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub model_role: Option<ModelRole>,

    /// Optional ordered list of provider preferences for this agent.
    /// When `None`, the caller's default provider selection applies.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub provider_preferences: Option<Vec<ResolvedProvider>>,

    /// Tool name allowlist. Empty vec means inherit all available tools.
    pub tools: Vec<String>,

    /// System prompt for this agent — populated from the `.md` body,
    /// **not** from a YAML key.
    pub instruction: String,
}

// ---------------------------------------------------------------------------
// AgentRegistry
// ---------------------------------------------------------------------------

/// In-memory registry that maps agent names to their [`AgentConfig`].
pub struct AgentRegistry {
    agents: HashMap<String, AgentConfig>,
}

impl Default for AgentRegistry {
    fn default() -> Self {
        Self::new()
    }
}

impl AgentRegistry {
    /// Create an empty registry.
    pub fn new() -> Self {
        Self {
            agents: HashMap::new(),
        }
    }

    /// Add (or replace) an agent in the registry.
    pub fn register(&mut self, config: AgentConfig) {
        self.agents.insert(config.name.clone(), config);
    }

    /// Look up an agent by name.
    pub fn get(&self, name: &str) -> Option<&AgentConfig> {
        self.agents.get(name)
    }

    /// Return all registered agents, sorted by name.
    pub fn list(&self) -> Vec<&AgentConfig> {
        let mut configs: Vec<&AgentConfig> = self.agents.values().collect();
        configs.sort_by(|a, b| a.name.cmp(&b.name));
        configs
    }

    /// Return all registered agent names, sorted.
    pub fn available_names(&self) -> Vec<&str> {
        let mut names: Vec<&str> = self.agents.keys().map(|s| s.as_str()).collect();
        names.sort_unstable();
        names
    }

    /// Set (or replace) the provider preferences for a registered agent.
    ///
    /// No-op if no agent with `name` is currently registered.
    pub fn set_provider_preferences(&mut self, name: &str, prefs: Vec<ResolvedProvider>) {
        if let Some(cfg) = self.agents.get_mut(name) {
            cfg.provider_preferences = Some(prefs);
        }
    }

    /// Scan `dir` and load all agent bundle files into this registry.
    ///
    /// Delegates to [`loader::load_from_dir`].
    pub fn load_from_dir(&mut self, dir: &Path) -> anyhow::Result<usize> {
        loader::load_from_dir(self, dir)
    }
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn model_role_single_round_trips() {
        let role = ModelRole::Single("fast".to_string());
        let json = serde_json::to_string(&role).expect("serialize");
        assert_eq!(json, r#""fast""#);
        let back: ModelRole = serde_json::from_str(&json).expect("deserialize");
        assert_eq!(back, ModelRole::Single("fast".to_string()));
    }

    #[test]
    fn model_role_chain_round_trips() {
        let role = ModelRole::Chain(vec!["fast".to_string(), "general".to_string()]);
        let json = serde_json::to_string(&role).expect("serialize");
        assert_eq!(json, r#"["fast","general"]"#);
        let back: ModelRole = serde_json::from_str(&json).expect("deserialize");
        assert_eq!(
            back,
            ModelRole::Chain(vec!["fast".to_string(), "general".to_string()])
        );
    }

    #[test]
    fn registry_register_and_get() {
        let mut registry = AgentRegistry::new();
        let config = AgentConfig {
            name: "my-agent".to_string(),
            description: "A test agent".to_string(),
            model_role: Some(ModelRole::Single("fast".to_string())),
            provider_preferences: None,
            tools: vec!["bash".to_string()],
            instruction: "You are a test agent.".to_string(),
        };
        registry.register(config.clone());
        let found = registry.get("my-agent").expect("should find agent");
        assert_eq!(found.name, "my-agent");
        assert_eq!(found.description, "A test agent");
        assert_eq!(found.instruction, "You are a test agent.");
    }

    #[test]
    fn registry_list_is_sorted() {
        let mut registry = AgentRegistry::new();
        for name in &["zebra", "alpha", "mango"] {
            registry.register(AgentConfig {
                name: name.to_string(),
                description: String::new(),
                model_role: None,
                provider_preferences: None,
                tools: vec![],
                instruction: String::new(),
            });
        }
        let names: Vec<&str> = registry.list().iter().map(|c| c.name.as_str()).collect();
        assert_eq!(names, vec!["alpha", "mango", "zebra"]);
    }

    #[test]
    fn registry_available_names_are_sorted() {
        let mut registry = AgentRegistry::new();
        for name in &["beta", "alpha", "gamma"] {
            registry.register(AgentConfig {
                name: name.to_string(),
                description: String::new(),
                model_role: None,
                provider_preferences: None,
                tools: vec![],
                instruction: String::new(),
            });
        }
        let names = registry.available_names();
        assert_eq!(names, vec!["alpha", "beta", "gamma"]);
    }

    // ---- New tests for provider_preferences and ModelRole ----------------

    #[test]
    fn agent_config_supports_model_role_single_and_chain() {
        let single = AgentConfig {
            name: "a".to_string(),
            description: "d".to_string(),
            model_role: Some(ModelRole::Single("fast".to_string())),
            provider_preferences: None,
            tools: vec![],
            instruction: String::new(),
        };
        let chain = AgentConfig {
            name: "b".to_string(),
            description: "d".to_string(),
            model_role: Some(ModelRole::Chain(vec![
                "reasoning".to_string(),
                "general".to_string(),
            ])),
            provider_preferences: None,
            tools: vec![],
            instruction: String::new(),
        };
        assert_eq!(
            single.model_role,
            Some(ModelRole::Single("fast".to_string()))
        );
        assert_eq!(
            chain.model_role,
            Some(ModelRole::Chain(vec![
                "reasoning".to_string(),
                "general".to_string()
            ]))
        );
        assert!(single.provider_preferences.is_none());
        assert!(chain.provider_preferences.is_none());
    }

    #[test]
    fn parse_agent_file_reads_model_role_string() {
        let content =
            "---\nmeta:\n  name: test\n  description: test\nmodel_role: fast\n---\nBody.\n";
        let config = crate::parser::parse_agent_file(content).expect("should parse");
        assert_eq!(
            config.model_role,
            Some(ModelRole::Single("fast".to_string()))
        );
        assert!(config.provider_preferences.is_none());
    }

    #[test]
    fn parse_agent_file_reads_model_role_list() {
        let content = "---\nmeta:\n  name: test\n  description: test\nmodel_role:\n  - reasoning\n  - general\n---\nBody.\n";
        let config = crate::parser::parse_agent_file(content).expect("should parse");
        assert_eq!(
            config.model_role,
            Some(ModelRole::Chain(vec![
                "reasoning".to_string(),
                "general".to_string()
            ]))
        );
        assert!(config.provider_preferences.is_none());
    }

    #[test]
    fn registry_set_provider_preferences_updates_existing_agent() {
        let mut registry = AgentRegistry::new();
        registry.register(AgentConfig {
            name: "test-agent".to_string(),
            description: "desc".to_string(),
            model_role: None,
            provider_preferences: None,
            tools: vec![],
            instruction: String::new(),
        });
        let prefs = vec![ResolvedProvider {
            provider: "anthropic".to_string(),
            model: "claude-opus-4-5".to_string(),
            config: serde_json::Value::Null,
        }];
        registry.set_provider_preferences("test-agent", prefs.clone());
        let agent = registry.get("test-agent").expect("agent should exist");
        assert_eq!(agent.provider_preferences, Some(prefs));
    }
}
