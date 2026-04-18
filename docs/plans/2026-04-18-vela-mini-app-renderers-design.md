# Vela Mini App Renderers Design

## Goal

Transform Vela from a chat-centric app into a personal app ecosystem. Every vault content type gets a fully realized, LLM-generated mini app (WebView + HTML/CSS/JS) that renders beautifully and can interoperate with other mini apps through a capabilities graph. App navigation is restructured around four top-level sections (Projects, Vault, Connectors, Profile), with Vault as the hero feature. The system adapts to phone and tablet form factors.

## Overview

The system has five interconnected layers:

1. **System Architecture** — Four-section adaptive navigation, WebView-based mini app rendering, Room-backed capabilities graph
2. **Vela Mini App SDK** — `window.vela` bridge with four namespaces (`db`, `events`, `ai`, `vault`) injected into every WebView
3. **Capabilities Graph + Renderer Lifecycle** — English-described capability manifests, one-shot renderer generation, additive evolution
4. **Navigation & Adaptive Layout** — `WindowSizeClass`-aware scaffold, `ConversationScreen` decomposition, master/detail vault on tablet
5. **Components & Error Handling** — Net-new files, modified files, removed code, failure modes, testing strategy

Data flows from vault content → LLM renderer generation → cached HTML + capability manifest → WebView with SDK bridge → cross-app communication via events and shared document store.

---

## Section 1 — System Architecture

### Navigation

Four top-level sections:

| Section        | Purpose                                          |
|----------------|--------------------------------------------------|
| **Projects**   | Existing chat sessions                           |
| **Vault**      | Promoted to hero feature, master/detail on tablet|
| **Connectors** | Vela nodes (SSH) + external service connectors   |
| **Profile**    | Settings                                         |

On phone: Material 3 `NavigationBar` (bottom). On tablet: `NavigationRail` (left) + two-pane layouts.

### Mini Apps

Every vault content type gets a WebView rendering a generated HTML/CSS/JS "mini app." On first encounter with an unknown content type, Vela sends the content plus the full capabilities graph to the LLM, which generates a bespoke HTML page. That renderer is cached in Room DB and loaded instantly on every subsequent visit.

Evolution is additive and incremental — the renderer updates only when the underlying content changes in ways the current renderer can't handle.

### Vela Mini App SDK

Every WebView has `window.vela` injected at load time with four namespaces:

- `vela.db` — schemaless document store
- `vela.events` — bidirectional pub/sub
- `vela.ai` — direct LLM access via AmplifierSession
- `vela.vault` — vault read/write/sync

### Capabilities Graph

Stored in Room DB (`mini_app_registry`). Each registered mini app declares what it `provides` and `consumes` in descriptive English. When generating a new renderer, the full graph is injected into the LLM prompt so it can make intelligent connection decisions.

---

## Section 2 — The Vela Mini App SDK

Android injects via `addJavascriptInterface`, available as `window.vela`. Events going native→JS use `webView.evaluateJavascript`.

### `vela.db` — Schemaless Document Store

Backed by a new Room table (`mini_app_documents`) with columns: `scope_prefix`, `collection`, `id`, `data` (JSON text), `updated_at`.

**Scope is LLM-determined at renderer generation time**, not hardcoded. Three prefixes:

| Prefix    | Visibility                              | Example                                                  |
|-----------|-----------------------------------------|----------------------------------------------------------|
| `local:`  | Scoped to the current vault item's path | Step completion in `carbonara.md` — separate per recipe  |
| `global:` | Shared across every mini app and session| Shopping list backing store, accessible from any mini app |
| `type:`   | Shared across all mini apps of the same content type | Recently-used ingredients shared across all recipe renderers |

When generating a new renderer, `__VELA_CONTEXT__` includes current `global:` and `type:` collection snapshots so the LLM can see what already exists and wire into existing collections rather than inventing new ones. The LLM documents its scope reasoning in comments within the generated JS.

**Methods:**

- `put(collection, id, data)` — upsert a document
- `get(collection, id)` — read a document
- `delete(collection, id)` — remove a document
- `watch(collection, cb)` — observes changes to a collection. Implemented as a Room `Flow` collected in a background coroutine. Changes are delivered to the WebView via `evaluateJavascript`. The observer is torn down when the WebView is destroyed.

The `collection` argument must begin with `local:`, `global:`, or `type:`. An unrecognised prefix throws an error.

Complex query/index support is deferred to a later on-device sync optimization pass.

### `vela.events` — Bidirectional Pub/Sub

Topics follow `{mini-app-type}:{event-name}` convention (e.g., `recipe:ingredients-ready`).

- `publish(topic, payload)` — routes to an in-process EventBus in Kotlin
- `subscribe(topic, cb)` — registers a listener; the bridge fires `evaluateJavascript` when the event arrives

**System events** published by Vela itself:

- `vela:theme-changed`
- `vela:vault-changed`
- `vela:vault-synced`
- `vela:layout-changed`

Cross-app communication (recipe pushes ingredients to shopping list) flows through this layer.

### `vela.ai` — LLM Access

Direct access to the existing AmplifierSession.

- `ask(prompt)` — returns a Promise with the complete response
- `stream(prompt, onChunk)` — delivers tokens as they arrive via `evaluateJavascript`

Mini apps get the same LLM intelligence as the main chat interface.

### `vela.vault` — Vault Operations

Wraps VaultManager.

- `read(path)` — read vault item content
- `write(path, content)` — write vault item (automatically publishes `vela:vault-changed`)
- `list(path)` — list vault directory contents
- `sync()` — trigger vault sync

### `__VELA_CONTEXT__` Injection

At WebView load, Vela injects `window.__VELA_CONTEXT__`:

```json
{
  "itemPath": "/vault/recipes/carbonara.md",
  "itemContent": "...",
  "contentType": "recipe",
  "capabilities": [
    {
      "type": "shopping-list",
      "provides": [
        { "id": "items_collection", "description": "The current collection of items the user intends to buy" }
      ],
      "consumes": [
        { "id": "shopping-list.add_items", "description": "Adds a list of items to the shopping list" }
      ]
    }
  ],
  "globalCollections": { "shopping-list-queue": ["..."] },
  "theme": { "isDark": true, "primaryColor": "#7C4DFF" },
  "layout": "phone"
}
```

> **Note:** Cross-app capability references in `consumes` use `{type}.{id}` format (e.g., `shopping-list.add_items`); `provides` entries use bare `{id}`.

---

## Section 3 — Capabilities Graph + Renderer Lifecycle

### The Graph

Lives in Room DB (`mini_app_registry`). Each row stores:

| Column           | Type       | Description                                        |
|------------------|------------|----------------------------------------------------|
| `content_type`   | String     | The vault content type this renderer handles       |
| `renderer_path`  | String     | Path to cached HTML file                           |
| `provides`       | JSON array | English-described capability objects this app offers|
| `consumes`       | JSON array | English-described capability objects this app needs |
| `db_collections` | JSON array | `{scope, collection, description}` entries         |
| `version`        | Int        | Renderer version                                   |
| `last_used`      | Long       | Timestamp of last use                              |

### English-Described Capabilities

Capabilities are described in natural English, not opaque tokens. Example for a recipe mini app:

```json
{
  "provides": [
    {
      "id": "ingredients_list",
      "description": "A structured list of ingredients with quantities and units extracted from the recipe"
    },
    {
      "id": "step_list",
      "description": "An ordered list of cooking steps with estimated times"
    }
  ],
  "consumes": [
    {
      "id": "shopping-list.add_items",
      "description": "Adds a list of items to the shopping list app"
    }
  ]
}
```

This richness allows the LLM generating new mini apps to make intelligent connection decisions by reading descriptions.

### Renderer Generation

Happens exactly once per unknown content type. The LLM receives:

1. The vault item content
2. The full English-described capabilities graph (all existing mini apps)
3. The scope contract with current `global:` / `type:` collection snapshots
4. Current theme + layout mode

It returns two artifacts:

- **Complete HTML/CSS/JS page** — persisted to `.vela/renderers/{content_type}/renderer.html` inside the vault (travels with data via git sync)
- **English-described capability manifest** — includes `provides`, `consumes`, and `db_collections` (the collections the mini app uses with their scope and description). Persisted to Room DB (`mini_app_registry`).

### Evolution: Additive, Not Destructive

Two triggers cause a renderer to update:

1. A new mini app joins the graph that the current renderer could connect to
2. The user explicitly requests an update

When triggered, the LLM receives the existing renderer HTML as context and produces a **complete updated HTML file**. Generation is semantically conservative — user customisations and `local:` state contracts are preserved — but the output is always a full HTML document, not a diff.

---

## Section 4 — Navigation & Adaptive Layout

### Current State Being Replaced

`ConversationScreen.kt` (1,263 lines) acts as the entire app shell using a manual `Page` enum with `AnimatedContent`. No `WindowSizeClass`. No adaptive layout. Vault is a chip in the composer.

### NavigationScaffold

New top-level composable reading `WindowSizeClass`:

- `WindowWidthSizeClass.Compact` → Material 3 `NavigationBar` (bottom), four items
- `WindowWidthSizeClass.Medium` or `Expanded` → `NavigationRail` (left), same four items

### Four Destinations

| Destination    | Behavior                                                                                          |
|----------------|---------------------------------------------------------------------------------------------------|
| **Projects**   | Existing conversation/session logic, re-hosted. Chat retained as the primary modality for AI.     |
| **Vault**      | `VaultBrowserScreen` promoted from chip to first-class destination. Phone: full-screen list, tap → full-screen `MiniAppContainer`. `Medium`/`Expanded`: permanent master/detail split. |
| **Connectors** | Migrates existing `NodesScreen` logic (SSH Vela nodes) into the new Connectors destination. External service connectors are out of scope for this spec and will be addressed in a follow-on design. |
| **Profile**    | Existing settings, renamed.                                                                       |

### ConversationScreen Decomposition

Extracted to separate files:

- `TurnRow`
- `ToolGroupRow`
- `TodoChecklistRow`
- `ComposerBox`
- `SessionsPage`

Vault chip removed from `ComposerBox`. `ConversationScreen.kt` reduced to ~300 lines of orchestration.

### MiniAppContainer

New composable wrapping WebView lifecycle, SDK injection, and layout context. Injects `layout: "phone"` or `layout: "tablet"` into `__VELA_CONTEXT__`.

Mini apps adapt via CSS variables (`--vela-layout`, `--vela-viewport-width`, etc.) and media queries. The same cached HTML serves both form factors.

---

## Section 5 — Components, Changes & Error Handling

### New Components

| File                              | Responsibility                                                  |
|-----------------------------------|-----------------------------------------------------------------|
| `MiniAppRuntime.kt`              | Composable owning WebView lifecycle, SDK injection, bridge routing. Note: the file is `MiniAppRuntime.kt`; the composable it exports (and used throughout the codebase) is `MiniAppContainer`. |
| `VelaJSInterface.kt`            | `@JavascriptInterface`-annotated class with four inner objects (`Db`, `Events`, `Ai`, `Vault`) |
| `RendererGenerator.kt`          | Assembles LLM generation prompt, calls AmplifierSession, parses HTML + manifest, persists both |
| `CapabilitiesGraphRepository.kt` | Room entity + repository for `mini_app_registry` table          |
| `MiniAppDocumentStore.kt`       | Room entity + repository for `mini_app_documents` table backing `vela.db` |
| `NavigationScaffold.kt`         | `WindowSizeClass`-aware adaptive navigation host                |
| `ConnectorsScreen.kt`           | Migrates existing `NodesScreen` logic (SSH Vela nodes) into the new Connectors destination. External service connectors are out of scope for this spec and will be addressed in a follow-on design. |

### Modified Files

| File                      | Change                                                                     |
|---------------------------|----------------------------------------------------------------------------|
| `ConversationScreen.kt`  | Decomposed from 1,263 → ~300 lines; `TurnRow`, `ToolGroupRow`, `TodoChecklistRow`, `ComposerBox`, `SessionsPage` extracted |
| `VaultBrowserScreen.kt`  | Mini app integration; file-tap loads `MiniAppContainer`; two-pane layout on tablet |
| `AppModule.kt`           | New Hilt bindings for all new components                                   |
| Room DB migration         | v13 — adds `mini_app_registry` and `mini_app_documents` tables            |

### Removed

- Vault chip in `ComposerBox`
- `Page` enum and `AnimatedContent`-based navigation in `ConversationScreen`
- `NodesScreen` (logic absorbed into `ConnectorsScreen`)

### Error Handling

| Failure                          | Response                                                                      |
|----------------------------------|-------------------------------------------------------------------------------|
| LLM fails to generate renderer   | Fall back to existing extension-based rendering (`MarkdownText`, `ImageViewer`, etc.). No crash, no blank screen. |
| `vela.db` / `vela.vault` throws  | Reject the JS Promise with a structured error object; mini app handles gracefully in its own UI. |
| `vela.ai` stream interrupted     | Publishes `vela:ai-interrupted`; mini app can retry or degrade.               |
| Vault sync fails                 | Publishes `vela:sync-failed`; mini apps subscribed to vault paths show a stale-data indicator. |

### Testing Strategy

| Component                      | Approach                                                                            |
|--------------------------------|-------------------------------------------------------------------------------------|
| `MiniAppRuntime`               | Assert `window.vela` and `__VELA_CONTEXT__` are present in WebView after load       |
| `VelaJSInterface`              | Test each namespace in isolation with a mock WebView; no live Amplifier session required |
| `CapabilitiesGraphRepository`  | Room in-memory DB tests for registry reads/writes and English manifest round-trips   |
| `NavigationScaffold`           | Screenshot tests at Compact and Medium window widths confirming bottom bar vs rail rendering |
| `RendererGenerator.kt`         | Unit test prompt assembly (verify content, capabilities graph, scope contract all present). Test manifest extraction from LLM response. Test HTML persistence to `.vela/renderers/` path. Use a mock AmplifierSession returning fixture HTML + manifest. |
