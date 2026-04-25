//! Loop-streaming orchestrator module.
//!
//! Provides [`LoopOrchestrator`] — a stateful driver that manages a registry of
//! tools (and context-aware tools) for use in an agent's agentic loop.

use std::collections::HashMap;
use std::sync::Arc;

use serde_json::Value;
use tokio::sync::RwLock;

use amplifier_module_tool_task::{Tool, ToolSpec};
use amplifier_module_tool_task::ContextAwareTool;

// ---------------------------------------------------------------------------
// LoopConfig
// ---------------------------------------------------------------------------

/// Configuration for the agentic loop.
#[derive(Debug, Clone)]
pub struct LoopConfig {
    /// Maximum number of loop steps before the orchestrator forces a stop.
    pub max_steps: usize,
    /// Optional system prompt injected at the start of every loop.
    pub system_prompt: Option<String>,
}

impl Default for LoopConfig {
    fn default() -> Self {
        Self {
            max_steps: 10,
            system_prompt: None,
        }
    }
}

// ---------------------------------------------------------------------------
// LoopOrchestrator
// ---------------------------------------------------------------------------

/// Stateful orchestrator that drives the streaming agentic loop.
///
/// Holds:
/// 1. A [`LoopConfig`] governing loop behaviour.
/// 2. A `tools` registry of [`Tool`] objects keyed by name.
/// 3. A `messages` buffer of conversation-history [`Value`] entries.
/// 4. A `context_aware_tools` registry of [`ContextAwareTool`] objects keyed by name.
pub struct LoopOrchestrator {
    /// Loop configuration (max steps, system prompt, …).
    pub config: LoopConfig,
    /// General tool registry — every registered tool lives here.
    pub tools: RwLock<HashMap<String, Arc<dyn Tool>>>,
    /// Conversation-history buffer used by the loop to build prompts.
    pub messages: RwLock<Vec<Value>>,
    /// Parallel registry of context-aware tools (a subset of `tools`).
    pub context_aware_tools: RwLock<HashMap<String, Arc<dyn ContextAwareTool>>>,
}

impl LoopOrchestrator {
    /// Create a new [`LoopOrchestrator`] with the given configuration.
    pub fn new(config: LoopConfig) -> Self {
        Self {
            config,
            tools: RwLock::new(HashMap::new()),
            messages: RwLock::new(Vec::new()),
            context_aware_tools: RwLock::new(HashMap::new()),
        }
    }

    // -----------------------------------------------------------------------
    // Tool registration
    // -----------------------------------------------------------------------

    /// Register a [`Tool`] by its spec name.
    pub async fn register_tool(&self, tool: Arc<dyn Tool>) {
        let name = tool.get_spec().name.clone();
        self.tools.write().await.insert(name, tool);
    }

    /// Return a snapshot of the current tool registry.
    pub async fn snapshot_tools(&self) -> HashMap<String, Arc<dyn Tool>> {
        self.tools.read().await.clone()
    }

    // -----------------------------------------------------------------------
    // Context-aware tool registration (task-8)
    // -----------------------------------------------------------------------

    /// Register a [`ContextAwareTool`] by its spec name.
    ///
    /// The tool is inserted into **both** the general `tools` registry and the
    /// parallel `context_aware_tools` registry so that the orchestrator can
    /// dispatch it either as a plain [`Tool`] or with context injection.
    pub async fn register_context_aware_tool(&self, tool: Arc<dyn ContextAwareTool>) {
        let name = tool.get_spec().name.clone();
        self.tools
            .write()
            .await
            .insert(name.clone(), tool.clone() as Arc<dyn Tool>);
        self.context_aware_tools.write().await.insert(name, tool);
    }

    /// Return a snapshot of the context-aware tool registry.
    pub async fn snapshot_context_aware_tools(
        &self,
    ) -> HashMap<String, Arc<dyn ContextAwareTool>> {
        self.context_aware_tools.read().await.clone()
    }
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;
    use amplifier_module_tool_task::{ContextAwareTool, ToolError, ToolResult};
    use serde_json::json;
    use std::future::Future;
    use std::pin::Pin;
    use std::sync::Mutex as StdMutex;

    // -----------------------------------------------------------------------
    // Existing baseline tests
    // -----------------------------------------------------------------------

    #[tokio::test]
    async fn register_tool_inserts_into_tools_registry() {
        struct DummyTool;
        impl Tool for DummyTool {
            fn name(&self) -> &str {
                "dummy"
            }
            fn description(&self) -> &str {
                "dummy"
            }
            fn get_spec(&self) -> ToolSpec {
                ToolSpec {
                    name: "dummy".to_string(),
                    description: None,
                    parameters: HashMap::new(),
                    extensions: HashMap::new(),
                }
            }
            fn execute(
                &self,
                _input: Value,
            ) -> Pin<Box<dyn Future<Output = Result<ToolResult, ToolError>> + Send + '_>> {
                Box::pin(async {
                    Ok(ToolResult {
                        success: true,
                        output: None,
                        error: None,
                    })
                })
            }
        }

        let orchestrator = LoopOrchestrator::new(LoopConfig::default());
        orchestrator
            .register_tool(Arc::new(DummyTool))
            .await;
        let tools = orchestrator.snapshot_tools().await;
        assert!(tools.contains_key("dummy"));
    }

    // -----------------------------------------------------------------------
    // Task-8 test: parallel context_aware_tools registry
    // -----------------------------------------------------------------------

    /// StubCAT implements both Tool (name="stub") and ContextAwareTool.
    /// Registering it via `register_context_aware_tool` must insert it
    /// into BOTH `tools` and `context_aware_tools`.
    #[tokio::test]
    async fn register_context_aware_tool_inserts_into_both_registries() {
        struct StubCAT {
            seen: Arc<StdMutex<Vec<Value>>>,
        }

        impl Tool for StubCAT {
            fn name(&self) -> &str {
                "stub"
            }
            fn description(&self) -> &str {
                "stub context-aware tool"
            }
            fn get_spec(&self) -> ToolSpec {
                ToolSpec {
                    name: "stub".to_string(),
                    description: None,
                    parameters: HashMap::new(),
                    extensions: HashMap::new(),
                }
            }
            fn execute(
                &self,
                _input: Value,
            ) -> Pin<Box<dyn Future<Output = Result<ToolResult, ToolError>> + Send + '_>> {
                Box::pin(async {
                    Ok(ToolResult {
                        success: true,
                        output: None,
                        error: None,
                    })
                })
            }
        }

        impl ContextAwareTool for StubCAT {
            fn set_execution_context(&self, messages: Vec<Value>) {
                *self.seen.lock().unwrap() = messages;
            }
        }

        let seen = Arc::new(StdMutex::new(Vec::new()));
        let stub: Arc<StubCAT> = Arc::new(StubCAT { seen: seen.clone() });

        let orchestrator = LoopOrchestrator::new(LoopConfig::default());

        // Register via the new context-aware path.
        orchestrator
            .register_context_aware_tool(stub as Arc<dyn ContextAwareTool>)
            .await;

        // Must appear in the general tools registry.
        let tools = orchestrator.snapshot_tools().await;
        assert!(
            tools.contains_key("stub"),
            "stub must be present in tools after register_context_aware_tool"
        );

        // Must also appear in the context-aware registry.
        let cat_tools = orchestrator.snapshot_context_aware_tools().await;
        assert!(
            cat_tools.contains_key("stub"),
            "stub must be present in context_aware_tools after register_context_aware_tool"
        );

        // Sanity: the retrieved tool can have its context set.
        let retrieved = cat_tools.get("stub").unwrap();
        retrieved.set_execution_context(vec![json!({"role": "user", "content": "hi"})]);
        assert_eq!(seen.lock().unwrap().len(), 1);
    }
}
