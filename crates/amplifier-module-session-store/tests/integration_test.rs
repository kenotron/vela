//! End-to-end crash-resume integration test for the session store.
//!
//! Exercises the full persist → drop → resume flow:
//! - Phase A: run an orchestrator, persist a session to disk, then drop the orchestrator.
//! - Phase B: create a fresh orchestrator (simulating a process restart), attach the same
//!   on-disk store, and call `SubagentRunner::resume` to continue the prior session.
//! - Final check: the store contains both the original and the new user turns.

#![allow(dead_code)]

mod common {
    use std::collections::HashMap;
    use std::future::Future;
    use std::pin::Pin;
    use std::sync::Mutex;

    use async_trait::async_trait;
    use serde_json::Value;

    // ---- Orchestrator trait imports ----
    use amplifier_module_orchestrator_loop_streaming::{
        ContentBlock as OrcContentBlock, Provider as OrcProvider, ToolCall as OrcToolCall,
    };

    // ---- amplifier-core trait imports ----
    use amplifier_core::errors::ProviderError;
    use amplifier_core::messages::{
        ChatRequest, ChatResponse, ContentBlock as CoreContentBlock,
        ToolCall as CoreToolCall,
    };
    use amplifier_core::models::{ModelInfo, ProviderInfo};
    use amplifier_core::traits::Provider as CoreProvider;

    /// A scripted LLM provider that returns pre-programmed replies in order.
    ///
    /// Implements both:
    /// - `amplifier_module_orchestrator_loop_streaming::Provider` (used by `LoopOrchestrator`)
    /// - `amplifier_core::traits::Provider` (standard Amplifier provider interface)
    pub struct ScriptedProvider {
        /// Queued replies consumed by the orchestrator's `complete()` call.
        replies: Mutex<Vec<String>>,
    }

    impl ScriptedProvider {
        pub fn new(replies: Vec<String>) -> Self {
            Self {
                replies: Mutex::new(replies),
            }
        }
    }

    // -------------------------------------------------------------------------
    // amplifier_core::traits::Provider — standard Amplifier provider interface
    // -------------------------------------------------------------------------

    impl CoreProvider for ScriptedProvider {
        fn name(&self) -> &str {
            "scripted"
        }

        fn get_info(&self) -> ProviderInfo {
            ProviderInfo {
                id: "scripted".to_string(),
                display_name: "scripted".to_string(),
                credential_env_vars: vec![],
                capabilities: vec![],
                defaults: HashMap::new(),
                config_fields: vec![],
            }
        }

        fn list_models(
            &self,
        ) -> Pin<Box<dyn Future<Output = Result<Vec<ModelInfo>, ProviderError>> + Send + '_>>
        {
            Box::pin(async { Ok(vec![]) })
        }

        fn complete(
            &self,
            _request: ChatRequest,
        ) -> Pin<Box<dyn Future<Output = Result<ChatResponse, ProviderError>> + Send + '_>>
        {
            // Peek at the first reply without consuming (the orchestrator path consumes).
            let text = self
                .replies
                .lock()
                .unwrap()
                .first()
                .cloned()
                .unwrap_or_default();
            Box::pin(async move {
                Ok(ChatResponse {
                    content: vec![CoreContentBlock::Text {
                        text,
                        visibility: None,
                        extensions: HashMap::new(),
                    }],
                    finish_reason: Some("end_turn".to_string()),
                    tool_calls: None,
                    usage: None,
                    degradation: None,
                    metadata: None,
                    extensions: HashMap::new(),
                })
            })
        }

        fn parse_tool_calls(&self, _response: &ChatResponse) -> Vec<CoreToolCall> {
            vec![]
        }
    }

    // -------------------------------------------------------------------------
    // amplifier_module_orchestrator_loop_streaming::Provider — used by LoopOrchestrator
    // -------------------------------------------------------------------------

    #[async_trait]
    impl OrcProvider for ScriptedProvider {
        /// Pops the next reply from the queue and returns it as a `Text` block.
        async fn complete(
            &self,
            _messages: Vec<Value>,
            _tools: Vec<Value>,
            _system_prompt: Option<String>,
        ) -> anyhow::Result<Vec<OrcContentBlock>> {
            let text = {
                let mut replies = self.replies.lock().unwrap();
                if replies.is_empty() {
                    "no more replies".to_string()
                } else {
                    replies.remove(0)
                }
            };
            Ok(vec![OrcContentBlock::Text { text }])
        }

        fn parse_tool_calls(&self, _response: &[OrcContentBlock]) -> Vec<OrcToolCall> {
            vec![]
        }
    }
}

// =============================================================================
// Crash-resume end-to-end test
// =============================================================================

#[tokio::test]
async fn crash_resume_end_to_end() {
    use std::sync::Arc;

    use amplifier_module_orchestrator_loop_streaming::{
        HookRegistry, LoopConfig, LoopOrchestrator, Provider as OrcProvider, SimpleContext,
    };
    use amplifier_module_session_store::file::FileSessionStore;
    use amplifier_module_session_store::format::{SessionEvent, SessionMetadata};
    use amplifier_module_session_store::SessionStore;
    use amplifier_module_tool_task::SubagentRunner;
    use chrono::Utc;
    use tempfile::TempDir;

    use common::ScriptedProvider;

    let tmp = TempDir::new().expect("tempdir");
    let store: Arc<FileSessionStore> =
        Arc::new(FileSessionStore::new_with_root(tmp.path().to_path_buf()));
    let session_id = "explorer-1";

    // =========================================================================
    // Phase A — run an orchestrator, persist the session, then DROP it
    // =========================================================================
    {
        let orch = LoopOrchestrator::new(LoopConfig::default());

        // Attach the store so the orchestrator persists events.
        orch.attach_store(
            Arc::clone(&store) as Arc<dyn SessionStore>,
            session_id.to_string(),
            "explorer".to_string(),
            None,
        );

        // Register a provider scripted to reply "found: a.rs, b.rs, c.rs".
        let provider = Arc::new(ScriptedProvider::new(vec![
            "found: a.rs, b.rs, c.rs".to_string(),
        ]));
        orch.register_provider("anthropic", provider as Arc<dyn OrcProvider>)
            .await;

        // Begin the session in the store (writes SessionStart event + index entry).
        let meta = SessionMetadata {
            session_id: session_id.to_string(),
            agent_name: "explorer".to_string(),
            parent_id: None,
            created: Utc::now().to_rfc3339(),
            status: "active".to_string(),
        };
        store.begin(session_id, meta).await.expect("begin");

        // Run one loop step with an empty context.
        let mut ctx = SimpleContext::new(vec![]);
        orch.execute(
            "list all Rust files".into(),
            &mut ctx,
            &HookRegistry::new(),
            |_| {},
        )
        .await
        .expect("execute phase A");

        // Finalise the session (writes SessionEnd + updates index status to "success").
        orch.finish_store("success").await.expect("finish_store");

        // `orch` is dropped here — simulating process exit.
    }

    // =========================================================================
    // Verify on-disk state after Phase A
    // =========================================================================

    let events_file = store.events_file(session_id);
    assert!(
        events_file.exists(),
        "events.jsonl should exist after Phase A"
    );

    let events_content =
        std::fs::read_to_string(&events_file).expect("read events.jsonl after Phase A");

    // Every line must parse as valid SessionEvent JSON.
    for (i, line) in events_content.lines().enumerate() {
        if line.trim().is_empty() {
            continue;
        }
        let _valid_json: serde_json::Value = serde_json::from_str(line).unwrap_or_else(|e| {
            panic!("Line {} of events.jsonl is not valid JSON: {}\n{}", i + 1, e, line)
        });
        let _valid_event: SessionEvent = serde_json::from_str(line).unwrap_or_else(|e| {
            panic!(
                "Line {} of events.jsonl is not a valid SessionEvent: {}\n{}",
                i + 1,
                e,
                line
            )
        });
    }

    // The transcript body must contain both the user prompt and the scripted reply.
    assert!(
        events_content.contains("list all Rust files"),
        "events.jsonl should contain the user prompt 'list all Rust files'"
    );
    assert!(
        events_content.contains("found: a.rs, b.rs, c.rs"),
        "events.jsonl should contain the scripted reply 'found: a.rs, b.rs, c.rs'"
    );

    // The index file must have exactly 1 line with status "success".
    let index_content =
        std::fs::read_to_string(store.index_file()).expect("read index.jsonl after Phase A");
    let index_lines: Vec<&str> = index_content
        .lines()
        .filter(|l| !l.trim().is_empty())
        .collect();
    assert_eq!(
        index_lines.len(),
        1,
        "index.jsonl should have exactly 1 line after Phase A; got: {:?}",
        index_lines
    );
    assert!(
        index_lines[0].contains("\"status\":\"success\""),
        "index line should contain '\"status\":\"success\"'; got: {}",
        index_lines[0]
    );

    // =========================================================================
    // Phase B — simulate process restart with a FRESH orchestrator
    // =========================================================================

    let orch2 = LoopOrchestrator::new(LoopConfig::default());

    // Use the SAME shared store (Arc::clone) — the store persists across "restarts".
    orch2.attach_store(
        Arc::clone(&store) as Arc<dyn SessionStore>,
        session_id.to_string(),
        "explorer".to_string(),
        None,
    );

    // Fresh provider scripted to reply "3 files".
    let provider2 = Arc::new(ScriptedProvider::new(vec!["3 files".to_string()]));
    orch2
        .register_provider("anthropic", provider2 as Arc<dyn OrcProvider>)
        .await;

    // Resume the existing session with a new instruction.
    let result = SubagentRunner::resume(&orch2, session_id, "now count them".into())
        .await
        .expect("SubagentRunner::resume should succeed");

    // The returned SpawnResult must carry the correct session_id.
    assert_eq!(
        result.session_id, session_id,
        "resume result session_id should match"
    );

    // The response must contain the scripted reply.
    assert!(
        result.response.contains("3 files"),
        "resume response should contain '3 files'; got: {}",
        result.response
    );

    // =========================================================================
    // Final verification — load all stored events and check both user turns
    // =========================================================================

    let all_events = store.load(session_id).await.expect("load all events");

    let user_turn_contents: Vec<&str> = all_events
        .iter()
        .filter_map(|e| {
            if let SessionEvent::Turn { role, content, .. } = e {
                if role == "user" {
                    Some(content.as_str())
                } else {
                    None
                }
            } else {
                None
            }
        })
        .collect();

    assert!(
        user_turn_contents.iter().any(|&t| t == "list all Rust files"),
        "store should still contain prior user turn 'list all Rust files'; \
         user turns found: {:?}",
        user_turn_contents
    );
    assert!(
        user_turn_contents.iter().any(|&t| t == "now count them"),
        "store should contain new user turn 'now count them' after resume; \
         user turns found: {:?}",
        user_turn_contents
    );
}
