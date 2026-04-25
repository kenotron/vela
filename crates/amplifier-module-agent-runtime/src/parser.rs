//! Agent bundle file parser.
//!
//! Parses a `.md`-formatted agent file (YAML front-matter + Markdown body)
//! into an [`AgentConfig`].

use crate::{AgentConfig, ModelRole};
use anyhow::Context as _;
use serde::Deserialize;

// ---------------------------------------------------------------------------
// Internal YAML frontmatter structures
// ---------------------------------------------------------------------------

#[derive(Deserialize)]
struct AgentFrontmatter {
    meta: AgentMeta,
    model_role: Option<serde_json::Value>,
    tools: Option<Vec<serde_json::Value>>,
}

#[derive(Deserialize)]
struct AgentMeta {
    name: String,
    description: String,
}

// ---------------------------------------------------------------------------
// Public parse function
// ---------------------------------------------------------------------------

/// Parse the text content of an agent bundle file into [`AgentConfig`].
///
/// # Format
/// ```text
/// ---
/// meta:
///   name: my-agent
///   description: Does things
/// model_role: fast
/// tools:
///   - filesystem
///   - {module: some/path, source: git+https://...}
/// ---
/// System prompt text here.
/// ```
///
/// The body after the closing `---` becomes `AgentConfig::instruction`.
pub fn parse_agent_file(content: &str) -> anyhow::Result<AgentConfig> {
    // Strip UTF-8 BOM if present.
    let content = content.strip_prefix('\u{FEFF}').unwrap_or(content);

    // Find two `---` delimiter lines (trim()-aware).
    let lines: Vec<&str> = content.lines().collect();
    let mut delimiters: Vec<usize> = Vec::new();
    for (i, line) in lines.iter().enumerate() {
        if line.trim() == "---" {
            delimiters.push(i);
            if delimiters.len() == 2 {
                break;
            }
        }
    }
    if delimiters.len() < 2 {
        anyhow::bail!("missing frontmatter delimiters: file must contain two '---' lines");
    }

    let first = delimiters[0];
    let second = delimiters[1];

    // Extract YAML between the two delimiters.
    let yaml = lines[first + 1..second].join("\n");

    // Parse YAML into AgentFrontmatter.
    let frontmatter: AgentFrontmatter =
        serde_yaml::from_str(&yaml).context("invalid YAML frontmatter")?;

    // Body: everything after the second `---`, trimmed.
    let body_lines = &lines[second + 1..];
    let instruction = body_lines.join("\n").trim().to_string();

    // Normalize model_role.
    let model_role = normalize_model_role(frontmatter.model_role);

    // Normalize tools.
    let tools = frontmatter
        .tools
        .unwrap_or_default()
        .into_iter()
        .filter_map(normalize_tool_entry)
        .collect();

    Ok(AgentConfig {
        name: frontmatter.meta.name,
        description: frontmatter.meta.description,
        model_role,
        provider_preferences: None,
        tools,
        instruction,
    })
}

// ---------------------------------------------------------------------------
// Normalisation helpers
// ---------------------------------------------------------------------------

fn normalize_model_role(value: Option<serde_json::Value>) -> Option<ModelRole> {
    match value {
        None => None,
        Some(serde_json::Value::String(s)) => Some(ModelRole::Single(s)),
        Some(serde_json::Value::Array(arr)) => {
            let strings: Vec<String> = arr
                .into_iter()
                .filter_map(|v| v.as_str().map(|s| s.to_string()))
                .collect();
            if strings.is_empty() {
                None
            } else {
                Some(ModelRole::Chain(strings))
            }
        }
        _ => None,
    }
}

fn normalize_tool_entry(v: serde_json::Value) -> Option<String> {
    match v {
        serde_json::Value::String(s) => Some(s),
        serde_json::Value::Object(obj) => obj
            .get("module")
            .and_then(|m| m.as_str())
            .map(|s| s.to_string()),
        _ => None,
    }
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    // ---- Test fixtures -------------------------------------------------------

    const SIMPLE_AGENT: &str = r#"---
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

    const CHAIN_ROLE_AGENT: &str = r#"---
meta:
  name: chain-agent
  description: "Uses chain role"
model_role:
  - reasoning
  - general
---

You are a chain agent.
"#;

    const OBJECT_TOOLS_AGENT: &str = r#"---
meta:
  name: tool-agent
  description: "Uses object tools"
tools:
  - bash
  - module: some/tool/path
    source: git+https://example.com
---

You are a tool agent.
"#;

    const MINIMAL_AGENT: &str = r#"---
meta:
  name: minimal
  description: "A minimal agent with no extras"
---

You are a minimal agent.
"#;

    // ---- Tests ---------------------------------------------------------------

    #[test]
    fn parse_simple_agent_name_and_description() {
        let config = parse_agent_file(SIMPLE_AGENT).expect("should parse");
        assert_eq!(config.name, "explorer");
        assert_eq!(config.description, "Explores the codebase deeply");
    }

    #[test]
    fn parse_simple_agent_model_role_single() {
        let config = parse_agent_file(SIMPLE_AGENT).expect("should parse");
        assert_eq!(
            config.model_role,
            Some(ModelRole::Single("fast".to_string()))
        );
    }

    #[test]
    fn parse_chain_model_role() {
        let config = parse_agent_file(CHAIN_ROLE_AGENT).expect("should parse");
        assert_eq!(
            config.model_role,
            Some(ModelRole::Chain(vec![
                "reasoning".to_string(),
                "general".to_string()
            ]))
        );
    }

    #[test]
    fn parse_tools_as_strings() {
        let config = parse_agent_file(SIMPLE_AGENT).expect("should parse");
        assert_eq!(
            config.tools,
            vec!["filesystem".to_string(), "bash".to_string()]
        );
    }

    #[test]
    fn parse_tools_normalises_object_form() {
        let config = parse_agent_file(OBJECT_TOOLS_AGENT).expect("should parse");
        assert_eq!(
            config.tools,
            vec!["bash".to_string(), "some/tool/path".to_string()]
        );
    }

    #[test]
    fn parse_body_becomes_instruction() {
        let config = parse_agent_file(SIMPLE_AGENT).expect("should parse");
        assert!(
            config.instruction.contains("You are an expert"),
            "instruction was: {:?}",
            config.instruction
        );
    }

    #[test]
    fn parse_minimal_agent_defaults() {
        let config = parse_agent_file(MINIMAL_AGENT).expect("should parse");
        assert_eq!(config.model_role, None);
        assert!(config.tools.is_empty());
        assert!(
            config.instruction.contains("You are a minimal agent"),
            "instruction was: {:?}",
            config.instruction
        );
    }

    #[test]
    fn parse_returns_error_for_missing_frontmatter() {
        let content = "This is just markdown without any frontmatter delimiters.\n";
        let result = parse_agent_file(content);
        assert!(result.is_err(), "expected error for missing frontmatter");
    }

    #[test]
    fn parse_returns_error_for_missing_meta_name() {
        let content = r#"---
meta:
  description: "No name field"
---

Body text.
"#;
        let result = parse_agent_file(content);
        assert!(result.is_err(), "expected error for missing meta.name");
    }
}
