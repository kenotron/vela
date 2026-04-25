// Integration tests for DelegateTool — 8 tests covering end-to-end dispatch.

use std::future::Future;
use std::pin::Pin;
use std::sync::{Arc, Mutex};

use amplifier_module_agent_runtime::{AgentConfig, AgentRegistry};
use amplifier_module_tool_delegate::{
    AgentRunner, DelegateTool, DelegateToolConfig, SpawnRequest, Tool, ToolError,
};
use serde_json::json;
use tokio::sync::RwLock;

// ---------------------------------------------------------------------------
// Mock runners
// ---------------------------------------------------------------------------

/// Always returns the configured response string.
struct EchoRunner {
    response: String,
}

impl AgentRunner for EchoRunner {
    fn run<'a>(
        &'a self,
        _req: SpawnRequest,
    ) -> Pin<Box<dyn Future<Output = anyhow::Result<String>> + Send + 'a>> {
        let response = self.response.clone();
        Box::pin(async move { Ok(response) })
    }
}

/// Captures the SpawnRequest it receives, then returns the configured response.
struct CapturingRunner {
    captured: Arc<Mutex<Option<SpawnRequest>>>,
    response: String,
}

impl AgentRunner for CapturingRunner {
    fn run<'a>(
        &'a self,
        req: SpawnRequest,
    ) -> Pin<Box<dyn Future<Output = anyhow::Result<String>> + Send + 'a>> {
        let captured = self.captured.clone();
        let response = self.response.clone();
        Box::pin(async move {
            let mut lock = captured.lock().unwrap();
            *lock = Some(req);
            Ok(response)
        })
    }
}

/// Always returns an error.
struct FailRunner;

impl AgentRunner for FailRunner {
    fn run<'a>(
        &'a self,
        _req: SpawnRequest,
    ) -> Pin<Box<dyn Future<Output = anyhow::Result<String>> + Send + 'a>> {
        Box::pin(async move { Err(anyhow::anyhow!("sub-agent failed")) })
    }
}

// ---------------------------------------------------------------------------
// Registry helper
// ---------------------------------------------------------------------------

/// Build a registry with two pre-registered agents for use across all tests.
/// Returns `Arc<RwLock<AgentRegistry>>` as required by `DelegateTool::new`.
fn make_registry() -> Arc<RwLock<AgentRegistry>> {
    let mut registry = AgentRegistry::new();
    registry.register(AgentConfig {
        name: "explorer".to_string(),
        description: "Explores codebases.".to_string(),
        model_role: None,
        provider_preferences: None,
        tools: vec!["filesystem".to_string(), "bash".to_string()],
        instruction: "You explore codebases.".to_string(),
    });
    registry.register(AgentConfig {
        name: "bug-hunter".to_string(),
        description: "Hunts bugs systematically.".to_string(),
        model_role: None,
        provider_preferences: None,
        tools: vec!["bash".to_string()],
        instruction: "You hunt bugs systematically.".to_string(),
    });
    Arc::new(RwLock::new(registry))
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

/// 1. Successful delegate to a known agent returns the expected JSON shape.
#[tokio::test]
async fn delegate_to_known_agent_returns_correct_shape() {
    let tool = DelegateTool::new(
        Arc::new(EchoRunner {
            response: "exploration complete".to_string(),
        }),
        make_registry(),
    );

    let result = tool
        .execute(json!({
            "agent": "explorer",
            "instruction": "explore the codebase"
        }))
        .await
        .expect("execute should succeed");

    assert!(result.success, "success field should be true");

    let output = result.output.expect("output should be Some");

    assert_eq!(
        output["output"]["response"],
        json!("exploration complete"),
        "response should match EchoRunner output"
    );
    assert_eq!(
        output["output"]["agent"],
        json!("explorer"),
        "agent should be 'explorer'"
    );
    assert_eq!(
        output["output"]["status"],
        json!("success"),
        "status should be 'success'"
    );

    let session_id = output["output"]["session_id"]
        .as_str()
        .expect("session_id should be a string");
    assert!(
        session_id.contains("_explorer"),
        "session_id '{}' should contain '_explorer'",
        session_id
    );
}

/// 2. Delegate without an agent name still succeeds.
#[tokio::test]
async fn delegate_without_agent_succeeds() {
    let tool = DelegateTool::new(
        Arc::new(EchoRunner {
            response: "done".to_string(),
        }),
        make_registry(),
    );

    let result = tool
        .execute(json!({
            "instruction": "do something"
        }))
        .await;

    assert!(
        result.is_ok(),
        "execute without agent should succeed; got: {:?}",
        result
    );
}

/// 3. The agent's system prompt (instruction) is forwarded to the runner.
#[tokio::test]
async fn delegate_passes_agent_system_prompt_to_runner() {
    let captured: Arc<Mutex<Option<SpawnRequest>>> = Arc::new(Mutex::new(None));

    let tool = DelegateTool::new(
        Arc::new(CapturingRunner {
            captured: captured.clone(),
            response: "ok".to_string(),
        }),
        make_registry(),
    );

    tool.execute(json!({
        "agent": "explorer",
        "instruction": "survey the repo"
    }))
    .await
    .expect("execute should succeed");

    let lock = captured.lock().unwrap();
    let req = lock
        .as_ref()
        .expect("CapturingRunner should have captured a request");

    assert_eq!(
        req.agent_system_prompt,
        Some("You explore codebases.".to_string()),
        "agent_system_prompt should match the explorer's instruction"
    );
}

/// 4. Tools listed in `exclude_tools` are removed from the tool filter.
#[tokio::test]
async fn delegate_excludes_configured_tools() {
    let captured: Arc<Mutex<Option<SpawnRequest>>> = Arc::new(Mutex::new(None));

    let mut tool = DelegateTool::new(
        Arc::new(CapturingRunner {
            captured: captured.clone(),
            response: "ok".to_string(),
        }),
        make_registry(),
    );
    tool.config = DelegateToolConfig {
        exclude_tools: vec!["bash".to_string()],
        ..DelegateToolConfig::default()
    };

    tool.execute(json!({
        "agent": "explorer",
        "instruction": "explore"
    }))
    .await
    .expect("execute should succeed");

    let lock = captured.lock().unwrap();
    let req = lock
        .as_ref()
        .expect("CapturingRunner should have captured a request");

    assert!(
        !req.tool_filter.contains(&"bash".to_string()),
        "tool_filter should NOT contain 'bash'; got: {:?}",
        req.tool_filter
    );
    assert!(
        req.tool_filter.contains(&"filesystem".to_string()),
        "tool_filter should contain 'filesystem'; got: {:?}",
        req.tool_filter
    );
}

/// 5. Delegating to an unknown agent returns an error that names the agent and
///    lists available agents.
#[tokio::test]
async fn delegate_to_unknown_agent_returns_error_with_list() {
    let tool = DelegateTool::new(
        Arc::new(EchoRunner {
            response: "irrelevant".to_string(),
        }),
        make_registry(),
    );

    let result = tool
        .execute(json!({
            "agent": "nonexistent-agent",
            "instruction": "do something"
        }))
        .await;

    match result {
        Err(ToolError::ExecutionFailed { message, .. }) => {
            assert!(
                message.contains("nonexistent-agent"),
                "error should mention the unknown agent name; got: '{}'",
                message
            );
            assert!(
                message.contains("explorer") || message.contains("Available"),
                "error should list available agents or say 'Available'; got: '{}'",
                message
            );
        }
        other => panic!(
            "expected Err(ToolError::ExecutionFailed {{ .. }}), got: {:?}",
            other
        ),
    }
}

/// 6. Missing instruction returns `ToolError::Other` (invalid input).
#[tokio::test]
async fn delegate_missing_instruction_returns_invalid_input() {
    let tool = DelegateTool::new(
        Arc::new(EchoRunner {
            response: "irrelevant".to_string(),
        }),
        make_registry(),
    );

    let result = tool
        .execute(json!({
            "agent": "explorer"
            // instruction deliberately omitted
        }))
        .await;

    assert!(
        matches!(result, Err(ToolError::Other { .. })),
        "expected Err(ToolError::Other {{ .. }}) for missing instruction, got: {:?}",
        result
    );
}

/// 7. A runner failure surfaces as `ToolError::ExecutionFailed`.
#[tokio::test]
async fn delegate_runner_failure_surfaces_as_execution_failed() {
    let tool = DelegateTool::new(Arc::new(FailRunner), make_registry());

    let result = tool
        .execute(json!({
            "agent": "explorer",
            "instruction": "..."
        }))
        .await;

    assert!(
        matches!(result, Err(ToolError::ExecutionFailed { .. })),
        "expected Err(ToolError::ExecutionFailed {{ .. }}) when runner fails, got: {:?}",
        result
    );
}

/// 8. Spec reports name == "delegate" and requires "instruction".
#[test]
fn delegate_spec_name_and_required() {
    let tool = DelegateTool::new(
        Arc::new(EchoRunner {
            response: String::new(),
        }),
        make_registry(),
    );

    let spec = tool.get_spec();

    assert_eq!(spec.name, "delegate", "spec.name should be 'delegate'");

    let required = spec
        .parameters
        .get("required")
        .expect("parameters should contain a 'required' key");
    let arr = required
        .as_array()
        .expect("'required' should be a JSON array");
    assert!(
        arr.iter().any(|v| v.as_str() == Some("instruction")),
        "'instruction' must appear in the required list; got: {:?}",
        arr
    );
}
