//! KotlinHookBridge — implements the amplifier-module-orchestrator-loop-streaming [`Hook`] trait
//! by delegating lifecycle events back to a Kotlin `HookCallback` via JNI.
//!
//! ## JNI call path
//!
//! ```text
//! KotlinHookBridge::handle(ctx)
//!   → attach current thread to JVM
//!   → call HookCallback.handleHook(eventName, contextJson)
//!   → parse returned JSON {"action":"…","context_injection":"…"}
//!   → return mapped HookResult
//! ```

use std::sync::Arc;

use amplifier_module_orchestrator_loop_streaming::{Hook, HookContext, HookEvent, HookResult};
use async_trait::async_trait;
use jni::objects::{GlobalRef, JString, JValue};
use jni::JavaVM;
use log::warn;
use serde_json::Value;

// ───────────────────────────────── KotlinHookBridge ─────────────────────────

/// A lifecycle hook backed by a Kotlin `HookCallback.handleHook(event, ctx)` method.
pub struct KotlinHookBridge {
    /// Global reference to the Kotlin `HookCallback` object.
    callback: Arc<GlobalRef>,
    /// The subset of lifecycle events this bridge forwards to Kotlin.
    events: Vec<HookEvent>,
    /// Shared JVM handle — used to attach worker threads for JNI calls.
    jvm: Arc<JavaVM>,
}

impl KotlinHookBridge {
    /// Construct a bridge wrapping the given Kotlin callback object.
    pub fn new(callback: Arc<GlobalRef>, events: Vec<HookEvent>, jvm: Arc<JavaVM>) -> Self {
        Self { callback, events, jvm }
    }

    /// Call `HookCallback.handleHook(eventStr, ctxJson)` on the Kotlin side.
    ///
    /// Returns the raw JSON string returned by Kotlin, or `None` on any JNI failure.
    fn call_kotlin(&self, event_str: &str, ctx_json: &str) -> Option<String> {
        let mut env = match self.jvm.attach_current_thread() {
            Ok(e) => e,
            Err(e) => {
                warn!("KotlinHookBridge: failed to attach JVM thread: {e:?}");
                return None;
            }
        };

        let j_event = match env.new_string(event_str) {
            Ok(s) => s,
            Err(e) => {
                warn!("KotlinHookBridge: new_string(event) failed: {e:?}");
                return None;
            }
        };

        let j_ctx = match env.new_string(ctx_json) {
            Ok(s) => s,
            Err(e) => {
                warn!("KotlinHookBridge: new_string(ctx) failed: {e:?}");
                return None;
            }
        };

        let call_result = env.call_method(
            self.callback.as_ref(),
            "handleHook",
            "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
            &[JValue::Object(&j_event), JValue::Object(&j_ctx)],
        );

        match call_result {
            Ok(v) => match v.l() {
                Ok(obj) => {
                    if obj.is_null() {
                        return None;
                    }
                    let jstr = JString::from(obj);
                    // Use a local binding so JavaStr is dropped before jstr.
                    let result = env
                        .get_string(&jstr)
                        .ok()
                        .map(|s| String::from(s));
                    if result.is_none() {
                        warn!("KotlinHookBridge: get_string failed");
                    }
                    result
                }
                Err(e) => {
                    let _ = env.exception_clear();
                    warn!("KotlinHookBridge: JValue→Object failed: {e:?}");
                    None
                }
            },
            Err(e) => {
                warn!("KotlinHookBridge: handleHook threw: {e:?}");
                let _ = env.exception_clear();
                None
            }
        }
    }
}

// ────────────────────────────────── Hook impl ───────────────────────────────

#[async_trait]
impl Hook for KotlinHookBridge {
    fn events(&self) -> &[HookEvent] {
        &self.events
    }

    async fn handle(&self, ctx: &HookContext) -> HookResult {
        let event_str = format!("{:?}", ctx.event);
        let ctx_json = serde_json::to_string(&ctx.data).unwrap_or_else(|_| "{}".to_string());

        let json_str = match self.call_kotlin(&event_str, &ctx_json) {
            None => return HookResult::Continue,
            Some(s) => s,
        };

        let v: Value = match serde_json::from_str(&json_str) {
            Ok(v) => v,
            Err(e) => {
                warn!("KotlinHookBridge: failed to parse handleHook response: {e:?}");
                return HookResult::Continue;
            }
        };

        let action = v
            .get("action")
            .and_then(|a| a.as_str())
            .unwrap_or("continue");

        let payload = v
            .get("context_injection")
            .and_then(|s| s.as_str())
            .unwrap_or("")
            .to_string();

        match action {
            "continue" => HookResult::Continue,
            "inject_context" => HookResult::InjectContext(payload),
            "system_prompt_addendum" => HookResult::SystemPromptAddendum(payload),
            "deny" => HookResult::Deny(payload),
            other => {
                warn!("KotlinHookBridge: unknown action '{}', returning Continue", other);
                HookResult::Continue
            }
        }
    }
}

// ────────────────────────────── parse_hook_events ───────────────────────────

/// Parse a list of event name strings (case-insensitive) into [`HookEvent`] values.
///
/// Unknown names are warned about and skipped.
///
/// | String              | HookEvent                   |
/// |---------------------|-----------------------------|
/// | `"session_start"`   | `HookEvent::SessionStart`   |
/// | `"provider_request"`| `HookEvent::ProviderRequest`|
/// | `"tool_pre"`        | `HookEvent::ToolPre`        |
/// | `"tool_post"`       | `HookEvent::ToolPost`       |
/// | `"turn_end"`        | `HookEvent::TurnEnd`        |
pub fn parse_hook_events(event_names: &[String]) -> Vec<HookEvent> {
    // RED phase stub — returns empty to make tests fail initially.
    // Implementation follows immediately below.
    event_names
        .iter()
        .filter_map(|name| match name.to_lowercase().as_str() {
            "session_start" => Some(HookEvent::SessionStart),
            "provider_request" => Some(HookEvent::ProviderRequest),
            "tool_pre" => Some(HookEvent::ToolPre),
            "tool_post" => Some(HookEvent::ToolPost),
            "turn_end" => Some(HookEvent::TurnEnd),
            other => {
                warn!("parse_hook_events: unknown event name '{}', skipping", other);
                None
            }
        })
        .collect()
}

// ────────────────────────────────── Tests ───────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_hook_events_all_known_names() {
        let names = vec![
            "session_start".to_string(),
            "provider_request".to_string(),
            "tool_pre".to_string(),
            "tool_post".to_string(),
            "turn_end".to_string(),
        ];
        let events = parse_hook_events(&names);
        assert_eq!(events.len(), 5);
        assert!(events.contains(&HookEvent::SessionStart));
        assert!(events.contains(&HookEvent::ProviderRequest));
        assert!(events.contains(&HookEvent::ToolPre));
        assert!(events.contains(&HookEvent::ToolPost));
        assert!(events.contains(&HookEvent::TurnEnd));
    }

    #[test]
    fn parse_hook_events_unknown_names_are_skipped() {
        let names = vec![
            "unknown_event".to_string(),
            "session_start".to_string(),
            "session_end".to_string(), // no such variant — skipped
        ];
        let events = parse_hook_events(&names);
        assert_eq!(events.len(), 1);
        assert!(events.contains(&HookEvent::SessionStart));
    }

    #[test]
    fn parse_hook_events_case_insensitive() {
        let names = vec![
            "SESSION_START".to_string(),
            "Provider_Request".to_string(),
            "TOOL_PRE".to_string(),
        ];
        let events = parse_hook_events(&names);
        assert_eq!(events.len(), 3);
        assert!(events.contains(&HookEvent::SessionStart));
        assert!(events.contains(&HookEvent::ProviderRequest));
        assert!(events.contains(&HookEvent::ToolPre));
    }

    #[test]
    fn parse_hook_events_empty_input_returns_empty() {
        let events = parse_hook_events(&[]);
        assert!(events.is_empty());
    }

    #[test]
    fn parse_hook_events_all_unknown_returns_empty() {
        let names = vec!["foo".to_string(), "bar".to_string()];
        let events = parse_hook_events(&names);
        assert!(events.is_empty());
    }
}
