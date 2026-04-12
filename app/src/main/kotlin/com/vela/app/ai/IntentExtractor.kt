package com.vela.app.ai

import org.json.JSONArray
import org.json.JSONObject

data class VelaIntent(
    val action: String,
    val target: String?,
    val constraints: List<String>,
    val rawText: String,
)

class IntentExtractor(private val engine: GemmaEngine) {

    suspend fun extract(userText: String): VelaIntent {
        val prompt = buildPrompt(userText)
        val response = engine.processText(prompt)
        return parseIntent(response, userText)
    }

    internal fun buildPrompt(userText: String): String =
        """
        Extract the user's intent from the following text and return it as JSON.

        User text: "$userText"

        Return a JSON object with this exact format:
        {
          "action": "the main action or verb",
          "target": "the target or object (or null if none)",
          "constraints": ["constraint1", "constraint2"],
          "rawText": "the original user text"
        }
        """.trimIndent()

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
