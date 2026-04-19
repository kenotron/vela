package com.vela.app.ui.miniapp

import android.webkit.JavascriptInterface
import com.vela.app.ai.AmplifierSession
import com.vela.app.data.repository.MiniAppDocumentStore
import com.vela.app.events.EventBus
import com.vela.app.vault.VaultManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch

/**
 * Exposes the Vela SDK to WebView JavaScript as `window.vela`.
 *
 * Android registers the four inner objects separately (not the outer class):
 * ```
 * webView.addJavascriptInterface(jsInterface.db,     "__vela_db")
 * webView.addJavascriptInterface(jsInterface.events, "__vela_events")
 * webView.addJavascriptInterface(jsInterface.ai,     "__vela_ai")
 * webView.addJavascriptInterface(jsInterface.vault,  "__vela_vault")
 * ```
 * A JS shim injected by [MiniAppContainer] after page load assembles them into
 * `window.vela = { db, events, ai, vault }`.
 *
 * [onEvaluateJs] is set by [MiniAppContainer] to route Kotlin→JS callbacks via
 * `webView.post { webView.evaluateJavascript(js, null) }`.  It is declared `var`
 * so the factory can create the interface before the WebView exists.
 *
 * [cancelAllSubscriptions] tears down all active Room Flow and event collectors —
 * call it in the `DisposableEffect.onDispose` block in [MiniAppContainer].
 */
class VelaJSInterface(
    private val documentStore: MiniAppDocumentStore,
    private val eventBus: EventBus,
    private val amplifierSession: AmplifierSession,
    private val vaultManager: VaultManager,
    /** Absolute path of the currently open vault item — used to scope `local:` collections. */
    private val itemScopePath: String,
    /** Content type of the current renderer — used to scope `type:` collections. */
    private val contentType: String,
) {
    /** Set by [MiniAppContainer] after WebView creation. All inner classes use this for callbacks. */
    var onEvaluateJs: (String) -> Unit = {}

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Active watch/subscribe jobs keyed by a stable string id. Thread-safe. */
    private val subscriptionJobs = ConcurrentHashMap<String, Job>()

    // ─────────────────────────────────────────────────────────────────
    // Inner objects registered with addJavascriptInterface
    // ─────────────────────────────────────────────────────────────────

    val db     = Db()
    val events = Events()
    val ai     = Ai()
    val vault  = Vault()

    inner class Db {
        /**
         * Upsert a document. `collection` must start with `local:`, `global:`, or `type:`.
         * Fire-and-forget from JS — no return value.
         */
        @JavascriptInterface
        fun put(collection: String, id: String, data: String) {
            validateCollection(collection)
            val (prefix, name) = scopeCollection(collection)
            scope.launch { documentStore.put(prefix, name, id, data) }
        }

        /**
         * Synchronous point read — blocks the Binder thread until the DB returns.
         * Acceptable for small document reads; do NOT call on the main thread.
         */
        @JavascriptInterface
        fun get(collection: String, id: String): String? {
            validateCollection(collection)
            val (prefix, name) = scopeCollection(collection)
            var result: String? = null
            val latch = CountDownLatch(1)
            scope.launch {
                result = documentStore.get(prefix, name, id)
                latch.countDown()
            }
            latch.await()
            return result
        }

        @JavascriptInterface
        fun delete(collection: String, id: String) {
            validateCollection(collection)
            val (prefix, name) = scopeCollection(collection)
            scope.launch { documentStore.delete(prefix, name, id) }
        }

        /**
         * Registers a live observer. [callbackName] is the JS function to invoke with
         * the updated array when the collection changes, e.g. `"onShoppingListChanged"`.
         *
         * The previous watch on the same collection (if any) is cancelled before
         * the new one starts — prevents duplicates on React-style re-renders.
         */
        @JavascriptInterface
        fun watch(collection: String, callbackName: String) {
            validateCollection(collection)
            val (prefix, name) = scopeCollection(collection)
            val key = "db:watch:$collection"
            subscriptionJobs[key]?.cancel()
            subscriptionJobs[key] = scope.launch {
                documentStore.watch(prefix, name).collect { entities ->
                    val jsonArray = buildString {
                        append("[")
                        entities.forEachIndexed { i, e ->
                            if (i > 0) append(",")
                            append("""{"id":${escapeJs(e.id)},"data":${e.data},"updatedAt":${e.updatedAt}}""")
                        }
                        append("]")
                    }
                    onEvaluateJs("$callbackName($jsonArray)")
                }
            }
        }
    }

    inner class Events {
        /** Publishes to the in-process [EventBus]. Visible to all subscribed mini apps. */
        @JavascriptInterface
        fun publish(topic: String, payload: String) {
            eventBus.tryPublish(topic, payload)
        }

        /**
         * Registers a subscription. [callbackName] is the JS function invoked with
         * `payload` whenever an event matching [topic] arrives.
         * The subscription runs until [cancelAllSubscriptions] is called.
         */
        @JavascriptInterface
        fun subscribe(topic: String, callbackName: String) {
            val key = "event:$topic:$callbackName"
            subscriptionJobs[key]?.cancel()
            subscriptionJobs[key] = scope.launch {
                eventBus.events.collect { event ->
                    if (event.topic == topic) {
                        onEvaluateJs("$callbackName(${escapeJs(event.payload)})")
                    }
                }
            }
        }
    }

    inner class Ai {
        /**
         * Single-shot LLM call. [callbackName] is invoked with the complete response
         * string when the model finishes: `callbackName(responseText)`.
         * On error: `callbackName(null, errorMessage)`.
         */
        @JavascriptInterface
        fun ask(prompt: String, callbackName: String) {
            val key = "ai:ask:$callbackName"
            subscriptionJobs[key]?.cancel()
            subscriptionJobs[key] = scope.launch {
                val sb = StringBuilder()
                try {
                    amplifierSession.runTurn(
                        historyJson       = "[]",
                        userInput         = prompt,
                        userContentJson   = null,
                        systemPrompt      = "",
                        onToolStart       = { _, _ -> "" },
                        onToolEnd         = { _, _ -> },
                        onToken           = { token -> sb.append(token) },
                        onProviderRequest = { null },
                        onServerTool      = { _, _ -> },
                    )
                    onEvaluateJs("$callbackName(${escapeJs(sb.toString())})")
                } catch (e: Exception) {
                    onEvaluateJs("$callbackName(null,${escapeJs(e.message ?: "Unknown error")})")
                }
            }
        }

        /**
         * Streaming LLM call. [chunkCallbackName] is invoked for each token;
         * [doneCallbackName] is invoked with no arguments on completion or with
         * an error string on failure.
         */
        @JavascriptInterface
        fun stream(prompt: String, chunkCallbackName: String, doneCallbackName: String) {
            val key = "ai:stream:$chunkCallbackName"
            subscriptionJobs[key]?.cancel()
            subscriptionJobs[key] = scope.launch {
                try {
                    amplifierSession.runTurn(
                        historyJson       = "[]",
                        userInput         = prompt,
                        userContentJson   = null,
                        systemPrompt      = "",
                        onToolStart       = { _, _ -> "" },
                        onToolEnd         = { _, _ -> },
                        onToken           = { token ->
                            onEvaluateJs("$chunkCallbackName(${escapeJs(token)})")
                        },
                        onProviderRequest = { null },
                        onServerTool      = { _, _ -> },
                    )
                    eventBus.tryPublish("vela:ai-interrupted", "{}")   // signals completion too
                    onEvaluateJs("$doneCallbackName()")
                } catch (e: Exception) {
                    eventBus.tryPublish("vela:ai-interrupted", "{}")
                    onEvaluateJs("$doneCallbackName(${escapeJs(e.message ?: "stream error")})")
                }
            }
        }
    }

    inner class Vault {
        /**
         * Reads a vault file. [callbackName] is invoked with the file content string.
         * Returns an empty string (not an error) if the file doesn't exist or the path
         * is outside the vault root.
         */
        @JavascriptInterface
        fun read(path: String, callbackName: String) {
            scope.launch {
                val content = runCatching {
                    vaultManager.resolve(path)?.readText() ?: ""
                }.getOrElse { "" }
                onEvaluateJs("$callbackName(${escapeJs(content)})")
            }
        }

        /**
         * Writes a vault file and publishes `vela:vault-changed`.
         * Silently no-ops if [path] resolves outside the vault root.
         */
        @JavascriptInterface
        fun write(path: String, content: String) {
            scope.launch {
                runCatching { vaultManager.resolve(path)?.writeText(content) }
                eventBus.tryPublish("vela:vault-changed", """{"path":${escapeJs(path)}}""")
            }
        }

        /** Lists vault directory entries. [callbackName] receives a JSON string array. */
        @JavascriptInterface
        fun list(path: String, callbackName: String) {
            scope.launch {
                val entries = runCatching {
                    vaultManager.resolve(path)?.listFiles()?.map { it.name } ?: emptyList()
                }.getOrElse { emptyList() }
                val json = entries.joinToString(",", "[", "]") { escapeJs(it) }
                onEvaluateJs("$callbackName($json)")
            }
        }

        /** Signals a vault sync. Publishes `vela:vault-synced` to the event bus. */
        @JavascriptInterface
        fun sync() {
            eventBus.tryPublish("vela:vault-synced", "{}")
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────

    /**
     * Cancel all active Room Flow collectors and event bus subscriptions.
     * Call this from `DisposableEffect.onDispose` in [MiniAppContainer].
     */
    fun cancelAllSubscriptions() {
        subscriptionJobs.values.forEach { it.cancel() }
        subscriptionJobs.clear()
    }

    // ─────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────

    /**
     * Validates that [collection] starts with a recognised scope prefix.
     * Throws [IllegalArgumentException] — surfaces as a JS exception to the mini app.
     */
    private fun validateCollection(collection: String) {
        if (!collection.startsWith("local:") &&
            !collection.startsWith("global:") &&
            !collection.startsWith("type:")
        ) {
            throw IllegalArgumentException(
                "vela.db: collection must start with 'local:', 'global:', or 'type:' — got: $collection"
            )
        }
    }

    /**
     * Splits a prefixed collection string into (scopePrefix, scopedCollectionName).
     *
     * Scoping rules:
     *  - "global:shopping-list-queue" → ("global", "shopping-list-queue")
     *  - "type:recent-ingredients"    → ("type",   "recipe::recent-ingredients")   [uses contentType]
     *  - "local:steps"               → ("local",  "recipes/carbonara.md::steps")   [uses itemScopePath]
     *
     * The scoped collection name is what is stored in [MiniAppDocumentEntity.collection].
     */
    private fun scopeCollection(collection: String): Pair<String, String> {
        val colonIdx = collection.indexOf(':')
        val prefix = collection.substring(0, colonIdx)
        val name   = collection.substring(colonIdx + 1)
        return when (prefix) {
            "local"  -> "local"  to "$itemScopePath::$name"
            "type"   -> "type"   to "$contentType::$name"
            else     -> "global" to name
        }
    }

    /** Wraps a string in a JS double-quoted literal with escaping for safe `evaluateJavascript` use. */
    private fun escapeJs(s: String): String {
        val escaped = s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return "\"$escaped\""
    }
}
