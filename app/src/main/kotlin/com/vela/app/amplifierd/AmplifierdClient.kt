package com.vela.app.amplifierd

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * HTTP client for a single amplifierd node.
 * One instance per node — NOT a singleton.
 *
 * All blocking OkHttp calls run on [Dispatchers.IO] via [withContext].
 * Non-2xx responses throw [IOException] (except [health], which returns false).
 */
class AmplifierdClient(private val baseUrl: String, private val token: String) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // ── Private HTTP helpers ──────────────────────────────────────────────────

    private suspend fun get(path: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$baseUrl$path")
            .header("x-amplifier-token", token)
            .build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw IOException("HTTP ${res.code} on GET $path")
            res.body?.string() ?: throw IOException("Empty body on GET $path")
        }
    }

    private suspend fun post(path: String, body: JSONObject): String = withContext(Dispatchers.IO) {
        val reqBody = body.toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url("$baseUrl$path")
            .header("x-amplifier-token", token)
            .post(reqBody)
            .build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw IOException("HTTP ${res.code} on POST $path")
            res.body?.string() ?: throw IOException("Empty body on POST $path")
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * GET /capabilities
     * {"hostname":"...","platform":"darwin/arm64","amplifierd_version":"0.x",
     *  "active_bundles":["superpowers"],"available_tools":["bash",...],"errors":[]}
     */
    suspend fun getCapabilities(): AmplifierdCapabilities {
        val json = JSONObject(get("/capabilities"))
        // active_sessions: reported by amplifierd health endpoint as active_sessions
        val activeSessions = json.optInt("active_sessions", 0)
        return AmplifierdCapabilities(
            hostname          = json.optString("hostname"),
            platform          = json.optString("platform"),
            amplifierdVersion = json.optString("amplifierd_version"),
            activeBundles     = json.optJSONArray("active_bundles").toStringList(),
            availableTools    = json.optJSONArray("available_tools").toStringList(),
            activeSessions    = activeSessions,
        )
    }

    /**
     * GET /projects
     * [{"id":"uuid","name":"Work","description":"","created_at":1234567890.0}]
     */
    suspend fun getProjects(): List<AmplifierdProject> {
        val arr = JSONArray(get("/projects"))
        return (0 until arr.length()).map { i ->
            arr.getJSONObject(i).toProject()
        }
    }

    /**
     * POST /projects  body: {"name":"Work","description":""}
     * → {"id":"uuid","name":"Work","description":"","created_at":1234567890.0}
     */
    suspend fun createProject(name: String, description: String = ""): AmplifierdProject {
        val body = JSONObject().apply {
            put("name", name)
            put("description", description)
        }
        return JSONObject(post("/projects", body)).toProject()
    }

    /**
     * GET /projects/:id/sessions
     * {"active":[...],"recent":[...],"total":N}
     * Returns Pair(active, recent) where active = running/waiting, recent = completed/error.
     */
    suspend fun getSessions(projectId: String): Pair<List<AmplifierdSession>, List<AmplifierdSession>> {
        val response = JSONObject(get("/projects/$projectId/sessions"))
        fun parseList(key: String): List<AmplifierdSession> {
            val arr = response.optJSONArray(key) ?: return emptyList()
            return (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                AmplifierdSession(
                    sessionId    = obj.getString("session_id"),
                    projectId    = obj.getString("project_id"),
                    status       = obj.optString("status", "completed"),
                    title        = obj.optString("title", "").ifBlank { obj.getString("session_id").take(8) },
                    createdAt    = obj.optLong("created_at", 0L),
                    lastActivity = obj.optLong("last_activity", 0L),
                    bundleName   = "",
                )
            }
        }
        return Pair(parseList("active"), parseList("recent"))
    }

    /**
     * Create a real amplifierd session, then register it with the vela plugin.
     *
     * Step 1: POST /sessions → {"session_id":"uuid",...}
     * Step 2: POST /projects/{id}/sessions with session_id so it appears in the project list.
     *
     * Returns the new session ID.
     */
    suspend fun createSession(
        projectId: String,
        workspaceDir: String = "~",
        title: String = "",
        bundle: String = "superpowers",
    ): String {
        // Create real amplifierd session
        val sessionBody = JSONObject().apply { put("bundle", bundle) }
        val sessionResp = JSONObject(post("/sessions", sessionBody))
        val sessionId   = sessionResp.getString("session_id")

        // Register with vela plugin for project tracking (non-fatal)
        try {
            val velaBody = JSONObject().apply {
                put("session_id", sessionId)
                put("working_directory", workspaceDir)
                put("title", title.ifBlank { sessionId.take(8) })
            }
            post("/projects/$projectId/sessions", velaBody)
        } catch (_: Exception) { /* non-fatal — vela plugin may not have updated yet */ }

        return sessionId
    }

    /**
     * GET /sessions/:id/transcript
     * → {"session_id":"","messages":[{"role":"user","content":"..."},...],"transcript":[],...}
     * Parses the messages list into [TurnContent] objects for display in the session view.
     */
    suspend fun getTranscript(sessionId: String): List<com.vela.app.ui.sessiondetail.TurnContent> {
        val response = JSONObject(get("/sessions/$sessionId/transcript"))
        val messages  = response.optJSONArray("messages") ?: return emptyList()
        val result    = mutableListOf<com.vela.app.ui.sessiondetail.TurnContent>()
        for (i in 0 until messages.length()) {
            val msg  = messages.getJSONObject(i)
            val role = msg.optString("role")
            val text = when {
                msg.has("content") && !msg.isNull("content") -> {
                    val raw = msg.get("content")
                    if (raw is String) raw else raw.toString()
                }
                else -> continue
            }
            when (role) {
                "user"      -> result += com.vela.app.ui.sessiondetail.TurnContent(text = text, isUser = true)
                "assistant" -> result += com.vela.app.ui.sessiondetail.TurnContent(text = text, isUser = false)
            }
        }
        return result
    }

    /**
     * POST /sessions/:id/approvals/:approvalId  body: {"approved":true|false}
     */
    suspend fun approveSession(sessionId: String, approvalId: String, approved: Boolean) {
        val body = JSONObject().apply { put("approved", approved) }
        post("/sessions/$sessionId/approvals/$approvalId", body)
    }

    /** GET /health → true if 200, false on any error or non-2xx. */
    suspend fun health(): Boolean = try {
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url("$baseUrl/health")
                .header("x-amplifier-token", token)
                .build()
            http.newCall(req).execute().use { res -> res.isSuccessful }
        }
    } catch (_: Exception) {
        false
    }

    // ── JSON mapping helpers ──────────────────────────────────────────────────

    private fun JSONObject.toProject() = AmplifierdProject(
        id          = getString("id"),
        name        = getString("name"),
        description = optString("description", ""),
        createdAt   = optDouble("created_at", 0.0).toLong(),
    )

    private fun JSONArray?.toStringList(): List<String> {
        this ?: return emptyList()
        return (0 until length()).map { getString(it) }
    }
}
