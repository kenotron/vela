// Property-based tests for build_inherited_context.
//
// Three invariants verified with 256 cases each (proptest default):
// 1. Never panics for ContextDepth::All + ContextScope::Full
// 2. Never panics for ContextDepth::Recent(n) + ContextScope::Conversation
// 3. If it returns Some(s), then s is non-empty

use amplifier_module_tool_delegate::context::build_inherited_context;
use amplifier_module_tool_delegate::{ContextDepth, ContextScope};
use proptest::prelude::*;
use serde_json::{json, Value};

// ---------------------------------------------------------------------------
// Strategy helpers
// ---------------------------------------------------------------------------

/// Generate a single message Value with a role from a fixed set or random
/// lowercase string, and a content string of 0-64 characters.
fn arb_message() -> impl Strategy<Value = Value> {
    let arb_role = prop_oneof![
        Just("user".to_string()),
        Just("assistant".to_string()),
        Just("system".to_string()),
        "[a-z]{1,8}",
    ];
    let arb_content = ".{0,64}";
    (arb_role, arb_content).prop_map(|(role, content)| json!({ "role": role, "content": content }))
}

// ---------------------------------------------------------------------------
// Property tests
// ---------------------------------------------------------------------------

proptest! {
    /// build_inherited_context must not panic for any vec of 0..32 messages
    /// when depth=All and scope=Full.
    #[test]
    fn build_inherited_context_never_panics_full(
        msgs in prop::collection::vec(arb_message(), 0..32)
    ) {
        let _ = build_inherited_context(&msgs, ContextDepth::All, 0, ContextScope::Full);
    }

    /// build_inherited_context must not panic for any vec of 0..32 messages
    /// and any n in 0..16 when depth=Recent(n) and scope=Conversation.
    #[test]
    fn build_inherited_context_never_panics_recent(
        msgs in prop::collection::vec(arb_message(), 0..32),
        n in 0usize..16
    ) {
        let _ = build_inherited_context(
            &msgs,
            ContextDepth::Recent(n),
            n,
            ContextScope::Conversation,
        );
    }

    /// If build_inherited_context returns Some(s), then s must not be empty.
    #[test]
    fn build_inherited_context_some_implies_non_empty(
        msgs in prop::collection::vec(arb_message(), 0..32)
    ) {
        if let Some(s) = build_inherited_context(&msgs, ContextDepth::All, 0, ContextScope::Full) {
            prop_assert!(!s.is_empty());
        }
    }
}
