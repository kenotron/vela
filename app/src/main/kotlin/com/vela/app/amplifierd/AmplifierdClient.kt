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

    // ── Private HTTP helpers ────────────────────────────────────────────────

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

    // ── Public API ──────────────────────────────────────────────────────────

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
     * [{"session_id":"uuid","project_id":"uuid","bundle_name":"superpowers",
     *   "created_at":1234567890.0,"status":"done"}]
     */
    suspend fun getSessions(projectId: String): List<AmplifierdSession> {
        val arr = JSONArray(get("/projects/$projectId/sessions"))
        return (0 until arr.length()).map { i ->
            arr.getJSONObject(i).toSession()
        }
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

    // ── JSON mapping helpers ────────────────────────────────────────────────

    private fun JSONObject.toProject() = AmplifierdProject(
        id          = getString("id"),
        name        = getString("name"),
        description = optString("description", ""),
        createdAt   = optDouble("created_at", 0.0).toLong(),
    )

    private fun JSONObject.toSession() = AmplifierdSession(
        sessionId  = getString("session_id"),
        projectId  = getString("project_id"),
        bundleName = optString("bundle_name", ""),
        createdAt  = optDouble("created_at", 0.0).toLong(),
        status     = optString("status", "done"),
    )

    private fun JSONArray?.toStringList(): List<String> {
        this ?: return emptyList()
        return (0 until length()).map { getString(it) }
    }
}
