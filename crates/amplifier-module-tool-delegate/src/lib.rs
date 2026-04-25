pub mod context;
pub mod resolver;

pub use context::{build_inherited_context, ContextDepth, ContextScope};
pub use resolver::{resolve_agent, ResolvedAgent};

use std::collections::HashMap;
use std::future::Future;
use std::pin::Pin;
use std::sync::Arc;

use serde_json::{json, Value};

use amplifier_module_agent_runtime::AgentRegistry;

// ---------------------------------------------------------------------------
// Tool trait + related types
// ---------------------------------------------------------------------------

/// Specification for a tool's parameters, surfaced in the tool registry.
pub struct ToolSpec {
    pub name: String,
    pub description: Option<String>,
    /// JSON-Schema-style parameter object (type, properties, required, …).
    pub parameters: HashMap<String, Value>,
    pub extensions: HashMap<String, Value>,
}

/// Successful result returned by [`Tool::execute`].
#[derive(Debug)]
pub struct ToolResult {
    pub success: bool,
    pub output: Option<Value>,
    pub error: Option<ToolError>,
}

/// Error variants returned by [`Tool::execute`].
#[derive(Debug)]
pub enum ToolError {
    /// Validation / usage error (e.g. missing required parameter).
    Other { message: String },
    /// Execution failure with optional process capture.
    ExecutionFailed {
        message: String,
        stdout: Option<String>,
        stderr: Option<String>,
        exit_code: Option<i32>,
    },
}

/// Trait for all tools that can be invoked by an agent.
pub trait Tool: Send + Sync {
    fn name(&self) -> &str;
    fn description(&self) -> &str;
    fn get_spec(&self) -> ToolSpec;
    fn execute<'a>(
        &'a self,
        input: Value,
    ) -> Pin<Box<dyn Future<Output = Result<ToolResult, ToolError>> + Send + 'a>>;
}

// ---------------------------------------------------------------------------
// AgentRunner trait + SpawnRequest
// ---------------------------------------------------------------------------

/// Request struct passed to the runner when spawning a sub-agent.
pub struct SpawnRequest {
    pub instruction: String,
    /// `None` means context is already embedded in `instruction`.
    pub context_depth: Option<ContextDepth>,
    pub context_scope: ContextScope,
    pub context: Vec<Value>,
    pub session_id: Option<String>,
    pub agent_system_prompt: Option<String>,
    pub tool_filter: Vec<String>,
}

/// Abstraction for running a sub-agent session.
pub trait AgentRunner: Send + Sync {
    fn run<'a>(
        &'a self,
        req: SpawnRequest,
    ) -> Pin<Box<dyn Future<Output = anyhow::Result<String>> + Send + 'a>>;
}

/// No-op runner used in tests — always returns an empty response.
pub struct NopRunner;

impl AgentRunner for NopRunner {
    fn run<'a>(
        &'a self,
        _req: SpawnRequest,
    ) -> Pin<Box<dyn Future<Output = anyhow::Result<String>> + Send + 'a>> {
        Box::pin(async { Ok(String::new()) })
    }
}

// ---------------------------------------------------------------------------
// DelegateTool configuration + struct
// ---------------------------------------------------------------------------

/// Runtime configuration for [`DelegateTool`].
pub struct DelegateToolConfig {
    /// Maximum number of context turns forwarded to a sub-agent.
    pub max_context_turns: u64,
    /// Tool names excluded from every sub-agent's tool list.
    pub exclude_tools: Vec<String>,
}

impl Default for DelegateToolConfig {
    fn default() -> Self {
        Self {
            max_context_turns: 10,
            exclude_tools: vec![],
        }
    }
}

/// Tool that delegates tasks to named sub-agents from the agent registry.
pub struct DelegateTool {
    pub config: DelegateToolConfig,
    pub runner: Arc<dyn AgentRunner>,
    pub registry: AgentRegistry,
    context: Arc<std::sync::Mutex<Vec<Value>>>,
}

// ---------------------------------------------------------------------------
// DelegateTool::dispatch  (pipeline implementation, placed between struct and
// generate_sub_session_id as required by the spec)
// ---------------------------------------------------------------------------

impl DelegateTool {
    /// Create a new [`DelegateTool`] with default configuration.
    pub fn new(runner: Arc<dyn AgentRunner>, registry: AgentRegistry) -> Self {
        Self {
            config: DelegateToolConfig::default(),
            runner,
            registry,
            context: Arc::new(std::sync::Mutex::new(Vec::new())),
        }
    }

    /// Returns a snapshot of the currently stored execution context messages.
    ///
    /// Primarily used in tests and debug tooling.
    pub fn snapshot_context(&self) -> Vec<Value> {
        self.context.lock().unwrap().clone()
    }

    /// Full dispatch pipeline: parse → resolve → build context → spawn → return.
    async fn dispatch(&self, input: Value) -> anyhow::Result<Value> {
        // ------------------------------------------------------------------
        // 1. Parse required `instruction`
        // ------------------------------------------------------------------
        let instruction = match input.get("instruction").and_then(|v| v.as_str()) {
            Some(s) if !s.is_empty() => s.to_string(),
            _ => anyhow::bail!("missing required parameter: 'instruction'"),
        };

        // ------------------------------------------------------------------
        // 2. Parse optional parameters
        // ------------------------------------------------------------------
        let agent_name = input
            .get("agent")
            .and_then(|v| v.as_str())
            .map(|s| s.to_string());

        let provided_session_id = input
            .get("session_id")
            .and_then(|v| v.as_str())
            .map(|s| s.to_string());

        let max_turns = self.config.max_context_turns;
        let context_turns = input
            .get("context_turns")
            .and_then(|v| v.as_u64())
            .unwrap_or(5)
            .min(max_turns) as usize;

        let depth = match input
            .get("context_depth")
            .and_then(|v| v.as_str())
            .unwrap_or("recent")
        {
            "none" => ContextDepth::None,
            "all" => ContextDepth::All,
            _ => ContextDepth::Recent(context_turns),
        };

        let scope = match input
            .get("context_scope")
            .and_then(|v| v.as_str())
            .unwrap_or("conversation")
        {
            "agents" => ContextScope::Agents,
            "full" => ContextScope::Full,
            _ => ContextScope::Conversation,
        };

        // ------------------------------------------------------------------
        // 3. Resolve agent (None if no agent name provided)
        // ------------------------------------------------------------------
        let resolved = if let Some(ref name) = agent_name {
            Some(resolver::resolve_agent(name, &self.registry)?)
        } else {
            None
        };

        // ------------------------------------------------------------------
        // 4. Phase 4 limitation: no parent messages available yet
        // ------------------------------------------------------------------
        let parent_messages: Vec<Value> = vec![];
        let context_str =
            build_inherited_context(&parent_messages, depth, context_turns, scope);

        // ------------------------------------------------------------------
        // 5. Build effective_instruction
        // ------------------------------------------------------------------
        let effective_instruction = match &context_str {
            Some(ctx) => format!("{ctx}\n\n[YOUR TASK]\n{instruction}"),
            None => instruction.clone(),
        };

        // ------------------------------------------------------------------
        // 6. Resolve agent system prompt + tool filter + resolved agent name
        // ------------------------------------------------------------------
        let (agent_system_prompt, filtered_tools, resolved_agent_name) = match &resolved {
            Some(ResolvedAgent::SelfDelegate) => (None, vec![], "self".to_string()),
            Some(ResolvedAgent::FoundAgent(config)) => {
                let system_prompt = if config.instruction.is_empty() {
                    None
                } else {
                    Some(config.instruction.clone())
                };
                let filtered: Vec<String> = config
                    .tools
                    .iter()
                    .filter(|t| !self.config.exclude_tools.contains(t))
                    .cloned()
                    .collect();
                (system_prompt, filtered, config.name.clone())
            }
            None => (None, vec![], "agent".to_string()),
        };

        // ------------------------------------------------------------------
        // 7. Generate sub_session_id
        // ------------------------------------------------------------------
        let sub_session_id = provided_session_id.unwrap_or_else(|| {
            generate_sub_session_id("0000000000000000", &resolved_agent_name)
        });

        // ------------------------------------------------------------------
        // 8. Build SpawnRequest and run
        // ------------------------------------------------------------------
        let req = SpawnRequest {
            instruction: effective_instruction,
            context_depth: None, // already embedded in instruction
            context_scope: ContextScope::Conversation,
            context: vec![],
            session_id: Some(sub_session_id.clone()),
            agent_system_prompt,
            tool_filter: filtered_tools,
        };

        let response = self
            .runner
            .run(req)
            .await
            .map_err(|e| {
                anyhow::anyhow!(
                    "delegate agent '{}' failed: {}",
                    resolved_agent_name,
                    e
                )
            })?;

        // ------------------------------------------------------------------
        // 9. Return result
        // ------------------------------------------------------------------
        Ok(json!({
            "success": true,
            "output": {
                "response": response,
                "session_id": sub_session_id,
                "agent": resolved_agent_name,
                "turn_count": 1,
                "status": "success"
            }
        }))
    }
}

// ---------------------------------------------------------------------------
// generate_sub_session_id  (placed after DelegateTool::dispatch per spec)
// ---------------------------------------------------------------------------

/// Generate a deterministic sub-session ID from a parent session ID and an
/// agent name.  Uses a millisecond timestamp suffix for uniqueness.
fn generate_sub_session_id(_parent_id: &str, agent_name: &str) -> String {
    let ts = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis();
    format!("sub_{}_{}", agent_name, ts)
}

// ---------------------------------------------------------------------------
// impl Tool for DelegateTool
// ---------------------------------------------------------------------------

impl Tool for DelegateTool {
    fn name(&self) -> &str {
        "delegate"
    }

    fn description(&self) -> &str {
        "Delegate a task to a named sub-agent from the agent registry"
    }

    fn get_spec(&self) -> ToolSpec {
        let mut properties: HashMap<String, Value> = HashMap::new();

        properties.insert(
            "agent".to_string(),
            json!({
                "type": "string",
                "description": "Name of the agent to delegate to, or 'self' to spawn a copy of the current agent"
            }),
        );
        properties.insert(
            "instruction".to_string(),
            json!({
                "type": "string",
                "description": "The task instruction for the sub-agent"
            }),
        );
        properties.insert(
            "session_id".to_string(),
            json!({
                "type": "string",
                "description": "Optional session ID to resume an existing sub-agent session"
            }),
        );
        properties.insert(
            "context_depth".to_string(),
            json!({
                "type": "string",
                "enum": ["none", "recent", "all"],
                "description": "How much parent context to pass to the sub-agent"
            }),
        );
        properties.insert(
            "context_turns".to_string(),
            json!({
                "type": "integer",
                "minimum": 1,
                "maximum": 10,
                "description": "Number of recent turns to include when context_depth is 'recent'"
            }),
        );
        properties.insert(
            "context_scope".to_string(),
            json!({
                "type": "string",
                "enum": ["conversation", "agents", "full"],
                "description": "Which message types to include in the context"
            }),
        );
        properties.insert(
            "model_role".to_string(),
            json!({
                "type": "string",
                "description": "Override the model role for this delegation"
            }),
        );

        let mut parameters: HashMap<String, Value> = HashMap::new();
        parameters.insert("type".to_string(), json!("object"));
        parameters.insert("properties".to_string(), json!(properties));
        parameters.insert("required".to_string(), json!(["instruction"]));

        ToolSpec {
            name: "delegate".to_string(),
            description: Some(
                "Delegate a task to a named sub-agent from the agent registry".to_string(),
            ),
            parameters,
            extensions: HashMap::new(),
        }
    }

    fn execute<'a>(
        &'a self,
        input: Value,
    ) -> Pin<Box<dyn Future<Output = Result<ToolResult, ToolError>> + Send + 'a>> {
        let context = Arc::clone(&self.context);
        let max_turns = self.config.max_context_turns;

        Box::pin(async move {
            // (2) Parse required `instruction`; return ToolError::Other if absent.
            let raw_instruction = match input.get("instruction").and_then(|v| v.as_str()) {
                Some(s) if !s.is_empty() => s.to_string(),
                _ => {
                    return Err(ToolError::Other {
                        message: "missing required parameter: 'instruction'".to_string(),
                    })
                }
            };

            // (3) Parse context_depth:
            //   "recent" | "recent_5" → Recent(max_turns)
            //   "all"                 → All
            //   otherwise             → None
            let context_depth =
                match input.get("context_depth").and_then(|v| v.as_str()) {
                    Some("recent") | Some("recent_5") => {
                        ContextDepth::Recent(max_turns as usize)
                    }
                    Some("all") => ContextDepth::All,
                    _ => ContextDepth::None,
                };

            // (4) Parse context_scope:
            //   "agents" → Agents
            //   "full"   → Full
            //   otherwise → Conversation
            let context_scope =
                match input.get("context_scope").and_then(|v| v.as_str()) {
                    Some("agents") => ContextScope::Agents,
                    Some("full") => ContextScope::Full,
                    _ => ContextScope::Conversation,
                };

            // (5) Snapshot parent messages from the shared context buffer.
            let parent_messages = context.lock().unwrap().clone();

            // (6) Build the inherited context block (returns None when depth is None
            //     or the filtered message list is empty).
            let inherited = crate::context::build_inherited_context(
                &parent_messages,
                context_depth,
                max_turns as usize,
                context_scope,
            );

            // (7) Prepend [PARENT CONVERSATION CONTEXT] block when inherited is Some.
            let effective_instruction = match inherited {
                Some(ref block) => format!("{block}\n\n{raw_instruction}"),
                None => raw_instruction.clone(),
            };

            // Rebuild the input JSON with the (possibly enriched) instruction so
            // that dispatch() forwards it verbatim to the runner.  dispatch() uses
            // an empty parent_messages list internally, so it will not add any
            // further context — the block we built above is the only one.
            let mut modified_input = input.clone();
            modified_input["instruction"] = json!(effective_instruction);

            // Delegate to the full dispatch pipeline (agent resolution, tool
            // filtering, session-id generation, output formatting).
            match self.dispatch(modified_input).await {
                Ok(value) => Ok(ToolResult {
                    success: true,
                    output: Some(value),
                    error: None,
                }),
                Err(e) => {
                    let message = e.to_string();
                    if message.contains("missing required parameter") {
                        Err(ToolError::Other { message })
                    } else {
                        Err(ToolError::ExecutionFailed {
                            message,
                            stdout: None,
                            stderr: None,
                            exit_code: None,
                        })
                    }
                }
            }
        })
    }
}

// ---------------------------------------------------------------------------
// impl amplifier_module_tool_task::Tool for DelegateTool
// ---------------------------------------------------------------------------

impl amplifier_module_tool_task::Tool for DelegateTool {
    fn name(&self) -> &str {
        "delegate"
    }

    fn description(&self) -> &str {
        "Delegate a task to a named sub-agent from the agent registry"
    }

    fn get_spec(&self) -> amplifier_module_tool_task::ToolSpec {
        let local = <Self as Tool>::get_spec(self);
        amplifier_module_tool_task::ToolSpec {
            name: local.name,
            description: local.description,
            parameters: local.parameters,
            extensions: local.extensions,
        }
    }

    fn execute<'a>(
        &'a self,
        input: Value,
    ) -> std::pin::Pin<
        Box<
            dyn std::future::Future<
                    Output = Result<
                        amplifier_module_tool_task::ToolResult,
                        amplifier_module_tool_task::ToolError,
                    >,
                > + Send
                + 'a,
        >,
    > {
        Box::pin(async move {
            match self.dispatch(input).await {
                Ok(value) => Ok(amplifier_module_tool_task::ToolResult {
                    success: true,
                    output: Some(value),
                    error: None,
                }),
                Err(e) => {
                    let message = e.to_string();
                    if message.contains("missing required parameter") {
                        Err(amplifier_module_tool_task::ToolError::Other { message })
                    } else {
                        Err(amplifier_module_tool_task::ToolError::ExecutionFailed {
                            message,
                            stdout: None,
                            stderr: None,
                            exit_code: None,
                        })
                    }
                }
            }
        })
    }
}

// ---------------------------------------------------------------------------
// impl ContextAwareTool for DelegateTool
// ---------------------------------------------------------------------------

impl amplifier_module_tool_task::ContextAwareTool for DelegateTool {
    fn set_execution_context(&self, messages: Vec<Value>) {
        *self.context.lock().unwrap() = messages;
    }
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    // -----------------------------------------------------------------------
    // CapturingRunner — stores the SpawnRequest for assertion
    // -----------------------------------------------------------------------
    struct CapturingRunner {
        captured: Arc<std::sync::Mutex<Option<SpawnRequest>>>,
    }

    impl AgentRunner for CapturingRunner {
        fn run<'a>(
            &'a self,
            req: SpawnRequest,
        ) -> Pin<Box<dyn Future<Output = anyhow::Result<String>> + Send + 'a>> {
            let captured = Arc::clone(&self.captured);
            Box::pin(async move {
                *captured.lock().unwrap() = Some(req);
                Ok("captured_response".to_string())
            })
        }
    }

    /// Construct a minimal DelegateTool for use in struct-level tests.
    fn make_tool() -> DelegateTool {
        DelegateTool::new(Arc::new(NopRunner), AgentRegistry::new())
    }

    // -----------------------------------------------------------------------
    // Struct tests (4)
    // -----------------------------------------------------------------------

    #[test]
    fn delegate_tool_name_returns_delegate() {
        let tool = make_tool();
        assert_eq!(tool.name(), "delegate");
    }

    #[test]
    fn delegate_tool_description_is_correct() {
        let tool = make_tool();
        assert_eq!(
            tool.description(),
            "Delegate a task to a named sub-agent from the agent registry"
        );
    }

    #[test]
    fn get_spec_name_is_delegate() {
        let tool = make_tool();
        let spec = tool.get_spec();
        assert_eq!(spec.name, "delegate");
    }

    #[test]
    fn get_spec_required_includes_instruction() {
        let tool = make_tool();
        let spec = tool.get_spec();
        let required = spec
            .parameters
            .get("required")
            .expect("required field should exist in parameters");
        let arr = required.as_array().expect("required should be a JSON array");
        assert!(
            arr.iter().any(|v| v.as_str() == Some("instruction")),
            "'instruction' must appear in the required list"
        );
    }

    // -----------------------------------------------------------------------
    // Execute test (1)
    // -----------------------------------------------------------------------

    #[tokio::test]
    async fn execute_missing_instruction_returns_error() {
        let tool = DelegateTool::new(Arc::new(NopRunner), AgentRegistry::new());
        let result = tool.execute(json!({})).await;
        assert!(
            matches!(result, Err(ToolError::Other { .. })),
            "expected Err(ToolError::Other {{ .. }}) for missing instruction, got: {:?}",
            result
        );
    }

    // -----------------------------------------------------------------------
    // ContextAwareTool test (1)
    // -----------------------------------------------------------------------

    #[test]
    fn delegate_tool_implements_context_aware() {
        use amplifier_module_tool_task::ContextAwareTool;

        let tool = Arc::new(DelegateTool::new(Arc::new(NopRunner), AgentRegistry::new()));
        let cat: Arc<dyn ContextAwareTool> = tool.clone();
        cat.set_execution_context(vec![json!({"role": "user", "content": "hello"})]);
        let ctx = tool.snapshot_context();
        assert_eq!(ctx.len(), 1);
        assert_eq!(ctx[0]["role"], json!("user"));
    }

    // -----------------------------------------------------------------------
    // Context injection tests (2)
    // -----------------------------------------------------------------------

    /// When context_depth is "all" and the context buffer has messages,
    /// execute() must prepend the [PARENT CONVERSATION CONTEXT] block to the
    /// instruction, and req.context must remain empty.
    #[tokio::test]
    async fn execute_prepends_parent_context_block() {
        use amplifier_module_tool_task::ContextAwareTool;

        let captured = Arc::new(std::sync::Mutex::new(None::<SpawnRequest>));
        let tool = DelegateTool::new(
            Arc::new(CapturingRunner {
                captured: captured.clone(),
            }),
            AgentRegistry::new(),
        );

        // Seed the context buffer with a message that must appear in the instruction.
        tool.set_execution_context(vec![
            json!({"role": "user", "content": "rust is a systems language"}),
        ]);

        tool.execute(json!({
            "agent": "self",
            "instruction": "summarise what we discussed",
            "context_depth": "all"
        }))
        .await
        .expect("execute should succeed");

        let lock = captured.lock().unwrap();
        let req = lock
            .as_ref()
            .expect("CapturingRunner should have captured a request");

        assert!(
            req.instruction.contains("[PARENT CONVERSATION CONTEXT]"),
            "instruction must contain context header; got:\n{}",
            req.instruction
        );
        assert!(
            req.instruction.contains("rust is a systems language"),
            "instruction must contain parent message content; got:\n{}",
            req.instruction
        );
        assert!(
            req.instruction.contains("summarise what we discussed"),
            "instruction must contain the raw instruction; got:\n{}",
            req.instruction
        );
        assert!(
            req.context.is_empty(),
            "req.context must be empty (ephemeral); got: {:?}",
            req.context
        );
    }

    /// When context_depth is "none", execute() must NOT prepend any context
    /// block, and the instruction must equal the raw instruction exactly.
    #[tokio::test]
    async fn execute_with_depth_none_omits_context_block() {
        use amplifier_module_tool_task::ContextAwareTool;

        let captured = Arc::new(std::sync::Mutex::new(None::<SpawnRequest>));
        let tool = DelegateTool::new(
            Arc::new(CapturingRunner {
                captured: captured.clone(),
            }),
            AgentRegistry::new(),
        );

        // Context is set but must NOT be injected when depth is "none".
        tool.set_execution_context(vec![
            json!({"role": "user", "content": "some context that must not appear"}),
        ]);

        tool.execute(json!({
            "agent": "self",
            "instruction": "do the thing",
            "context_depth": "none"
        }))
        .await
        .expect("execute should succeed");

        let lock = captured.lock().unwrap();
        let req = lock
            .as_ref()
            .expect("CapturingRunner should have captured a request");

        assert!(
            !req.instruction.contains("[PARENT CONVERSATION CONTEXT]"),
            "instruction must NOT contain context marker; got:\n{}",
            req.instruction
        );
        assert_eq!(
            req.instruction, "do the thing",
            "instruction must be exactly the raw instruction"
        );
    }
}
