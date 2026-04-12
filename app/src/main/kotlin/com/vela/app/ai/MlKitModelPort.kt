package com.vela.app.ai

import kotlinx.coroutines.flow.Flow

/**
 * Abstraction over the real ML Kit [com.google.mlkit.genai.prompt.GenerativeModel] that allows
 * unit tests to inject a fast, in-process test double without Android dependencies.
 *
 * Streaming API confirmed via javap of genai-prompt-1.0.0-beta2-api.jar:
 *   Flow<GenerateContentResponse> generateContentStream(String) — emits delta chunks.
 *   Each GenerateContentResponse.candidates[0].text is the incremental new text.
 */
internal interface MlKitModelPort {
    val isClosed: Boolean
    suspend fun checkStatus(): ReadinessState
    suspend fun download()
    suspend fun generate(input: String): String

    /** Emit incremental text delta chunks. Each emission is a partial chunk, not full response. */
    fun generateStream(input: String): Flow<String>

    fun close()
}