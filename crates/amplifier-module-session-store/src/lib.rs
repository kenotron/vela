pub mod file;
pub mod format;

use async_trait::async_trait;
use format::{SessionEvent, SessionMetadata};

// ---------------------------------------------------------------------------
// SessionStore trait
// ---------------------------------------------------------------------------

/// Persistent store for session transcripts and metadata.
///
/// Implementations must be `Send + Sync` so they can be shared across async
/// tasks.  See [`file::FileSessionStore`] for the filesystem-backed implementation.
#[async_trait]
pub trait SessionStore: Send + Sync {
    /// Creates the session directory, writes the `session_start` event to the
    /// transcript, and appends an entry to the global index.
    async fn begin(&self, session_id: &str, metadata: SessionMetadata) -> anyhow::Result<()>;

    /// Appends a single event to the session's JSONL transcript.
    async fn append_event(&self, session_id: &str, event: SessionEvent) -> anyhow::Result<()>;

    /// Finalises a session by recording a `session_end` event and updating
    /// the index entry's `status`.
    async fn finish(&self, session_id: &str, status: &str) -> anyhow::Result<()>;

    /// Looks up the metadata for a session by ID.
    async fn find(&self, session_id: &str) -> anyhow::Result<Option<SessionMetadata>>;

    /// Returns all indexed sessions.
    async fn list(&self) -> anyhow::Result<Vec<SessionMetadata>>;

    /// Returns `true` if the session directory already exists on disk.
    fn exists(&self, session_id: &str) -> bool;
}
