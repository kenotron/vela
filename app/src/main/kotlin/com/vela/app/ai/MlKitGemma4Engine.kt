package com.vela.app.ai

import android.content.Context
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.GenerationConfig
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ModelConfig
import com.google.mlkit.genai.prompt.ModelPreference
import com.google.mlkit.genai.prompt.ModelReleaseStage
import com.google.mlkit.genai.prompt.Generation
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gemma 4 E2B engine via Android AICore ML Kit GenAI Prompt API.
 *
 * E2B = "Effective 2 Billion" — 2.3B compute-active params, 5.1B total with PLE tables.
 * ModelPreference.FAST selects the E2B variant (speed-optimized / nano-fast).
 *
 * Preview limitations (as of April 2026):
 * - No structured output (use JSON-in-prompt via VelaPromptBuilder)
 * - No system prompts (prefix as user-turn text)
 * - No tool calling (deferred to post-preview GA)
 * - 4000 token input limit
 * - Image input: Pixel 10 only in preview
 * - Unlocked bootloaders not supported
 * - Only English and Korean validated
 *
 * Streaming API confirmed via javap of genai-prompt-1.0.0-beta2-api.jar:
 *   Flow<GenerateContentResponse> generateContentStream(String)
 *   Each emission = delta chunk; candidates[0].text = the new partial text.
 */
@Singleton
class MlKitGemma4Engine : LifecycleAwareEngine {

    private val port: MlKitModelPort

    @Inject
    constructor(@ApplicationContext context: Context) {
        val modelConfigBuilder = ModelConfig.builder()
        modelConfigBuilder.preference = ModelPreference.FAST // E2B / nano-fast variant
        modelConfigBuilder.releaseStage = ModelReleaseStage.PREVIEW
        val modelConfig = modelConfigBuilder.build()

        val configBuilder = GenerationConfig.builder()
        configBuilder.modelConfig = modelConfig
        val config = configBuilder.build()

        port = RealMlKitModelPort(Generation.getClient(config))
    }

    /** Test constructor — inject a [MlKitModelPort] test double instead of real ML Kit. */
    internal constructor(fakeModel: MlKitModelPort) {
        port = fakeModel
    }

    override suspend fun checkReadiness(): ReadinessState = withContext(Dispatchers.IO) {
        port.checkStatus()
    }

    override suspend fun ensureReady() = withContext(Dispatchers.IO) {
        when (checkReadiness()) {
            ReadinessState.Downloadable -> port.download()
            ReadinessState.Unavailable -> throw UnsupportedOperationException(
                "Gemma 4 (AICore) is not available on this device. Unlocked bootloaders and some chipsets are unsupported."
            )
            else -> Unit // Available or Downloading — nothing to do
        }
    }

    /** Non-streaming single-shot inference. Prefer [streamText] for UI responsiveness. */
    override suspend fun processText(input: String): String = withContext(Dispatchers.IO) {
        check(!port.isClosed) { "Engine has been shut down" }
        val safeInput = if (input.length > 3500) input.take(3500) + "...[truncated]" else input
        port.generate(safeInput)
    }

    /**
     * Stream token delta chunks from Gemma 4 E2B using [GenerativeModel.generateContentStream].
     * Each emitted String is a partial text chunk. The caller accumulates them into the full response.
     *
     * Runs on [Dispatchers.IO] — safe to collect on Main.
     */
    override fun streamText(input: String): Flow<String> {
        check(!port.isClosed) { "Engine has been shut down" }
        val safeInput = if (input.length > 3500) input.take(3500) + "...[truncated]" else input
        return port.generateStream(safeInput).flowOn(Dispatchers.IO)
    }

    override fun shutdown() {
        port.close()
    }
}

private class RealMlKitModelPort(private val model: GenerativeModel) : MlKitModelPort {

    @Volatile private var modelClosed = false

    override val isClosed: Boolean get() = modelClosed

    override suspend fun checkStatus(): ReadinessState = withContext(Dispatchers.IO) {
        when (model.checkStatus()) {
            FeatureStatus.AVAILABLE -> ReadinessState.Available
            FeatureStatus.DOWNLOADING -> ReadinessState.Downloading(null)
            FeatureStatus.DOWNLOADABLE -> ReadinessState.Downloadable
            else -> ReadinessState.Unavailable
        }
    }

    override suspend fun download() = withContext(Dispatchers.IO) {
        model.download().collect { }
    }

    override suspend fun generate(input: String): String = withContext(Dispatchers.IO) {
        val response = model.generateContent(input)
        response.candidates.firstOrNull()?.text
            ?: throw IllegalStateException(
                "Gemma 4 returned no candidates — model may be unresponsive or input was safety-filtered"
            )
    }

    /**
     * Stream delta chunks using [GenerativeModel.generateContentStream].
     * API confirmed: returns Flow<GenerateContentResponse> where each response.candidates[0].text
     * is the incremental new text (delta), not the accumulated full response.
     * Empty chunks are skipped; the caller accumulates deltas.
     */
    override fun generateStream(input: String): Flow<String> = flow {
        model.generateContentStream(input).collect { response ->
            val chunk = response.candidates.firstOrNull()?.text.orEmpty()
            if (chunk.isNotEmpty()) emit(chunk)
        }
    }

    override fun close() {
        modelClosed = true
        model.close()
    }
}
