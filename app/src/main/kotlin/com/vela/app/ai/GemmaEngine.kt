package com.vela.app.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

interface GemmaEngine {
    suspend fun processText(input: String): String

    /**
     * Stream [input] through the model, emitting text delta chunks as they arrive.
     *
     * Default wraps [processText] as a single-emission Flow so existing implementations
     * (e.g. [FakeGemmaEngine]) stay valid until they override this for true streaming.
     */
    fun streamText(input: String): Flow<String> = flow {
        emit(processText(input))
    }

    fun shutdown() {}  // default no-op
}
