package com.vela.app.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Extracts structured semantic data from raw markdown content using the skill's
 * extractorPrompt and schema as a guide for the LLM.
 *
 * The result is a JSON object that the mini app's Lit components can consume
 * directly — no further parsing needed in JavaScript.
 */
@Singleton
class ContentExtractor @Inject constructor(
    private val amplifierSession: AmplifierSession,
) {
    /**
     * Extracts structured data from [rawMarkdown] according to [skill]'s schema.
     *
     * Returns the extracted JSON string on success, or null if extraction fails or
     * the skill has no extractor defined.
     */
    suspend fun extract(rawMarkdown: String, skill: SkillLibrary.Skill): String? {
        val prompt = skill.extractorPrompt ?: return null
        val schema = skill.schema          ?: return null

        return withContext(Dispatchers.IO) {
            // Truncate to 8 KB — enough for extraction, avoids overwhelming the context
            val safeMarkdown = rawMarkdown.take(8_192)

            val fullPrompt = buildString {
                appendLine("## Content to extract from")
                appendLine("```markdown")
                appendLine(safeMarkdown)
                appendLine("```")
                appendLine()
                appendLine("## Schema to populate")
                appendLine("```json")
                appendLine(schema)
                appendLine("```")
                appendLine()
                appendLine("## Extraction instructions")
                appendLine(prompt)
                appendLine()
                appendLine("Return ONLY valid JSON that exactly matches the schema above. No explanation, no markdown fences.")
            }

            try {
                val sb = StringBuilder()
                amplifierSession.runTurn(
                    historyJson       = "[]",
                    userInput         = fullPrompt,
                    userContentJson   = null,
                    systemPrompt      = "You are a structured data extractor. Return only valid JSON. No explanation.",
                    onToolStart       = { _, _ -> "" },
                    onToolEnd         = { _, _ -> },
                    onToken           = { sb.append(it) },
                    onProviderRequest = { null },
                    onServerTool      = { _, _ -> },
                )
                val raw = sb.toString().trim()
                    .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                // Validate it's parseable JSON
                JSONObject(raw)
                raw
            } catch (e: Exception) {
                android.util.Log.w("ContentExtractor", "Extraction failed for ${skill.id}: ${e.message}")
                null
            }
        }
    }
}
