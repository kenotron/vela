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
        return AmplifierdCapabilities(
            hostname          = json.optString("hostname"),
            platform          = json.optString("platform"),
            amplifierdVersion = json.optString("amplifierd_version"),
            activeBundles     = json.optJSONArray("active_bundles").toStringList(),
            availableTools    = json.optJSONArray("available_tools").toStringList(),
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
     * POST /projects/:id/sessions  body: {"working_directory":"~/workspace","title":""}
     * → {"session_id":"uuid"}
     * Returns the new session ID.
     */
    suspend fun createSession(projectId: String, workspaceDir: String = "~", title: String = ""): String {
        val body = JSONObject().apply {
            put("working_directory", workspaceDir)
            put("title", title)
        }
        val response = JSONObject(post("/projects/$projectId/sessions", body))
        return response.getString("session_id")
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
