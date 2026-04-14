package com.vela.app.engine

import android.util.Log
import com.vela.app.data.db.TurnDao
import com.vela.app.data.db.TurnEventDao
import com.vela.app.data.db.TurnEntity
import com.vela.app.data.db.TurnEventEntity
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

/**
 * Owns all AI inference. Completely independent of any ViewModel or UI lifecycle.
 *
 * INTERLEAVING DESIGN:
 *
 * The Anthropic content array may look like:
 *   [{type:text, text:"I'll search..."}, {type:tool_use, ...}, {type:text, text:"Based on..."}]
 *
 * The Rust orchestrator already emits text before tools (via emit_token / preamble),
 * then calls executeTool per tool block, then emits the final text on the next round.
 *
 * We produce TurnEvents in the order events arrive:
 *
 *   seq=0  text   "I'll search..."     ← flushed when tool starts
 *   seq=1  tool   search_web  running  ← inserted when tool starts
 *   seq=1  tool   search_web  done     ← updated in-place (same row, same seq)
 *   seq=2  text   "Based on results…"  ← flushed at turn end
 *
 * KEY INVARIANT: text that arrives BEFORE a tool call is flushed to DB
 * (with the next available seq) immediately when [onToolStart] fires.
 * This guarantees correct interleaving — text seq < adjacent tool seq.
 *
 * The streaming text StateFlow carries ONLY uncommitted in-flight text.
 * When text is flushed to DB it is removed from the streaming map.
 */
@Singleton
class InferenceEngine @Inject constructor(
    private val session: InferenceSession,
    private val toolRegistry: com.vela.app.ai.tools.ToolRegistry,
    private val turnDao: TurnDao,
    private val turnEventDao: TurnEventDao,
    private val conversationDao: com.vela.app.data.db.ConversationDao,
    private val vaultRegistry: com.vela.app.vault.VaultRegistry,
    private val harness: com.vela.app.harness.SessionHarness,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val activeJobs = ConcurrentHashMap<String, Job>()

    /**
     * In-flight streaming text per turn — ONLY text not yet committed to DB.
     * Cleared (entry removed) when text is flushed to a TurnEvent row.
     * The streaming bubble in the UI should show this AND disappear when it's in DB.
     */
    private val _streamingText = MutableStateFlow<Map<String, String>>(emptyMap())
    val streamingText: StateFlow<Map<String, String>> = _streamingText.asStateFlow()

    private val _turnComplete = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val turnComplete: SharedFlow<String> = _turnComplete.asSharedFlow()

    // ── Public API ────────────────────────────────────────────────────────────

    fun startTurn(conversationId: String, userMessage: String): String {
        val turnId = UUID.randomUUID().toString()
        val job = scope.launch {
            try {
                turnDao.insert(TurnEntity(
                    id             = turnId,
                    conversationId = conversationId,
                    userMessage    = userMessage,
                    status         = "running",
                    timestamp      = System.currentTimeMillis(),
                ))
                processTurn(turnId, conversationId, userMessage)
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

    // ── Inference ─────────────────────────────────────────────────────────────

    private suspend fun processTurn(turnId: String, conversationId: String, userMessage: String) {
        val seq       = AtomicInteger(0)
        val textBuffer = StringBuilder()   // uncommitted text since last flush

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
                // Remove from streaming map — it's now in DB
                _streamingText.update { it - turnId }
            }
        }

        val historyJson = buildHistory(conversationId)

        val systemPrompt = if (!harness.isInitialized(conversationId)) {
            harness.buildSystemPrompt(conversationId, vaultRegistry.getEnabledVaults())
        } else {
            ""
        }

        session.runTurn(
            historyJson  = historyJson,
            userInput    = userMessage,
            systemPrompt = systemPrompt,

            onToken = { token ->
                textBuffer.append(token)
                // Show uncommitted text in streaming bubble
                _streamingText.update { map ->
                    map + (turnId to textBuffer.toString())
                }
            },

            onToolStart = { name, argsJson ->
                // FLUSH any accumulated text BEFORE the tool — this preserves
                // the order: preamble text (seq N) < tool (seq N+1)
                flushText()

                val eventId = UUID.randomUUID().toString()
                val tool    = toolRegistry.find(name)
                val summary = extractSummary(name, argsJson)

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
                    toolStatus      = "running",
                ))
                Log.d(TAG, "Tool started: $name seq=${seq.get()-1}")
                eventId
            },

            onToolEnd = { eventId, result ->
                // Same row, same seq — just flip the status in-place
                turnEventDao.updateEvent(
                    id     = eventId,
                    status = "done",
                    result = result.take(500),
                )
                Log.d(TAG, "Tool done: $eventId")
            },
        )

        // Flush whatever text arrived after the last tool call (or the only response)
        flushText()
    }

    // ── History ───────────────────────────────────────────────────────────────

    private suspend fun buildHistory(conversationId: String): String {
        val arr           = JSONArray()
        val completedTurns = turnDao.getCompletedTurnsWithEvents(conversationId)

        for (twe in completedTurns) {
            arr.put(JSONObject().put("role", "user").put("content", twe.turn.userMessage))

            val assistantText = twe.sortedEvents
                .filter { it.type == "text" && !it.text.isNullOrBlank() }
                .joinToString("\n") { it.text!! }

            if (assistantText.isNotBlank()) {
                arr.put(JSONObject().put("role", "assistant").put("content", assistantText))
            }
        }
        return arr.toString()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun extractSummary(name: String, argsJson: String) = try {
        val obj = JSONObject(argsJson)
        sequenceOf("query", "url", "location", "command", "expression", "file_path", "pattern", "action", "skill_name")
            .mapNotNull { obj.optString(it).takeIf { v -> v.isNotBlank() } }
            .firstOrNull() ?: name
    } catch (e: Exception) { name }
}
