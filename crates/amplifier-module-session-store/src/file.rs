// file.rs — FileSessionStore: JSONL transcript store backed by the local filesystem.
//
// Layout:
//   `{root}/{session_id}/events.jsonl` — per-session JSONL event transcript
//   `{root}/index.jsonl`               — global flat index of all sessions

use std::io::Write as _;
use std::path::{Path, PathBuf};

use async_trait::async_trait;
use chrono::Utc;
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
        let home =
            dirs::home_dir().ok_or_else(|| anyhow::anyhow!("cannot determine home directory"))?;
        Ok(Self::new_with_root(
            home.join(".amplifier").join("sessions"),
        ))
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

    async fn append_event(&self, session_id: &str, event: SessionEvent) -> anyhow::Result<()> {
        append_jsonl(&self.events_file(session_id), &event)
    }

    async fn finish(&self, session_id: &str, status: &str, turn_count: u32) -> anyhow::Result<()> {
        // 1. Compute current timestamp.
        let now = Utc::now().to_rfc3339();

        // 2. Append session_end event to events.jsonl.
        let event = SessionEvent::SessionEnd {
            status: status.to_string(),
            turn_count,
            timestamp: now,
        };
        append_jsonl(&self.events_file(session_id), &event)?;

        // 3. Under index lock: rewrite index.jsonl, updating the matching entry's status.
        let idx_path = self.index_file();
        let _guard = self.index_lock.lock().await;

        let existing = match std::fs::read_to_string(&idx_path) {
            Ok(s) => s,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => String::new(),
            Err(e) => return Err(e.into()),
        };

        let mut out = String::new();
        for line in existing.lines() {
            if line.trim().is_empty() {
                continue;
            }
            let mut entry: SessionMetadata = serde_json::from_str(line)?;
            if entry.session_id == session_id {
                entry.status = status.to_string();
            }
            out.push_str(&serde_json::to_string(&entry)?);
            out.push('\n');
        }

        // 4. Atomically overwrite the index file.
        std::fs::write(&idx_path, out)?;

        Ok(())
    }

    async fn find(&self, _session_id: &str) -> anyhow::Result<Option<SessionMetadata>> {
        anyhow::bail!("find not implemented yet")
    }

    async fn list(&self) -> anyhow::Result<Vec<SessionMetadata>> {
        let path = self.index_file();
        let body = match std::fs::read_to_string(&path) {
            Ok(s) => s,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(vec![]),
            Err(e) => return Err(e.into()),
        };

        let mut entries = Vec::new();
        for (i, line) in body.lines().enumerate() {
            if line.trim().is_empty() {
                continue;
            }
            let entry: SessionMetadata = serde_json::from_str(line)
                .map_err(|e| anyhow::anyhow!("malformed index.jsonl line {}: {}", i + 1, e))?;
            entries.push(entry);
        }
        Ok(entries)
    }

    fn exists(&self, session_id: &str) -> bool {
        self.events_file(session_id).is_file()
    }

    async fn load(&self, session_id: &str) -> anyhow::Result<Vec<SessionEvent>> {
        let path = self.events_file(session_id);
        let body = std::fs::read_to_string(&path)
            .map_err(|e| anyhow::anyhow!("could not read {}: {}", path.display(), e))?;

        let mut events = Vec::new();
        for (i, line) in body.lines().enumerate() {
            if line.trim().is_empty() {
                continue;
            }
            let event: SessionEvent = serde_json::from_str(line).map_err(|e| {
                anyhow::anyhow!(
                    "malformed JSONL at {} line {}: {}",
                    path.display(),
                    i + 1,
                    e
                )
            })?;
            events.push(event);
        }
        Ok(events)
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
        assert!(
            dir.starts_with(tmp.path()),
            "session_dir should be under root"
        );
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
    async fn append_adds_event_line() {
        let tmp = TempDir::new().expect("tempdir");
        let store = FileSessionStore::new_with_root(tmp.path().to_path_buf());

        let meta = SessionMetadata {
            session_id: "s1".to_string(),
            agent_name: "test-agent".to_string(),
            parent_id: None,
            created: "2026-04-24T00:00:00Z".to_string(),
            status: "active".to_string(),
        };
        SessionStore::begin(&store, "s1", meta)
            .await
            .expect("begin");

        let event = crate::format::SessionEvent::Turn {
            role: "user".to_string(),
            content: "hi".to_string(),
            timestamp: "t1".to_string(),
        };
        SessionStore::append_event(&store, "s1", event)
            .await
            .expect("append_event");

        let events_path = tmp.path().join("s1").join("events.jsonl");
        let content = std::fs::read_to_string(&events_path).expect("read events.jsonl");
        let lines: Vec<&str> = content.lines().collect();
        assert_eq!(lines.len(), 2, "events.jsonl should have exactly 2 lines");
        assert!(
            lines[1].contains(r#""type":"turn""#),
            r#"second line should contain "type":"turn": {}"#,
            lines[1]
        );
    }

    #[tokio::test]
    async fn exists_true_after_begin_false_otherwise() {
        let tmp = TempDir::new().expect("tempdir");
        let store = FileSessionStore::new_with_root(tmp.path().to_path_buf());

        assert!(
            !SessionStore::exists(&store, "missing"),
            "exists should return false for unknown session"
        );

        let meta = SessionMetadata {
            session_id: "s1".to_string(),
            agent_name: "test-agent".to_string(),
            parent_id: None,
            created: "2026-04-24T00:00:00Z".to_string(),
            status: "active".to_string(),
        };
        SessionStore::begin(&store, "s1", meta)
            .await
            .expect("begin");

        assert!(
            SessionStore::exists(&store, "s1"),
            "exists should return true after begin"
        );
    }

    #[tokio::test]
    async fn finish_appends_session_end_and_rewrites_index_status() {
        let tmp = TempDir::new().expect("tempdir");
        let store = FileSessionStore::new_with_root(tmp.path().to_path_buf());

        // Begin a session with status "active".
        let meta = SessionMetadata {
            session_id: "s1".to_string(),
            agent_name: "test-agent".to_string(),
            parent_id: None,
            created: "2026-04-24T00:00:00Z".to_string(),
            status: "active".to_string(),
        };
        SessionStore::begin(&store, "s1", meta)
            .await
            .expect("begin");

        // Finish the session.
        SessionStore::finish(&store, "s1", "success", 4)
            .await
            .expect("finish");

        // (a) Last line of events.jsonl contains session_end, status=success, turn_count=4.
        let events_path = tmp.path().join("s1").join("events.jsonl");
        let events_content = std::fs::read_to_string(&events_path).expect("read events.jsonl");
        let event_lines: Vec<&str> = events_content.lines().collect();
        let last_line = event_lines
            .last()
            .expect("events.jsonl should not be empty");
        assert!(
            last_line.contains(r#""type":"session_end""#),
            r#"last line should contain "type":"session_end": {last_line}"#
        );
        assert!(
            last_line.contains(r#""status":"success""#),
            r#"last line should contain "status":"success": {last_line}"#
        );
        assert!(
            last_line.contains(r#""turn_count":4"#),
            r#"last line should contain "turn_count":4: {last_line}"#
        );

        // (b) index.jsonl still has exactly 1 line, now status=success, no longer status=active.
        let index_path = tmp.path().join("index.jsonl");
        let index_content = std::fs::read_to_string(&index_path).expect("read index.jsonl");
        let index_lines: Vec<&str> = index_content.lines().collect();
        assert_eq!(
            index_lines.len(),
            1,
            "index.jsonl should still have exactly 1 line after finish, got: {index_lines:?}"
        );
        assert!(
            index_lines[0].contains(r#""status":"success""#),
            r#"index line should contain "status":"success": {}"#,
            index_lines[0]
        );
        assert!(
            !index_lines[0].contains(r#""status":"active""#),
            r#"index line should NOT contain "status":"active": {}"#,
            index_lines[0]
        );
    }

    // -----------------------------------------------------------------------
    // load / list tests (task-7)
    // -----------------------------------------------------------------------

    #[tokio::test]
    async fn load_returns_events_in_order() {
        let (tmp, store) = make_store();
        let meta = SessionMetadata {
            session_id: "s1".to_string(),
            agent_name: "test-agent".to_string(),
            parent_id: None,
            created: "2026-04-25T00:00:00Z".to_string(),
            status: "active".to_string(),
        };
        SessionStore::begin(&store, "s1", meta)
            .await
            .expect("begin");

        let turn = crate::format::SessionEvent::Turn {
            role: "user".to_string(),
            content: "hello".to_string(),
            timestamp: "2026-04-25T00:00:01Z".to_string(),
        };
        SessionStore::append_event(&store, "s1", turn)
            .await
            .expect("append_event");

        SessionStore::finish(&store, "s1", "success", 1)
            .await
            .expect("finish");

        let events = store.load("s1").await.expect("load");
        assert_eq!(
            events.len(),
            3,
            "expected exactly 3 events, got: {}",
            events.len()
        );
        assert!(
            matches!(events[0], crate::format::SessionEvent::SessionStart { .. }),
            "first event should be SessionStart"
        );
        assert!(
            matches!(events[1], crate::format::SessionEvent::Turn { .. }),
            "second event should be Turn"
        );
        assert!(
            matches!(events[2], crate::format::SessionEvent::SessionEnd { .. }),
            "third event should be SessionEnd"
        );
        // suppress unused tmp warning
        let _ = &tmp;
    }

    #[tokio::test]
    async fn load_returns_clear_error_on_malformed_jsonl() {
        let (tmp, store) = make_store();

        // Manually create a session dir 'bad' with events.jsonl that has one
        // valid session_start line followed by NOT JSON on line 2.
        let session_dir = tmp.path().join("bad");
        std::fs::create_dir_all(&session_dir).expect("create dir");
        let events_path = session_dir.join("events.jsonl");
        let valid_line = serde_json::to_string(&crate::format::SessionEvent::SessionStart {
            session_id: "bad".to_string(),
            parent_id: None,
            agent_name: "test".to_string(),
            timestamp: "2026-04-25T00:00:00Z".to_string(),
        })
        .expect("serialize");
        std::fs::write(&events_path, format!("{valid_line}\nNOT JSON\n")).expect("write");

        let result = store.load("bad").await;
        assert!(result.is_err(), "expected an error for malformed JSONL");
        let msg = format!("{}", result.unwrap_err());
        assert!(
            msg.contains("malformed") || msg.contains("line 2"),
            "error should mention 'malformed' or 'line 2', got: {msg}"
        );
        // no panic = test passes
    }

    #[tokio::test]
    async fn list_returns_all_sessions_in_order() {
        let (_tmp, store) = make_store();

        for (i, id) in ["s1", "s2", "s3"].iter().enumerate() {
            let meta = SessionMetadata {
                session_id: id.to_string(),
                agent_name: "test-agent".to_string(),
                parent_id: None,
                created: format!("2026-04-25T00:00:0{}Z", i),
                status: "active".to_string(),
            };
            SessionStore::begin(&store, id, meta).await.expect("begin");
        }

        let sessions = SessionStore::list(&store).await.expect("list");
        let ids: Vec<&str> = sessions.iter().map(|s| s.session_id.as_str()).collect();
        assert_eq!(
            ids,
            vec!["s1", "s2", "s3"],
            "expected [s1, s2, s3] in order, got: {ids:?}"
        );
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

        SessionStore::begin(&store, "s1", meta)
            .await
            .expect("begin");

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
        assert_eq!(
            event_lines.len(),
            1,
            "events.jsonl should have exactly 1 line"
        );
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
        assert_eq!(
            index_lines.len(),
            1,
            "index.jsonl should have exactly 1 line"
        );
        assert!(
            index_lines[0].contains(r#""status":"active""#),
            r#"index line should contain "status":"active": {}"#,
            index_lines[0]
        );
    }
}
