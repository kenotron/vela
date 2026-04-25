//! End-to-end integration test: `DelegateTool` receives parent context through
//! the full `LoopOrchestrator` loop.

use amplifier_module_agent_runtime::{AgentConfig, AgentRegistry};
use amplifier_module_orchestrator_loop_streaming::{
    ContentBlock, Context, HookRegistry, LoopConfig, LoopOrchestrator, Provider, ToolCall,
};
use amplifier_module_tool_delegate::{AgentRunner, DelegateTool, SpawnRequest};
use amplifier_module_tool_task::ContextAwareTool;
use serde_json::{json, Value};
use std::future::Future;
use std::pin::Pin;
use std::sync::{Arc, Mutex};

// ---------------------------------------------------------------------------
// SimpleContext — minimal in-test implementation of the Context trait
// ---------------------------------------------------------------------------

struct SimpleContext {
    messages: Vec<Value>,
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

// ---------------------------------------------------------------------------
// DelegateCallingProvider — returns a delegate ToolCall on the first call,
// then an end-turn Text block on all subsequent calls.
// ---------------------------------------------------------------------------

struct DelegateCallingProvider {
    call_count: Mutex<usize>,
}

#[async_trait::async_trait]
impl Provider for DelegateCallingProvider {
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
                id: "call_delegate_1".to_string(),
                name: "delegate".to_string(),
                input: json!({
                    "agent": "explorer",
                    "instruction": "summarise",
                    "context_depth": "recent"
                }),
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

// ---------------------------------------------------------------------------
// CapturingRunner — stores the received SpawnRequest for later assertion.
// ---------------------------------------------------------------------------

struct CapturingRunner {
    captured: Arc<Mutex<Option<SpawnRequest>>>,
}

impl AgentRunner for CapturingRunner {
    fn run<'a>(
        &'a self,
        req: SpawnRequest,
    ) -> Pin<Box<dyn Future<Output = anyhow::Result<String>> + Send + 'a>> {
        let captured = Arc::clone(&self.captured);
        Box::pin(async move {
            *captured.lock().unwrap() = Some(req);
            Ok("subagent-result".to_string())
        })
    }
}

// ---------------------------------------------------------------------------
// Integration test
// ---------------------------------------------------------------------------

/// Verifies that, when the full LoopOrchestrator loop dispatches a `delegate`
/// tool call, the real `DelegateTool` receives the parent conversation history
/// (via `set_execution_context`) and forwards it as a `[PARENT CONVERSATION
/// CONTEXT]` block embedded in the sub-agent's instruction.
#[tokio::test]
async fn delegate_receives_parent_context_through_orchestrator() {
    // -----------------------------------------------------------------------
    // (a) Pre-seed SimpleContext with 6 conversation turns about Rust
    //     (3 user/assistant pairs covering rust language, ownership, borrowing)
    // -----------------------------------------------------------------------
    let mut ctx = SimpleContext {
        messages: vec![
            json!({"role": "user", "content": "what is rust"}),
            json!({"role": "assistant", "content": "Rust is a systems programming language"}),
            json!({"role": "user", "content": "what about ownership"}),
            json!({"role": "assistant", "content": "Rust ownership system ensures memory safety"}),
            json!({"role": "user", "content": "what about borrowing"}),
            json!({"role": "assistant", "content": "borrowing lets you reference without moving"}),
        ],
    };

    // -----------------------------------------------------------------------
    // (b) Build LoopOrchestrator
    // -----------------------------------------------------------------------
    let orchestrator = LoopOrchestrator::new(LoopConfig {
        max_steps: Some(5),
        system_prompt: String::new(),
    });

    // -----------------------------------------------------------------------
    // (c) Register DelegateCallingProvider
    // -----------------------------------------------------------------------
    let provider = Arc::new(DelegateCallingProvider {
        call_count: Mutex::new(0),
    });
    orchestrator.register_provider("default", provider).await;

    // -----------------------------------------------------------------------
    // (d) CapturingRunner stashes the received SpawnRequest
    // -----------------------------------------------------------------------
    let captured = Arc::new(Mutex::new(None::<SpawnRequest>));
    let runner: Arc<dyn AgentRunner> = Arc::new(CapturingRunner {
        captured: captured.clone(),
    });

    // -----------------------------------------------------------------------
    // (e) Build DelegateTool, register "explorer" so dispatch succeeds,
    //     then register as a context-aware tool.
    // -----------------------------------------------------------------------
    let mut registry = AgentRegistry::new();
    registry.register(AgentConfig {
        name: "explorer".to_string(),
        description: "Deep local-context reconnaissance agent.".to_string(),
        model_role: None,
        provider_preferences: None,
        tools: vec![],
        instruction: String::new(),
    });
    let delegate_tool = Arc::new(DelegateTool::new(
        runner,
        Arc::new(tokio::sync::RwLock::new(registry)),
    ));
    orchestrator
        .register_context_aware_tool(delegate_tool as Arc<dyn ContextAwareTool>)
        .await;

    // -----------------------------------------------------------------------
    // (f) Execute the agentic loop
    // -----------------------------------------------------------------------
    let hooks = HookRegistry::new();
    orchestrator
        .execute(
            "now summarise our discussion".to_string(),
            &mut ctx,
            &hooks,
            |_| {},
        )
        .await
        .expect("orchestrator execute should succeed");

    // -----------------------------------------------------------------------
    // (g) Assert the captured SpawnRequest
    // -----------------------------------------------------------------------
    let lock = captured.lock().unwrap();
    let req = lock
        .as_ref()
        .expect("CapturingRunner should have captured a SpawnRequest — delegate was not dispatched");

    assert!(
        req.instruction.contains("[PARENT CONVERSATION CONTEXT]"),
        "instruction must contain [PARENT CONVERSATION CONTEXT] header; got:\n{}",
        req.instruction
    );
    assert!(
        req.instruction.contains("[END PARENT CONTEXT]"),
        "instruction must contain [END PARENT CONTEXT] footer; got:\n{}",
        req.instruction
    );
    assert!(
        req.context.is_empty(),
        "SpawnRequest.context must be empty (context is embedded in instruction); got: {:?}",
        req.context
    );
    assert!(
        req.instruction.contains("summarise"),
        "instruction must contain the original instruction 'summarise'; got:\n{}",
        req.instruction
    );
    assert!(
        req.instruction.contains("borrowing lets you reference without moving")
            || req.instruction.contains("what about borrowing"),
        "instruction must contain at least one recent conversation turn; got:\n{}",
        req.instruction
    );
}
