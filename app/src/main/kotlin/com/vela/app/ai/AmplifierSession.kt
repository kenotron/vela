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
 * Tools now executed natively in Rust (amplifier-core). Filtering these out prevents
 * the Kotlin-side ToolRegistry definitions from shadowing the Rust implementations.
 */
internal val RUST_NATIVE_TOOLS = setOf(
    "read_file", "write_file", "edit_file", "glob", "grep",
    "bash", "todo", "load_skill",
)

/**
 * Thin, stateless wrapper around the amplifier-core JNI bridge.
 *
 * Owns NO history — callers pass [historyJson] pre-built from DB.
 * Owns NO coroutine scope — callers own the lifecycle.
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

    override suspend fun runTurn(
        historyJson:       String,
        userInput:         String,
        userContentJson:   String?,
        systemPrompt:      String,
        vaultPath:         String,
        onToolStart:       (suspend (name: String, argsJson: String) -> String),
        onToolEnd:         (suspend (stableId: String, result: String) -> Unit),
        onToken:           (suspend (token: String) -> Unit),
        onProviderRequest: (suspend () -> String?),
            onServerTool:      (suspend (name: String, argsJson: String) -> Unit),
        ) {
            val toolsJson = buildToolsJson()
        Log.d(TAG, "runTurn model=${getModel()} historyLen=${historyJson.length} hasSystemPrompt=${systemPrompt.isNotBlank()}")

        var tokenWasEmitted = false

        val finalText = AmplifierBridge.nativeRun(
            apiKey            = getApiKey(),
            model             = getModel(),
            toolsJson         = toolsJson,
            historyJson       = historyJson,
            userInput         = userInput,
            userContentJson   = userContentJson,
            systemPrompt      = systemPrompt,
            vaultPath         = vaultPath,
            tokenCb           = { token ->
                tokenWasEmitted = true
                runBlocking { onToken(token) }
            },
            toolCb            = { name, argsJson ->
                runBlocking {
                    val stableId = onToolStart(name, argsJson)
                    val result   = executeTool(name, argsJson)
                    onToolEnd(stableId, result)
                    result
                }
            },
            // provider_request hook — injects ephemeral context before each LLM call.
            // Additional hooks (VaultSyncHook, etc.) may be added here later.
            hookCallbacks = arrayOf(
                AmplifierBridge.HookRegistration(
                    events = arrayOf("provider_request"),
                    callback = AmplifierBridge.HookCallback { _, _ ->
                        val injection = runBlocking { onProviderRequest() }
                        if (injection.isNullOrEmpty())
                            """{"action":"continue"}"""
                        else
                            """{"action":"inject_context","context_injection":${org.json.JSONObject.quote(injection)}}"""
                    }
                )
            ),
            serverToolCb = { name, argsJson ->
                runBlocking { onServerTool(name, argsJson) }
            },
            )

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
        toolRegistry.all()
            .filter { tool -> tool.name !in RUST_NATIVE_TOOLS }
            .forEach { tool ->
            val props = JSONObject()
            tool.parameters.forEach { p ->
                props.put(p.name, JSONObject().put("type", p.type.lowercase()).put("description", p.description))
            }
            val params = JSONObject().put("type", "object").put("properties", props).also { obj ->
                val requiredParams = tool.parameters.filter { it.required }
                if (requiredParams.isNotEmpty()) obj.put("required", JSONArray().also { req ->
                    requiredParams.forEach { req.put(it.name) }
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
