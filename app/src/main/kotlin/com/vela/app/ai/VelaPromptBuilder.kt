package com.vela.app.ai

/**
 * Builds the on-device prompt for Gemma 4 E2B (nano-fast) via ML Kit GenAI.
 *
 * ML Kit Preview constraints honoured here:
 * - No system prompt support → role prefix injected as user-turn text (standard workaround)
 * - 4000 token input limit → user input hard-capped at [MAX_USER_CHARS]
 * - No structured output → JSON-in-prompt pattern guides optional Vela-UI output
 *
 * Vela-UI JSON is a compact A2UI v0.8-inspired schema understood by [VelaUiParser].
 * The model defaults to plain text for conversational answers; structured JSON is opt-in.
 *
 * A2UI spec: https://a2ui.org/specifications/v0.8/
 * Official Jetpack Compose A2UI renderer is planned for Q2 2025 — Vela-UI bridges the gap.
 */
object VelaPromptBuilder {

    private const val MAX_USER_CHARS = 800

    /**
     * Prefix injected as a user-turn to simulate a system prompt.
     * Kept intentionally short to maximise token budget for the model's response.
     *
     * The Vela-UI format is compact so Gemma 4 E2B can emit it reliably:
     *   t = component type abbreviation (card, step, item, tip, code)
     *   n = step number (steps only)
     *   title/text = human-readable content
     */
    private const val SYSTEM_PREFIX = """[Vela: on-device AI assistant. Respond concisely.
For structured answers (steps, lists, data), you MAY respond with JSON:
{"type":"vela-ui","components":[
{"t":"card","title":"..."},
{"t":"step","n":1,"text":"..."},
{"t":"item","text":"..."},
{"t":"tip","text":"..."},
{"t":"code","text":"..."}
]}
Plain text is preferred for simple conversational answers.]"""

    /** Build the complete prompt for a given [userInput]. */
    fun build(userInput: String): String {
        val safe = userInput.take(MAX_USER_CHARS)
        return "$SYSTEM_PREFIX\n\nUser: $safe\nVela:"
    }
}
