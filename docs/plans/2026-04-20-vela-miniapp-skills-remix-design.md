# Vela Mini App — Skills, Blocks, Runtime & Remix Mode

## Goal

Replace the open-ended LLM HTML generation with a hybrid skills + blocks system using Lit Web Components and a proper `@vela/runtime` ES module. Add a card-sequence build animation, remove the ✨ FAB from the fallback view, and introduce Remix mode — a draggable chat sheet for iterative mini app refinement powered by a dedicated Amplifier session.

## Background

The current mini app pipeline generates HTML in a single open-ended LLM pass. This produces inconsistent quality, offers no extensibility, and gives users no visibility into what's happening during generation. Users also have no way to iteratively refine a generated mini app without starting over.

The ✨ FAB on the fallback view is a redundant entry point now that generation is accessible from the TopAppBar view menu.

## Approach

**Hybrid C (baseline in-app + vault-extensible):** A curated skill library ships with the app as Android assets. Each skill knows which Lit Web Component blocks to compose. Users (or the AI) can extend the library via `.vela/skills/` in their vault. This gives guaranteed quality on launch with open-ended extensibility for the future Skills system.

---

## Section 1 — Remove ✨ FAB from Fallback View

Delete the FAB and all associated state from `MiniAppContainer`'s `RendererState.Fallback` branch in `MiniAppRuntime.kt`. Specifically remove:

- `var showTypeSheet`, `var isAnalysing` state
- `InfiniteTransition` + `fabRotation` animation
- `LaunchedEffect(isAnalysing)` block (fitness check + delayed sheet open)
- The `FloatingActionButton` composable
- The `RendererTypeSheet(...)` call in the Fallback branch
- The `onSuggestionsReady` callback wiring

The fallback view becomes just `FallbackRenderer` filling the screen. Generation is entered exclusively via `[Markdown ▾]` → "Generate different view…" in the TopAppBar view menu. `fitnessCheck()` in the ViewModel survives — it's called from the view menu's generation path.

---

## Section 2 — Card Sequence Build Animation

Replace the `LinearProgressIndicator` + text label in the build screen with an animated phase card sequence. Applies to both initial generation and Remix mode regeneration.

### Five Phases

1. Detecting content type
2. Skill selected
3. Assembling blocks
4. Building your app
5. Saving

### Card Anatomy

Icon · label · detail line (shows specific context: archetype name, skill name, block names).

Three states:

| State     | Appearance                                                                 |
|-----------|----------------------------------------------------------------------------|
| `PENDING` | Not yet visible                                                            |
| `ACTIVE`  | Full opacity, pulsing ring animation on icon, animated typing dots on label |
| `DONE`    | Green ✓ badge on icon, text dims to 60%, card shrinks slightly              |

### Animation

Cards arrive one at a time from the bottom via `AnimatedVisibility(enter = slideInVertically + fadeIn)` with a spring spec. Active card always at the bottom; completed cards accumulate above. No scrolling needed — 5 cards maximum.

### Implementation

`_buildActivity: StateFlow<String>` in `MiniAppViewModel` becomes `_buildPhases: StateFlow<List<BuildPhase>>` where `BuildPhase` holds:

- `label: String`
- `detail: String`
- `status: PhaseStatus` (`PENDING` | `ACTIVE` | `DONE`)

Token-count milestones become phase transitions. The `BuildingScreen` composable in `MiniAppRuntime.kt` renders the card list.

---

## Section 3 — Skills + Blocks Architecture (Library-First Hybrid)

### In-App Asset Structure

```
app/src/main/assets/
  lib/
    lit-core.min.js          ← Lit Web Components library (~5KB gzipped)
    vela-runtime.js          ← @vela/runtime SDK
  skills/
    recipe-cookalong/
      skill.yaml             ← archetype tags, block list, confidence threshold
      template.html          ← HTML shell with importmap, block tags, onVelaReady
      prompt.txt             ← LLM instruction specialized for this archetype
    security-alert/
    meeting-action-tracker/
    book-reading-card/
    daily-journal/
    task-kanban/
    goal-habit-tracker/
  blocks/
    vela-alert-card.js
    vela-action-list.js
    vela-step-through.js
    vela-checklist.js
    vela-progress-ring.js
    vela-metadata-card.js
    vela-timeline.js
    vela-kanban.js
    vela-heatmap-calendar.js
    vela-data-table.js
    vela-status-badge.js
    vela-timer.js
```

### Skill File Format

**`skill.yaml`:**

```yaml
name: Recipe Cook-along
archetypes: [recipe, cooking, meal]
blocks: [vela-step-through, vela-checklist, vela-timer]
confidence_threshold: 0.7
description: Step-by-step cook-along with ingredient tracking and timers
```

**`template.html`** — declarative markup, LLM fills minimal JS:

```html
<script type="importmap">
  { "imports": { "lit": "/lib/lit-core.min.js", "@vela/runtime": "/lib/vela-runtime.js", "@vela/": "/blocks/" } }
</script>
<script type="module">
  import '@vela/vela-step-through.js';
  import '@vela/vela-checklist.js';
  import { vela } from '@vela/runtime';

  window.onVelaReady = async () => {
    const data = await vela.vault.read(vela.app.context.itemPath, 'json');
    document.querySelector('vela-step-through').sections = data.sections;
    document.querySelector('vela-checklist').items = data.frontmatter.ingredients;
  };
</script>
<body>
  <vela-step-through></vela-step-through>
  <vela-checklist></vela-checklist>
</body>
```

### Block Format — Lit Web Components

Each block is a self-contained Lit Web Component served at `/blocks/{filename}`:

```javascript
// assets/blocks/vela-alert-card.js
import { LitElement, html, css } from 'lit';

export class VelaAlertCard extends LitElement {
  static styles = css`:host { display: block; border-radius: 16px; }`;
  static properties = {
    frontmatter: { type: Object },
    sections:    { type: Array },
  };
  render() {
    return html`<div class="card"><h2>${this.frontmatter?.title}</h2></div>`;
  }
}
customElements.define('vela-alert-card', VelaAlertCard);
```

Shadow DOM provides CSS encapsulation per block. Reactive properties mean patching `element.frontmatter = newData` updates the UI without a full page reload — used by Remix mode.

### Vault Extensibility

`SkillLibrary` checks `.vela/skills/` before `assets/skills/`. A vault skill with the same archetype tag as a built-in skill wins. A vault skill with a new archetype tag extends the library. This establishes the extensibility hook for the future Skills system without requiring it now.

### New Generation Pipeline

`RendererGenerator.kt` splits into three classes:

1. **`ArchetypeDetector`** — fast LLM call analyzing first 500 chars + YAML frontmatter → `{ archetype: String, confidence: Float }`. Powers the "Detecting content type" build card.

2. **`SkillLibrary`** — `findMatches(archetype): List<SkillMatch>` returns ranked skills. Top 3 presented to user in the skill picker sheet. Above `confidence_threshold`, top skill is auto-accepted silently.

3. **`RendererAssembler`** — loads `template.html` + skill's `prompt.txt` + block list → LLM fills `{{CONFIG}}` slots → writes complete HTML to `.vela/renderers/{contentType}/renderer.html`.

### Skill Selection UI

When user taps "Generate different view…":

1. A detection banner animates in: `Detected: Recipe · 91%`
2. Three skill cards appear — each showing name, description, and block tags
3. Best match gets a "Best match" label
4. A "↻ These don't fit? Try different suggestions" re-roll link triggers `ArchetypeDetector` again with a different seed
5. User taps a skill card → build animation starts

### Ktor Server New Routes

| Route                          | Method | Behavior                                                        |
|--------------------------------|--------|-----------------------------------------------------------------|
| `/blocks/{filename}`           | GET    | Stream from `assets/blocks/`                                    |
| `/lib/{filename}`              | GET    | Stream from `assets/lib/`                                       |
| `/miniapps/{contentType}`      | PUT    | Overwrite renderer file + emit `EventBus` reload event          |

### P0 Skills at Launch

| Skill               | Archetype        |
|----------------------|------------------|
| Meeting action tracker | Meeting notes  |
| Day dashboard        | Daily journal    |
| Kanban               | Task / project   |
| Cook-along           | Recipe           |
| Reading card         | Book notes       |
| Alert tracker        | Security alert   |

---

## Section 3b — `@vela/runtime` SDK

Served at `/lib/vela-runtime.js`. Registered in import map as `"@vela/runtime"`. Replaces the current `window.vela` injected shim as the public API surface. The shim becomes the internal fetch layer beneath the runtime.

### Full API Surface

```javascript
const vela = {
  vault: {
    read(path, format = 'raw'),    // → string | { frontmatter, sections }
    list(path = ''),               // → [{ name, path, isDir, size }]
    write(path, content),          // → { ok: true }
  },
  db: {
    query(sql, params = []),       // → { rows: [] }
    mutate(sql, params = []),      // → { rowsAffected: N }
  },
  ai: {
    complete(prompt, systemPrompt?), // → string
  },
  events: {
    emit(name, data),              // → { ok: true }
    on(name, fn),                  // void — polls every 3s
  },
  app: {
    context,                       // { itemPath, contentType, theme, layout } — readonly
    navigate(relPath),             // open another vault file in Vela
    notify(message, type?),        // native toast — 'info' | 'success' | 'warning'
    refresh(),                     // reload this mini app's WebView
    remix(),                       // open the Remix sheet for this mini app
    record(options?),              // trigger a recording session
  },
};
```

`vela.app.*` methods POST to new Ktor routes `/api/app/{action}` which fire into the native Android app via `EventBus` (already in codebase). Event names: `app:navigate`, `app:notify`, `app:refresh`, `app:remix-requested`, `app:record-start`.

---

## Section 4 — Remix Mode

### Entry Point

`✦ Remix` option in the view menu (only shown when a mini app exists). Also callable from inside a mini app via `vela.app.remix()`.

### The Sheet

`BottomSheetScaffold` with two states:

| State                | Behavior                                        |
|----------------------|-------------------------------------------------|
| `PartiallyExpanded`  | ~60% height (default) — mini app visible beneath |
| `Expanded`           | Full screen chat                                 |

Drag handle at the top. Dragging down collapses to a peek state (just the header strip). The mini app WebView stays live and interactive behind the sheet at all times.

### Header

Drag handle · `✦ Remix · [filename]` · `↺ Restart` · `Accept ✓`

- **Restart** — clears conversation, keeps current renderer
- **Accept ✓** — dismisses sheet, commits (renderer already saved on each `write_renderer` call)

### `RendererSession.kt`

Thin wrapper around `AmplifierSession`:

- Created when Remix sheet opens
- Cancelled on sheet dismiss or Restart
- System prompt pre-loads: current renderer HTML + vault file `format=json` + instruction: *"You are remixing a mini app for {filename}. Describe your reasoning before each change. Use write_renderer(html) when ready to update."*
- Tools: `write_renderer(html)`, `read_renderer()`, `read_vault(path, format?)`, `list_vault(path?)`

### Chat UI

`LazyColumn` of alternating user/AI messages. Each tool call appears as a chip row below the triggering AI message: `⟳ vault.read · recipe.md` → `✓` when done. Tool call chips use the same visual language as the build animation cards — coherent system language.

### Regeneration Loop

When `write_renderer(html)` fires → `PUT /miniapps/{type}` on Ktor → renderer file overwritten → `EventBus` emits `renderer:updated` → `MiniAppContainer` WebView reloads. User sees the mini app update live behind the chat.

### Input

`OutlinedTextField` + send button (↑). Send triggers a new `amplifierSession.runTurn()` call. Streaming tokens arrive and fill the AI message bubble progressively.

---

## Data Flow

```
User taps "Generate different view…"
  → ArchetypeDetector (LLM: 500 chars + frontmatter → archetype + confidence)
  → SkillLibrary.findMatches(archetype) → top 3 skills
  → User picks a skill (or auto-accept if confidence > threshold)
  → RendererAssembler (template.html + prompt.txt + blocks → LLM fills config → renderer.html)
  → WebView loads renderer.html via Ktor
  → onVelaReady() → vela.vault.read() → blocks render live data

Remix loop:
  User opens Remix sheet
  → RendererSession created (AmplifierSession + system prompt with current HTML)
  → User types message → runTurn()
  → AI calls write_renderer(html) → PUT /miniapps/{type}
  → EventBus renderer:updated → WebView reloads
  → User sees update, continues conversation or taps Accept ✓
```

## Error Handling

- **Archetype detection failure:** Falls back to a generic "freeform" archetype with a general-purpose skill that composes basic blocks.
- **Skill not found:** If no skill matches above threshold, the skill picker sheet shows all available skills for manual selection.
- **Block load failure:** Individual Lit components fail gracefully — shadow DOM isolates failures so other blocks continue rendering.
- **Remix session errors:** Tool call chips show error state (red ✗). AI receives the error and can retry or explain.
- **Vault read failures in runtime:** `vela.vault.read()` rejects the promise; mini app `onVelaReady` should catch and show a user-friendly fallback.
- **Ktor route failures:** Standard HTTP error codes returned; `@vela/runtime` surfaces them as rejected promises with descriptive messages.

## Testing Strategy

- **Unit tests:** `ArchetypeDetector`, `SkillLibrary.findMatches()`, `RendererAssembler` template merging — all testable with mock LLM responses and fixture YAML files.
- **Build animation:** Compose Preview tests for `BuildingScreen` with hardcoded `BuildPhase` lists in each state combination.
- **Block components:** Browser-based tests for each Lit Web Component — set properties, assert rendered DOM.
- **Remix session:** Integration test with a mock `AmplifierSession` — verify `write_renderer` tool call triggers `PUT` and `EventBus` event.
- **End-to-end:** Instrumented Android test opening a recipe `.md`, triggering generation, verifying the WebView loads the skill's blocks, opening Remix, sending a message, and confirming the WebView reloads.

## Open Questions

*None — all questions resolved during design review.*

---

## Theory of Success

> You open a vault `.md` file — no ✨ FAB anywhere. Tap `[Markdown ▾]` → "Generate different view…" → a sheet appears showing the detected archetype ("Recipe · 91%") with 3 skill cards, each showing its name, description, and block tags. Pick one → a phase-card build animation plays (5 cards, each sliding in and completing in sequence). The resulting mini app is built from Lit Web Components that import `@vela/runtime` and call `vela.vault.read()` for live data — no hardcoded content. Tap `[Mini App ▾]` → "✦ Remix" → a draggable chat sheet slides up over the live mini app. Type "make the steps bigger" → tool call chips stream in → the mini app updates behind the sheet. Tap "Accept ✓" → done.
