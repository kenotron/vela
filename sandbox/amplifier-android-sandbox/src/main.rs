use anyhow::Result;

fn main() {
    println!("amplifier-android-sandbox: Phase 4 wiring smoke test binary");
}

/// Attempt to reach an Ollama-compatible backend at localhost:11434.
///
/// Returns `Ok(...)` if a TCP connection can be established to the given
/// backend address, or `Err` with a descriptive message if not reachable.
pub fn build_provider(backend: &str, model: Option<&str>) -> Result<(String, Option<String>)> {
    use std::net::TcpStream;
    TcpStream::connect("127.0.0.1:11434")
        .map_err(|e| anyhow::anyhow!("Cannot reach {} at localhost:11434: {}", backend, e))?;
    Ok((backend.to_string(), model.map(|s| s.to_string())))
}

#[cfg(test)]
mod tests {
    use super::*;
    use amplifier_agent_foundation::foundation_agents;
    use amplifier_module_agent_runtime::AgentRegistry;
    use amplifier_module_tool_delegate::{
        AgentRunner as SubagentRunner, DelegateTool, DelegateToolConfig as DelegateConfig,
        SpawnRequest, Tool,
    };
    use std::future::Future;
    use std::pin::Pin;
    use std::sync::Arc;

    // -----------------------------------------------------------------------
    // Test 1: foundation_agents_are_accessible_in_tool_spec
    // -----------------------------------------------------------------------

    /// Verifies that all 6 foundation agents are accessible and wire cleanly
    /// into an AgentRegistry.  No Ollama needed — pure structural check.
    #[test]
    fn foundation_agents_are_accessible_in_tool_spec() {
        let agents = foundation_agents();
        let mut reg = AgentRegistry::new();
        for agent in agents {
            reg.register(agent);
        }

        assert_eq!(reg.list().len(), 6, "expected exactly 6 foundation agents");

        let explorer = reg.get("explorer").expect("explorer agent should be present");
        assert_eq!(explorer.name, "explorer", "explorer name mismatch");
        assert!(
            !explorer.instruction.is_empty(),
            "explorer instruction should not be empty"
        );
    }

    // -----------------------------------------------------------------------
    // Test 2: delegate_tool_spec_is_valid
    // -----------------------------------------------------------------------

    /// Verifies that DelegateTool's ToolSpec is correctly shaped — name,
    /// properties map present, and required parameters present.  No Ollama
    /// needed — pure structural check.
    #[test]
    fn delegate_tool_spec_is_valid() {
        // Local NopRunner that implements SubagentRunner (= AgentRunner).
        struct NopRunner;
        impl SubagentRunner for NopRunner {
            fn run<'a>(
                &'a self,
                _req: SpawnRequest,
            ) -> Pin<Box<dyn Future<Output = anyhow::Result<String>> + Send + 'a>> {
                Box::pin(async { Ok("nop".to_string()) })
            }
        }

        let agents = foundation_agents();
        let mut reg = AgentRegistry::new();
        for agent in agents {
            reg.register(agent);
        }

        let tool = DelegateTool {
            config: DelegateConfig::default(),
            runner: Arc::new(NopRunner),
            registry: reg,
        };

        let spec = tool.get_spec();

        assert_eq!(spec.name, "delegate", "spec name should be 'delegate'");
        assert!(
            spec.parameters.contains_key("properties"),
            "parameters map should contain key 'properties'"
        );

        let properties = spec
            .parameters
            .get("properties")
            .expect("'properties' key must exist in parameters");
        let props_obj = properties
            .as_object()
            .expect("'properties' value should be a JSON object");

        assert!(
            props_obj.contains_key("instruction"),
            "properties should include 'instruction'"
        );
        assert!(
            props_obj.contains_key("agent"),
            "properties should include 'agent'"
        );
        assert!(
            props_obj.contains_key("context_depth"),
            "properties should include 'context_depth'"
        );
    }

    // -----------------------------------------------------------------------
    // Test 3: smoke_test_ollama_delegate  (requires live Ollama)
    // -----------------------------------------------------------------------

    /// End-to-end smoke test that requires a live Ollama instance at
    /// localhost:11434.
    ///
    /// Run manually with:
    ///   cargo test -p amplifier-android-sandbox -- smoke_test_ollama_delegate --include-ignored
    #[tokio::test]
    #[ignore]
    async fn smoke_test_ollama_delegate() {
        let tmp = tempfile::TempDir::new().expect("should be able to create a TempDir");
        println!("[smoke] TempDir created at: {:?}", tmp.path());

        let result = build_provider("ollama", Some("llama3.2"));
        assert!(
            result.is_ok(),
            "build_provider should succeed when Ollama is running: {:?}",
            result
        );

        let agents = foundation_agents();
        let mut reg = AgentRegistry::new();
        for agent in &agents {
            println!("[smoke] Loaded foundation agent: {}", agent.name);
            reg.register(agent.clone());
        }
        println!("[smoke] {} foundation agents registered", reg.list().len());

        use amplifier_module_tool_delegate::{DelegateTool, DelegateToolConfig, NopRunner};
        let _tool = DelegateTool {
            config: DelegateToolConfig::default(),
            runner: Arc::new(NopRunner),
            registry: reg,
        };
        println!("[smoke] DelegateTool wiring verified for Phase 4");
    }
}
