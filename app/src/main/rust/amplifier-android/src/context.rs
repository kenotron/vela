//! SimpleContext — in-memory implementation of the amplifier-core
//! [`ContextManager`] trait.
//!
//! Holds the full conversation history as a `Vec<Value>` in
//! **amplifier-core message format** (not raw Anthropic wire format).
//! See `lib.rs` for the conversion from Anthropic history → core format.

use std::future::Future;
use std::pin::Pin;
use std::sync::{Arc, Mutex};

use amplifier_core::{ContextError, Provider};
use amplifier_core::traits::ContextManager;
use serde_json::Value;

/// In-memory context manager — stores messages for one agent session.
///
/// Thread-safe: wraps the message list in `Mutex` so the async orchestrator
/// can add messages from any task without data races.
pub struct SimpleContext {
    messages: Arc<Mutex<Vec<Value>>>,
}

impl SimpleContext {
    /// Create a context pre-loaded with conversation history.
    ///
    /// `history` must already be in **amplifier-core message format**
    /// (the lib.rs entry point converts from Anthropic DB format before
    /// calling this constructor).
    pub fn new(history: Vec<Value>) -> Self {
        Self {
            messages: Arc::new(Mutex::new(history)),
        }
    }
}

// ──────────────────────────── ContextManager impl ────────────────────────────

impl ContextManager for SimpleContext {
    /// Append a message to the context history.
    ///
    /// The push is synchronous (just a `Mutex::lock` + `push`); the returned
    /// future resolves immediately with `Ok(())`.
    fn add_message(
        &self,
        message: Value,
    ) -> Pin<Box<dyn Future<Output = Result<(), ContextError>> + Send + '_>> {
        self.messages.lock().expect("context mutex poisoned").push(message);
        Box::pin(std::future::ready(Ok(())))
    }

    /// Return all messages, compacted if necessary.
    ///
    /// This simple implementation returns all messages unchanged.  A production
    /// implementation would truncate to fit the provider's context window.
    fn get_messages_for_request(
        &self,
        _token_budget: Option<i64>,
        _provider: Option<Arc<dyn Provider>>,
    ) -> Pin<Box<dyn Future<Output = Result<Vec<Value>, ContextError>> + Send + '_>> {
        let msgs = self
            .messages
            .lock()
            .expect("context mutex poisoned")
            .clone();
        Box::pin(std::future::ready(Ok(msgs)))
    }

    /// Return all messages (raw, uncompacted) for inspection / debugging.
    fn get_messages(
        &self,
    ) -> Pin<Box<dyn Future<Output = Result<Vec<Value>, ContextError>> + Send + '_>> {
        let msgs = self
            .messages
            .lock()
            .expect("context mutex poisoned")
            .clone();
        Box::pin(std::future::ready(Ok(msgs)))
    }

    /// Replace the entire message list (e.g. for session resume).
    fn set_messages(
        &self,
        messages: Vec<Value>,
    ) -> Pin<Box<dyn Future<Output = Result<(), ContextError>> + Send + '_>> {
        *self.messages.lock().expect("context mutex poisoned") = messages;
        Box::pin(std::future::ready(Ok(())))
    }

    /// Clear all messages from the context.
    fn clear(&self) -> Pin<Box<dyn Future<Output = Result<(), ContextError>> + Send + '_>> {
        self.messages.lock().expect("context mutex poisoned").clear();
        Box::pin(std::future::ready(Ok(())))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[tokio::test]
    async fn empty_history_starts_empty() {
        let ctx = SimpleContext::new(vec![]);
        let msgs = ctx.get_messages().await.unwrap();
        assert!(msgs.is_empty());
    }

    #[tokio::test]
    async fn pre_loaded_history_is_returned() {
        let history = vec![
            json!({"role": "user", "content": "Hi"}),
            json!({"role": "assistant", "content": "Hello!"}),
        ];
        let ctx = SimpleContext::new(history.clone());
        let msgs = ctx.get_messages().await.unwrap();
        assert_eq!(msgs.len(), 2);
        assert_eq!(msgs[0]["content"], "Hi");
    }

    #[tokio::test]
    async fn add_message_appends_in_order() {
        let ctx = SimpleContext::new(vec![]);
        ctx.add_message(json!({"role": "user", "content": "First"}))
            .await
            .unwrap();
        ctx.add_message(json!({"role": "assistant", "content": "Second"}))
            .await
            .unwrap();
        let msgs = ctx.get_messages().await.unwrap();
        assert_eq!(msgs.len(), 2);
        assert_eq!(msgs[1]["content"], "Second");
    }

    #[tokio::test]
    async fn clear_removes_all_messages() {
        let ctx = SimpleContext::new(vec![
            json!({"role": "user", "content": "msg"}),
        ]);
        ctx.clear().await.unwrap();
        let msgs = ctx.get_messages().await.unwrap();
        assert!(msgs.is_empty());
    }

    #[tokio::test]
    async fn set_messages_replaces_history() {
        let ctx = SimpleContext::new(vec![
            json!({"role": "user", "content": "old"}),
        ]);
        let new_msgs = vec![
            json!({"role": "user", "content": "new"}),
            json!({"role": "assistant", "content": "response"}),
        ];
        ctx.set_messages(new_msgs.clone()).await.unwrap();
        let msgs = ctx.get_messages().await.unwrap();
        assert_eq!(msgs.len(), 2);
        assert_eq!(msgs[0]["content"], "new");
    }
}
