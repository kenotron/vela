// file.rs — FileSessionStore: JSONL transcript store backed by the local filesystem.
//
// Layout:
//   `{root}/{session_id}/events.jsonl` — per-session JSONL event transcript
//   `{root}/index.jsonl`               — global flat index of all sessions

use std::path::{Path, PathBuf};
use tokio::sync::Mutex;

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

#[cfg(test)]
mod tests {
    use super::*;
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
}
