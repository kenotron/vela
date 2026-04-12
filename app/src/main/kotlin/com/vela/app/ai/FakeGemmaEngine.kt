package com.vela.app.ai

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * In-process fake for tests and Preview builds.
 *
 * [streamText] simulates token-level streaming by emitting the canned response word-by-word
 * with a configurable delay — exercising the same streaming paths as the real engine.
 */
class FakeGemmaEngine(
    private val response: String = "Hello! I'm Vela, your on-device AI assistant. How can I help?",
    private val wordDelayMs: Long = 0L, // 0 = instant in tests; set ~40 for UI previews
) : GemmaEngine {

    override suspend fun processText(input: String): String {
        delay(100)
        return response
    }

    /**
     * Simulates streaming by emitting each word of [response] individually.
     * Uses [wordDelayMs] between emissions so instrumented / preview flows look realistic.
     */
    override fun streamText(input: String): Flow<String> = flow {
        val words = response.split(" ")
        words.forEachIndexed { index, word ->
            val chunk = if (index == 0) word else " $word"
            emit(chunk)
            if (wordDelayMs > 0) delay(wordDelayMs)
        }
    }
}
