// context.rs — build_inherited_context with depth/scope filtering
//
// Mirrors the Python tool-delegate context building logic:
// filters parent messages by depth/scope and serialises as
// a [PARENT CONVERSATION CONTEXT] block.

use serde_json::Value;
use std::collections::HashSet;

// ---------------------------------------------------------------------------
// Public types
// ---------------------------------------------------------------------------

/// Controls how many messages to include.
#[derive(Debug, Clone, PartialEq)]
pub enum ContextDepth {
    /// Include no context at all.
    None,
    /// Include recent N turns (uses the `turns` parameter of
    /// `build_inherited_context`).
    Recent(usize),
    /// Include all available messages.
    All,
}

/// Controls which message types to include.
#[derive(Debug, Clone, PartialEq)]
pub enum ContextScope {
    /// Only conversational text (user text / assistant text).
    Conversation,
    /// Conversation + tool results from agent delegate calls.
    Agents,
    /// Everything — all user / assistant messages including raw tool results.
    Full,
}

// ---------------------------------------------------------------------------
// Public entry point
// ---------------------------------------------------------------------------

/// Build the `[PARENT CONVERSATION CONTEXT]` block from `messages`.
///
/// Returns `None` when:
/// - `depth == ContextDepth::None`
/// - the filtered message list is empty
pub fn build_inherited_context(
    messages: &[Value],
    depth: ContextDepth,
    turns: usize,
    scope: ContextScope,
) -> Option<String> {
    // 1. None depth → skip entirely.
    if depth == ContextDepth::None {
        return None;
    }

    // 1b. Compute the window size.
    let max_messages: usize = match depth {
        ContextDepth::None => unreachable!(),
        ContextDepth::Recent(_) => turns.saturating_mul(2),
        ContextDepth::All => usize::MAX,
    };

    // 2. Pre-compute agent tool-call IDs (needed for Agents scope).
    let agent_tool_ids = find_agent_tool_call_ids(messages);

    // 3. Filter messages by scope.
    let filtered: Vec<&Value> = messages
        .iter()
        .filter(|msg| include_in_scope(msg, &scope, &agent_tool_ids))
        .collect();

    // 4. Empty → nothing to emit.
    if filtered.is_empty() {
        return None;
    }

    // 5. Apply depth window — take the *last* max_messages.
    let windowed: &[&Value] = if max_messages >= filtered.len() {
        &filtered
    } else {
        &filtered[filtered.len() - max_messages..]
    };

    // 6. Serialise into the block.
    let mut lines: Vec<String> = Vec::new();
    lines.push("[PARENT CONVERSATION CONTEXT]".to_string());
    lines.push(
        "The following is recent conversation history from the parent session:".to_string(),
    );
    lines.push(String::new());

    for msg in windowed {
        let role = msg.get("role").and_then(|r| r.as_str()).unwrap_or("");
        let text = extract_text_content(msg, &scope, &agent_tool_ids);
        if text.is_empty() {
            continue;
        }

        match role {
            "user" => {
                let truncated = truncate_chars(&text, 2000);
                lines.push(format!("USER: {}", truncated));
                lines.push(String::new());
            }
            "assistant" => {
                lines.push(format!("ASSISTANT: {}", text));
                lines.push(String::new());
            }
            _ => {}
        }
    }

    lines.push("[END PARENT CONTEXT]".to_string());

    Some(lines.join("\n"))
}

// ---------------------------------------------------------------------------
// Private helpers
// ---------------------------------------------------------------------------

/// Collect the `id` of every `tool_use` block in assistant messages whose
/// `name` is one of the agent-delegation tool names.
fn find_agent_tool_call_ids(messages: &[Value]) -> HashSet<String> {
    const AGENT_NAMES: &[&str] = &["delegate", "task", "spawn_agent"];
    let mut ids = HashSet::new();

    for msg in messages {
        if msg.get("role").and_then(|r| r.as_str()) != Some("assistant") {
            continue;
        }
        if let Some(blocks) = msg.get("content").and_then(|c| c.as_array()) {
            for block in blocks {
                if block.get("type").and_then(|t| t.as_str()) != Some("tool_use") {
                    continue;
                }
                let name = block.get("name").and_then(|n| n.as_str()).unwrap_or("");
                if AGENT_NAMES.contains(&name) {
                    if let Some(id) = block.get("id").and_then(|i| i.as_str()) {
                        ids.insert(id.to_string());
                    }
                }
            }
        }
    }

    ids
}

/// Returns `true` if the message is a conversational turn (user text or
/// assistant text), as opposed to a pure tool-invocation message.
///
/// - user : non-empty string  OR  array with at least one non-`tool_result` block
/// - assistant : non-empty string  OR  array with at least one `text` block
fn is_conversation_message(msg: &Value, role: &str) -> bool {
    let Some(content) = msg.get("content") else {
        return false;
    };

    match role {
        "user" => {
            if let Some(s) = content.as_str() {
                !s.is_empty()
            } else if let Some(blocks) = content.as_array() {
                blocks
                    .iter()
                    .any(|b| b.get("type").and_then(|t| t.as_str()) != Some("tool_result"))
            } else {
                false
            }
        }
        "assistant" => {
            if let Some(s) = content.as_str() {
                !s.is_empty()
            } else if let Some(blocks) = content.as_array() {
                blocks
                    .iter()
                    .any(|b| b.get("type").and_then(|t| t.as_str()) == Some("text"))
            } else {
                false
            }
        }
        _ => false,
    }
}

/// Decide whether `msg` passes the scope filter.
fn include_in_scope(msg: &Value, scope: &ContextScope, agent_tool_ids: &HashSet<String>) -> bool {
    let role = msg.get("role").and_then(|r| r.as_str()).unwrap_or("");

    match scope {
        ContextScope::Conversation => is_conversation_message(msg, role),

        ContextScope::Agents => {
            if is_conversation_message(msg, role) {
                return true;
            }
            // Also include user-role messages whose tool_result tool_use_id is in agent_tool_ids.
            if role == "user" {
                if let Some(blocks) = msg.get("content").and_then(|c| c.as_array()) {
                    return blocks.iter().any(|b| {
                        if b.get("type").and_then(|t| t.as_str()) == Some("tool_result") {
                            let id = b
                                .get("tool_use_id")
                                .and_then(|i| i.as_str())
                                .unwrap_or("");
                            return agent_tool_ids.contains(id);
                        }
                        false
                    });
                }
            }
            false
        }

        ContextScope::Full => matches!(role, "user" | "assistant"),
    }
}

/// Extract displayable text from a message, honouring the scope rules.
pub fn extract_text_content(
    msg: &Value,
    scope: &ContextScope,
    agent_tool_ids: &HashSet<String>,
) -> String {
    let Some(content) = msg.get("content") else {
        return String::new();
    };

    // Plain string content.
    if let Some(s) = content.as_str() {
        return s.to_string();
    }

    // Array of content blocks.
    if let Some(blocks) = content.as_array() {
        let mut parts: Vec<String> = Vec::new();

        for block in blocks {
            let block_type = block.get("type").and_then(|t| t.as_str()).unwrap_or("");

            match block_type {
                "text" => {
                    if let Some(text) = block.get("text").and_then(|t| t.as_str()) {
                        if !text.is_empty() {
                            parts.push(text.to_string());
                        }
                    }
                }
                "tool_result" => match scope {
                    ContextScope::Full => {
                        let output = get_tool_result_output(block);
                        let truncated = truncate_chars(&output, 4000);
                        parts.push(format!("[tool_result: {}]", truncated));
                    }
                    ContextScope::Agents => {
                        let id = block
                            .get("tool_use_id")
                            .and_then(|i| i.as_str())
                            .unwrap_or("");
                        if agent_tool_ids.contains(id) {
                            let output = get_tool_result_output(block);
                            let truncated = truncate_chars(&output, 4000);
                            parts.push(format!("[agent_result: {}]", truncated));
                        }
                    }
                    ContextScope::Conversation => {
                        // Skip tool_result blocks entirely.
                    }
                },
                _ => {} // ignore tool_use, images, etc.
            }
        }

        return parts.join("\n");
    }

    String::new()
}

/// Pull the textual output from a `tool_result` content block.
fn get_tool_result_output(block: &Value) -> String {
    if let Some(content) = block.get("content") {
        if let Some(s) = content.as_str() {
            return s.to_string();
        }
        if let Some(sub_blocks) = content.as_array() {
            let parts: Vec<String> = sub_blocks
                .iter()
                .filter_map(|b| {
                    if b.get("type").and_then(|t| t.as_str()) == Some("text") {
                        b.get("text")
                            .and_then(|t| t.as_str())
                            .map(|s| s.to_string())
                    } else {
                        None
                    }
                })
                .collect();
            return parts.join("\n");
        }
    }
    String::new()
}

/// Truncate `s` to at most `max_chars` Unicode scalar values, appending
/// `...[truncated]` if truncation occurred.
pub fn truncate_chars(s: &str, max_chars: usize) -> String {
    let char_count = s.chars().count();
    if char_count <= max_chars {
        s.to_string()
    } else {
        let head: String = s.chars().take(max_chars).collect();
        format!("{}...[truncated]", head)
    }
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    // -----------------------------------------------------------------------
    // Test helpers
    // -----------------------------------------------------------------------

    /// Generic turn helper: creates a message with any role and string content.
    fn turn(role: &str, text: &str) -> Value {
        json!({ "role": role, "content": text })
    }

    fn user_msg(text: &str) -> Value {
        json!({ "role": "user", "content": text })
    }

    fn asst_msg(text: &str) -> Value {
        json!({ "role": "assistant", "content": text })
    }

    /// A user message that is purely a `tool_result` block.
    fn tool_result_msg(tool_use_id: &str, output: &str) -> Value {
        json!({
            "role": "user",
            "content": [
                {
                    "type": "tool_result",
                    "tool_use_id": tool_use_id,
                    "content": output
                }
            ]
        })
    }

    /// An assistant message containing a single `tool_use` block.
    fn asst_tool_use_msg(id: &str, name: &str) -> Value {
        json!({
            "role": "assistant",
            "content": [
                {
                    "type": "tool_use",
                    "id": id,
                    "name": name,
                    "input": {}
                }
            ]
        })
    }

    // -----------------------------------------------------------------------
    // 1. depth_none_returns_none
    // -----------------------------------------------------------------------
    #[test]
    fn depth_none_returns_none() {
        let msgs = vec![turn("user", "hi"), turn("assistant", "hello")];
        let result = build_inherited_context(
            &msgs,
            ContextDepth::None,
            5,
            ContextScope::Conversation,
        );
        assert!(result.is_none(), "expected None for ContextDepth::None");
    }

    // -----------------------------------------------------------------------
    // 2. empty_messages_returns_none
    // -----------------------------------------------------------------------
    #[test]
    fn empty_messages_returns_none() {
        let result = build_inherited_context(&[], ContextDepth::All, 5, ContextScope::Conversation);
        assert!(result.is_none(), "expected None for empty message list");
    }

    // -----------------------------------------------------------------------
    // 3. output_contains_correct_header_and_footer
    // -----------------------------------------------------------------------
    #[test]
    fn output_contains_correct_header_and_footer() {
        let messages = vec![user_msg("hi")];
        let result =
            build_inherited_context(&messages, ContextDepth::All, 5, ContextScope::Conversation)
                .expect("should produce output");
        assert!(
            result.contains("[PARENT CONVERSATION CONTEXT]"),
            "missing header"
        );
        assert!(
            result.contains("The following is recent conversation history from the parent session:"),
            "missing subtitle"
        );
        assert!(result.contains("[END PARENT CONTEXT]"), "missing footer");
    }

    // -----------------------------------------------------------------------
    // 4. output_formats_user_and_assistant_roles
    // -----------------------------------------------------------------------
    #[test]
    fn output_formats_user_and_assistant_roles() {
        let messages = vec![user_msg("Hello there"), asst_msg("Hello back")];
        let result =
            build_inherited_context(&messages, ContextDepth::All, 5, ContextScope::Conversation)
                .expect("should produce output");
        assert!(result.contains("USER: Hello there"), "USER prefix missing");
        assert!(
            result.contains("ASSISTANT: Hello back"),
            "ASSISTANT prefix missing"
        );
    }

    // -----------------------------------------------------------------------
    // 5. recent_depth_limits_to_n_turns
    //    6 messages (3 turns); Recent(_) + turns=1 → max_messages=2 → last turn only
    // -----------------------------------------------------------------------
    #[test]
    fn recent_depth_limits_to_n_turns() {
        let messages = vec![
            user_msg("turn1-user"),
            asst_msg("turn1-asst"),
            user_msg("turn2-user"),
            asst_msg("turn2-asst"),
            user_msg("turn3-user"),
            asst_msg("turn3-asst"),
        ];
        let result = build_inherited_context(
            &messages,
            ContextDepth::Recent(0), // inner value ignored; `turns` param drives it
            1,                        // turns=1 → max_messages=2
            ContextScope::Conversation,
        )
        .expect("should produce output");

        // Only turn 3 should appear.
        assert!(result.contains("turn3"), "should contain turn3");
        assert!(!result.contains("turn1"), "should NOT contain turn1");
        assert!(!result.contains("turn2"), "should NOT contain turn2");
    }

    // -----------------------------------------------------------------------
    // 6. user_content_truncated_at_2000_chars
    // -----------------------------------------------------------------------
    #[test]
    fn user_content_truncated_at_2000_chars() {
        let long_text: String = "a".repeat(3000);
        let messages = vec![user_msg(&long_text)];
        let result =
            build_inherited_context(&messages, ContextDepth::All, 5, ContextScope::Conversation)
                .expect("should produce output");
        assert!(
            result.contains("...[truncated]"),
            "expected truncation marker"
        );
        // The displayed user content must not exceed 2000 chars + prefix + marker.
        // Simple check: "a"*2001 must not appear.
        assert!(
            !result.contains(&"a".repeat(2001)),
            "content should be truncated to ≤2000 chars before marker"
        );
    }

    // -----------------------------------------------------------------------
    // 7. conversation_scope_excludes_tool_result_messages
    // -----------------------------------------------------------------------
    #[test]
    fn conversation_scope_excludes_tool_result_messages() {
        let messages = vec![
            user_msg("question"),
            asst_msg("answer"),
            tool_result_msg("tool_123", "tool output"),
        ];
        let result = build_inherited_context(
            &messages,
            ContextDepth::All,
            5,
            ContextScope::Conversation,
        )
        .expect("should produce output");
        assert!(
            !result.contains("tool output"),
            "tool_result should be excluded in Conversation scope"
        );
    }

    // -----------------------------------------------------------------------
    // 8. agents_scope_includes_delegate_tool_results
    //    delegate tool_use + matching tool_result → preserved as [agent_result: ...]
    // -----------------------------------------------------------------------
    #[test]
    fn agents_scope_includes_delegate_tool_results() {
        let messages = vec![
            user_msg("run agent"),
            asst_tool_use_msg("call_abc", "delegate"),
            tool_result_msg("call_abc", "agent output here"),
        ];
        let result =
            build_inherited_context(&messages, ContextDepth::All, 5, ContextScope::Agents)
                .expect("should produce output");
        assert!(
            result.contains("[agent_result:"),
            "delegate result should appear as [agent_result: ...]"
        );
        assert!(
            result.contains("agent output here"),
            "agent output text should be present"
        );
    }

    // -----------------------------------------------------------------------
    // 9. agents_scope_excludes_non_delegate_tool_results
    //    bash tool_use + bash tool_result → excluded from Agents scope
    // -----------------------------------------------------------------------
    #[test]
    fn agents_scope_excludes_non_delegate_tool_results() {
        let messages = vec![
            user_msg("run bash"),
            asst_tool_use_msg("bash_xyz", "bash"),
            tool_result_msg("bash_xyz", "bash output here"),
        ];
        let result =
            build_inherited_context(&messages, ContextDepth::All, 5, ContextScope::Agents)
                .expect("should produce output");
        assert!(
            !result.contains("bash output here"),
            "bash result should be excluded in Agents scope"
        );
        // The conversational messages should still be present.
        assert!(result.contains("run bash"), "user text should be present");
    }

    // -----------------------------------------------------------------------
    // 10. full_scope_includes_tool_results
    // -----------------------------------------------------------------------
    #[test]
    fn full_scope_includes_tool_results() {
        let messages = vec![
            user_msg("question"),
            asst_tool_use_msg("t1", "bash"),
            tool_result_msg("t1", "bash result text"),
        ];
        let result =
            build_inherited_context(&messages, ContextDepth::All, 5, ContextScope::Full)
                .expect("should produce output");
        assert!(
            result.contains("[tool_result:"),
            "Full scope should include tool_result blocks"
        );
        assert!(
            result.contains("bash result text"),
            "Full scope should include tool_result content"
        );
    }

    // -----------------------------------------------------------------------
    // 11. all_depth_includes_all_messages
    // -----------------------------------------------------------------------
    #[test]
    fn all_depth_includes_all_messages() {
        let messages: Vec<Value> = (1..=5)
            .flat_map(|i| {
                vec![
                    user_msg(&format!("user-{}", i)),
                    asst_msg(&format!("asst-{}", i)),
                ]
            })
            .collect();
        let result =
            build_inherited_context(&messages, ContextDepth::All, 1, ContextScope::Conversation)
                .expect("should produce output");
        // All 5 turns should be present.
        for i in 1..=5 {
            assert!(
                result.contains(&format!("user-{}", i)),
                "user-{} missing",
                i
            );
            assert!(
                result.contains(&format!("asst-{}", i)),
                "asst-{} missing",
                i
            );
        }
    }

    // -----------------------------------------------------------------------
    // 12. assistant_content_not_truncated
    //     Only USER content is truncated at 2000; assistant content is not.
    // -----------------------------------------------------------------------
    #[test]
    fn assistant_content_not_truncated() {
        let long_text: String = "b".repeat(3000);
        let messages = vec![asst_msg(&long_text)];
        let result =
            build_inherited_context(&messages, ContextDepth::All, 5, ContextScope::Conversation)
                .expect("should produce output");
        // No truncation marker should appear for assistant content.
        assert!(
            !result.contains("...[truncated]"),
            "assistant content should NOT be truncated"
        );
        assert!(
            result.contains(&"b".repeat(3000)),
            "assistant full content should be present"
        );
    }
}
