# Vela Mini App Backend Server Design

## Goal

Replace the Android `@JavascriptInterface` bridge in Vela's mini app WebViews with a local Ktor-CIO HTTP server that serves mini app HTML, exposes a REST API, enables LAN access for desktop browsers, and lays the foundation for the future Skills system.

## Background

Mini apps currently communicate with native Android code through `@JavascriptInterface` — a synchronous bridge that couples the renderer tightly to the Android runtime. This prevents mini apps from working outside the WebView (e.g., from a desktop browser on the same LAN) and makes the JS-to-native boundary fragile and hard to extend.

By moving to a local HTTP server, mini apps become real web apps that call `fetch()` against REST endpoints. The same URL that loads inside the Android WebView also works from any browser on the local network.

## Approach

**Ktor-CIO embedded server** — started in `VelaApplication.onCreate()` as a coroutine-based `@Singleton`. No Android `Service` needed.

Alternatives evaluated and rejected:
- **Bound Service** — unnecessary lifecycle complexity; the server only needs to live as long as the app process, which `@Singleton` already provides.
- **Foreground Service** — user-visible notification for something that should be invisible; overkill for a localhost server.

## Architecture

Two new files in a `server/` package. Everything else is a modification.

```
VelaApplication.onCreate()
  └── VelaMiniAppServer.start()          ← @Singleton, starts Ktor-CIO on :7701
        └── VelaMiniAppRoutes.install()   ← route definitions, injected Hilt deps
```

WebView changes from file loading to HTTP:

```
Before:  loadDataWithBaseURL("file:///vault/.vela/renderers/markdown/", html, ...)
After:   webView.loadUrl("http://localhost:7701/miniapps/markdown")
```

### New Files

| File | Purpose |
|---|---|
| `app/src/.../server/VelaMiniAppServer.kt` | Ktor server lifecycle — `start()`, `stop()`, `restart(host)` |
| `app/src/.../server/VelaMiniAppRoutes.kt` | All route handlers — injects `TurnDao`, `VaultRegistry`, `AmplifierSession` |

### Modified Files

| File | Change |
|---|---|
| `app/build.gradle.kts` | Add `ktor-server-cio`, `ktor-server-content-negotiation`, `ktor-serialization-gson` |
| `VelaApplication.kt` | Call `server.start()` in `onCreate()` |
| `ui/miniapp/MiniAppRuntime.kt` | `loadUrl("http://localhost:$port/miniapps/{type}")`, remove `addJavascriptInterface` calls, inject `window.vela` shim |
| `ai/RendererGenerator.kt` | Update LLM system prompt to use `window.vela.*` API |
| `ui/settings/SettingsScreen.kt` | Add LAN toggle switch |

## API Endpoints

```
GET    /health                              → { ok: true, host, port, lanEnabled }
GET    /miniapps/{contentType}              → serves renderer HTML
DELETE /miniapps/{contentType}             → deletes renderer (allows fresh regeneration)

POST   /api/db/query                        { sql, params? }  → { rows: [...] }
POST   /api/db/mutate                       { sql, params? }  → { rowsAffected: N }

POST   /api/ai/complete                     { prompt, systemPrompt? }  → { text }

GET    /api/vault/read?path=…&format=raw    → file content (plain text, default)
GET    /api/vault/read?path=…&format=json   → CommonMark-parsed unified JSON:
                                              { frontmatter: {...}, sections: [{type, text, items...}] }
GET    /api/vault/list?path=…              → [ { name, path, isDir, size } ]

POST   /api/events/emit                     { name, data }  → { ok: true }
GET    /api/events/poll?since={ms}          → [ { name, data, ts } ]
```

### Markdown → JSON Transform

The `format=json` parameter on `/api/vault/read` parses markdown server-side using CommonMark/Flexmark into a unified structure:

```json
{
  "frontmatter": { "title": "...", "tags": ["..."] },
  "sections": [
    { "type": "heading", "level": 2, "text": "..." },
    { "type": "paragraph", "text": "..." },
    { "type": "list", "ordered": false, "items": ["...", "..."] }
  ]
}
```

Raw markdown parsing in JS is difficult — the server-side transform is the clean solution. Mini app JS receives structured data it can immediately render or query.

### Optional Real DB

`/api/db/query` and `/api/db/mutate` stay in the API. Some mini apps need read/write persistence beyond what markdown provides (e.g., tracking which recipes you've tried, storing progress state).

### Events Polling

`events.on(name, fn)` maps to a 3-second polling interval against `/api/events/poll?since={ms}`. The server keeps a ring buffer of the last 100 events in memory. No SSE needed — polling is simpler and sufficient for mini app use cases.

## Data Flow

```
User taps ✨ on vault file
  → FAB spins, LLM fitness check runs
  → Match found? Load WebView immediately. No match? Show type selection sheet.
  → WebView loads: http://localhost:7701/miniapps/{type}
  → Ktor serves renderer HTML
  → onPageFinished injects window.vela shim
  → Mini app JS calls window.vela.vault.read(path)
  → Shim translates to fetch('/api/vault/read?path=…&format=json')
  → Ktor route handler reads vault file, parses markdown, returns JSON
  → Mini app renders structured data
```

For LAN access:

```
User toggles LAN ON in Settings
  → server.restart("0.0.0.0")
  → Desktop browser navigates to http://<phone-ip>:7701/miniapps/{type}
  → Same endpoints, same mini app, same data
```

## Lifecycle and LAN Toggle

### Startup

`VelaMiniAppServer` is `@Singleton`, started in `VelaApplication.onCreate()` in its own `CoroutineScope(SupervisorJob() + Dispatchers.IO)`. Up within ~100ms of cold start.

### Shutdown

No explicit cleanup needed — sockets close when the process dies.

### LAN Toggle

A `Switch` in `SettingsScreen`. Backed by `SharedPreferences` key `"mini_app_server_lan"`.

- Toggled ON: `server.restart("0.0.0.0")`
- Toggled OFF: `server.restart("127.0.0.1")`
- Warning shown: *"When on, mini apps are accessible from your local network. Anyone on the same Wi-Fi can read your vault data."*

### Port Conflict

On startup, catch `BindException`, try `:7702`, then `:7703`. Actual bound port stored in `StateFlow<Int>` on `VelaMiniAppServer`.

### Ready Signal

`MiniAppContainer` observes `server.isReady: StateFlow<Boolean>`. Shows `CircularProgressIndicator` until ready (~100ms), then loads URL.

## Mini App Generation Changes

### Bridge Removal

All four `addJavascriptInterface(...)` calls removed from WebView setup.

### `window.vela` Shim

`onPageFinished` injects a JS wrapper that maps `window.vela.*` to `fetch()`:

```javascript
window.vela = {
  db: {
    query:  (sql,p) => fetch('/api/db/query',  {method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({sql,params:p})}).then(r=>r.json()),
    mutate: (sql,p) => fetch('/api/db/mutate', {method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({sql,params:p})}).then(r=>r.json())
  },
  ai: {
    complete: (prompt,sp) => fetch('/api/ai/complete',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({prompt,systemPrompt:sp})}).then(r=>r.json()).then(r=>r.text)
  },
  vault: {
    read: (p)  => fetch(`/api/vault/read?path=${encodeURIComponent(p)}`).then(r=>r.text()),
    list: (p)  => fetch(`/api/vault/list?path=${encodeURIComponent(p)}`).then(r=>r.json())
  },
  events: {
    emit: (n,d) => fetch('/api/events/emit',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({name:n,data:d})}).then(r=>r.json()),
    on:   (name,fn) => {
      let t = Date.now();
      setInterval(async () => {
        const evs = await fetch(`/api/events/poll?since=${t}`).then(r=>r.json());
        t = Date.now();
        evs.filter(e=>e.name===name).forEach(e=>fn(e.data));
      }, 3000);
    }
  }
};
if (typeof window.onVelaReady === 'function') window.onVelaReady();
```

Backward compatible: old mini apps referencing `window.vela.*` continue to work. New ones use the same API.

### Existing Renderers Cleared on First Launch

On first launch after this update, all `.vela/renderers/*/renderer.html` files are deleted (they use the old `window.__vela_*` bridge). Users regenerate from scratch.

## Magic Button UX Flow

```
User taps ✨ on vault file
  ↓
FAB spins (InfiniteTransition rotation, tertiaryContainer colour)
LLM fitness check:
  → Input: contentType + content snippet (first 400 chars) + list of existing mini app types
  → Output: { match: string | null, confidence: 0–1, reason: string }
  ├─ FITS (confidence ≥ 0.7)
  │    → WebView loads immediately: http://localhost:7701/miniapps/{match}
  │    → ✨ FAB = "Tweak" mode → opens feedback sheet
  └─ NO FIT / FIRST TIME
       → Type selection sheet: 3 utility-focused suggestions
```

### Utility-First Suggestions

The LLM prompt asks *"What are 3 specific things the user could DO with this content as an interactive app?"* — not visual styles.

Example for a recipe file:
- *"Build a step-by-step cook-along guide"*
- *"Track which recipes you've tried"*
- *"Generate a shopping list from ingredients"*

### Feedback Sheet (Tweak Mode)

Identical structure to the type-selection sheet — `ListItem` rows per AI suggestion, framed as actions. Plus a "Start fresh" row at the bottom that calls `DELETE /miniapps/{type}` then opens the type selection sheet.

### Type Selection Sheet

- **Loading state:** "Analysing your {contentType}…" + `LinearProgressIndicator` + 3 shimmer placeholder cards
- **After load:** re-roll `↺` button appears (top-right of sheet header)
- **Re-roll:** increments `rerollKey` → `LaunchedEffect` re-runs → new LLM suggestions

## Error Handling

- **Port conflict:** Catch `BindException` on startup, try ports 7701–7703. If all fail, surface error in `MiniAppContainer`.
- **Server not ready:** `MiniAppContainer` observes `isReady: StateFlow<Boolean>` — shows loading indicator until server is up.
- **Vault read failures:** `/api/vault/read` returns `404` for missing files, `400` for invalid paths. Mini app JS handles via standard fetch error patterns.
- **LLM failures:** Fitness check timeout (5s) falls through to type selection sheet. AI complete endpoint returns `{ error: "..." }` on failure.
- **DB errors:** `/api/db/query` and `/api/db/mutate` return `{ error: "..." }` with HTTP 400 for SQL errors.
- **Old renderer cleanup:** First launch deletes stale renderers; if deletion fails, individual files are skipped and retried on next launch.

## Testing Strategy

- **Server unit tests:** Start `VelaMiniAppServer` in test, hit each endpoint with OkHttp/Ktor test client, assert responses.
- **Shim integration tests:** Load a test mini app in WebView, verify `window.vela.vault.read()` returns expected data through the full fetch → Ktor → vault path.
- **LAN toggle tests:** Verify `restart("0.0.0.0")` makes server accessible on non-loopback interface; `restart("127.0.0.1")` restricts it.
- **Port fallback tests:** Bind a socket on 7701 before starting server, verify it falls back to 7702.
- **Magic button flow tests:** Mock LLM responses, verify fitness check routes to immediate load vs. type selection sheet.
- **Renderer cleanup tests:** Place stale renderer files, trigger first-launch cleanup, verify deletion.

## Forward Reference: Skills System

This backend is the serving layer for the future **Skills system** (separate design session). A skill will bundle: a content-type binding, a data parser definition, and a renderer. The backend's `format=json` provides the structured content that skills consume. The backend's API surface does not change; skills add a definition layer on top.

## Theory of Success

> Vela starts and the server binds on `:7701`. Tapping ✨ on a vault file spins the FAB while the LLM fitness-checks existing mini apps. If a match is found, the WebView loads immediately from `http://localhost:7701/miniapps/{type}` — no sheet, just the app. If no match, the type sheet offers 3 utility-focused suggestions (what you can DO, not how it looks). The generated mini app calls `window.vela.vault.read(path)` and gets data back. With LAN toggled on in Settings, the same URL opens in a desktop browser showing the working mini app.
