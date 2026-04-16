package com.vela.app.ai.tools

    import android.util.Log
    import com.jcraft.jsch.ChannelExec
    import com.jcraft.jsch.JSch
    import com.vela.app.ssh.NodeType
    import com.vela.app.ssh.SshKeyManager
    import com.vela.app.ssh.SshNode
    import com.vela.app.ssh.SshNodeRegistry
    import kotlinx.coroutines.Dispatchers
    import kotlinx.coroutines.withContext
    import okhttp3.MediaType.Companion.toMediaType
    import okhttp3.OkHttpClient
    import okhttp3.Request
    import okhttp3.RequestBody.Companion.toRequestBody
    import org.json.JSONObject
    import java.io.ByteArrayOutputStream
    import java.util.concurrent.ConcurrentHashMap
    import java.util.concurrent.TimeUnit

    private const val TAG = "RunInNodeTool"
    private val JSON_MT = "application/json".toMediaType()

    /**
     * Unified node execution tool.
     *
     * Dispatches based on the node's type:
     *   SSH        → runs [input] as a shell command via JSch
     *   Amplifierd → sends [input] as a prompt to the amplifierd HTTP API
     *
     * Sessions are cached per amplifierd node so context accumulates across turns.
     */
    class RunInNodeTool(
        private val registry:   SshNodeRegistry,
        private val keyManager: SshKeyManager,
        baseClient: OkHttpClient,
    ) : Tool {

        override val name        = "run_in_node"
        override val displayName = "Run in Node"
        override val icon        = "🖥️"
        override val description = """
            Execute on a connected Vela node. Works with both node types:
              • SSH nodes        — runs `input` as a shell command on the remote machine
              • Amplifier daemon — sends `input` as a prompt to a full Amplifier agent session

            Call list_nodes first to see available node labels and their types.
        """.trimIndent()

        override val parameters = listOf(
            ToolParameter("node",            "string",  "Node label (from list_nodes)"),
            ToolParameter("input",           "string",  "Shell command (SSH) or task prompt (amplifierd)"),
            ToolParameter("timeout_seconds", "integer", "Max seconds to wait — default 30 (SSH) or 300 (amplifierd)", required = false),
        )

        // amplifierd: reuse one session per node across turns
        private val sessions = ConcurrentHashMap<String, String>()

        private val httpClient = baseClient.newBuilder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        // ── Dispatch ─────────────────────────────────────────────────────────────

        override suspend fun execute(args: Map<String, Any>): String = withContext(Dispatchers.IO) {
            val nodeLabel = args["node"]  as? String ?: return@withContext "Error: 'node' is required"
            val input     = args["input"] as? String ?: return@withContext "Error: 'input' is required"
            val timeout   = (args["timeout_seconds"] as? Number)?.toInt()

            val node = registry.findByLabel(nodeLabel) ?: run {
                val avail = registry.allSync()
                return@withContext if (avail.isEmpty())
                    "No nodes configured. Add one in Settings → Connections."
                else
                    "No node found with label '$nodeLabel'. Available: ${avail.joinToString { it.label }}"
            }

            when (node.type) {
                NodeType.SSH        -> runSsh(node, input, timeout ?: 30)
                NodeType.AMPLIFIERD -> runAmplifierd(node, input)
            }
        }

        // ── SSH path ──────────────────────────────────────────────────────────────

        private fun runSsh(node: SshNode, command: String, timeoutSec: Int): String {
            val jsch = JSch()
            jsch.addIdentity("vela",
                keyManager.getPrivateKeyPem().toByteArray(Charsets.UTF_8), null, null)

            val errors = mutableListOf<String>()
            for (host in node.hosts) {
                Log.d(TAG, "SSH → ${node.username}@$host:${node.port}  cmd=`$command`")
                try {
                    val session = jsch.getSession(node.username, host, node.port).apply {
                        setConfig("StrictHostKeyChecking", "no")
                        setConfig("ServerAliveInterval", "10")
                        connect(timeoutSec * 1000)
                    }
                    val channel = (session.openChannel("exec") as ChannelExec).apply {
                        setCommand(command)
                        val stdout = ByteArrayOutputStream()
                        val stderr = ByteArrayOutputStream()
                        outputStream = stdout
                        setErrStream(stderr)
                        connect()
                        val deadline = System.currentTimeMillis() + timeoutSec * 1000L
                        while (!isClosed && System.currentTimeMillis() < deadline) Thread.sleep(50)
                        Thread.sleep(50)
                        val out = stdout.toString(Charsets.UTF_8.name()).trimEnd()
                        val err = stderr.toString(Charsets.UTF_8.name()).trimEnd()
                        val exit = exitStatus
                        disconnect(); session.disconnect()
                        return buildString {
                            if (node.hosts.size > 1) appendLine("[connected via $host]")
                            if (out.isNotEmpty()) appendLine(out)
                            if (err.isNotEmpty()) appendLine("[stderr] $err")
                            append("[exit $exit]")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "SSH $host failed", e)
                    errors += "$host: ${e.message}"
                }
            }
            return "All hosts failed for '${node.label}':\n" + errors.joinToString("\n")
        }

        // ── amplifierd path ───────────────────────────────────────────────────────

        private fun runAmplifierd(node: SshNode, prompt: String): String {
            val sessionId = sessions[node.id] ?: createSession(node)
                ?: return "Error: could not create amplifierd session at ${node.url}"
            sessions[node.id] = sessionId

            val body = JSONObject().put("prompt", prompt).toString().toRequestBody(JSON_MT)
            val req  = Request.Builder()
                .url("${node.url}/sessions/$sessionId/execute")
                .post(body).applyToken(node).build()

            return httpClient.newCall(req).execute().use { resp ->
                if (resp.code == 404 || resp.code == 409) {
                    sessions.remove(node.id)
                    return "Session expired — retry to create a new session."
                }
                if (!resp.isSuccessful) return "Error: amplifierd HTTP ${resp.code}"
                parseAmplifierdResponse(resp.body!!.string())
            }
        }

        private fun createSession(node: SshNode): String? = try {
            val body = JSONObject().put("bundle_name", JSONObject.NULL).toString()
                .toRequestBody(JSON_MT)
            val req = Request.Builder()
                .url("${node.url}/sessions").post(body).applyToken(node).build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) { Log.w(TAG, "createSession ${resp.code}"); null }
                else JSONObject(resp.body!!.string()).getString("session_id")
            }
        } catch (e: Exception) { Log.e(TAG, "createSession", e); null }

        private fun parseAmplifierdResponse(json: String): String = try {
            val o = JSONObject(json)
            listOf("text", "response", "content", "output")
                .map { o.optString(it) }.firstOrNull { it.isNotBlank() } ?: json
        } catch (_: Exception) { json }

        private fun Request.Builder.applyToken(node: SshNode) = apply {
            if (node.token.isNotBlank()) addHeader("x-amplifier-token", node.token)
        }
    }
    