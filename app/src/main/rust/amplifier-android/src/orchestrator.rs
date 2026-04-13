//! SimpleOrchestrator — drives the Anthropic tool-calling loop and fires JNI callbacks.
//!
//! ## Agent loop
//!
//! ```text
//! ┌─────────────┐         ┌──────────────────┐        ┌───────────────┐
//! │  Orchestrator│──call──▶│ AnthropicProvider │        │ Kotlin bridge │
//! │  run()       │◀──resp──│  complete()       │        │ executeTool() │
//! │              │         └──────────────────┘        │ onToken()     │
//! │  loop ≤10    │──tool_use?──────────────────────────▶│               │
//! │  steps       │◀─result─────────────────────────────│               │
//! └─────────────┘                                      └───────────────┘
//! ```
//!
//! On `stop_reason == "end_turn"`: emits the full assistant text via
//! `tokenCallback.onToken(text)` and returns it.
//!
//! On `stop_reason == "tool_use"`: extracts every `tool_use` block, invokes
//! `toolCallback.executeTool(name, inputJson)` for each one, assembles the
//! `tool_result` user message, and loops.

use jni::objects::{GlobalRef, JString, JValue};
use jni::JavaVM;
use log::{debug, warn};
use serde_json::{json, Value};
use std::sync::Arc;

use crate::provider::AnthropicProvider;

/// Maximum number of provider roundtrips before giving up.
const MAX_STEPS: usize = 10;

/// Drives the multi-turn agent loop and calls back into Kotlin for streaming
/// tokens and tool execution.
pub struct SimpleOrchestrator {
    /// Shared JVM handle — used to attach worker threads before JNI calls.
    jvm: Arc<JavaVM>,
    /// Global reference to the Kotlin `TokenCallback` object.
    /// Method: `onToken(text: String)`.
    token_cb: GlobalRef,
    /// Global reference to the Kotlin `ToolCallback` object.
    /// Method: `executeTool(name: String, inputJson: String): String`.
    tool_cb: GlobalRef,
}

impl SimpleOrchestrator {
    /// Create the orchestrator with JVM + pre-promoted global callback refs.
    pub fn new(jvm: Arc<JavaVM>, token_cb: GlobalRef, tool_cb: GlobalRef) -> Self {
        Self {
            jvm,
            token_cb,
            tool_cb,
        }
    }

    /// Run the full agent loop against `provider` starting from `messages`.
    ///
    /// Mutates `messages` in-place as assistant and tool-result turns are
    /// appended.  Returns the final assistant text, or an `"Error: …"` string
    /// on unrecoverable failure.
    pub async fn run(&self, provider: &AnthropicProvider, mut messages: Vec<Value>) -> String {
        for step in 0..MAX_STEPS {
            debug!("orchestrator: step {step}, messages={}", messages.len());

            // ── 1. Call the model ──────────────────────────────────────────
            let response = match provider.complete(messages.clone()).await {
                Ok(r) => r,
                Err(e) => {
                    warn!("orchestrator: provider error at step {step}: {e}");
                    return format!("Error: {e}");
                }
            };

            let stop_reason = response
                .get("stop_reason")
                .and_then(|v| v.as_str())
                .unwrap_or("end_turn");

            let content: Vec<Value> = response
                .get("content")
                .and_then(|v| v.as_array())
                .cloned()
                .unwrap_or_default();

            debug!("orchestrator: stop_reason={stop_reason} content_blocks={}", content.len());

            match stop_reason {
                // ── 2a. End of conversation ───────────────────────────────
                "end_turn" | "stop_sequence" => {
                    let text = extract_text(&content);
                    // Emit the full response as a single streaming "chunk" so
                    // the Kotlin side can display it progressively if desired.
                    if !text.is_empty() {
                        self.emit_token(&text);
                    }
                    return text;
                }

                // ── 2b. Model wants to call tools ─────────────────────────
                "tool_use" => {
                    // Append the assistant turn (may include preamble text and
                    // one or more tool_use blocks).
                    messages.push(json!({
                        "role": "assistant",
                        "content": content.clone(),
                    }));

                    // Emit any preamble text so the UI is not silent.
                    let preamble = extract_text(&content);
                    if !preamble.is_empty() {
                        self.emit_token(&preamble);
                    }

                    // Execute each tool and collect results.
                    let tool_results = self.execute_tools(&content);

                    if tool_results.is_empty() {
                        // No tool_use blocks despite the stop_reason — bail.
                        warn!("orchestrator: stop_reason=tool_use but no tool_use blocks found");
                        return "Error: model requested tool_use but no tool blocks were present"
                            .to_string();
                    }

                    // Anthropic expects tool results as a single user message
                    // whose content is an array of tool_result objects.
                    messages.push(json!({
                        "role": "user",
                        "content": tool_results,
                    }));
                }

                // ── 2c. Unexpected stop reason ────────────────────────────
                other => {
                    warn!("orchestrator: unexpected stop_reason={other}");
                    let text = extract_text(&content);
                    if !text.is_empty() {
                        return text;
                    }
                    return format!("Stopped unexpectedly (reason: {other})");
                }
            }
        }

        warn!("orchestrator: reached MAX_STEPS={MAX_STEPS} without end_turn");
        "Error: agent loop exceeded maximum steps without completing".to_string()
    }

    // ────────────────────────────── JNI helpers ───────────────────────────

    /// Invoke `toolCallback.executeTool(name, inputJson)` on the Kotlin side
    /// and return its result string.
    ///
    /// Attaches the calling thread to the JVM for the duration of the call.
    /// Returns an empty string on any JNI error so the loop can continue.
    fn call_tool(&self, name: &str, input_json: &str) -> String {
        let attach_result = self.jvm.attach_current_thread();
        let mut env = match attach_result {
            Ok(guard) => guard,
            Err(e) => {
                warn!("call_tool: failed to attach JVM thread: {e:?}");
                return String::new();
            }
        };

        // Build Java strings for the two arguments.
        let j_name = match env.new_string(name) {
            Ok(s) => s,
            Err(e) => {
                warn!("call_tool: failed to create j_name string: {e:?}");
                return String::new();
            }
        };
        let j_args = match env.new_string(input_json) {
            Ok(s) => s,
            Err(e) => {
                warn!("call_tool: failed to create j_args string: {e:?}");
                return String::new();
            }
        };

        // Call the Kotlin method.
        let call_result = env.call_method(
            &self.tool_cb,
            "executeTool",
            "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
            &[JValue::Object(&j_name), JValue::Object(&j_args)],
        );

        let jobject = match call_result {
            Ok(v) => match v.l() {
                Ok(o) => o,
                Err(e) => {
                    warn!("call_tool: failed to extract Object from JValue: {e:?}");
                    let _ = env.exception_clear();
                    return String::new();
                }
            },
            Err(e) => {
                warn!("call_tool: executeTool threw or failed: {e:?}");
                let _ = env.exception_clear();
                return String::new();
            }
        };

        if jobject.is_null() {
            warn!("call_tool: executeTool returned null for tool={name}");
            return String::new();
        }

        // Safe downcast: JObject → JString (jni 0.21 guarantees same repr).
        let jstr = JString::from(jobject);
        // Collect into owned String before jstr drops (lifetime constraint)
        let result: String = env.get_string(&jstr)
            .map(|s| s.into())
            .unwrap_or_else(|e| { warn!("call_tool: get_string failed: {e:?}"); String::new() });
        result
    }

    /// Invoke `tokenCallback.onToken(text)` on the Kotlin side.
    ///
    /// Failures are logged and silently swallowed — a missing token callback
    /// must not abort the agent loop.
    fn emit_token(&self, token: &str) {
        let attach_result = self.jvm.attach_current_thread();
        let mut env = match attach_result {
            Ok(guard) => guard,
            Err(e) => {
                warn!("emit_token: failed to attach JVM thread: {e:?}");
                return;
            }
        };

        let j_token = match env.new_string(token) {
            Ok(s) => s,
            Err(e) => {
                warn!("emit_token: failed to create token string: {e:?}");
                return;
            }
        };

        if let Err(e) = env.call_method(
            &self.token_cb,
            "onToken",
            "(Ljava/lang/String;)V",
            &[JValue::Object(&j_token)],
        ) {
            warn!("emit_token: onToken call failed: {e:?}");
            let _ = env.exception_clear();
        }
    }

    // ─────────────────────────── Tool execution ───────────────────────────

    /// Iterate over `content` blocks, execute every `tool_use` block via the
    /// Kotlin callback, and return an array of Anthropic `tool_result` objects.
    fn execute_tools(&self, content: &[Value]) -> Vec<Value> {
        content
            .iter()
            .filter(|block| {
                block
                    .get("type")
                    .and_then(|t| t.as_str())
                    .map(|t| t == "tool_use")
                    .unwrap_or(false)
            })
            .map(|block| {
                let tool_id = block
                    .get("id")
                    .and_then(|v| v.as_str())
                    .unwrap_or("")
                    .to_string();
                let tool_name = block
                    .get("name")
                    .and_then(|v| v.as_str())
                    .unwrap_or("")
                    .to_string();
                let tool_input = block
                    .get("input")
                    .cloned()
                    .unwrap_or_else(|| json!({}));

                let input_json = tool_input.to_string();
                debug!("orchestrator: invoking tool={tool_name} id={tool_id}");

                let result = self.call_tool(&tool_name, &input_json);

                debug!(
                    "orchestrator: tool={tool_name} result_len={}",
                    result.len()
                );

                json!({
                    "type": "tool_result",
                    "tool_use_id": tool_id,
                    "content": result,
                })
            })
            .collect()
    }
}

// ─────────────────────────── Pure helpers ─────────────────────────────────

/// Concatenate all `text` blocks in an Anthropic content array.
fn extract_text(content: &[Value]) -> String {
    content
        .iter()
        .filter_map(|block| {
            if block.get("type").and_then(|t| t.as_str()) == Some("text") {
                block.get("text").and_then(|t| t.as_str()).map(str::to_owned)
            } else {
                None
            }
        })
        .collect::<Vec<_>>()
        .join("")
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn extract_text_joins_all_text_blocks() {
        let content = vec![
            json!({"type": "text", "text": "Hello"}),
            json!({"type": "tool_use", "id": "x", "name": "foo", "input": {}}),
            json!({"type": "text", "text": " world"}),
        ];
        assert_eq!(extract_text(&content), "Hello world");
    }

    #[test]
    fn extract_text_empty_on_no_text_blocks() {
        let content = vec![json!({"type": "tool_use", "id": "x", "name": "foo", "input": {}})];
        assert_eq!(extract_text(&content), "");
    }

    #[test]
    fn extract_text_empty_content() {
        assert_eq!(extract_text(&[]), "");
    }
}
