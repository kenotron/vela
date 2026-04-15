//! KotlinToolBridge — implements the amplifier-core [`Tool`] trait by
//! delegating execution back to the Kotlin `ToolCallback` via JNI.
//!
//! Each Kotlin-registered tool becomes one `KotlinToolBridge` instance.
//! All bridges share the same JVM + `tool_cb` global reference; the
//! tool name is used to dispatch to the right handler on the Kotlin side.
//!
//! ## JNI call path
//!
//! ```text
//! KotlinToolBridge::execute(input)
//!   → attach current thread to JVM
//!   → call ToolCallback.executeTool(name, inputJson)
//!   → return result string as ToolResult.output
//! ```

use std::collections::HashMap;
use std::future::Future;
use std::pin::Pin;
use std::sync::Arc;

use amplifier_core::models::ToolResult;
use amplifier_core::errors::ToolError;
use amplifier_core::messages::ToolSpec;
use amplifier_core::traits::Tool;
use jni::objects::{GlobalRef, JString, JValue};
use jni::JavaVM;
use log::warn;
use serde_json::{json, Value};

/// One tool backed by the Kotlin `ToolCallback.executeTool(name, argsJson)` method.
///
/// Multiple bridges share the same `jvm` and `tool_cb`; only the tool name
/// (and its spec) differ between instances.
pub struct KotlinToolBridge {
    /// Tool name (matches the key in the orchestrator's `tools` map).
    tool_name: String,
    /// Human-readable description forwarded to the LLM.
    tool_description: String,
    /// Full JSON Schema for the tool's input parameters.
    ///
    /// Stored as the `ToolSpec.parameters` map (i.e. the object itself,
    /// not wrapped in `{type:function, function:{…}}`).
    input_schema: HashMap<String, Value>,
    /// Shared JVM handle — used to attach worker threads for JNI calls.
    jvm: Arc<JavaVM>,
    /// Global reference to the Kotlin `ToolCallback` object.
    /// Shared across all bridges created in the same JNI call.
    tool_cb: Arc<GlobalRef>,
}

impl KotlinToolBridge {
    /// Construct a bridge for one tool.
    pub fn new(
        tool_name: String,
        tool_description: String,
        input_schema: HashMap<String, Value>,
        jvm: Arc<JavaVM>,
        tool_cb: Arc<GlobalRef>,
    ) -> Self {
        Self {
            tool_name,
            tool_description,
            input_schema,
            jvm,
            tool_cb,
        }
    }

    /// Call `ToolCallback.executeTool(name, inputJson)` on the Kotlin side.
    ///
    /// Attaches the calling thread to the JVM for the duration of the call.
    /// Returns `Ok(result_string)` or `Err(ToolError)` on JNI failure.
    fn call_kotlin(&self, input_json: &str) -> Result<String, ToolError> {
        let mut env = self.jvm.attach_current_thread().map_err(|e| {
            warn!("KotlinToolBridge[{}]: failed to attach JVM thread: {e:?}", self.tool_name);
            ToolError::Other {
                message: format!("JVM attach failed: {e}"),
            }
        })?;

        let j_name = env.new_string(&self.tool_name).map_err(|e| ToolError::Other {
            message: format!("new_string(name) failed: {e}"),
        })?;
        let j_args = env.new_string(input_json).map_err(|e| ToolError::Other {
            message: format!("new_string(args) failed: {e}"),
        })?;

        let call_result = env.call_method(
            self.tool_cb.as_ref(),
            "executeTool",
            "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
            &[JValue::Object(&j_name), JValue::Object(&j_args)],
        );

        let jobject = match call_result {
            Ok(v) => v.l().map_err(|e| {
                let _ = env.exception_clear();
                ToolError::Other {
                    message: format!("JValue → Object failed: {e}"),
                }
            })?,
            Err(e) => {
                warn!("KotlinToolBridge[{}]: executeTool threw: {e:?}", self.tool_name);
                let _ = env.exception_clear();
                return Err(ToolError::Other {
                    message: format!("executeTool threw: {e}"),
                });
            }
        };

        if jobject.is_null() {
            warn!("KotlinToolBridge[{}]: executeTool returned null", self.tool_name);
            return Ok(String::new());
        }

        let jstr = JString::from(jobject);
        env.get_string(&jstr)
            .map(|s| String::from(s))
            .map_err(|e| ToolError::Other {
                message: format!("get_string failed: {e}"),
            })
    }
}

// ──────────────────────────────── Tool impl ───────────────────────────────────

impl Tool for KotlinToolBridge {
    fn name(&self) -> &str {
        &self.tool_name
    }

    fn description(&self) -> &str {
        &self.tool_description
    }

    fn get_spec(&self) -> ToolSpec {
        ToolSpec {
            name: self.tool_name.clone(),
            parameters: self.input_schema.clone(),
            description: if self.tool_description.is_empty() {
                None
            } else {
                Some(self.tool_description.clone())
            },
            extensions: HashMap::new(),
        }
    }

    fn execute(
        &self,
        input: Value,
    ) -> Pin<Box<dyn Future<Output = Result<ToolResult, ToolError>> + Send + '_>> {
        let input_json = input.to_string();
        let result = self.call_kotlin(&input_json);

        Box::pin(std::future::ready(result.map(|output| ToolResult {
            success: true,
            output: Some(json!(output)),
            error: None,
        })))
    }
}

// ──────────────────────────────── Builder helper ──────────────────────────────

/// Parse OpenAI-format tool specs from `tools_json` and build one
/// [`KotlinToolBridge`] per tool, all sharing the same JVM + `tool_cb`.
///
/// OpenAI format:
/// ```json
/// [
///   {
///     "type": "function",
///     "function": {
///       "name": "search_web",
///       "description": "…",
///       "parameters": { "type": "object", "properties": { … } }
///     }
///   }
/// ]
/// ```
///
/// Returns an empty map on JSON parse failure.
pub fn build_tool_map(
    tools_json: &str,
    jvm: Arc<JavaVM>,
    tool_cb: Arc<GlobalRef>,
) -> HashMap<String, Arc<dyn Tool>> {
    let raw: Vec<Value> = match serde_json::from_str(tools_json) {
        Ok(v) => v,
        Err(e) => {
            warn!("build_tool_map: failed to parse tools_json: {e}");
            return HashMap::new();
        }
    };

    raw.into_iter()
        .filter_map(|entry| {
            // Support both OpenAI format {"type":"function","function":{…}} and
            // already-flattened Anthropic format {"name":…,"description":…,"parameters":…}.
            let func = if let Some(f) = entry.get("function") {
                f.clone()
            } else {
                entry.clone()
            };

            let name = func.get("name").and_then(|v| v.as_str())?.to_string();
            let description = func
                .get("description")
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .to_string();

            // Parameters is the JSON Schema object; store as HashMap<String, Value>
            let schema: HashMap<String, Value> = func
                .get("parameters")
                .and_then(|p| p.as_object())
                .map(|obj| obj.iter().map(|(k, v)| (k.clone(), v.clone())).collect())
                .unwrap_or_else(|| {
                    let mut m = HashMap::new();
                    m.insert("type".to_string(), json!("object"));
                    m.insert("properties".to_string(), json!({}));
                    m
                });

            let bridge = Arc::new(KotlinToolBridge::new(
                name.clone(),
                description,
                schema,
                Arc::clone(&jvm),
                Arc::clone(&tool_cb),
            )) as Arc<dyn Tool>;

            Some((name, bridge))
        })
        .collect()
}
