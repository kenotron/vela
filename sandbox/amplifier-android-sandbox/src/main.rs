use amplifier_module_session_store::file::FileSessionStore;
use amplifier_module_tool_delegate::{DelegateTool, DelegateToolConfig, NopRunner};
use anyhow::{Context, Result};
use std::sync::Arc;

#[tokio::main]
async fn main() -> Result<()> {
    use amplifier_agent_foundation::foundation_agents;
    use amplifier_module_agent_runtime::AgentRegistry;
    use amplifier_module_hooks_routing::{HookRegistry, HooksRouting, ProviderMap, RoutingConfig};

    // Build agent registry and populate it with foundation agents.
    let mut agent_registry = AgentRegistry::new();
    for agent in foundation_agents() {
        agent_registry.register(agent);
    }

    // Switch to Arc<RwLock<AgentRegistry>> so the routing hook and the
    // orchestrator can share the same registry for concurrent read/write.
    let registry = Arc::new(tokio::sync::RwLock::new(agent_registry));

    // Build the hook registry and attach the HooksRouting hooks.
    let mut hook_registry = HookRegistry::new();

    let routing = HooksRouting::new(
        RoutingConfig::default(),
        Arc::clone(&registry),
    )
    .context("failed to load routing matrix")?;
    routing.register_on(&mut hook_registry);

    // Push the live provider into the routing's provider map.
    //
    // In a production sandbox this would be:
    //   let provider_arc: Arc<dyn amplifier_core::traits::Provider> =
    //       Arc::from(build_real_provider());
    //   orchestrator.register_provider(&args.provider, Arc::clone(&provider_arc)).await;
    //   let mut provider_map = ProviderMap::new();
    //   provider_map.insert(args.provider.clone(), Arc::clone(&provider_arc));
    //   routing.set_providers(provider_map).await;
    //
    // For this wiring smoke test, call set_providers with an empty map
    // (no live provider available in the sandbox binary).
    let provider_map = ProviderMap::new();
    routing.set_providers(provider_map).await;

    println!(
        "amplifier-android-sandbox: HooksRouting wired, matrix='{}', {} foundation agents loaded",
        routing.matrix_name(),
        registry.read().await.list().len(),
    );

    // Build a shared FileSessionStore rooted at ~/.amplifier/sessions and
    // wire it into the DelegateTool so the sandbox writes/reads transcripts.
    let session_store: Arc<dyn amplifier_module_session_store::SessionStore> =
        Arc::new(FileSessionStore::new()?);
    let _delegate = DelegateTool::new_with_store(
        Arc::new(NopRunner),
        registry.clone(),
        DelegateToolConfig::default(),
        session_store,
    );
    println!("amplifier-android-sandbox: FileSessionStore wired into DelegateTool");

    Ok(())
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
        AgentRunner as SubagentRunner, DelegateTool, SpawnRequest, Tool,
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

        // Wrap registry in Arc<RwLock<_>> as required by task-10.
        let registry = Arc::new(tokio::sync::RwLock::new(reg));

        let tool = DelegateTool::new(Arc::new(NopRunner), registry);

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
    // Test 2c: file_session_store_wires_into_sandbox_delegate_tool
    // -----------------------------------------------------------------------

    /// Verifies that FileSessionStore can be constructed and wired into
    /// DelegateTool::new_with_store from within the sandbox crate.
    /// RED: fails until amplifier-module-session-store is in Cargo.toml.
    #[test]
    fn file_session_store_wires_into_sandbox_delegate_tool() {
        use amplifier_module_session_store::file::FileSessionStore;
        use amplifier_module_tool_delegate::{DelegateTool, DelegateToolConfig, NopRunner};

        let tmp = tempfile::TempDir::new().expect("tempdir");
        let store: Arc<dyn amplifier_module_session_store::SessionStore> =
            Arc::new(FileSessionStore::new_with_root(tmp.path().to_path_buf()));

        let registry = Arc::new(tokio::sync::RwLock::new(AgentRegistry::new()));

        let tool = DelegateTool::new_with_store(
            Arc::new(NopRunner),
            registry,
            DelegateToolConfig::default(),
            store,
        );
        assert_eq!(
            tool.name(),
            "delegate",
            "wired DelegateTool should report name 'delegate'"
        );
    }

    // -----------------------------------------------------------------------
    // Test 2b: hooks_routing_wires_to_arc_rwlock_registry
    // -----------------------------------------------------------------------

    /// Verifies that HooksRouting wires up correctly with Arc<RwLock<AgentRegistry>>.
    /// This is the core integration test for task-10.
    #[tokio::test]
    async fn hooks_routing_wires_to_arc_rwlock_registry() {
        use amplifier_module_hooks_routing::{HookRegistry, HooksRouting, ProviderMap, RoutingConfig};

        let mut agent_registry = AgentRegistry::new();
        for agent in foundation_agents() {
            agent_registry.register(agent);
        }

        let registry = Arc::new(tokio::sync::RwLock::new(agent_registry));

        let routing = HooksRouting::new(
            RoutingConfig::default(),
            Arc::clone(&registry),
        )
        .expect("HooksRouting::new should succeed with balanced matrix");

        let mut hook_registry = HookRegistry::new();
        routing.register_on(&mut hook_registry);

        // set_providers must be called — the CRITICAL step per task-10 spec.
        let provider_map = ProviderMap::new();
        routing.set_providers(provider_map).await;

        assert_eq!(
            routing.matrix_name(), "balanced",
            "should use the balanced matrix by default"
        );

        // Verify agents are still accessible via registry after wiring.
        let reg = registry.read().await;
        assert_eq!(
            reg.list().len(), 6,
            "registry should still have 6 foundation agents after wiring"
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
        let _tool = DelegateTool::new(
            Arc::new(NopRunner),
            Arc::new(tokio::sync::RwLock::new(reg)),
        );
        println!("[smoke] DelegateTool wiring verified for Phase 4");
        // Optionally override config (DelegateTool::new uses default):
        let _ = DelegateToolConfig::default();
    }
}
