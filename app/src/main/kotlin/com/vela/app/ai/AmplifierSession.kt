    package com.vela.app.ai

    import android.content.Context
    import android.util.Log
    import com.vela.app.ai.tools.ToolRegistry
    import kotlinx.coroutines.Dispatchers
    import kotlinx.coroutines.channels.awaitClose
    import kotlinx.coroutines.flow.Flow
    import kotlinx.coroutines.flow.callbackFlow
    import kotlinx.coroutines.flow.flowOn
    import org.json.JSONArray
    import org.json.JSONObject
    import javax.inject.Inject
    import javax.inject.Singleton

    private const val TAG = "AmplifierSession"

    @Singleton
    class AmplifierSession @Inject constructor(
        private val context: Context,
        private val toolRegistry: ToolRegistry,
    ) {
        companion object {
            private const val PREFS_NAME   = "amplifier_prefs"
            private const val KEY_API_KEY  = "anthropic_api_key"
            private const val KEY_MODEL    = "anthropic_model"
            const val DEFAULT_MODEL = "claude-sonnet-4-6"
        }

        private val history = mutableListOf<JSONObject>()
        private val prefs   get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        fun getApiKey(): String  = prefs.getString(KEY_API_KEY, "")  ?: ""
        fun getModel(): String   = prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        fun setApiKey(key: String)   { prefs.edit().putString(KEY_API_KEY,  key).apply() }
        fun setModel(model: String)  { prefs.edit().putString(KEY_MODEL, model).apply() }
        fun isConfigured(): Boolean  = getApiKey().isNotBlank()
        fun clearHistory()           { history.clear() }

        /**
         * Stream one user turn through the Rust amplifier-core engine.
         *
         * @param userInput    The user's message.
         * @param onToolStart  Called when a tool call begins: (toolName, argsJson).
         * @param onToolEnd    Called when a tool call completes: (toolName, resultSnippet).
         */
        fun runTurn(
            userInput: String,
            onToolStart: ((name: String, argsJson: String) -> Unit)? = null,
            onToolEnd:   ((name: String, result: String)   -> Unit)? = null,
        ): Flow<String> = callbackFlow {
            Log.d(TAG, "runTurn: model=${getModel()} history=${history.size} msgs")

            var tokensEmitted = false

            val finalText = try {
                AmplifierBridge.nativeRun(
                    apiKey      = getApiKey(),
                    model       = getModel(),
                    toolsJson   = buildToolsJson(),
                    historyJson = buildHistoryJson(),
                    userInput   = userInput,
                    tokenCb     = { token ->
                        tokensEmitted = true
                        trySend(token)
                    },
                    toolCb = { name, argsJson ->
                        // Notify before execution so the UI can show "in progress"
                        onToolStart?.invoke(name, argsJson)
                        val result = executeTool(name, argsJson)
                        // Notify after so the UI can mark "done"
                        onToolEnd?.invoke(name, result)
                        result
                    },
                )
            } catch (e: Throwable) {
                Log.e(TAG, "nativeRun threw: $e")
                "Error: ${e.message?.take(200) ?: "JNI call failed"}"
            }

            Log.d(TAG, "runTurn: done tokensEmitted=$tokensEmitted finalLen=${finalText.length}")

            if (!tokensEmitted && finalText.isNotEmpty()) {
                Log.w(TAG, "runTurn: no streaming — emitting finalText as single chunk")
                trySend(finalText)
            }

            history += JSONObject().put("role", "user").put("content", userInput)
            history += JSONObject().put("role", "assistant").put("content", finalText)

            close()
            awaitClose()
        }.flowOn(Dispatchers.IO)

        // ── Tool dispatch ─────────────────────────────────────────────────────────

        private fun executeTool(name: String, argsJson: String): String {
            Log.d(TAG, "executeTool: $name  args=$argsJson")
            return try {
                val argsMap = parseArgsJson(argsJson)
                kotlinx.coroutines.runBlocking { toolRegistry.execute(name, argsMap) }
            } catch (e: Exception) {
                Log.e(TAG, "executeTool $name failed: $e")
                "Error executing $name: ${e.message?.take(200)}"
            }
        }

        private fun parseArgsJson(json: String): Map<String, Any> = try {
            val obj = JSONObject(json)
            obj.keys().asSequence().associateWith { obj.get(it) }
        } catch (e: Exception) {
            emptyMap()
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
                    .put("name", tool.name)
                    .put("description", tool.description)

                val props = JSONObject()
                tool.parameters.forEach { p ->
                    props.put(p.name, JSONObject()
                        .put("type", p.type.lowercase())
                        .put("description", p.description))
                }
                val params = JSONObject().put("type", "object").put("properties", props)
                if (tool.parameters.isNotEmpty()) {
                    val req = JSONArray()
                    tool.parameters.forEach { req.put(it.name) }
                    params.put("required", req)
                }
                fn.put("parameters", params)

                arr.put(JSONObject().put("type", "function").put("function", fn))
            }
            return arr.toString()
        }
    }
    