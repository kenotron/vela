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
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(untagged)]
pub enum ModelRole {
    Single(String),
    Chain(Vec<String>),
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
    pub model_role: Option<ModelRole>,

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
                tools: vec![],
                instruction: String::new(),
            });
        }
        let names = registry.available_names();
        assert_eq!(names, vec!["alpha", "beta", "gamma"]);
    }
}
