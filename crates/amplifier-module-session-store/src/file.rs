// file.rs — FileSessionStore: JSONL transcript store backed by the local filesystem.
//
// Layout:
//   `{root}/{session_id}/events.jsonl` — per-session JSONL event transcript
//   `{root}/index.jsonl`               — global flat index of all sessions

use std::io::Write as _;
use std::path::{Path, PathBuf};

use async_trait::async_trait;
use tokio::sync::Mutex;

use crate::format::{SessionEvent, SessionMetadata};
use crate::SessionStore;

/// Filesystem-backed session store that writes JSONL transcripts.
///
/// # Storage layout
///
/// ```text
/// {root}/
///   {session_id}/
///     events.jsonl      <- per-session JSONL event transcript
///   index.jsonl         <- global flat index of all sessions
/// ```
///
/// Use [`FileSessionStore::new`] to root the store at the conventional
/// `~/.amplifier/sessions` path, or [`FileSessionStore::new_with_root`]
/// to supply a custom root (useful for tests).
pub struct FileSessionStore {
    root: PathBuf,
    /// Serialises concurrent writes to `index.jsonl`.
    index_lock: Mutex<()>,
}

impl FileSessionStore {
    /// Creates a new `FileSessionStore` rooted at `~/.amplifier/sessions`.
    ///
    /// Returns an error if the home directory cannot be determined.
    pub fn new() -> anyhow::Result<Self> {
        let home = dirs::home_dir()
            .ok_or_else(|| anyhow::anyhow!("cannot determine home directory"))?;
        Ok(Self::new_with_root(home.join(".amplifier").join("sessions")))
    }

    /// Creates a new `FileSessionStore` rooted at the given `root` path.
    ///
    /// Prefer [`FileSessionStore::new`] in production; use this constructor
    /// in tests to supply a temporary directory.
    pub fn new_with_root(root: PathBuf) -> Self {
        Self {
            root,
            index_lock: Mutex::new(()),
        }
    }

    /// Returns the root directory of this store.
    pub fn root(&self) -> &Path {
        &self.root
    }

    /// Returns the directory that holds all files for `session_id`.
    ///
    /// The JSONL transcript lives at `{root}/{session_id}/events.jsonl`.
    pub fn session_dir(&self, session_id: &str) -> PathBuf {
        self.root.join(session_id)
    }

    /// Returns the path to the JSONL event transcript for `session_id`.
    ///
    /// Path: `{root}/{session_id}/events.jsonl`.
    pub fn events_file(&self, session_id: &str) -> PathBuf {
        self.session_dir(session_id).join("events.jsonl")
    }

    /// Returns the path to the global session index.
    ///
    /// Path: `{root}/index.jsonl`.
    pub fn index_file(&self) -> PathBuf {
        self.root.join("index.jsonl")
    }
}

// ---------------------------------------------------------------------------
// Helper
// ---------------------------------------------------------------------------

/// Opens `path` in create-or-append mode, writes `value` as a single JSON
/// line (no trailing whitespace), and flushes.
///
/// Parent directories are created automatically.
fn append_jsonl<T: serde::Serialize>(path: &Path, value: &T) -> anyhow::Result<()> {
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    let mut file = std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(path)?;
    let line = serde_json::to_string(value)?;
    writeln!(file, "{line}")?;
    file.flush()?;
    Ok(())
}

// ---------------------------------------------------------------------------
// SessionStore impl
// ---------------------------------------------------------------------------

#[async_trait]
impl SessionStore for FileSessionStore {
    /// Creates the session directory, appends a `session_start` event to the
    /// transcript, then (under the index lock) appends the metadata to the
    /// global index file.
    async fn begin(&self, session_id: &str, metadata: SessionMetadata) -> anyhow::Result<()> {
        // 1. Ensure the session directory exists.
        std::fs::create_dir_all(self.session_dir(session_id))?;

        // 2. Write the session_start event to events.jsonl.
        let event = SessionEvent::SessionStart {
            session_id: session_id.to_string(),
            parent_id: metadata.parent_id.clone(),
            agent_name: metadata.agent_name.clone(),
            timestamp: metadata.created.clone(),
        };
        append_jsonl(&self.events_file(session_id), &event)?;

        // 3. Serialise index writes and append the metadata entry.
        let _guard = self.index_lock.lock().await;
        append_jsonl(&self.index_file(), &metadata)?;

        Ok(())
    }

    async fn append_event(&self, _session_id: &str, _event: SessionEvent) -> anyhow::Result<()> {
        anyhow::bail!("append_event not implemented yet")
    }

    async fn finish(&self, _session_id: &str, _status: &str) -> anyhow::Result<()> {
        anyhow::bail!("finish not implemented yet")
    }

    async fn find(&self, _session_id: &str) -> anyhow::Result<Option<SessionMetadata>> {
        anyhow::bail!("find not implemented yet")
    }

    async fn list(&self) -> anyhow::Result<Vec<SessionMetadata>> {
        anyhow::bail!("list not implemented yet")
    }

    fn exists(&self, _session_id: &str) -> bool {
        false
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::format::SessionMetadata;
    use crate::SessionStore;
    use tempfile::TempDir;

    fn make_store() -> (TempDir, FileSessionStore) {
        let tmp = TempDir::new().expect("tempdir");
        let store = FileSessionStore::new_with_root(tmp.path().to_path_buf());
        (tmp, store)
    }

    #[test]
    fn new_with_root_uses_supplied_path() {
        let (tmp, store) = make_store();
        assert_eq!(store.root(), tmp.path());
    }

    #[test]
    fn session_dir_path_is_under_root() {
        let (tmp, store) = make_store();
        let dir = store.session_dir("abc-123");
        assert!(dir.starts_with(tmp.path()), "session_dir should be under root");
        assert!(
            dir.ends_with("abc-123"),
            "session_dir should end with session_id, got: {dir:?}"
        );
    }

    #[test]
    fn events_file_path_ends_with_events_jsonl() {
        let (_tmp, store) = make_store();
        let path = store.events_file("abc");
        assert!(
            path.ends_with("events.jsonl"),
            "events_file path should end with events.jsonl, got: {path:?}"
        );
    }

    #[test]
    fn index_file_is_root_index_jsonl() {
        let (tmp, store) = make_store();
        assert_eq!(store.index_file(), tmp.path().join("index.jsonl"));
    }

    #[tokio::test]
    async fn begin_creates_session_dir_writes_session_start_and_index() {
        let tmp = TempDir::new().expect("tempdir");
        let store = FileSessionStore::new_with_root(tmp.path().to_path_buf());

        let meta = SessionMetadata {
            session_id: "s1".to_string(),
            agent_name: "explorer".to_string(),
            parent_id: Some("root".to_string()),
            created: "2026-04-24T00:00:00Z".to_string(),
            status: "active".to_string(),
        };

        SessionStore::begin(&store, "s1", meta).await.expect("begin");

        // (a) session directory exists
        assert!(
            tmp.path().join("s1").is_dir(),
            "session directory s1 should exist"
        );

        // (b) events.jsonl has exactly 1 line containing session_start and agent_name
        let events_path = tmp.path().join("s1").join("events.jsonl");
        assert!(events_path.exists(), "events.jsonl should exist");
        let events_content = std::fs::read_to_string(&events_path).expect("read events.jsonl");
        let event_lines: Vec<&str> = events_content.lines().collect();
        assert_eq!(event_lines.len(), 1, "events.jsonl should have exactly 1 line");
        assert!(
            event_lines[0].contains(r#""type":"session_start""#),
            r#"line should contain "type":"session_start": {}"#,
            event_lines[0]
        );
        assert!(
            event_lines[0].contains(r#""agent_name":"explorer""#),
            r#"line should contain "agent_name":"explorer": {}"#,
            event_lines[0]
        );

        // (c) index.jsonl has exactly 1 line containing status="active"
        let index_path = tmp.path().join("index.jsonl");
        let index_content = std::fs::read_to_string(&index_path).expect("read index.jsonl");
        let index_lines: Vec<&str> = index_content.lines().collect();
        assert_eq!(index_lines.len(), 1, "index.jsonl should have exactly 1 line");
        assert!(
            index_lines[0].contains(r#""status":"active""#),
            r#"index line should contain "status":"active": {}"#,
            index_lines[0]
        );
    }
}
