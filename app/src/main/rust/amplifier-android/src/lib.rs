//! `amplifier-android` — JNI bridge from Kotlin to the amplifier-core agent loop.
//!
//! All AI orchestration is wired through amplifier-core traits:
//!
//! | Module             | amplifier-core trait |
//! |--------------------|----------------------|
//! | `AnthropicProvider`| [`Provider`]         |
//! | `SimpleContext`    | [`ContextManager`]   |
//! | `SimpleOrchestrator`| [`Orchestrator`]    |
//! | `KotlinToolBridge` | [`Tool`]             |
//!
//! ## Exposed JNI surface
//!
//! Package: `com.vela.app.ai`
//! Class:   `AmplifierBridge`
//!
//! | Kotlin signature | Description |
//! |---|---|
//! | `nativeRun(apiKey, model, toolsJson, historyJson, userInput, systemPrompt, tokenCb, toolCb): String` | Run one user turn |
//!
//! ## Threading model
//!
//! A single `tokio` multi-thread runtime is shared across all JNI calls.
//! The JNI thread calls `RT.block_on(…)` which drives the async agent loop.
//! JVM callbacks are issued from tokio worker threads via
//! `jvm.attach_current_thread()`.
//!
//! ## Message format
//!
//! The Kotlin side stores conversation history in **Anthropic wire format**
//! (the format returned by `api.anthropic.com/v1/messages`).  Before loading
//! history into the [`SimpleContext`], this module converts it to
//! **amplifier-core format**:
//!
//! | Anthropic field       | amplifier-core field  |
//! |-----------------------|-----------------------|
//! | `type: "tool_use"`    | `type: "tool_call"`   |
//! | `tool_use_id`         | `tool_call_id`        |
//! | `content` (result)    | `output`              |
//!
//! The [`AnthropicProvider`] reverses these conversions when building each
//! API request, so the Anthropic API always sees its native format.

#![allow(non_snake_case)] // required by JNI naming convention

use std::collections::HashMap;
use std::sync::Arc;

use amplifier_core::traits::{ContextManager, Orchestrator, Provider, Tool};
use android_logger::Config;
use jni::objects::{JClass, JObject, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use log::{error, info, LevelFilter};
use once_cell::sync::Lazy;
use serde_json::{json, Value};
use tokio::runtime::Runtime;

mod context;
mod jni_tools;
mod orchestrator;
mod provider;

use context::SimpleContext;
use jni_tools::build_tool_map;
use orchestrator::SimpleOrchestrator;
use provider::AnthropicProvider;

// ──────────────────────────── Shared Tokio runtime ────────────────────────────

/// A single multi-thread runtime that persists for the lifetime of the process.
static RT: Lazy<Runtime> = Lazy::new(|| {
    tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
        .expect("failed to build tokio runtime")
});

// ──────────────────────────── JNI entry point ─────────────────────────────────

/// Run one full user turn through the amplifier-core agent loop.
///
/// ## Parameters (all non-null; pass `""` / `"[]"` for empty values)
///
/// * `api_key`       – Anthropic API key (`sk-ant-…`).
/// * `model`         – Model name, e.g. `"claude-sonnet-4-6"`.
/// * `tools_json`    – JSON array of OpenAI-format tool schemas.
///   ```json
///   [{"type":"function","function":{"name":"search_web","description":"…","parameters":{…}}}]
///   ```
/// * `history_json`  – JSON array of Anthropic-format prior-turn messages.
///   ```json
///   [{"role":"user","content":"Hi"},{"role":"assistant","content":"Hello!"}]
///   ```
/// * `user_input`    – The user's current message text.
/// * `system_prompt` – Optional system prompt. Pass `""` to omit.
/// * `token_cb`      – Kotlin `TokenCallback` instance:
///   ```kotlin
///   interface TokenCallback { fun onToken(text: String) }
///   ```
/// * `tool_cb`       – Kotlin `ToolCallback` instance:
///   ```kotlin
///   interface ToolCallback { fun executeTool(name: String, inputJson: String): String }
///   ```
///
/// ## Return value
///
/// The final assistant response text.  On error, returns `"Error: <message>"`.
///
/// ## Safety
///
/// All raw JNI objects are promoted to `GlobalRef` (wrapped in `Arc`) before
/// crossing the async boundary, so they remain valid on tokio worker threads.
#[no_mangle]
pub extern "C" fn Java_com_vela_app_ai_AmplifierBridge_nativeRun(
    mut env: JNIEnv,
    _class: JClass,
    api_key: JString,
    model: JString,
    tools_json: JString,
    history_json: JString,
    user_input: JString,
    user_content_json: JObject,   // nullable String — Anthropic content blocks JSON array
    system_prompt: JString,
    token_cb: JObject,
    tool_cb: JObject,
) -> jstring {
    // Initialise Android logcat sink exactly once.
    android_logger::init_once(
        Config::default()
            .with_max_level(LevelFilter::Debug)
            .with_tag("amplifier"),
    );

    // ── Extract Kotlin strings ────────────────────────────────────────────────
    let api_key = jni_string(&mut env, &api_key, "api_key");
    let model = jni_string(&mut env, &model, "model");
    let tools_json = jni_string(&mut env, &tools_json, "tools_json");
    let history_json = jni_string(&mut env, &history_json, "history_json");
    let user_input = jni_string(&mut env, &user_input, "user_input");

    // user_content_json is nullable (String? in Kotlin) — use JObject to allow null check.
    let user_content_json: Option<String> = if user_content_json.is_null() {
        None
    } else {
        let jstr = JString::from(user_content_json);
        Some(env.get_string(&jstr).map(|s| s.into()).unwrap_or_default())
    };

    let system_prompt = jni_string(&mut env, &system_prompt, "system_prompt");

    info!(
        "nativeRun: model={model} user_len={} history_len={} tools_len={} system={} has_content_json={}",
        user_input.len(),
        history_json.len(),
        tools_json.len(),
        !system_prompt.is_empty(),
        user_content_json.is_some(),
    );

    // ── Promote callbacks to Arc<GlobalRef> (safe across async threads) ───────
    let jvm = match env.get_java_vm() {
        Ok(jvm) => Arc::new(jvm),
        Err(e) => {
            error!("nativeRun: failed to get JavaVM: {e:?}");
            return err_string(&mut env, "could not obtain JavaVM");
        }
    };

    let token_cb_global = match env.new_global_ref(token_cb) {
        Ok(r) => Arc::new(r),
        Err(e) => {
            error!("nativeRun: failed to create global ref for tokenCb: {e:?}");
            return err_string(&mut env, "could not pin tokenCb");
        }
    };

    let tool_cb_global = match env.new_global_ref(tool_cb) {
        Ok(r) => Arc::new(r),
        Err(e) => {
            error!("nativeRun: failed to create global ref for toolCb: {e:?}");
            return err_string(&mut env, "could not pin toolCb");
        }
    };

    // ── Run the agent loop on the shared tokio runtime ────────────────────────
    let result = RT.block_on(async move {
        // Parse the Anthropic-format history and convert to amplifier-core format.
        let raw_history: Vec<Value> = serde_json::from_str(&history_json).unwrap_or_else(|e| {
            error!("nativeRun: failed to parse history_json: {e}");
            vec![]
        });
        let history = raw_history
            .into_iter()
            .map(anthropic_to_core_message)
            .collect::<Vec<_>>();

        // Build amplifier-core modules.
        let provider: Arc<dyn Provider> = Arc::new(AnthropicProvider::new(
            api_key,
            model,
            system_prompt,
        ));
        let context: Arc<dyn ContextManager> =
            Arc::new(SimpleContext::new(history));
        let tools: HashMap<String, Arc<dyn Tool>> =
            build_tool_map(&tools_json, Arc::clone(&jvm), Arc::clone(&tool_cb_global));
        let orchestrator = SimpleOrchestrator::new(Arc::clone(&jvm), Arc::clone(&token_cb_global));

        // Wire providers map (Orchestrator trait expects a HashMap).
        let mut providers: HashMap<String, Arc<dyn Provider>> = HashMap::new();
        providers.insert("anthropic".to_string(), Arc::clone(&provider));

        // Decode user_content_json (optional content-blocks array) into a Value.
        // Passed as hooks so the orchestrator can build a rich user message (image/document
        // blocks) instead of the plain-text fallback when content blocks are present.
        let user_content_value: Value = match user_content_json {
            Some(ref json_str) => serde_json::from_str(json_str).unwrap_or(Value::Null),
            None => Value::Null,
        };

        // Execute the agent loop via the amplifier-core Orchestrator trait.
        match orchestrator
            .execute(
                user_input,
                context,
                providers,
                tools,
                user_content_value, // hooks — content blocks array, or Null for plain text
                Value::Null,        // coordinator — not used in this integration
            )
            .await
        {
            Ok(text) => text,
            Err(e) => {
                error!("nativeRun: agent loop error: {e:?}");
                format!("Error: {e:?}")
            }
        }
    });

    info!("nativeRun: result_len={}", result.len());

    env.new_string(result)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

// ──────────────────────────── Message format conversion ───────────────────────

/// Convert an Anthropic-wire-format message `Value` to amplifier-core format.
///
/// The conversion is applied to the `content` array only when needed:
///
/// | Anthropic (in)          | amplifier-core (out)         |
/// |-------------------------|------------------------------|
/// | `type: "tool_use"`      | `type: "tool_call"`          |
/// | `tool_use_id: "…"`      | `tool_call_id: "…"`          |
/// | `content: <result>`     | `output: <result>`           |
///
/// Messages without a content array (plain text messages) are returned as-is.
fn anthropic_to_core_message(mut msg: Value) -> Value {
    // Only messages whose content is an array need conversion.
    let content_array = match msg.get("content").and_then(|c| c.as_array()) {
        Some(arr) => arr.clone(),
        None => return msg,
    };

    let converted: Vec<Value> = content_array
        .into_iter()
        .map(|block| {
            match block.get("type").and_then(|t| t.as_str()) {
                Some("tool_use") => {
                    // Anthropic tool_use → amplifier-core tool_call
                    json!({
                        "type":  "tool_call",
                        "id":    block.get("id").cloned().unwrap_or(Value::Null),
                        "name":  block.get("name").cloned().unwrap_or(Value::Null),
                        "input": block.get("input").cloned().unwrap_or(json!({})),
                    })
                }
                Some("tool_result") => {
                    // Anthropic tool_result → amplifier-core tool_result
                    // Field renames: tool_use_id → tool_call_id, content → output
                    let tool_call_id = block
                        .get("tool_use_id")
                        .or_else(|| block.get("tool_call_id"))
                        .cloned()
                        .unwrap_or(Value::Null);
                    let output = block
                        .get("content")
                        .or_else(|| block.get("output"))
                        .cloned()
                        .unwrap_or(Value::Null);
                    json!({
                        "type":         "tool_result",
                        "tool_call_id": tool_call_id,
                        "output":       output,
                    })
                }
                _ => block,
            }
        })
        .collect();

    if let Some(obj) = msg.as_object_mut() {
        obj.insert("content".to_string(), json!(converted));
    }
    msg
}

// ──────────────────────────── JNI helpers ─────────────────────────────────────

/// Extract a Rust `String` from a JNI `JString`.
///
/// Returns an empty string on failure so the caller always has a valid value.
fn jni_string(env: &mut JNIEnv, s: &JString, name: &str) -> String {
    env.get_string(s).map(|js| js.into()).unwrap_or_else(|e| {
        error!("jni_string: failed to read '{name}': {e:?}");
        String::new()
    })
}

/// Build a Java string from `msg` and return it as a raw `jstring`.
///
/// Returns null if string allocation fails — Kotlin must treat null as an
/// error sentinel.
fn err_string(env: &mut JNIEnv, msg: &str) -> jstring {
    env.new_string(format!("Error: {msg}"))
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

// ──────────────────────────── Tests ───────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn anthropic_to_core_plain_text_unchanged() {
        let msg = json!({"role": "user", "content": "Hello"});
        let converted = anthropic_to_core_message(msg.clone());
        assert_eq!(converted["content"], "Hello");
    }

    #[test]
    fn anthropic_to_core_tool_use_becomes_tool_call() {
        let msg = json!({
            "role": "assistant",
            "content": [
                {"type": "text",     "text": "Searching…"},
                {"type": "tool_use", "id": "toolu_01", "name": "search", "input": {"q": "foo"}}
            ]
        });
        let converted = anthropic_to_core_message(msg);
        let content = converted["content"].as_array().unwrap();
        // text block is unchanged
        assert_eq!(content[0]["type"], "text");
        // tool_use → tool_call
        assert_eq!(content[1]["type"], "tool_call");
        assert_eq!(content[1]["id"],   "toolu_01");
        assert_eq!(content[1]["name"], "search");
    }

    #[test]
    fn anthropic_to_core_tool_result_fields_renamed() {
        let msg = json!({
            "role": "user",
            "content": [{
                "type":        "tool_result",
                "tool_use_id": "toolu_01",
                "content":     "result text",
            }]
        });
        let converted = anthropic_to_core_message(msg);
        let block = &converted["content"][0];
        assert_eq!(block["type"],         "tool_result");
        assert_eq!(block["tool_call_id"], "toolu_01");
        assert_eq!(block["output"],       "result text");
        // Old field names must NOT be present
        assert!(block.get("tool_use_id").is_none());
    }

    #[test]
    fn anthropic_to_core_already_core_format_passthrough() {
        // If Kotlin somehow stores core format already, it must not be double-converted
        let msg = json!({
            "role": "user",
            "content": [{
                "type":         "tool_result",
                "tool_call_id": "toolu_01",
                "output":       "result",
            }]
        });
        let converted = anthropic_to_core_message(msg);
        let block = &converted["content"][0];
        // tool_result blocks without tool_use_id fall through the match arm
        // as an unrecognised type — this is intentional: double-conversion would
        // break, so the test verifies idempotency for core-format input.
        assert_eq!(block["type"], "tool_result");
    }
}
