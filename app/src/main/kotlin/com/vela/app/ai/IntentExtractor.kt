/**
 * Extracts structured intent from user text using Gemma 4 E2B via ML Kit Preview API.
 *
 * ML Kit Preview Limitations (April 2026):
 * - No system prompt support — user-turn prefix workaround in use
 * - No structured output — JSON-in-prompt pattern
 * - 4000 token input limit — inputs truncated at 500 chars as safety margin
 * - Only English and Korean validated
 * - Tool calling deferred to post-preview GA release
 */
package com.vela.app.ai

import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject

data class VelaIntent(
    val action: String,
    val target: String?,
    val constraints: List<String>,
    val rawText: String,
    val confidence: Float = 1.0f,
)

class IntentExtractor @Inject constructor(private val engine: GemmaEngine) {

    companion object {
        private const val MAX_USER_INPUT_CHARS = 500
    }

    suspend fun extract(userText: String): VelaIntent {
        val prompt = buildPrompt(userText)
        val response = engine.processText(prompt)
        return parseIntent(response, userText)
    }

    internal fun buildPrompt(userText: String): String {
        val truncated = if (userText.length > MAX_USER_INPUT_CHARS) {
            userText.take(MAX_USER_INPUT_CHARS)
        } else {
            userText
        }
        return "[Task: Extract intent as JSON. No prose, only valid JSON.]\n\nUser said: \"$truncated\"\n\nJSON response:"
    }

    internal fun parseIntent(response: String, originalText: String): VelaIntent {
        return try {
            val startIndex = response.indexOf('{')
            val endIndex = response.lastIndexOf('}')
            if (startIndex == -1 || endIndex == -1 || startIndex > endIndex) {
                return fallbackIntent(originalText)
            }
            val jsonString = response.substring(startIndex, endIndex + 1)
            val json = JSONObject(jsonString)
            val action = json.optString("action", "unknown")
            val target = if (!json.has("target") || json.isNull("target")) {
                null
            } else {
                json.getString("target")
            }
            val constraints = parseConstraints(json)
            val rawText = json.optString("rawText", originalText)
            VelaIntent(
                action = action,
                target = target,
                constraints = constraints,
                rawText = rawText,
            )
        } catch (e: Exception) {
            // Intentionally broad: LLM response may not contain valid JSON
            fallbackIntent(originalText)
        }
    }

    internal fun parseConstraints(json: JSONObject): List<String> {
        val array: JSONArray = json.optJSONArray("constraints") ?: return emptyList()
        return (0 until array.length()).map { array.getString(it) }
    }

    private fun fallbackIntent(originalText: String) = VelaIntent(
        action = "unknown",
        target = null,
        constraints = emptyList(),
        rawText = originalText,
    )
}
