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
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gemma 4 E2B engine via Android AICore ML Kit GenAI Prompt API.
 *
 * E2B = "Effective 2 Billion" — 2.3B compute-active params, 5.1B total with PLE tables.
 * ModelPreference.FAST selects the E2B variant (speed-optimized).
 *
 * Preview limitations (as of April 2026):
 * - No structured output (use JSON-in-prompt in IntentExtractor)
 * - No system prompts (prefix as user-turn text)
 * - No tool calling (deferred to post-preview)
 * - 4000 token input limit
 * - Image input: Pixel 10 only in preview
 * - Unlocked bootloaders not supported
 * - Only English and Korean validated
 */
@Singleton
class MlKitGemma4Engine : GemmaEngine {

    private val port: MlKitModelPort

    @Inject
    constructor(@ApplicationContext context: Context) {
        val modelConfigBuilder = ModelConfig.builder()
        modelConfigBuilder.preference = ModelPreference.FAST // E2B variant
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

    suspend fun checkReadiness(): ReadinessState = withContext(Dispatchers.IO) {
        port.checkStatus()
    }

    suspend fun ensureReady() = withContext(Dispatchers.IO) {
        if (port.checkStatus() == ReadinessState.Downloadable) {
            port.download()
        }
    }

    override suspend fun processText(input: String): String = withContext(Dispatchers.IO) {
        check(!port.isClosed) { "Engine has been shut down" }
        // Preview: 4000 token limit. Truncate input conservatively at 3500 chars.
        val safeInput = if (input.length > 3500) input.take(3500) + "...[truncated]" else input
        port.generate(safeInput)
    }

    fun shutdown() {
        port.close()
    }
}

private class RealMlKitModelPort(private val model: GenerativeModel) : MlKitModelPort {

    private var modelClosed = false

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
        response.candidates.firstOrNull()?.text ?: ""
    }

    override fun close() {
        modelClosed = true
        model.close()
    }
}
