package com.vela.app.ai.tools

import android.util.Log
import com.vela.app.ssh.NodeType
import com.vela.app.ssh.SshNode
import com.vela.app.ssh.SshNodeRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private const val TAG = "AmplifierdTool"
private val JSON = "application/json".toMediaType()

/**
 * Delegates a task to a full Amplifier agent session running on a connected
 * amplifierd node. Each node gets one persistent session that is reused across
 * calls (sessions survive multiple tool invocations within a conversation).
 *
 * The tool uses the synchronous amplifierd execute endpoint so the agent loop
 * waits for the remote response before continuing.
 */
class AmplifierdTool(
    private val nodeRegistry: SshNodeRegistry,
    baseClient: OkHttpClient,
) : Tool {

    override val name        = "run_on_amplifierd"
    override val displayName = "Amplifier Daemon"
    override val icon        = "🤖"
    override val description = """
        Delegate a complex task to a full Amplifier agent session running on a
        connected amplifierd node. Use when the task needs desktop-level
        capabilities: running code, editing remote files, multi-step workflows,
        or tools not available on mobile.

        The node must be configured in Settings → Connections with type "Amplifier Daemon".
        Sessions are persistent per node — context accumulates across calls.
    """.trimIndent()

    override val parameters = listOf(
        ToolParameter("prompt", "string", "The task or question to send to the remote Amplifier agent"),
        ToolParameter("node",   "string", "Node label to target. Omit to use the first available amplifierd node.", required = false),
    )

    // Reuse one session per node URL to keep context across turns
    private val sessions = ConcurrentHashMap<String, String>() // nodeId → sessionId

    // Use a longer timeout — remote agent calls can be slow
    private val client = baseClient.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun execute(args: Map<String, Any>): String = withContext(Dispatchers.IO) {
        val prompt    = args["prompt"] as? String ?: return@withContext "Error: prompt is required"
        val nodeLabel = args["node"]   as? String

        val node = pickNode(nodeLabel)
            ?: return@withContext buildString {
                append("Error: no amplifierd node available.")
                if (!nodeLabel.isNullOrBlank()) append(" Node '$nodeLabel' not found.")
                append("\nAdd one in Settings → Connections → [+] → Amplifier Daemon.")
            }

        try {
            val sessionId = getOrCreateSession(node)
                ?: return@withContext "Error: could not create amplifierd session on ${node.url}"
            executePrompt(node, sessionId, prompt)
        } catch (e: Exception) {
            Log.e(TAG, "amplifierd call failed for ${node.url}", e)
            "Error connecting to amplifierd at ${node.url}: ${e.message}"
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private fun pickNode(label: String?): SshNode? {
        val amplifierdNodes = nodeRegistry.cache.filter { it.type == NodeType.AMPLIFIERD }
        return if (label != null) amplifierdNodes.firstOrNull { it.label.equals(label, ignoreCase = true) }
               else              amplifierdNodes.firstOrNull()
    }

    private fun getOrCreateSession(node: SshNode): String? {
        sessions[node.id]?.let { return it }
        return createSession(node)?.also { sessions[node.id] = it }
    }

    /** POST /sessions → returns session_id string, or null on failure. */
    private fun createSession(node: SshNode): String? = try {
        val body = JSONObject().put("bundle_name", JSONObject.NULL).toString()
            .toRequestBody(JSON)
        val req = Request.Builder()
            .url("${node.url}/sessions")
            .post(body)
            .applyAuth(node)
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "createSession HTTP ${resp.code} from ${node.url}")
                return null
            }
            JSONObject(resp.body!!.string()).getString("session_id")
        }
    } catch (e: Exception) {
        Log.e(TAG, "createSession failed", e)
        null
    }

    /** POST /sessions/{id}/execute → response text from the agent. */
    private fun executePrompt(node: SshNode, sessionId: String, prompt: String): String {
        val body = JSONObject().put("prompt", prompt).toString().toRequestBody(JSON)
        val req  = Request.Builder()
            .url("${node.url}/sessions/$sessionId/execute")
            .post(body)
            .applyAuth(node)
            .build()
        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                // Session might have been cleaned up — clear cache and return error
                if (resp.code == 404 || resp.code == 409) sessions.remove(node.id)
                "Error: amplifierd returned HTTP ${resp.code} — session may have expired. Retry to create a new session."
            } else {
                parseResponse(resp.body!!.string())
            }
        }
    }

    /** Try common response field names from amplifierd's ExecuteResponse. */
    private fun parseResponse(json: String): String = try {
        val obj = JSONObject(json)
        obj.optString("text")
            .ifBlank { obj.optString("response") }
            .ifBlank { obj.optString("content") }
            .ifBlank { obj.optString("output") }
            .ifBlank { json }
    } catch (_: Exception) { json }

    private fun Request.Builder.applyAuth(node: SshNode): Request.Builder = apply {
        if (node.token.isNotBlank()) addHeader("x-amplifier-token", node.token)
    }
}
