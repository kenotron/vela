//! SimpleOrchestrator — implements the amplifier-core [`Orchestrator`] trait.
//!
//! Drives the multi-turn agent loop:
//!
//! ```text
//! ┌─────────────────────────┐
//! │ execute(prompt, ctx, …) │
//! └────────────┬────────────┘
//!              │ add_message(user)
//!              ▼
//!   ┌──────────────────────┐        ┌──────────────────────┐
//!   │  context.get_msgs()  │──────▶ │  provider.complete() │
//!   └──────────────────────┘        └──────────┬───────────┘
//!                                              │
//!              ┌───────────────────────────────┘
//!              │ stop_reason?
//!              ├─ "end_turn"  → emit_token + return Ok(text)
//!              └─ "tool_use"  → run tools → add results → loop
//! ```
//!
//! ## Vela-specific addition
//!
//! The amplifier-core `Orchestrator` trait returns the full response as a
//! `String`.  Vela's Kotlin UI displays text as it arrives, so `execute()`
//! also calls `emit_token()` whenever text is ready (preamble, final text).
//! This is an extension on top of the trait; it does not change the contract.

use std::collections::HashMap;
use std::future::Future;
use std::pin::Pin;
use std::sync::Arc;

use amplifier_core::errors::AmplifierError;
use amplifier_core::messages::{
    ChatRequest, ContentBlock, Message, MessageContent, Role, ToolSpec,
};
use amplifier_core::traits::{ContextManager, Orchestrator, Provider, Tool};
use jni::objects::{GlobalRef, JValue};
use jni::JavaVM;
use log::{debug, warn};
use serde_json::{json, Value};

/// Maximum provider roundtrips before aborting to avoid infinite loops.
const MAX_STEPS: usize = 10;

/// Agent-loop orchestrator with a Vela-specific token-streaming callback.
///
/// The `jvm` and `token_cb` fields are used only by `emit_token()`;
/// they are NOT part of the `Orchestrator` trait contract.
pub struct SimpleOrchestrator {
    jvm: Arc<JavaVM>,
    /// Global reference to `TokenCallback` — called after each text segment.
    token_cb: Arc<GlobalRef>,
        /// Global reference to `ProviderRequestCallback` — called before each LLM call.
        provider_request_cb: Arc<GlobalRef>,
        /// Global reference to `ServerToolCallback` — notifies Kotlin when a
        /// server tool (e.g. web_search) runs, for UI display only.
        server_tool_cb: Arc<GlobalRef>,
    }

    impl SimpleOrchestrator {
        pub fn new(
            jvm: Arc<JavaVM>,
            token_cb: Arc<GlobalRef>,
            provider_request_cb: Arc<GlobalRef>,
            server_tool_cb: Arc<GlobalRef>,
        ) -> Self {
            Self { jvm, token_cb, provider_request_cb, server_tool_cb }
        }

        /// Notify Kotlin that a server-side tool ran (UI display only — no execution).
        fn emit_server_tool(&self, name: &str, args_json: &str) {
            let Ok(mut env) = self.jvm.attach_current_thread() else {
                warn!("emit_server_tool: failed to attach JVM thread");
                return;
            };
            let Ok(j_name) = env.new_string(name) else { return; };
            let Ok(j_args) = env.new_string(args_json) else { return; };
            if let Err(e) = env.call_method(
                self.server_tool_cb.as_ref(),
                "onServerTool",
                "(Ljava/lang/String;Ljava/lang/String;)V",
                &[JValue::Object(&j_name), JValue::Object(&j_args)],
            ) {
                warn!("emit_server_tool: callback failed: {e:?}");
                let _ = env.exception_clear();
            }
        }

        /// Call `ProviderRequestCallback.onProviderRequest()` on the Kotlin side.
        /// Returns `Some(injection)` if hooks want to inject ephemeral context,
        /// or `None` for no injection. Failures are swallowed so they cannot abort the loop.
        fn call_provider_request(&self) -> Option<String> {
            let mut env = self.jvm.attach_current_thread().ok()?;
            let result = env.call_method(
                self.provider_request_cb.as_ref(),
                "onProviderRequest",
                "()Ljava/lang/String;",
                &[],
            );
            let jvalue = match result {
                Ok(v) => v,
                Err(e) => {
                    warn!("call_provider_request: threw: {e:?}");
                    let _ = env.exception_clear();
                    return None;
                }
            };
            let jobject = jvalue.l().ok()?;
            if jobject.is_null() { return None; }
            let jstr = jni::objects::JString::from(jobject);
            env.get_string(&jstr)
                .map(|s| String::from(s))
                .ok()
                .filter(|s| !s.is_empty())
        }

    // ─────────────────────── Token streaming (Vela-specific) ─────────────────

    /// Call `TokenCallback.onToken(text)` on the Kotlin side.
    ///
    /// Failures are logged and silently swallowed — a broken callback must
    /// not abort the agent loop.
    fn emit_token(&self, token: &str) {
        let Ok(mut env) = self.jvm.attach_current_thread() else {
            warn!("emit_token: failed to attach JVM thread");
            return;
        };
        let Ok(j_token) = env.new_string(token) else {
            warn!("emit_token: failed to create Java string");
            return;
        };
        if let Err(e) = env.call_method(
            self.token_cb.as_ref(),
            "onToken",
            "(Ljava/lang/String;)V",
            &[JValue::Object(&j_token)],
        ) {
            warn!("emit_token: onToken call failed: {e:?}");
            let _ = env.exception_clear();
        }
    }

    // ────────────────────────── Message construction ──────────────────────────

    /// Convert a [`ChatResponse`] content into a serialised assistant `Message`
    /// ready to store in the [`ContextManager`].
    fn response_to_context_message(content: &[ContentBlock]) -> Value {
        let blocks: Vec<Value> = content
            .iter()
            .map(|block| {
                serde_json::to_value(block).unwrap_or(Value::Null)
            })
            .filter(|v| !v.is_null())
            .collect();

        json!({
            "role": "assistant",
            "content": blocks,
        })
    }

    /// Build the `ChatRequest` from the current context messages and the
    /// tools that are available for this session.
    ///
    /// Messages are deserialised from JSON (`Vec<Value>`) into typed
    /// [`Message`] structs.  Entries that fail to deserialise are skipped
    /// with a warning rather than aborting the loop.
    fn build_chat_request(
        context_messages: Vec<Value>,
        tool_specs: &[ToolSpec],
        model: Option<&str>,
    ) -> ChatRequest {
        let messages: Vec<Message> = context_messages
            .into_iter()
            .filter_map(|v| {
                serde_json::from_value::<Message>(v.clone())
                    .map_err(|e| warn!("orchestrator: failed to deserialise context message: {e}"))
                    .ok()
                    // Rescue: amplifier-core ContentBlock has no "document" variant, so messages
                    // with PDF/file blocks fail full deserialisation.  Extract role + serialise
                    // the raw content array as a JSON string — provider detects and passes through.
                    .or_else(|| {
                        let role_str = v.get("role").and_then(|r| r.as_str())?;
                        let role = if role_str == "assistant" { Role::Assistant } else { Role::User };
                        let content = v.get("content")?;
                        let content_str = serde_json::to_string(content).ok()?;
                        warn!("orchestrator: rescued message with raw content ({} bytes)", content_str.len());
                        Some(Message {
                            role,
                            content: MessageContent::Text(content_str),
                            name: None,
                            tool_call_id: None,
                            metadata: None,
                            extensions: HashMap::new(),
                        })
                    })
            })
            .collect();

        ChatRequest {
            messages,
            tools: if tool_specs.is_empty() {
                None
            } else {
                Some(tool_specs.to_vec())
            },
            model: model.map(str::to_string),
            response_format: None,
            temperature: None,
            top_p: None,
            max_output_tokens: None,
            conversation_id: None,
            stream: None,
            metadata: None,
            tool_choice: None,
            stop: None,
            reasoning_effort: None,
            timeout: None,
            extensions: HashMap::new(),
        }
    }

    /// Extract all text from `ContentBlock::Text` blocks and concatenate.
    fn extract_text(content: &[ContentBlock]) -> String {
        content
            .iter()
            .filter_map(|block| {
                if let ContentBlock::Text { text, .. } = block {
                    Some(text.as_str())
                } else {
                    None
                }
            })
            .collect::<Vec<_>>()
            .join("")
    }
}

// ──────────────────────────── Orchestrator impl ───────────────────────────────

impl Orchestrator for SimpleOrchestrator {
    /// Run the agent loop for a single user prompt.
    ///
    /// 1. Adds the prompt as a user message to `context`.
    /// 2. Calls `provider.complete()` in a loop (max [`MAX_STEPS`] iterations).
    /// 3. On `end_turn`: emits the text via `emit_token()` and returns.
    /// 4. On `tool_use`: dispatches tools via the `tools` map, adds
    ///    results to `context`, and continues.
    fn execute(
        &self,
        prompt: String,
        context: Arc<dyn ContextManager>,
        providers: HashMap<String, Arc<dyn Provider>>,
        tools: HashMap<String, Arc<dyn Tool>>,
        hooks: Value,          // content blocks array when user sent images/docs, else Null
        _coordinator: Value,
    ) -> Pin<Box<dyn Future<Output = Result<String, AmplifierError>> + Send + '_>> {
        Box::pin(async move {
            // ── Pick provider ──────────────────────────────────────────────────
            // Prefer a provider named "anthropic", otherwise take the first one.
            let provider = providers
                .get("anthropic")
                .or_else(|| providers.values().next())
                .ok_or_else(|| AmplifierError::Session(
                    amplifier_core::errors::SessionError::NotInitialized,
                ))?;

            // ── Collect tool specs ─────────────────────────────────────────────
            let tool_specs: Vec<ToolSpec> = tools.values().map(|t| t.get_spec()).collect();

            // ── Add user message to context ────────────────────────────────────
            // When hooks is a JSON array it contains Anthropic content blocks (image/document/text).
            // Fall back to a plain text string when no rich content was provided.
            let user_content = if hooks.is_array() { hooks } else { json!(prompt) };
            let user_msg = json!({"role": "user", "content": user_content});
            context
                .add_message(user_msg)
                .await
                .map_err(|e| AmplifierError::Session(
                    amplifier_core::errors::SessionError::Other {
                        message: format!("context.add_message failed: {e}"),
                    },
                ))?;

            // ── Agent loop ─────────────────────────────────────────────────────
            for step in 0..MAX_STEPS {
                debug!("orchestrator: step {step}");

                // 1. Retrieve messages from context
                let context_messages = context
                    .get_messages_for_request(None, None)
                    .await
                    .map_err(|e| AmplifierError::Session(
                        amplifier_core::errors::SessionError::Other {
                            message: format!("get_messages failed: {e}"),
                        },
                    ))?;

                // 2. Inject ephemeral context from Kotlin hooks (PROVIDER_REQUEST)
                    let mut messages_for_request = context_messages;
                    if let Some(injection) = self.call_provider_request() {
                        debug!("orchestrator: injecting {} bytes from hooks", injection.len());
                        messages_for_request.push(serde_json::json!({
                            "role":    "user",
                            "content": injection,
                        }));
                    }

                    // 3. Build and send request
                    let request = Self::build_chat_request(messages_for_request, &tool_specs, None);
                let response = provider
                    .complete(request)
                    .await
                    .map_err(|e| AmplifierError::Provider(e))?;

                let stop_reason = response
                    .finish_reason
                    .as_deref()
                    .unwrap_or("end_turn");

                debug!(
                    "orchestrator: step={step} stop_reason={stop_reason} blocks={}",
                    response.content.len()
                );

                match stop_reason {
                    // ── Turn complete ─────────────────────────────────────────
                    "end_turn" | "stop_sequence" | "stop" => {
                            // Notify Kotlin of any server tool calls (e.g. web_search)
                            // so the UI can show them. These appear as ToolCall blocks
                            // in end_turn responses (Anthropic executed them server-side).
                            for block in &response.content {
                                if let amplifier_core::messages::ContentBlock::ToolCall {
                                    name, input, ..
                                } = block {
                                    let args = serde_json::to_string(input).unwrap_or_default();
                                    self.emit_server_tool(name, &args);
                                }
                            }
                            let text = Self::extract_text(&response.content);
                            if !text.is_empty() {
                                self.emit_token(&text);
                            }
                            return Ok(text);
                        }

                    // ── Tool calls ────────────────────────────────────────────
                    "tool_use" | "tool_calls" => {
                        // Emit any preamble text so the UI is not silent.
                        let preamble = Self::extract_text(&response.content);
                        if !preamble.is_empty() {
                            self.emit_token(&preamble);
                        }

                        // Add the assistant turn to context BEFORE calling tools.
                        let asst_msg = Self::response_to_context_message(&response.content);
                        context
                            .add_message(asst_msg)
                            .await
                            .map_err(|e| AmplifierError::Session(
                                amplifier_core::errors::SessionError::Other {
                                    message: format!("add assistant message failed: {e}"),
                                },
                            ))?;

                        // Extract tool calls and execute each.
                        let tool_calls = provider.parse_tool_calls(&response);
                        if tool_calls.is_empty() {
                            warn!("orchestrator: stop_reason=tool_use but no tool calls found");
                            return Err(AmplifierError::Session(
                                amplifier_core::errors::SessionError::Other {
                                    message: "model requested tool_use but no tool calls present"
                                        .to_string(),
                                },
                            ));
                        }

                        // Execute each tool and collect results.
                        let mut tool_result_blocks: Vec<Value> = Vec::new();
                        for call in &tool_calls {
                            debug!("orchestrator: executing tool={} id={}", call.name, call.id);
                            let input_value = serde_json::to_value(&call.arguments)
                                .unwrap_or(Value::Null);

                            let result_output = if let Some(tool) = tools.get(&call.name) {
                                match tool.execute(input_value).await {
                                    Ok(r) => r.output.unwrap_or(json!("")),
                                    Err(e) => {
                                        warn!("orchestrator: tool {} failed: {e}", call.name);
                                        json!(format!("Error: {e}"))
                                    }
                                }
                            } else {
                                // No local handler — check for a server-provided result.
                                // Anthropic server tools (web_search_20250305) execute on
                                // Anthropic's backend and return a ToolResult content block
                                // in the same response, matched by tool_call_id.
                                let server_result = response.content.iter().find_map(|blk| {
                                    if let amplifier_core::ContentBlock::ToolResult {
                                        tool_call_id, output, ..
                                    } = blk {
                                        if tool_call_id == &call.id { Some(output.clone()) }
                                        else { None }
                                    } else { None }
                                });
                                if let Some(r) = server_result {
                                    debug!("orchestrator: using server result for '{}'", call.name);
                                    r
                                } else {
                                    warn!("orchestrator: no handler or server result for '{}'", call.name);
                                    json!("") // skip gracefully — don't inject an error
                                }
                            };

                            // Store result in amplifier-core format (tool_call_id, output).
                            // The provider will convert to Anthropic wire format (tool_use_id)
                            // when building the next API request.
                            tool_result_blocks.push(json!({
                                "type":         "tool_result",
                                "tool_call_id": call.id,
                                "output":       result_output,
                            }));
                        }

                        // Add tool results as a user message.
                        context
                            .add_message(json!({
                                "role":    "user",
                                "content": tool_result_blocks,
                            }))
                            .await
                            .map_err(|e| AmplifierError::Session(
                                amplifier_core::errors::SessionError::Other {
                                    message: format!("add tool results failed: {e}"),
                                },
                            ))?;
                    }

                    other => {
                        warn!("orchestrator: unexpected stop_reason={other}");
                        let text = Self::extract_text(&response.content);
                        if !text.is_empty() {
                            self.emit_token(&text);
                            return Ok(text);
                        }
                        return Err(AmplifierError::Session(
                            amplifier_core::errors::SessionError::Other {
                                message: format!("unexpected stop_reason: {other}"),
                            },
                        ));
                    }
                }
            }

            warn!("orchestrator: reached MAX_STEPS={MAX_STEPS}");
            Err(AmplifierError::Session(
                amplifier_core::errors::SessionError::Other {
                    message: "agent loop exceeded maximum steps without completing".to_string(),
                },
            ))
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn extract_text_joins_text_blocks() {
        use amplifier_core::messages::ContentBlock;
        let content = vec![
            ContentBlock::Text {
                text: "Hello ".to_string(),
                visibility: None,
                extensions: HashMap::new(),
            },
            ContentBlock::ToolCall {
                id: "x".to_string(),
                name: "foo".to_string(),
                input: HashMap::new(),
                visibility: None,
                extensions: HashMap::new(),
            },
            ContentBlock::Text {
                text: "world".to_string(),
                visibility: None,
                extensions: HashMap::new(),
            },
        ];
        assert_eq!(SimpleOrchestrator::extract_text(&content), "Hello world");
    }

    #[test]
    fn extract_text_empty_on_no_text_blocks() {
        use amplifier_core::messages::ContentBlock;
        let content = vec![ContentBlock::ToolCall {
            id: "x".to_string(),
            name: "foo".to_string(),
            input: HashMap::new(),
            visibility: None,
            extensions: HashMap::new(),
        }];
        assert_eq!(SimpleOrchestrator::extract_text(&content), "");
    }
}
