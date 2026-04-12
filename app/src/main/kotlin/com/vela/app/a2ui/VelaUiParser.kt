package com.vela.app.a2ui

import org.json.JSONArray
import org.json.JSONObject

/**
 * Parses Gemma 4's response text into a [VelaUiPayload] when the model chose
 * to respond in the compact Vela-UI JSON format (A2UI-inspired).
 *
 * If the response is plain text, or parsing fails for any reason, [parse] returns null
 * and the caller should fall back to plain-text rendering.
 *
 * JSON shape produced by the model (see [VelaPromptBuilder]):
 * {
 *   "type": "vela-ui",
 *   "components": [
 *     {"t": "card",  "title": "...", "subtitle": "..."},
 *     {"t": "step",  "n": 1,        "text": "..."},
 *     {"t": "item",                 "text": "..."},
 *     {"t": "tip",                  "text": "..."},
 *     {"t": "code",                 "text": "...", "lang": "kotlin"},
 *     {"t": "text",                 "text": "..."}
 *   ]
 * }
 */
object VelaUiParser {

    /**
     * Try to extract a [VelaUiPayload] from [responseText].
     * Returns null if:
     *  - the response is plain text (no JSON object found)
     *  - the JSON is present but malformed
     *  - type != "vela-ui"
     */
    fun parse(responseText: String): VelaUiPayload? {
        return try {
            val start = responseText.indexOf('{')
            val end = responseText.lastIndexOf('}')
            if (start == -1 || end == -1 || end <= start) return null

            val jsonStr = responseText.substring(start, end + 1)
            val root = JSONObject(jsonStr)
            if (root.optString("type") != "vela-ui") return null

            val array: JSONArray = root.optJSONArray("components") ?: return null
            val components = mutableListOf<VelaUiComponent>()

            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val component = parseComponent(obj) ?: continue
                components.add(component)
            }

            if (components.isEmpty()) null else VelaUiPayload(components)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseComponent(obj: JSONObject): VelaUiComponent? {
        return when (obj.optString("t")) {
            "card" -> VelaUiComponent.Card(
                title = obj.optString("title", "").ifBlank { return null },
                subtitle = obj.optString("subtitle").takeIf { it.isNotBlank() },
            )
            "step" -> VelaUiComponent.Step(
                n = obj.optInt("n", 0),
                text = obj.optString("text", "").ifBlank { return null },
            )
            "item" -> VelaUiComponent.Item(
                text = obj.optString("text", "").ifBlank { return null },
            )
            "tip" -> VelaUiComponent.Tip(
                text = obj.optString("text", "").ifBlank { return null },
            )
            "code" -> VelaUiComponent.Code(
                text = obj.optString("text", "").ifBlank { return null },
                lang = obj.optString("lang").takeIf { it.isNotBlank() },
            )
            "text" -> VelaUiComponent.BodyText(
                text = obj.optString("text", "").ifBlank { return null },
            )
            else -> null
        }
    }
}
