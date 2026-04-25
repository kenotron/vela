//! Model-role routing hook — Rust port of `amplifier-module-hooks-routing`.

pub mod composer;
pub mod matrix;
pub mod resolver;

pub use matrix::{Candidate, MatrixConfig, RoleConfig, RolesMap};
pub use resolver::{resolve_model_role, ProviderMap, ResolvedProvider};

use std::path::Path;
use std::sync::Arc;

use tokio::sync::RwLock;

use amplifier_module_agent_runtime::AgentRegistry;

use crate::matrix::default_search_dirs;

// ---------------------------------------------------------------------------
// RoutingConfig
// ---------------------------------------------------------------------------

/// Configuration for the model-role routing hook.
#[derive(Debug, Clone)]
pub struct RoutingConfig {
    /// Matrix name (file basename without `.yaml`).  Defaults to `"balanced"`.
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

/// Routing hook that loads a model-role matrix and resolves provider
/// preferences for agents.
#[allow(dead_code)]
pub struct HooksRouting {
    matrix: MatrixConfig,
    agent_registry: Arc<RwLock<AgentRegistry>>,
    providers: Arc<RwLock<ProviderMap>>,
}

impl HooksRouting {
    /// Create a new `HooksRouting` from a [`RoutingConfig`] and an
    /// `AgentRegistry` wrapped in a shared lock.
    ///
    /// Steps:
    /// 1. Build the default search path.
    /// 2. Load the matrix YAML identified by `config.default_matrix`.
    /// 3. If `config.overrides` is present, apply them via [`composer::compose_matrix`].
    ///    Otherwise, validate the loaded matrix strictly.
    /// 4. Return `Self` with an empty provider map.
    pub fn new(
        config: RoutingConfig,
        agent_registry: Arc<RwLock<AgentRegistry>>,
    ) -> anyhow::Result<Self> {
        let dirs = default_search_dirs();
        let dir_refs: Vec<&Path> = dirs.iter().map(|p| p.as_path()).collect();

        let mut loaded_matrix = matrix::load_matrix_from_dirs(&config.default_matrix, &dir_refs)?;

        if let Some(overrides) = &config.overrides {
            loaded_matrix.roles = composer::compose_matrix(&loaded_matrix.roles, overrides)?;
        } else {
            matrix::validate_matrix(&loaded_matrix.roles, false)?;
        }

        Ok(Self {
            matrix: loaded_matrix,
            agent_registry,
            providers: Arc::new(RwLock::new(ProviderMap::new())),
        })
    }

    /// Replace the provider map.  Called by the sandbox after providers register.
    pub async fn set_providers(&self, providers: ProviderMap) {
        let mut guard = self.providers.write().await;
        *guard = providers;
    }

    /// Return the name of the loaded matrix (e.g. `"balanced"`).
    pub fn matrix_name(&self) -> &str {
        &self.matrix.name
    }

    /// Look up a role by name in the loaded matrix.
    pub fn role(&self, name: &str) -> Option<&RoleConfig> {
        self.matrix.roles.get(name)
    }

    /// Return all role names defined in the loaded matrix.
    pub fn role_names(&self) -> Vec<String> {
        self.matrix.roles.keys().cloned().collect()
    }
}
