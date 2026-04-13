    //! SimpleContext — assembles the Anthropic messages array for one agent turn.
    //!
    //! Holds prior-turn history (already in Anthropic `{role, content}` shape) and
    //! the current user input. `to_messages()` appends the new user turn before
    //! handing the full array to the provider.

    use serde_json::{json, Value};

    /// Carries the conversation history and the current user turn.
    pub struct SimpleContext {
        /// All previous turns in Anthropic message format:
        /// `[{"role": "user"|"assistant", "content": "..." | [{type, ...}]}, ...]`
        history: Vec<Value>,
        /// The user's current (latest) message text.
        user_input: String,
    }

    impl SimpleContext {
        /// Create a context from raw history and the new user message.
        ///
        /// # Arguments
        /// * `history`    – Prior turns deserialized from `historyJson` (may be empty).
        /// * `user_input` – The current user message text.
        pub fn new(history: Vec<Value>, user_input: String) -> Self {
            Self { history, user_input }
        }

        /// Return the complete messages array ready to send to the Anthropic API.
        ///
        /// Appends a `{"role": "user", "content": user_input}` entry after the
        /// existing history so the model sees the full conversation in order.
        pub fn to_messages(&self) -> Vec<Value> {
            let mut messages = self.history.clone();
            messages.push(json!({
                "role": "user",
                "content": self.user_input,
            }));
            messages
        }
    }

    #[cfg(test)]
    mod tests {
        use super::*;
        use serde_json::json;

        #[test]
        fn empty_history_produces_single_user_message() {
            let ctx = SimpleContext::new(vec![], "Hello".to_string());
            let msgs = ctx.to_messages();
            assert_eq!(msgs.len(), 1);
            assert_eq!(msgs[0]["role"], "user");
            assert_eq!(msgs[0]["content"], "Hello");
        }

        #[test]
        fn history_is_prepended_before_user_turn() {
            let history = vec![
                json!({"role": "user", "content": "Hi"}),
                json!({"role": "assistant", "content": "Hey!"}),
            ];
            let ctx = SimpleContext::new(history, "Follow-up".to_string());
            let msgs = ctx.to_messages();
            assert_eq!(msgs.len(), 3);
            assert_eq!(msgs[2]["role"], "user");
            assert_eq!(msgs[2]["content"], "Follow-up");
        }

        #[test]
        fn history_is_not_mutated() {
            let history = vec![json!({"role": "user", "content": "First"})];
            let ctx = SimpleContext::new(history.clone(), "Second".to_string());
            let msgs = ctx.to_messages();
            // Original history vec unchanged; messages contains one extra entry
            assert_eq!(msgs.len(), 2);
        }
    }
    