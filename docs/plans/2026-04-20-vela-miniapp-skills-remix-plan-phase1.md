# Vela Mini App Skills + Blocks + Runtime — Phase 1 Implementation Plan

> **Execution:** Use the subagent-driven-development workflow to implement this plan.

**Goal:** Replace open-ended LLM HTML generation with a hybrid skills + blocks system using Lit Web Components, a `@vela/runtime` SDK, and card-sequence build animation. Remove the FAB from the fallback view.

**Architecture:** An `ArchetypeDetector` classifies vault content via a fast LLM call, then `SkillLibrary` matches curated skill templates (shipped as Android assets). `RendererAssembler` orchestrates the pipeline — for matched skills it writes the template directly (no LLM HTML generation), falling back to the existing `RendererGenerator` for unmatched content. Lit Web Component blocks provide reusable UI elements imported via ES import maps.

**Tech Stack:** Kotlin/Compose (Android), Hilt DI, Ktor embedded server, Lit 3 Web Components, ES import maps, `@vela/runtime` JS SDK

---

## Task 1: Remove FAB from Fallback branch

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/ui/miniapp/MiniAppRuntime.kt`

**Step 1: Delete FAB-related code from the `RendererState.Fallback` branch**

The Fallback branch is at lines 455-543. Replace the entire branch body with a clean version that only renders `FallbackRenderer`. Delete:
- `var showTypeSheet by remember { mutableStateOf(false) }` (L456)
- `var isAnalysing by remember { mutableStateOf(false) }` (L457)
- Entire `LaunchedEffect(isAnalysing)` block (L460-484)
- `val infiniteTransition = ...` + `val fabRotation by ...` (L487-493)
- The `FloatingActionButton(...)` block (L501-519) — this is the Fallback FAB. **Keep** the FAB at L662 in the Ready branch ("Improve mini app").
- The `if (showTypeSheet) { RendererTypeSheet(...) }` block (L522-542)

The Fallback branch becomes:
```kotlin
is RendererState.Fallback -> {
    Box(modifier) {
        FallbackRenderer(
            contentType = s.contentType,
            content     = s.content,
            modifier    = Modifier.fillMaxSize(),
        )
    }
}
```

**Step 2: Remove now-unused imports**

These 6 imports are ONLY used in the Fallback branch and must be removed:
```kotlin
import androidx.compose.animation.core.LinearEasing          // L55
import androidx.compose.animation.core.animateFloat           // L56
import androidx.compose.animation.core.infiniteRepeatable     // L57
import androidx.compose.animation.core.rememberInfiniteTransition  // L58
import androidx.compose.animation.core.tween                  // L59
import androidx.compose.ui.draw.rotate                        // L69
```

**DO NOT remove** these imports — they are used elsewhere in the file:
- `FloatingActionButton` — used at L662 in the Ready branch
- `delay` — used at L756 in `RendererTypeSheet`
- `launch` — used at L690 in the Ready branch
- `LinearProgressIndicator` — used at L791, L886

**Step 3: Verify**

Run:
```bash
cd /Users/ken/workspace/vela && ./gradlew :app:compileDebugKotlin 2>&1 | tail -4
```
Expected: `BUILD SUCCESSFUL`

Run:
```bash
grep -n "isAnalysing\|showTypeSheet" app/src/main/kotlin/com/vela/app/ui/miniapp/MiniAppRuntime.kt | head -5
```
Expected: 0 matches (these vars existed only in the Fallback branch).

Run:
```bash
grep -c "FloatingActionButton" app/src/main/kotlin/com/vela/app/ui/miniapp/MiniAppRuntime.kt
```
Expected: `2` (one import + one usage in Ready branch).

**Step 4: Commit**
```bash
git add app/src/main/kotlin/com/vela/app/ui/miniapp/MiniAppRuntime.kt && git commit -m "refactor: remove FAB from Fallback branch in MiniAppRuntime"
```

**Theory of Success:** Compilation passes. The Fallback branch has no FAB, no `isAnalysing`, no `showTypeSheet`. The Ready branch FAB for "Improve mini app" survives. `fitnessCheck()` in `MiniAppViewModel` is untouched — it's still called from the view menu path.

---

## Task 2: BuildPhase model + card-sequence build animation

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/ui/miniapp/MiniAppRuntime.kt`

This is the largest task — it replaces `_buildLog`/`_buildActivity` with `_buildPhases`, replaces `generateRenderer()` body, replaces `RendererBuildScreen` composable, and updates the `Building` branch.

### Step 1: Add BuildPhase model

Add after the `RendererState` sealed class (after L155, before the ViewModel comment block at L157):

```kotlin
enum class PhaseStatus { PENDING, ACTIVE, DONE }

data class BuildPhase(
    val label:  String,
    val detail: String = "",
    val status: PhaseStatus = PhaseStatus.PENDING,
)
```

### Step 2: Replace ViewModel state fields

In `MiniAppViewModel` (starts at L162), replace lines 178-182:

```kotlin
    private val _buildLog = MutableStateFlow("")
    val buildLog: StateFlow<String> = _buildLog.asStateFlow()

    private val _buildActivity = MutableStateFlow("Starting…")
    val buildActivity: StateFlow<String> = _buildActivity.asStateFlow()
```

With:

```kotlin
    private val _buildPhases = MutableStateFlow<List<BuildPhase>>(emptyList())
    val buildPhases: StateFlow<List<BuildPhase>> = _buildPhases.asStateFlow()
```

### Step 3: Replace generateRenderer() body

Replace the entire `generateRenderer()` method (L208-242) including its KDoc comment (L200-207). Keep the same method signature. The new body uses `_buildPhases` instead of `_buildLog`/`_buildActivity`:

```kotlin
    /**
     * Starts async renderer generation for [rendererType].
     * Updates [buildPhases] as generation progresses through 5 phases.
     * Returns the [GenerationResult] when complete.
     *
     * When [feedback] is supplied the current on-disk renderer is read and passed
     * to the generator so the LLM can update it in place.
     */
    suspend fun generateRenderer(
        itemPath: String,
        itemContent: String,
        contentType: String,
        theme: VelaTheme,
        layout: String,
        rendererType: com.vela.app.ai.RendererType,
        feedback: String? = null,
    ): com.vela.app.ai.GenerationResult {
        _buildPhases.value = listOf(
            BuildPhase("Detecting content type", status = PhaseStatus.ACTIVE),
            BuildPhase("Skill selected"),
            BuildPhase("Assembling blocks"),
            BuildPhase("Building your app"),
            BuildPhase("Saving"),
        )

        fun advance(index: Int, detail: String = "") {
            _buildPhases.update { phases ->
                phases.mapIndexed { i, p ->
                    when {
                        i < index  -> p.copy(status = PhaseStatus.DONE)
                        i == index -> p.copy(status = PhaseStatus.ACTIVE, detail = detail)
                        else       -> p
                    }
                }
            }
        }

        var tokensSeen = 0
        val existingHtml = if (feedback != null) getRendererFile(contentType)?.readText() else null
        advance(0, contentType)

        return rendererGenerator.generateRenderer(
            itemPath     = itemPath,
            itemContent  = itemContent,
            contentType  = contentType,
            theme        = theme,
            layout       = layout,
            rendererType = rendererType,
            feedback     = feedback,
            existingHtml = existingHtml,
            onToken      = { _ ->
                tokensSeen++
                when (tokensSeen) {
                    1    -> advance(1, rendererType.label)
                    100  -> advance(2, "Loading blocks…")
                    500  -> advance(3)
                    2000 -> advance(4)
                }
            },
            onActivity = { _ -> },
        ).also { result ->
            if (result is com.vela.app.ai.GenerationResult.Success) {
                _buildPhases.update { phases -> phases.map { it.copy(status = PhaseStatus.DONE) } }
            }
        }
    }
```

### Step 4: Add new imports for animation composables

Add these imports near the top of the file (after existing animation imports around L55-59, or grouped with other compose imports):

```kotlin
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.ui.draw.clip
```

Note: `Icons.Default.CheckCircle` is available — `material-icons-extended` is in `app/build.gradle.kts`.

### Step 5: Replace RendererBuildScreen composable

Replace the entire `RendererBuildScreen` composable (L938-1018 — from the section comment through the closing brace) with the new card-animation version:

```kotlin
// ────────────────────────────────────────────────────────────────────────────────
// RendererBuildScreen — animated phase-card build sequence
// ────────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RendererBuildScreen(
    rendererType: com.vela.app.ai.RendererType,
    contentType: String,
    buildPhases: List<BuildPhase>,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text("Building ${rendererType.label}", style = MaterialTheme.typography.titleMedium)
                    val active = buildPhases.firstOrNull { it.status == PhaseStatus.ACTIVE }
                    if (active != null) {
                        Text(
                            active.label,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            navigationIcon = {
                IconButton(onClick = onCancel) {
                    Icon(Icons.Default.Close, contentDescription = "Cancel")
                }
            },
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            buildPhases.forEach { phase ->
                AnimatedVisibility(
                    visible = phase.status != PhaseStatus.PENDING,
                    enter = slideInVertically(
                        initialOffsetY = { it / 2 },
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessLow,
                        ),
                    ) + fadeIn(),
                ) {
                    BuildPhaseCard(phase)
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

@Composable
private fun BuildPhaseCard(phase: BuildPhase) {
    val containerColor = when (phase.status) {
        PhaseStatus.ACTIVE  -> MaterialTheme.colorScheme.secondaryContainer
        PhaseStatus.DONE    -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        PhaseStatus.PENDING -> MaterialTheme.colorScheme.surfaceVariant
    }
    val alpha = if (phase.status == PhaseStatus.DONE) 0.6f else 1f

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(40.dp),
            ) {
                when (phase.status) {
                    PhaseStatus.ACTIVE -> CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                    )
                    PhaseStatus.DONE -> Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = alpha),
                        modifier = Modifier.size(24.dp),
                    )
                    PhaseStatus.PENDING -> Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    phase.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                )
                if (phase.detail.isNotBlank()) {
                    Text(
                        phase.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                    )
                }
            }
        }
    }
}
```

### Step 6: Update the `RendererState.Building` branch

Replace lines 546-580 (the entire `is RendererState.Building ->` block):

```kotlin
        is RendererState.Building -> {
            val buildPhases by viewModel.buildPhases.collectAsState()

            LaunchedEffect(s.rendererType, s.feedback) {
                val result = viewModel.generateRenderer(
                    itemPath     = itemPath,
                    itemContent  = itemContent,
                    contentType  = contentType,
                    theme        = theme,
                    layout       = layout,
                    rendererType = s.rendererType,
                    feedback     = s.feedback,
                )
                rendererState = when (result) {
                    is GenerationResult.Success ->
                        viewModel.getRendererFile(contentType)
                            ?.let { RendererState.Ready(it) }
                            ?: RendererState.Fallback(contentType, itemContent)
                    is GenerationResult.Failure -> {
                        Log.w("MiniAppRuntime", "Build failed", result.cause)
                        RendererState.BuildFailed(s.rendererType, result.cause)
                    }
                }
            }

            RendererBuildScreen(
                rendererType = s.rendererType,
                contentType  = contentType,
                buildPhases  = buildPhases,
                onCancel     = { rendererState = RendererState.Fallback(contentType, itemContent) },
                modifier     = modifier,
            )
        }
```

### Step 7: Verify

Run:
```bash
cd /Users/ken/workspace/vela && ./gradlew :app:assembleDebug 2>&1 | tail -4
```
Expected: `BUILD SUCCESSFUL`

Run:
```bash
grep -c "_buildLog\|_buildActivity" app/src/main/kotlin/com/vela/app/ui/miniapp/MiniAppRuntime.kt
```
Expected: `0` — no references to old state fields remain.

Deploy and test on device:
```bash
cd /Users/ken/workspace/vela && ./gradlew :app:installDebug 2>&1 | tail -4
adb connect 10.0.0.106:38391 && adb -s 10.0.0.106:38391 shell am force-stop com.vela.app && sleep 1 && adb -s 10.0.0.106:38391 shell am start -n com.vela.app/.MainActivity && sleep 4 && adb -s 10.0.0.106:38391 logcat -d | grep -E "FATAL|VelaMiniAppServer" | tail -5
```
Expected: No FATAL exceptions. App starts normally. Triggering a build shows 5 animated phase cards instead of progress bar + log text.

### Step 8: Commit
```bash
git add app/src/main/kotlin/com/vela/app/ui/miniapp/MiniAppRuntime.kt && git commit -m "feat: replace build log with animated phase-card build sequence"
```

**Theory of Success:** `assembleDebug` passes. No `_buildLog` or `_buildActivity` references remain. On device, generating a mini app shows 5 animated phase cards (slide up from bottom, one at a time) instead of the old progress bar + monospace log text.

---

## Task 3: Asset directory structure + Ktor routes

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/server/VelaMiniAppRoutes.kt`
- Create dirs: `app/src/main/assets/lib/`, `app/src/main/assets/blocks/`, `app/src/main/assets/skills/`

### Step 1: Create asset directories

```bash
mkdir -p /Users/ken/workspace/vela/app/src/main/assets/lib
mkdir -p /Users/ken/workspace/vela/app/src/main/assets/blocks
mkdir -p /Users/ken/workspace/vela/app/src/main/assets/skills
```

Add `.gitkeep` files so empty dirs are tracked:
```bash
touch /Users/ken/workspace/vela/app/src/main/assets/lib/.gitkeep
touch /Users/ken/workspace/vela/app/src/main/assets/blocks/.gitkeep
touch /Users/ken/workspace/vela/app/src/main/assets/skills/.gitkeep
```

### Step 2: Add `context` and `eventBus` to `VelaMiniAppRoutes` constructor

Replace the constructor (L20-24 in `VelaMiniAppRoutes.kt`):

```kotlin
    @Singleton
    class VelaMiniAppRoutes @Inject constructor(
        private val vaultRegistry: VaultRegistry,
        private val amplifierSession: AmplifierSession,
    ) {
```

With:

```kotlin
    @Singleton
    class VelaMiniAppRoutes @Inject constructor(
        private val vaultRegistry: VaultRegistry,
        private val amplifierSession: AmplifierSession,
        @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
        private val eventBus: com.vela.app.events.EventBus,
    ) {
```

### Step 3: Add new routes inside the `install()` routing block

Add these routes after the existing `get("/api/events/poll")` block (after L145, before the closing `}` of `app.routing {` at L146):

```kotlin
                // ── Skills + Blocks routes ───────────────────────────────────

                // Serve Lit + vela-runtime from assets/lib/
                get("/lib/{filename}") {
                    val filename = call.parameters["filename"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest)
                    try {
                        val bytes = withContext(Dispatchers.IO) {
                            context.assets.open("lib/$filename").readBytes()
                        }
                        val ct = when {
                            filename.endsWith(".js") -> ContentType("application", "javascript")
                            else -> ContentType.Application.OctetStream
                        }
                        call.respondBytes(bytes, ct)
                    } catch (_: java.io.FileNotFoundException) {
                        call.respond(HttpStatusCode.NotFound)
                    }
                }

                // Serve Lit Web Component blocks from assets/blocks/
                get("/blocks/{filename}") {
                    val filename = call.parameters["filename"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest)
                    try {
                        val bytes = withContext(Dispatchers.IO) {
                            context.assets.open("blocks/$filename").readBytes()
                        }
                        call.respondBytes(bytes, ContentType("application", "javascript"))
                    } catch (_: java.io.FileNotFoundException) {
                        call.respond(HttpStatusCode.NotFound)
                    }
                }

                // Serve skill template files from assets/skills/
                get("/skills/{skill}/{filename}") {
                    val skill = call.parameters["skill"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val filename = call.parameters["filename"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest)
                    try {
                        val bytes = withContext(Dispatchers.IO) {
                            context.assets.open("skills/$skill/$filename").readBytes()
                        }
                        val ct = when {
                            filename.endsWith(".html") -> ContentType.Text.Html
                            filename.endsWith(".js")   -> ContentType("application", "javascript")
                            else -> ContentType.Text.Plain
                        }
                        call.respondBytes(bytes, ct)
                    } catch (_: java.io.FileNotFoundException) {
                        call.respond(HttpStatusCode.NotFound)
                    }
                }

                // PUT /miniapps/{contentType} — overwrite renderer + signal reload
                put("/miniapps/{contentType}") {
                    val type = call.parameters["contentType"]
                        ?: return@put call.respond(HttpStatusCode.BadRequest)
                    val vault = vaultRegistry.enabledVaults.value.firstOrNull()
                        ?: return@put call.respond(HttpStatusCode.ServiceUnavailable)
                    val html = call.receiveText()
                    withContext(Dispatchers.IO) {
                        val dir = java.io.File(vault.localPath, ".vela/renderers/$type")
                        dir.mkdirs()
                        java.io.File(dir, "renderer.html").writeText(html)
                    }
                    eventBus.tryPublish("renderer:updated", type)
                    call.respondText(
                        JSONObject().put("ok", true).toString(),
                        ContentType.Application.Json,
                    )
                }

                // Write vault file
                post("/api/vault/write") {
                    val body = JSONObject(call.receiveText())
                    val path = body.getString("path")
                    val content = body.getString("content")
                    val vault = vaultRegistry.enabledVaults.value.firstOrNull()
                        ?: return@post call.respond(HttpStatusCode.ServiceUnavailable)
                    withContext(Dispatchers.IO) {
                        val file = java.io.File(vault.localPath, path)
                        file.parentFile?.mkdirs()
                        file.writeText(content)
                    }
                    call.respondText(
                        JSONObject().put("ok", true).toString(),
                        ContentType.Application.Json,
                    )
                }

                // App operations — fire into EventBus, native Compose listens
                post("/api/app/navigate") {
                    val body = JSONObject(call.receiveText())
                    eventBus.tryPublish("app:navigate", body.optString("relPath", ""))
                    call.respondText(
                        JSONObject().put("ok", true).toString(),
                        ContentType.Application.Json,
                    )
                }
                post("/api/app/notify") {
                    eventBus.tryPublish("app:notify", call.receiveText())
                    call.respondText(
                        JSONObject().put("ok", true).toString(),
                        ContentType.Application.Json,
                    )
                }
                post("/api/app/refresh") {
                    call.receiveText()
                    eventBus.tryPublish("app:refresh", "")
                    call.respondText(
                        JSONObject().put("ok", true).toString(),
                        ContentType.Application.Json,
                    )
                }
                post("/api/app/remix") {
                    call.receiveText()
                    eventBus.tryPublish("app:remix-requested", "")
                    call.respondText(
                        JSONObject().put("ok", true).toString(),
                        ContentType.Application.Json,
                    )
                }
                post("/api/app/record") {
                    eventBus.tryPublish("app:record-start", call.receiveText())
                    call.respondText(
                        JSONObject().put("ok", true).toString(),
                        ContentType.Application.Json,
                    )
                }
```

### Step 4: Verify

```bash
cd /Users/ken/workspace/vela && ./gradlew :app:assembleDebug 2>&1 | tail -4
```
Expected: `BUILD SUCCESSFUL`

Deploy and test PUT route:
```bash
cd /Users/ken/workspace/vela && ./gradlew :app:installDebug 2>&1 | tail -4
adb -s 10.0.0.106:38391 shell am start -n com.vela.app/.MainActivity && sleep 3
adb -s 10.0.0.106:38391 shell "curl -s -X PUT -d '<html>ok</html>' http://localhost:7701/miniapps/test"
```
Expected: `{"ok":true}`

### Step 5: Commit

```bash
git add app/src/main/assets/ app/src/main/kotlin/com/vela/app/server/VelaMiniAppRoutes.kt && git commit -m "feat: add Ktor routes for /lib, /blocks, /skills, PUT /miniapps, vault write, app actions"
```

**Theory of Success:** `assembleDebug` passes. `PUT /miniapps/test` returns `{"ok":true}`. All route handlers wrap file reads in `Dispatchers.IO`. All POST/PUT routes call `call.receiveText()` even when body is unused (avoids Ktor protocol errors).

---

## Task 4: Download Lit + create vela-runtime.js

**Files:**
- Create: `app/src/main/assets/lib/lit-core.min.js` (download)
- Create: `app/src/main/assets/lib/vela-runtime.js` (hand-written)

### Step 1: Download Lit 3 core bundle

```bash
curl -L "https://cdn.jsdelivr.net/gh/nicolo-ribaudo/tc39-proposal-import-maps-cdn-demo@main/lit/lit-core.min.js" -o /Users/ken/workspace/vela/app/src/main/assets/lib/lit-core.min.js 2>/dev/null
wc -c /Users/ken/workspace/vela/app/src/main/assets/lib/lit-core.min.js
```

If the CDN URL fails, try the npm approach:
```bash
cd /tmp && npm pack lit@3 && tar -xf lit-*.tgz && cp package/lit-core.min.js /Users/ken/workspace/vela/app/src/main/assets/lib/
```

If neither works, try:
```bash
curl -L "https://cdn.jsdelivr.net/npm/lit@3/+esm" -o /Users/ken/workspace/vela/app/src/main/assets/lib/lit-core.min.js
```

Expected: File > 10,000 bytes. Verify with `wc -c`.

**IMPORTANT:** The file must export `LitElement`, `html`, and `css` as named exports. After downloading, verify:
```bash
head -5 /Users/ken/workspace/vela/app/src/main/assets/lib/lit-core.min.js
```
It should contain minified JS (not HTML or an error page).

### Step 2: Create vela-runtime.js

Create file at `app/src/main/assets/lib/vela-runtime.js`:

```javascript
/**
 * @vela/runtime — the Vela Mini App JavaScript SDK.
 * Served at /lib/vela-runtime.js by the local Ktor server.
 * Import via import map: import { vela } from '@vela/runtime'
 */

const _h = { 'Content-Type': 'application/json' };
const _post = (url, body) =>
  fetch(url, { method: 'POST', headers: _h, body: JSON.stringify(body) }).then(r => r.json());

export const vela = {
  vault: {
    read:  (path, fmt) =>
      fetch(`/api/vault/read?path=${encodeURIComponent(path)}${fmt ? '&format=' + fmt : ''}`)
        .then(r => fmt === 'json' ? r.json() : r.text()),
    list:  (path = '') =>
      fetch(`/api/vault/list?path=${encodeURIComponent(path)}`).then(r => r.json()),
    write: (path, content) => _post('/api/vault/write', { path, content }),
  },
  db: {
    query:  (sql, params = []) => _post('/api/db/query',  { sql, params }),
    mutate: (sql, params = []) => _post('/api/db/mutate', { sql, params }),
  },
  ai: {
    complete: (prompt, systemPrompt) =>
      _post('/api/ai/complete', { prompt, systemPrompt }).then(r => r.text),
  },
  events: {
    emit: (name, data) => _post('/api/events/emit', { name, data }),
    on:   (name, fn) => {
      let t = Date.now();
      setInterval(async () => {
        const evs = await fetch(`/api/events/poll?since=${t}`).then(r => r.json());
        t = Date.now();
        evs.filter(e => e.name === name).forEach(e => fn(e.data));
      }, 3000);
    },
  },
  app: {
    get context() { return window.__VELA_CONTEXT__ || {}; },
    navigate: (relPath)                => _post('/api/app/navigate', { relPath }),
    notify:   (message, type = 'info') => _post('/api/app/notify', { message, type }),
    refresh:  ()                       => _post('/api/app/refresh', {}),
    remix:    ()                       => _post('/api/app/remix', {}),
    record:   (options = {})           => _post('/api/app/record', options),
  },
};
```

### Step 3: Remove .gitkeep from lib/

```bash
rm /Users/ken/workspace/vela/app/src/main/assets/lib/.gitkeep
```

### Step 4: Verify

```bash
cd /Users/ken/workspace/vela && ./gradlew :app:installDebug 2>&1 | tail -4
adb -s 10.0.0.106:38391 shell am start -n com.vela.app/.MainActivity && sleep 3
adb -s 10.0.0.106:38391 shell "curl -s http://localhost:7701/lib/vela-runtime.js | head -5"
adb -s 10.0.0.106:38391 shell "curl -s http://localhost:7701/lib/lit-core.min.js | wc -c"
```
Expected: First command shows the SDK header comment. Second command shows > 10000 bytes.

### Step 5: Commit

```bash
git add app/src/main/assets/lib/ && git commit -m "feat: add Lit 3 core bundle and @vela/runtime SDK"
```

**Theory of Success:** Both files exist in `assets/lib/`. Ktor serves them at `/lib/vela-runtime.js` and `/lib/lit-core.min.js`. The runtime exports a `vela` object with `vault`, `db`, `ai`, `events`, and `app` namespaces.

---

## Task 5: P0 Lit Web Component block files (10 blocks)

**Files to create in `app/src/main/assets/blocks/`:**

All 10 blocks follow the same pattern:
1. `import { LitElement, html, css } from 'lit';`
2. Define `static styles = css\`...\``
3. Define `static properties = { ... }`
4. Implement `render()` using `html\`...\`` template literals
5. Call `customElements.define('vela-{name}', Vela{Name});`

All blocks use Shadow DOM encapsulation. All are defensive against null/undefined data. CSS custom properties: `--primary`, `--surface2`, `--border`, `--muted`, `--text`, `--bg`, `--card`.

### Step 1: Create all 10 block files

Create these 10 files with complete, working Lit 3 Web Component implementations:

**`app/src/main/assets/blocks/vela-action-list.js`** — Interactive checklist with owner/due-date support. Tap to check off items.
- Props: `items: Array<{text, done, assignee?}>`, `title: String`
- Renders: List of clickable rows with checkbox, text, and optional assignee badge
- On tap: toggles `done` state, dispatches `change` CustomEvent

**`app/src/main/assets/blocks/vela-checklist.js`** — Checkbox list with section title and progress bar.
- Props: `items: Array<string|{text,done}>`, `title: String`
- Renders: Title, progress bar (X/Y done), list of checkboxes
- On tap: toggles done, updates progress bar

**`app/src/main/assets/blocks/vela-step-through.js`** — One-at-a-time step navigator.
- Props: `sections: Array<{type,text}>` (from vault.read JSON format)
- Renders: Current step text, navigation dots, prev/next buttons
- State: internal `currentStep` index

**`app/src/main/assets/blocks/vela-progress-ring.js`** — SVG circular progress indicator.
- Props: `value: Number` (default 0), `max: Number` (default 100), `title: String`, `size: Number` (default 80)
- Renders: SVG circle with stroke-dashoffset animation, center percentage text

**`app/src/main/assets/blocks/vela-metadata-card.js`** — Key-value display for frontmatter.
- Props: `frontmatter: Object`
- Renders: Card with key-value rows for each frontmatter field

**`app/src/main/assets/blocks/vela-timeline.js`** — Vertical timeline with dot indicators.
- Props: `events: Array<{text, date?, done?}>`
- Renders: Vertical line with dots, event text, optional date and done checkmark

**`app/src/main/assets/blocks/vela-status-badge.js`** — Color-coded status chip with tap-to-cycle.
- Props: `status: String`, `statuses: Array<String>` (default: `['open','in-progress','done','blocked']`)
- Colors: open=yellow, done=green, blocked=red, in-progress=blue
- On tap: cycles to next status, dispatches `change` CustomEvent

**`app/src/main/assets/blocks/vela-kanban.js`** — Three-column kanban board.
- Props: `columns: Array<{title, cards: string[]}>`
- Renders: Horizontal scroll, column headers with card count, stacked card items

**`app/src/main/assets/blocks/vela-heatmap-calendar.js`** — GitHub-style contribution grid.
- Props: `data: Array<Number>` (0-10 values mapped to 5 intensity levels), `title: String`, `weeks: Number` (default 12)
- Renders: Grid of 7-row x N-week squares with intensity-based colors

**`app/src/main/assets/blocks/vela-timer.js`** — Countdown/stopwatch with controls.
- Props: `duration: Number` (seconds, default 300), `label: String`
- Renders: Large time display (MM:SS), play/pause/reset buttons
- Emits `complete` CustomEvent when countdown reaches 0

Each component must be fully self-contained and render correctly with default/empty props. Write complete implementations — not stubs.

### Step 2: Remove .gitkeep from blocks/

```bash
rm /Users/ken/workspace/vela/app/src/main/assets/blocks/.gitkeep
```

### Step 3: Verify

```bash
ls /Users/ken/workspace/vela/app/src/main/assets/blocks/ | wc -l
```
Expected: `10`

```bash
cd /Users/ken/workspace/vela && ./gradlew :app:installDebug 2>&1 | tail -4
adb -s 10.0.0.106:38391 shell am start -n com.vela.app/.MainActivity && sleep 3
adb -s 10.0.0.106:38391 shell "curl -s http://localhost:7701/blocks/vela-checklist.js | head -3"
```
Expected: First line is `import { LitElement, html, css } from 'lit';`

### Step 4: Commit

```bash
git add app/src/main/assets/blocks/ && git commit -m "feat: add 10 P0 Lit Web Component blocks"
```

**Theory of Success:** 10 `.js` files in `assets/blocks/`. Each starts with the Lit import. Each registers a custom element. `curl /blocks/vela-checklist.js` returns valid JavaScript.

---

## Task 6: P0 Skill YAML + template files (6 skills)

**Files to create in `app/src/main/assets/skills/`:**

Six skill directories, each containing `skill.yaml` and `template.html`.

### Step 1: Create skill directories

```bash
for skill in recipe-cookalong meeting-action-tracker daily-journal task-kanban book-reading-card security-alert; do
  mkdir -p /Users/ken/workspace/vela/app/src/main/assets/skills/$skill
done
```

### Step 2: Create skill.yaml and template.html for each skill

**CRITICAL:** All template.html files must use this exact import map with absolute paths:
```html
<script type="importmap">{"imports":{"lit":"/lib/lit-core.min.js","@vela/runtime":"/lib/vela-runtime.js","@vela/":"/blocks/"}}</script>
```

**`recipe-cookalong/skill.yaml`:**
```yaml
name: Recipe Cook-along
archetypes: [recipe, cooking, meal, ingredients, food]
blocks: [vela-step-through, vela-checklist, vela-timer]
confidence_threshold: 0.7
description: Step-by-step cook-along with ingredient tracking and timers
```

**`recipe-cookalong/template.html`:** Full HTML document with import map. Imports `@vela/vela-step-through.js`, `@vela/vela-checklist.js`, and `@vela/runtime`. In `window.onVelaReady`: reads vault data via `vela.vault.read(vela.app.context.itemPath, 'json')`, extracts title from frontmatter or first heading, populates `vela-step-through` with sections, populates `vela-checklist` with list items as ingredients. CSS: clean sans-serif, max-width 600px, section spacing.

**`meeting-action-tracker/skill.yaml`:**
```yaml
name: Meeting Action Tracker
archetypes: [meeting, standup, notes, 1on1, interview, discussion, sync]
blocks: [vela-action-list, vela-metadata-card, vela-timeline]
confidence_threshold: 0.7
description: Extracts tasks and decisions from meeting notes with owner tracking
```

**`meeting-action-tracker/template.html`:** Imports `vela-action-list`, `vela-metadata-card`. Shows meeting title, metadata card from frontmatter, action items list from list sections.

**`security-alert/skill.yaml`:**
```yaml
name: Security Alert Tracker
archetypes: [security, oauth, alert, incident, breach, vulnerability, warning, phishing]
blocks: [vela-action-list, vela-metadata-card, vela-status-badge]
confidence_threshold: 0.72
description: Track status and response actions for security events
```

**`security-alert/template.html`:** Imports `vela-action-list`, `vela-metadata-card`, `vela-status-badge`. Shows alert title (red-tinted), status badge (tap-to-cycle), metadata card, response actions list.

**`daily-journal/skill.yaml`:**
```yaml
name: Daily Journal Dashboard
archetypes: [journal, diary, daily, log, reflection, morning-pages]
blocks: [vela-checklist, vela-heatmap-calendar]
confidence_threshold: 0.7
description: Habits checklist and consistency heatmap for daily notes
```

**`daily-journal/template.html`:** Imports `vela-checklist`, `vela-heatmap-calendar`. Shows journal title, habits checklist from list items, heatmap placeholder.

**`task-kanban/skill.yaml`:**
```yaml
name: Task Kanban Board
archetypes: [task, project, todo, sprint, backlog, kanban, roadmap]
blocks: [vela-kanban, vela-progress-ring]
confidence_threshold: 0.7
description: Kanban board with progress ring for task and project notes
```

**`task-kanban/template.html`:** Imports `vela-kanban`, `vela-progress-ring`. Parses list sections into kanban columns (To Do / Doing / Done), shows progress ring with completion percentage.

**`book-reading-card/skill.yaml`:**
```yaml
name: Book Reading Card
archetypes: [book, reading, literature, highlights, notes, review, summary]
blocks: [vela-progress-ring, vela-metadata-card, vela-checklist]
confidence_threshold: 0.7
description: Reading progress, book metadata, and key points checklist
```

**`book-reading-card/template.html`:** Imports `vela-progress-ring`, `vela-metadata-card`, `vela-checklist`. Shows book metadata from frontmatter, progress ring, key points checklist from list items.

All template.html files follow the same pattern as the recipe-cookalong example — full HTML5 document, import map, module script with `window.onVelaReady`, minimal clean CSS.

### Step 3: Remove .gitkeep from skills/

```bash
rm /Users/ken/workspace/vela/app/src/main/assets/skills/.gitkeep
```

### Step 4: Verify

```bash
ls /Users/ken/workspace/vela/app/src/main/assets/skills/ | wc -l
```
Expected: `6`

```bash
cd /Users/ken/workspace/vela && ./gradlew :app:installDebug 2>&1 | tail -4
adb -s 10.0.0.106:38391 shell am start -n com.vela.app/.MainActivity && sleep 3
adb -s 10.0.0.106:38391 shell "curl -s http://localhost:7701/skills/recipe-cookalong/skill.yaml | head -3"
```
Expected: `name: Recipe Cook-along`

### Step 5: Commit

```bash
git add app/src/main/assets/skills/ && git commit -m "feat: add 6 P0 skill templates with YAML metadata"
```

**Theory of Success:** 6 skill directories exist. Each has `skill.yaml` and `template.html`. `curl /skills/recipe-cookalong/skill.yaml` returns YAML content. Templates use absolute `/blocks/` paths in import maps.

---

## Task 7: ArchetypeDetector.kt

**File to create:** `app/src/main/kotlin/com/vela/app/ai/ArchetypeDetector.kt`

### Step 1: Create the file

```kotlin
package com.vela.app.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ArchetypeDetector @Inject constructor(
    private val amplifierSession: AmplifierSession,
) {
    data class DetectionResult(
        val archetype: String,
        val confidence: Float,
        val displayLabel: String,
    )

    private val KNOWN_ARCHETYPES = setOf(
        "recipe", "cooking", "meal",
        "meeting", "standup", "notes",
        "journal", "diary", "daily",
        "task", "project", "kanban", "todo",
        "book", "reading", "literature",
        "security", "oauth", "alert", "incident",
        "finance", "budget", "expense",
        "contact", "person", "crm",
        "travel", "trip", "itinerary",
        "research", "paper", "article",
        "health", "fitness", "workout",
    )

    suspend fun detect(contentType: String, content: String): DetectionResult =
        withContext(Dispatchers.IO) {
            val snippet = content.take(500).replace("\"", "'")
            val prompt = buildString {
                appendLine("Content type hint: \"$contentType\"")
                appendLine("Content preview:")
                appendLine(snippet)
                appendLine()
                appendLine("Classify this content. Pick the most specific archetype from: ${KNOWN_ARCHETYPES.joinToString()}")
                append("Return ONLY JSON: {\"archetype\": \"tag\", \"confidence\": 0.0_to_1.0, \"label\": \"human readable name\"}")
            }
            try {
                val sb = StringBuilder()
                amplifierSession.runTurn(
                    historyJson       = "[]",
                    userInput         = prompt,
                    userContentJson   = null,
                    systemPrompt      = "You are a content type classifier. Return only valid JSON.",
                    onToolStart       = { _, _ -> "" },
                    onToolEnd         = { _, _ -> },
                    onToken           = { sb.append(it) },
                    onProviderRequest = { null },
                    onServerTool      = { _, _ -> },
                )
                val raw = sb.toString().trim()
                    .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                val json = JSONObject(raw)
                DetectionResult(
                    archetype    = json.optString("archetype", contentType),
                    confidence   = json.optDouble("confidence", 0.5).toFloat(),
                    displayLabel = json.optString("label", contentType),
                )
            } catch (_: Exception) {
                DetectionResult(
                    archetype    = contentType,
                    confidence   = 0.5f,
                    displayLabel = contentType,
                )
            }
        }
}
```

### Step 2: Verify

```bash
cd /Users/ken/workspace/vela && ./gradlew :app:compileDebugKotlin 2>&1 | tail -4
```
Expected: `BUILD SUCCESSFUL`

```bash
find app/build/generated/ksp/debug -name "ArchetypeDetector*" 2>/dev/null | head -3
```
Expected: At least one file (Hilt/Dagger generated factory).

### Step 3: Commit

```bash
git add app/src/main/kotlin/com/vela/app/ai/ArchetypeDetector.kt && git commit -m "feat: add ArchetypeDetector for fast content classification"
```

**Theory of Success:** `compileDebugKotlin` passes. KSP generates `ArchetypeDetector_Factory`. The class uses first 500 chars only for speed (~1-2s LLM call). Falls back to `contentType` on any error. Wrapped in `Dispatchers.IO`.

---

## Task 8: SkillLibrary.kt

**File to create:** `app/src/main/kotlin/com/vela/app/ai/SkillLibrary.kt`

### Step 1: Create the file

```kotlin
package com.vela.app.ai

import android.content.Context
import com.vela.app.vault.VaultRegistry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SkillLibrary @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vaultRegistry: VaultRegistry,
) {
    data class Skill(
        val id: String,
        val name: String,
        val archetypes: List<String>,
        val blocks: List<String>,
        val confidenceThreshold: Float,
        val description: String,
        val isVaultSkill: Boolean = false,
    )

    data class SkillMatch(val skill: Skill, val confidence: Float)

    /**
     * Returns skills whose archetype tags match [archetype], ranked by confidence.
     * Matching is case-insensitive and supports substring containment
     * (e.g. archetype "cooking" matches skill tag "cook").
     */
    suspend fun findMatches(
        archetype: String,
        confidence: Float,
        limit: Int = 3,
    ): List<SkillMatch> = withContext(Dispatchers.IO) {
        loadAllSkills()
            .mapNotNull { skill ->
                val matches = skill.archetypes.any { tag ->
                    tag.equals(archetype, ignoreCase = true) ||
                    archetype.contains(tag, ignoreCase = true) ||
                    tag.contains(archetype, ignoreCase = true)
                }
                if (!matches) null else SkillMatch(skill, confidence)
            }
            .sortedByDescending { it.confidence }
            .take(limit)
    }

    /** Loads all skills — vault skills override asset skills with the same id. */
    suspend fun loadAllSkills(): List<Skill> = withContext(Dispatchers.IO) {
        val vault = loadVaultSkills()
        val asset = loadAssetSkills()
        val vaultIds = vault.map { it.id }.toSet()
        vault + asset.filter { it.id !in vaultIds }
    }

    /** Reads the template.html for a skill, from vault or assets. */
    suspend fun loadTemplate(skillId: String, isVaultSkill: Boolean): String? =
        withContext(Dispatchers.IO) {
            if (isVaultSkill) {
                val vault = vaultRegistry.enabledVaults.value.firstOrNull()
                    ?: return@withContext null
                File(vault.localPath, ".vela/skills/$skillId/template.html")
                    .takeIf { it.exists() }?.readText()
            } else {
                runCatching {
                    context.assets.open("skills/$skillId/template.html")
                        .bufferedReader().readText()
                }.getOrNull()
            }
        }

    private fun loadAssetSkills(): List<Skill> =
        runCatching { context.assets.list("skills") }.getOrNull()
            ?.mapNotNull { parseAssetSkill(it) } ?: emptyList()

    private fun loadVaultSkills(): List<Skill> {
        val vault = vaultRegistry.enabledVaults.value.firstOrNull() ?: return emptyList()
        val dir = File(vault.localPath, ".vela/skills")
        return if (!dir.exists()) emptyList()
        else dir.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { parseVaultSkill(it) }
            ?: emptyList()
    }

    private fun parseAssetSkill(id: String): Skill? = runCatching {
        val yaml = context.assets.open("skills/$id/skill.yaml").bufferedReader().readText()
        parseYaml(id, yaml, isVaultSkill = false)
    }.getOrNull()

    private fun parseVaultSkill(dir: File): Skill? = runCatching {
        val yaml = File(dir, "skill.yaml").readText()
        parseYaml(dir.name, yaml, isVaultSkill = true)
    }.getOrNull()

    /**
     * Minimal YAML parser — handles simple `key: value` and `key: [item1, item2]` format.
     * No external YAML library needed. Malformed files are silently skipped.
     */
    private fun parseYaml(id: String, yaml: String, isVaultSkill: Boolean): Skill {
        val lines = yaml.lines().associate { line ->
            val idx = line.indexOf(':')
            if (idx < 0) "" to ""
            else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
        }
        fun parseList(raw: String?) = raw
            ?.removePrefix("[")?.removeSuffix("]")
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?: emptyList()
        return Skill(
            id                  = id,
            name                = lines["name"] ?: id,
            archetypes          = parseList(lines["archetypes"]),
            blocks              = parseList(lines["blocks"]),
            confidenceThreshold = lines["confidence_threshold"]?.toFloatOrNull() ?: 0.7f,
            description         = lines["description"] ?: "",
            isVaultSkill        = isVaultSkill,
        )
    }
}
```

### Step 2: Verify

```bash
cd /Users/ken/workspace/vela && ./gradlew :app:compileDebugKotlin 2>&1 | tail -4
```
Expected: `BUILD SUCCESSFUL`

```bash
find app/build/generated/ksp/debug -name "SkillLibrary*" 2>/dev/null | head -3
```
Expected: At least one generated factory file.

### Step 3: Commit

```bash
git add app/src/main/kotlin/com/vela/app/ai/SkillLibrary.kt && git commit -m "feat: add SkillLibrary for skill discovery and template loading"
```

**Theory of Success:** `compileDebugKotlin` passes. `SkillLibrary_Factory` generated. Hand-rolled YAML parser handles `[item1, item2]` list syntax. All parsing wrapped in `runCatching`. Vault skills take priority over asset skills via id deduplication.

---

## Task 9: RendererAssembler.kt

**File to create:** `app/src/main/kotlin/com/vela/app/ai/RendererAssembler.kt`

### Step 1: Create the file

```kotlin
package com.vela.app.ai

import com.vela.app.ui.miniapp.VelaTheme
import com.vela.app.vault.VaultRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates the skill-based generation pipeline:
 * 1. Detect archetype via [ArchetypeDetector]
 * 2. Find matching skills via [SkillLibrary]
 * 3. If matched: write template directly (no LLM for HTML — near-instant)
 * 4. If unmatched: fall back to open-ended [RendererGenerator]
 */
@Singleton
class RendererAssembler @Inject constructor(
    private val archetypeDetector: ArchetypeDetector,
    private val skillLibrary: SkillLibrary,
    private val rendererGenerator: RendererGenerator,
    private val vaultRegistry: VaultRegistry,
) {
    data class AssemblyResult(
        val result: GenerationResult,
        val skillUsed: SkillLibrary.Skill?,
        val archetype: ArchetypeDetector.DetectionResult?,
    )

    suspend fun assemble(
        itemPath: String,
        itemContent: String,
        contentType: String,
        theme: VelaTheme,
        layout: String,
        rendererType: RendererType,
        feedback: String? = null,
        existingHtml: String? = null,
        onPhase: (index: Int, detail: String) -> Unit = { _, _ -> },
        onToken: ((String) -> Unit)? = null,
    ): AssemblyResult = withContext(Dispatchers.IO) {
        // Phase 0: Detecting content type
        onPhase(0, contentType)

        val detection = archetypeDetector.detect(contentType, itemContent)
        // Phase 1: Skill selected
        onPhase(1, "${detection.displayLabel} · ${(detection.confidence * 100).toInt()}%")

        val matches = skillLibrary.findMatches(detection.archetype, detection.confidence)
        val topSkill = matches.firstOrNull()?.skill

        if (topSkill != null && feedback == null) {
            // Skill-based path: write template directly, no LLM for HTML
            // Phase 2: Assembling blocks
            onPhase(2, topSkill.blocks.joinToString(" · "))

            val template = skillLibrary.loadTemplate(topSkill.id, topSkill.isVaultSkill)
            if (template != null) {
                // Phase 3: Building your app
                onPhase(3, topSkill.name)

                val vault = vaultRegistry.enabledVaults.value.firstOrNull()
                    ?: return@withContext AssemblyResult(
                        GenerationResult.Failure(
                            IllegalStateException("No vault configured"),
                        ),
                        topSkill,
                        detection,
                    )
                val dir = File(vault.localPath, ".vela/renderers/$contentType")
                    .also { it.mkdirs() }
                val file = File(dir, "renderer.html")
                    .also { it.writeText(template) }

                // Phase 4: Saving
                onPhase(4, "")

                return@withContext AssemblyResult(
                    GenerationResult.Success(file.absolutePath),
                    topSkill,
                    detection,
                )
            }
        }

        // Fallback: open-ended LLM generation
        onPhase(2, "Generating…")
        onPhase(3, rendererType.label)

        val result = rendererGenerator.generateRenderer(
            itemPath     = itemPath,
            itemContent  = itemContent,
            contentType  = contentType,
            theme        = theme,
            layout       = layout,
            rendererType = rendererType,
            feedback     = feedback,
            existingHtml = existingHtml,
            onToken      = onToken,
            onActivity   = { _ -> },
        )

        onPhase(4, "")
        AssemblyResult(result, null, detection)
    }
}
```

### Step 2: Verify

```bash
cd /Users/ken/workspace/vela && ./gradlew :app:compileDebugKotlin 2>&1 | tail -4
```
Expected: `BUILD SUCCESSFUL`

```bash
find app/build/generated/ksp/debug -name "RendererAssembler*" 2>/dev/null | head -3
```
Expected: At least one generated factory file.

### Step 3: Commit

```bash
git add app/src/main/kotlin/com/vela/app/ai/RendererAssembler.kt && git commit -m "feat: add RendererAssembler orchestrating skill-based generation pipeline"
```

**Theory of Success:** `compileDebugKotlin` passes. `RendererAssembler_Factory` generated. Skill-based path doesn't call the LLM — near-instant. Fallback path maintains full backward compat with `RendererGenerator`. All file writes wrapped in `Dispatchers.IO`.

---

## Task 10: Wire RendererAssembler into MiniAppViewModel

**File to modify:** `app/src/main/kotlin/com/vela/app/ui/miniapp/MiniAppRuntime.kt`

### Step 1: Add new dependencies to MiniAppViewModel constructor

Replace the constructor (L161-171):

```kotlin
@HiltViewModel
class MiniAppViewModel @Inject constructor(
    internal val documentStore: MiniAppDocumentStore,
    internal val eventBus: EventBus,
    internal val amplifierSession: AmplifierSession,
    internal val vaultManager: VaultManager,
    private val vaultRegistry: VaultRegistry,
    internal val rendererGenerator: RendererGenerator,
    private val capabilitiesRepo: CapabilitiesGraphRepository,
    private val server: VelaMiniAppServer,
) : ViewModel() {
```

With:

```kotlin
@HiltViewModel
class MiniAppViewModel @Inject constructor(
    internal val documentStore: MiniAppDocumentStore,
    internal val eventBus: EventBus,
    internal val amplifierSession: AmplifierSession,
    internal val vaultManager: VaultManager,
    private val vaultRegistry: VaultRegistry,
    internal val rendererGenerator: RendererGenerator,
    private val rendererAssembler: com.vela.app.ai.RendererAssembler,
    private val archetypeDetector: com.vela.app.ai.ArchetypeDetector,
    private val skillLibrary: com.vela.app.ai.SkillLibrary,
    private val capabilitiesRepo: CapabilitiesGraphRepository,
    private val server: VelaMiniAppServer,
) : ViewModel() {
```

Hilt generates all factories automatically — no manual module changes needed.

### Step 2: Update generateRenderer() to use RendererAssembler

Replace the entire `generateRenderer()` method (the one written in Task 2) with a version that delegates to `rendererAssembler.assemble()`:

```kotlin
    /**
     * Starts async renderer generation for [rendererType].
     * Uses [RendererAssembler] which tries skill-based instant generation first,
     * falling back to open-ended LLM generation.
     * Updates [buildPhases] as generation progresses through 5 phases.
     */
    suspend fun generateRenderer(
        itemPath: String,
        itemContent: String,
        contentType: String,
        theme: VelaTheme,
        layout: String,
        rendererType: com.vela.app.ai.RendererType,
        feedback: String? = null,
    ): com.vela.app.ai.GenerationResult {
        _buildPhases.value = listOf(
            BuildPhase("Detecting content type", status = PhaseStatus.ACTIVE),
            BuildPhase("Skill selected"),
            BuildPhase("Assembling blocks"),
            BuildPhase("Building your app"),
            BuildPhase("Saving"),
        )

        fun setPhase(index: Int, detail: String = "") {
            _buildPhases.update { phases ->
                phases.mapIndexed { i, p ->
                    when {
                        i < index  -> p.copy(status = PhaseStatus.DONE)
                        i == index -> p.copy(status = PhaseStatus.ACTIVE, detail = detail)
                        else       -> p
                    }
                }
            }
        }

        val existingHtml = if (feedback != null) getRendererFile(contentType)?.readText() else null

        val assembly = rendererAssembler.assemble(
            itemPath     = itemPath,
            itemContent  = itemContent,
            contentType  = contentType,
            theme        = theme,
            layout       = layout,
            rendererType = rendererType,
            feedback     = feedback,
            existingHtml = existingHtml,
            onPhase      = { index, detail -> setPhase(index, detail) },
            onToken      = null,
        )

        if (assembly.result is com.vela.app.ai.GenerationResult.Success) {
            _buildPhases.update { phases -> phases.map { it.copy(status = PhaseStatus.DONE) } }
        }

        return assembly.result
    }
```

### Step 3: Update suggestRendererTypes() to use ArchetypeDetector + SkillLibrary

Replace the `suggestRendererTypes()` method (L248-307) with a version that tries skill matching first:

```kotlin
    /**
     * Uses [ArchetypeDetector] + [SkillLibrary] for primary suggestions,
     * falling back to LLM-based suggestions if no skills match.
     */
    suspend fun suggestRendererTypes(
        itemContent: String,
        contentType: String,
    ): List<RendererSuggestion> {
        return try {
            val detection = archetypeDetector.detect(contentType, itemContent)
            val matches = skillLibrary.findMatches(detection.archetype, detection.confidence, limit = 3)
            if (matches.isNotEmpty()) {
                matches.map { match ->
                    RendererSuggestion(
                        type = when {
                            match.skill.id.contains("kanban") || match.skill.id.contains("task") ->
                                com.vela.app.ai.RendererType.DASHBOARD
                            match.skill.id.contains("journal") || match.skill.id.contains("tracker") ||
                            match.skill.id.contains("habit") ->
                                com.vela.app.ai.RendererType.INTERACTIVE
                            else -> com.vela.app.ai.RendererType.READER
                        },
                        label       = match.skill.name,
                        description = match.skill.description,
                    )
                }
            } else {
                suggestViaLlm(itemContent, contentType)
            }
        } catch (e: Exception) {
            Log.d("MiniAppViewModel", "suggestRendererTypes fallback: ${e.message}")
            com.vela.app.ai.RendererType.entries.map { type ->
                RendererSuggestion(
                    type        = type,
                    label       = type.label,
                    description = type.promptStyle.take(70) + "…",
                )
            }
        }
    }

    /** LLM-based suggestions for content that doesn't match any skill. */
    private suspend fun suggestViaLlm(
        itemContent: String,
        contentType: String,
    ): List<RendererSuggestion> {
        val preview = itemContent.take(600).replace("\"", "'")
        val prompt = """
You are helping a user decide what to build as an interactive mini app for their vault content.

Content type: "$contentType"
Content preview:
$preview

Suggest exactly 3 specific things the user could DO with this content as an interactive app.
Focus on UTILITY and ACTIONS the user can take — not visual style or aesthetics.
Good examples: "Track which recipes you've tried", "Build a step-by-step cook-along guide"
Bad examples: "Reader view", "Interactive layout", "Dashboard style"

Return ONLY valid JSON array of exactly 3 objects:
[{"type":"READER|INTERACTIVE|DASHBOARD","label":"short action title (max 6 words)","description":"what the user can do (max 12 words)"}]
        """.trimIndent()

        val sb = StringBuilder()
        amplifierSession.runTurn(
            historyJson       = "[]",
            userInput         = prompt,
            userContentJson   = null,
            systemPrompt      = "Return ONLY valid JSON. No markdown. No explanation.",
            onToolStart       = { _, _ -> "" },
            onToolEnd         = { _, _ -> },
            onToken           = { token -> sb.append(token) },
            onProviderRequest = { null },
            onServerTool      = { _, _ -> },
        )

        val raw = sb.toString().trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

        val arr = JSONArray(raw)
        return (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            RendererSuggestion(
                type = com.vela.app.ai.RendererType.entries.find {
                    it.name == obj.optString("type")
                } ?: com.vela.app.ai.RendererType.READER,
                label       = obj.optString("label", "Mini App"),
                description = obj.optString("description", ""),
            )
        }.takeIf { it.size == 3 } ?: com.vela.app.ai.RendererType.entries.map { type ->
            RendererSuggestion(
                type        = type,
                label       = type.label,
                description = type.promptStyle.take(70) + "…",
            )
        }
    }
```

### Step 4: Verify compilation

```bash
cd /Users/ken/workspace/vela && ./gradlew :app:assembleDebug 2>&1 | tail -4
```
Expected: `BUILD SUCCESSFUL`

### Step 5: Deploy and test on device

```bash
cd /Users/ken/workspace/vela && ./gradlew :app:installDebug 2>&1 | tail -4
adb connect 10.0.0.106:38391 && \
adb -s 10.0.0.106:38391 shell am force-stop com.vela.app && sleep 1 && \
adb -s 10.0.0.106:38391 shell am start -n com.vela.app/.MainActivity && sleep 4 && \
adb -s 10.0.0.106:38391 logcat -d | grep -E "FATAL|VelaMiniAppServer|RendererAssembler" | tail -10
```
Expected: No FATAL exceptions. App starts normally.

Manual test: Open a vault `.md` file (recipe or meeting notes). Tap `[Markdown ▾]` -> "Generate different view..." -> The 3 suggestions should show skill names (e.g., "Recipe Cook-along") when content matches a skill. Selecting one -> 5 phase cards animate -> mini app loads from skill template.

### Step 6: Commit

```bash
git add app/src/main/kotlin/com/vela/app/ui/miniapp/MiniAppRuntime.kt && git commit -m "feat: wire RendererAssembler into MiniAppViewModel for skill-based generation"
```

**Theory of Success:** `assembleDebug` passes. On device: opening a recipe `.md` and generating shows skill-matched suggestions. Selecting a skill triggers the phase cards animation and produces a Lit-based template mini app in 2-3 seconds (instead of 15-30s LLM generation). The skill-based path short-circuits the LLM entirely for HTML — only the archetype detection call hits the LLM.

---

## Phase 1 Overall Theory of Success

> Vela starts. Server binds on `:7701`. Opening a vault `.md` file shows plain markdown — no FAB anywhere. Tapping `[Markdown ▾]` -> "Generate different view..." detects the archetype, shows 3 skill-matched suggestions. Selecting one triggers 5 animated phase cards (not a progress bar). For skill-matched content (recipe, meeting, journal, task, security, book), the mini app appears in 2-3 seconds — a Lit Web Component template served from `http://localhost:7701/miniapps/{type}`, with blocks from `/blocks/*.js` and the runtime from `/lib/vela-runtime.js`.

## Deferred to Phase 2

- Skill picker UI redesign (detection banner + skill cards with block tags in the sheet)
- Remix mode (BottomSheetScaffold + RendererSession.kt + live chat)
- EventBus listeners in native Compose for `app:navigate`, `app:notify`, `app:remix-requested`, `app:record-start`
- `db.query` / `db.mutate` Ktor routes
