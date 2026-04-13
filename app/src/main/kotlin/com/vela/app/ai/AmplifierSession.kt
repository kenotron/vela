    package com.vela.app.ai

    import android.content.Context
    import android.util.Log
    import com.vela.app.ai.tools.ToolRegistry
    import com.vela.app.domain.model.Message
    import com.vela.app.domain.model.MessageRole
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

    /**
     * Stateless inference facade over the amplifier-core Rust engine.
     *
     * This class has NO in-memory conversation history. History is owned entirely
     * by the DB (via [ConversationRepository]) and passed in on every call via
     * [priorMessages]. This means:
     *  - History survives app restarts (loaded from Room on ViewModel init)
     *  - Session switching is free — just pass different messages
     *  - No sync issues between in-memory state and the DB
     */
    @Singleton
    class AmplifierSession @Inject constructor(
        private val context: Context,
        private val toolRegistry: ToolRegistry,
    ) {
        companion object {
            private const val PREFS_NAME  = "amplifier_prefs"
            private const val KEY_API_KEY = "anthropic_api_key"
            private const val KEY_MODEL   = "anthropic_model"
            const val DEFAULT_MODEL       = "claude-sonnet-4-6"
        }

        private val prefs get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        fun getApiKey(): String = prefs.getString(KEY_API_KEY, "") ?: ""
        fun getModel(): String  = prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        fun setApiKey(key: String)  { prefs.edit().putString(KEY_API_KEY, key).apply() }
        fun setModel(m: String)     { prefs.edit().putString(KEY_MODEL, m).apply() }
        fun isConfigured(): Boolean = getApiKey().isNotBlank()

        /**
         * Stream one user turn through the Rust amplifier-core engine.
         *
         * @param priorMessages  All USER + ASSISTANT messages in this conversation so far
         *                       (loaded from DB — order = chronological ascending).
         *                       TOOL_CALL messages are skipped; they are UI artefacts only.
         * @param userInput      The new user message.
         * @param onToolStart    Called when a tool call begins: (toolName, argsJson).
         * @param onToolEnd      Called when a tool call finishes: (toolName, resultText).
         */
        fun runTurn(
            priorMessages: List<Message>,
            userInput: String,
            onToolStart: ((name: String, argsJson: String) -> Unit)? = null,
            onToolEnd:   ((name: String, result: String)   -> Unit)? = null,
        ): Flow<String> = callbackFlow {
            val historyJson = buildHistoryJson(priorMessages)
            Log.d(TAG, "runTurn: model=${getModel()} history=${priorMessages.size} msgs")

            var tokensEmitted = false
            val finalText = try {
                AmplifierBridge.nativeRun(
                    apiKey      = getApiKey(),
                    model       = getModel(),
                    toolsJson   = buildToolsJson(),
                    historyJson = historyJson,
                    userInput   = userInput,
                    tokenCb     = { token -> tokensEmitted = true; trySend(token) },
                    toolCb      = { name, argsJson ->
                        onToolStart?.invoke(name, argsJson)
                        val result = executeTool(name, argsJson)
                        onToolEnd?.invoke(name, result)
                        result
                    },
                )
            } catch (e: Throwable) {
                Log.e(TAG, "nativeRun threw: $e")
                "Error: ${e.message?.take(200) ?: "JNI call failed"}"
            }

            Log.d(TAG, "runTurn done — tokensEmitted=$tokensEmitted finalLen=${finalText.length}")
            if (!tokensEmitted && finalText.isNotEmpty()) trySend(finalText)

            close(); awaitClose()
        }.flowOn(Dispatchers.IO)

        // ── Helpers ───────────────────────────────────────────────────────────────

        private fun executeTool(name: String, argsJson: String): String = try {
            val argsMap = JSONObject(argsJson).let { obj ->
                obj.keys().asSequence().associateWith { obj.get(it) }
            }
            kotlinx.coroutines.runBlocking { toolRegistry.execute(name, argsMap) }
        } catch (e: Exception) {
            Log.e(TAG, "executeTool $name: $e")
            "Error: ${e.message?.take(200)}"
        }

        /**
         * Convert the DB message list to Anthropic JSON format.
         * Only USER and ASSISTANT messages are included — TOOL_CALL rows are UI-only.
         */
        private fun buildHistoryJson(messages: List<Message>): String {
            val arr = JSONArray()
            messages
                .filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
                .forEach { msg ->
                    arr.put(JSONObject()
                        .put("role", if (msg.role == MessageRole.USER) "user" else "assistant")
                        .put("content", msg.content))
                }
            return arr.toString()
        }

        private fun buildToolsJson(): String {
            val arr = JSONArray()
            toolRegistry.all().forEach { tool ->
                val props = JSONObject()
                tool.parameters.forEach { p ->
                    props.put(p.name, JSONObject().put("type", p.type.lowercase()).put("description", p.description))
                }
                val params = JSONObject().put("type", "object").put("properties", props).also { obj ->
                    if (tool.parameters.isNotEmpty()) obj.put("required", JSONArray().also { req ->
                        tool.parameters.forEach { req.put(it.name) }
                    })
                }
                arr.put(JSONObject()
                    .put("type", "function")
                    .put("function", JSONObject()
                        .put("name", tool.name)
                        .put("description", tool.description)
                        .put("parameters", params)))
            }
            return arr.toString()
        }
    }
    