package com.vela.app.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Detects the semantic archetype of vault item content using a fast LLM call.
 *
 * Uses only the first 500 chars for speed (~1-2 second LLM round-trip).
 * Falls back to the provided contentType hint on any error.
 */
@Singleton
class ArchetypeDetector @Inject constructor(
    private val amplifierSession: AmplifierSession,
) {
    data class DetectionResult(
        val archetype: String,
        val confidence: Float,
        val displayLabel: String,
    )

    private val knownArchetypes = setOf(
        "recipe", "cooking", "meal",
        "meeting", "standup", "notes",
        "journal", "diary", "daily",
        "task", "project", "kanban", "todo",
        "book", "reading", "literature",
        "security", "oauth", "alert", "incident",
        "finance", "budget", "expense",
        "contact", "person", "crm",
        "travel", "trip", "itinerary",
        "research", "paper", "article",
        "health", "fitness", "workout",
    )

    /**
     * Detects the archetype of [content]. Falls back to [contentType] on error.
     */
    suspend fun detect(contentType: String, content: String): DetectionResult =
        withContext(Dispatchers.IO) {
            val snippet = content.take(500).replace("\"", "'")
            val prompt = buildString {
                appendLine("Content type hint: \"$contentType\"")
                appendLine("Content preview:")
                appendLine(snippet)
                appendLine()
                appendLine("Classify this content. Pick the most specific archetype from: ${knownArchetypes.joinToString()}")
                append("Return ONLY JSON: {\"archetype\": \"tag\", \"confidence\": 0.0_to_1.0, \"label\": \"human readable name\"}")
            }
            try {
                val sb = StringBuilder()
                amplifierSession.runTurn(
                    historyJson       = "[]",
                    userInput         = prompt,
                    userContentJson   = null,
                    systemPrompt      = "You are a content type classifier. Return only valid JSON.",
                    onToolStart       = { _, _ -> "" },
                    onToolEnd         = { _, _ -> },
                    onToken           = { sb.append(it) },
                    onProviderRequest = { null },
                    onServerTool      = { _, _ -> },
                )
                val raw  = sb.toString().trim()
                    .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                val json = JSONObject(raw)
                DetectionResult(
                    archetype    = json.optString("archetype", contentType),
                    confidence   = json.optDouble("confidence", 0.5).toFloat(),
                    displayLabel = json.optString("label", contentType),
                )
            } catch (e: Exception) {
                DetectionResult(archetype = contentType, confidence = 0.5f, displayLabel = contentType)
            }
        }
}
