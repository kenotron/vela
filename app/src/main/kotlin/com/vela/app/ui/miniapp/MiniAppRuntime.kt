package com.vela.app.ui.miniapp

import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.rotate
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vela.app.ai.AmplifierSession
import com.vela.app.ai.GenerationResult
import com.vela.app.ai.RendererGenerator
import com.vela.app.data.db.MiniAppRegistryEntity
import com.vela.app.data.repository.CapabilitiesGraphRepository
import com.vela.app.data.repository.MiniAppDocumentStore
import com.vela.app.events.EventBus
import com.vela.app.ui.components.MarkdownText
import com.vela.app.server.VelaMiniAppServer
import com.vela.app.vault.VaultManager
import com.vela.app.vault.VaultRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject

// ────────────────────────────────────────────────────────────────────────────────
// Shared data class — also used by RendererGenerator (Task 6)
// ────────────────────────────────────────────────────────────────────────────────

/**
 * Represents the current app theme for injection into `__VELA_CONTEXT__` and for
 * LLM prompt assembly in [RendererGenerator]. Passed as a value — not a Compose state.
 */
data class VelaTheme(
    val isDark: Boolean,
    /** RGB hex colour, e.g. `"#7C4DFF"`. */
    val primaryColor: String,
)

// ────────────────────────────────────────────────────────────────────────────────
// RendererState — drives what MiniAppContainer shows
// ────────────────────────────────────────────────────────────────────────────────

/**
 * A context-aware suggestion for what kind of mini app to build.
 * Generated by [MiniAppViewModel.suggestRendererTypes] using the file content.
 */
data class RendererSuggestion(
    val type: com.vela.app.ai.RendererType,
    /** Short, content-specific label, e.g. "Step-by-step guide" for a recipe. */
    val label: String,
    /** One sentence specific to this content, e.g. "Check off ingredients as you cook." */
    val description: String,
)

/**
 * Tracks the renderer lifecycle inside [MiniAppContainer].
 *
 * - [Loading]      — reserved; not used as the initial state after the blank-screen fix.
 * - [Ready]        — renderer HTML exists on disk; WebView loads it.
 * - [Fallback]     — no cached renderer yet; show a native Compose viewer + "Generate" FAB.
 * - [Building]     — renderer generation is in progress; show full-screen build log.
 * - [BuildFailed]  — generation failed; show error screen with retry option.
 */
sealed class RendererState {
    object Loading : RendererState()
    data class Fallback(val contentType: String, val content: String) : RendererState()
    data class Building(
        val rendererType: com.vela.app.ai.RendererType,
        val feedback: String? = null,
    ) : RendererState()
    data class Ready(val rendererFile: File) : RendererState()
    data class BuildFailed(
        val rendererType: com.vela.app.ai.RendererType,
        val cause: Throwable,
    ) : RendererState()
}

// ────────────────────────────────────────────────────────────────────────────────
// ViewModel — dependency carrier, one instance per screen destination
// ────────────────────────────────────────────────────────────────────────────────

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

    /** Live capabilities graph — drives `__VELA_CONTEXT__.capabilities` updates. */
    val capabilities: StateFlow<List<MiniAppRegistryEntity>> =
        capabilitiesRepo.getAll()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _buildLog = MutableStateFlow("")
    val buildLog: StateFlow<String> = _buildLog.asStateFlow()

    private val _buildActivity = MutableStateFlow("Starting…")
    val buildActivity: StateFlow<String> = _buildActivity.asStateFlow()

    val serverPort: StateFlow<Int> = server.port
    val serverReady: StateFlow<Boolean> = server.isReady

    /**
     * Returns the cached renderer HTML [File], or `null` if generation has not
     * happened yet (triggers fallback placeholder UI in [MiniAppContainer]).
     */
    /** Returns the cached renderer HTML file for [contentType], or null if none exists.
     *  Resolves via VaultRegistry (not VaultManager.resolve which needs InferenceEngine session paths). */
    fun getRendererFile(contentType: String): File? {
        val vault = vaultRegistry.enabledVaults.value.firstOrNull() ?: return null
        return File(vault.localPath, ".vela/renderers/$contentType/renderer.html").takeIf { it.exists() }
    }

    fun primaryVault() = vaultRegistry.enabledVaults.value.firstOrNull()

    /**
     * Starts async renderer generation for [rendererType].
     * Streams tokens into [buildLog] as they arrive.
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
        _buildLog.value = ""
        _buildActivity.value = "Preparing…"
        var tokensSeen = 0
        val existingHtml = if (feedback != null) getRendererFile(contentType)?.readText() else null
        return rendererGenerator.generateRenderer(
            itemPath     = itemPath,
            itemContent  = itemContent,
            contentType  = contentType,
            theme        = theme,
            layout       = layout,
            rendererType = rendererType,
            feedback     = feedback,
            existingHtml = existingHtml,
            onToken      = { token ->
                _buildLog.update { it + token }
                tokensSeen++
                when (tokensSeen) {
                    1    -> _buildActivity.value = if (feedback != null) "Applying feedback…" else "Generating renderer…"
                    200  -> _buildActivity.value = "Writing HTML structure…"
                    800  -> _buildActivity.value = "Adding styles and scripts…"
                    2000 -> _buildActivity.value = "Finalising…"
                }
            },
            onActivity   = { activity -> _buildActivity.value = activity },
        )
    }

    /**
     * Uses [amplifierSession] to generate context-aware mini app suggestions for [itemContent].
     * Falls back to static [RendererType] entries if the LLM call fails or returns unparseable output.
     */
    suspend fun suggestRendererTypes(
        itemContent: String,
        contentType: String,
    ): List<RendererSuggestion> {
        val fallback = com.vela.app.ai.RendererType.entries.map { type ->
            RendererSuggestion(
                type        = type,
                label       = type.label,
                description = type.promptStyle.take(70) + "…",
            )
        }
        return try {
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

            // Strip possible markdown fences
            val raw = sb.toString().trim()
                .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                RendererSuggestion(
                    type        = com.vela.app.ai.RendererType.valueOf(obj.getString("type")),
                    label       = obj.getString("label"),
                    description = obj.getString("description"),
                )
            }.takeIf { it.size == 3 } ?: fallback
        } catch (e: Exception) {
            Log.d("MiniAppViewModel", "suggestRendererTypes fallback: ${e.message}")
            fallback
        }
    }

    /**
     * Generates 2 short feedback suggestions for the user to improve the current mini app.
     * Falls back to generic suggestions if the LLM call fails.
     */
    suspend fun suggestFeedback(
        contentType: String,
        rendererType: com.vela.app.ai.RendererType,
    ): List<String> {
        val fallback = when (rendererType) {
            com.vela.app.ai.RendererType.READER      -> listOf("Make it more visual", "Add a summary section")
            com.vela.app.ai.RendererType.INTERACTIVE -> listOf("Add more checkboxes", "Include a progress tracker")
            com.vela.app.ai.RendererType.DASHBOARD   -> listOf("Show more metrics", "Add visual charts")
        }
        return try {
            val prompt = """
For a $contentType mini app built as a ${rendererType.label.lowercase()} renderer,
suggest exactly 2 short improvement ideas (10 words max each).
Return ONLY a JSON array of 2 strings: ["idea 1", "idea 2"]
            """.trimIndent()
            val sb = StringBuilder()
            amplifierSession.runTurn(
                historyJson       = "[]",
                userInput         = prompt,
                userContentJson   = null,
                systemPrompt      = "Return ONLY valid JSON. No explanation.",
                onToolStart       = { _, _ -> "" },
                onToolEnd         = { _, _ -> },
                onToken           = { token -> sb.append(token) },
                onProviderRequest = { null },
                onServerTool      = { _, _ -> },
            )
            val raw = sb.toString().trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val arr = org.json.JSONArray(raw)
            listOf(arr.getString(0), arr.getString(1)).takeIf { it.size == 2 } ?: fallback
        } catch (e: Exception) {
            fallback
        }
    }

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
            val json = org.json.JSONObject(raw)
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
}

// ────────────────────────────────────────────────────────────────────────────────
// Composable
// ────────────────────────────────────────────────────────────────────────────────

/**
 * Full-screen mini app host. Owns the [WebView] lifecycle, injects the Vela SDK,
 * and keeps `window.__VELA_CONTEXT__` current as theme or capabilities change.
 *
 * The file is named `MiniAppRuntime.kt`; the exported composable is `MiniAppContainer`
 * (per the design doc §5 naming convention).
 *
 * @param itemPath      Vault-relative path to the item being rendered, e.g. `"recipes/carbonara.md"`.
 * @param itemContent   Raw text content of the vault item.
 * @param contentType   Renderer content type key, e.g. `"recipe"`.
 * @param layout        `"phone"` or `"tablet"` — injected into `__VELA_CONTEXT__`.
 */
@Composable
fun MiniAppContainer(
    itemPath: String,
    itemContent: String,
    contentType: String,
    layout: String,
    modifier: Modifier = Modifier,
    initialBuildType: com.vela.app.ai.RendererType? = null,
    forceMarkdown: Boolean = false,
    viewModel: MiniAppViewModel = hiltViewModel(),
) {
    // ── Reactive state ────────────────────────────────────────────────────────
    val capabilities by viewModel.capabilities.collectAsState()
    val isDark       = isSystemInDarkTheme()
    val primaryArgb  = MaterialTheme.colorScheme.primary.toArgb()
    val primaryHex   = "#%06X".format(primaryArgb and 0xFFFFFF)
    val theme        = VelaTheme(isDark = isDark, primaryColor = primaryHex)

    val contextJson = remember(itemPath, itemContent, contentType, capabilities, isDark, primaryHex, layout) {
        buildContextJson(itemPath, itemContent, contentType, capabilities, theme, layout)
    }

    // ── RendererState — drives which UI branch is shown ───────────────────────
    // Default: if a cached renderer exists → Ready; otherwise → Fallback (show
    // native content immediately, no LLM call). Generation is user-initiated only.
    var rendererState by remember(initialBuildType, forceMarkdown) {
        mutableStateOf<RendererState>(
            when {
                initialBuildType != null -> RendererState.Building(initialBuildType)
                forceMarkdown           -> RendererState.Fallback(contentType, itemContent)
                else -> viewModel.getRendererFile(contentType)
                            ?.let { RendererState.Ready(it) }
                            ?: RendererState.Fallback(contentType, itemContent)
            }
        )
    }

    // ── Branch on state ───────────────────────────────────────────────────────
    when (val s = rendererState) {

        // ── Fallback: native content + icon-only FAB ──────────────────────────
        is RendererState.Fallback -> {
            var showTypeSheet by remember { mutableStateOf(false) }
            var isAnalysing   by remember { mutableStateOf(false) }

            // Delay sheet opening so user sees the FAB spin for a beat before the modal covers it
            LaunchedEffect(isAnalysing) {
                if (isAnalysing && !showTypeSheet) {
                    delay(280)
                    // Run fitness check against existing renderer types
                    val vault = viewModel.primaryVault()
                    val renderersDir = vault?.let { java.io.File(it.localPath, ".vela/renderers") }
                    val existingTypes = renderersDir
                        ?.listFiles()
                        ?.filter { it.isDirectory && java.io.File(it, "renderer.html").exists() }
                        ?.map { it.name }
                        ?: emptyList()
                    val fitness = viewModel.fitnessCheck(contentType, itemContent.take(400), existingTypes)
                    if (fitness.confidence >= 0.7f && fitness.match != null) {
                        val rendererFile = vault?.let {
                            java.io.File(it.localPath, ".vela/renderers/${fitness.match}/renderer.html")
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

            // Continuous rotation for the ✨ icon while analysing
            val infiniteTransition = rememberInfiniteTransition(label = "fab-spin")
            val fabRotation by infiniteTransition.animateFloat(
                initialValue  = 0f,
                targetValue   = 360f,
                animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
                label         = "fab-rotation",
            )

            Box(modifier) {
                FallbackRenderer(
                    contentType = s.contentType,
                    content     = s.content,
                    modifier    = Modifier.fillMaxSize(),
                )
                FloatingActionButton(
                    onClick        = { if (!isAnalysing) isAnalysing = true },
                    modifier       = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp),
                    containerColor = if (isAnalysing)
                        MaterialTheme.colorScheme.tertiaryContainer
                    else
                        MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Icon(
                        imageVector        = Icons.Default.AutoAwesome,
                        contentDescription = "Generate mini app",
                        modifier           = if (isAnalysing)
                            Modifier.rotate(fabRotation)
                        else
                            Modifier,
                    )
                }
            }

            if (showTypeSheet) {
                RendererTypeSheet(
                    viewModel          = viewModel,
                    itemContent        = itemContent,
                    contentType        = contentType,
                    onDismiss          = { showTypeSheet = false; isAnalysing = false },
                    onSelect           = { type ->
                        showTypeSheet = false
                        isAnalysing   = false
                        rendererState = RendererState.Building(type)
                    },
                    onOpenExisting     = {
                        showTypeSheet = false
                        isAnalysing   = false
                        viewModel.getRendererFile(contentType)?.let { file ->
                            rendererState = RendererState.Ready(file)
                        }
                    },
                    onSuggestionsReady = { isAnalysing = false },
                )
            }
        }

        // ── Building: full-screen build log ───────────────────────────────────
        is RendererState.Building -> {
            val buildLog      by viewModel.buildLog.collectAsState()
            val buildActivity by viewModel.buildActivity.collectAsState()

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
                rendererType  = s.rendererType,
                contentType   = contentType,
                buildLog      = buildLog,
                buildActivity = buildActivity,
                onCancel      = { rendererState = RendererState.Fallback(contentType, itemContent) },
                modifier      = modifier,
            )
        }

        // ── BuildFailed: error + retry ─────────────────────────────────────────
        is RendererState.BuildFailed -> {
            RendererBuildFailedScreen(
                rendererType = s.rendererType,
                cause        = s.cause,
                onRetry      = { rendererState = RendererState.Building(s.rendererType) },
                onViewAsText = { rendererState = RendererState.Fallback(contentType, itemContent) },
                modifier     = modifier,
            )
        }

        // ── Ready / Loading: WebView ───────────────────────────────────────────
        else -> {
            var showFeedbackSheet by remember { mutableStateOf(false) }
            val serverReady by viewModel.serverReady.collectAsState()

            if (!serverReady) {
                Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
            Box(modifier) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true

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

                            val port = viewModel.serverPort.value
                            tag = "http://localhost:$port/miniapps/$contentType"
                            loadUrl("http://localhost:$port/miniapps/$contentType")
                        }
                    },
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
                    modifier = Modifier.fillMaxSize(),
                )

                // Only show feedback FAB when renderer is ready (not loading)
                if (rendererState is RendererState.Ready) {
                    FloatingActionButton(
                        onClick  = { showFeedbackSheet = true },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp),
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = "Improve mini app")
                    }
                }
            }
            } // end of serverReady else-branch

            val feedbackScope = rememberCoroutineScope()
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
                    onStartFresh    = {
                        showFeedbackSheet = false
                        feedbackScope.launch {
                            try {
                                val port = viewModel.serverPort.value
                                val conn = java.net.URL("http://localhost:$port/miniapps/$contentType")
                                    .openConnection() as java.net.HttpURLConnection
                                conn.requestMethod = "DELETE"
                                conn.connect()
                                conn.responseCode
                                conn.disconnect()
                            } catch (e: Exception) {
                                android.util.Log.w("MiniApp", "DELETE renderer failed: ${e.message}")
                            }
                        }
                        rendererState = RendererState.Fallback(contentType, itemContent)
                    },
                )
            }
        }
    }
}

// ────────────────────────────────────────────────────────────────────────────────
// FallbackRenderer — always renders something when WebView generation fails
// ────────────────────────────────────────────────────────────────────────────────

/**
 * Native Compose fallback shown when no cached renderer exists (or generation fails).
 * Dispatches to content-type-appropriate viewers so there is never a blank screen.
 */
@Composable
private fun FallbackRenderer(
    contentType: String,
    content: String,
    modifier: Modifier = Modifier,
) {
    when (contentType) {
        "markdown" -> Box(modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            MarkdownText(text = content, color = MaterialTheme.colorScheme.onSurface)
        }
        else -> Box(modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(text = content, style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ────────────────────────────────────────────────────────────────────────────────
// RendererTypeSheet — bottom sheet for selecting renderer type
// ────────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RendererTypeSheet(
    viewModel: MiniAppViewModel,
    itemContent: String,
    contentType: String,
    onDismiss: () -> Unit,
    onSelect: (com.vela.app.ai.RendererType) -> Unit,
    onOpenExisting: () -> Unit,
    onSuggestionsReady: () -> Unit = {},
) {
    var suggestions  by remember { mutableStateOf<List<RendererSuggestion>?>(null) }
    var existingFile by remember { mutableStateOf<java.io.File?>(null) }
    var rerollKey    by remember { mutableStateOf(0) }

    LaunchedEffect(contentType, rerollKey) {
        suggestions = null          // reset — triggers loading skeleton
        delay(50)                   // one frame: guarantee skeleton renders before new results arrive
        existingFile = viewModel.getRendererFile(contentType)
        suggestions  = viewModel.suggestRendererTypes(itemContent, contentType)
        onSuggestionsReady()        // signal FAB to stop spinning
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(
            modifier              = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("What kind of mini app?", style = MaterialTheme.typography.titleMedium)
            if (suggestions != null) {
                IconButton(onClick = { rerollKey++ }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Try different ideas")
                }
            }
        }

        if (suggestions == null) {
            // Loading state — better than just a spinner
            Column(
                modifier            = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Analysing your $contentType…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                // Shimmer placeholders
                repeat(3) {
                    Surface(
                        shape    = MaterialTheme.shapes.medium,
                        color    = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp),
                    ) {}
                }
            }
        } else {
            // Existing app row (only shown if a renderer file exists)
            existingFile?.let {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clickable { onOpenExisting() },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                ) {
                    ListItem(
                        headlineContent   = { Text("Open existing mini app") },
                        supportingContent = { Text("View the $contentType app you already built.") },
                        leadingContent    = {
                            Icon(
                                Icons.Default.OpenInNew,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        },
                        colors = ListItemDefaults.colors(
                            containerColor = androidx.compose.ui.graphics.Color.Transparent,
                        ),
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }

            suggestions!!.forEach { suggestion ->
                ListItem(
                    headlineContent   = { Text(suggestion.label) },
                    supportingContent = { Text(suggestion.description) },
                    leadingContent    = {
                        Icon(
                            imageVector = when (suggestion.type) {
                                com.vela.app.ai.RendererType.READER      -> Icons.Default.MenuBook
                                com.vela.app.ai.RendererType.INTERACTIVE -> Icons.Default.TouchApp
                                com.vela.app.ai.RendererType.DASHBOARD   -> Icons.Default.Dashboard
                            },
                            contentDescription = null,
                        )
                    },
                    modifier = Modifier.clickable { onSelect(suggestion.type) },
                )
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}

// ────────────────────────────────────────────────────────────────────────────────────────────
// RendererFeedbackSheet — post-generation improvement sheet
// ────────────────────────────────────────────────────────────────────────────────────────────

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

        OutlinedTextField(
            value         = customText,
            onValueChange = { customText = it; selectedFeedback = null },
            placeholder   = { Text("Or describe what you'd like\u2026") },
            modifier      = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            maxLines      = 3,
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        ListItem(
            headlineContent   = { Text("Start fresh") },
            supportingContent = { Text("Choose a different renderer type") },
            leadingContent    = { Icon(Icons.Default.Refresh, contentDescription = null) },
            modifier          = Modifier.clickable { onStartFresh() },
        )

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

// ────────────────────────────────────────────────────────────────────────────────
// RendererBuildScreen — full-screen streaming build log
// ────────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RendererBuildScreen(
    rendererType: com.vela.app.ai.RendererType,
    contentType: String,
    buildLog: String,
    buildActivity: String,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState  = rememberLazyListState()
    val tokenCount = buildLog.length

    // Auto-scroll to bottom as tokens arrive
    LaunchedEffect(tokenCount) {
        if (tokenCount > 0) listState.animateScrollToItem(0)
    }

    Column(modifier.fillMaxSize()) {
        // Header
        TopAppBar(
            title = {
                Column {
                    Text("Building ${rendererType.label}", style = MaterialTheme.typography.titleMedium)
                    Text(
                        buildActivity,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            navigationIcon = {
                IconButton(onClick = onCancel) {
                    Icon(Icons.Default.Close, contentDescription = "Cancel")
                }
            },
        )
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

        // Live build log (newest lines at bottom; rendered reversed in a reversed LazyColumn)
        val lines = buildLog.takeLast(4_000).lines()
        LazyColumn(
            state          = listState,
            reverseLayout  = true,
            modifier       = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            items(lines.reversed()) { line ->
                Text(
                    text  = line,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    ),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                )
            }
        }

        // Footer
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            Text(
                "$tokenCount chars",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ────────────────────────────────────────────────────────────────────────────────
// RendererBuildFailedScreen — error screen with retry / view-as-text
// ────────────────────────────────────────────────────────────────────────────────

@Composable
private fun RendererBuildFailedScreen(
    rendererType: com.vela.app.ai.RendererType,
    cause: Throwable,
    onRetry: () -> Unit,
    onViewAsText: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier            = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Default.ErrorOutline,
            contentDescription = null,
            tint     = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text("Build failed", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            cause.message ?: "Unknown error",
            style     = MaterialTheme.typography.bodySmall,
            color     = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRetry) { Text("Try again") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onViewAsText) { Text("View as text") }
    }
}

// ────────────────────────────────────────────────────────────────────────────────
// Private helpers
// ────────────────────────────────────────────────────────────────────────────────

/**
 * Serialises all inputs into the `__VELA_CONTEXT__` JSON object that is injected
 * into every renderer after page load (design doc §2).
 *
 * `globalCollections` is an empty object for now — RendererGenerator populates it
 * with actual global/type snapshots at generation time (Task 6). Keeping it as `{}`
 * here keeps the runtime injection fast and avoids an additional DB round-trip on
 * every page load.
 */
private fun buildContextJson(
    itemPath: String,
    itemContent: String,
    contentType: String,
    capabilities: List<MiniAppRegistryEntity>,
    theme: VelaTheme,
    layout: String,
): String {
    val capsArray = JSONArray()
    capabilities.forEach { entity ->
        capsArray.put(JSONObject().apply {
            put("type", entity.contentType)
            put("provides", runCatching { JSONArray(entity.provides) }.getOrElse { JSONArray() })
            put("consumes", runCatching { JSONArray(entity.consumes) }.getOrElse { JSONArray() })
        })
    }
    return JSONObject().apply {
        put("itemPath",          itemPath)
        put("itemContent",       itemContent)
        put("contentType",       contentType)
        put("capabilities",      capsArray)
        put("globalCollections", JSONObject())
        put("theme", JSONObject().apply {
            put("isDark",       theme.isDark)
            put("primaryColor", theme.primaryColor)
        })
        put("layout", layout)
    }.toString()
}

private const val LOADING_PLACEHOLDER_HTML = """<!DOCTYPE html>
<html lang="en">
<head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<style>
  body{margin:0;display:flex;align-items:center;justify-content:center;
       min-height:100vh;font-family:sans-serif;background:#121212;color:#888;}
  p{font-size:14px;letter-spacing:.5px;}
</style></head>
<body><p>Generating renderer…</p></body>
</html>"""
