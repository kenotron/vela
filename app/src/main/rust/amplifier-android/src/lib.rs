//! `amplifier-android` — JNI bridge from Kotlin to the amplifier-rust workspace crates.
//!
//! Thin wiring only. All AI orchestration is delegated to workspace crates:
//!
//! | Responsibility          | Workspace crate                                    |
//! |-------------------------|-------------------------------------------------------|
//! | Agent loop / hooks      | `amplifier-module-orchestrator-loop-streaming`     |
//! | Anthropic provider      | `amplifier-module-provider-anthropic`              |
//! | Context management      | `amplifier-module-context-simple`                  |
//! | Kotlin tool bridges     | local `jni_tools` module (preserved as-is)         |
//! | Kotlin hook bridges     | local `jni_hooks` module                           |
//!
//! ## Exposed JNI surface
//!
//! Package: `com.vela.app.ai`
//! Class:   `AmplifierBridge`
//!
//! | Kotlin signature | Description |
//! |---|---|
//! | `nativeRun(apiKey, model, toolsJson, historyJson, userInput, userContentJson, systemPrompt, tokenCb, toolCb, hookCallbacks, serverToolCb): String` | Run one user turn |
//!
//! ## Threading model
//!
//! A single `tokio` multi-thread runtime is shared across all JNI calls via
//! `once_cell::sync::Lazy`. The JNI thread blocks on `RT.block_on(…)` which drives
//! the async agent loop. JVM callbacks are issued from tokio worker threads via
//! `jvm.attach_current_thread()`.
//!
//! ## Message format
//!
//! The Kotlin side stores conversation history in **Anthropic wire format**.
//! Before loading into `SimpleContext`, this module converts it to
//! **amplifier-core format** via `convert_history_to_core_format`:
//!
//! | Anthropic field       | amplifier-core field  |
//! |-----------------------|-----------------------|
//! | `type: "tool_use"`    | `type: "tool_call"`   |
//! | `tool_use_id`         | `tool_call_id`        |
//! | `content` (result)    | `output`              |

#![allow(non_snake_case)] // required by JNI naming convention

use std::sync::Arc;

use amplifier_module_agent_runtime::AgentRegistry;
use amplifier_module_context_simple::SimpleContext;
use amplifier_module_orchestrator_loop_streaming::{
    Hook, HookRegistry, LoopConfig, LoopOrchestrator,
};
use amplifier_module_provider_anthropic::{AnthropicConfig, AnthropicProvider};
use amplifier_module_tool_delegate::{DelegateConfig, DelegateTool, SubagentRunner};
use android_logger::Config;
use jni::objects::{GlobalRef, JClass, JObject, JObjectArray, JString, JValue};
use jni::sys::jstring;
use jni::JavaVM;
use jni::JNIEnv;
use log::{error, LevelFilter};
use once_cell::sync::Lazy;
use serde_json::{json, Value};
use tokio::runtime::Runtime;

mod jni_hooks;
mod jni_tools;
mod agents;
use jni_tools::build_tool_map;

// ─────────────────────────────── Shared Tokio runtime ────────────────────────────────────

/// A single multi-thread runtime that persists for the lifetime of the process.
static RT: Lazy<Runtime> = Lazy::new(|| {
    tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
        .expect("failed to build tokio runtime")
});

// ─────────────────────────────── JNI entry point ─────────────────────────────────────────

/// Run one full user turn through the amplifier-core agent loop.
///
/// # Parameters (all non-null; pass `""` / `"[]"` for empty values)
///
/// * `api_key`            – Anthropic API key (`sk-ant-…`).
/// * `model`              – Model name, e.g. `"claude-sonnet-4-5"`. Empty → default.
/// * `tools_json`         – JSON array of OpenAI-format tool schemas.
/// * `history_json`       – JSON array of Anthropic-format prior-turn messages.
/// * `user_input`         – The user's current message text.
/// * `_user_content_json` – Optional content-blocks JSON (null = use plain `user_input`).
/// * `system_prompt`      – Optional system prompt. Pass `""` to omit.
/// * `vault_path`         – Filesystem path to the active vault root (used to load `.agents/`).
/// * `token_cb`           – Kotlin `TokenCallback` (`fun onToken(text: String)`).
/// * `tool_cb`            – Kotlin `ToolCallback` (`fun executeTool(name, argsJson): String`).
/// * `hook_callbacks`     – Kotlin `Array<HookRegistration>` — lifecycle hook registry.
/// * `_server_tool_cb`    – Kotlin `ServerToolCallback` (reserved for future use).
///
/// # Return value
///
/// The final assistant response text. On error, returns `"Error: <message>"`.
///
/// # Safety
///
/// All raw JNI objects are promoted to `GlobalRef` (wrapped in `Arc`) before
/// crossing the async boundary, keeping them valid on tokio worker threads.
#[no_mangle]
pub extern "C" fn Java_com_vela_app_ai_AmplifierBridge_nativeRun(
    mut env: JNIEnv,
    _class: JClass,
    api_key: JString,
    model: JString,
    tools_json: JString,
    history_json: JString,
    user_input: JString,
    _user_content_json: JObject,    // Kotlin: String? — reserved for content-blocks (unused)
    system_prompt: JString,
    vault_path: JString,
    token_cb: JObject,
    tool_cb: JObject,               // Kotlin: ToolCallback → executeTool()
    hook_callbacks: JObject,        // Kotlin: Array<HookRegistration>
    _server_tool_cb: JObject,       // Kotlin: ServerToolCallback (reserved for future use)
) -> jstring {
    // Initialise Android logcat sink exactly once.
    android_logger::init_once(
        Config::default()
            .with_max_level(LevelFilter::Debug)
            .with_tag("amplifier"),
    );

    // ── Extract Kotlin strings ────────────────────────────────────────────────────────────
    let api_key       = jstring_to_rust(&mut env, &api_key,       "api_key");
    let model         = jstring_to_rust(&mut env, &model,         "model");
    let tools_json    = jstring_to_rust(&mut env, &tools_json,    "tools_json");
    let history_json  = jstring_to_rust(&mut env, &history_json,  "history_json");
    let user_input    = jstring_to_rust(&mut env, &user_input,    "user_input");
    let system_prompt = jstring_to_rust(&mut env, &system_prompt, "system_prompt");
    let vault_path    = jstring_to_rust(&mut env, &vault_path,    "vault_path");

    // ── Obtain JavaVM ─────────────────────────────────────────────────────────────────────
    let jvm = match env.get_java_vm() {
        Ok(jvm) => Arc::new(jvm),
        Err(e) => {
            error!("nativeRun: failed to get JavaVM: {e:?}");
            return err_jstring(&mut env, "could not obtain JavaVM");
        }
    };

    // ── Pin callbacks as GlobalRefs (safe across async threads) ──────────────────────────
    let token_cb_global = match env.new_global_ref(token_cb) {
        Ok(r) => Arc::new(r),
        Err(e) => {
            error!("nativeRun: failed to create global ref for tokenCb: {e:?}");
            return err_jstring(&mut env, "could not pin tokenCb");
        }
    };
    let tool_cb_global = match env.new_global_ref(tool_cb) {
        Ok(r) => Arc::new(r),
        Err(e) => {
            error!("nativeRun: failed to create global ref for toolCb: {e:?}");
            return err_jstring(&mut env, "could not pin toolCb");
        }
    };

    // ── Build hook registrations from Kotlin hookCallbacks array ─────────────────────────
    let hook_registrations = build_hook_registrations(&mut env, hook_callbacks);

    // ── Run the agent loop on the shared tokio runtime ───────────────────────────────────
    let result = RT.block_on(run_agent_loop(
        api_key,
        model,
        tools_json,
        history_json,
        user_input,
        system_prompt,
        vault_path,
        jvm,
        token_cb_global,
        tool_cb_global,
        hook_registrations,
    ));

    match result {
        Ok(text) => env.new_string(text).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut()),
        Err(e)   => err_jstring(&mut env, &format!("Error: {e}")),
    }
}

// ──────────────────────────── nativeListAgents JNI entry ────────────────────────────

/// Build an [`AgentRegistry`] for the given vault and return all agents as a JSON array.
///
/// Wire format: `[{"name":"...","description":"...","tools":[...]}, ...]`
/// On any failure, returns `"[]"`.
///
/// # Parameters
/// * `vault_path` – Filesystem path to the active vault root.
#[no_mangle]
pub extern "C" fn Java_com_vela_app_ai_AmplifierBridge_nativeListAgents(
    mut env: JNIEnv,
    _class: JClass,
    vault_path: JString,
) -> jstring {
    let vault_path = jstring_to_rust(&mut env, &vault_path, "vault_path");
    let registry = crate::agents::build_agent_registry(std::path::Path::new(&vault_path));
    let json = crate::agents::list_agents_to_json(&registry);
    env.new_string(json)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

// ─────────────────────────────── Hook registrations builder ──────────────────────────────

/// Parse a Kotlin `Array<HookRegistration>` JObject into a list of
/// `(GlobalRef, event_names)` tuples for use in building the `HookRegistry`.
///
/// Each `HookRegistration` has:
/// - `events: Array<String>` — the lifecycle event names this hook subscribes to
/// - `callback: HookCallback` — the Kotlin callback to invoke
///
/// Invalid elements are skipped with a warning.
fn build_hook_registrations(
    env: &mut JNIEnv,
    hook_callbacks: JObject,
) -> Vec<(Arc<GlobalRef>, Vec<String>)> {
    let mut registrations: Vec<(Arc<GlobalRef>, Vec<String>)> = Vec::new();

    if hook_callbacks.is_null() {
        return registrations;
    }

    // Cast JObject → JObjectArray (safe: Kotlin guarantees Array<HookRegistration>)
    let arr = JObjectArray::from(hook_callbacks);
    let len = match env.get_array_length(&arr) {
        Ok(n) => n,
        Err(e) => {
            log::warn!("build_hook_registrations: get_array_length failed: {e:?}");
            return registrations;
        }
    };

    for i in 0..len {
        // Get the HookRegistration element at index i
        let elem = match env.get_object_array_element(&arr, i) {
            Ok(e) => e,
            Err(e) => {
                log::warn!("build_hook_registrations: get_object_array_element[{i}] failed: {e:?}");
                let _ = env.exception_clear();
                continue;
            }
        };

        // ── Extract events field ([Ljava/lang/String;) ────────────────────────────────────
        let event_names: Vec<String> = match env.get_field(&elem, "events", "[Ljava/lang/String;") {
            Ok(jv) => match jv.l() {
                Ok(events_obj) if !events_obj.is_null() => {
                    let ev_arr = JObjectArray::from(events_obj);
                    let ev_len = env.get_array_length(&ev_arr).unwrap_or(0);
                    (0..ev_len)
                        .filter_map(|j| {
                            env.get_object_array_element(&ev_arr, j).ok().and_then(|s| {
                                let jstr = JString::from(s);
                                env.get_string(&jstr).ok().map(|gs| String::from(gs))
                            })
                        })
                        .collect()
                }
                _ => Vec::new(),
            },
            Err(e) => {
                log::warn!("build_hook_registrations: get events field[{i}] failed: {e:?}");
                let _ = env.exception_clear();
                Vec::new()
            }
        };

        // ── Extract callback field (Lcom/vela/app/ai/AmplifierBridge$HookCallback;) ───────
        let cb_global = match env.get_field(
            &elem,
            "callback",
            "Lcom/vela/app/ai/AmplifierBridge$HookCallback;",
        ) {
            Ok(jv) => match jv.l() {
                Ok(obj) if !obj.is_null() => env.new_global_ref(obj).ok().map(Arc::new),
                _ => None,
            },
            Err(e) => {
                log::warn!("build_hook_registrations: get callback field[{i}] failed: {e:?}");
                let _ = env.exception_clear();
                None
            }
        };

        if let Some(global) = cb_global {
            registrations.push((global, event_names));
        }
    }

    registrations
}

// ─────────────────────────────── Agent loop ──────────────────────────────────────────────

async fn run_agent_loop(
    api_key: String,
    model: String,
    tools_json: String,
    history_json: String,
    user_input: String,
    system_prompt: String,
    vault_path: String,
    jvm: Arc<JavaVM>,
    token_cb: Arc<GlobalRef>,
    tool_cb: Arc<GlobalRef>,
    hook_registrations: Vec<(Arc<GlobalRef>, Vec<String>)>,
) -> anyhow::Result<String> {
    // Parse history and convert Anthropic wire format → amplifier-core format.
    let raw_history: Vec<Value> = serde_json::from_str(&history_json).unwrap_or_else(|e| {
        log::warn!("run_agent_loop: failed to parse history_json: {e}");
        vec![]
    });
    let history = convert_history_to_core_format(raw_history);

    // Build provider — default model if caller passed empty string.
    let model = if model.is_empty() {
        "claude-sonnet-4-5".to_string()
    } else {
        model
    };
    let provider = Arc::new(AnthropicProvider::new(AnthropicConfig {
        api_key,
        model,
        ..AnthropicConfig::default()
    }));

    // Build Kotlin tool bridges (each tool delegates execution to Kotlin via JNI).
    let tool_map = build_tool_map(&tools_json, Arc::clone(&jvm), Arc::clone(&tool_cb));

    // Build and configure the orchestrator.
    let config = LoopConfig { max_steps: 10, system_prompt };
    let orch = Arc::new(LoopOrchestrator::new(config));
    orch.register_provider("anthropic".to_string(), provider).await;
    for tool in tool_map.into_values() {
        orch.register_tool(tool).await;
    }

    // ── Build agent registry (foundation + vault/.agents/) and register DelegateTool ──
    let agent_registry: Arc<AgentRegistry> =
        crate::agents::build_agent_registry(std::path::Path::new(&vault_path));

    let delegate_tool = DelegateTool::new(
        Arc::clone(&orch) as Arc<dyn SubagentRunner>,
        Arc::clone(&agent_registry),
        DelegateConfig::default(),
    );
    orch.register_tool(Arc::new(delegate_tool)).await;
    log::info!(
        "[amplifier-android] DelegateTool registered with {} agents",
        agent_registry.available_names().len()
    );

    // Build context pre-loaded with converted history.
    let mut ctx = SimpleContext::new(history);

    // Build hook registry from Kotlin hookCallbacks registrations.
    let mut hooks = HookRegistry::new();
    for (cb_global, event_names) in hook_registrations {
        let events = crate::jni_hooks::parse_hook_events(&event_names);
        if !events.is_empty() {
            let bridge = crate::jni_hooks::KotlinHookBridge::new(
                cb_global,
                events,
                Arc::clone(&jvm),
            );
            hooks.register(Box::new(bridge) as Box<dyn Hook>);
        }
    }

    // Build the on_token closure — forwards text segments to Kotlin UI.
    let on_token = {
        let jvm       = Arc::clone(&jvm);
        let token_cb  = Arc::clone(&token_cb);
        move |text: &str| {
            emit_token_to_kotlin(&jvm, &token_cb, text);
        }
    };

    orch.execute(user_input, &mut ctx, &hooks, on_token).await
}

// ─────────────────────────────── Token streaming ─────────────────────────────────────────

/// Call `TokenCallback.onToken(text)` on the Kotlin side.
///
/// Attaches the calling thread to the JVM for the duration of the call.
/// Failures are logged and silently swallowed — a broken callback must not
/// abort the agent loop.
fn emit_token_to_kotlin(jvm: &Arc<JavaVM>, callback: &Arc<GlobalRef>, text: &str) {
    let Ok(mut env) = jvm.attach_current_thread() else {
        log::warn!("emit_token_to_kotlin: failed to attach JVM thread");
        return;
    };
    let Ok(j_text) = env.new_string(text) else {
        log::warn!("emit_token_to_kotlin: failed to create Java string");
        return;
    };
    if let Err(e) = env.call_method(
        callback.as_ref(),
        "onToken",
        "(Ljava/lang/String;)V",
        &[JValue::Object(&j_text)],
    ) {
        error!("emit_token_to_kotlin: onToken call failed: {e:?}");
        let _ = env.exception_clear();
    }
}

// ─────────────────────────────── History conversion ──────────────────────────────────────

/// Convert a list of Anthropic-wire-format messages to amplifier-core format.
///
/// Applies `convert_message` to every element; messages with a plain-string
/// `content` field are returned unchanged.
fn convert_history_to_core_format(msgs: Vec<Value>) -> Vec<Value> {
    msgs.into_iter().map(convert_message).collect()
}

/// Convert a single Anthropic-wire-format message `Value` to amplifier-core format.
///
/// The conversion applies to the `content` array only when needed:
///
/// | Anthropic (in)          | amplifier-core (out)          |
/// |-------------------------|-------------------------------|
/// | `type: "tool_use"`      | `type: "tool_call"`           |
/// | `tool_use_id: "…"`      | `tool_call_id: "…"`           |
/// | `content: <result>`     | `output: <result>`            |
///
/// Messages without a content array (plain text messages) are returned as-is.
fn convert_message(mut msg: Value) -> Value {
    let content_array = match msg.get("content").and_then(|c| c.as_array()) {
        Some(arr) => arr.clone(),
        None      => return msg,
    };

    let converted: Vec<Value> = content_array
        .into_iter()
        .map(|block| match block.get("type").and_then(|t| t.as_str()) {
            Some("tool_use") => json!({
                "type":  "tool_call",
                "id":    block.get("id").cloned().unwrap_or(Value::Null),
                "name":  block.get("name").cloned().unwrap_or(Value::Null),
                "input": block.get("input").cloned().unwrap_or(json!({})),
            }),
            Some("tool_result") => {
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
        })
        .collect();

    if let Some(obj) = msg.as_object_mut() {
        obj.insert("content".to_string(), json!(converted));
    }
    msg
}

// ─────────────────────────────── JNI helpers ─────────────────────────────────────────────

/// Extract a Rust `String` from a JNI `JString`.
///
/// Returns an empty string on failure so the caller always has a valid value.
fn jstring_to_rust(env: &mut JNIEnv, s: &JString, name: &str) -> String {
    env.get_string(s).map(|js| js.into()).unwrap_or_else(|e| {
        error!("jstring_to_rust: failed to read '{name}': {e:?}");
        String::new()
    })
}

/// Build a Java `"Error: <msg>"` string and return it as a raw `jstring`.
///
/// Returns null if string allocation fails — Kotlin must treat null as an
/// error sentinel.
fn err_jstring(env: &mut JNIEnv, msg: &str) -> jstring {
    env.new_string(format!("Error: {msg}"))
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

// ─────────────────────────────── Tests ───────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn convert_history_plain_text_unchanged() {
        let msgs = vec![json!({"role": "user", "content": "Hello"})];
        let converted = convert_history_to_core_format(msgs);
        assert_eq!(converted[0]["content"], "Hello");
    }

    #[test]
    fn convert_history_tool_use_becomes_tool_call() {
        let msgs = vec![json!({
            "role": "assistant",
            "content": [
                {"type": "text",     "text": "Searching…"},
                {"type": "tool_use", "id": "toolu_01", "name": "search", "input": {"q": "foo"}}
            ]
        })];
        let converted = convert_history_to_core_format(msgs);
        let content = converted[0]["content"].as_array().unwrap();
        // text block is unchanged
        assert_eq!(content[0]["type"], "text");
        // tool_use → tool_call
        assert_eq!(content[1]["type"], "tool_call");
        assert_eq!(content[1]["id"],   "toolu_01");
        assert_eq!(content[1]["name"], "search");
    }

    #[test]
    fn convert_history_tool_result_fields_renamed() {
        let msgs = vec![json!({
            "role": "user",
            "content": [{
                "type":        "tool_result",
                "tool_use_id": "toolu_01",
                "content":     "result text",
            }]
        })];
        let converted = convert_history_to_core_format(msgs);
        let block = &converted[0]["content"][0];
        assert_eq!(block["type"],         "tool_result");
        assert_eq!(block["tool_call_id"], "toolu_01");
        assert_eq!(block["output"],       "result text");
        // Old field names must NOT be present
        assert!(block.get("tool_use_id").is_none());
    }

    #[test]
    fn convert_history_already_core_format_passthrough() {
        // If Kotlin somehow stores core format already, it must not be double-converted.
        let msgs = vec![json!({
            "role": "user",
            "content": [{
                "type":         "tool_result",
                "tool_call_id": "toolu_01",
                "output":       "result",
            }]
        })];
        let converted = convert_history_to_core_format(msgs);
        let block = &converted[0]["content"][0];
        assert_eq!(block["type"], "tool_result");
        // Already-core-format tool_result should retain tool_call_id unchanged.
        assert_eq!(block["tool_call_id"], "toolu_01");
        assert_eq!(block["output"],       "result");
    }

    /// Verify that DelegateTool can be constructed with a LoopOrchestrator as the
    /// SubagentRunner — confirming the Arc coercion used in run_agent_loop compiles.
    #[test]
    fn delegate_tool_wired_with_orchestrator_as_runner() {
        use amplifier_module_orchestrator_loop_streaming::{LoopConfig, LoopOrchestrator};
        let config = LoopConfig { max_steps: 1, system_prompt: String::new() };
        let orch: Arc<LoopOrchestrator> = Arc::new(LoopOrchestrator::new(config));
        let registry: Arc<AgentRegistry> =
            crate::agents::build_agent_registry(std::path::Path::new("/tmp"));
        let _tool = DelegateTool::new(
            Arc::clone(&orch) as Arc<dyn SubagentRunner>,
            Arc::clone(&registry),
            DelegateConfig::default(),
        );
        // If this compiles, DelegateTool accepts LoopOrchestrator as SubagentRunner.
        assert!(registry.available_names().len() >= 6, "expected foundation agents");
    }

    #[test]
    fn convert_history_empty_list_is_noop() {
        let msgs: Vec<Value> = vec![];
        let converted = convert_history_to_core_format(msgs);
        assert!(converted.is_empty());
    }

    #[test]
    fn convert_history_multiple_messages_all_converted() {
        let msgs = vec![
            json!({"role": "user",      "content": "Hi"}),
            json!({"role": "assistant", "content": [
                {"type": "tool_use", "id": "t1", "name": "fn", "input": {}}
            ]}),
            json!({"role": "user", "content": [
                {"type": "tool_result", "tool_use_id": "t1", "content": "ok"}
            ]}),
        ];
        let converted = convert_history_to_core_format(msgs);
        assert_eq!(converted.len(), 3);
        // First message is plain text — unchanged
        assert_eq!(converted[0]["content"], "Hi");
        // Second message: tool_use → tool_call
        assert_eq!(converted[1]["content"][0]["type"], "tool_call");
        // Third message: tool_result fields renamed
        assert_eq!(converted[2]["content"][0]["tool_call_id"], "t1");
        assert_eq!(converted[2]["content"][0]["output"],       "ok");
    }
}
