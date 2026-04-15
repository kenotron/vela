//! AnthropicProvider — Anthropic Messages API implementation of the
//! amplifier-core [`Provider`] trait.
//!
//! ## Conversions
//!
//! The Anthropic API uses slightly different field names from the
//! amplifier-core canonical format:
//!
//! | Amplifier-core               | Anthropic wire          |
//! |------------------------------|-------------------------|
//! | `ContentBlock::ToolCall`     | `{"type":"tool_use",…}` |
//! | `tool_call_id`               | `tool_use_id`           |
//! | `output`                     | `content`               |
//! | `finish_reason`              | `stop_reason`           |
//!
//! All conversions are handled inside `complete()` so callers see only
//! the amplifier-core types.

use std::collections::HashMap;
use std::future::Future;
use std::pin::Pin;

use amplifier_core::{
    ChatRequest, ChatResponse, ContentBlock, Message, MessageContent, ModelInfo, ProviderError,
    ProviderInfo, Role, ToolCall, Usage,
};
use amplifier_core::traits::Provider;
use log::{debug, warn};
use reqwest::Client;
use serde_json::{json, Value};

const ANTHROPIC_API_URL: &str = "https://api.anthropic.com/v1/messages";
const ANTHROPIC_VERSION: &str = "2023-06-01";
const DEFAULT_MAX_TOKENS: u64 = 8192;

/// Calls the Anthropic Messages API implementing the amplifier-core [`Provider`] trait.
///
/// Constructed once per JNI call; the model, API key, and system prompt are
/// provider-level configuration rather than per-request parameters.
pub struct AnthropicProvider {
    api_key: String,
    model: String,
    system_prompt: String,
    client: Client,
}

impl AnthropicProvider {
    /// Create a provider.
    ///
    /// # Arguments
    /// * `api_key`       – Anthropic API key (`sk-ant-…`).
    /// * `model`         – Model identifier, e.g. `"claude-sonnet-4-6"`.
    /// * `system_prompt` – Optional system prompt. Empty string means no system prompt.
    pub fn new(api_key: String, model: String, system_prompt: String) -> Self {
        Self {
            api_key,
            model,
            system_prompt,
            client: Client::new(),
        }
    }

    // ─────────────────────────────── Message conversion ───────────────────────

    /// Convert an amplifier-core [`Message`] to Anthropic wire-format JSON.
    ///
    /// Key conversions:
    /// - `ContentBlock::ToolCall` → `{"type":"tool_use", …}`
    /// - `ContentBlock::ToolResult` → `{"type":"tool_result", "tool_use_id":…, "content":…}`
    fn message_to_anthropic(msg: &Message) -> Value {
        let role = match msg.role {
            Role::User | Role::Tool | Role::Function => "user",
            Role::Assistant => "assistant",
            Role::System | Role::Developer => "system",
        };

        let content = match &msg.content {
            MessageContent::Text(text) => json!(text),
            MessageContent::Blocks(blocks) => {
                let arr: Vec<Value> = blocks
                    .iter()
                    .map(|block| Self::content_block_to_anthropic(block))
                    .filter(|v| !v.is_null())
                    .collect();
                json!(arr)
            }
        };

        json!({ "role": role, "content": content })
    }

    /// Convert a single amplifier-core [`ContentBlock`] to Anthropic wire format.
    fn content_block_to_anthropic(block: &ContentBlock) -> Value {
        match block {
            ContentBlock::Text { text, .. } => {
                json!({ "type": "text", "text": text })
            }
            ContentBlock::ToolCall { id, name, input, .. } => {
                // Anthropic uses "tool_use" not "tool_call"
                json!({
                    "type": "tool_use",
                    "id": id,
                    "name": name,
                    "input": input,
                })
            }
            ContentBlock::ToolResult { tool_call_id, output, .. } => {
                // Anthropic uses "tool_use_id" not "tool_call_id"
                // and "content" not "output"
                json!({
                    "type": "tool_result",
                    "tool_use_id": tool_call_id,
                    "content": output,
                })
            }
            ContentBlock::Thinking { thinking, .. } => {
                json!({ "type": "thinking", "thinking": thinking })
            }
            // Silently drop unsupported block types
            _ => Value::Null,
        }
    }

    /// Parse an Anthropic content array into amplifier-core [`ContentBlock`]s.
    fn parse_content_blocks(raw_content: &[Value]) -> Vec<ContentBlock> {
        raw_content
            .iter()
            .filter_map(|block| {
                let block_type = block.get("type").and_then(|t| t.as_str())?;
                match block_type {
                    "text" => {
                        let text = block
                            .get("text")
                            .and_then(|t| t.as_str())
                            .unwrap_or("")
                            .to_string();
                        Some(ContentBlock::Text {
                            text,
                            visibility: None,
                            extensions: HashMap::new(),
                        })
                    }
                    "tool_use" => {
                        let id = block
                            .get("id")
                            .and_then(|v| v.as_str())
                            .unwrap_or("")
                            .to_string();
                        let name = block
                            .get("name")
                            .and_then(|v| v.as_str())
                            .unwrap_or("")
                            .to_string();
                        // Convert input JSON object → HashMap<String, Value>
                        let input: HashMap<String, Value> = block
                            .get("input")
                            .and_then(|v| v.as_object())
                            .map(|obj| {
                                obj.iter()
                                    .map(|(k, v)| (k.clone(), v.clone()))
                                    .collect()
                            })
                            .unwrap_or_default();
                        Some(ContentBlock::ToolCall {
                            id,
                            name,
                            input,
                            visibility: None,
                            extensions: HashMap::new(),
                        })
                    }
                    "thinking" => {
                        let thinking = block
                            .get("thinking")
                            .and_then(|t| t.as_str())
                            .unwrap_or("")
                            .to_string();
                        Some(ContentBlock::Thinking {
                            thinking,
                            signature: None,
                            visibility: None,
                            content: None,
                            extensions: HashMap::new(),
                        })
                    }
                    _ => None,
                }
            })
            .collect()
    }

    /// Convert a [`ChatRequest`] tools list to Anthropic tool format.
    fn build_anthropic_tools(tool_specs: &[amplifier_core::ToolSpec]) -> Vec<Value> {
        tool_specs
            .iter()
            .map(|spec| {
                let input_schema: Value = serde_json::to_value(&spec.parameters)
                    .unwrap_or_else(|_| {
                        json!({"type": "object", "properties": {}})
                    });
                json!({
                    "name": spec.name,
                    "description": spec.description,
                    "input_schema": input_schema,
                })
            })
            .collect()
    }

    /// Call `POST https://api.anthropic.com/v1/messages` and return the raw
    /// response JSON, or a [`ProviderError`] on failure.
    async fn call_api(&self, body: Value) -> Result<Value, ProviderError> {
        debug!("AnthropicProvider: calling API model={}", self.model);

        let response = self
            .client
            .post(ANTHROPIC_API_URL)
            .header("x-api-key", &self.api_key)
            .header("anthropic-version", ANTHROPIC_VERSION)
            .header("content-type", "application/json")
            .json(&body)
            .send()
            .await
            .map_err(|e| ProviderError::Unavailable {
                message: e.to_string(),
                provider: Some("anthropic".to_string()),
                model: None,
                retry_after: None,
                status_code: None,
                delay_multiplier: None,
            })?;

        let status = response.status();
        let raw: Value = response.json().await.map_err(|e| ProviderError::Other {
            message: format!("failed to parse Anthropic response: {e}"),
            provider: Some("anthropic".to_string()),
            model: None,
            retry_after: None,
            status_code: None,
            retryable: false,
            delay_multiplier: None,
        })?;

        if !status.is_success() {
            let msg = raw
                .get("error")
                .and_then(|e| e.get("message"))
                .and_then(|m| m.as_str())
                .unwrap_or("unknown error");
            warn!("AnthropicProvider: API error status={status} msg={msg}");
            return Err(ProviderError::Other {
                message: format!("Anthropic API error {status}: {msg}"),
                provider: Some("anthropic".to_string()),
                model: None,
                retry_after: None,
                status_code: Some(status.as_u16()),
                retryable: status.is_server_error(),
                delay_multiplier: None,
            });
        }

        Ok(raw)
    }
}

// ──────────────────────────── Provider trait impl ────────────────────────────

impl Provider for AnthropicProvider {
    fn name(&self) -> &str {
        "anthropic"
    }

    fn get_info(&self) -> ProviderInfo {
        ProviderInfo {
            id: "anthropic".to_string(),
            display_name: "Anthropic Claude".to_string(),
            credential_env_vars: vec!["ANTHROPIC_API_KEY".to_string()],
            capabilities: vec!["tools".to_string(), "streaming".to_string()],
            defaults: HashMap::new(),
            config_fields: vec![],
        }
    }

    fn list_models(
        &self,
    ) -> Pin<Box<dyn Future<Output = Result<Vec<ModelInfo>, ProviderError>> + Send + '_>> {
        Box::pin(async {
            Ok(vec![
                ModelInfo {
                    id: "claude-sonnet-4-6".to_string(),
                    display_name: "Claude Sonnet 4".to_string(),
                    context_window: 200_000,
                    max_output_tokens: 16_384,
                    capabilities: vec!["tools".to_string(), "vision".to_string()],
                    defaults: HashMap::new(),
                },
                ModelInfo {
                    id: "claude-haiku-4-5".to_string(),
                    display_name: "Claude Haiku 4".to_string(),
                    context_window: 200_000,
                    max_output_tokens: 8_192,
                    capabilities: vec!["tools".to_string()],
                    defaults: HashMap::new(),
                },
            ])
        })
    }

    fn complete(
        &self,
        request: ChatRequest,
    ) -> Pin<Box<dyn Future<Output = Result<ChatResponse, ProviderError>> + Send + '_>> {
        Box::pin(async move {
            // ── Build Anthropic API request body ──────────────────────────────
            let anthropic_messages: Vec<Value> = request
                .messages
                .iter()
                // Skip system-role messages — they go in the top-level "system" field
                .filter(|m| !matches!(m.role, Role::System | Role::Developer))
                .map(Self::message_to_anthropic)
                .collect();

            let mut body = json!({
                "model":      self.model,
                "max_tokens": DEFAULT_MAX_TOKENS,
                "messages":   anthropic_messages,
            });

            // System prompt (provider-level config)
            if !self.system_prompt.is_empty() {
                body["system"] = json!(self.system_prompt);
            }

            // Tools (from the request)
            if let Some(specs) = &request.tools {
                if !specs.is_empty() {
                    body["tools"] = json!(Self::build_anthropic_tools(specs));
                }
            }

            // ── Call the API ──────────────────────────────────────────────────
            let raw = self.call_api(body).await?;

            debug!(
                "AnthropicProvider: response stop_reason={:?}",
                raw.get("stop_reason")
            );

            // ── Parse the response ────────────────────────────────────────────
            let content_blocks = raw
                .get("content")
                .and_then(|c| c.as_array())
                .map(|arr| Self::parse_content_blocks(arr))
                .unwrap_or_default();

            let usage = raw.get("usage").map(|u| {
                let input = u.get("input_tokens").and_then(|v| v.as_i64()).unwrap_or(0);
                let output = u.get("output_tokens").and_then(|v| v.as_i64()).unwrap_or(0);
                Usage {
                    input_tokens: input,
                    output_tokens: output,
                    total_tokens: input + output,
                    reasoning_tokens: None,
                    cache_read_tokens: u
                        .get("cache_read_input_tokens")
                        .and_then(|v| v.as_i64()),
                    cache_write_tokens: u
                        .get("cache_creation_input_tokens")
                        .and_then(|v| v.as_i64()),
                    extensions: HashMap::new(),
                }
            });

            // Anthropic calls it "stop_reason"; amplifier-core calls it "finish_reason"
            let finish_reason = raw
                .get("stop_reason")
                .and_then(|v| v.as_str())
                .map(|s| s.to_string());

            Ok(ChatResponse {
                content: content_blocks,
                tool_calls: None, // populated by parse_tool_calls()
                usage,
                degradation: None,
                finish_reason,
                metadata: None,
                extensions: HashMap::new(),
            })
        })
    }

    /// Extract tool calls from a [`ChatResponse`] content block array.
    fn parse_tool_calls(&self, response: &ChatResponse) -> Vec<ToolCall> {
        response
            .content
            .iter()
            .filter_map(|block| {
                if let ContentBlock::ToolCall { id, name, input, .. } = block {
                    Some(ToolCall {
                        id: id.clone(),
                        name: name.clone(),
                        arguments: input.clone(),
                        extensions: HashMap::new(),
                    })
                } else {
                    None
                }
            })
            .collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn message_to_anthropic_simple_text() {
        let msg = Message {
            role: Role::User,
            content: MessageContent::Text("Hello".to_string()),
            name: None,
            tool_call_id: None,
            metadata: None,
            extensions: HashMap::new(),
        };
        let v = AnthropicProvider::message_to_anthropic(&msg);
        assert_eq!(v["role"], "user");
        assert_eq!(v["content"], "Hello");
    }

    #[test]
    fn message_to_anthropic_tool_call_uses_tool_use_type() {
        let mut input = HashMap::new();
        input.insert("query".to_string(), json!("test"));
        let msg = Message {
            role: Role::Assistant,
            content: MessageContent::Blocks(vec![ContentBlock::ToolCall {
                id: "call_1".to_string(),
                name: "search".to_string(),
                input,
                visibility: None,
                extensions: HashMap::new(),
            }]),
            name: None,
            tool_call_id: None,
            metadata: None,
            extensions: HashMap::new(),
        };
        let v = AnthropicProvider::message_to_anthropic(&msg);
        assert_eq!(v["content"][0]["type"], "tool_use");
        assert_eq!(v["content"][0]["id"], "call_1");
    }

    #[test]
    fn message_to_anthropic_tool_result_uses_tool_use_id() {
        let msg = Message {
            role: Role::User,
            content: MessageContent::Blocks(vec![ContentBlock::ToolResult {
                tool_call_id: "call_1".to_string(),
                output: json!("result text"),
                visibility: None,
                extensions: HashMap::new(),
            }]),
            name: None,
            tool_call_id: None,
            metadata: None,
            extensions: HashMap::new(),
        };
        let v = AnthropicProvider::message_to_anthropic(&msg);
        assert_eq!(v["content"][0]["type"], "tool_result");
        assert_eq!(v["content"][0]["tool_use_id"], "call_1");
        assert_eq!(v["content"][0]["content"], "result text");
    }

    #[test]
    fn parse_content_blocks_handles_tool_use() {
        let raw = vec![
            json!({"type": "text", "text": "Let me search."}),
            json!({"type": "tool_use", "id": "toolu_01", "name": "search", "input": {"query": "foo"}}),
        ];
        let blocks = AnthropicProvider::parse_content_blocks(&raw);
        assert_eq!(blocks.len(), 2);
        assert!(matches!(&blocks[0], ContentBlock::Text { text, .. } if text == "Let me search."));
        assert!(
            matches!(&blocks[1], ContentBlock::ToolCall { id, name, .. } if id == "toolu_01" && name == "search")
        );
    }

    #[test]
    fn build_anthropic_tools_converts_spec() {
        let mut params = HashMap::new();
        params.insert("type".to_string(), json!("object"));
        params.insert("properties".to_string(), json!({"q": {"type": "string"}}));
        let spec = amplifier_core::ToolSpec {
            name: "search".to_string(),
            parameters: params,
            description: Some("Search the web".to_string()),
            extensions: HashMap::new(),
        };
        let tools = AnthropicProvider::build_anthropic_tools(&[spec]);
        assert_eq!(tools[0]["name"], "search");
        assert_eq!(tools[0]["description"], "Search the web");
        assert!(tools[0]["input_schema"].is_object());
    }
}
