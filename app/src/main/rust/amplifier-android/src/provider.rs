    //! AnthropicProvider — thin async HTTP client for the Anthropic Messages API.
    //!
    //! Converts OpenAI-format tool schemas to Anthropic format on construction,
    //! then issues `POST /v1/messages` (non-streaming) and returns the raw JSON
    //! response for the orchestrator to interpret.

    use log::{debug, warn};
    use reqwest::Client;
    use serde_json::{json, Value};

    /// Calls the Anthropic Messages API with a given conversation.
    ///
    /// Constructed once per JNI call and used across the agent loop's steps.
    pub struct AnthropicProvider {
        api_key: String,
        model: String,
        /// Tools in **Anthropic** format (converted from OpenAI on construction).
        tools: Vec<Value>,
        system_prompt: String,
        client: Client,
    }

    impl AnthropicProvider {
        /// Create a provider.
        ///
        /// # Arguments
        /// * `api_key`       – Anthropic API key (`sk-ant-…`).
        /// * `model`         – Model identifier, e.g. `"claude-3-5-haiku-20241022"`.
        /// * `openai_tools`  – OpenAI-format tool schemas from Kotlin:
        ///   `[{"type":"function","function":{"name":…,"description":…,"parameters":{…}}}]`
        /// * `system_prompt` – Optional system prompt. When non-empty the Anthropic
        ///   request body will include a top-level `"system"` field.
        ///
        /// Tool schemas are converted to Anthropic format in place:
        ///   `{"name":…,"description":…,"input_schema":{…}}`
        pub fn new(api_key: String, model: String, openai_tools: Vec<Value>, system_prompt: String) -> Self {
            let tools = Self::convert_tools(openai_tools);
            Self {
                api_key,
                model,
                tools,
                system_prompt,
                client: Client::new(),
            }
        }

        /// Convert an OpenAI tool array to the Anthropic tool array format.
        ///
        /// OpenAI:   `{"type":"function","function":{"name":…,"description":…,"parameters":{…}}}`
        /// Anthropic: `{"name":…,"description":…,"input_schema":{…}}`
        ///
        /// Entries that are already missing the `"function"` wrapper are passed
        /// through unchanged so callers that already supply Anthropic format work.
        fn convert_tools(openai_tools: Vec<Value>) -> Vec<Value> {
            openai_tools
                .into_iter()
                .map(|t| {
                    if let Some(func) = t.get("function") {
                        let name = func.get("name").cloned().unwrap_or(Value::Null);
                        let description = func.get("description").cloned().unwrap_or(Value::Null);
                        let input_schema = func
                            .get("parameters")
                            .cloned()
                            .unwrap_or_else(|| json!({"type": "object", "properties": {}}));
                        json!({
                            "name": name,
                            "description": description,
                            "input_schema": input_schema,
                        })
                    } else {
                        // Already Anthropic-shaped or unknown — pass through.
                        t
                    }
                })
                .collect()
        }

        /// Call `POST https://api.anthropic.com/v1/messages` (non-streaming).
        ///
        /// # Arguments
        /// * `messages` – Full conversation array in Anthropic format.
        ///
        /// # Returns
        /// The raw Anthropic JSON response as a `serde_json::Value` so the
        /// orchestrator can inspect `stop_reason` and `content` blocks directly.
        ///
        /// # Errors
        /// Returns `Err(String)` on network failure, non-2xx HTTP status, or
        /// JSON parse failure.  The caller treats errors as final and surfaces
        /// them to the user as `"Error: …"` strings.
        pub async fn complete(&self, messages: Vec<Value>) -> Result<Value, String> {
            let mut body = json!({
                "model": self.model,
                "max_tokens": 4096,
                "messages": messages,
                "tools": self.tools,
                "stream": false,
            });
            if !self.system_prompt.is_empty() {
                body["system"] = Value::String(self.system_prompt.clone());
            }

            debug!(
                "AnthropicProvider: POST /v1/messages model={} messages={}",
                self.model,
                messages.len()
            );

            let response = self
                .client
                .post("https://api.anthropic.com/v1/messages")
                .header("anthropic-version", "2023-06-01")
                .header("anthropic-beta", "tools-2024-04-04")
                .header("x-api-key", &self.api_key)
                .header("content-type", "application/json")
                .json(&body)
                .send()
                .await
                .map_err(|e| format!("HTTP request failed: {e}"))?;

            let status = response.status();
            let text = response
                .text()
                .await
                .map_err(|e| format!("Failed to read response body: {e}"))?;

            if !status.is_success() {
                warn!("Anthropic API returned {status}: {text}");
                return Err(format!("Anthropic API error {status}: {text}"));
            }

            debug!("AnthropicProvider: received {} bytes", text.len());

            serde_json::from_str(&text)
                .map_err(|e| format!("Failed to parse Anthropic response as JSON: {e}"))
        }
    }

    #[cfg(test)]
    mod tests {
        use super::*;
        use serde_json::json;

        #[test]
        fn converts_openai_tool_to_anthropic_format() {
            let openai = vec![json!({
                "type": "function",
                "function": {
                    "name": "search_web",
                    "description": "Searches the web",
                    "parameters": {
                        "type": "object",
                        "properties": {
                            "query": {"type": "string"}
                        },
                        "required": ["query"]
                    }
                }
            })];

            let provider = AnthropicProvider::new("key".into(), "model".into(), openai, "".into());
            let tool = &provider.tools[0];

            assert_eq!(tool["name"], "search_web");
            assert_eq!(tool["description"], "Searches the web");
            assert!(tool.get("function").is_none(), "wrapper key must be stripped");
            assert!(tool.get("input_schema").is_some(), "input_schema must exist");
            assert_eq!(
                tool["input_schema"]["properties"]["query"]["type"],
                "string"
            );
        }

        #[test]
        fn passthrough_when_no_function_wrapper() {
            let already_anthropic = vec![json!({
                "name": "do_thing",
                "description": "Does a thing",
                "input_schema": {"type": "object", "properties": {}}
            })];
            let provider =
                AnthropicProvider::new("key".into(), "model".into(), already_anthropic.clone(), "".into());
            assert_eq!(provider.tools[0]["name"], "do_thing");
        }

        #[test]
        fn empty_tool_list_passes_through() {
            let provider = AnthropicProvider::new("key".into(), "model".into(), vec![], "".into());
            assert!(provider.tools.is_empty());
        }

        #[test]
        fn system_prompt_stored_when_non_empty() {
            let provider = AnthropicProvider::new(
                "key".into(),
                "model".into(),
                vec![],
                "You are a helpful assistant.".into(),
            );
            assert_eq!(provider.system_prompt, "You are a helpful assistant.");
        }

        #[test]
        fn system_prompt_empty_by_default_variant() {
            let provider = AnthropicProvider::new("key".into(), "model".into(), vec![], "".into());
            assert!(provider.system_prompt.is_empty());
        }
    }
