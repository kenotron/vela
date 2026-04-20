# Vela Mini App Backend Server — Implementation Plan

> **Execution:** Use the subagent-driven-development workflow to implement this plan.

**Goal:** Replace the `@JavascriptInterface` bridge in Vela's mini app WebViews with a local Ktor-CIO HTTP server that serves renderer HTML, exposes a REST API, and enables LAN access.

**Architecture:** A `@Singleton` Ktor-CIO server starts in `VelaApplication.onCreate()` on port 7701 (with 7702/7703 fallback). WebViews load renderers via `http://localhost:PORT/miniapps/{type}` instead of `loadDataWithBaseURL(file://...)`. A JS shim injected `onPageFinished` maps `window.vela.*` calls to `fetch()` against the server's REST endpoints. LAN toggle in Settings rebinds from `127.0.0.1` to `0.0.0.0`.

**Tech Stack:** Kotlin, Ktor-CIO 2.3.13, CommonMark 0.22.0, Hilt DI, Compose UI, Room, Coroutines.

---

## Task 1: Add Ktor + CommonMark dependencies

**Files:**
- Modify: `app/build.gradle.kts` (line ~148, after the Markwon block)

**Step 1: Add dependencies**

In `app/build.gradle.kts`, add the following three lines after the Markwon block (after line 146 — `implementation("io.noties.markwon:linkify:4.6.2")`), inside the `dependencies { }` block:

```kotlin
        // Ktor embedded server for mini app backend
        implementation("io.ktor:ktor-server-core:2.3.13")
        implementation("io.ktor:ktor-server-cio:2.3.13")

        // CommonMark for server-side markdown → JSON transform
        implementation("org.commonmark:commonmark:0.22.0")
```

**Step 2: Verify compilation**

Run:
```bash
cd /Users/ken/workspace/vela && ./gradlew :app:compileDebugKotlin 2>&1 | tail -5
```
Expected: `BUILD SUCCESSFUL`

**Step 3: Commit**
```bash
git add app/build.gradle.kts && git commit -m "feat: add Ktor-CIO and CommonMark dependencies for mini app server"
```

---

## Task 2: Create VelaMiniAppServer.kt

**Files:**
- Create: `app/src/main/kotlin/com/vela/app/server/VelaMiniAppServer.kt`

**Step 1: Create the server class**

Create the file `app/src/main/kotlin/com/vela/app/server/VelaMiniAppServer.kt` with this content:

```kotlin
package com.vela.app.server

import android.util.Log
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VelaMiniAppServer @Inject constructor(
    private val routes: VelaMiniAppRoutes,
) {
    companion object {
        const val DEFAULT_HOST = "127.0.0.1"
        const val DEFAULT_PORT = 7701
        private val FALLBACK_PORTS = listOf(7701, 7702, 7703)
        const val TAG = "VelaMiniAppServer"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var engine: ApplicationEngine? = null

    private val _port = MutableStateFlow(DEFAULT_PORT)
    val port: StateFlow<Int> = _port.asStateFlow()

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _lanEnabled = MutableStateFlow(false)
    val lanEnabled: StateFlow<Boolean> = _lanEnabled.asStateFlow()

    fun start(host: String = DEFAULT_HOST) {
        scope.launch {
            _isReady.value = false
            var bound = false
            for (tryPort in FALLBACK_PORTS) {
                try {
                    engine = embeddedServer(CIO, host = host, port = tryPort) {
                        routes.install(this)
                    }.start(wait = false)
                    _port.value = tryPort
                    _lanEnabled.value = host != DEFAULT_HOST
                    _isReady.value = true
                    bound = true
                    Log.i(TAG, "Server started on $host:$tryPort")
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Port $tryPort unavailable: ${e.message}")
                }
            }
            if (!bound) Log.e(TAG, "All ports unavailable — mini app server not started")
        }
    }

    fun restart(host: String) {
        engine?.stop(1_000, 2_000)
        engine = null
        start(host)
    }

    fun stop() {
        engine?.stop(1_000, 2_000)
        _isReady.value = false
    }
}
```

**Step 2: Verify compilation**

This file depends on `VelaMiniAppRoutes` which doesn't exist yet. The compilation will fail. That's expected — Task 3 creates the dependency. Skip compilation verification here.

**Step 3: Commit** (deferred — commit with Task 3)

---

## Task 3: Create VelaMiniAppRoutes.kt

**Files:**
- Create: `app/src/main/kotlin/com/vela/app/server/VelaMiniAppRoutes.kt`

**Context (verified from codebase):**
- `AmplifierSession.runTurn()` signature (from `AmplifierSession.kt:40-50`):
  ```kotlin
  suspend fun runTurn(
      historyJson: String,
      userInput: String,
      userContentJson: String?,
      systemPrompt: String,
      onToolStart: (suspend (name: String, argsJson: String) -> String),
      onToolEnd: (suspend (stableId: String, result: String) -> Unit),
      onToken: (suspend (token: String) -> Unit),
      onProviderRequest: (suspend () -> String?),
      onServerTool: (suspend (name: String, argsJson: String) -> Unit),
  )
  ```
- `VaultRegistry.enabledVaults` is `StateFlow<List<VaultEntity>>` where `VaultEntity.localPath: String`
- `/api/db/query` and `/api/db/mutate` are intentionally omitted from v1

**Step 1: Create the routes class**

Create `app/src/main/kotlin/com/vela/app/server/VelaMiniAppRoutes.kt`:

```kotlin
package com.vela.app.server

import com.vela.app.ai.AmplifierSession
import com.vela.app.vault.VaultRegistry
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.commonmark.node.*
import org.commonmark.parser.Parser
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VelaMiniAppRoutes @Inject constructor(
    private val vaultRegistry: VaultRegistry,
    private val amplifierSession: AmplifierSession,
) {
    // In-memory event ring buffer — max 100 events, thread-safe via synchronized
    private val eventBuffer = ArrayDeque<Triple<String, String, Long>>()

    fun install(app: Application) {
        app.routing {

            get("/health") {
                call.respondText(
                    JSONObject().put("ok", true).toString(),
                    ContentType.Application.Json
                )
            }

            // Serve renderer HTML
            get("/miniapps/{contentType}") {
                val type = call.parameters["contentType"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing contentType")
                val vault = vaultRegistry.enabledVaults.value.firstOrNull()
                    ?: return@get call.respond(HttpStatusCode.ServiceUnavailable, "No vault configured")
                val file = File(vault.localPath, ".vela/renderers/$type/renderer.html")
                if (!file.exists()) return@get call.respond(HttpStatusCode.NotFound, "No renderer for $type")
                call.respondText(withContext(Dispatchers.IO) { file.readText() }, ContentType.Text.Html)
            }

            // Delete renderer (force fresh generation)
            delete("/miniapps/{contentType}") {
                val type = call.parameters["contentType"]
                    ?: return@delete call.respond(HttpStatusCode.BadRequest)
                val vault = vaultRegistry.enabledVaults.value.firstOrNull()
                    ?: return@delete call.respond(HttpStatusCode.ServiceUnavailable)
                withContext(Dispatchers.IO) {
                    File(vault.localPath, ".vela/renderers/$type/renderer.html").delete()
                }
                call.respondText(JSONObject().put("ok", true).toString(), ContentType.Application.Json)
            }

            // Vault read — raw text or parsed JSON
            get("/api/vault/read") {
                val path = call.request.queryParameters["path"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing path")
                val format = call.request.queryParameters["format"] ?: "raw"
                val vault = vaultRegistry.enabledVaults.value.firstOrNull()
                    ?: return@get call.respond(HttpStatusCode.ServiceUnavailable)
                val file = File(vault.localPath, path)
                if (!file.exists()) return@get call.respond(HttpStatusCode.NotFound)
                val content = withContext(Dispatchers.IO) { file.readText() }
                if (format == "json") {
                    call.respondText(parseMarkdownToJson(content).toString(), ContentType.Application.Json)
                } else {
                    call.respondText(content, ContentType.Text.Plain)
                }
            }

            // Vault directory list
            get("/api/vault/list") {
                val path = call.request.queryParameters["path"] ?: ""
                val vault = vaultRegistry.enabledVaults.value.firstOrNull()
                    ?: return@get call.respond(HttpStatusCode.ServiceUnavailable)
                val dir = File(vault.localPath, path)
                val arr = JSONArray()
                withContext(Dispatchers.IO) {
                    dir.listFiles()?.sortedBy { it.name }?.forEach { f ->
                        arr.put(JSONObject().apply {
                            put("name", f.name)
                            put("path", f.relativeTo(File(vault.localPath)).path)
                            put("isDir", f.isDirectory)
                            put("size", if (f.isFile) f.length() else 0)
                        })
                    }
                }
                call.respondText(arr.toString(), ContentType.Application.Json)
            }

            // AI complete — single synchronous turn
            post("/api/ai/complete") {
                val body = JSONObject(call.receiveText())
                val prompt = body.getString("prompt")
                val systemPrompt = body.optString("systemPrompt", "You are a helpful assistant.")
                val sb = StringBuilder()
                amplifierSession.runTurn(
                    historyJson       = "[]",
                    userInput         = prompt,
                    userContentJson   = null,
                    systemPrompt      = systemPrompt,
                    onToolStart       = { _, _ -> "" },
                    onToolEnd         = { _, _ -> },
                    onToken           = { token -> sb.append(token) },
                    onProviderRequest = { null },
                    onServerTool      = { _, _ -> },
                )
                call.respondText(
                    JSONObject().put("text", sb.toString()).toString(),
                    ContentType.Application.Json
                )
            }

            // Events emit
            post("/api/events/emit") {
                val body = JSONObject(call.receiveText())
                synchronized(eventBuffer) {
                    if (eventBuffer.size >= 100) eventBuffer.removeFirst()
                    eventBuffer.addLast(Triple(
                        body.getString("name"),
                        body.optString("data", "{}"),
                        System.currentTimeMillis()
                    ))
                }
                call.respondText(JSONObject().put("ok", true).toString(), ContentType.Application.Json)
            }

            // Events poll
            get("/api/events/poll") {
                val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L
                val arr = JSONArray()
                synchronized(eventBuffer) {
                    eventBuffer.filter { it.third > since }.forEach { (name, data, ts) ->
                        arr.put(JSONObject().put("name", name).put("data", data).put("ts", ts))
                    }
                }
                call.respondText(arr.toString(), ContentType.Application.Json)
            }
        }
    }

    private fun parseMarkdownToJson(markdown: String): JSONObject {
        val parser = Parser.builder().build()
        val doc = parser.parse(markdown)
        val sections = JSONArray()
        val frontmatter = JSONObject()

        // Extract YAML frontmatter between --- delimiters
        val fmRegex = Regex("^---\\n([\\s\\S]*?)\\n---", RegexOption.MULTILINE)
        fmRegex.find(markdown)?.groupValues?.getOrNull(1)?.lines()?.forEach { line ->
            if (line.contains(":")) {
                val idx = line.indexOf(":")
                frontmatter.put(line.substring(0, idx).trim(), line.substring(idx + 1).trim())
            }
        }

        var node: Node? = doc.firstChild
        while (node != null) {
            val obj = JSONObject()
            when (node) {
                is Heading -> {
                    obj.put("type", "heading")
                    obj.put("level", node.level)
                    obj.put("text", collectText(node.firstChild))
                }
                is Paragraph -> {
                    obj.put("type", "paragraph")
                    obj.put("text", collectText(node.firstChild))
                }
                is BulletList, is OrderedList -> {
                    obj.put("type", "list")
                    val items = JSONArray()
                    var item = node.firstChild
                    while (item != null) {
                        items.put(collectText(item.firstChild?.firstChild))
                        item = item.next
                    }
                    obj.put("items", items)
                }
                is FencedCodeBlock -> {
                    obj.put("type", "code")
                    obj.put("text", node.literal)
                }
                else -> { node = node.next; continue }
            }
            sections.put(obj)
            node = node.next
        }
        return JSONObject().put("frontmatter", frontmatter).put("sections", sections)
    }

    private fun collectText(node: Node?): String {
        var n = node
        val sb = StringBuilder()
        while (n != null) {
            when (n) {
                is Text -> sb.append(n.literal)
                is SoftLineBreak -> sb.append(" ")
                is HardLineBreak -> sb.append("\n")
                else -> if (n.firstChild != null) sb.append(collectText(n.firstChild))
            }
            n = n.next
        }
        return sb.toString()
    }
}
```

**Step 2: Verify compilation**

Run:
```bash
cd /Users/ken/workspace/vela && ./gradlew :app:compileDebugKotlin 2>&1 | tail -5
```
Expected: `BUILD SUCCESSFUL`

**Step 3: Commit**
```bash
git add app/src/main/kotlin/com/vela/app/server/ && git commit -m "feat: VelaMiniAppServer + VelaMiniAppRoutes — Ktor-CIO server with REST endpoints"
```

---

## Task 4: Wire server startup in VelaApplication.kt

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/VelaApplication.kt`

**Context (verified):** `VelaApplication.kt` is 28 lines. Hilt field injection pattern: `@Inject lateinit var foo: Foo` (see lines 13-17 for existing examples). `profileWorkerScheduler.schedule()` is on line 21.

**Step 1: Add import**

Add after line 6 (`import com.vela.app.workers.ProfileWorkerScheduler`):
```kotlin
import com.vela.app.server.VelaMiniAppServer
```

**Step 2: Add field injection**

Add after line 17 (`lateinit var profileWorkerScheduler: ProfileWorkerScheduler`):
```kotlin

    @Inject
    lateinit var miniAppServer: VelaMiniAppServer
```

**Step 3: Start server in onCreate()**

Add after line 21 (`profileWorkerScheduler.schedule()`):
```kotlin
        miniAppServer.start()
```

The final `onCreate()` body should be:
```kotlin
    override fun onCreate() {
        super.onCreate()
        profileWorkerScheduler.schedule()
        miniAppServer.start()
    }
```

**Step 4: Verify compilation**

Run:
```bash
cd /Users/ken/workspace/vela && ./gradlew :app:assembleDebug 2>&1 | tail -5
```
Expected: `BUILD SUCCESSFUL`

**Step 5: Commit**
```bash
git add app/src/main/kotlin/com/vela/app/VelaApplication.kt && git commit -m "feat: start mini app server in VelaApplication.onCreate()"
```

---

## Task 5: MiniAppRuntime.kt — WebView HTTP integration

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/ui/miniapp/MiniAppRuntime.kt`

This is the largest task. It has 4 sub-parts.

**Context (verified from MiniAppRuntime.kt):**
- `MiniAppViewModel` constructor is at line 160, parameters on lines 161-167
- `addJavascriptInterface` calls are at lines 542-545
- `loadDataWithBaseURL` (factory) is at lines 577-581
- `loadDataWithBaseURL` (update lambda) is at lines 595-599
- `onPageFinished` script injection is at lines 553-566
- `jsInterface` setup is at lines 386-392
- `RendererFeedbackSheet` call site is at lines 624-638

### Step 1: Add VelaMiniAppServer to MiniAppViewModel constructor

In `MiniAppViewModel`'s `@Inject constructor` (line 160-168), add `VelaMiniAppServer` as the last constructor parameter:

Replace:
```kotlin
    private val capabilitiesRepo: CapabilitiesGraphRepository,
) : ViewModel() {
```
With:
```kotlin
    private val capabilitiesRepo: CapabilitiesGraphRepository,
    private val server: com.vela.app.server.VelaMiniAppServer,
) : ViewModel() {
```

### Step 2: Add server state flows to MiniAppViewModel

Add after the `buildActivity` StateFlow (after line 179):

```kotlin
    val serverPort: StateFlow<Int> = server.port
    val serverReady: StateFlow<Boolean> = server.isReady
```

### Step 3: Add primaryVault() helper to MiniAppViewModel

Add after the `getRendererFile` function (after line 201):

```kotlin
    fun primaryVault() = vaultRegistry.enabledVaults.value.firstOrNull()
```

### Step 4: Replace WebView factory block (Ready/Loading branch)

In the `else ->` branch (line 529 onwards), replace the entire `AndroidView` block. The key changes:

a) **Remove `jsInterface` setup** — delete lines 386-392 (the `val jsInterface = remember(...)` block and the `DisposableEffect` block).

b) **Replace the `factory` lambda** of the `AndroidView` at lines 534-586. Remove the 4 `addJavascriptInterface(...)` calls (lines 542-545). Remove `loadDataWithBaseURL(...)` (lines 577-581) and `loadData(...)` (line 583). Replace with `loadUrl("http://localhost:${server.port.value}/miniapps/$contentType")`.

c) **Replace the `onPageFinished` script injection** (lines 553-567). Instead of the buildString assembling `window.vela` from `__vela_*` interfaces, inject the fetch-based shim:

```kotlin
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView, url: String) {
                                    val velaShim = """
(function(){
  const h={'Content-Type':'application/json'};
  window.vela={
    db:{
      query:(sql,p)=>fetch('/api/db/query',{method:'POST',headers:h,body:JSON.stringify({sql,params:p})}).then(r=>r.json()),
      mutate:(sql,p)=>fetch('/api/db/mutate',{method:'POST',headers:h,body:JSON.stringify({sql,params:p})}).then(r=>r.json())
    },
    ai:{complete:(prompt,sp)=>fetch('/api/ai/complete',{method:'POST',headers:h,body:JSON.stringify({prompt,systemPrompt:sp})}).then(r=>r.json()).then(r=>r.text)},
    vault:{
      read:(p,fmt)=>fetch('/api/vault/read?path='+encodeURIComponent(p)+(fmt?'&format='+fmt:'')).then(r=>fmt==='json'?r.json():r.text()),
      list:(p)=>fetch('/api/vault/list?path='+encodeURIComponent(p||'')).then(r=>r.json())
    },
    events:{
      emit:(n,d)=>fetch('/api/events/emit',{method:'POST',headers:h,body:JSON.stringify({name:n,data:d})}).then(r=>r.json()),
      on:(name,fn)=>{let t=Date.now();setInterval(async()=>{const evs=await fetch('/api/events/poll?since='+t).then(r=>r.json());t=Date.now();evs.filter(e=>e.name===name).forEach(e=>fn(e.data));},3000);}
    }
  };
  window.__VELA_CONTEXT__=$contextJson;
  if(typeof window.onVelaReady==='function')window.onVelaReady();
})();
                                    """.trimIndent()
                                    view.evaluateJavascript(velaShim, null)
                                }
                            }
```

d) **Replace the initial load** — instead of `loadDataWithBaseURL(...)`, use:
```kotlin
                            val port = viewModel.serverPort.value
                            tag = "http://localhost:$port/miniapps/$contentType"
                            loadUrl("http://localhost:$port/miniapps/$contentType")
```

e) **Replace the `update` lambda** (lines 587-607). Replace `loadDataWithBaseURL` tag check with:
```kotlin
                    update = { webView ->
                        val currentState = rendererState
                        if (currentState is RendererState.Ready) {
                            val port = viewModel.serverPort.value
                            val targetUrl = "http://localhost:$port/miniapps/$contentType"
                            if (webView.tag != targetUrl) {
                                webView.tag = targetUrl
                                webView.loadUrl(targetUrl)
                            }
                        }
                        webView.post {
                            webView.evaluateJavascript("window.__VELA_CONTEXT__=$contextJson;", null)
                        }
                    },
```

f) **Add server-ready gate** — In the `else ->` branch, before the `Box(modifier)` that contains the `AndroidView`, add a ready check:

```kotlin
        else -> {
            var showFeedbackSheet by remember { mutableStateOf(false) }
            val serverReady by viewModel.serverReady.collectAsState()

            if (!serverReady) {
                Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                Box(modifier) {
                    // ... existing AndroidView + FAB ...
                }
            }
```

### Step 5: Remove jsInterface setup

Delete the `jsInterface` `remember` block (lines 386-388) and the `DisposableEffect` block (lines 390-392). These are no longer needed since the `@JavascriptInterface` bridge is replaced by HTTP.

Also remove the `createJsInterface` factory method from `MiniAppViewModel` (lines 182-190) — it's dead code now. (If other files reference it, leave it and add a `@Deprecated` annotation instead. Check first with grep.)

### Step 6: Verify no old bridge references remain

Run:
```bash
grep -c "addJavascriptInterface\|__vela_db\|__vela_events\|__vela_ai\|__vela_vault" app/src/main/kotlin/com/vela/app/ui/miniapp/MiniAppRuntime.kt
```
Expected: `0`

### Step 7: Verify compilation

Run:
```bash
cd /Users/ken/workspace/vela && ./gradlew :app:compileDebugKotlin 2>&1 | tail -5
```
Expected: `BUILD SUCCESSFUL`

### Step 8: Commit
```bash
git add app/src/main/kotlin/com/vela/app/ui/miniapp/MiniAppRuntime.kt && git commit -m "feat: replace @JavascriptInterface bridge with HTTP fetch shim in MiniAppRuntime"
```

---

## Task 6: Update RendererGenerator.kt system prompt

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/ai/RendererGenerator.kt`

**Context (verified):** The system prompt is `RENDERER_SYSTEM_PROMPT` at line 317. The SDK reference in the user prompt is at line 239.

### Step 1: Update the SDK line in buildRendererPrompt

In `buildRendererPrompt()`, find line 239:
```kotlin
            appendLine("- Vela SDK: window.vela.db.put/get/delete/watch, events.publish/subscribe, ai.ask/stream, vault.read/write/list/sync")
```

Replace with:
```kotlin
            appendLine("- Vela SDK (all calls return Promises via fetch()):")
            appendLine("  window.vela.db.query(sql, params?) → Promise<{ rows: any[] }>")
            appendLine("  window.vela.db.mutate(sql, params?) → Promise<{ rowsAffected: number }>")
            appendLine("  window.vela.vault.read(path) → Promise<string>")
            appendLine("  window.vela.vault.read(path, 'json') → Promise<{ frontmatter, sections }>")
            appendLine("  window.vela.vault.list(path?) → Promise<[{ name, path, isDir, size }]>")
            appendLine("  window.vela.ai.complete(prompt, systemPrompt?) → Promise<string>")
            appendLine("  window.vela.events.emit(name, data) → Promise<{ ok: true }>")
            appendLine("  window.vela.events.on(name, fn) → void (polls every 3s)")
            appendLine("  Use window.vela.vault.read(path, 'json') to get structured JSON from markdown files rather than parsing raw markdown yourself.")
```

### Step 2: Update RENDERER_SYSTEM_PROMPT Rules section

In the `RENDERER_SYSTEM_PROMPT` constant (line 317), find the Rules section. Replace:
```kotlin
- Use window.vela.db for persistence (scope: local:, type:, or global: with JS comments).
- Use window.vela.events to publish/subscribe cross-app events.
- Call window.onVelaReady for post-SDK-init setup.
```

With:
```kotlin
- Use window.vela.db.query(sql) and window.vela.db.mutate(sql) for persistence. All calls are async (return Promises).
- Use window.vela.vault.read(path, 'json') to get structured markdown content — avoids parsing raw markdown in JS.
- Use window.vela.events.emit(name, data) and window.vela.events.on(name, fn) for cross-app events.
- Use window.vela.ai.complete(prompt) for LLM calls from within the mini app.
- Call window.onVelaReady for post-SDK-init setup.
```

### Step 3: Verify no old SDK references remain

Run:
```bash
grep -c "window.vela.db.put\|window.vela.db.get\|events.publish\|events.subscribe\|ai.ask\|ai.stream\|vault.write\|vault.sync" app/src/main/kotlin/com/vela/app/ai/RendererGenerator.kt
```
Expected: `0`

### Step 4: Verify compilation

Run:
```bash
cd /Users/ken/workspace/vela && ./gradlew :app:compileDebugKotlin 2>&1 | tail -5
```
Expected: `BUILD SUCCESSFUL`

### Step 5: Commit
```bash
git add app/src/main/kotlin/com/vela/app/ai/RendererGenerator.kt && git commit -m "feat: update RendererGenerator prompt with fetch-based window.vela API"
```

---

## Task 7: LAN toggle in Settings

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/ui/settings/SettingsViewModel.kt`
- Modify: `app/src/main/kotlin/com/vela/app/ui/settings/SettingsScreen.kt`

**Context (verified):**
- `SettingsViewModel` constructor is at line 25-31 of `SettingsViewModel.kt`
- `companion object` with `PREFS` constant is at line 43-77
- `prefs` accessor is at line 79: `private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)`
- `SettingsScreen` has `LazyColumn` at line 51-68 with AI row (item lines 52-58) and Recording row (item lines 60-67)

### Step 1: Add VelaMiniAppServer to SettingsViewModel constructor

In `SettingsViewModel`'s constructor (line 25-31), add after `private val gitHubIdentityManager: GitHubIdentityManager,`:
```kotlin
        private val miniAppServer: com.vela.app.server.VelaMiniAppServer,
```

### Step 2: Add KEY_LAN_SERVER constant

In the `companion object` (line 43), add after `private const val KEY_OPENAI_API_KEY = "openai_api_key"`:
```kotlin
            private const val KEY_LAN_SERVER = "mini_app_server_lan"
```

### Step 3: Add LAN state + setter

Add after the `openAiApiKey` StateFlow (after line 93):
```kotlin

        private val _lanEnabled = MutableStateFlow(prefs.getBoolean(KEY_LAN_SERVER, false))
        val lanEnabled: StateFlow<Boolean> = _lanEnabled.asStateFlow()

        fun setLanEnabled(enabled: Boolean) {
            prefs.edit().putBoolean(KEY_LAN_SERVER, enabled).apply()
            _lanEnabled.value = enabled
            miniAppServer.restart(if (enabled) "0.0.0.0" else "127.0.0.1")
        }
```

### Step 4: Add Mini App Server section to SettingsScreen

In `SettingsScreen`, inside the `LazyColumn` (line 51-68), add after the Recording `item` block (after line 67, before the closing `}`):

```kotlin
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            }
            item {
                SectionHeader(
                    title    = "Mini App Server",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            item {
                val lanEnabled by viewModel.lanEnabled.collectAsState()
                ListItem(
                    headlineContent   = { Text("LAN Access") },
                    supportingContent = {
                        Text(if (lanEnabled) "Accessible on your local network" else "Localhost only")
                    },
                    trailingContent   = {
                        Switch(
                            checked         = lanEnabled,
                            onCheckedChange = { viewModel.setLanEnabled(it) },
                        )
                    },
                )
            }
            item {
                val lanEnabled by viewModel.lanEnabled.collectAsState()
                if (lanEnabled) {
                    Text(
                        "\u26A0\uFE0F Anyone on your Wi-Fi can read your vault data.",
                        style    = MaterialTheme.typography.labelSmall,
                        color    = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
```

### Step 5: Verify compilation

Run:
```bash
cd /Users/ken/workspace/vela && ./gradlew :app:assembleDebug 2>&1 | tail -5
```
Expected: `BUILD SUCCESSFUL`

### Step 6: Commit
```bash
git add app/src/main/kotlin/com/vela/app/ui/settings/SettingsViewModel.kt app/src/main/kotlin/com/vela/app/ui/settings/SettingsScreen.kt && git commit -m "feat: LAN toggle in Settings for mini app server"
```

---

## Task 8: Fitness check + utility-first suggestions in MiniAppViewModel

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/ui/miniapp/MiniAppRuntime.kt`

**Context (verified):** `MiniAppViewModel` is at line 159-347. `suggestRendererTypes` is at lines 251-307. The existing prompt for suggestRendererTypes is at lines 264-275.

### Step 1: Add FitnessResult data class + fitnessCheck method

Add to `MiniAppViewModel` after the `suggestFeedback` method (after line 346, before the closing `}`):

```kotlin

    data class FitnessResult(
        val match: String?,
        val confidence: Float,
        val reason: String,
    )

    suspend fun fitnessCheck(
        contentType: String,
        contentSnippet: String,
        existingTypes: List<String>,
    ): FitnessResult {
        if (existingTypes.isEmpty()) return FitnessResult(null, 0f, "no existing mini apps")
        val prompt = buildString {
            appendLine("Vault file content type: \"$contentType\"")
            appendLine("Opening content:")
            appendLine(contentSnippet.take(400))
            appendLine()
            appendLine("Existing mini app types: ${existingTypes.joinToString()}")
            appendLine()
            appendLine("Does one of these types fit this content well?")
            append("Reply ONLY with valid JSON: {\"match\": \"type_name_or_null\", \"confidence\": 0.0_to_1.0, \"reason\": \"brief\"}")
        }
        return try {
            val sb = StringBuilder()
            amplifierSession.runTurn(
                historyJson       = "[]",
                userInput         = prompt,
                userContentJson   = null,
                systemPrompt      = "Return only valid JSON. No explanation.",
                onToolStart       = { _, _ -> "" },
                onToolEnd         = { _, _ -> },
                onToken           = { sb.append(it) },
                onProviderRequest = { null },
                onServerTool      = { _, _ -> },
            )
            val raw = sb.toString().trim()
                .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val json = JSONObject(raw)
            val matchVal = json.optString("match").takeIf { it.isNotBlank() && it != "null" }
            FitnessResult(
                match      = matchVal,
                confidence = json.optDouble("confidence", 0.0).toFloat(),
                reason     = json.optString("reason", ""),
            )
        } catch (e: Exception) {
            FitnessResult(null, 0f, "error: ${e.message}")
        }
    }
```

### Step 2: Update suggestRendererTypes prompt

Replace the existing prompt in `suggestRendererTypes` (lines 264-275):

Find:
```kotlin
            val prompt = """
You are helping a user choose how to turn a vault file into a mini app.
Content type: $contentType
Content preview:
${"\"\"\""}
$preview
${"\"\"\""}

Return ONLY a valid JSON array (no explanation, no markdown fences) with exactly 3 objects:
[{"type":"READER|INTERACTIVE|DASHBOARD","label":"2-4 word title","description":"one sentence specific to this content"}]
Order them from most useful to least useful for this content.
            """.trimIndent()
```

Replace with:
```kotlin
            val prompt = """
You are helping a user decide what to build as an interactive mini app for their vault content.

Content type: "$contentType"
Content preview:
${"\"\"\""}
$preview
${"\"\"\""}

Suggest exactly 3 specific things the user could DO with this content as an interactive app.
Focus on UTILITY and ACTIONS the user can take — not visual style or aesthetics.
Good: "Track which recipes you've tried", "Build a step-by-step cook-along guide"
Bad: "Reader view", "Interactive layout", "Dashboard style"

Return ONLY valid JSON array of exactly 3 objects:
[{"type":"READER|INTERACTIVE|DASHBOARD","label":"short action title (max 6 words)","description":"what the user can do (max 12 words)"}]
            """.trimIndent()
```

### Step 3: Verify compilation

Run:
```bash
cd /Users/ken/workspace/vela && ./gradlew :app:compileDebugKotlin 2>&1 | tail -5
```
Expected: `BUILD SUCCESSFUL`

### Step 4: Verify additions

Run:
```bash
grep -c "data class FitnessResult\|fun fitnessCheck" app/src/main/kotlin/com/vela/app/ui/miniapp/MiniAppRuntime.kt
```
Expected: `2`

### Step 5: Commit
```bash
git add app/src/main/kotlin/com/vela/app/ui/miniapp/MiniAppRuntime.kt && git commit -m "feat: fitness check + utility-first suggestions in MiniAppViewModel"
```

---

## Task 9: Fitness check FAB flow + feedback sheet redesign

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/ui/miniapp/MiniAppRuntime.kt`

**Context (verified):**
- Fallback branch `LaunchedEffect(isAnalysing)` is at lines 414-419
- `RendererFeedbackSheet` is at lines 793-865
- Feedback sheet call site is at lines 624-638

### Step 1: Update Fallback LaunchedEffect with fitness check

Replace the `LaunchedEffect(isAnalysing)` block (lines 414-419):

Find:
```kotlin
            LaunchedEffect(isAnalysing) {
                if (isAnalysing && !showTypeSheet) {
                    delay(280)
                    showTypeSheet = true
                }
            }
```

Replace with:
```kotlin
            LaunchedEffect(isAnalysing) {
                if (isAnalysing && !showTypeSheet) {
                    delay(280)
                    // Run fitness check against existing renderer types
                    val vault = viewModel.primaryVault()
                    val renderersDir = vault?.let { File(it.localPath, ".vela/renderers") }
                    val existingTypes = renderersDir?.listFiles()
                        ?.filter { it.isDirectory && File(it, "renderer.html").exists() }
                        ?.map { it.name }
                        ?: emptyList()
                    val fitness = viewModel.fitnessCheck(contentType, itemContent.take(400), existingTypes)
                    if (fitness.confidence >= 0.7f && fitness.match != null) {
                        val rendererFile = vault?.let {
                            File(it.localPath, ".vela/renderers/${fitness.match}/renderer.html")
                        }
                        if (rendererFile?.exists() == true) {
                            rendererState = RendererState.Ready(rendererFile)
                            isAnalysing = false
                            return@LaunchedEffect
                        }
                    }
                    showTypeSheet = true
                }
            }
```

Note: This requires `import java.io.File` which is already imported at line 99.

### Step 2: Redesign RendererFeedbackSheet

Replace the entire `RendererFeedbackSheet` composable (lines 793-865) with:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RendererFeedbackSheet(
    viewModel: MiniAppViewModel,
    contentType: String,
    rendererType: com.vela.app.ai.RendererType,
    onDismiss: () -> Unit,
    onApplyFeedback: (String) -> Unit,
    onStartFresh: () -> Unit,
) {
    var suggestions      by remember { mutableStateOf<List<String>?>(null) }
    var selectedFeedback by remember { mutableStateOf<String?>(null) }
    var customText       by remember { mutableStateOf("") }

    LaunchedEffect(contentType, rendererType) {
        suggestions = viewModel.suggestFeedback(contentType, rendererType)
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            "How would you improve it?",
            style    = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )

        if (suggestions == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp))
            Spacer(Modifier.height(16.dp))
        } else {
            suggestions!!.forEach { suggestion ->
                val selected = selectedFeedback == suggestion
                ListItem(
                    headlineContent = { Text(suggestion) },
                    modifier        = Modifier.clickable {
                        selectedFeedback = if (selected) null else suggestion
                        if (!selected) customText = ""
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = if (selected)
                            MaterialTheme.colorScheme.secondaryContainer
                        else
                            MaterialTheme.colorScheme.surface,
                    ),
                )
            }
        }

        // Custom text input
        OutlinedTextField(
            value         = customText,
            onValueChange = { customText = it; selectedFeedback = null },
            placeholder   = { Text("Or describe what you'd like\u2026") },
            modifier      = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            maxLines      = 3,
        )

        // Start fresh row
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        ListItem(
            headlineContent   = { Text("Start fresh") },
            supportingContent = { Text("Choose a different renderer type") },
            leadingContent    = { Icon(Icons.Default.Refresh, contentDescription = null) },
            modifier          = Modifier.clickable { onStartFresh() },
        )

        // Actions
        Row(
            modifier              = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            TextButton(onClick = onDismiss) { Text("Cancel") }
            val feedback = customText.ifBlank { selectedFeedback ?: "" }
            Button(
                onClick  = { if (feedback.isNotBlank()) onApplyFeedback(feedback) },
                enabled  = feedback.isNotBlank(),
            ) { Text("Apply feedback") }
        }
        Spacer(Modifier.height(32.dp))
    }
}
```

### Step 3: Update RendererFeedbackSheet call site

Find the existing call site (lines 624-638):
```kotlin
            if (showFeedbackSheet && rendererState is RendererState.Ready) {
                RendererFeedbackSheet(
                    viewModel       = viewModel,
                    contentType     = contentType,
                    rendererType    = com.vela.app.ai.RendererType.READER,
                    onDismiss       = { showFeedbackSheet = false },
                    onApplyFeedback = { feedback ->
                        showFeedbackSheet = false
                        rendererState = RendererState.Building(
                            rendererType = com.vela.app.ai.RendererType.READER,
                            feedback     = feedback,
                        )
                    },
                )
            }
```

Replace with:
```kotlin
            if (showFeedbackSheet && rendererState is RendererState.Ready) {
                val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
                RendererFeedbackSheet(
                    viewModel       = viewModel,
                    contentType     = contentType,
                    rendererType    = com.vela.app.ai.RendererType.READER,
                    onDismiss       = { showFeedbackSheet = false },
                    onApplyFeedback = { feedback ->
                        showFeedbackSheet = false
                        rendererState = RendererState.Building(
                            rendererType = com.vela.app.ai.RendererType.READER,
                            feedback     = feedback,
                        )
                    },
                    onStartFresh    = {
                        showFeedbackSheet = false
                        scope.launch {
                            try {
                                val port = viewModel.serverPort.value
                                val conn = java.net.URL("http://localhost:$port/miniapps/$contentType")
                                    .openConnection() as java.net.HttpURLConnection
                                conn.requestMethod = "DELETE"
                                conn.connect()
                                conn.responseCode
                                conn.disconnect()
                            } catch (e: Exception) {
                                Log.w("MiniApp", "DELETE failed: ${e.message}")
                            }
                        }
                        rendererState = RendererState.Fallback(contentType, itemContent)
                    },
                )
            }
```

Note: The `scope.launch` approach above will leak — better to use `rememberCoroutineScope()`. Adjust: add `val scope = rememberCoroutineScope()` before the feedback sheet conditional, and use that scope instead of creating a new one.

### Step 4: Verify compilation

Run:
```bash
cd /Users/ken/workspace/vela && ./gradlew :app:compileDebugKotlin 2>&1 | tail -5
```
Expected: `BUILD SUCCESSFUL`

### Step 5: Commit
```bash
git add app/src/main/kotlin/com/vela/app/ui/miniapp/MiniAppRuntime.kt && git commit -m "feat: fitness check FAB flow + redesigned feedback sheet with Start Fresh"
```

---

## Task 10: Clear stale renderers on first launch

**Files:**
- Create: `app/src/main/kotlin/com/vela/app/server/VelaMiniAppCleaner.kt`
- Modify: `app/src/main/kotlin/com/vela/app/VelaApplication.kt`

### Step 1: Create VelaMiniAppCleaner

Create `app/src/main/kotlin/com/vela/app/server/VelaMiniAppCleaner.kt`:

```kotlin
package com.vela.app.server

import android.content.Context
import com.vela.app.vault.VaultRegistry
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VelaMiniAppCleaner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vaultRegistry: VaultRegistry,
) {
    private val prefs = context.getSharedPreferences("amplifier_prefs", 0)

    fun clearStaleRenderersIfNeeded() {
        if (prefs.getBoolean("renderers_v2_cleared", false)) return
        vaultRegistry.enabledVaults.value.forEach { vault ->
            val renderersDir = File(vault.localPath, ".vela/renderers")
            renderersDir.listFiles()?.forEach { typeDir ->
                File(typeDir, "renderer.html").delete()
            }
        }
        prefs.edit().putBoolean("renderers_v2_cleared", true).apply()
    }
}
```

### Step 2: Wire cleaner in VelaApplication.kt

Add import after existing server import:
```kotlin
import com.vela.app.server.VelaMiniAppCleaner
```

Add field injection after `miniAppServer`:
```kotlin
    @Inject
    lateinit var miniAppCleaner: VelaMiniAppCleaner
```

Add to `onCreate()`, **before** `miniAppServer.start()`:
```kotlin
        miniAppCleaner.clearStaleRenderersIfNeeded()
```

The final `onCreate()` body should be:
```kotlin
    override fun onCreate() {
        super.onCreate()
        profileWorkerScheduler.schedule()
        miniAppCleaner.clearStaleRenderersIfNeeded()
        miniAppServer.start()
    }
```

### Step 3: Verify compilation

Run:
```bash
cd /Users/ken/workspace/vela && ./gradlew :app:assembleDebug 2>&1 | tail -5
```
Expected: `BUILD SUCCESSFUL`

### Step 4: Commit
```bash
git add app/src/main/kotlin/com/vela/app/server/VelaMiniAppCleaner.kt app/src/main/kotlin/com/vela/app/VelaApplication.kt && git commit -m "feat: clear stale renderers on first launch after bridge migration"
```

---

## Summary of All Files

### New files (3):
| File | Purpose |
|---|---|
| `app/src/main/kotlin/com/vela/app/server/VelaMiniAppServer.kt` | Ktor server lifecycle — start/stop/restart |
| `app/src/main/kotlin/com/vela/app/server/VelaMiniAppRoutes.kt` | All REST route handlers + markdown parser |
| `app/src/main/kotlin/com/vela/app/server/VelaMiniAppCleaner.kt` | One-time migration: delete old-bridge renderers |

### Modified files (5):
| File | Change |
|---|---|
| `app/build.gradle.kts` | Add ktor-server-core, ktor-server-cio, commonmark deps |
| `app/src/.../VelaApplication.kt` | Inject + start server, inject + run cleaner |
| `app/src/.../ui/miniapp/MiniAppRuntime.kt` | HTTP WebView loading, fetch shim, fitness check, feedback redesign |
| `app/src/.../ai/RendererGenerator.kt` | Update LLM prompt with fetch-based `window.vela.*` API |
| `app/src/.../ui/settings/SettingsViewModel.kt` + `SettingsScreen.kt` | LAN toggle |

## Deferred

- `/api/db/query` and `/api/db/mutate` endpoints (requires `@RawQuery` DAO method)
- SSE streaming for `/api/ai/complete`
- Skills system (separate design session)
- Auth/rate-limiting for LAN mode
