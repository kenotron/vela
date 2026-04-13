    package com.vela.app.ai

    import android.content.Context
    import com.vela.app.ai.tools.ToolRegistry
    import kotlinx.coroutines.Dispatchers
    import kotlinx.coroutines.channels.awaitClose
    import kotlinx.coroutines.flow.Flow
    import kotlinx.coroutines.flow.callbackFlow
    import kotlinx.coroutines.flow.flowOn
    import kotlinx.coroutines.withContext
    import org.json.JSONArray
    import org.json.JSONObject
    import javax.inject.Inject
    import javax.inject.Singleton

    /**
     * Manages one ongoing conversation through the amplifier-core Rust engine.
     *
     * - Maintains Anthropic-format message history across turns.
     * - Builds the tool schema JSON from [ToolRegistry] and passes it to Rust.
     * - Dispatches tool calls from the Rust orchestrator back to [ToolRegistry].
     * - Streams tokens to the caller via [Flow<String>].
     *
     * API key is read from SharedPreferences ("amplifier_prefs" / "anthropic_api_key").
     * Call [setApiKey] to update it.
     */
    @Singleton
    class AmplifierSession @Inject constructor(
        private val context: Context,
        private val toolRegistry: ToolRegistry,
    ) {
        companion object {
            private const val PREFS_NAME = "amplifier_prefs"
            private const val KEY_API_KEY = "anthropic_api_key"
            private const val KEY_MODEL   = "anthropic_model"
            private const val DEFAULT_MODEL = "claude-3-5-haiku-20241022"
        }

        // In-memory Anthropic-format message history for this session
        private val history = mutableListOf<JSONObject>()

        // ── API key / model ───────────────────────────────────────────────────────

        private val prefs get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        fun getApiKey(): String = prefs.getString(KEY_API_KEY, "") ?: ""
        fun getModel(): String  = prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL

        fun setApiKey(key: String) { prefs.edit().putString(KEY_API_KEY, key).apply() }
        fun setModel(model: String) { prefs.edit().putString(KEY_MODEL, model).apply() }

        fun isConfigured(): Boolean = getApiKey().isNotBlank()

        // ── Run one turn ──────────────────────────────────────────────────────────

        /**
         * Stream a single user turn through amplifier-core.
         * Each emitted String is a raw token chunk from the Rust side.
         * The final value is the complete assistant response (all chunks concatenated).
         *
         * Suspends on IO; safe to collect on any coroutine context.
         */
        fun runTurn(userInput: String): Flow<String> = callbackFlow {
            withContext(Dispatchers.IO) {
                val finalText = AmplifierBridge.nativeRun(
                    apiKey      = getApiKey(),
                    model       = getModel(),
                    toolsJson   = buildToolsJson(),
                    historyJson = buildHistoryJson(),
                    userInput   = userInput,
                    tokenCb     = { token -> trySend(token) },
                    toolCb      = { name, argsJson -> executeTool(name, argsJson) },
                )
                // Append complete exchange to history for next turn
                history += JSONObject().put("role", "user").put("content", userInput)
                history += JSONObject().put("role", "assistant").put("content", finalText)
            }
            close()
            awaitClose()
        }.flowOn(Dispatchers.IO)

        fun clearHistory() { history.clear() }

        // ── Tool dispatch ─────────────────────────────────────────────────────────

        private fun executeTool(name: String, argsJson: String): String {
            return try {
                val argsMap = parseArgsJson(argsJson)
                kotlinx.coroutines.runBlocking { toolRegistry.execute(name, argsMap) }
            } catch (e: Exception) {
                "Error executing $name: ${e.message?.take(200)}"
            }
        }

        private fun parseArgsJson(json: String): Map<String, Any> {
            val obj = JSONObject(json)
            return obj.keys().asSequence().associateWith { key -> obj.get(key) }
        }

        // ── Serialisation ─────────────────────────────────────────────────────────

        private fun buildHistoryJson(): String {
            val arr = JSONArray()
            history.forEach { arr.put(it) }
            return arr.toString()
        }

        private fun buildToolsJson(): String {
            val arr = JSONArray()
            toolRegistry.all().forEach { tool ->
                val fn = JSONObject()
                fn.put("name", tool.name)
                fn.put("description", tool.description)

                val props = JSONObject()
                tool.parameters.forEach { p ->
                    props.put(p.name, JSONObject()
                        .put("type", p.type)
                        .put("description", p.description))
                }
                val params = JSONObject()
                    .put("type", "object")
                    .put("properties", props)
                if (tool.parameters.isNotEmpty()) {
                    val req = JSONArray()
                    tool.parameters.forEach { req.put(it.name) }
                    params.put("required", req)
                }
                fn.put("parameters", params)

                arr.put(JSONObject()
                    .put("type", "function")
                    .put("function", fn))
            }
            return arr.toString()
        }
    }
    