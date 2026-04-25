use serde::{Deserialize, Serialize};

// ---------------------------------------------------------------------------
// SessionEvent
// ---------------------------------------------------------------------------

/// A single event recorded to the session transcript.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "type")]
pub enum SessionEvent {
    /// Marks the beginning of a session.
    #[serde(rename = "session_start")]
    SessionStart {
        session_id: String,
        #[serde(skip_serializing_if = "Option::is_none")]
        parent_id: Option<String>,
        agent_name: String,
        timestamp: String,
    },

    /// A conversation turn (user or assistant message).
    #[serde(rename = "turn")]
    Turn {
        role: String,
        content: String,
        timestamp: String,
    },

    /// A tool invocation and its result.
    #[serde(rename = "tool_call")]
    ToolCall {
        tool: String,
        args: serde_json::Value,
        result: String,
        timestamp: String,
    },

    /// Marks the end of a session.
    #[serde(rename = "session_end")]
    SessionEnd {
        status: String,
        turn_count: u32,
        timestamp: String,
    },
}

// ---------------------------------------------------------------------------
// SessionMetadata / IndexEntry
// ---------------------------------------------------------------------------

/// Metadata persisted alongside a session transcript and used in the index.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct SessionMetadata {
    pub session_id: String,
    pub agent_name: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub parent_id: Option<String>,
    pub created: String,
    /// `"active"` while running; `"success"` or `"error"` after finish.
    pub status: String,
}

/// Alias for [`SessionMetadata`] used in the flat session index.
pub type IndexEntry = SessionMetadata;

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn session_start_round_trip() {
        let event = SessionEvent::SessionStart {
            session_id: "abc".to_string(),
            parent_id: Some("root".to_string()),
            agent_name: "explorer".to_string(),
            timestamp: "2024-01-01T00:00:00Z".to_string(),
        };
        let json = serde_json::to_string(&event).expect("serialize");
        assert!(json.contains(r#""type":"session_start""#), "missing type discriminator: {json}");
        assert!(json.contains(r#""session_id":"abc""#), "missing session_id: {json}");
        let back: SessionEvent = serde_json::from_str(&json).expect("deserialize");
        assert_eq!(back, event);
    }

    #[test]
    fn turn_round_trip() {
        let event = SessionEvent::Turn {
            role: "user".to_string(),
            content: "hello".to_string(),
            timestamp: "2024-01-01T00:00:01Z".to_string(),
        };
        let json = serde_json::to_string(&event).expect("serialize");
        assert!(json.contains(r#""type":"turn""#), "missing type discriminator: {json}");
        let back: SessionEvent = serde_json::from_str(&json).expect("deserialize");
        assert_eq!(back, event);
    }

    #[test]
    fn tool_call_round_trip() {
        let event = SessionEvent::ToolCall {
            tool: "bash".to_string(),
            args: serde_json::json!({"command": "ls"}),
            result: "file1\nfile2".to_string(),
            timestamp: "2024-01-01T00:00:02Z".to_string(),
        };
        let json = serde_json::to_string(&event).expect("serialize");
        assert!(json.contains(r#""type":"tool_call""#), "missing type discriminator: {json}");
        let back: SessionEvent = serde_json::from_str(&json).expect("deserialize");
        assert_eq!(back, event);
    }

    #[test]
    fn session_end_round_trip() {
        let event = SessionEvent::SessionEnd {
            status: "success".to_string(),
            turn_count: 5,
            timestamp: "2024-01-01T00:00:03Z".to_string(),
        };
        let json = serde_json::to_string(&event).expect("serialize");
        assert!(json.contains(r#""type":"session_end""#), "missing type discriminator: {json}");
        let back: SessionEvent = serde_json::from_str(&json).expect("deserialize");
        assert_eq!(back, event);
    }

    #[test]
    fn index_entry_round_trip() {
        let entry = IndexEntry {
            session_id: "sess-1".to_string(),
            agent_name: "explorer".to_string(),
            parent_id: None,
            created: "2024-01-01T00:00:00Z".to_string(),
            status: "active".to_string(),
        };
        let json = serde_json::to_string(&entry).expect("serialize");
        assert!(json.contains(r#""session_id":"sess-1""#), "missing session_id: {json}");
        assert!(json.contains(r#""agent_name":"explorer""#), "missing agent_name: {json}");
        // parent_id: None should be omitted
        assert!(!json.contains("parent_id"), "parent_id should be omitted when None: {json}");
        let back: IndexEntry = serde_json::from_str(&json).expect("deserialize");
        assert_eq!(back, entry);
    }
}
