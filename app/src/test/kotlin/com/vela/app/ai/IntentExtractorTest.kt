package com.vela.app.ai

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class IntentExtractorTest {

    private class ConfigurableFakeGemmaEngine : GemmaEngine {
        var response: String = ""
        var lastInput: String = ""

        override suspend fun processText(input: String): String {
            lastInput = input
            return response
        }
    }

    @Test
    fun extractParsesValidJson() = runTest {
        val engine = ConfigurableFakeGemmaEngine()
        engine.response = """{"action": "play", "target": "music", "constraints": [], "rawText": "play music"}"""
        val extractor = IntentExtractor(engine)

        val intent = extractor.extract("play music")

        assertThat(intent.action).isEqualTo("play")
        assertThat(intent.target).isEqualTo("music")
        assertThat(intent.constraints).isEmpty()
        assertThat(intent.rawText).isEqualTo("play music")
    }

    @Test
    fun extractParsesJsonWithConstraints() = runTest {
        val engine = ConfigurableFakeGemmaEngine()
        engine.response =
            """{"action": "search", "target": "restaurants", "constraints": ["nearby", "open now"], "rawText": "find nearby open restaurants"}"""
        val extractor = IntentExtractor(engine)

        val intent = extractor.extract("find nearby open restaurants")

        assertThat(intent.action).isEqualTo("search")
        assertThat(intent.constraints).hasSize(2)
        assertThat(intent.constraints).containsExactly("nearby", "open now")
    }

    @Test
    fun extractHandlesNullTarget() = runTest {
        val engine = ConfigurableFakeGemmaEngine()
        engine.response = """{"action": "help", "target": null, "constraints": [], "rawText": "help me"}"""
        val extractor = IntentExtractor(engine)

        val intent = extractor.extract("help me")

        assertThat(intent.action).isEqualTo("help")
        assertThat(intent.target).isNull()
    }

    @Test
    fun extractFallsBackOnMalformedJson() = runTest {
        val engine = ConfigurableFakeGemmaEngine()
        engine.response = "This is not JSON"
        val extractor = IntentExtractor(engine)

        val intent = extractor.extract("play music")

        assertThat(intent.action).isEqualTo("unknown")
        assertThat(intent.target).isNull()
        assertThat(intent.constraints).isEmpty()
        assertThat(intent.rawText).isEqualTo("play music")
    }

    @Test
    fun extractFallsBackOnPartialJson() = runTest {
        val engine = ConfigurableFakeGemmaEngine()
        engine.response = """{"action": "play"}"""
        val extractor = IntentExtractor(engine)

        val intent = extractor.extract("play something")

        assertThat(intent.action).isEqualTo("play")
        assertThat(intent.target).isNull()
        assertThat(intent.constraints).isEmpty()
        assertThat(intent.rawText).isEqualTo("play something")
    }

    @Test
    fun promptUsesUserTurnPrefixNotSystemRole() = runTest {
        val engine = ConfigurableFakeGemmaEngine()
        engine.response = """{"action": "unknown", "target": null, "constraints": [], "rawText": "hello world"}"""
        val extractor = IntentExtractor(engine)

        extractor.extract("hello world")

        // ML Kit Preview workaround: user-turn prefix, no system role
        assertThat(engine.lastInput).contains("Extract intent as JSON")
        assertThat(engine.lastInput).contains("hello world")
        assertThat(engine.lastInput).doesNotContain("system:")
        assertThat(engine.lastInput).doesNotContain("[SYSTEM:")
        assertThat(engine.lastInput).contains("JSON response:")
    }

    @Test
    fun longInputIsTruncatedAt500CharsInPrompt() = runTest {
        val engine = ConfigurableFakeGemmaEngine()
        engine.response = """{"action": "unknown", "target": null, "constraints": [], "rawText": "x"}"""
        val extractor = IntentExtractor(engine)

        val longInput = "a".repeat(600)
        extractor.extract(longInput)

        // The prompt should contain a truncated version, not the full 600-char input
        val truncated = "a".repeat(500)
        assertThat(engine.lastInput).contains(truncated)
        assertThat(engine.lastInput).doesNotContain("a".repeat(501))
    }

    @Test
    fun promptContainsUserTextWithinBrackets() = runTest {
        val engine = ConfigurableFakeGemmaEngine()
        engine.response = """{"action": "unknown", "target": null, "constraints": [], "rawText": "short text"}"""
        val extractor = IntentExtractor(engine)

        extractor.extract("short text")

        // The user-turn prefix pattern wraps the user input in quotes
        assertThat(engine.lastInput).contains("\"short text\"")
    }

    @Test
    fun velaIntentHasDefaultConfidence() {
        val intent = VelaIntent(
            action = "play",
            target = "music",
            constraints = emptyList(),
            rawText = "play music",
        )
        assertThat(intent.confidence).isEqualTo(1.0f)
    }
}
