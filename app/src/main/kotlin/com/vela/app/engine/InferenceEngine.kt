package com.vela.app.engine

import android.util.Log
import com.vela.app.ai.AmplifierSession
import com.vela.app.ai.tools.ToolRegistry
import com.vela.app.data.db.TurnDao
import com.vela.app.data.db.TurnEventDao
import com.vela.app.data.db.TurnEntity
import com.vela.app.data.db.TurnEventEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
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
 * Owns all AI inference. Lives as a process-scoped singleton — completely
 * independent of any ViewModel or Activity lifecycle.
 *
 * When the user sends a message:
 *   1. [startTurn] creates a Turn row in DB and returns immediately.
 *   2. A coroutine in [scope] runs the inference on Dispatchers.IO.
 *   3. Tool events are written to turn_events as they start (status="running")
 *      and updated in-place when they finish (status="done").
 *   4. The final text is written as a text TurnEvent after all tools.
 *   5. [streamingText] emits (turnId, token) for the live streaming indicator.
 *   6. [turnComplete] emits turnId when done so the ViewModel can clear the indicator.
 *
 * The UI reads only from Room (reactive Flows). It never touches this scope.
 */
@Singleton
class InferenceEngine @Inject constructor(
    private val session: AmplifierSession,
    private val toolRegistry: ToolRegistry,
    private val turnDao: TurnDao,
    private val turnEventDao: TurnEventDao,
) {
    /** Scope outlives any ViewModel. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val activeJobs = ConcurrentHashMap<String, Job>()

    /** (turnId, textChunk) — UI accumulates these into the streaming bubble. */
    private val _streamingText = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 128)
    val streamingText: SharedFlow<Pair<String, String>> = _streamingText

    /** Emits turnId when inference ends (success or error). */
    private val _turnComplete = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val turnComplete: SharedFlow<String> = _turnComplete

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
                // Emit error as a text event so it appears in the conversation
                turnEventDao.insert(TurnEventEntity(
                    id     = UUID.randomUUID().toString(),
                    turnId = turnId,
                    seq    = 0,
                    type   = "text",
                    text   = "Error: ${e.message?.take(200) ?: "unknown"}",
                ))
            } finally {
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
        val seq = AtomicInteger(0)
        val historyJson = buildHistory(conversationId)
        Log.d(TAG, "processTurn turnId=$turnId historyLen=${historyJson.length}")

        val sb = StringBuilder()

        session.runTurn(
            historyJson = historyJson,
            userInput   = userMessage,
            onToolStart = { name, argsJson ->
                val eventId = UUID.randomUUID().toString()
                val tool    = toolRegistry.find(name)
                val summary = extractSummary(name, argsJson)

                // INSERT immediately — visible in UI before tool even executes
                turnEventDao.insert(TurnEventEntity(
                    id              = eventId,
                    turnId          = turnId,
                    seq             = seq.getAndIncrement(),  // ← integer, never a timestamp
                    type            = "tool",
                    toolName        = name,
                    toolDisplayName = tool?.displayName ?: name,
                    toolIcon        = tool?.icon ?: "🔧",
                    toolSummary     = summary,
                    toolArgs        = argsJson,
                    toolStatus      = "running",
                ))

                Log.d(TAG, "Tool started: $name seq=${seq.get()-1} eventId=$eventId")
                eventId // caller-assigned stable ID returned to onToolEnd
            },
            onToolEnd = { eventId, result ->
                // UPDATE the same row, same seq — never moves in the UI
                turnEventDao.updateEvent(
                    id     = eventId,
                    status = "done",
                    result = result.take(500),
                )
                Log.d(TAG, "Tool done: eventId=$eventId")
            },
            onToken = { token ->
                sb.append(token)
                _streamingText.emit(Pair(turnId, token))
            },
        )

        val finalText = sb.toString().trim()
        if (finalText.isNotEmpty()) {
            turnEventDao.insert(TurnEventEntity(
                id     = UUID.randomUUID().toString(),
                turnId = turnId,
                seq    = seq.getAndIncrement(),  // always AFTER all tool seqs
                type   = "text",
                text   = finalText,
            ))
        }
    }

    // ── History ───────────────────────────────────────────────────────────────

    /**
     * Build the Anthropic message history JSON from completed prior turns.
     *
     * Each completed turn contributes:
     *   {role:"user", content: userMessage}
     *   {role:"assistant", content: <assembled from text events in that turn>}
     *
     * This gives the model correct context of the conversation so far.
     */
    private suspend fun buildHistory(conversationId: String): String {
        val arr = JSONArray()
        val completedTurns = turnDao.getCompletedTurns(conversationId)

        for (turn in completedTurns) {
            // User message
            arr.put(JSONObject().put("role", "user").put("content", turn.userMessage))

            // Collect the text events from this turn to form the assistant message
            val events = turnEventDao.getEventsForTurn(turn.id).first()
            val assistantText = events
                .filter { it.type == "text" && !it.text.isNullOrBlank() }
                .joinToString("\n") { it.text!! }

            if (assistantText.isNotBlank()) {
                arr.put(JSONObject().put("role", "assistant").put("content", assistantText))
            }
        }

        return arr.toString()
    }

    private fun extractSummary(name: String, argsJson: String) = try {
        val obj = JSONObject(argsJson)
        sequenceOf("query", "url", "location", "command", "expression")
            .mapNotNull { obj.optString(it).takeIf { v -> v.isNotBlank() } }
            .firstOrNull() ?: name
    } catch (e: Exception) { name }
}
