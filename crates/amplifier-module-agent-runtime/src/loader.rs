//! Directory loader — walks a directory and loads all agent bundle files into
//! an [`AgentRegistry`].

use std::path::{Path, PathBuf};
use crate::{parser, AgentRegistry};

/// Load all agent bundle files from `dir` into `registry`.
///
/// Returns the number of agents successfully loaded.
/// Non-existent directories are not an error — they return `Ok(0)`.
pub fn load_from_dir(registry: &mut AgentRegistry, dir: &Path) -> anyhow::Result<usize> {
    if !dir.is_dir() {
        return Ok(0);
    }

    let entries = std::fs::read_dir(dir)
        .map_err(|e| anyhow::anyhow!("failed to read directory {}: {}", dir.display(), e))?;

    let mut count = 0;

    for entry_result in entries {
        let entry = match entry_result {
            Ok(e) => e,
            Err(_) => continue,
        };

        let path = entry.path();

        // Only process .md files.
        if path.extension().and_then(|ext| ext.to_str()) != Some("md") {
            continue;
        }

        // Read file content — skip silently on failure.
        let content = match std::fs::read_to_string(&path) {
            Ok(c) => c,
            Err(_) => continue,
        };

        // Parse the agent file — skip silently on error.
        let config = match parser::parse_agent_file(&content) {
            Ok(c) => c,
            Err(_) => continue,
        };

        registry.register(config);
        count += 1;
    }

    Ok(count)
}

/// Return the default directories to search for agent bundle files.
///
/// - `{vault_path}/.agents`
/// - `$HOME/.amplifier/agents` (only if `$HOME` is set in the environment)
pub fn default_search_dirs(vault_path: &Path) -> Vec<PathBuf> {
    let mut dirs = Vec::new();
    dirs.push(vault_path.join(".agents"));
    if let Ok(home) = std::env::var("HOME") {
        dirs.push(PathBuf::from(home).join(".amplifier").join("agents"));
    }
    dirs
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    use std::path::PathBuf;
    use tempfile::TempDir;
    use crate::AgentRegistry;

    // ---- Fixtures -----------------------------------------------------------

    const EXPLORER_MD: &str = r#"---
meta:
  name: explorer
  description: "Explores the codebase deeply"
model_role: fast
tools:
  - filesystem
  - bash
---

You are an expert codebase explorer.
"#;

    const BUG_HUNTER_MD: &str = r#"---
meta:
  name: bug-hunter
  description: "Finds and fixes bugs systematically"
model_role: reasoning
tools:
  - bash
  - filesystem
---

You are a bug hunter expert.
"#;

    // ---- Tests --------------------------------------------------------------

    #[test]
    fn load_from_dir_loads_md_files() {
        let dir = TempDir::new().expect("create temp dir");
        fs::write(dir.path().join("explorer.md"), EXPLORER_MD).expect("write explorer.md");
        fs::write(dir.path().join("bug-hunter.md"), BUG_HUNTER_MD).expect("write bug-hunter.md");

        let mut registry = AgentRegistry::new();
        let count = load_from_dir(&mut registry, dir.path()).expect("load_from_dir should succeed");

        assert_eq!(count, 2, "expected 2 agents loaded");
        assert!(registry.get("explorer").is_some(), "explorer should be registered");
        assert!(registry.get("bug-hunter").is_some(), "bug-hunter should be registered");
    }

    #[test]
    fn load_from_dir_ignores_non_md_files() {
        let dir = TempDir::new().expect("create temp dir");
        fs::write(dir.path().join("explorer.md"), EXPLORER_MD).expect("write explorer.md");
        fs::write(dir.path().join("README.txt"), "just a readme").expect("write README.txt");
        fs::write(dir.path().join("config.yaml"), "key: value").expect("write config.yaml");

        let mut registry = AgentRegistry::new();
        let count = load_from_dir(&mut registry, dir.path()).expect("load_from_dir should succeed");

        assert_eq!(count, 1, "expected only 1 agent loaded (non-.md files ignored)");
        assert!(registry.get("explorer").is_some(), "explorer should be registered");
    }

    #[test]
    fn load_from_dir_skips_unparseable_files() {
        let dir = TempDir::new().expect("create temp dir");
        fs::write(dir.path().join("explorer.md"), EXPLORER_MD).expect("write explorer.md");
        // broken.md has no frontmatter delimiters — parser will return an error
        fs::write(
            dir.path().join("broken.md"),
            "This is just markdown without frontmatter.\n",
        )
        .expect("write broken.md");

        let mut registry = AgentRegistry::new();
        let count = load_from_dir(&mut registry, dir.path()).expect("load_from_dir should succeed");

        assert_eq!(count, 1, "expected 1 agent loaded (broken file skipped silently)");
        assert!(registry.get("explorer").is_some(), "explorer should be registered");
    }

    #[test]
    fn load_from_nonexistent_dir_returns_zero() {
        let mut registry = AgentRegistry::new();
        let nonexistent = PathBuf::from("/no/such/path/99999");
        let result = load_from_dir(&mut registry, &nonexistent);

        assert!(result.is_ok(), "nonexistent dir should not be an error");
        assert_eq!(result.unwrap(), 0, "should return 0 for nonexistent dir");
    }

    #[test]
    fn default_search_dirs_includes_vault_and_home() {
        let vault = PathBuf::from("/some/vault");
        let dirs = default_search_dirs(&vault);

        assert!(
            !dirs.is_empty(),
            "default_search_dirs should return at least one directory"
        );
        assert!(
            dirs.iter().any(|p| p.ends_with(".agents")),
            "at least one path should end with .agents, got: {:?}",
            dirs
        );
    }
}
