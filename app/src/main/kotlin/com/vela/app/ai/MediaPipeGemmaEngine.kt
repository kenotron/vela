package com.vela.app.ai

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface LlmInferenceWrapper {
    fun generateResponse(inputText: String): String
    fun close()
}

class RealLlmInferenceWrapper(
    context: Context,
    modelPath: String,
    maxTokens: Int = 512,
    temperature: Float = 0.7f,
    topK: Int = 40,
) : LlmInferenceWrapper {

    private val inference: LlmInference

    init {
        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelPath)
            .setMaxTokens(maxTokens)
            .setTemperature(temperature)
            .setTopK(topK)
            .build()
        inference = LlmInference.createFromOptions(context, options)
    }

    override fun generateResponse(inputText: String): String = inference.generateResponse(inputText)

    override fun close() {
        inference.close()
    }
}

class FakeLlmInferenceWrapper(private val response: String) : LlmInferenceWrapper {

    var lastInput: String? = null
        private set

    override fun generateResponse(inputText: String): String {
        lastInput = inputText
        return response
    }

    override fun close() {}
}

class MediaPipeGemmaEngine(private val inference: LlmInferenceWrapper?) : GemmaEngine {

    override suspend fun processText(input: String): String = withContext(Dispatchers.IO) {
        val wrapper = inference ?: throw IllegalStateException("LlmInference is not initialized")
        wrapper.generateResponse(input)
    }
}
