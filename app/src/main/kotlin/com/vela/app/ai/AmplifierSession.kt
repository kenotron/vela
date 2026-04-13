package com.vela.app.ai

import android.content.Context
import android.util.Log
import com.vela.app.ai.tools.ToolRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import com.vela.app.engine.InferenceSession
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AmplifierSession"

/**
 * Thin, stateless wrapper around the amplifier-core JNI bridge.
 *
 * Owns NO history — callers pass [historyJson] pre-built from DB.
 * Owns NO coroutine scope — callers own the lifecycle.
 *
 * [onToolStart] returns a stable String ID (the caller-assigned TurnEvent ID).
 * That same ID is passed to [onToolEnd] so the caller can UPDATE the exact
 * DB row without any snapshot lookup.
 */
@Singleton
class AmplifierSession @Inject constructor(
    private val context: Context,
    private val toolRegistry: ToolRegistry,
) : InferenceSession {
    companion object {
        private const val PREFS_NAME  = "amplifier_prefs"
        private const val KEY_API_KEY = "anthropic_api_key"
        private const val KEY_MODEL   = "anthropic_model"
        const val DEFAULT_MODEL       = "claude-sonnet-4-6"
    }

    private val prefs get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getApiKey(): String = prefs.getString(KEY_API_KEY, "") ?: ""
    fun getModel(): String  = prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
    fun setApiKey(k: String) { prefs.edit().putString(KEY_API_KEY, k).apply() }
    override fun isConfigured(): Boolean = getApiKey().isNotBlank()

    /**
     * Run one user turn.
     *
     * This is a **blocking** call — it drives the Rust JNI loop synchronously on
     * whatever thread the caller is on. Callers should be on Dispatchers.IO.
     *
     * @param historyJson  Anthropic-format JSON array of prior messages (built by caller).
     * @param userInput    The user's new message.
     * @param onToolStart  Called when a tool call starts. Returns a stable ID the caller
     *                     assigns (e.g., a UUID for the TurnEvent row). The same ID is
     *                     passed back to [onToolEnd] for in-place DB update.
     * @param onToolEnd    Called when a tool completes. Receives (stableId, result).
     * @param onToken      Called for each streamed text token (currently the full response
     *                     arrives at once, but this API is forward-compatible with SSE).
     */
    override suspend fun runTurn(
        historyJson: String,
        userInput: String,
        onToolStart: (suspend (name: String, argsJson: String) -> String),
        onToolEnd:   (suspend (stableId: String, result: String) -> Unit),
        onToken:     (suspend (token: String) -> Unit),
    ) {
        val toolsJson = buildToolsJson()
        Log.d(TAG, "runTurn model=${getModel()} historyLen=${historyJson.length}")

        // Antipattern guard: orchestrator.rs calls emit_token() AND returns the
        // same string. Without this flag the text would be emitted twice —
        // once via tokenCb during nativeRun, once from the return value below.
        var tokenWasEmitted = false

        val finalText = AmplifierBridge.nativeRun(
            apiKey      = getApiKey(),
            model       = getModel(),
            toolsJson   = toolsJson,
            historyJson = historyJson,
            userInput   = userInput,
            tokenCb     = { token ->
                tokenWasEmitted = true
                runBlocking { onToken(token) }
            },
            toolCb      = { name, argsJson ->
                runBlocking {
                    val stableId = onToolStart(name, argsJson)
                    val result   = executeTool(name, argsJson)
                    onToolEnd(stableId, result)
                    result
                }
            },
        )

        // Fallback: only emit from return value if streaming callbacks never fired
        // (e.g., future code path where Rust skips emit_token).
        if (!tokenWasEmitted && finalText.isNotEmpty()) onToken(finalText)
    }

    private suspend fun executeTool(name: String, argsJson: String): String = try {
        val argsMap = JSONObject(argsJson).let { o -> o.keys().asSequence().associateWith { o.get(it) } }
        toolRegistry.execute(name, argsMap)
    } catch (e: Exception) {
        Log.e(TAG, "executeTool $name: $e")
        "Error: ${e.message?.take(200)}"
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
