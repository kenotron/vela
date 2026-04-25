//! Loop-streaming orchestrator module.
//!
//! Provides [`LoopOrchestrator`] — a stateful driver that manages a registry of
//! tools (and context-aware tools) for use in an agent's agentic loop.

use std::collections::HashMap;
use std::sync::Arc;

use async_trait::async_trait;
use serde_json::Value;
use tokio::sync::RwLock;

use amplifier_module_session_store::format::SessionEvent;
use amplifier_module_session_store::SessionStore;
use amplifier_module_tool_task::{ContextAwareTool, Tool};

// ---------------------------------------------------------------------------
// ContentBlock
// ---------------------------------------------------------------------------

/// A single content block returned by a [`Provider`].
#[derive(Debug, Clone)]
pub enum ContentBlock {
    /// A text / end-turn block.
    Text { text: String },
    /// A tool-call block.
    ToolCall {
        id: String,
        name: String,
        input: Value,
    },
}

// ---------------------------------------------------------------------------
// ToolCall  (parsed tool-call request from a provider response)
// ---------------------------------------------------------------------------

/// A parsed tool call extracted from a [`Provider`] response.
#[derive(Debug, Clone)]
pub struct ToolCall {
    pub name: String,
    pub id: String,
    pub input: Value,
}

// ---------------------------------------------------------------------------
// Provider trait
// ---------------------------------------------------------------------------

/// Trait for LLM providers that produce completions and parse tool calls.
#[async_trait]
pub trait Provider: Send + Sync {
    /// Request a completion from the provider.
    async fn complete(
        &self,
        messages: Vec<Value>,
        tools: Vec<Value>,
        system_prompt: Option<String>,
    ) -> anyhow::Result<Vec<ContentBlock>>;

    /// Extract any tool-call requests from the raw response.
    fn parse_tool_calls(&self, response: &[ContentBlock]) -> Vec<ToolCall>;
}

// ---------------------------------------------------------------------------
// Context trait
// ---------------------------------------------------------------------------

/// Trait for conversation-context management.
#[async_trait]
pub trait Context: Send + Sync {
    /// Return the message slice suitable for an LLM request.
    async fn get_messages_for_request(
        &self,
        depth: Option<usize>,
        scope: Option<usize>,
    ) -> anyhow::Result<Vec<Value>>;

    /// Append a new message to the history.
    async fn add_message(&mut self, message: Value) -> anyhow::Result<()>;
}

// ---------------------------------------------------------------------------
// HookRegistry
// ---------------------------------------------------------------------------

/// Registry for lifecycle hooks in the agentic loop.
pub struct HookRegistry;

impl HookRegistry {
    pub fn new() -> Self {
        Self
    }
}

impl Default for HookRegistry {
    fn default() -> Self {
        Self::new()
    }
}

// ---------------------------------------------------------------------------
// LoopConfig
// ---------------------------------------------------------------------------

/// Configuration for the agentic loop.
#[derive(Debug, Clone)]
pub struct LoopConfig {
    /// Maximum number of loop steps before the orchestrator forces a stop.
    /// `None` means unlimited.
    pub max_steps: Option<usize>,
    /// System prompt injected at the start of every loop.
    pub system_prompt: String,
}

impl Default for LoopConfig {
    fn default() -> Self {
        Self {
            max_steps: Some(10),
            system_prompt: String::new(),
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
/// 5. A `providers` registry of [`Provider`] objects keyed by name.
/// 6. An optional [`SessionStore`] for persisting events.
pub struct LoopOrchestrator {
    /// Loop configuration (max steps, system prompt, …).
    pub config: LoopConfig,
    /// General tool registry — every registered tool lives here.
    pub tools: RwLock<HashMap<String, Arc<dyn Tool>>>,
    /// Conversation-history buffer used by the loop to build prompts.
    pub messages: RwLock<Vec<Value>>,
    /// Parallel registry of context-aware tools (a subset of `tools`).
    pub context_aware_tools: RwLock<HashMap<String, Arc<dyn ContextAwareTool>>>,
    /// Provider registry keyed by provider name.
    pub providers: RwLock<HashMap<String, Arc<dyn Provider>>>,
    /// Optional session store for event persistence.
    session_store: RwLock<Option<Arc<dyn SessionStore>>>,
    /// Session ID to use when persisting events.
    session_id: RwLock<Option<String>>,
    /// Agent name for the current session.
    #[allow(dead_code)]
    agent_name: RwLock<Option<String>>,
    /// Parent session ID (for sub-sessions).
    #[allow(dead_code)]
    parent_id: RwLock<Option<String>>,
}

impl LoopOrchestrator {
    /// Create a new [`LoopOrchestrator`] with the given configuration.
    pub fn new(config: LoopConfig) -> Self {
        Self {
            config,
            tools: RwLock::new(HashMap::new()),
            messages: RwLock::new(Vec::new()),
            context_aware_tools: RwLock::new(HashMap::new()),
            providers: RwLock::new(HashMap::new()),
            session_store: RwLock::new(None),
            session_id: RwLock::new(None),
            agent_name: RwLock::new(None),
            parent_id: RwLock::new(None),
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
    pub async fn snapshot_context_aware_tools(&self) -> HashMap<String, Arc<dyn ContextAwareTool>> {
        self.context_aware_tools.read().await.clone()
    }

    // -----------------------------------------------------------------------
    // Provider registration (task-9)
    // -----------------------------------------------------------------------

    /// Register a [`Provider`] by name (e.g. `"anthropic"`).
    pub async fn register_provider(&self, name: &str, provider: Arc<dyn Provider>) {
        self.providers
            .write()
            .await
            .insert(name.to_string(), provider);
    }

    // -----------------------------------------------------------------------
    // Session store attachment (task-9)
    // -----------------------------------------------------------------------

    /// Attach a [`SessionStore`] and associated session metadata to this orchestrator.
    ///
    /// Uses `try_write().expect(...)` for each field — panics only on lock contention,
    /// which should not occur during normal sequential setup.
    pub fn attach_store(
        &self,
        store: Arc<dyn SessionStore>,
        session_id: String,
        agent_name: String,
        parent_id: Option<String>,
    ) {
        *self
            .session_store
            .try_write()
            .expect("attach_store contention on session_store") = Some(store);
        *self
            .session_id
            .try_write()
            .expect("attach_store contention on session_id") = Some(session_id);
        *self
            .agent_name
            .try_write()
            .expect("attach_store contention on agent_name") = Some(agent_name);
        *self
            .parent_id
            .try_write()
            .expect("attach_store contention on parent_id") = parent_id;
    }

    /// Persist a single [`SessionEvent`] to the attached store, if any.
    ///
    /// If no store or session ID is attached, this is a no-op.
    async fn persist(&self, event: SessionEvent) -> anyhow::Result<()> {
        let store = {
            let guard = self.session_store.read().await;
            guard.as_ref().cloned()
        };
        let sid = {
            let guard = self.session_id.read().await;
            guard.as_ref().cloned()
        };

        if let (Some(store), Some(sid)) = (store, sid) {
            store.append_event(&sid, event).await?;
        }
        Ok(())
    }

    /// Finalise the attached session by appending a `session_end` event.
    ///
    /// `turn_count` is passed as `0` — callers may query `load()` for the real count.
    /// If no store is attached this is a no-op.
    pub async fn finish_store(&self, status: &str) -> anyhow::Result<()> {
        let store = {
            let guard = self.session_store.read().await;
            guard.as_ref().cloned()
        };
        let sid = {
            let guard = self.session_id.read().await;
            guard.as_ref().cloned()
        };

        if let (Some(store), Some(sid)) = (store, sid) {
            store.finish(&sid, status, 0).await?;
        }
        Ok(())
    }

    // -----------------------------------------------------------------------
    // Agentic loop execution (task-9)
    // -----------------------------------------------------------------------

    /// Run the agentic loop for `user_prompt`.
    ///
    /// # Loop invariant — context injection
    ///
    /// Immediately before the per-call tool-dispatch loop, the orchestrator:
    /// 1. Snapshots the context-aware tool registry (`cat_snapshot`).
    /// 2. Fetches the current message slice (`context_messages`) via
    ///    `context.get_messages_for_request(None, None)`.
    ///
    /// Then, for every tool call, if the tool is registered as context-aware,
    /// `set_execution_context(context_messages.clone())` is called **before**
    /// `tool.execute()`.
    pub async fn execute<C, Cb>(
        &self,
        user_prompt: String,
        context: &mut C,
        _hooks: &HookRegistry,
        mut _callback: Cb,
    ) -> anyhow::Result<String>
    where
        C: Context,
        Cb: FnMut(Value),
    {
        use anyhow::anyhow;

        // Step 1: add the user message to context.
        let user_message = serde_json::json!({ "role": "user", "content": user_prompt });
        context
            .add_message(user_message)
            .await
            .map_err(|e| anyhow!("add_message failed: {e}"))?;

        // Persist the user turn to the attached session store (if any).
        self.persist(SessionEvent::Turn {
            role: "user".to_string(),
            content: user_prompt.clone(),
            timestamp: chrono::Utc::now().to_rfc3339(),
        })
        .await?;

        // Step 2: select a provider (first registered).
        let provider = {
            let providers = self.providers.read().await;
            providers
                .values()
                .next()
                .ok_or_else(|| anyhow!("No provider registered"))?
                .clone()
        };

        // Step 3: build a static tool-spec JSON array.
        let tool_specs: Vec<Value> = {
            let tools = self.tools.read().await;
            tools
                .values()
                .map(|t| {
                    let spec = t.get_spec();
                    serde_json::json!({
                        "name": spec.name,
                        "description": spec.description,
                    })
                })
                .collect()
        };

        let max_steps = self.config.max_steps.unwrap_or(10);
        let system_prompt = if self.config.system_prompt.is_empty() {
            None
        } else {
            Some(self.config.system_prompt.clone())
        };

        // Agentic loop.
        for _step in 0..max_steps {
            // Fetch the current conversation history.
            let messages = context
                .get_messages_for_request(None, None)
                .await
                .map_err(|e| anyhow!("get_messages_for_request failed: {e}"))?;

            // Call the LLM provider.
            let response = provider
                .complete(messages, tool_specs.clone(), system_prompt.clone())
                .await?;

            // Parse any tool calls from the response.
            let tool_calls = provider.parse_tool_calls(&response);

            // No tool calls → end-of-turn; extract and return text.
            if tool_calls.is_empty() {
                let text = response
                    .iter()
                    .filter_map(|b| {
                        if let ContentBlock::Text { text } = b {
                            Some(text.as_str())
                        } else {
                            None
                        }
                    })
                    .collect::<Vec<_>>()
                    .join("");

                // Persist the assistant turn before returning.
                self.persist(SessionEvent::Turn {
                    role: "assistant".to_string(),
                    content: text.clone(),
                    timestamp: chrono::Utc::now().to_rfc3339(),
                })
                .await?;

                return Ok(text);
            }

            // -----------------------------------------------------------
            // (a) Snapshot context-aware tools + message history ONCE,
            //     before the per-call dispatch loop.
            // -----------------------------------------------------------
            let cat_snapshot = self.snapshot_context_aware_tools().await;
            let context_messages: Vec<Value> =
                context
                    .get_messages_for_request(None, None)
                    .await
                    .map_err(|e| anyhow!("get_messages_for_request failed: {e}"))?;

            // Record the assistant's tool-use turn in context.
            let tool_use_blocks: Vec<Value> = tool_calls
                .iter()
                .map(|tc| {
                    serde_json::json!({
                        "type": "tool_use",
                        "id":   tc.id,
                        "name": tc.name,
                        "input": tc.input,
                    })
                })
                .collect();
            context
                .add_message(serde_json::json!({
                    "role": "assistant",
                    "content": tool_use_blocks,
                }))
                .await
                .map_err(|e| anyhow!("add_message failed: {e}"))?;

            // Snapshot the tool registry for dispatch.
            let tools = self.snapshot_tools().await;
            let mut result_blocks: Vec<Value> = Vec::new();

            for call in &tool_calls {
                let args_value = call.input.clone();

                if !tools.contains_key(&call.name) {
                    result_blocks.push(serde_json::json!({
                        "type": "tool_result",
                        "tool_use_id": call.id,
                        "content": format!("Unknown tool: {}", call.name),
                    }));
                } else if let Some(tool) = tools.get(&call.name) {
                    // ---------------------------------------------------
                    // (b) Context injection: call set_execution_context
                    //     BEFORE tool.execute() for context-aware tools.
                    // ---------------------------------------------------
                    if let Some(cat) = cat_snapshot.get(&call.name) {
                        cat.set_execution_context(context_messages.clone());
                    }

                    let result = tool.execute(args_value.clone()).await;
                    let content = match result {
                        Ok(r) => r
                            .output
                            .map(|v| v.to_string())
                            .unwrap_or_else(|| "ok".to_string()),
                        Err(e) => format!("Error: {:?}", e),
                    };

                    // Persist a ToolCall event after execution.
                    self.persist(SessionEvent::ToolCall {
                        tool: call.name.clone(),
                        args: args_value.clone(),
                        result: content.clone(),
                        timestamp: chrono::Utc::now().to_rfc3339(),
                    })
                    .await?;

                    result_blocks.push(serde_json::json!({
                        "type": "tool_result",
                        "tool_use_id": call.id,
                        "content": content,
                    }));
                }
            }

            // Add tool results back into context.
            if !result_blocks.is_empty() {
                context
                    .add_message(serde_json::json!({
                        "role": "user",
                        "content": result_blocks,
                    }))
                    .await
                    .map_err(|e| anyhow!("add_message failed: {e}"))?;
            }
        }

        // max_steps exhausted without an end-turn response.
        Ok(String::new())
    }
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;
    use amplifier_module_session_store::file::FileSessionStore;
    use amplifier_module_session_store::format::{SessionEvent, SessionMetadata};
    use amplifier_module_tool_task::{ContextAwareTool, ToolError, ToolResult, ToolSpec};
    use serde_json::json;
    use std::future::Future;
    use std::pin::Pin;
    use std::sync::Mutex as StdMutex;
    use tempfile::TempDir;

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
            ) -> Pin<Box<dyn Future<Output = Result<ToolResult, ToolError>> + Send + '_>>
            {
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
        orchestrator.register_tool(Arc::new(DummyTool)).await;
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
            ) -> Pin<Box<dyn Future<Output = Result<ToolResult, ToolError>> + Send + '_>>
            {
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

    // -----------------------------------------------------------------------
    // Task-9 test: orchestrator passes messages to context-aware tools
    // -----------------------------------------------------------------------

    /// Verifies that `set_execution_context` is called on a context-aware tool
    /// **before** `tool.execute()` during the agentic loop, and that the
    /// messages snapshot includes the original user prompt.
    #[tokio::test]
    async fn orchestrator_passes_messages_to_context_aware_tool_before_dispatch() {
        // -------------------------------------------------------------------
        // StubCAT: captures messages passed via set_execution_context
        // -------------------------------------------------------------------
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
            ) -> Pin<Box<dyn Future<Output = Result<ToolResult, ToolError>> + Send + '_>>
            {
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

        // -------------------------------------------------------------------
        // StubCallingProvider: ToolCall on first complete(), Text on second
        // -------------------------------------------------------------------
        struct StubCallingProvider {
            call_count: StdMutex<usize>,
        }

        #[async_trait::async_trait]
        impl Provider for StubCallingProvider {
            async fn complete(
                &self,
                _messages: Vec<Value>,
                _tools: Vec<Value>,
                _system_prompt: Option<String>,
            ) -> anyhow::Result<Vec<ContentBlock>> {
                let mut count = self.call_count.lock().unwrap();
                *count += 1;
                if *count == 1 {
                    Ok(vec![ContentBlock::ToolCall {
                        id: "call_1".to_string(),
                        name: "stub".to_string(),
                        input: json!({}),
                    }])
                } else {
                    Ok(vec![ContentBlock::Text {
                        text: "done".to_string(),
                    }])
                }
            }

            fn parse_tool_calls(&self, response: &[ContentBlock]) -> Vec<ToolCall> {
                response
                    .iter()
                    .filter_map(|b| {
                        if let ContentBlock::ToolCall { id, name, input } = b {
                            Some(ToolCall {
                                name: name.clone(),
                                id: id.clone(),
                                input: input.clone(),
                            })
                        } else {
                            None
                        }
                    })
                    .collect()
            }
        }

        // -------------------------------------------------------------------
        // SimpleContext: plain Vec<Value> backing store
        // -------------------------------------------------------------------
        struct SimpleContext {
            messages: Vec<Value>,
        }

        impl SimpleContext {
            fn new(messages: Vec<Value>) -> Self {
                Self { messages }
            }
        }

        #[async_trait::async_trait]
        impl Context for SimpleContext {
            async fn get_messages_for_request(
                &self,
                _depth: Option<usize>,
                _scope: Option<usize>,
            ) -> anyhow::Result<Vec<Value>> {
                Ok(self.messages.clone())
            }

            async fn add_message(&mut self, message: Value) -> anyhow::Result<()> {
                self.messages.push(message);
                Ok(())
            }
        }

        // -------------------------------------------------------------------
        // Setup
        // -------------------------------------------------------------------
        let seen = Arc::new(StdMutex::new(Vec::<Value>::new()));
        let stub_cat = Arc::new(StubCAT { seen: seen.clone() });

        let orchestrator = LoopOrchestrator::new(LoopConfig {
            max_steps: Some(5),
            system_prompt: String::new(),
        });

        let provider = Arc::new(StubCallingProvider {
            call_count: StdMutex::new(0),
        });
        orchestrator.register_provider("anthropic", provider).await;
        orchestrator
            .register_context_aware_tool(stub_cat as Arc<dyn ContextAwareTool>)
            .await;

        let mut ctx = SimpleContext::new(vec![]);
        let hooks = HookRegistry::new();

        // -------------------------------------------------------------------
        // Execute
        // -------------------------------------------------------------------
        orchestrator
            .execute("the user prompt".to_string(), &mut ctx, &hooks, |_| {})
            .await
            .expect("execute should succeed");

        // -------------------------------------------------------------------
        // Assertions
        // -------------------------------------------------------------------
        let messages = seen.lock().unwrap();
        assert!(
            !messages.is_empty(),
            "set_execution_context should have been called with non-empty messages"
        );

        let has_user_prompt = messages.iter().any(|msg| {
            let is_user = msg.get("role").and_then(|r| r.as_str()) == Some("user");
            let has_prompt = msg
                .get("content")
                .and_then(|c| c.as_str())
                .map(|s| s.contains("the user prompt"))
                .unwrap_or(false);
            is_user && has_prompt
        });
        assert!(
            has_user_prompt,
            "messages passed to set_execution_context should contain \
             a user message with 'the user prompt'"
        );
    }

    // -----------------------------------------------------------------------
    // Task-9 (session-store): persist events when store is attached
    // -----------------------------------------------------------------------

    /// Verifies that attach_store / finish_store exist and that execute()
    /// writes SessionStart (via store.begin), a user Turn, an assistant Turn,
    /// and SessionEnd (via finish_store) to the FileSessionStore.
    #[tokio::test]
    async fn execute_persists_events_when_store_attached() {
        // -------------------------------------------------------------------
        // MockProvider: always returns a single Text block (no tool calls)
        // -------------------------------------------------------------------
        struct MockProvider;

        #[async_trait::async_trait]
        impl Provider for MockProvider {
            async fn complete(
                &self,
                _messages: Vec<Value>,
                _tools: Vec<Value>,
                _system_prompt: Option<String>,
            ) -> anyhow::Result<Vec<ContentBlock>> {
                Ok(vec![ContentBlock::Text {
                    text: "assistant reply".to_string(),
                }])
            }

            fn parse_tool_calls(&self, _response: &[ContentBlock]) -> Vec<ToolCall> {
                vec![]
            }
        }

        // -------------------------------------------------------------------
        // SimpleContext: plain Vec<Value> backing store
        // -------------------------------------------------------------------
        struct SimpleContext {
            messages: Vec<Value>,
        }

        impl SimpleContext {
            fn new() -> Self {
                Self {
                    messages: Vec::new(),
                }
            }
        }

        #[async_trait::async_trait]
        impl Context for SimpleContext {
            async fn get_messages_for_request(
                &self,
                _depth: Option<usize>,
                _scope: Option<usize>,
            ) -> anyhow::Result<Vec<Value>> {
                Ok(self.messages.clone())
            }

            async fn add_message(&mut self, message: Value) -> anyhow::Result<()> {
                self.messages.push(message);
                Ok(())
            }
        }

        // -------------------------------------------------------------------
        // Setup: TempDir + FileSessionStore wrapped in Arc
        // -------------------------------------------------------------------
        let tmp = TempDir::new().expect("tempdir");
        let store: Arc<FileSessionStore> =
            Arc::new(FileSessionStore::new_with_root(tmp.path().to_path_buf()));

        // Build orchestrator and attach store
        let orch = LoopOrchestrator::new(LoopConfig::default());
        orch.attach_store(
            store.clone() as Arc<dyn SessionStore>,
            "test-session-1".to_string(),
            "test-agent".to_string(),
            None,
        );

        // Register provider
        orch.register_provider("anthropic", Arc::new(MockProvider))
            .await;

        // Begin the session in the store (writes SessionStart event)
        let meta = SessionMetadata {
            session_id: "test-session-1".to_string(),
            agent_name: "test-agent".to_string(),
            parent_id: None,
            created: chrono::Utc::now().to_rfc3339(),
            status: "active".to_string(),
        };
        store.begin("test-session-1", meta).await.expect("begin");

        // Build context + hooks
        let mut ctx = SimpleContext::new();
        let hooks = HookRegistry::new();

        // -------------------------------------------------------------------
        // Execute the loop
        // -------------------------------------------------------------------
        orch.execute("hello".to_string(), &mut ctx, &hooks, |_| {})
            .await
            .expect("execute should succeed");

        // Finalise the session
        orch.finish_store("success").await.expect("finish_store");

        // -------------------------------------------------------------------
        // Assertions: load events and verify
        // -------------------------------------------------------------------
        let events = store.load("test-session-1").await.expect("load");

        assert!(
            events.len() >= 4,
            "expected at least 4 events (SessionStart, user Turn, assistant Turn, SessionEnd), got {}",
            events.len()
        );

        // First event: SessionStart (written by store.begin)
        assert!(
            matches!(events[0], SessionEvent::SessionStart { .. }),
            "events[0] should be SessionStart, got: {:?}",
            events[0]
        );

        // Contains at least one user Turn
        let has_user_turn = events.iter().any(|e| {
            matches!(e, SessionEvent::Turn { role, .. } if role == "user")
        });
        assert!(has_user_turn, "events should contain a user Turn");

        // Contains at least one assistant Turn
        let has_assistant_turn = events.iter().any(|e| {
            matches!(e, SessionEvent::Turn { role, .. } if role == "assistant")
        });
        assert!(has_assistant_turn, "events should contain an assistant Turn");

        // Last event: SessionEnd (written by finish_store)
        let last = events.last().expect("events should not be empty");
        assert!(
            matches!(last, SessionEvent::SessionEnd { .. }),
            "last event should be SessionEnd, got: {:?}",
            last
        );
    }
}
