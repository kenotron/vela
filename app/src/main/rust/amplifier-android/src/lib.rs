//! `amplifier-android` — JNI bridge from the Android/Kotlin layer to the
//! Anthropic agent loop implemented in `provider` + `orchestrator`.
//!
//! ## Exposed JNI surface
//!
//! Package: `com.vela.app.ai`  
//! Class:   `AmplifierBridge`
//!
//! | Kotlin signature | Description |
//! |---|---|
//! | `nativeRun(apiKey, model, toolsJson, historyJson, userInput, systemPrompt, tokenCb, toolCb): String` | Run one user turn through the full agent loop |
//!
//! ## Threading model
//!
//! A single `tokio` multi-thread runtime (`RT`) is shared across all JNI calls
//! via `once_cell::Lazy`.  The JNI thread calls `RT.block_on(…)` which drives
//! the async HTTP + orchestrator logic without blocking the main thread.
//!
//! JVM callbacks (`tokenCb.onToken`, `toolCb.executeTool`) are issued from
//! worker threads via `jvm.attach_current_thread()`.
//!
//! ## No amplifier-core dependency
//!
//! This crate talks directly to the Anthropic API through the plain-struct
//! implementation in `provider` and `orchestrator`.  The `amplifier-core`
//! crate is listed in `Cargo.toml` for future integration but its traits are
//! not imported here.

#![allow(non_snake_case)] // required by JNI naming convention

use android_logger::Config;
use jni::objects::{JClass, JObject, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use log::{error, info, LevelFilter};
use once_cell::sync::Lazy;
use std::sync::Arc;
use tokio::runtime::Runtime;

mod context;
mod orchestrator;
mod provider;

use context::SimpleContext;
use orchestrator::SimpleOrchestrator;
use provider::AnthropicProvider;

// ─────────────────────────────── Shared Tokio runtime ─────────────────────────────────────

/// A single multi-thread `tokio` runtime that persists for the lifetime of
/// the process.  Creating a runtime is expensive; sharing one avoids overhead
/// on repeated JNI calls.
static RT: Lazy<Runtime> = Lazy::new(|| {
    tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
        .expect("failed to build tokio runtime")
});

// ─────────────────────────────────────────── JNI entry points ─────────────────────────────

/// Run one full user turn through the Anthropic agent loop.
///
/// This is the primary entry point called from Kotlin's `AmplifierBridge`.
///
/// ## Parameters (all non-null; pass `""` / `"[]"` for empty)
///
/// * `api_key`       – Anthropic API key (`sk-ant-…`).
/// * `model`         – Model name, e.g. `"claude-3-5-haiku-20241022"`.
/// * `tools_json`    – JSON array of OpenAI-format tool schemas.
///   ```json
///   [{"type":"function","function":{"name":"search_web","description":"…","parameters":{…}}}]
///   ```
/// * `history_json`  – JSON array of Anthropic-format prior-turn messages.
///   ```json
///   [{"role":"user","content":"Hi"},{"role":"assistant","content":"Hello!"}]
///   ```
/// * `user_input`    – The user's current message text.
/// * `system_prompt` – Optional system prompt. Pass `""` to omit the field.
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
/// All raw JNI objects are promoted to `GlobalRef` before crossing the async
/// boundary so they remain valid on tokio worker threads.
#[no_mangle]
pub extern "C" fn Java_com_vela_app_ai_AmplifierBridge_nativeRun(
    mut env: JNIEnv,
    _class: JClass,
    api_key: JString,
    model: JString,
    tools_json: JString,
    history_json: JString,
    user_input: JString,
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

    // ── Extract Kotlin strings ──────────────────────────────────────────────────────────────
    let api_key = jni_string(&mut env, &api_key, "api_key");
    let model = jni_string(&mut env, &model, "model");
    let tools_json = jni_string(&mut env, &tools_json, "tools_json");
    let history_json = jni_string(&mut env, &history_json, "history_json");
    let user_input = jni_string(&mut env, &user_input, "user_input");
    let system_prompt = jni_string(&mut env, &system_prompt, "system_prompt");

    info!(
        "nativeRun: model={model} user_input_len={} history_json_len={} tools_json_len={} has_system_prompt={}",
        user_input.len(),
        history_json.len(),
        tools_json.len(),
        !system_prompt.is_empty(),
    );

    // ── Promote callbacks to global refs (safe across async threads) ───────────
    let jvm = match env.get_java_vm() {
        Ok(jvm) => Arc::new(jvm),
        Err(e) => {
            error!("nativeRun: failed to get JavaVM: {e:?}");
            return env
                .new_string("Error: could not obtain JavaVM")
                .map(|s| s.into_raw())
                .unwrap_or(std::ptr::null_mut());
        }
    };

    let token_cb_global = match env.new_global_ref(token_cb) {
        Ok(r) => r,
        Err(e) => {
            error!("nativeRun: failed to create global ref for tokenCb: {e:?}");
            return env
                .new_string("Error: could not pin tokenCb")
                .map(|s| s.into_raw())
                .unwrap_or(std::ptr::null_mut());
        }
    };

    let tool_cb_global = match env.new_global_ref(tool_cb) {
        Ok(r) => r,
        Err(e) => {
            error!("nativeRun: failed to create global ref for toolCb: {e:?}");
            return env
                .new_string("Error: could not pin toolCb")
                .map(|s| s.into_raw())
                .unwrap_or(std::ptr::null_mut());
        }
    };

    // ── Run agent loop on the shared tokio runtime ─────────────────────────────────────────
    let result = RT.block_on(async move {
        let tools: Vec<serde_json::Value> =
            serde_json::from_str(&tools_json).unwrap_or_else(|e| {
                error!("nativeRun: failed to parse tools_json: {e}");
                vec![]
            });

        let history: Vec<serde_json::Value> =
            serde_json::from_str(&history_json).unwrap_or_else(|e| {
                error!("nativeRun: failed to parse history_json: {e}");
                vec![]
            });

        // Build the three components and wire them together.
        let provider = AnthropicProvider::new(api_key, model, tools, system_prompt);
        let orchestrator = SimpleOrchestrator::new(jvm, token_cb_global, tool_cb_global);
        let ctx = SimpleContext::new(history, user_input);

        // Execute the multi-turn agent loop.
        orchestrator.run(&provider, ctx.to_messages()).await
    });

    info!("nativeRun: result_len={}", result.len());

    // Return the result string to Kotlin.  If we can't even allocate a Java
    // string we return null — Kotlin must treat null as an error sentinel.
    env.new_string(result)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

// ─────────────────────────────────────────── Helpers ──────────────────────────────────────

/// Extract a Rust `String` from a JNI `JString`.
///
/// Returns an empty string on failure so the caller always gets a valid value
/// rather than a panic.  Logs a warning with the parameter name on error.
fn jni_string(env: &mut JNIEnv, s: &JString, name: &str) -> String {
    env.get_string(s)
        .map(|js| js.into())
        .unwrap_or_else(|e| {
            error!("jni_string: failed to read JString for '{name}': {e:?}");
            String::new()
        })
}
