# Amplifier Rust Foundation — Phase 2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build three new provider crates (Gemini, OpenAI Responses API, Ollama), two new tool crates (web, todo), and wire Kotlin hooks through the Rust HookRegistry via a new `jni_hooks.rs` bridge.

**Architecture:** Each provider and tool crate is a self-contained member of the `amplifier-rust` workspace at `/Users/ken/workspace/amplifier-rust/`, implementing amplifier-core traits with no circular dependencies. The Android bridge (`amplifier-android`) gains a new `jni_hooks.rs` that wraps any Kotlin object implementing `HookCallback` into the `Hook` trait, allowing all existing Kotlin hooks to be registered with the Rust `HookRegistry` without changing their logic.

**Tech Stack:** Rust 2021 edition, reqwest 0.12 (rustls-tls), wiremock 0.6, serde_json 1, uuid 1, async-trait 0.1, tokio 1 (full), jni 0.21.

**Prerequisite:** Phase 1 is complete. The workspace at `/Users/ken/workspace/amplifier-rust/` exists and all five Phase 1 crates compile. `amplifier-android/src/orchestrator.rs`, `provider.rs`, and `context.rs` have been deleted; `lib.rs` has been rewritten to wire the Phase 1 workspace crates.

> **Trait signature note:** The `Provider` trait in amplifier-core does NOT use `async_trait`. It uses explicit `Pin<Box<dyn Future>>` boxing — the same pattern you see in the Phase 1 `amplifier-module-provider-anthropic`. Before implementing `complete()` in any new provider, open `/Users/ken/workspace/amplifier-rust/crates/amplifier-module-provider-anthropic/src/lib.rs` and copy the exact `impl Provider` method signature. The `Hook` trait (from `amplifier-module-orchestrator-loop-streaming`) DOES use `async_trait`.

---

## Task 1: Add 5 new crates to the workspace Cargo.toml

**Files:**
- Modify: `/Users/ken/workspace/amplifier-rust/Cargo.toml`

- [ ] **Step 1: Read the current workspace Cargo.toml**

```bash
cat /Users/ken/workspace/amplifier-rust/Cargo.toml
```

It currently lists five members. Add the five new ones.

- [ ] **Step 2: Add the new members to the `[workspace]` members array**

The complete `members` list should now be:

```toml
[workspace]
members = [
    "crates/amplifier-module-context-simple",
    "crates/amplifier-module-orchestrator-loop-streaming",
    "crates/amplifier-module-provider-anthropic",
    "crates/amplifier-module-tool-task",
    "crates/amplifier-module-tool-skills",
    # Phase 2
    "crates/amplifier-module-provider-gemini",
    "crates/amplifier-module-provider-openai",
    "crates/amplifier-module-provider-ollama",
    "crates/amplifier-module-tool-web",
    "crates/amplifier-module-tool-todo",
]
resolver = "2"
```

Keep any existing `[workspace.dependencies]` section unchanged.

- [ ] **Step 3: Create the five crate directories so cargo doesn't error**

```bash
cd /Users/ken/workspace/amplifier-rust
mkdir -p crates/amplifier-module-provider-gemini/src
mkdir -p crates/amplifier-module-provider-openai/src
mkdir -p crates/amplifier-module-provider-ollama/src
mkdir -p crates/amplifier-module-tool-web/src
mkdir -p crates/amplifier-module-tool-todo/src
```

- [ ] **Step 4: Create stub `lib.rs` for each new crate so the workspace parses**

```bash
for crate in amplifier-module-provider-gemini amplifier-module-provider-openai amplifier-module-provider-ollama amplifier-module-tool-web amplifier-module-tool-todo; do
  echo "// stub" > crates/$crate/src/lib.rs
  cat > crates/$crate/Cargo.toml << 'TOML'
[package]
name = "CRATE_NAME"
version = "0.1.0"
edition = "2021"
TOML
  sed -i "" "s/CRATE_NAME/$crate/" crates/$crate/Cargo.toml
done
```

- [ ] **Step 5: Verify the workspace parses**

```bash
cd /Users/ken/workspace/amplifier-rust
cargo metadata --no-deps --format-version 1 | python3 -c "import sys,json; m=json.load(sys.stdin); print([p['name'] for p in m['packages']])"
```

Expected: a list that includes all ten crate names with no errors.

- [ ] **Step 6: Commit**

```bash
cd /Users/ken/workspace/amplifier-rust
git add Cargo.toml crates/amplifier-module-provider-gemini crates/amplifier-module-provider-openai crates/amplifier-module-provider-ollama crates/amplifier-module-tool-web crates/amplifier-module-tool-todo
git commit -m "chore: register 5 Phase 2 crates in workspace"
```

---

## Task 2: amplifier-module-provider-gemini — scaffold

**Files:**
- Create: `/Users/ken/workspace/amplifier-rust/crates/amplifier-module-provider-gemini/Cargo.toml`
- Create: `/Users/ken/workspace/amplifier-rust/crates/amplifier-module-provider-gemini/src/types.rs`
- Modify: `/Users/ken/workspace/amplifier-rust/crates/amplifier-module-provider-gemini/src/lib.rs`

- [ ] **Step 1: Write `Cargo.toml`**

Replace the stub with:

```toml
[package]
name = "amplifier-module-provider-gemini"
version = "0.1.0"
edition = "2021"
description = "Gemini Developer API provider for the amplifier-rust workspace"

[dependencies]
amplifier-core = { git = "https://github.com/microsoft/amplifier-core" }
reqwest = { version = "0.12", default-features = false, features = ["json", "rustls-tls", "stream"] }
serde = { version = "1", features = ["derive"] }
serde_json = "1"
tokio = { version = "1", features = ["rt-multi-thread", "macros"] }
futures = "0.3"
uuid = { version = "1", features = ["v4"] }
log = "0.4"

[dev-dependencies]
wiremock = "0.6"
tokio = { version = "1", features = ["full"] }
```

- [ ] **Step 2: Write `src/types.rs`**

```rust
//! Gemini Developer API wire types.
//!
//! POST https://generativelanguage.googleapis.com/v1beta/models/{model}:streamGenerateContent?key={api_key}
//! Auth: query param `key=` — NOT an Authorization header.

use serde::{Deserialize, Serialize};
use serde_json::Value;

// ─────────────────────────────── Request ────────────────────────────────────

#[derive(Debug, Serialize)]
pub struct GeminiRequest {
    pub contents: Vec<GeminiContent>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub system_instruction: Option<GeminiSystemInstruction>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub tools: Option<Vec<GeminiToolWrapper>>,
    pub generation_config: GeminiGenerationConfig,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct GeminiContent {
    pub role: String, // "user" or "model"
    pub parts: Vec<GeminiPart>,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
#[serde(untagged)]
pub enum GeminiPart {
    Text { text: String },
    FunctionCall { function_call: GeminiFunctionCall },
    FunctionResponse { function_response: GeminiFunctionResponse },
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct GeminiFunctionCall {
    pub name: String,
    pub args: Value,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct GeminiFunctionResponse {
    pub name: String,
    pub response: Value,
}

#[derive(Debug, Serialize)]
pub struct GeminiSystemInstruction {
    pub parts: Vec<GeminiPart>,
}

#[derive(Debug, Serialize)]
pub struct GeminiToolWrapper {
    pub function_declarations: Vec<GeminiFunctionDeclaration>,
}

#[derive(Debug, Serialize)]
pub struct GeminiFunctionDeclaration {
    pub name: String,
    pub description: String,
    pub parameters: Value,
}

#[derive(Debug, Serialize)]
pub struct GeminiGenerationConfig {
    pub max_output_tokens: u32,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub thinking_config: Option<GeminiThinkingConfig>,
}

#[derive(Debug, Serialize)]
pub struct GeminiThinkingConfig {
    /// -1 = dynamic, 0 = off, N = fixed token budget.
    pub thinking_budget: i32,
}

// ─────────────────────────────── Response ───────────────────────────────────

#[derive(Debug, Deserialize)]
pub struct GeminiStreamChunk {
    pub candidates: Option<Vec<GeminiCandidate>>,
    #[serde(rename = "usageMetadata")]
    pub usage_metadata: Option<GeminiUsageMetadata>,
}

#[derive(Debug, Deserialize)]
pub struct GeminiCandidate {
    pub content: Option<GeminiContent>,
    #[serde(rename = "finishReason")]
    pub finish_reason: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct GeminiUsageMetadata {
    #[serde(rename = "promptTokenCount")]
    pub prompt_token_count: Option<i64>,
    #[serde(rename = "candidatesTokenCount")]
    pub candidates_token_count: Option<i64>,
}
```

- [ ] **Step 3: Write a minimal stub `src/lib.rs` that declares the module**

```rust
pub mod types;

pub struct GeminiConfig {
    pub api_key: String,
    pub model: String,
    pub max_tokens: u32,
    /// -1 = dynamic, 0 = off, N = fixed token budget.
    pub thinking_budget: i32,
    pub max_retries: u32,
}

impl Default for GeminiConfig {
    fn default() -> Self {
        Self {
            api_key: String::new(),
            model: "gemini-2.5-flash".to_string(),
            max_tokens: 8192,
            thinking_budget: -1,
            max_retries: 3,
        }
    }
}
```

- [ ] **Step 4: Verify it compiles**

```bash
cd /Users/ken/workspace/amplifier-rust
cargo build -p amplifier-module-provider-gemini
```

Expected: `Compiling amplifier-module-provider-gemini ...` then `Finished`.

- [ ] **Step 5: Commit**

```bash
git add crates/amplifier-module-provider-gemini
git commit -m "feat(gemini): scaffold Cargo.toml + wire types"
```

---

## Task 3: amplifier-module-provider-gemini — SSE parser + synthetic tool IDs

This task is test-first. The SSE parser is the trickiest Gemini-specific logic: each `data:` line is a JSON chunk, and function calls need synthetic IDs generated because Gemini never returns them.

**Files:**
- Create: `/Users/ken/workspace/amplifier-rust/crates/amplifier-module-provider-gemini/tests/integration_test.rs`
- Modify: `src/lib.rs`

- [ ] **Step 1: Write the failing tests**

Create `tests/integration_test.rs`:

```rust
use amplifier_module_provider_gemini::parse_sse_line;

#[test]
fn sse_text_chunk_extracts_text() {
    let data = r#"{"candidates":[{"content":{"role":"model","parts":[{"text":"Hello world"}]}}]}"#;
    let (text, calls) = parse_sse_line(data).unwrap();
    assert_eq!(text, "Hello world");
    assert!(calls.is_empty());
}

#[test]
fn sse_function_call_chunk_generates_synthetic_id() {
    let data = r#"{
        "candidates":[{
            "content":{
                "role":"model",
                "parts":[{"functionCall":{"name":"search_web","args":{"query":"Rust async"}}}]
            }
        }]
    }"#;
    let (text, calls) = parse_sse_line(data).unwrap();
    assert!(text.is_empty());
    assert_eq!(calls.len(), 1);
    assert_eq!(calls[0].name, "search_web");
    // Synthetic ID must start with "gemini_call_" and be non-empty
    assert!(calls[0].id.starts_with("gemini_call_"), "id was: {}", calls[0].id);
    assert_eq!(calls[0].args["query"], "Rust async");
}

#[test]
fn sse_empty_candidates_returns_none() {
    let data = r#"{"candidates":[]}"#;
    assert!(parse_sse_line(data).is_none());
}

#[test]
fn sse_non_json_returns_none() {
    assert!(parse_sse_line("[DONE]").is_none());
    assert!(parse_sse_line("").is_none());
}

#[test]
fn two_synthetic_ids_are_distinct() {
    let data = r#"{"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"name":"tool_a","args":{}}}]}}]}"#;
    let (_, calls1) = parse_sse_line(data).unwrap();
    let (_, calls2) = parse_sse_line(data).unwrap();
    assert_ne!(calls1[0].id, calls2[0].id, "Synthetic IDs must be unique per call");
}
```

- [ ] **Step 2: Run to verify they fail**

```bash
cd /Users/ken/workspace/amplifier-rust
cargo test -p amplifier-module-provider-gemini 2>&1 | head -20
```

Expected: `error[E0432]: unresolved import` or similar — `parse_sse_line` doesn't exist yet.

- [ ] **Step 3: Implement `parse_sse_line` in `src/lib.rs`**

Add these imports and the struct + function. The struct `SseFunctionCall` is local to this crate (not an amplifier-core type — conversion to amplifier-core `ToolCall` happens later in `complete()`).

```rust
pub mod types;

use serde_json::Value;
use types::{GeminiPart, GeminiStreamChunk};
use uuid::Uuid;

pub struct GeminiConfig {
    pub api_key: String,
    pub model: String,
    pub max_tokens: u32,
    pub thinking_budget: i32,
    pub max_retries: u32,
}

impl Default for GeminiConfig {
    fn default() -> Self {
        Self {
            api_key: String::new(),
            model: "gemini-2.5-flash".to_string(),
            max_tokens: 8192,
            thinking_budget: -1,
            max_retries: 3,
        }
    }
}

/// A parsed function call from a Gemini SSE chunk.
/// IDs are synthetic (Gemini doesn't return them natively).
pub struct SseFunctionCall {
    pub id: String,
    pub name: String,
    pub args: Value,
}

/// Parse one `data: <json>` SSE line from the Gemini streaming endpoint.
///
/// Returns `Some((text_delta, function_calls))` on a valid JSON chunk with
/// at least one candidate, or `None` for `[DONE]`, empty lines, or JSON
/// that can't be parsed as a Gemini response.
///
/// Synthetic IDs are generated with `format!("gemini_call_{}", uuid::Uuid::new_v4())`.
/// Every call to this function that produces a function call will produce a
/// fresh UUID, so two identical JSON chunks produce different IDs.
pub fn parse_sse_line(data: &str) -> Option<(String, Vec<SseFunctionCall>)> {
    if data.is_empty() || data.starts_with('[') {
        return None;
    }
    let chunk: GeminiStreamChunk = serde_json::from_str(data).ok()?;
    let candidates = chunk.candidates?;
    let candidate = candidates.into_iter().next()?;
    let content = candidate.content?;

    let mut text = String::new();
    let mut calls: Vec<SseFunctionCall> = Vec::new();

    for part in content.parts {
        match part {
            GeminiPart::Text { text: t } => text.push_str(&t),
            GeminiPart::FunctionCall { function_call } => {
                calls.push(SseFunctionCall {
                    id: format!("gemini_call_{}", Uuid::new_v4()),
                    name: function_call.name,
                    args: function_call.args,
                });
            }
            GeminiPart::FunctionResponse { .. } => {}
        }
    }

    Some((text, calls))
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd /Users/ken/workspace/amplifier-rust
cargo test -p amplifier-module-provider-gemini 2>&1
```

Expected:
```
test sse_text_chunk_extracts_text ... ok
test sse_function_call_chunk_generates_synthetic_id ... ok
test sse_empty_candidates_returns_none ... ok
test sse_non_json_returns_none ... ok
test two_synthetic_ids_are_distinct ... ok

test result: ok. 5 passed; 0 failed
```

- [ ] **Step 5: Commit**

```bash
git add crates/amplifier-module-provider-gemini
git commit -m "feat(gemini): SSE parser with synthetic tool-call IDs"
```

---

## Task 4: amplifier-module-provider-gemini — full Provider impl + wiremock integration test

**Files:**
- Modify: `/Users/ken/workspace/amplifier-rust/crates/amplifier-module-provider-gemini/src/lib.rs`
- Modify: `/Users/ken/workspace/amplifier-rust/crates/amplifier-module-provider-gemini/tests/integration_test.rs`

- [ ] **Step 1: Write the wiremock integration test (append to existing tests file)**

```rust
// ─── integration tests (wiremock) ────────────────────────────────────────────
// These tests use a local HTTP mock server to verify the full request/response
// cycle without hitting the real Gemini API.

use amplifier_module_provider_gemini::{GeminiConfig, GeminiProvider};
use amplifier_core::traits::Provider;
use amplifier_core::messages::{ChatRequest, Message, MessageContent, Role};
use wiremock::{MockServer, Mock, ResponseTemplate};
use wiremock::matchers::{method, path_regex, query_param};
use std::collections::HashMap;

#[tokio::test]
async fn gemini_provider_streams_text_response() {
    let mock_server = MockServer::start().await;

    // Gemini SSE: each data line is a JSON object, terminated by a final chunk
    // with finishReason. Lines separated by \n\n.
    let sse_body = concat!(
        "data: {\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"Hello \"}]}}]}\n\n",
        "data: {\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"world\"}]},",
        "\"finishReason\":\"STOP\"}],",
        "\"usageMetadata\":{\"promptTokenCount\":10,\"candidatesTokenCount\":5}}\n\n",
    );

    Mock::given(method("POST"))
        .and(path_regex(r".*streamGenerateContent.*"))
        .and(query_param("key", "test_key"))
        .respond_with(
            ResponseTemplate::new(200)
                .insert_header("content-type", "text/event-stream")
                .set_body_string(sse_body),
        )
        .mount(&mock_server)
        .await;

    let config = GeminiConfig {
        api_key: "test_key".to_string(),
        model: "gemini-2.5-flash".to_string(),
        base_url: mock_server.uri(), // override for test
        max_tokens: 1024,
        thinking_budget: 0, // off for this test
        max_retries: 1,
    };
    let provider = GeminiProvider::new(config);

    let request = ChatRequest {
        messages: vec![Message {
            role: Role::User,
            content: MessageContent::Text("Hi".to_string()),
            name: None,
            tool_call_id: None,
            metadata: None,
            extensions: HashMap::new(),
        }],
        tools: vec![],
        system: None,
        max_tokens: None,
        metadata: None,
        extensions: HashMap::new(),
    };

    let response = provider.complete(request).await.unwrap();
    let text: String = response.content.iter().filter_map(|b| {
        if let amplifier_core::messages::ContentBlock::Text { text, .. } = b {
            Some(text.clone())
        } else {
            None
        }
    }).collect();
    assert_eq!(text, "Hello world");
    let usage = response.usage.unwrap();
    assert_eq!(usage.input_tokens, 10);
    assert_eq!(usage.output_tokens, 5);
}

#[tokio::test]
async fn gemini_provider_converts_function_call_to_tool_call() {
    let mock_server = MockServer::start().await;

    let sse_body = concat!(
        "data: {\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[",
        "{\"functionCall\":{\"name\":\"search_web\",\"args\":{\"query\":\"test\"}}}",
        "]},\"finishReason\":\"STOP\"}]}\n\n",
    );

    Mock::given(method("POST"))
        .and(path_regex(r".*streamGenerateContent.*"))
        .and(query_param("key", "test_key"))
        .respond_with(ResponseTemplate::new(200).set_body_string(sse_body))
        .mount(&mock_server)
        .await;

    let config = GeminiConfig {
        api_key: "test_key".to_string(),
        model: "gemini-2.5-flash".to_string(),
        base_url: mock_server.uri(),
        max_tokens: 1024,
        thinking_budget: 0,
        max_retries: 1,
    };
    let response = GeminiProvider::new(config)
        .complete(ChatRequest {
            messages: vec![Message {
                role: Role::User,
                content: MessageContent::Text("search something".to_string()),
                name: None,
                tool_call_id: None,
                metadata: None,
                extensions: HashMap::new(),
            }],
            tools: vec![],
            system: None,
            max_tokens: None,
            metadata: None,
            extensions: HashMap::new(),
        })
        .await
        .unwrap();

    // The tool call should appear in content as ContentBlock::ToolCall
    let tool_calls: Vec<_> = response.content.iter().filter_map(|b| {
        if let amplifier_core::messages::ContentBlock::ToolCall { id, name, input, .. } = b {
            Some((id.clone(), name.clone(), input.clone()))
        } else {
            None
        }
    }).collect();

    assert_eq!(tool_calls.len(), 1);
    assert_eq!(tool_calls[0].1, "search_web");
    assert!(tool_calls[0].0.starts_with("gemini_call_"), "id: {}", tool_calls[0].0);
    assert_eq!(tool_calls[0].2["query"], "test");
}
```

- [ ] **Step 2: Run to verify tests fail (GeminiProvider doesn't have `complete` yet)**

```bash
cd /Users/ken/workspace/amplifier-rust
cargo test -p amplifier-module-provider-gemini 2>&1 | head -30
```

Expected: compile errors about missing `base_url` field and `complete` method.

- [ ] **Step 3: Write the full `GeminiProvider` implementation**

Replace the entire contents of `src/lib.rs` with:

```rust
//! Gemini Developer API provider for the amplifier-rust workspace.
//!
//! Wire protocol: POST https://generativelanguage.googleapis.com/v1beta/models/{model}:streamGenerateContent?key={api_key}
//! Auth: query param `key=` — NOT an Authorization header.
//! Synthetic tool-call IDs: Gemini never returns IDs → generated as `gemini_call_{uuid}`.

pub mod types;

use std::collections::HashMap;
use std::future::Future;
use std::pin::Pin;

use amplifier_core::messages::{
    ChatRequest, ChatResponse, ContentBlock, Message, MessageContent, Role, ToolSpec,
};
use amplifier_core::traits::Provider;
use amplifier_core::{ProviderError, Usage};
use futures::StreamExt;
use log::{debug, warn};
use reqwest::Client;
use serde_json::{json, Value};
use types::*;
use uuid::Uuid;

const DEFAULT_BASE_URL: &str = "https://generativelanguage.googleapis.com";

// ─────────────────────────────── Config ─────────────────────────────────────

pub struct GeminiConfig {
    pub api_key: String,
    pub model: String,
    /// Base URL — override in tests to point at a WireMock server.
    pub base_url: String,
    pub max_tokens: u32,
    /// -1 = dynamic, 0 = off, N = fixed token budget.
    pub thinking_budget: i32,
    pub max_retries: u32,
}

impl Default for GeminiConfig {
    fn default() -> Self {
        Self {
            api_key: String::new(),
            model: "gemini-2.5-flash".to_string(),
            base_url: DEFAULT_BASE_URL.to_string(),
            max_tokens: 8192,
            thinking_budget: -1,
            max_retries: 3,
        }
    }
}

// ─────────────────────────── SSE helper (public for tests) ──────────────────

/// A parsed function call from a Gemini SSE chunk.
pub struct SseFunctionCall {
    pub id: String,
    pub name: String,
    pub args: Value,
}

/// Parse one JSON blob from a Gemini SSE `data:` line.
///
/// Returns `Some((text_delta, function_calls))` on a valid chunk with at least
/// one candidate, or `None` for `[DONE]`, empty input, or unparseable JSON.
pub fn parse_sse_line(data: &str) -> Option<(String, Vec<SseFunctionCall>)> {
    if data.is_empty() || data.starts_with('[') {
        return None;
    }
    let chunk: GeminiStreamChunk = serde_json::from_str(data).ok()?;
    let candidates = chunk.candidates?;
    let candidate = candidates.into_iter().next()?;
    let content = candidate.content?;

    let mut text = String::new();
    let mut calls: Vec<SseFunctionCall> = Vec::new();

    for part in content.parts {
        match part {
            GeminiPart::Text { text: t } => text.push_str(&t),
            GeminiPart::FunctionCall { function_call } => {
                calls.push(SseFunctionCall {
                    id: format!("gemini_call_{}", Uuid::new_v4()),
                    name: function_call.name,
                    args: function_call.args,
                });
            }
            GeminiPart::FunctionResponse { .. } => {}
        }
    }
    Some((text, calls))
}

// ────────────────────────────── Provider ────────────────────────────────────

pub struct GeminiProvider {
    config: GeminiConfig,
    client: Client,
}

impl GeminiProvider {
    pub fn new(config: GeminiConfig) -> Self {
        Self { config, client: Client::new() }
    }

    fn url(&self) -> String {
        format!(
            "{}/v1beta/models/{}:streamGenerateContent",
            self.config.base_url, self.config.model
        )
    }

    fn messages_to_gemini(messages: &[Message]) -> Vec<GeminiContent> {
        messages
            .iter()
            .filter_map(|msg| {
                let role = match msg.role {
                    Role::User | Role::Tool | Role::Function => "user",
                    Role::Assistant => "model",
                    Role::System | Role::Developer => return None,
                };
                let parts = match &msg.content {
                    MessageContent::Text(text) => {
                        vec![GeminiPart::Text { text: text.clone() }]
                    }
                    MessageContent::Blocks(blocks) => blocks
                        .iter()
                        .filter_map(|block| match block {
                            ContentBlock::Text { text, .. } => {
                                Some(GeminiPart::Text { text: text.clone() })
                            }
                            ContentBlock::ToolCall { id: _, name, input, .. } => {
                                Some(GeminiPart::FunctionCall {
                                    function_call: GeminiFunctionCall {
                                        name: name.clone(),
                                        args: serde_json::to_value(input).unwrap_or(json!({})),
                                    },
                                })
                            }
                            ContentBlock::ToolResult { tool_call_id: _, output, .. } => {
                                // Gemini expects function responses keyed by tool name.
                                // We don't have the name here, so use a placeholder.
                                Some(GeminiPart::FunctionResponse {
                                    function_response: GeminiFunctionResponse {
                                        name: "tool".to_string(),
                                        response: json!({ "output": output }),
                                    },
                                })
                            }
                            _ => None,
                        })
                        .collect(),
                };
                if parts.is_empty() {
                    return None;
                }
                Some(GeminiContent { role: role.to_string(), parts })
            })
            .collect()
    }

    fn tools_to_gemini(tools: &[ToolSpec]) -> Option<Vec<GeminiToolWrapper>> {
        if tools.is_empty() {
            return None;
        }
        let decls: Vec<GeminiFunctionDeclaration> = tools
            .iter()
            .map(|t| GeminiFunctionDeclaration {
                name: t.name.clone(),
                description: t.description.clone().unwrap_or_default(),
                parameters: serde_json::to_value(&t.parameters).unwrap_or(json!({})),
            })
            .collect();
        Some(vec![GeminiToolWrapper { function_declarations: decls }])
    }

    async fn do_complete(&self, request: ChatRequest) -> Result<ChatResponse, ProviderError> {
        let system_instruction = request.system.as_ref().map(|s| GeminiSystemInstruction {
            parts: vec![GeminiPart::Text { text: s.clone() }],
        });

        let thinking_config = if self.config.thinking_budget == 0 {
            None
        } else {
            Some(GeminiThinkingConfig { thinking_budget: self.config.thinking_budget })
        };

        let body = GeminiRequest {
            contents: Self::messages_to_gemini(&request.messages),
            system_instruction,
            tools: Self::tools_to_gemini(&request.tools),
            generation_config: GeminiGenerationConfig {
                max_output_tokens: request
                    .max_tokens
                    .map(|t| t as u32)
                    .unwrap_or(self.config.max_tokens),
                thinking_config,
            },
        };

        let resp = self
            .client
            .post(&self.url())
            .query(&[("key", &self.config.api_key)])
            .query(&[("alt", "sse")])
            .json(&body)
            .send()
            .await
            .map_err(|e| ProviderError::Network {
                message: e.to_string(),
            })?;

        if !resp.status().is_success() {
            let status = resp.status().as_u16();
            let body = resp.text().await.unwrap_or_default();
            return Err(ProviderError::Api {
                status_code: status,
                message: body,
            });
        }

        let mut stream = resp.bytes_stream();
        let mut full_text = String::new();
        let mut tool_calls: Vec<ContentBlock> = Vec::new();
        let mut finish_reason: Option<String> = None;
        let mut usage: Option<Usage> = None;
        let mut buffer = String::new();

        while let Some(chunk) = stream.next().await {
            let chunk = chunk.map_err(|e| ProviderError::Network { message: e.to_string() })?;
            buffer.push_str(&String::from_utf8_lossy(&chunk));

            // Process complete `data: ...` lines
            while let Some(newline_pos) = buffer.find("\n\n") {
                let line = buffer[..newline_pos].trim().to_string();
                buffer = buffer[newline_pos + 2..].to_string();

                let json_data = if let Some(stripped) = line.strip_prefix("data: ") {
                    stripped
                } else {
                    continue;
                };

                if let Some((text, calls)) = parse_sse_line(json_data) {
                    full_text.push_str(&text);
                    for call in calls {
                        tool_calls.push(ContentBlock::ToolCall {
                            id: call.id,
                            name: call.name,
                            input: {
                                let mut map = HashMap::new();
                                if let Value::Object(obj) = call.args {
                                    for (k, v) in obj {
                                        map.insert(k, v);
                                    }
                                }
                                map
                            },
                            visibility: None,
                            extensions: HashMap::new(),
                        });
                    }
                }

                // Also attempt to parse usage from the chunk
                if let Ok(chunk_val) = serde_json::from_str::<GeminiStreamChunk>(json_data) {
                    if let Some(meta) = chunk_val.usage_metadata {
                        let input = meta.prompt_token_count.unwrap_or(0);
                        let output = meta.candidates_token_count.unwrap_or(0);
                        usage = Some(Usage {
                            input_tokens: input,
                            output_tokens: output,
                            total_tokens: input + output,
                            reasoning_tokens: None,
                            cache_read_tokens: None,
                            cache_write_tokens: None,
                            extensions: HashMap::new(),
                        });
                    }
                    if let Some(candidates) = chunk_val.candidates {
                        if let Some(candidate) = candidates.into_iter().next() {
                            if let Some(reason) = candidate.finish_reason {
                                finish_reason = Some(reason);
                            }
                        }
                    }
                }
            }
        }

        let mut content: Vec<ContentBlock> = Vec::new();
        if !full_text.is_empty() {
            content.push(ContentBlock::Text {
                text: full_text,
                visibility: None,
                extensions: HashMap::new(),
            });
        }
        content.extend(tool_calls);

        Ok(ChatResponse {
            content,
            tool_calls: None,
            usage,
            degradation: None,
            finish_reason,
            metadata: None,
            extensions: HashMap::new(),
        })
    }
}

impl Provider for GeminiProvider {
    fn complete<'a>(
        &'a self,
        request: ChatRequest,
    ) -> Pin<Box<dyn Future<Output = Result<ChatResponse, ProviderError>> + Send + 'a>> {
        Box::pin(async move { self.do_complete(request).await })
    }

    fn info(&self) -> amplifier_core::ProviderInfo {
        amplifier_core::ProviderInfo {
            name: "gemini".to_string(),
            model: self.config.model.clone(),
            extensions: HashMap::new(),
        }
    }
}
```

> **Note:** If the `Provider` trait signature or amplifier-core struct field names differ from what you see in `amplifier-module-provider-anthropic`, use that crate as the reference and adjust accordingly.

- [ ] **Step 4: Run all tests**

```bash
cd /Users/ken/workspace/amplifier-rust
cargo test -p amplifier-module-provider-gemini 2>&1
```

Expected:
```
test sse_text_chunk_extracts_text ... ok
test sse_function_call_chunk_generates_synthetic_id ... ok
test sse_empty_candidates_returns_none ... ok
test sse_non_json_returns_none ... ok
test two_synthetic_ids_are_distinct ... ok
test gemini_provider_streams_text_response ... ok
test gemini_provider_converts_function_call_to_tool_call ... ok

test result: ok. 7 passed; 0 failed
```

- [ ] **Step 5: Commit**

```bash
git add crates/amplifier-module-provider-gemini
git commit -m "feat(gemini): full Provider impl with SSE streaming + wiremock tests"
```

---

## Task 5: amplifier-module-provider-openai — scaffold + responses.rs

OpenAI Phase 2 uses the **Responses API** (`POST /v1/responses`), NOT `/v1/chat/completions`. The Responses API is newer, supports reasoning effort (`low/medium/high`), and has encrypted reasoning state that must round-trip across turns.

**Files:**
- Create: `/Users/ken/workspace/amplifier-rust/crates/amplifier-module-provider-openai/Cargo.toml`
- Create: `/Users/ken/workspace/amplifier-rust/crates/amplifier-module-provider-openai/src/responses.rs`
- Modify: `/Users/ken/workspace/amplifier-rust/crates/amplifier-module-provider-openai/src/lib.rs`

- [ ] **Step 1: Write `Cargo.toml`**

```toml
[package]
name = "amplifier-module-provider-openai"
version = "0.1.0"
edition = "2021"
description = "OpenAI Responses API provider for the amplifier-rust workspace"

[dependencies]
amplifier-core = { git = "https://github.com/microsoft/amplifier-core" }
reqwest = { version = "0.12", default-features = false, features = ["json", "rustls-tls", "stream"] }
serde = { version = "1", features = ["derive"] }
serde_json = "1"
tokio = { version = "1", features = ["rt-multi-thread", "macros"] }
log = "0.4"

[dev-dependencies]
wiremock = "0.6"
tokio = { version = "1", features = ["full"] }
```

- [ ] **Step 2: Write `src/responses.rs`**

```rust
//! OpenAI Responses API wire types.
//!
//! Endpoint: POST https://api.openai.com/v1/responses
//! NOT /v1/chat/completions — this is the newer Responses API.
//! Auth: Authorization: Bearer {api_key}

use serde::{Deserialize, Serialize};
use serde_json::Value;

// ─────────────────────────────── Request ────────────────────────────────────

#[derive(Debug, Serialize)]
pub struct ResponsesRequest {
    pub model: String,
    pub input: Vec<ResponsesInputItem>,
    pub max_output_tokens: u32,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub instructions: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub tools: Option<Vec<ResponsesTool>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub reasoning: Option<ResponsesReasoning>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub include: Option<Vec<String>>,
    /// Previous response ID for multi-turn conversations.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub previous_response_id: Option<String>,
}

#[derive(Debug, Serialize, Clone)]
#[serde(tag = "type")]
pub enum ResponsesInputItem {
    #[serde(rename = "message")]
    Message {
        role: String, // "user" | "assistant" | "system"
        content: Value,
    },
    #[serde(rename = "function_call_output")]
    FunctionCallOutput {
        call_id: String,
        output: String,
    },
}

#[derive(Debug, Serialize, Clone)]
pub struct ResponsesTool {
    #[serde(rename = "type")]
    pub tool_type: String, // "function"
    pub name: String,
    pub description: String,
    pub parameters: Value,
}

#[derive(Debug, Serialize, Clone)]
pub struct ResponsesReasoning {
    pub effort: String, // "low" | "medium" | "high"
}

// ─────────────────────────────── Response ───────────────────────────────────

#[derive(Debug, Deserialize)]
pub struct ResponsesResponse {
    pub id: String,
    pub status: String, // "completed" | "incomplete" | "failed"
    pub output: Vec<ResponsesOutputItem>,
    #[serde(default)]
    pub usage: Option<ResponsesUsage>,
    pub incomplete_details: Option<IncompleteDetails>,
    /// Encrypted reasoning state — must be echoed back in `include` on next request.
    pub reasoning: Option<ResponsesReasoningState>,
}

#[derive(Debug, Deserialize)]
pub struct ResponsesOutputItem {
    #[serde(rename = "type")]
    pub item_type: String,
    // For type == "message"
    pub content: Option<Vec<ResponsesContent>>,
    // For type == "function_call"
    pub call_id: Option<String>,
    pub name: Option<String>,
    pub arguments: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct ResponsesContent {
    #[serde(rename = "type")]
    pub content_type: String, // "output_text"
    pub text: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct ResponsesUsage {
    pub input_tokens: i64,
    pub output_tokens: i64,
    pub total_tokens: i64,
    #[serde(default)]
    pub output_tokens_details: Option<OutputTokensDetails>,
}

#[derive(Debug, Deserialize)]
pub struct OutputTokensDetails {
    pub reasoning_tokens: Option<i64>,
}

#[derive(Debug, Deserialize)]
pub struct IncompleteDetails {
    pub reason: String, // "max_output_tokens" | "content_filter"
}

#[derive(Debug, Deserialize)]
pub struct ResponsesReasoningState {
    pub encrypted_content: Option<String>,
}
```

- [ ] **Step 3: Write a stub `src/lib.rs`**

```rust
pub mod responses;

pub struct OpenAIConfig {
    pub api_key: String,
    pub model: String,
    pub base_url: String,
    pub max_tokens: u32,
    /// None = no reasoning. Some("low"|"medium"|"high") = reasoning effort.
    pub reasoning_effort: Option<String>,
    pub max_retries: u32,
}

impl Default for OpenAIConfig {
    fn default() -> Self {
        Self {
            api_key: String::new(),
            model: "gpt-4o".to_string(),
            base_url: "https://api.openai.com".to_string(),
            max_tokens: 4096,
            reasoning_effort: None,
            max_retries: 3,
        }
    }
}
```

- [ ] **Step 4: Verify it compiles**

```bash
cd /Users/ken/workspace/amplifier-rust
cargo build -p amplifier-module-provider-openai
```

Expected: `Finished` with no errors.

- [ ] **Step 5: Commit**

```bash
git add crates/amplifier-module-provider-openai
git commit -m "feat(openai): scaffold Cargo.toml + Responses API wire types"
```

---

## Task 6: amplifier-module-provider-openai — full Provider impl + auto-continuation

Auto-continuation handles the case where OpenAI returns `status == "incomplete"` with `reason == "max_output_tokens"` — the provider retries up to 5 times, appending the previous output.

**Files:**
- Modify: `/Users/ken/workspace/amplifier-rust/crates/amplifier-module-provider-openai/src/lib.rs`
- Create: `/Users/ken/workspace/amplifier-rust/crates/amplifier-module-provider-openai/tests/integration_test.rs`

- [ ] **Step 1: Write the integration tests**

Create `tests/integration_test.rs`:

```rust
use amplifier_module_provider_openai::{OpenAIConfig, OpenAIProvider};
use amplifier_core::traits::Provider;
use amplifier_core::messages::{ChatRequest, Message, MessageContent, Role};
use wiremock::{MockServer, Mock, ResponseTemplate};
use wiremock::matchers::{method, path, header};
use std::collections::HashMap;

#[tokio::test]
async fn openai_sends_bearer_auth_header() {
    let mock_server = MockServer::start().await;

    Mock::given(method("POST"))
        .and(path("/v1/responses"))
        .and(header("authorization", "Bearer test_key"))
        .respond_with(
            ResponseTemplate::new(200).set_body_json(serde_json::json!({
                "id": "resp_001",
                "status": "completed",
                "output": [{"type": "message", "content": [{"type": "output_text", "text": "Hi there"}]}],
                "usage": {"input_tokens": 5, "output_tokens": 3, "total_tokens": 8}
            })),
        )
        .mount(&mock_server)
        .await;

    let config = OpenAIConfig {
        api_key: "test_key".to_string(),
        model: "gpt-4o".to_string(),
        base_url: mock_server.uri(),
        max_tokens: 512,
        reasoning_effort: None,
        max_retries: 1,
    };
    let response = OpenAIProvider::new(config)
        .complete(ChatRequest {
            messages: vec![Message {
                role: Role::User,
                content: MessageContent::Text("Hello".to_string()),
                name: None,
                tool_call_id: None,
                metadata: None,
                extensions: HashMap::new(),
            }],
            tools: vec![],
            system: None,
            max_tokens: None,
            metadata: None,
            extensions: HashMap::new(),
        })
        .await
        .unwrap();

    let text: String = response.content.iter().filter_map(|b| {
        if let amplifier_core::messages::ContentBlock::Text { text, .. } = b {
            Some(text.clone())
        } else { None }
    }).collect();
    assert_eq!(text, "Hi there");
}

#[tokio::test]
async fn openai_auto_continues_on_max_output_tokens() {
    let mock_server = MockServer::start().await;

    // First call: incomplete
    Mock::given(method("POST"))
        .and(path("/v1/responses"))
        .respond_with(
            ResponseTemplate::new(200).set_body_json(serde_json::json!({
                "id": "resp_001",
                "status": "incomplete",
                "incomplete_details": {"reason": "max_output_tokens"},
                "output": [{"type": "message", "content": [{"type": "output_text", "text": "Part one "}]}],
                "usage": {"input_tokens": 5, "output_tokens": 10, "total_tokens": 15}
            })),
        )
        .up_to_n_times(1)
        .mount(&mock_server)
        .await;

    // Second call: completed
    Mock::given(method("POST"))
        .and(path("/v1/responses"))
        .respond_with(
            ResponseTemplate::new(200).set_body_json(serde_json::json!({
                "id": "resp_002",
                "status": "completed",
                "output": [{"type": "message", "content": [{"type": "output_text", "text": "part two"}]}],
                "usage": {"input_tokens": 5, "output_tokens": 5, "total_tokens": 10}
            })),
        )
        .mount(&mock_server)
        .await;

    let config = OpenAIConfig {
        api_key: "k".to_string(),
        model: "gpt-4o".to_string(),
        base_url: mock_server.uri(),
        max_tokens: 512,
        reasoning_effort: None,
        max_retries: 1,
    };
    let response = OpenAIProvider::new(config)
        .complete(ChatRequest {
            messages: vec![Message {
                role: Role::User,
                content: MessageContent::Text("Write a long thing".to_string()),
                name: None,
                tool_call_id: None,
                metadata: None,
                extensions: HashMap::new(),
            }],
            tools: vec![],
            system: None,
            max_tokens: None,
            metadata: None,
            extensions: HashMap::new(),
        })
        .await
        .unwrap();

    let text: String = response.content.iter().filter_map(|b| {
        if let amplifier_core::messages::ContentBlock::Text { text, .. } = b { Some(text.clone()) }
        else { None }
    }).collect();
    assert_eq!(text, "Part one part two", "Auto-continuation must join both parts");
}
```

- [ ] **Step 2: Run to verify they fail**

```bash
cd /Users/ken/workspace/amplifier-rust
cargo test -p amplifier-module-provider-openai 2>&1 | head -20
```

Expected: compile errors — `OpenAIProvider` not yet defined.

- [ ] **Step 3: Write the full `OpenAIProvider` in `src/lib.rs`**

```rust
//! OpenAI Responses API provider.
//!
//! Endpoint: POST {base_url}/v1/responses
//! Auth: Authorization: Bearer {api_key}
//! Reasoning: { "reasoning": { "effort": "low"|"medium"|"high" } }
//! Auto-continuation: if status=="incomplete" and reason=="max_output_tokens",
//!   retry up to 5 times, appending accumulated text each time.

pub mod responses;

use std::collections::HashMap;
use std::future::Future;
use std::pin::Pin;

use amplifier_core::messages::{
    ChatRequest, ChatResponse, ContentBlock, Message, MessageContent, Role, ToolSpec,
};
use amplifier_core::traits::Provider;
use amplifier_core::{ProviderError, ProviderInfo, Usage};
use log::warn;
use reqwest::Client;
use serde_json::{json, Value};
use responses::*;

const MAX_CONTINUATIONS: u32 = 5;

pub struct OpenAIConfig {
    pub api_key: String,
    pub model: String,
    pub base_url: String,
    pub max_tokens: u32,
    pub reasoning_effort: Option<String>,
    pub max_retries: u32,
}

impl Default for OpenAIConfig {
    fn default() -> Self {
        Self {
            api_key: String::new(),
            model: "gpt-4o".to_string(),
            base_url: "https://api.openai.com".to_string(),
            max_tokens: 4096,
            reasoning_effort: None,
            max_retries: 3,
        }
    }
}

pub struct OpenAIProvider {
    config: OpenAIConfig,
    client: Client,
}

impl OpenAIProvider {
    pub fn new(config: OpenAIConfig) -> Self {
        Self { config, client: Client::new() }
    }

    fn messages_to_input(messages: &[Message]) -> Vec<ResponsesInputItem> {
        messages
            .iter()
            .flat_map(|msg| {
                let role = match msg.role {
                    Role::User | Role::Function => "user",
                    Role::Assistant => "assistant",
                    Role::System | Role::Developer => "system",
                    Role::Tool => return vec![], // tool results handled via function_call_output
                };
                match &msg.content {
                    MessageContent::Text(text) => vec![ResponsesInputItem::Message {
                        role: role.to_string(),
                        content: json!(text),
                    }],
                    MessageContent::Blocks(blocks) => {
                        let mut items = Vec::new();
                        let mut tool_results_pending = Vec::new();

                        for block in blocks {
                            match block {
                                ContentBlock::Text { text, .. } => {
                                    items.push(ResponsesInputItem::Message {
                                        role: role.to_string(),
                                        content: json!(text),
                                    });
                                }
                                ContentBlock::ToolCall { id, name: _, input, .. } => {
                                    // Tool calls in assistant messages become assistant content
                                    items.push(ResponsesInputItem::Message {
                                        role: "assistant".to_string(),
                                        content: json!([{
                                            "type": "function_call",
                                            "call_id": id,
                                            "arguments": serde_json::to_string(input).unwrap_or_default()
                                        }]),
                                    });
                                }
                                ContentBlock::ToolResult { tool_call_id, output, .. } => {
                                    tool_results_pending.push(ResponsesInputItem::FunctionCallOutput {
                                        call_id: tool_call_id.clone(),
                                        output: output.to_string(),
                                    });
                                }
                                _ => {}
                            }
                        }
                        items.extend(tool_results_pending);
                        items
                    }
                }
            })
            .collect()
    }

    fn tools_to_responses(tools: &[ToolSpec]) -> Option<Vec<ResponsesTool>> {
        if tools.is_empty() {
            return None;
        }
        Some(
            tools
                .iter()
                .map(|t| ResponsesTool {
                    tool_type: "function".to_string(),
                    name: t.name.clone(),
                    description: t.description.clone().unwrap_or_default(),
                    parameters: serde_json::to_value(&t.parameters).unwrap_or(json!({})),
                })
                .collect(),
        )
    }

    async fn post_request(
        &self,
        req: &ResponsesRequest,
    ) -> Result<ResponsesResponse, ProviderError> {
        let url = format!("{}/v1/responses", self.config.base_url);
        let resp = self
            .client
            .post(&url)
            .header("Authorization", format!("Bearer {}", self.config.api_key))
            .header("Content-Type", "application/json")
            .json(req)
            .send()
            .await
            .map_err(|e| ProviderError::Network { message: e.to_string() })?;

        if !resp.status().is_success() {
            let status = resp.status().as_u16();
            let body = resp.text().await.unwrap_or_default();
            return Err(ProviderError::Api { status_code: status, message: body });
        }
        resp.json::<ResponsesResponse>().await.map_err(|e| ProviderError::Parse {
            message: e.to_string(),
        })
    }

    fn extract_text_and_calls(
        response: &ResponsesResponse,
    ) -> (String, Vec<ContentBlock>) {
        let mut text = String::new();
        let mut tool_calls: Vec<ContentBlock> = Vec::new();

        for item in &response.output {
            match item.item_type.as_str() {
                "message" => {
                    if let Some(contents) = &item.content {
                        for c in contents {
                            if c.content_type == "output_text" {
                                if let Some(t) = &c.text {
                                    text.push_str(t);
                                }
                            }
                        }
                    }
                }
                "function_call" => {
                    if let (Some(call_id), Some(name)) = (&item.call_id, &item.name) {
                        let args_str = item.arguments.as_deref().unwrap_or("{}");
                        let args: Value =
                            serde_json::from_str(args_str).unwrap_or(json!({}));
                        let input = if let Value::Object(map) = args {
                            map.into_iter().collect()
                        } else {
                            HashMap::new()
                        };
                        tool_calls.push(ContentBlock::ToolCall {
                            id: call_id.clone(),
                            name: name.clone(),
                            input,
                            visibility: None,
                            extensions: HashMap::new(),
                        });
                    }
                }
                _ => {}
            }
        }

        (text, tool_calls)
    }

    async fn do_complete(&self, request: ChatRequest) -> Result<ChatResponse, ProviderError> {
        let max_tokens = request.max_tokens.map(|t| t as u32).unwrap_or(self.config.max_tokens);

        let mut req = ResponsesRequest {
            model: self.config.model.clone(),
            input: Self::messages_to_input(&request.messages),
            max_output_tokens: max_tokens,
            instructions: request.system.clone(),
            tools: Self::tools_to_responses(&request.tools),
            reasoning: self.config.reasoning_effort.as_ref().map(|effort| ResponsesReasoning {
                effort: effort.clone(),
            }),
            include: None,
            previous_response_id: None,
        };

        let mut accumulated_text = String::new();
        let mut accumulated_tools: Vec<ContentBlock> = Vec::new();
        let mut final_usage: Option<Usage> = None;
        let mut finish_reason: Option<String> = None;

        for attempt in 0..=MAX_CONTINUATIONS {
            let response = self.post_request(&req).await?;

            let (text, calls) = Self::extract_text_and_calls(&response);
            accumulated_text.push_str(&text);
            accumulated_tools.extend(calls);

            // Track usage (last response wins)
            if let Some(u) = &response.usage {
                final_usage = Some(Usage {
                    input_tokens: u.input_tokens,
                    output_tokens: u.output_tokens,
                    total_tokens: u.total_tokens,
                    reasoning_tokens: u.output_tokens_details
                        .as_ref()
                        .and_then(|d| d.reasoning_tokens),
                    cache_read_tokens: None,
                    cache_write_tokens: None,
                    extensions: HashMap::new(),
                });
            }

            match response.status.as_str() {
                "completed" => {
                    finish_reason = Some("stop".to_string());
                    break;
                }
                "incomplete" => {
                    let reason = response
                        .incomplete_details
                        .as_ref()
                        .map(|d| d.reason.as_str())
                        .unwrap_or("");

                    if reason == "max_output_tokens" && attempt < MAX_CONTINUATIONS {
                        // Continue: append what we have so far as assistant context,
                        // keep previous_response_id for stateful continuation
                        req.previous_response_id = Some(response.id.clone());
                        req.include = Some(vec!["reasoning.encrypted_content".to_string()]);
                        // Append accumulated text as assistant message for context
                        req.input.push(ResponsesInputItem::Message {
                            role: "assistant".to_string(),
                            content: json!(accumulated_text.clone()),
                        });
                        warn!(
                            "OpenAI incomplete (max_output_tokens), continuing attempt {}/{}",
                            attempt + 1,
                            MAX_CONTINUATIONS
                        );
                    } else {
                        finish_reason = Some(format!("incomplete:{}", reason));
                        break;
                    }
                }
                other => {
                    return Err(ProviderError::Api {
                        status_code: 200,
                        message: format!("unexpected status: {other}"),
                    });
                }
            }
        }

        let mut content: Vec<ContentBlock> = Vec::new();
        if !accumulated_text.is_empty() {
            content.push(ContentBlock::Text {
                text: accumulated_text,
                visibility: None,
                extensions: HashMap::new(),
            });
        }
        content.extend(accumulated_tools);

        Ok(ChatResponse {
            content,
            tool_calls: None,
            usage: final_usage,
            degradation: None,
            finish_reason,
            metadata: None,
            extensions: HashMap::new(),
        })
    }
}

impl Provider for OpenAIProvider {
    fn complete<'a>(
        &'a self,
        request: ChatRequest,
    ) -> Pin<Box<dyn Future<Output = Result<ChatResponse, ProviderError>> + Send + 'a>> {
        Box::pin(async move { self.do_complete(request).await })
    }

    fn info(&self) -> ProviderInfo {
        ProviderInfo {
            name: "openai".to_string(),
            model: self.config.model.clone(),
            extensions: HashMap::new(),
        }
    }
}
```

- [ ] **Step 4: Run tests**

```bash
cd /Users/ken/workspace/amplifier-rust
cargo test -p amplifier-module-provider-openai 2>&1
```

Expected:
```
test openai_sends_bearer_auth_header ... ok
test openai_auto_continues_on_max_output_tokens ... ok

test result: ok. 2 passed; 0 failed
```

- [ ] **Step 5: Commit**

```bash
git add crates/amplifier-module-provider-openai
git commit -m "feat(openai): Responses API provider with auth + auto-continuation"
```

---

## Task 7: amplifier-module-provider-ollama — full crate

Ollama uses the OpenAI ChatCompletions format (`/v1/chat/completions`). It's the simplest provider: no auth required by default, the model is mandatory, and `base_url` is configurable so the same code targets LM Studio, vLLM, or OpenRouter.

**Files:**
- Create: `/Users/ken/workspace/amplifier-rust/crates/amplifier-module-provider-ollama/Cargo.toml`
- Create: `/Users/ken/workspace/amplifier-rust/crates/amplifier-module-provider-ollama/src/lib.rs`
- Create: `/Users/ken/workspace/amplifier-rust/crates/amplifier-module-provider-ollama/tests/integration_test.rs`

- [ ] **Step 1: Write `Cargo.toml`**

```toml
[package]
name = "amplifier-module-provider-ollama"
version = "0.1.0"
edition = "2021"
description = "Ollama / ChatCompletions-compatible provider for the amplifier-rust workspace"

[dependencies]
amplifier-core = { git = "https://github.com/microsoft/amplifier-core" }
reqwest = { version = "0.12", default-features = false, features = ["json", "rustls-tls", "stream"] }
serde = { version = "1", features = ["derive"] }
serde_json = "1"
tokio = { version = "1", features = ["rt-multi-thread", "macros"] }
futures = "0.3"
log = "0.4"

[dev-dependencies]
wiremock = "0.6"
tokio = { version = "1", features = ["full"] }
```

- [ ] **Step 2: Write the integration test first**

Create `tests/integration_test.rs`:

```rust
use amplifier_module_provider_ollama::{OllamaConfig, OllamaProvider};
use amplifier_core::traits::Provider;
use amplifier_core::messages::{ChatRequest, Message, MessageContent, Role};
use wiremock::{MockServer, Mock, ResponseTemplate};
use wiremock::matchers::{method, path};
use std::collections::HashMap;

#[tokio::test]
async fn ollama_sends_to_chat_completions_endpoint() {
    let mock_server = MockServer::start().await;

    // ChatCompletions non-streaming response
    Mock::given(method("POST"))
        .and(path("/v1/chat/completions"))
        .respond_with(
            ResponseTemplate::new(200).set_body_json(serde_json::json!({
                "id": "chatcmpl-1",
                "object": "chat.completion",
                "choices": [{
                    "index": 0,
                    "message": {"role": "assistant", "content": "Hello from Ollama"},
                    "finish_reason": "stop"
                }],
                "usage": {"prompt_tokens": 8, "completion_tokens": 4, "total_tokens": 12}
            })),
        )
        .mount(&mock_server)
        .await;

    let config = OllamaConfig {
        base_url: mock_server.uri(),
        api_key: None,
        model: "llama3.2".to_string(),
        max_tokens: 512,
        max_retries: 1,
    };
    let response = OllamaProvider::new(config)
        .complete(ChatRequest {
            messages: vec![Message {
                role: Role::User,
                content: MessageContent::Text("Hi".to_string()),
                name: None,
                tool_call_id: None,
                metadata: None,
                extensions: HashMap::new(),
            }],
            tools: vec![],
            system: None,
            max_tokens: None,
            metadata: None,
            extensions: HashMap::new(),
        })
        .await
        .unwrap();

    let text: String = response.content.iter().filter_map(|b| {
        if let amplifier_core::messages::ContentBlock::Text { text, .. } = b {
            Some(text.clone())
        } else { None }
    }).collect();
    assert_eq!(text, "Hello from Ollama");

    let usage = response.usage.unwrap();
    assert_eq!(usage.input_tokens, 8);
    assert_eq!(usage.output_tokens, 4);
}

#[tokio::test]
async fn ollama_tool_call_is_parsed() {
    let mock_server = MockServer::start().await;

    Mock::given(method("POST"))
        .and(path("/v1/chat/completions"))
        .respond_with(
            ResponseTemplate::new(200).set_body_json(serde_json::json!({
                "id": "chatcmpl-2",
                "choices": [{
                    "index": 0,
                    "message": {
                        "role": "assistant",
                        "content": null,
                        "tool_calls": [{
                            "id": "call_abc",
                            "type": "function",
                            "function": {
                                "name": "fetch_url",
                                "arguments": "{\"url\":\"https://example.com\"}"
                            }
                        }]
                    },
                    "finish_reason": "tool_calls"
                }],
                "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}
            })),
        )
        .mount(&mock_server)
        .await;

    let config = OllamaConfig {
        base_url: mock_server.uri(),
        api_key: None,
        model: "llama3.2".to_string(),
        max_tokens: 512,
        max_retries: 1,
    };
    let response = OllamaProvider::new(config)
        .complete(ChatRequest {
            messages: vec![Message {
                role: Role::User,
                content: MessageContent::Text("fetch this".to_string()),
                name: None,
                tool_call_id: None,
                metadata: None,
                extensions: HashMap::new(),
            }],
            tools: vec![],
            system: None,
            max_tokens: None,
            metadata: None,
            extensions: HashMap::new(),
        })
        .await
        .unwrap();

    let tool_call = response.content.iter().find_map(|b| {
        if let amplifier_core::messages::ContentBlock::ToolCall { id, name, input, .. } = b {
            Some((id.clone(), name.clone(), input.clone()))
        } else { None }
    }).expect("expected a ToolCall block");

    assert_eq!(tool_call.0, "call_abc");
    assert_eq!(tool_call.1, "fetch_url");
    assert_eq!(tool_call.2["url"], "https://example.com");
}

/// This test requires a real Ollama server — skipped by default.
#[tokio::test]
#[ignore = "requires local Ollama on :11434"]
async fn ollama_real_server_completion() {
    let config = OllamaConfig {
        base_url: "http://localhost:11434".to_string(),
        api_key: None,
        model: "llama3.2".to_string(),
        max_tokens: 64,
        max_retries: 1,
    };
    let response = OllamaProvider::new(config)
        .complete(ChatRequest {
            messages: vec![Message {
                role: Role::User,
                content: MessageContent::Text("Say hi in one word".to_string()),
                name: None,
                tool_call_id: None,
                metadata: None,
                extensions: HashMap::new(),
            }],
            tools: vec![],
            system: None,
            max_tokens: None,
            metadata: None,
            extensions: HashMap::new(),
        })
        .await
        .unwrap();
    assert!(!response.content.is_empty());
}
```

- [ ] **Step 3: Run to verify tests fail**

```bash
cd /Users/ken/workspace/amplifier-rust
cargo test -p amplifier-module-provider-ollama 2>&1 | head -15
```

Expected: compile errors — `OllamaConfig`, `OllamaProvider` not defined.

- [ ] **Step 4: Write `src/lib.rs`**

```rust
//! Ollama / ChatCompletions-compatible provider.
//!
//! Wire: POST {base_url}/v1/chat/completions
//! Compatible with: Ollama, LM Studio, vLLM, OpenRouter, any OpenAI-compatible endpoint.
//! API key: optional — most Ollama installs don't need one.
//! Model: required — no default (user must specify, e.g. "llama3.2").

use std::collections::HashMap;
use std::future::Future;
use std::pin::Pin;

use amplifier_core::messages::{
    ChatRequest, ChatResponse, ContentBlock, Message, MessageContent, Role, ToolSpec,
};
use amplifier_core::traits::Provider;
use amplifier_core::{ProviderError, ProviderInfo, Usage};
use log::warn;
use reqwest::Client;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};

// ─────────────────────────────── Config ─────────────────────────────────────

pub struct OllamaConfig {
    pub base_url: String,
    pub api_key: Option<String>,
    /// Required — no default. E.g. "llama3.2", "mistral", "codestral".
    pub model: String,
    pub max_tokens: u32,
    pub max_retries: u32,
}

impl Default for OllamaConfig {
    fn default() -> Self {
        Self {
            base_url: "http://localhost:11434".to_string(),
            api_key: None,
            model: String::new(), // must be set
            max_tokens: 4096,
            max_retries: 2,
        }
    }
}

// ────────────────────────────── Wire types ──────────────────────────────────

#[derive(Debug, Serialize)]
struct ChatCompletionsRequest {
    model: String,
    messages: Vec<Value>,
    max_tokens: u32,
    #[serde(skip_serializing_if = "Option::is_none")]
    tools: Option<Vec<Value>>,
    stream: bool,
}

#[derive(Debug, Deserialize)]
struct ChatCompletionsResponse {
    choices: Vec<ChatChoice>,
    usage: Option<ChatUsage>,
}

#[derive(Debug, Deserialize)]
struct ChatChoice {
    message: ChatMessage,
    finish_reason: Option<String>,
}

#[derive(Debug, Deserialize)]
struct ChatMessage {
    role: Option<String>,
    content: Option<String>,
    tool_calls: Option<Vec<ChatToolCall>>,
}

#[derive(Debug, Deserialize)]
struct ChatToolCall {
    id: String,
    function: ChatToolCallFunction,
}

#[derive(Debug, Deserialize)]
struct ChatToolCallFunction {
    name: String,
    arguments: String,
}

#[derive(Debug, Deserialize)]
struct ChatUsage {
    prompt_tokens: Option<i64>,
    completion_tokens: Option<i64>,
    total_tokens: Option<i64>,
}

// ─────────────────────────────── Provider ───────────────────────────────────

pub struct OllamaProvider {
    config: OllamaConfig,
    client: Client,
}

impl OllamaProvider {
    pub fn new(config: OllamaConfig) -> Self {
        Self { config, client: Client::new() }
    }

    fn message_to_chat(msg: &Message) -> Vec<Value> {
        let role = match msg.role {
            Role::User | Role::Function => "user",
            Role::Assistant => "assistant",
            Role::System | Role::Developer => "system",
            Role::Tool => "tool",
        };
        match &msg.content {
            MessageContent::Text(text) => vec![json!({"role": role, "content": text})],
            MessageContent::Blocks(blocks) => {
                let mut out = Vec::new();
                for block in blocks {
                    match block {
                        ContentBlock::Text { text, .. } => {
                            out.push(json!({"role": role, "content": text}));
                        }
                        ContentBlock::ToolCall { id, name, input, .. } => {
                            out.push(json!({
                                "role": "assistant",
                                "tool_calls": [{
                                    "id": id,
                                    "type": "function",
                                    "function": {
                                        "name": name,
                                        "arguments": serde_json::to_string(input).unwrap_or_default()
                                    }
                                }]
                            }));
                        }
                        ContentBlock::ToolResult { tool_call_id, output, .. } => {
                            out.push(json!({
                                "role": "tool",
                                "tool_call_id": tool_call_id,
                                "content": output.to_string()
                            }));
                        }
                        _ => {}
                    }
                }
                out
            }
        }
    }

    fn tools_to_chat(tools: &[ToolSpec]) -> Option<Vec<Value>> {
        if tools.is_empty() {
            return None;
        }
        Some(
            tools
                .iter()
                .map(|t| {
                    json!({
                        "type": "function",
                        "function": {
                            "name": t.name,
                            "description": t.description.as_deref().unwrap_or(""),
                            "parameters": serde_json::to_value(&t.parameters).unwrap_or(json!({}))
                        }
                    })
                })
                .collect(),
        )
    }

    async fn do_complete(&self, request: ChatRequest) -> Result<ChatResponse, ProviderError> {
        let url = format!("{}/v1/chat/completions", self.config.base_url);

        let mut messages: Vec<Value> = Vec::new();
        if let Some(system) = &request.system {
            messages.push(json!({"role": "system", "content": system}));
        }
        for msg in &request.messages {
            messages.extend(Self::message_to_chat(msg));
        }

        let body = ChatCompletionsRequest {
            model: self.config.model.clone(),
            messages,
            max_tokens: request.max_tokens.map(|t| t as u32).unwrap_or(self.config.max_tokens),
            tools: Self::tools_to_chat(&request.tools),
            stream: false, // non-streaming for simplicity; streaming can be added later
        };

        let mut req_builder = self.client.post(&url).json(&body);
        if let Some(key) = &self.config.api_key {
            req_builder = req_builder.header("Authorization", format!("Bearer {}", key));
        }

        let resp = req_builder
            .send()
            .await
            .map_err(|e| ProviderError::Network { message: e.to_string() })?;

        if !resp.status().is_success() {
            let status = resp.status().as_u16();
            let body_text = resp.text().await.unwrap_or_default();
            return Err(ProviderError::Api { status_code: status, message: body_text });
        }

        let completion: ChatCompletionsResponse = resp.json().await.map_err(|e| {
            ProviderError::Parse { message: e.to_string() }
        })?;

        let choice = completion.choices.into_iter().next().ok_or_else(|| ProviderError::Parse {
            message: "no choices in response".to_string(),
        })?;

        let mut content: Vec<ContentBlock> = Vec::new();
        if let Some(text) = choice.message.content.filter(|s| !s.is_empty()) {
            content.push(ContentBlock::Text {
                text,
                visibility: None,
                extensions: HashMap::new(),
            });
        }
        if let Some(tool_calls) = choice.message.tool_calls {
            for tc in tool_calls {
                let args: Value =
                    serde_json::from_str(&tc.function.arguments).unwrap_or(json!({}));
                let input = if let Value::Object(map) = args {
                    map.into_iter().collect()
                } else {
                    HashMap::new()
                };
                content.push(ContentBlock::ToolCall {
                    id: tc.id,
                    name: tc.function.name,
                    input,
                    visibility: None,
                    extensions: HashMap::new(),
                });
            }
        }

        let usage = completion.usage.map(|u| Usage {
            input_tokens: u.prompt_tokens.unwrap_or(0),
            output_tokens: u.completion_tokens.unwrap_or(0),
            total_tokens: u.total_tokens.unwrap_or(0),
            reasoning_tokens: None,
            cache_read_tokens: None,
            cache_write_tokens: None,
            extensions: HashMap::new(),
        });

        Ok(ChatResponse {
            content,
            tool_calls: None,
            usage,
            degradation: None,
            finish_reason: choice.finish_reason,
            metadata: None,
            extensions: HashMap::new(),
        })
    }
}

impl Provider for OllamaProvider {
    fn complete<'a>(
        &'a self,
        request: ChatRequest,
    ) -> Pin<Box<dyn Future<Output = Result<ChatResponse, ProviderError>> + Send + 'a>> {
        Box::pin(async move { self.do_complete(request).await })
    }

    fn info(&self) -> ProviderInfo {
        ProviderInfo {
            name: "ollama".to_string(),
            model: self.config.model.clone(),
            extensions: HashMap::new(),
        }
    }
}
```

- [ ] **Step 5: Run tests (non-ignored only)**

```bash
cd /Users/ken/workspace/amplifier-rust
cargo test -p amplifier-module-provider-ollama 2>&1
```

Expected:
```
test ollama_sends_to_chat_completions_endpoint ... ok
test ollama_tool_call_is_parsed ... ok
test ollama_real_server_completion ... ignored

test result: ok. 2 passed; 0 failed; 1 ignored
```

- [ ] **Step 6: Commit**

```bash
git add crates/amplifier-module-provider-ollama
git commit -m "feat(ollama): ChatCompletions provider with configurable base_url"
```

---

## Task 8: amplifier-module-tool-web — fetch_url

The web tool crate exposes two tools via `WebToolSuite`. This task covers `fetch_url` (GET + HTML strip). HTML stripping removes `<script>`, `<style>`, `<nav>`, `<header>`, `<footer>` blocks first, then strips all remaining tags, collapses whitespace, and truncates at 8KB.

**Files:**
- Create: `/Users/ken/workspace/amplifier-rust/crates/amplifier-module-tool-web/Cargo.toml`
- Create: `/Users/ken/workspace/amplifier-rust/crates/amplifier-module-tool-web/src/fetch.rs`
- Modify: `/Users/ken/workspace/amplifier-rust/crates/amplifier-module-tool-web/src/lib.rs`
- Create: `/Users/ken/workspace/amplifier-rust/crates/amplifier-module-tool-web/tests/integration_test.rs`

- [ ] **Step 1: Write `Cargo.toml`**

```toml
[package]
name = "amplifier-module-tool-web"
version = "0.1.0"
edition = "2021"
description = "Web fetch and DuckDuckGo search tools for the amplifier-rust workspace"

[dependencies]
amplifier-core = { git = "https://github.com/microsoft/amplifier-core" }
reqwest = { version = "0.12", default-features = false, features = ["rustls-tls"] }
serde = { version = "1", features = ["derive"] }
serde_json = "1"
tokio = { version = "1", features = ["rt-multi-thread", "macros"] }
regex = "1"
urlencoding = "2"
log = "0.4"

[dev-dependencies]
wiremock = "0.6"
tokio = { version = "1", features = ["full"] }
```

- [ ] **Step 2: Write the `strip_html` unit tests (before writing the function)**

Create `tests/integration_test.rs`:

```rust
// Unit tests for fetch.rs HTML stripping logic
use amplifier_module_tool_web::fetch::strip_html;

#[test]
fn strip_html_removes_script_tags_and_content() {
    let html = r#"<html><script>alert("evil")</script><p>Hello</p></html>"#;
    let text = strip_html(html);
    assert!(!text.contains("alert"), "script content should be removed");
    assert!(text.contains("Hello"));
}

#[test]
fn strip_html_removes_style_tags_and_content() {
    let html = r#"<html><style>body { color: red }</style><p>World</p></html>"#;
    let text = strip_html(html);
    assert!(!text.contains("color"), "style content should be removed");
    assert!(text.contains("World"));
}

#[test]
fn strip_html_removes_nav_header_footer() {
    let html = r#"<nav>Menu items</nav><main>Content</main><footer>Footer text</footer>"#;
    let text = strip_html(html);
    assert!(!text.contains("Menu items"), "nav content should be removed");
    assert!(text.contains("Content"));
    assert!(!text.contains("Footer text"), "footer content should be removed");
}

#[test]
fn strip_html_collapses_whitespace() {
    let html = "<p>Hello   \n\n\t  world</p>";
    let text = strip_html(html);
    // Multiple whitespace should become single spaces
    assert!(!text.contains("   "), "multiple spaces should be collapsed");
    assert!(text.contains("Hello") && text.contains("world"));
}

#[test]
fn strip_html_truncates_at_8kb() {
    // Build HTML longer than 8KB
    let repeated = "<p>x</p>".repeat(2000); // ~16KB
    let text = strip_html(&repeated);
    assert!(text.len() <= 8 * 1024 + 100, "should be truncated near 8KB");
    assert!(text.ends_with("[...truncated at 8KB]"), "truncation notice missing: {:?}", &text[text.len().saturating_sub(50)..]);
}

#[test]
fn strip_html_plain_text_passthrough() {
    let plain = "Hello world. No tags here.";
    let text = strip_html(plain);
    assert!(text.contains("Hello world"));
}
```

- [ ] **Step 3: Run to verify tests fail**

```bash
cd /Users/ken/workspace/amplifier-rust
cargo test -p amplifier-module-tool-web 2>&1 | head -20
```

Expected: compile error — `fetch::strip_html` not defined.

- [ ] **Step 4: Write `src/fetch.rs`**

```rust
//! FetchUrlTool — HTTP GET with HTML stripping and 8KB response limit.

use amplifier_core::errors::ToolError;
use amplifier_core::messages::ToolSpec;
use amplifier_core::models::ToolResult;
use amplifier_core::traits::Tool;
use regex::Regex;
use reqwest::Client;
use serde_json::{json, Value};
use std::collections::HashMap;
use std::future::Future;
use std::pin::Pin;
use std::sync::OnceLock;
use std::time::Duration;

const MAX_BYTES: usize = 8 * 1024;
const TRUNCATION_NOTICE: &str = "[...truncated at 8KB]";

/// Strip boilerplate HTML elements and all tags, returning plain text.
///
/// Processing order:
/// 1. Remove `<script>...</script>` blocks and their content.
/// 2. Remove `<style>...</style>` blocks.
/// 3. Remove `<nav>...</nav>`, `<header>...</header>`, `<footer>...</footer>` blocks.
/// 4. Strip all remaining HTML tags.
/// 5. Collapse whitespace.
/// 6. Truncate at 8KB with notice.
pub fn strip_html(html: &str) -> String {
    // Patterns that remove both the tag and all content inside it.
    static SCRIPT_RE: OnceLock<Regex> = OnceLock::new();
    static STYLE_RE: OnceLock<Regex> = OnceLock::new();
    static NAV_RE: OnceLock<Regex> = OnceLock::new();
    static HEADER_RE: OnceLock<Regex> = OnceLock::new();
    static FOOTER_RE: OnceLock<Regex> = OnceLock::new();
    static TAG_RE: OnceLock<Regex> = OnceLock::new();
    static WHITESPACE_RE: OnceLock<Regex> = OnceLock::new();

    let script = SCRIPT_RE
        .get_or_init(|| Regex::new(r"(?is)<script[^>]*>.*?</script>").unwrap());
    let style = STYLE_RE
        .get_or_init(|| Regex::new(r"(?is)<style[^>]*>.*?</style>").unwrap());
    let nav = NAV_RE
        .get_or_init(|| Regex::new(r"(?is)<nav[^>]*>.*?</nav>").unwrap());
    let header = HEADER_RE
        .get_or_init(|| Regex::new(r"(?is)<header[^>]*>.*?</header>").unwrap());
    let footer = FOOTER_RE
        .get_or_init(|| Regex::new(r"(?is)<footer[^>]*>.*?</footer>").unwrap());
    let tag = TAG_RE.get_or_init(|| Regex::new(r"<[^>]+>").unwrap());
    let ws = WHITESPACE_RE.get_or_init(|| Regex::new(r"\s+").unwrap());

    let mut text = html.to_string();
    text = script.replace_all(&text, " ").into_owned();
    text = style.replace_all(&text, " ").into_owned();
    text = nav.replace_all(&text, " ").into_owned();
    text = header.replace_all(&text, " ").into_owned();
    text = footer.replace_all(&text, " ").into_owned();
    text = tag.replace_all(&text, " ").into_owned();
    text = ws.replace_all(&text, " ").into_owned();
    let text = text.trim().to_string();

    if text.len() > MAX_BYTES {
        let mut truncated = text[..MAX_BYTES].to_string();
        truncated.push_str(TRUNCATION_NOTICE);
        truncated
    } else {
        text
    }
}

/// Tool that fetches a URL and returns stripped plain text.
pub struct FetchUrlTool {
    client: Client,
}

impl FetchUrlTool {
    pub fn new() -> Self {
        Self { client: Client::builder().timeout(Duration::from_secs(30)).build().unwrap() }
    }

    pub fn spec() -> ToolSpec {
        ToolSpec {
            name: "fetch_url".to_string(),
            description: Some("Fetch content from a URL and return as text".to_string()),
            parameters: {
                let mut map = HashMap::new();
                map.insert("type".to_string(), json!("object"));
                map.insert("properties".to_string(), json!({
                    "url": {
                        "type": "string",
                        "description": "The URL to fetch"
                    },
                    "timeout_secs": {
                        "type": "integer",
                        "description": "Request timeout in seconds (default: 10)"
                    }
                }));
                map.insert("required".to_string(), json!(["url"]));
                map
            },
            extensions: HashMap::new(),
        }
    }

    async fn do_execute(&self, input: Value) -> Result<ToolResult, ToolError> {
        let url = input["url"].as_str().ok_or_else(|| ToolError::InvalidInput {
            message: "url parameter is required".to_string(),
        })?;
        let timeout_secs = input["timeout_secs"].as_u64().unwrap_or(10);
        let client = Client::builder()
            .timeout(Duration::from_secs(timeout_secs))
            .build()
            .map_err(|e| ToolError::Other { message: e.to_string() })?;

        let resp = client
            .get(url)
            .header("User-Agent", "amplifier-tool-web/0.1")
            .send()
            .await
            .map_err(|e| ToolError::Other { message: format!("fetch failed: {e}") })?;

        if !resp.status().is_success() {
            return Err(ToolError::Other {
                message: format!("HTTP {}: {}", resp.status().as_u16(), url),
            });
        }

        let body = resp
            .text()
            .await
            .map_err(|e| ToolError::Other { message: e.to_string() })?;

        let text = strip_html(&body);
        Ok(ToolResult {
            output: Some(json!(text)),
            error: None,
        })
    }
}

impl Default for FetchUrlTool {
    fn default() -> Self {
        Self::new()
    }
}

impl Tool for FetchUrlTool {
    fn spec(&self) -> ToolSpec {
        Self::spec()
    }

    fn execute<'a>(
        &'a self,
        input: Value,
    ) -> Pin<Box<dyn Future<Output = Result<ToolResult, ToolError>> + Send + 'a>> {
        Box::pin(async move { self.do_execute(input).await })
    }
}
```

- [ ] **Step 5: Wire `src/lib.rs`**

```rust
pub mod fetch;
pub mod search;
```

Create a stub `src/search.rs` so it compiles (Task 9 will fill it in):

```rust
// search.rs — SearchWebTool implemented in Task 9
```

- [ ] **Step 6: Run tests**

```bash
cd /Users/ken/workspace/amplifier-rust
cargo test -p amplifier-module-tool-web 2>&1
```

Expected:
```
test strip_html_removes_script_tags_and_content ... ok
test strip_html_removes_style_tags_and_content ... ok
test strip_html_removes_nav_header_footer ... ok
test strip_html_collapses_whitespace ... ok
test strip_html_truncates_at_8kb ... ok
test strip_html_plain_text_passthrough ... ok

test result: ok. 6 passed; 0 failed
```

- [ ] **Step 7: Commit**

```bash
git add crates/amplifier-module-tool-web
git commit -m "feat(tool-web): FetchUrlTool with HTML stripping + 8KB limit"
```

---

## Task 9: amplifier-module-tool-web — search_web + WebToolSuite

`search_web` scrapes DuckDuckGo's HTML endpoint and returns a JSON array of results. `WebToolSuite` is a simple struct that exposes both tools for registration.

**Files:**
- Modify: `/Users/ken/workspace/amplifier-rust/crates/amplifier-module-tool-web/src/search.rs`
- Modify: `/Users/ken/workspace/amplifier-rust/crates/amplifier-module-tool-web/src/lib.rs`
- Modify: `/Users/ken/workspace/amplifier-rust/crates/amplifier-module-tool-web/tests/integration_test.rs`

- [ ] **Step 1: Add search tests (append to the existing tests file)**

```rust
// ─── search_web tests ─────────────────────────────────────────────────────
use amplifier_module_tool_web::search::parse_ddg_results;

#[test]
fn ddg_parse_extracts_title_url_snippet() {
    // Minimal DuckDuckGo-style HTML with .result__title, .result__url, .result__snippet
    let html = r#"
        <div class="result">
            <a class="result__a" href="/l/?uddg=https%3A%2F%2Fexample.com">Example Title</a>
            <a class="result__url" href="https://example.com">example.com</a>
            <div class="result__snippet">This is the snippet text.</div>
        </div>
        <div class="result">
            <a class="result__a" href="/l/?uddg=https%3A%2F%2Frust-lang.org">Rust Language</a>
            <a class="result__url" href="https://rust-lang.org">rust-lang.org</a>
            <div class="result__snippet">Systems programming.</div>
        </div>
    "#;

    let results = parse_ddg_results(html, 5);
    assert_eq!(results.len(), 2);
    assert_eq!(results[0]["title"], "Example Title");
    assert!(results[0]["url"].as_str().unwrap().contains("example.com"),
        "url: {}", results[0]["url"]);
    assert_eq!(results[0]["snippet"], "This is the snippet text.");
    assert_eq!(results[1]["title"], "Rust Language");
}

#[test]
fn ddg_parse_respects_num_results_limit() {
    let mut html = String::new();
    for i in 0..10 {
        html.push_str(&format!(
            r#"<div class="result">
                <a class="result__a" href="/l/?uddg=https%3A%2F%2Fexample{i}.com">Title {i}</a>
                <a class="result__url">example{i}.com</a>
                <div class="result__snippet">Snippet {i}.</div>
            </div>"#
        ));
    }
    let results = parse_ddg_results(&html, 3);
    assert_eq!(results.len(), 3, "should be capped at 3");
}

#[test]
fn ddg_parse_empty_html_returns_empty() {
    let results = parse_ddg_results("<html><body></body></html>", 5);
    assert!(results.is_empty());
}
```

- [ ] **Step 2: Run to verify the new tests fail**

```bash
cd /Users/ken/workspace/amplifier-rust
cargo test -p amplifier-module-tool-web 2>&1 | tail -20
```

Expected: `error[E0432]: unresolved import` on `search::parse_ddg_results`.

- [ ] **Step 3: Write `src/search.rs`**

```rust
//! SearchWebTool — DuckDuckGo HTML scraper.
//!
//! GET https://duckduckgo.com/html/?q={encoded_query}
//! Parse: .result__a (title + URL), .result__url (display URL), .result__snippet (description)
//! Returns JSON array: [{"title": "...", "url": "...", "snippet": "..."}]

use amplifier_core::errors::ToolError;
use amplifier_core::messages::ToolSpec;
use amplifier_core::models::ToolResult;
use amplifier_core::traits::Tool;
use regex::Regex;
use reqwest::Client;
use serde_json::{json, Value};
use std::collections::HashMap;
use std::future::Future;
use std::pin::Pin;
use std::sync::OnceLock;
use std::time::Duration;
use urlencoding::encode;

/// Parse DuckDuckGo HTML results page into a JSON array.
///
/// Extracts `.result__a` (title + href), `.result__url`, and `.result__snippet`
/// using regex. Returns up to `num_results` items.
pub fn parse_ddg_results(html: &str, num_results: usize) -> Vec<Value> {
    // Match a full result block: title link + url + snippet.
    // DuckDuckGo's HTML format (as of 2025):
    // <a class="result__a" href="/l/?uddg=<encoded_url>">Title</a>
    // <a class="result__url" href="...">display</a>
    // <div class="result__snippet">Snippet text</div>
    static RESULT_RE: OnceLock<Regex> = OnceLock::new();
    static TITLE_RE: OnceLock<Regex> = OnceLock::new();
    static URL_RE: OnceLock<Regex> = OnceLock::new();
    static SNIPPET_RE: OnceLock<Regex> = OnceLock::new();
    static TAG_RE: OnceLock<Regex> = OnceLock::new();

    let result_re = RESULT_RE.get_or_init(|| {
        Regex::new(r#"(?is)<div[^>]+class="[^"]*result[^"]*"[^>]*>(.*?)</div>"#).unwrap()
    });
    let title_re = TITLE_RE.get_or_init(|| {
        Regex::new(r#"(?is)class="result__a"[^>]*href="[^"]*"[^>]*>(.*?)</a>"#).unwrap()
    });
    let url_re = URL_RE.get_or_init(|| {
        Regex::new(r#"(?is)class="result__a"[^>]*href="([^"]*)"[^>]*>"#).unwrap()
    });
    let snippet_re = SNIPPET_RE.get_or_init(|| {
        Regex::new(r#"(?is)class="result__snippet"[^>]*>(.*?)</div>"#).unwrap()
    });
    let tag_re = TAG_RE.get_or_init(|| Regex::new(r"<[^>]+>").unwrap());

    let mut results = Vec::new();

    for cap in result_re.captures_iter(html) {
        if results.len() >= num_results {
            break;
        }
        let block = &cap[1];

        let title = title_re
            .captures(block)
            .map(|c| tag_re.replace_all(&c[1], "").trim().to_string())
            .filter(|s| !s.is_empty());

        let url = url_re
            .captures(block)
            .map(|c| c[1].to_string())
            .map(|href| {
                // Extract the real URL from DuckDuckGo's redirect URL
                // e.g. /l/?uddg=https%3A%2F%2Fexample.com -> https://example.com
                if let Some(pos) = href.find("uddg=") {
                    let encoded = &href[pos + 5..];
                    urlencoding::decode(encoded).map(|s| s.into_owned()).unwrap_or(href)
                } else {
                    href
                }
            });

        let snippet = snippet_re
            .captures(block)
            .map(|c| tag_re.replace_all(&c[1], "").trim().to_string())
            .filter(|s| !s.is_empty());

        if let (Some(title), Some(url)) = (title, url) {
            results.push(json!({
                "title": title,
                "url": url,
                "snippet": snippet.unwrap_or_default(),
            }));
        }
    }
    results
}

pub struct SearchWebTool {
    client: Client,
}

impl SearchWebTool {
    pub fn new() -> Self {
        Self { client: Client::builder().timeout(Duration::from_secs(15)).build().unwrap() }
    }

    pub fn spec() -> ToolSpec {
        ToolSpec {
            name: "search_web".to_string(),
            description: Some("Search the web using DuckDuckGo".to_string()),
            parameters: {
                let mut map = HashMap::new();
                map.insert("type".to_string(), json!("object"));
                map.insert("properties".to_string(), json!({
                    "query": {
                        "type": "string",
                        "description": "Search query"
                    },
                    "num_results": {
                        "type": "integer",
                        "description": "Number of results to return (default: 5)"
                    }
                }));
                map.insert("required".to_string(), json!(["query"]));
                map
            },
            extensions: HashMap::new(),
        }
    }

    async fn do_execute(&self, input: Value) -> Result<ToolResult, ToolError> {
        let query = input["query"].as_str().ok_or_else(|| ToolError::InvalidInput {
            message: "query parameter is required".to_string(),
        })?;
        let num_results = input["num_results"].as_u64().unwrap_or(5) as usize;
        let encoded_query = encode(query);
        let url = format!("https://duckduckgo.com/html/?q={}", encoded_query);

        let resp = self
            .client
            .get(&url)
            .header("User-Agent", "Mozilla/5.0 (compatible; amplifier-search/0.1)")
            .send()
            .await
            .map_err(|e| ToolError::Other { message: format!("search failed: {e}") })?;

        let html = resp
            .text()
            .await
            .map_err(|e| ToolError::Other { message: e.to_string() })?;

        let results = parse_ddg_results(&html, num_results);
        Ok(ToolResult {
            output: Some(json!(results)),
            error: None,
        })
    }
}

impl Default for SearchWebTool {
    fn default() -> Self {
        Self::new()
    }
}

impl Tool for SearchWebTool {
    fn spec(&self) -> ToolSpec {
        Self::spec()
    }

    fn execute<'a>(
        &'a self,
        input: Value,
    ) -> Pin<Box<dyn Future<Output = Result<ToolResult, ToolError>> + Send + 'a>> {
        Box::pin(async move { self.do_execute(input).await })
    }
}
```

- [ ] **Step 4: Update `src/lib.rs` to export `WebToolSuite`**

```rust
pub mod fetch;
pub mod search;

use amplifier_core::traits::Tool;
use fetch::FetchUrlTool;
use search::SearchWebTool;
use std::sync::Arc;

/// Registers both `fetch_url` and `search_web` tools.
pub struct WebToolSuite;

impl WebToolSuite {
    /// Return both tools as `Arc<dyn Tool>` ready for registration.
    pub fn tools() -> Vec<(String, Arc<dyn Tool>)> {
        vec![
            (
                "fetch_url".to_string(),
                Arc::new(FetchUrlTool::new()) as Arc<dyn Tool>,
            ),
            (
                "search_web".to_string(),
                Arc::new(SearchWebTool::new()) as Arc<dyn Tool>,
            ),
        ]
    }
}
```

- [ ] **Step 5: Run all tests in the crate**

```bash
cd /Users/ken/workspace/amplifier-rust
cargo test -p amplifier-module-tool-web 2>&1
```

Expected:
```
test strip_html_removes_script_tags_and_content ... ok
test strip_html_removes_style_tags_and_content ... ok
test strip_html_removes_nav_header_footer ... ok
test strip_html_collapses_whitespace ... ok
test strip_html_truncates_at_8kb ... ok
test strip_html_plain_text_passthrough ... ok
test ddg_parse_extracts_title_url_snippet ... ok
test ddg_parse_respects_num_results_limit ... ok
test ddg_parse_empty_html_returns_empty ... ok

test result: ok. 9 passed; 0 failed
```

- [ ] **Step 6: Commit**

```bash
git add crates/amplifier-module-tool-web
git commit -m "feat(tool-web): SearchWebTool DuckDuckGo scraper + WebToolSuite"
```

---

## Task 10: amplifier-module-tool-todo — full crate

The todo tool is in-memory, session-scoped. No disk persistence. Three operations: `create` (replace all), `update` (same as create), `list`.

**Files:**
- Create: `/Users/ken/workspace/amplifier-rust/crates/amplifier-module-tool-todo/Cargo.toml`
- Create: `/Users/ken/workspace/amplifier-rust/crates/amplifier-module-tool-todo/src/lib.rs`
- Create: `/Users/ken/workspace/amplifier-rust/crates/amplifier-module-tool-todo/tests/integration_test.rs`

- [ ] **Step 1: Write `Cargo.toml`**

```toml
[package]
name = "amplifier-module-tool-todo"
version = "0.1.0"
edition = "2021"
description = "In-memory session-scoped todo list tool for the amplifier-rust workspace"

[dependencies]
amplifier-core = { git = "https://github.com/microsoft/amplifier-core" }
serde = { version = "1", features = ["derive"] }
serde_json = "1"
tokio = { version = "1", features = ["rt-multi-thread", "macros"] }
uuid = { version = "1", features = ["v4"] }
log = "0.4"

[dev-dependencies]
tokio = { version = "1", features = ["full"] }
```

- [ ] **Step 2: Write the tests first**

Create `tests/integration_test.rs`:

```rust
use amplifier_module_tool_todo::TodoTool;
use amplifier_core::traits::Tool;
use serde_json::json;

#[tokio::test]
async fn create_replaces_all_todos() {
    let tool = TodoTool::new();

    let result = tool.execute(json!({
        "action": "create",
        "todos": [
            {"content": "Write tests", "active_form": "Writing tests", "status": "in_progress"},
            {"content": "Run tests", "active_form": "Running tests", "status": "pending"}
        ]
    })).await.unwrap();

    let output = result.output.unwrap();
    assert_eq!(output["count"], 2);
    let todos = output["todos"].as_array().unwrap();
    assert_eq!(todos.len(), 2);
    // Each item has an id
    assert!(todos[0]["id"].is_string());
    assert_eq!(todos[0]["content"], "Write tests");
    assert_eq!(todos[0]["status"], "in_progress");
    assert_eq!(todos[1]["content"], "Run tests");
}

#[tokio::test]
async fn list_returns_current_todos() {
    let tool = TodoTool::new();

    // Create some todos
    tool.execute(json!({
        "action": "create",
        "todos": [{"content": "Task A", "active_form": "Doing A", "status": "pending"}]
    })).await.unwrap();

    // List them
    let result = tool.execute(json!({"action": "list"})).await.unwrap();
    let output = result.output.unwrap();
    assert_eq!(output["count"], 1);
    assert_eq!(output["todos"][0]["content"], "Task A");
}

#[tokio::test]
async fn update_replaces_all_todos() {
    let tool = TodoTool::new();

    // Create initial
    tool.execute(json!({
        "action": "create",
        "todos": [{"content": "Old task", "active_form": "Doing old", "status": "pending"}]
    })).await.unwrap();

    // Update replaces entirely
    let result = tool.execute(json!({
        "action": "update",
        "todos": [
            {"content": "New task 1", "active_form": "Doing 1", "status": "completed"},
            {"content": "New task 2", "active_form": "Doing 2", "status": "pending"}
        ]
    })).await.unwrap();

    let output = result.output.unwrap();
    assert_eq!(output["count"], 2);
    assert_eq!(output["todos"][0]["content"], "New task 1");
    assert!(!output["todos"].as_array().unwrap().iter().any(|t| t["content"] == "Old task"),
        "old tasks should be gone after update");
}

#[tokio::test]
async fn list_empty_returns_empty_array() {
    let tool = TodoTool::new();
    let result = tool.execute(json!({"action": "list"})).await.unwrap();
    let output = result.output.unwrap();
    assert_eq!(output["count"], 0);
    assert_eq!(output["todos"].as_array().unwrap().len(), 0);
}

#[tokio::test]
async fn unknown_action_returns_error() {
    let tool = TodoTool::new();
    let result = tool.execute(json!({"action": "delete"})).await;
    assert!(result.is_err(), "unknown action should return ToolError");
}

#[tokio::test]
async fn ids_are_unique_across_creates() {
    let tool = TodoTool::new();

    let r1 = tool.execute(json!({
        "action": "create",
        "todos": [{"content": "A", "active_form": "Doing A", "status": "pending"}]
    })).await.unwrap();

    let r2 = tool.execute(json!({
        "action": "create",
        "todos": [{"content": "B", "active_form": "Doing B", "status": "pending"}]
    })).await.unwrap();

    let id1 = r1.output.unwrap()["todos"][0]["id"].as_str().unwrap().to_string();
    let id2 = r2.output.unwrap()["todos"][0]["id"].as_str().unwrap().to_string();
    assert_ne!(id1, id2, "Each todo must get a unique ID");
}
```

- [ ] **Step 3: Run to verify tests fail**

```bash
cd /Users/ken/workspace/amplifier-rust
cargo test -p amplifier-module-tool-todo 2>&1 | head -15
```

Expected: compile errors — `TodoTool` not defined.

- [ ] **Step 4: Write `src/lib.rs`**

```rust
//! In-memory todo list tool, session-scoped.
//!
//! Actions: create (replace all), update (same as create), list.
//! IDs are assigned by the tool (UUID v4) on create/update.
//! No disk persistence — the Android app handles storage separately.

use amplifier_core::errors::ToolError;
use amplifier_core::messages::ToolSpec;
use amplifier_core::models::ToolResult;
use amplifier_core::traits::Tool;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::collections::HashMap;
use std::future::Future;
use std::pin::Pin;
use std::sync::Mutex;
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "snake_case")]
pub enum TodoStatus {
    Pending,
    InProgress,
    Completed,
}

impl std::fmt::Display for TodoStatus {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let s = match self {
            TodoStatus::Pending => "pending",
            TodoStatus::InProgress => "in_progress",
            TodoStatus::Completed => "completed",
        };
        write!(f, "{s}")
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TodoItem {
    pub id: String,
    pub content: String,
    pub active_form: String,
    pub status: String, // stored as string to be flexible with input
}

/// In-memory todo list, session-scoped.
pub struct TodoTool {
    items: Mutex<Vec<TodoItem>>,
}

impl TodoTool {
    pub fn new() -> Self {
        Self { items: Mutex::new(Vec::new()) }
    }

    pub fn spec() -> ToolSpec {
        ToolSpec {
            name: "todo".to_string(),
            description: Some(
                "Manage a session-scoped todo list. Actions: create (replace all), update (replace all), list."
                    .to_string(),
            ),
            parameters: {
                let mut map = HashMap::new();
                map.insert("type".to_string(), json!("object"));
                map.insert("properties".to_string(), json!({
                    "action": {
                        "type": "string",
                        "enum": ["create", "update", "list"],
                        "description": "Operation: 'create' or 'update' replaces all todos; 'list' returns current"
                    },
                    "todos": {
                        "type": "array",
                        "description": "Array of todo items (required for create/update)",
                        "items": {
                            "type": "object",
                            "properties": {
                                "content": {"type": "string"},
                                "active_form": {"type": "string", "description": "Present continuous: 'Running tests'"},
                                "status": {"type": "string", "enum": ["pending", "in_progress", "completed"]}
                            },
                            "required": ["content", "active_form", "status"]
                        }
                    }
                }));
                map.insert("required".to_string(), json!(["action"]));
                map
            },
            extensions: HashMap::new(),
        }
    }

    fn to_output(items: &[TodoItem]) -> Value {
        json!({
            "count": items.len(),
            "todos": items.iter().map(|item| json!({
                "id": item.id,
                "content": item.content,
                "active_form": item.active_form,
                "status": item.status,
            })).collect::<Vec<_>>()
        })
    }

    fn do_execute(&self, input: Value) -> Result<ToolResult, ToolError> {
        let action = input["action"]
            .as_str()
            .ok_or_else(|| ToolError::InvalidInput {
                message: "action parameter is required".to_string(),
            })?;

        match action {
            "create" | "update" => {
                let raw = input["todos"].as_array().ok_or_else(|| ToolError::InvalidInput {
                    message: "todos array is required for create/update".to_string(),
                })?;

                let new_items: Vec<TodoItem> = raw
                    .iter()
                    .map(|t| TodoItem {
                        id: Uuid::new_v4().to_string(),
                        content: t["content"].as_str().unwrap_or("").to_string(),
                        active_form: t["active_form"].as_str().unwrap_or("").to_string(),
                        status: t["status"].as_str().unwrap_or("pending").to_string(),
                    })
                    .collect();

                let mut guard = self.items.lock().unwrap();
                *guard = new_items;
                Ok(ToolResult { output: Some(Self::to_output(&guard)), error: None })
            }
            "list" => {
                let guard = self.items.lock().unwrap();
                Ok(ToolResult { output: Some(Self::to_output(&guard)), error: None })
            }
            other => Err(ToolError::InvalidInput {
                message: format!("unknown action: '{}'. Must be create, update, or list.", other),
            }),
        }
    }
}

impl Default for TodoTool {
    fn default() -> Self {
        Self::new()
    }
}

impl Tool for TodoTool {
    fn spec(&self) -> ToolSpec {
        Self::spec()
    }

    fn execute<'a>(
        &'a self,
        input: Value,
    ) -> Pin<Box<dyn Future<Output = Result<ToolResult, ToolError>> + Send + 'a>> {
        let result = self.do_execute(input);
        Box::pin(async move { result })
    }
}
```

- [ ] **Step 5: Run tests**

```bash
cd /Users/ken/workspace/amplifier-rust
cargo test -p amplifier-module-tool-todo 2>&1
```

Expected:
```
test create_replaces_all_todos ... ok
test list_returns_current_todos ... ok
test update_replaces_all_todos ... ok
test list_empty_returns_empty_array ... ok
test unknown_action_returns_error ... ok
test ids_are_unique_across_creates ... ok

test result: ok. 6 passed; 0 failed
```

- [ ] **Step 6: Commit**

```bash
git add crates/amplifier-module-tool-todo
git commit -m "feat(tool-todo): in-memory session-scoped todo list"
```

---

## Task 11: amplifier-android — create `jni_hooks.rs`

`KotlinHookBridge` wraps any Kotlin object that implements `HookCallback` (see Task 12) into the `Hook` trait from `amplifier-module-orchestrator-loop-streaming`. The pattern is identical to `KotlinToolBridge` in `jni_tools.rs`.

**Files:**
- Create: `/Users/ken/workspace/vela/app/src/main/rust/amplifier-android/src/jni_hooks.rs`
- Modify: `/Users/ken/workspace/vela/app/src/main/rust/amplifier-android/src/lib.rs` (add `mod jni_hooks;`)

- [ ] **Step 1: Read `jni_tools.rs` to confirm the JNI call pattern before writing**

```bash
cat /Users/ken/workspace/vela/app/src/main/rust/amplifier-android/src/jni_tools.rs
```

Specifically note how `call_kotlin()` calls `env.call_method(...)` — you'll replicate this for `handleHook`.

- [ ] **Step 2: Update `amplifier-android/Cargo.toml` to add Phase 2 workspace crate deps**

Read the current file first:
```bash
cat /Users/ken/workspace/vela/app/src/main/rust/amplifier-android/Cargo.toml
```

Add the orchestrator and tool crates (needed for Hook trait + TodoTool):

```toml
# Phase 2 workspace crates (add after existing amplifier-core dep)
amplifier-module-orchestrator-loop-streaming = { path = "../../../../workspace/amplifier-rust/crates/amplifier-module-orchestrator-loop-streaming" }
amplifier-module-tool-todo = { path = "../../../../workspace/amplifier-rust/crates/amplifier-module-tool-todo" }
amplifier-module-tool-web = { path = "../../../../workspace/amplifier-rust/crates/amplifier-module-tool-web" }
async-trait = "0.1"
```

> **Path note:** The relative path from `amplifier-android/` to `amplifier-rust/` is `../../../../workspace/amplifier-rust/` when both repos sit inside `/Users/ken/workspace/`. Adjust if your directory layout differs from `~/workspace/vela/` and `~/workspace/amplifier-rust/`.

- [ ] **Step 3: Write `src/jni_hooks.rs`**

```rust
//! KotlinHookBridge — wraps a Kotlin `HookCallback` object as a Rust `Hook`.
//!
//! Each registered Kotlin hook becomes one `KotlinHookBridge`.
//! All bridges share the same JVM reference.
//!
//! JNI call path:
//!   KotlinHookBridge::handle(event, ctx)
//!     → attach current thread to JVM
//!     → call HookCallback.handleHook(event_str: String, ctx_json: String): String
//!     → parse returned JSON into HookResult
//!
//! The returned JSON must conform to amplifier-core HookResult format:
//!   {"action": "continue" | "deny" | "inject_context", "context_injection": "..."}

use std::sync::Arc;

use amplifier_module_orchestrator_loop_streaming::{Hook, HookContext, HookEvent, HookResult};
use async_trait::async_trait;
use jni::objects::{GlobalRef, JString, JValue};
use jni::JavaVM;
use log::warn;
use serde_json::Value;

pub struct KotlinHookBridge {
    /// Reference to the Kotlin `HookCallback` object.
    callback: Arc<GlobalRef>,
    /// Events this hook is registered for (subset of HookEvent variants).
    events: Vec<HookEvent>,
    /// Shared JVM for attaching worker threads.
    jvm: Arc<JavaVM>,
}

impl KotlinHookBridge {
    pub fn new(callback: Arc<GlobalRef>, events: Vec<HookEvent>, jvm: Arc<JavaVM>) -> Self {
        Self { callback, events, jvm }
    }

    fn call_kotlin(&self, event_str: &str, ctx_json: &str) -> Option<String> {
        let mut env = match self.jvm.attach_current_thread() {
            Ok(e) => e,
            Err(e) => {
                warn!("KotlinHookBridge: failed to attach JVM thread: {e:?}");
                return None;
            }
        };

        let j_event = match env.new_string(event_str) {
            Ok(s) => s,
            Err(e) => {
                warn!("KotlinHookBridge: new_string(event) failed: {e:?}");
                return None;
            }
        };
        let j_ctx = match env.new_string(ctx_json) {
            Ok(s) => s,
            Err(e) => {
                warn!("KotlinHookBridge: new_string(ctx) failed: {e:?}");
                return None;
            }
        };

        let result = env.call_method(
            self.callback.as_obj(),
            "handleHook",
            "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
            &[JValue::Object(&j_event), JValue::Object(&j_ctx)],
        );

        match result {
            Ok(jval) => {
                let jstr: JString = match jval.l() {
                    Ok(o) => o.into(),
                    Err(e) => {
                        warn!("KotlinHookBridge: result not an object: {e:?}");
                        return None;
                    }
                };
                match unsafe { env.get_string(&jstr) } {
                    Ok(s) => Some(s.into()),
                    Err(e) => {
                        warn!("KotlinHookBridge: get_string failed: {e:?}");
                        None
                    }
                }
            }
            Err(e) => {
                warn!("KotlinHookBridge: handleHook JNI call failed: {e:?}");
                None
            }
        }
    }
}

#[async_trait]
impl Hook for KotlinHookBridge {
    fn events(&self) -> &[HookEvent] {
        &self.events
    }

    async fn handle(&self, event: HookEvent, ctx: &HookContext) -> HookResult {
        let event_str = format!("{:?}", event); // e.g. "ToolPre"
        let ctx_json = serde_json::to_string(ctx).unwrap_or_else(|_| "{}".to_string());

        let Some(json_str) = self.call_kotlin(&event_str, &ctx_json) else {
            // Kotlin returned null or call failed — treat as "continue"
            return HookResult::default();
        };

        // Parse: {"action": "continue"|"deny"|"inject_context", "context_injection": "..."}
        let v: Value = match serde_json::from_str(&json_str) {
            Ok(v) => v,
            Err(e) => {
                warn!("KotlinHookBridge: failed to parse HookResult JSON: {e}");
                return HookResult::default();
            }
        };

        HookResult {
            action: v["action"].as_str().unwrap_or("continue").to_string(),
            context_injection: v["context_injection"].as_str().map(|s| s.to_string()),
        }
    }
}

// ─────────────────────────────── Builder ────────────────────────────────────

/// Parse the events list from a `HookRegistration.events: Array<String>` JNI value.
///
/// Accepted string values (case-insensitive):
/// "session_start", "session_end", "provider_request", "tool_pre", "tool_post"
pub fn parse_hook_events(event_names: &[String]) -> Vec<HookEvent> {
    event_names
        .iter()
        .filter_map(|name| match name.to_lowercase().as_str() {
            "session_start" => Some(HookEvent::SessionStart),
            "session_end" => Some(HookEvent::SessionEnd),
            "provider_request" => Some(HookEvent::ProviderRequest),
            "tool_pre" => Some(HookEvent::ToolPre),
            "tool_post" => Some(HookEvent::ToolPost),
            other => {
                warn!("parse_hook_events: unknown event '{}' — skipped", other);
                None
            }
        })
        .collect()
}
```

> **HookEvent variants:** The exact variant names depend on what `amplifier-module-orchestrator-loop-streaming` exposes. Check that crate's `src/lib.rs` (built in Phase 1) and update the `parse_hook_events` match arms if the names differ.

- [ ] **Step 4: Add `mod jni_hooks;` to `src/lib.rs`**

Open `lib.rs` and add alongside the other `mod` declarations:

```rust
mod jni_hooks;
```

- [ ] **Step 5: Verify it compiles**

```bash
cd /Users/ken/workspace/vela/app/src/main/rust/amplifier-android
cargo build 2>&1 | tail -10
```

Expected: `Finished` with no errors. If there are `HookEvent` variant name mismatches, fix them to match what the orchestrator crate exports.

- [ ] **Step 6: Commit**

```bash
cd /Users/ken/workspace/vela
git add app/src/main/rust/amplifier-android/src/jni_hooks.rs \
        app/src/main/rust/amplifier-android/src/lib.rs \
        app/src/main/rust/amplifier-android/Cargo.toml
git commit -m "feat(android): KotlinHookBridge JNI implementation"
```

---

## Task 12: AmplifierBridge.kt — add `HookCallback`, `HookRegistration`, update `nativeRun`

**Files:**
- Modify: `/Users/ken/workspace/vela/app/src/main/kotlin/com/vela/app/ai/AmplifierBridge.kt`

- [ ] **Step 1: Read the full current AmplifierBridge.kt**

```bash
cat /Users/ken/workspace/vela/app/src/main/kotlin/com/vela/app/ai/AmplifierBridge.kt
```

- [ ] **Step 2: Replace the entire file with the updated version**

The only changes are:
1. Add `HookCallback` fun interface
2. Add `HookRegistration` data class
3. Add `hookCallbacks: Array<HookRegistration>` parameter to `nativeRun`
4. Remove `providerRequestCb: ProviderRequestCallback` parameter (absorbed into hooks)
5. Remove `ProviderRequestCallback` fun interface

```kotlin
package com.vela.app.ai

/**
 * JNI bridge to the amplifier-android Rust crate (libamplifier_android.so).
 *
 * The Rust side implements the agent loop:
 *  - AnthropicProvider — HTTP to api.anthropic.com/v1/messages
 *  - LoopOrchestrator  — tool-calling loop (≤10 steps) with full HookRegistry
 *  - SimpleContext     — in-memory message history
 *
 * Tool calls delegate back to Kotlin so Android-specific logic stays in Kotlin.
 * Hook callbacks delegate back to Kotlin via [HookCallback] — existing Kotlin
 * hooks (VaultSyncHook, StatusContextHook, etc.) are wrapped without changes.
 */
object AmplifierBridge {

    init { System.loadLibrary("amplifier_android") }

    external fun nativeRun(
        apiKey:          String,
        model:           String,
        toolsJson:       String,
        historyJson:     String,
        userInput:       String,
        userContentJson: String?,       // null = plain text; non-null = content blocks JSON
        systemPrompt:    String,
        tokenCb:         TokenCallback,
        toolCb:          ToolCallback,
        hookCallbacks:   Array<HookRegistration>,  // Kotlin hooks registered with HookRegistry
        serverToolCb:    ServerToolCallback,
    ): String

    /** Per-token streaming callback — called from the Rust decode loop. */
    fun interface TokenCallback {
        fun onToken(token: String)
    }

    /**
     * Tool execution callback.
     *
     * @param name     Tool name (e.g. "search_web")
     * @param argsJson JSON object of arguments
     * @return         Tool result string passed back to the model
     */
    fun interface ToolCallback {
        fun executeTool(name: String, argsJson: String): String
    }

    /**
     * Server tool callback — called when Anthropic server tools (e.g. web_search_20250305)
     * execute. UI display only — no local execution needed.
     *
     * @param name     Tool name, e.g. "web_search"
     * @param argsJson JSON object of arguments, e.g. {"query":"..."}
     */
    fun interface ServerToolCallback {
        fun onServerTool(name: String, argsJson: String)
    }

    /**
     * Hook callback — called by the Rust HookRegistry at lifecycle events.
     *
     * @param event      Event name: "SessionStart", "SessionEnd", "ProviderRequest",
     *                   "ToolPre", "ToolPost"
     * @param contextJson JSON string of HookContext data for the event
     * @return JSON string: {"action": "continue"|"deny"|"inject_context",
     *                       "context_injection": "optional text"}
     *         Return {"action":"continue"} (or null) to do nothing.
     */
    fun interface HookCallback {
        fun handleHook(event: String, contextJson: String): String
    }

    /**
     * Registers a Kotlin hook with the Rust HookRegistry.
     *
     * @param events   Which lifecycle events to receive. Accepted values:
     *                 "session_start", "session_end", "provider_request", "tool_pre", "tool_post"
     * @param callback The hook handler.
     */
    data class HookRegistration(
        val events: Array<String>,
        val callback: HookCallback,
    ) {
        // Auto-generated equals/hashCode for Array fields
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is HookRegistration) return false
            return events.contentEquals(other.events) && callback == other.callback
        }
        override fun hashCode(): Int = 31 * events.contentHashCode() + callback.hashCode()
    }
}
```

- [ ] **Step 3: Verify the Kotlin file is syntactically correct**

```bash
cd /Users/ken/workspace/vela
./gradlew :app:compileDebugKotlin 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL` or at worst errors in *other* files — not in `AmplifierBridge.kt`.

> If `AmplifierSession.kt` fails because it references the now-removed `ProviderRequestCallback`, see the note in Step 4.

- [ ] **Step 4: Update `AmplifierSession.kt` to use the new signature**

The `ProviderRequestCallback` that was previously passed as a parameter must now be registered as a `HookRegistration` with event `"provider_request"`. Open `AmplifierSession.kt` and find where `nativeRun` is called. Update the call site:

**Old call site (approximate):**
```kotlin
AmplifierBridge.nativeRun(
    apiKey       = getApiKey(),
    model        = getModel(),
    toolsJson    = toolsJson,
    historyJson  = historyJson,
    userInput    = userInput,
    userContentJson = userContentJson,
    systemPrompt = systemPrompt,
    tokenCb      = AmplifierBridge.TokenCallback { token -> onToken(token) },
    toolCb       = AmplifierBridge.ToolCallback { name, args -> onToolStart(name, args) },
    providerRequestCb = AmplifierBridge.ProviderRequestCallback { onProviderRequest() },
    serverToolCb = AmplifierBridge.ServerToolCallback { name, args -> onServerTool(name, args) }
)
```

**New call site:**
```kotlin
AmplifierBridge.nativeRun(
    apiKey          = getApiKey(),
    model           = getModel(),
    toolsJson       = toolsJson,
    historyJson     = historyJson,
    userInput       = userInput,
    userContentJson = userContentJson,
    systemPrompt    = systemPrompt,
    tokenCb         = AmplifierBridge.TokenCallback { token -> onToken(token) },
    toolCb          = AmplifierBridge.ToolCallback { name, args -> onToolStart(name, args) },
    hookCallbacks   = arrayOf(
        // ProviderRequestCallback is now a hook on the "provider_request" event.
        AmplifierBridge.HookRegistration(
            events   = arrayOf("provider_request"),
            callback = AmplifierBridge.HookCallback { _, _ ->
                val injection = onProviderRequest()
                if (injection.isNullOrEmpty()) {
                    """{"action":"continue"}"""
                } else {
                    """{"action":"inject_context","context_injection":${
                        org.json.JSONObject.quote(injection)
                    }}"""
                }
            }
        )
        // Add VaultSyncHook, StatusContextHook, etc. here as additional HookRegistrations
        // when those hooks are ready to be migrated (out of scope for this task).
    ),
    serverToolCb    = AmplifierBridge.ServerToolCallback { name, args -> onServerTool(name, args) }
)
```

- [ ] **Step 5: Verify the full build succeeds**

```bash
cd /Users/ken/workspace/vela
./gradlew :app:compileDebugKotlin 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
cd /Users/ken/workspace/vela
git add app/src/main/kotlin/com/vela/app/ai/AmplifierBridge.kt \
        app/src/main/kotlin/com/vela/app/ai/AmplifierSession.kt
git commit -m "feat(android): add HookCallback/HookRegistration; migrate ProviderRequestCallback to hook"
```

---

## Task 13: amplifier-android lib.rs — wire HookRegistry

Wire the new `hookCallbacks` JNI array parameter into a `HookRegistry` and pass it to the orchestrator's `execute()`.

**Files:**
- Modify: `/Users/ken/workspace/vela/app/src/main/rust/amplifier-android/src/lib.rs`

- [ ] **Step 1: Read the current `lib.rs` to understand the existing JNI entry point signature and wiring**

```bash
cat /Users/ken/workspace/vela/app/src/main/rust/amplifier-android/src/lib.rs
```

Take note of:
- The `#[no_mangle]` function name (JNI naming convention)
- How `JObject` parameters map to Kotlin types
- How `KotlinToolBridge` instances are built from JNI objects
- The pattern for extracting `GlobalRef` from `JObject`

- [ ] **Step 2: Update the nativeRun JNI function signature**

The function signature must match the new Kotlin `nativeRun`. The old `providerRequestCb` parameter is removed; a new `hook_callbacks` `JObjectArray` parameter is added.

Find the `#[no_mangle]` function in `lib.rs`. It will look like:
```rust
pub extern "C" fn Java_com_vela_app_ai_AmplifierBridge_nativeRun(
    mut env: JNIEnv,
    _class: JClass,
    api_key: JString,
    model: JString,
    tools_json: JString,
    history_json: JString,
    user_input: JString,
    user_content_json: JObject,
    system_prompt: JString,
    token_cb: JObject,
    tool_cb: JObject,
    provider_request_cb: JObject,   // ← REMOVE this
    server_tool_cb: JObject,
) -> jstring {
```

Update it to:
```rust
pub extern "C" fn Java_com_vela_app_ai_AmplifierBridge_nativeRun(
    mut env: JNIEnv,
    _class: JClass,
    api_key: JString,
    model: JString,
    tools_json: JString,
    history_json: JString,
    user_input: JString,
    user_content_json: JObject,
    system_prompt: JString,
    token_cb: JObject,
    tool_cb: JObject,
    hook_callbacks: JObject,    // Array<HookRegistration> from Kotlin
    server_tool_cb: JObject,
) -> jstring {
```

- [ ] **Step 3: Add hook registry construction in the function body**

After the existing tool map construction (`let tool_map = build_tool_map(...)`), add:

```rust
use jni::objects::JObjectArray;
use crate::jni_hooks::{KotlinHookBridge, parse_hook_events};
use amplifier_module_orchestrator_loop_streaming::HookRegistry;

// Build the HookRegistry from the hookCallbacks array
let hook_registry = {
    let mut registry = HookRegistry::new();
    let jvm = Arc::clone(&jvm);

    // hook_callbacks is Array<HookRegistration>
    // Each element has fields: events: Array<String>, callback: HookCallback
    let cb_array: JObjectArray = hook_callbacks.into();
    let len = env.get_array_length(&cb_array).unwrap_or(0);

    for i in 0..len {
        let registration = env
            .get_object_array_element(&cb_array, i)
            .unwrap();

        // Get the events array field
        let events_obj = env
            .get_field(&registration, "events", "[Ljava/lang/String;")
            .and_then(|v| v.l())
            .unwrap_or_default();
        let events_arr: JObjectArray = events_obj.into();
        let events_len = env.get_array_length(&events_arr).unwrap_or(0);
        let mut event_names: Vec<String> = Vec::new();
        for j in 0..events_len {
            let s_obj = env
                .get_object_array_element(&events_arr, j)
                .unwrap();
            let jstr: jni::objects::JString = s_obj.into();
            if let Ok(s) = unsafe { env.get_string(&jstr) } {
                event_names.push(s.into());
            }
        }

        // Get the callback field
        let cb_obj = env
            .get_field(&registration, "callback", "Lcom/vela/app/ai/AmplifierBridge$HookCallback;")
            .and_then(|v| v.l())
            .unwrap_or_default();
        let cb_global = env.new_global_ref(cb_obj).unwrap();

        let events = parse_hook_events(&event_names);
        if !events.is_empty() {
            let bridge = KotlinHookBridge::new(
                Arc::new(cb_global),
                events,
                Arc::clone(&jvm),
            );
            registry.register(Arc::new(bridge));
        }
    }
    registry
};
```

Then pass `&hook_registry` to the orchestrator's `execute()` call instead of wherever the hook registry was previously wired.

- [ ] **Step 4: Remove the old `provider_request_cb` GlobalRef construction**

Find any code that built a `GlobalRef` from `provider_request_cb` and used it as `ProviderRequestCallback`. Remove it — this is now handled by the hook registered in `AmplifierSession.kt`.

- [ ] **Step 5: Verify compilation**

```bash
cd /Users/ken/workspace/vela/app/src/main/rust/amplifier-android
cargo build 2>&1 | tail -15
```

Expected: `Finished` with no errors.

If you see "function `register` not found on `HookRegistry`": check `amplifier-module-orchestrator-loop-streaming/src/lib.rs` (built in Phase 1) for the actual method name (`register`, `add`, `push`, etc.) and use that name instead.

- [ ] **Step 6: Run the full Gradle build to verify JNI ABI matches**

```bash
cd /Users/ken/workspace/vela
./gradlew :app:assembleDebug 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`. If there are JNI method signature errors, verify the `nativeRun` parameter order matches exactly between Kotlin and Rust.

- [ ] **Step 7: Commit**

```bash
cd /Users/ken/workspace/vela
git add app/src/main/rust/amplifier-android/src/lib.rs
git commit -m "feat(android): wire HookRegistry from Kotlin hookCallbacks array"
```

---

## Task 14: Final workspace build verification

Verify all 10 crates compile together and no test is broken.

**No files changed — this is a verification-only task.**

- [ ] **Step 1: Build the entire workspace**

```bash
cd /Users/ken/workspace/amplifier-rust
cargo build --workspace 2>&1 | tail -20
```

Expected: All 10 crates compile. `Finished dev [unoptimized + debuginfo]` with no errors.

- [ ] **Step 2: Run all workspace tests (excluding the ignored Ollama real-server test)**

```bash
cd /Users/ken/workspace/amplifier-rust
cargo test --workspace 2>&1
```

Expected: All non-ignored tests pass across all 10 crates. Summary line should show `N passed; 0 failed`.

- [ ] **Step 3: Run the Android crate build for good measure**

```bash
cd /Users/ken/workspace/vela/app/src/main/rust/amplifier-android
cargo build 2>&1 | tail -5
```

Expected: `Finished`.

- [ ] **Step 4: Run the to-be-confirmed ignored Ollama test if you have Ollama running**

```bash
# Only if Ollama is running locally with llama3.2 pulled:
cd /Users/ken/workspace/amplifier-rust
cargo test -p amplifier-module-provider-ollama -- --ignored 2>&1
```

Expected: `test ollama_real_server_completion ... ok`

- [ ] **Step 5: Final commit tag**

```bash
cd /Users/ken/workspace/amplifier-rust
git tag -a v0.2.0 -m "Phase 2: Gemini + OpenAI Responses API + Ollama providers; web + todo tools"
git push origin v0.2.0
```

```bash
cd /Users/ken/workspace/vela
git tag -a amplifier-android-phase2 -m "Phase 2: HookCallback JNI bridge wired"
git push origin amplifier-android-phase2
```

---

## Summary

| Task | Deliverable | Key TDD focus |
|------|-------------|---------------|
| 1 | Workspace Cargo.toml + 5 crate stubs | Workspace parses |
| 2 | Gemini scaffold: Cargo.toml + types.rs | Compiles |
| 3 | Gemini SSE parser | Synthetic ID uniqueness |
| 4 | Gemini full Provider + wiremock tests | Streaming parse, tool calls |
| 5 | OpenAI scaffold: Cargo.toml + responses.rs | Compiles |
| 6 | OpenAI full Provider | Bearer auth, auto-continuation |
| 7 | Ollama full crate | base_url config, ChatCompletions |
| 8 | tool-web: fetch_url | HTML stripping, 8KB truncation |
| 9 | tool-web: search_web + WebToolSuite | DuckDuckGo HTML parse |
| 10 | tool-todo full crate | create/update/list, unique IDs |
| 11 | jni_hooks.rs + Cargo.toml | KotlinHookBridge JNI pattern |
| 12 | AmplifierBridge.kt | HookCallback interface, session migration |
| 13 | lib.rs hook wiring | JNI array traversal, HookRegistry |
| 14 | Full build verification | All 10 crates, all tests green |

**New test count after Phase 2:** ≥ 25 tests across 5 new crates (all passing, 1 ignored).
