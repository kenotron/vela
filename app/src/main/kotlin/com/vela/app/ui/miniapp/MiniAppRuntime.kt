package com.vela.app.ui.miniapp

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vela.app.ai.AmplifierSession
import com.vela.app.data.db.MiniAppRegistryEntity
import com.vela.app.data.repository.CapabilitiesGraphRepository
import com.vela.app.data.repository.MiniAppDocumentStore
import com.vela.app.events.EventBus
import com.vela.app.vault.VaultManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject

// ────────────────────────────────────────────────────────────────────────────
// Shared data class — also used by RendererGenerator (Task 6)
// ────────────────────────────────────────────────────────────────────────────

/**
 * Represents the current app theme for injection into `__VELA_CONTEXT__` and for
 * LLM prompt assembly in [RendererGenerator]. Passed as a value — not a Compose state.
 */
data class VelaTheme(
    val isDark: Boolean,
    /** RGB hex colour, e.g. `"#7C4DFF"`. */
    val primaryColor: String,
)

// ────────────────────────────────────────────────────────────────────────────
// ViewModel — dependency carrier, one instance per screen destination
// ────────────────────────────────────────────────────────────────────────────

@HiltViewModel
class MiniAppViewModel @Inject constructor(
    internal val documentStore: MiniAppDocumentStore,
    internal val eventBus: EventBus,
    internal val amplifierSession: AmplifierSession,
    internal val vaultManager: VaultManager,
    private val capabilitiesRepo: CapabilitiesGraphRepository,
) : ViewModel() {

    /** Live capabilities graph — drives `__VELA_CONTEXT__.capabilities` updates. */
    val capabilities: StateFlow<List<MiniAppRegistryEntity>> =
        capabilitiesRepo.getAll()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Factory — creates one [VelaJSInterface] per (itemPath, contentType) pair. */
    fun createJsInterface(itemPath: String, contentType: String): VelaJSInterface =
        VelaJSInterface(
            documentStore    = documentStore,
            eventBus         = eventBus,
            amplifierSession = amplifierSession,
            vaultManager     = vaultManager,
            itemScopePath    = itemPath,
            contentType      = contentType,
        )

    /**
     * Returns the cached renderer HTML [File], or `null` if generation has not
     * happened yet (triggers fallback placeholder UI in [MiniAppContainer]).
     */
    fun getRendererFile(contentType: String): File? =
        vaultManager.resolve(".vela/renderers/$contentType/renderer.html")
            ?.takeIf { it.exists() }
}

// ────────────────────────────────────────────────────────────────────────────
// Composable
// ────────────────────────────────────────────────────────────────────────────

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
    viewModel: MiniAppViewModel = hiltViewModel(),
) {
    // ── Reactive state ──────────────────────────────────────────────
    val capabilities by viewModel.capabilities.collectAsState()
    val isDark       = isSystemInDarkTheme()
    val primaryArgb  = MaterialTheme.colorScheme.primary.toArgb()
    val primaryHex   = "#%06X".format(primaryArgb and 0xFFFFFF)
    val theme        = VelaTheme(isDark = isDark, primaryColor = primaryHex)

    val contextJson = remember(itemPath, itemContent, contentType, capabilities, isDark, primaryHex, layout) {
        buildContextJson(itemPath, itemContent, contentType, capabilities, theme, layout)
    }

    // ── JS interface — stable for the composable's lifetime ─────────
    val jsInterface = remember(itemPath, contentType) {
        viewModel.createJsInterface(itemPath, contentType)
    }

    DisposableEffect(jsInterface) {
        onDispose { jsInterface.cancelAllSubscriptions() }
    }

    // ── WebView ──────────────────────────────────────────────────────
    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                // Wire evaluateJavascript callback back to this WebView
                jsInterface.onEvaluateJs = { js ->
                    post { evaluateJavascript(js, null) }
                }

                // Register the four SDK namespaces
                addJavascriptInterface(jsInterface.db,     "__vela_db")
                addJavascriptInterface(jsInterface.events, "__vela_events")
                addJavascriptInterface(jsInterface.ai,     "__vela_ai")
                addJavascriptInterface(jsInterface.vault,  "__vela_vault")

                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        // Assemble window.vela namespace + inject __VELA_CONTEXT__
                        val script = buildString {
                            append("(function(){")
                            append("window.vela={")
                            append("db:window.__vela_db,")
                            append("events:window.__vela_events,")
                            append("ai:window.__vela_ai,")
                            append("vault:window.__vela_vault")
                            append("};")
                            append("window.__VELA_CONTEXT__=")
                            append(contextJson)
                            append(";")
                            append("if(typeof window.onVelaReady==='function')window.onVelaReady();")
                            append("})();")
                        }
                        view.evaluateJavascript(script, null)
                    }
                }

                // Load renderer if it exists, otherwise show the loading placeholder
                val rendererFile = viewModel.getRendererFile(contentType)
                if (rendererFile != null) {
                    loadUrl("file://${rendererFile.absolutePath}")
                } else {
                    loadData(LOADING_PLACEHOLDER_HTML, "text/html", "UTF-8")
                }
            }
        },
        update = { webView ->
            // Recomposition — push the latest __VELA_CONTEXT__ (e.g. after theme change
            // or after a new mini app joins the capabilities graph)
            webView.post {
                webView.evaluateJavascript("window.__VELA_CONTEXT__=$contextJson;", null)
            }
        },
        modifier = modifier.fillMaxSize(),
    )
}

// ────────────────────────────────────────────────────────────────────────────
// Private helpers
// ────────────────────────────────────────────────────────────────────────────

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
