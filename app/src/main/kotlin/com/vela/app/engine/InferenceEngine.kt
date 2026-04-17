package com.vela.app.engine

    import android.content.Context
    import android.util.Log
    import com.vela.app.ai.tools.TodoTool
    import com.vela.app.ai.tools.ToolRegistry
    import com.vela.app.data.db.TurnDao
    import com.vela.app.data.db.TurnEventDao
    import com.vela.app.data.db.TurnEntity
    import com.vela.app.data.db.TurnEventEntity
    import com.vela.app.hooks.HookContext
    import com.vela.app.hooks.HookEvent
    import com.vela.app.hooks.HookRegistry
    import com.vela.app.hooks.HookResult
    import dagger.hilt.android.qualifiers.ApplicationContext
    import kotlinx.coroutines.CoroutineScope
    import kotlinx.coroutines.Dispatchers
    import kotlinx.coroutines.Job
    import kotlinx.coroutines.SupervisorJob
    import kotlinx.coroutines.flow.MutableSharedFlow
    import kotlinx.coroutines.flow.MutableStateFlow
    import kotlinx.coroutines.flow.SharedFlow
    import kotlinx.coroutines.flow.StateFlow
    import kotlinx.coroutines.flow.asSharedFlow
    import kotlinx.coroutines.flow.asStateFlow
    import kotlinx.coroutines.flow.update
    import kotlinx.coroutines.launch
    import org.json.JSONArray
    import org.json.JSONObject
    import java.util.UUID
    import java.util.concurrent.ConcurrentHashMap
    import java.util.concurrent.atomic.AtomicInteger
    import javax.inject.Inject
    import javax.inject.Singleton

    private const val TAG = "InferenceEngine"
    private const val MAX_RECENT_TOOLS = 5

    /**
     * Owns all AI inference. Completely independent of any ViewModel or UI lifecycle.
     *
     * HOOK LIFECYCLE (mirrors loop-streaming):
     *
     *   TURN_START         — fires once at the start of every user turn
     *   PROVIDER_REQUEST   — fires before each LLM call within the agent loop
     *                        (via onProviderRequest callback to Rust)
     *   TOOL_PRE           — fires before each tool execution; Deny blocks the call
     *   TOOL_POST          — fires after each tool; InjectContext queued for next LLM call
     *   TURN_END           — fires once when the turn completes
     *
     * INTERLEAVING DESIGN:
     *
     * The Anthropic content array may look like:
     *   [{type:text, text:"I'll search..."}, {type:tool_use, ...}, {type:text, text:"Based on..."}]
     *
     * We produce TurnEvents in the order events arrive:
     *
     *   seq=0  text   "I'll search..."     ← flushed when tool starts
     *   seq=1  tool   search_web  running  ← inserted when tool starts
     *   seq=1  tool   search_web  done     ← updated in-place (same row, same seq)
     *   seq=2  text   "Based on results…"  ← flushed at turn end
     */
    @Singleton
    class InferenceEngine @Inject constructor(
        @ApplicationContext private val context: Context,
        private val session: InferenceSession,
        private val toolRegistry: ToolRegistry,
        private val hookRegistry: HookRegistry,
        private val turnDao: TurnDao,
        private val turnEventDao: TurnEventDao,
        private val conversationDao: com.vela.app.data.db.ConversationDao,
        private val vaultRegistry: com.vela.app.vault.VaultRegistry,
        private val vaultManager: com.vela.app.vault.VaultManager,
        private val harness: com.vela.app.harness.SessionHarness,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private val activeJobs = ConcurrentHashMap<String, Job>()

        private val _streamingText = MutableStateFlow<Map<String, String>>(emptyMap())
        val streamingText: StateFlow<Map<String, String>> = _streamingText.asStateFlow()

        private val _turnComplete = MutableSharedFlow<String>(extraBufferCapacity = 16)
        val turnComplete: SharedFlow<String> = _turnComplete.asSharedFlow()

        // ── Public API ─────────────────────────────────────────────────────────────

        fun startTurn(
            conversationId: String,
            userMessage: String,
            userContentJson: String? = null,
            apiContentJson: String? = null,
            activeVaultIds: Set<String> = emptySet(),
        ): String {
            val turnId = UUID.randomUUID().toString()
            val job = scope.launch {
                try {
                    turnDao.insert(TurnEntity(
                        id              = turnId,
                        conversationId  = conversationId,
                        userMessage     = userMessage,
                        userContentJson = userContentJson,
                        status          = "running",
                        timestamp       = System.currentTimeMillis(),
                    ))
                    processTurn(turnId, conversationId, userMessage, apiContentJson, activeVaultIds)
                    turnDao.updateStatus(turnId, "complete")
                } catch (e: Exception) {
                    Log.e(TAG, "Turn $turnId failed", e)
                    turnDao.updateStatus(turnId, "error", e.message?.take(200))
                    turnEventDao.insert(TurnEventEntity(
                        id     = UUID.randomUUID().toString(),
                        turnId = turnId,
                        seq    = 0,
                        type   = "text",
                        text   = "Error: ${e.message?.take(200) ?: "unknown"}",
                    ))
                } finally {
                    _streamingText.update { it - turnId }
                    _turnComplete.emit(turnId)
                    activeJobs.remove(turnId)
                }
            }
            activeJobs[turnId] = job
            return turnId
        }

        fun isRunning(turnId: String): Boolean = activeJobs.containsKey(turnId)

        // ── Inference ──────────────────────────────────────────────────────────────

        private suspend fun processTurn(
            turnId: String,
            conversationId: String,
            userMessage: String,
            apiContentJson: String? = null,
            activeVaultIds: Set<String> = emptySet(),
        ) {
            val seq        = AtomicInteger(0)
            val textBuffer = StringBuilder()

            // Per-turn hook state ── reset fresh for each turn
            val recentToolNames            = ArrayDeque<String>()            // ring buffer, max MAX_RECENT_TOOLS
            val pendingEphemeralInjections = mutableListOf<String>()         // TOOL_POST → queued for next LLM call
            val toolNameByEventId          = mutableMapOf<String, String>()  // track tool name per eventId

            fun flushText() {
                val text = textBuffer.toString().trim()
                if (text.isNotEmpty()) {
                    scope.launch {
                        turnEventDao.insert(TurnEventEntity(
                            id     = UUID.randomUUID().toString(),
                            turnId = turnId,
                            seq    = seq.getAndIncrement(),
                            type   = "text",
                            text   = text,
                        ))
                    }
                    textBuffer.clear()
                    _streamingText.update { it - turnId }
                }
            }

            val historyJson = buildHistory(conversationId)

            val allEnabled = vaultRegistry.getEnabledVaults()
            val vaultsForSession = allEnabled.filter { it.id in activeVaultIds }
            val systemPrompt = harness.buildSystemPrompt(conversationId, vaultsForSession)

            val sessionCanonicalPaths = vaultsForSession.mapNotNull { v ->
                runCatching { java.io.File(v.localPath).canonicalPath }.getOrNull()
            }.toSet()
            vaultManager.setSessionVaultPaths(sessionCanonicalPaths)

            val effectiveUserInput = if (userMessage.isBlank() && !apiContentJson.isNullOrBlank()) {
                "(see attached)"
            } else {
                userMessage
            }

            // ── TURN_START hook ────────────────────────────────────────────────────
            val turnCtx = HookContext(
                conversationId = conversationId,
                activeVaults   = vaultsForSession,
                event          = HookEvent.TURN_START,
                metadata       = mapOf("turnId" to turnId),
            )
            hookRegistry.fire(HookEvent.TURN_START, turnCtx)

            try { session.runTurn(
                historyJson     = historyJson,
                userInput       = effectiveUserInput,
                userContentJson = apiContentJson,
                systemPrompt    = systemPrompt,

                onToken = { token ->
                    textBuffer.append(token)
                    _streamingText.update { map -> map + (turnId to textBuffer.toString()) }
                },

                onToolStart = { name, argsJson ->
                    flushText()

                    // Track for PROVIDER_REQUEST reminder hooks
                    recentToolNames.addLast(name)
                    while (recentToolNames.size > MAX_RECENT_TOOLS) recentToolNames.removeFirst()

                    // ── TOOL_PRE hook ──────────────────────────────────────────────
                    val preCtx = HookContext(
                        conversationId = conversationId,
                        activeVaults   = vaultsForSession,
                        event          = HookEvent.TOOL_PRE,
                        metadata       = mapOf("toolName" to name, "toolArgsJson" to argsJson),
                    )
                    val denyReason = hookRegistry.checkToolPre(preCtx)

                    val eventId = UUID.randomUUID().toString()
                    val tool    = toolRegistry.find(name)
                    val summary = extractSummary(name, argsJson)

                    toolNameByEventId[eventId] = name   // track for TOOL_POST hook

                    turnEventDao.insert(TurnEventEntity(
                        id              = eventId,
                        turnId          = turnId,
                        seq             = seq.getAndIncrement(),
                        type            = "tool",
                        toolName        = name,
                        toolDisplayName = tool?.displayName ?: name,
                        toolIcon        = tool?.icon ?: "🔧",
                        toolSummary     = summary,
                        toolArgs        = argsJson,
                        toolStatus      = if (denyReason != null) "denied" else "running",
                    ))
                    Log.d(TAG, "Tool ${if (denyReason != null) "denied" else "started"}: $name seq=${seq.get()-1}")

                    // Encode deny reason into the stableId so onToolEnd can handle it
                    if (denyReason != null) "__denied__:$eventId:$denyReason" else eventId
                },

                onToolEnd = { stableId, result ->
                    if (stableId.startsWith("__denied__:")) {
                        // Denied by TOOL_PRE hook — update status, skip TOOL_POST
                        val parts   = stableId.removePrefix("__denied__:").split(":", limit = 2)
                        val eventId = parts[0]
                        turnEventDao.updateEvent(id = eventId, status = "denied", result = parts.getOrNull(1)?.take(500))
                    } else {
                        // Normal tool completion
                        turnEventDao.updateEvent(id = stableId, status = "done", result = result.take(500))
                        Log.d(TAG, "Tool done: $stableId")

                        // ── TOOL_POST hook ─────────────────────────────────────────
                        val toolName = toolNameByEventId.remove(stableId) ?: ""
                        val postCtx = HookContext(
                            conversationId = conversationId,
                            activeVaults   = vaultsForSession,
                            event          = HookEvent.TOOL_POST,
                            metadata       = mapOf(
                                "toolName"   to toolName,
                                "toolResult" to result,
                            ),
                        )
                        hookRegistry.fire(HookEvent.TOOL_POST, postCtx)
                            .filterIsInstance<HookResult.InjectContext>()
                            .filter { it.content.isNotBlank() }
                            .forEach { pendingEphemeralInjections += it.content }
                    }
                },

                onServerTool = { name, argsJson ->
                    // Server tool ran on Anthropic's backend — create a TurnEvent for UI display
                    val eventId = UUID.randomUUID().toString()
                    val tool    = toolRegistry.find(name)
                    val summary = extractSummary(name, argsJson)
                    turnEventDao.insert(TurnEventEntity(
                        id              = eventId,
                        turnId          = turnId,
                        seq             = seq.getAndIncrement(),
                        type            = "tool",
                        toolName        = name,
                        toolDisplayName = tool?.displayName ?: "Web Search",
                        toolIcon        = tool?.icon ?: "🔍",
                        toolSummary     = summary,
                        toolArgs        = argsJson,
                        toolStatus      = "done",
                    ))
                },

                onProviderRequest = {
                    // ── PROVIDER_REQUEST hook ─────────────────────────────────────
                    // Build context including current todo state + recent tools
                    val currentTodos = (toolRegistry.find("todo") as? TodoTool)
                        ?.getFormattedState() ?: ""
                    val provCtx = HookContext(
                        conversationId = conversationId,
                        activeVaults   = vaultsForSession,
                        event          = HookEvent.PROVIDER_REQUEST,
                        metadata       = mapOf(
                            "recentToolNames" to recentToolNames.toList(),
                            "currentTodos"    to currentTodos,
                        ),
                    )

                    // Collect: pending TOOL_POST injections + PROVIDER_REQUEST hook injections
                    val hookInjection = hookRegistry.collectEphemeralInjection(HookEvent.PROVIDER_REQUEST, provCtx)
                    val parts = (pendingEphemeralInjections + listOfNotNull(hookInjection)).filter { it.isNotBlank() }
                    pendingEphemeralInjections.clear()

                    parts.joinToString("\n\n").takeIf { it.isNotBlank() }
                },
            )

            flushText()
            } finally {
                vaultManager.clearSessionVaultPaths()

                // ── TURN_END hook ──────────────────────────────────────────────────
                hookRegistry.fire(HookEvent.TURN_END, turnCtx.copy(event = HookEvent.TURN_END))
            }
        }

        // ── History ────────────────────────────────────────────────────────────────

        /**
         * Prefix every historical user message with its send time so the model
         * knows WHEN a message was sent, not just what was said.
         *
         * Content can be a plain String or a JSONArray (content blocks with images/docs).
         * Both cases get a leading "[2026-04-15 14:30]" marker.
         */
        private fun withTimestamp(content: Any, epochMs: Long): Any {
            val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                .format(java.util.Date(epochMs))
            return when (content) {
                // <vela-meta> is invisible in the UI (stripped before rendering)
                    // but fully visible to the LLM in the raw message content.
                    is String    -> "<vela-meta>$ts</vela-meta>\n\n$content"
                    is JSONArray -> {
                        val stamped = JSONArray()
                        stamped.put(JSONObject().put("type", "text").put("text", "<vela-meta>$ts</vela-meta>\n\n"))
                    repeat(content.length()) { i -> stamped.put(content.get(i)) }
                    stamped
                }
                else -> content
            }
        }

        private suspend fun buildHistory(conversationId: String): String {
            val arr            = JSONArray()
            val completedTurns = turnDao.getCompletedTurnsWithEvents(conversationId)

            for (twe in completedTurns) {
                val userContent: Any = when {
                    !twe.turn.userContentJson.isNullOrBlank() -> {
                        val refs = runCatching { parseContentBlockRefs(twe.turn.userContentJson) }
                            .getOrNull()
                            .orEmpty()
                        if (refs.isNotEmpty()) {
                            val resolved = resolveContentBlockRefs(context, refs)
                            if (resolved.size > 1 || resolved.any { it !is ContentBlock.Text }) {
                                JSONArray(resolved.map { it.toApiJson() })
                            } else {
                                twe.turn.userMessage
                            }
                        } else {
                            twe.turn.userMessage
                        }
                    }
                    else -> twe.turn.userMessage
                }
                arr.put(JSONObject().put("role", "user").put("content",
                    withTimestamp(userContent, twe.turn.timestamp)))

                // Build assistant message — include a synthetic summary for tool-only turns
                // so multi-turn conversations don't lose context about completed work.
                val assistantText = twe.sortedEvents
                    .filter { it.type == "text" && !it.text.isNullOrBlank() }
                    .joinToString("\n") { it.text!! }

                val toolEvents = twe.sortedEvents.filter { it.type == "tool" }

                val messageForHistory = when {
                    assistantText.isNotBlank() -> assistantText
                    toolEvents.isNotEmpty()    -> {
                        // Synthetic summary so the assistant's tool work is visible in history
                        val toolList = toolEvents
                            .mapNotNull { it.toolDisplayName ?: it.toolName }
                            .distinct()
                            .joinToString(", ")
                        "[Used tools: $toolList]"
                    }
                    else -> null
                }

                if (messageForHistory != null) {
                    arr.put(JSONObject().put("role", "assistant").put("content", messageForHistory))
                }
            }
            return arr.toString()
        }

        // ── Helpers ────────────────────────────────────────────────────────────────

        private fun extractSummary(name: String, argsJson: String) = try {
            val obj = JSONObject(argsJson)
            sequenceOf("query", "url", "location", "command", "expression", "file_path", "pattern", "action", "skill_name")
                .mapNotNull { obj.optString(it).takeIf { v -> v.isNotBlank() } }
                .firstOrNull() ?: name
        } catch (e: Exception) { name }
    }
    