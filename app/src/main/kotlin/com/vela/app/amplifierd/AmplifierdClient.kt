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
    suspend fun createProject(name: String, description: String = "", workingDir: String = ""): AmplifierdProject {
        val body = JSONObject().apply {
            put("name", name)
            put("description", description)
            if (workingDir.isNotBlank()) put("working_dir", workingDir)
        }
        val response = JSONObject(post("/projects", body))
        val project = response.toProject()
        // Fallback: if server didn't echo working_dir back yet, use what we sent
        return if (project.workingDir.isBlank() && workingDir.isNotBlank()) project.copy(workingDir = workingDir) else project
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
                    createdAt    = parseIso(obj.optString("created_at")),
                    lastActivity = parseIso(obj.optString("last_activity")),
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
    /**
     * Lightweight preview fetch: reads just the first few messages of a session transcript.
     * Returns Pair(firstUserMessage, lastUserMessage). Both are blank if session has no messages.
     * Used by the session list to show meaningful titles without loading the full transcript.
     */
    suspend fun getSessionPreview(sessionId: String): Pair<String, String> {
        return try {
            val response = JSONObject(get("/sessions/$sessionId/transcript"))
            val messages = response.optJSONArray("messages") ?: return Pair("", "")
            val userMessages = (0 until messages.length()).mapNotNull { i ->
                val msg = messages.getJSONObject(i)
                if (msg.optString("role") != "user") return@mapNotNull null
                when (val raw = msg.opt("content")) {
                    is String -> raw.take(120).trim()
                    else -> null
                }
            }.filter { it.isNotBlank() }
            val first = userMessages.firstOrNull() ?: ""
            val last  = userMessages.lastOrNull()?.takeIf { it != first } ?: ""
            Pair(first, last)
        } catch (_: Exception) { Pair("", "") }
    }

    suspend fun getTranscript(sessionId: String): List<com.vela.app.ui.sessiondetail.TurnContent> {
        val response = JSONObject(get("/sessions/$sessionId/transcript"))
        val messages  = response.optJSONArray("messages") ?: return emptyList()
        val result    = mutableListOf<com.vela.app.ui.sessiondetail.TurnContent>()
        for (i in 0 until messages.length()) {
            val msg  = messages.getJSONObject(i)
            val role = msg.optString("role")
            val text: String = when {
                msg.has("content") && !msg.isNull("content") -> {
                    when (val raw = msg.get("content")) {
                        is String -> raw  // user messages are plain strings
                        is org.json.JSONArray -> {
                            // Assistant messages are content block arrays:
                            // [{"type":"thinking","thinking":"..."}, {"type":"text","text":"..."}]
                            // Extract only "text" blocks, skip "thinking" blocks
                            (0 until raw.length()).mapNotNull { i ->
                                val block = raw.getJSONObject(i)
                                if (block.optString("type") == "text") block.optString("text", null)
                                else null
                            }.joinToString("\n").ifBlank { null }
                        }
                        else -> null
                    }
                }
                else -> null
            } ?: continue
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
    /**
     * GET /sessions/:id/transcript — full parse including content blocks.
     *
     * Amplifier/Anthropic transcript format:
     *   assistant message content: [{type:"thinking"}, {type:"text", text:""}, {type:"tool_use", id:"", name:"", input:{}}]
     *   user message content (tool results): [{type:"tool_result", tool_use_id:"", content:"", is_error:false}]
     *
     * Tool results (user role with tool_result content) are matched to their ToolUse
     * and folded into the same TurnContent as a ContentBlock.ToolResult.
     * The intermediate user-role tool_result messages are NOT exposed as separate turns.
     */
    /**
         * GET /sessions/:id/transcript — full parse including content blocks.
         *
         * IMPORTANT: amplifierd transcript format differs from Anthropic's raw API:
         *   - assistant tool calls:  type = "tool_call"   (NOT "tool_use")
         *   - tool results:          role = "tool" with STRING content  (NOT role="user" type="tool_result")
         *   - tool input field:      "input" JSONObject (same as Anthropic)
         *
         * Tool results are matched to their tool_call by position in the message list
         * (each "tool" role message immediately follows the "assistant" that called it).
         */
        suspend fun getTranscriptWithBlocks(sessionId: String): List<com.vela.app.ui.sessiondetail.TurnContent> {
            val response = JSONObject(get("/sessions/$sessionId/transcript"))
            val messages = response.optJSONArray("messages") ?: return emptyList()
            val result = mutableListOf<com.vela.app.ui.sessiondetail.TurnContent>()

            var i = 0
            while (i < messages.length()) {
                val msg  = messages.getJSONObject(i)
                val role = msg.optString("role")

                when (role) {
                    "user" -> {
                        val content = msg.opt("content")
                        if (content is String && content.isNotBlank()) {
                            result.add(com.vela.app.ui.sessiondetail.TurnContent(text = content, isUser = true))
                        }
                        i++
                    }

                    "assistant" -> {
                        val contentArr = msg.opt("content")
                        val blocks     = mutableListOf<com.vela.app.ui.sessiondetail.ContentBlock>()
                        var plainText  = ""

                        if (contentArr is JSONArray) {
                            for (j in 0 until contentArr.length()) {
                                val block = contentArr.getJSONObject(j)
                                when (block.optString("type")) {
                                    "text" -> {
                                        val t = block.optString("text", "")
                                        if (t.isNotBlank()) {
                                            blocks.add(com.vela.app.ui.sessiondetail.ContentBlock.Text(t))
                                            if (plainText.isBlank()) plainText = t
                                        }
                                    }
                                    "thinking" -> {
                                        val t = block.optString("thinking", "")
                                        if (t.isNotBlank()) blocks.add(com.vela.app.ui.sessiondetail.ContentBlock.Thinking(t))
                                    }
                                    // amplifierd stores tool calls as "tool_call"; handle "tool_use" too for safety
                                    "tool_call", "tool_use" -> {
                                        blocks.add(com.vela.app.ui.sessiondetail.ContentBlock.ToolUse(
                                            id        = block.optString("id"),
                                            name      = block.optString("name"),
                                            inputJson = block.optJSONObject("input")?.toString() ?: "{}",
                                            isRunning = false, // from transcript — tool has completed
                                        ))
                                    }
                                }
                            }
                        } else if (contentArr is String && contentArr.isNotBlank()) {
                            plainText = contentArr
                            blocks.add(com.vela.app.ui.sessiondetail.ContentBlock.Text(contentArr))
                        }

                        // Collect following role="tool" messages as results.
                        // Each "tool" message corresponds (by position) to a tool_call in this turn.
                        val toolCalls = blocks.filterIsInstance<com.vela.app.ui.sessiondetail.ContentBlock.ToolUse>()
                        var k = i + 1
                        var toolIdx = 0
                        while (k < messages.length() && toolIdx < toolCalls.size) {
                            val next = messages.getJSONObject(k)
                            if (next.optString("role") != "tool") break
                            val output = when (val c = next.opt("content")) {
                                is String    -> c
                                is JSONArray -> (0 until c.length()).joinToString("\n") { idx ->
                                    val item = c.getJSONObject(idx)
                                    if (item.optString("type") == "text") item.optString("text") else ""
                                }
                                else         -> ""
                            }
                            blocks.add(com.vela.app.ui.sessiondetail.ContentBlock.ToolResult(
                                toolUseId = toolCalls[toolIdx].id,
                                output    = output,
                                isError   = next.optBoolean("is_error", false),
                            ))
                            toolIdx++
                            k++
                        }
                        i = k

                        result.add(com.vela.app.ui.sessiondetail.TurnContent(
                            text          = plainText,
                            isUser        = false,
                            contentBlocks = blocks,
                        ))
                    }

                    // role="tool" messages consumed above; skip any orphaned ones
                    else -> i++
                }
            }
            return result
        }

        suspend fun approveSession(sessionId: String, approvalId: String, approved: Boolean) {
        val body = JSONObject().apply { put("approved", approved) }
        post("/sessions/$sessionId/approvals/$approvalId", body)
    }

    /**
     * POST /sessions/:id/name  body: {"name":"..."}
     * Updates the session name via the vela plugin. Fails silently if endpoint not available.
     */
    suspend fun updateSessionName(sessionId: String, name: String) {
        val body = JSONObject().apply { put("name", name) }
        post("/sessions/$sessionId/name", body)
    }

    /**
         * GET /sessions — returns native amplifierd sessions filtered by recency.
         *
         * Includes:
         *  - All active sessions (status "running" or "waiting"), regardless of age.
         *  - Sessions whose last_activity is within the last [sinceMs] millis (default 7 days).
         *
         * The native /sessions endpoint returns all sessions with no server-side filter, so we
         * download the full list and filter client-side. Fields differ from the vela-plugin response:
         *   "bundle"       → bundleName
         *   "created_at"   → ISO-8601 string (not epoch Long)
         *   "last_activity"→ ISO-8601 string (not epoch Long)
         *   no "project_id" or "title"
         */
        suspend fun getNativeSessions(
            sinceMs: Long = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000,
        ): List<AmplifierdSession> {
            val response = JSONObject(get("/sessions"))
            val arr = response.optJSONArray("sessions") ?: return emptyList()
            val result = mutableListOf<AmplifierdSession>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val sessionId    = obj.optString("session_id", "")
                // Sub-agent sessions (delegate tool spawns) start with 0000000000 — never surface to user
                if (sessionId.startsWith("0000000000")) continue
                val rawStatus    = obj.optString("status", "completed")
                val isActive     = rawStatus == "executing"
                val activityMs   = parseIso(obj.optString("last_activity"))
                val lastMs       = if (activityMs != 0L) activityMs else parseIso(obj.optString("created_at"))
                // Exclude old completed/failed sessions outside the recency window
                if (!isActive && lastMs in 1 until sinceMs) continue
                result.add(AmplifierdSession(
                    sessionId    = obj.getString("session_id"),
                    projectId    = "",
                    bundleName   = obj.optString("bundle", ""),
                    createdAt    = parseIso(obj.optString("created_at")),
                    lastActivity = lastMs,
                    status       = when (rawStatus) {
                        "executing" -> "running"
                        "idle"      -> "completed"
                        "failed"    -> "error"
                        "completed" -> "completed"
                        else        -> rawStatus
                    },
                    title        = "",
                ))
            }
            return result
        }

        /** Parse an ISO-8601 datetime string (e.g. "2026-05-01T07:09:22.481012+00:00") to epoch millis.
         *  Returns 0L for blank/null input or any parse failure. */
        private fun parseIso(s: String?): Long {
            if (s.isNullOrBlank()) return 0L
            return try {
                java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli()
            } catch (e: Exception) { 0L }
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
        workingDir  = optString("working_dir", ""),
    )

    private fun JSONArray?.toStringList(): List<String> {
        this ?: return emptyList()
        return (0 until length()).map { getString(it) }
    }
}
