use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

/// A single resolved model candidate.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct Candidate {
    pub provider: String,
    pub model: String,
    #[serde(default, skip_serializing_if = "serde_json::Value::is_null")]
    pub config: serde_json::Value,
}

/// Configuration for a single model role, as stored in a matrix / override file.
///
/// `candidates` holds raw JSON values because during composition the special
/// sentinel string `"base"` is allowed in override files.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct RoleConfig {
    pub description: String,
    pub candidates: Vec<serde_json::Value>,
}

/// Map of role-name → role configuration.
///
/// `BTreeMap` is intentional: deterministic iteration order is required for
/// tests and for generating stable prompt output.
pub type RolesMap = BTreeMap<String, RoleConfig>;

/// Top-level structure of a matrix YAML file.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MatrixConfig {
    pub name: String,
    #[serde(default)]
    pub description: String,
    #[serde(default)]
    pub updated: String,
    pub roles: RolesMap,
}

// ---------------------------------------------------------------------------
// Validation
// ---------------------------------------------------------------------------

/// Validate a `RolesMap` from either a full matrix file or an override file.
///
/// Rules:
/// - `is_override = false` (matrix file): both `general` and `fast` roles must exist.
/// - Every role: non-empty `description` and non-empty `candidates`.
/// - `is_override = false`: literal `"base"` candidates are rejected.
/// - `is_override = true`: `"base"` is allowed; `general`/`fast` not required.
pub fn validate_matrix(roles: &RolesMap, is_override: bool) -> anyhow::Result<()> {
    if !is_override {
        if !roles.contains_key("general") {
            anyhow::bail!("matrix must contain a 'general' role");
        }
        if !roles.contains_key("fast") {
            anyhow::bail!("matrix must contain a 'fast' role");
        }
    }

    for (name, role) in roles {
        if role.description.is_empty() {
            anyhow::bail!("role '{}' has an empty description", name);
        }
        if role.candidates.is_empty() {
            anyhow::bail!("role '{}' has empty candidates", name);
        }
        if !is_override {
            for candidate in &role.candidates {
                if candidate.as_str() == Some("base") {
                    anyhow::bail!(
                        "role '{}' contains a 'base' sentinel candidate, which is not allowed in a matrix file",
                        name
                    );
                }
            }
        }
    }

    Ok(())
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/// Convert a raw JSON value from a `candidates` list into a `Candidate`.
///
/// Returns `None` if the value is the `"base"` sentinel string, or if
/// deserialisation fails.
pub fn candidate_from_value(v: &serde_json::Value) -> Option<Candidate> {
    if v.as_str() == Some("base") {
        return None;
    }
    serde_json::from_value(v.clone()).ok()
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn make_role(description: &str, candidates: Vec<serde_json::Value>) -> RoleConfig {
        RoleConfig {
            description: description.to_string(),
            candidates,
        }
    }

    fn minimal_candidate() -> serde_json::Value {
        json!({"provider": "openai", "model": "gpt-4o"})
    }

    // 1. Happy path: a minimal valid matrix (general + fast) should pass.
    #[test]
    fn validate_passes_for_minimal_valid_matrix() {
        let mut roles = RolesMap::new();
        roles.insert(
            "general".into(),
            make_role("General purpose", vec![minimal_candidate()]),
        );
        roles.insert(
            "fast".into(),
            make_role("Fast responses", vec![minimal_candidate()]),
        );
        assert!(validate_matrix(&roles, false).is_ok());
    }

    // 2. Missing 'general' role must be rejected with a message mentioning "general".
    #[test]
    fn validate_rejects_missing_general() {
        let mut roles = RolesMap::new();
        roles.insert(
            "fast".into(),
            make_role("Fast responses", vec![minimal_candidate()]),
        );
        let err = validate_matrix(&roles, false).unwrap_err();
        assert!(
            err.to_string().contains("general"),
            "expected 'general' in error: {err}"
        );
    }

    // 3. Missing 'fast' role must be rejected with a message mentioning "fast".
    #[test]
    fn validate_rejects_missing_fast() {
        let mut roles = RolesMap::new();
        roles.insert(
            "general".into(),
            make_role("General purpose", vec![minimal_candidate()]),
        );
        let err = validate_matrix(&roles, false).unwrap_err();
        assert!(
            err.to_string().contains("fast"),
            "expected 'fast' in error: {err}"
        );
    }

    // 4. A "base" sentinel in a non-override matrix must be rejected; error mentions "base".
    #[test]
    fn validate_rejects_base_in_matrix_file() {
        let mut roles = RolesMap::new();
        roles.insert(
            "general".into(),
            make_role("General purpose", vec![json!("base")]),
        );
        roles.insert(
            "fast".into(),
            make_role("Fast responses", vec![minimal_candidate()]),
        );
        let err = validate_matrix(&roles, false).unwrap_err();
        assert!(
            err.to_string().contains("base"),
            "expected 'base' in error: {err}"
        );
    }

    // 5. A "base" sentinel in an override file must be allowed.
    #[test]
    fn validate_allows_base_in_override() {
        let mut roles = RolesMap::new();
        roles.insert(
            "general".into(),
            make_role("General purpose", vec![json!("base")]),
        );
        assert!(validate_matrix(&roles, true).is_ok());
    }

    // 6. Empty candidates Vec must be rejected; error mentions "empty candidates".
    #[test]
    fn validate_rejects_empty_candidates() {
        let mut roles = RolesMap::new();
        roles.insert("general".into(), make_role("General purpose", vec![]));
        roles.insert(
            "fast".into(),
            make_role("Fast responses", vec![minimal_candidate()]),
        );
        let err = validate_matrix(&roles, false).unwrap_err();
        assert!(
            err.to_string().contains("empty candidates"),
            "expected 'empty candidates' in error: {err}"
        );
    }

    // 7. candidate_from_value: "base" → None; valid object → Some(Candidate).
    #[test]
    fn candidate_from_value_skips_base_sentinel() {
        assert_eq!(candidate_from_value(&json!("base")), None);

        let v = json!({"provider": "anthropic", "model": "claude-3-5-sonnet-20241022"});
        let result = candidate_from_value(&v);
        assert!(result.is_some());
        let c = result.unwrap();
        assert_eq!(c.provider, "anthropic");
        assert_eq!(c.model, "claude-3-5-sonnet-20241022");
    }
}
