package com.vela.app.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface TtsEngine {
    suspend fun speak(text: String)
    fun stop()
    fun shutdown()
}

class AndroidTtsEngine(context: Context) : TtsEngine {

    @Volatile private var initialized = false
    @Volatile private var initFailed = false

    private val tts = TextToSpeech(context) { status ->
        if (status == TextToSpeech.SUCCESS) {
            initialized = true
        } else {
            initFailed = true
        }
    }

    override suspend fun speak(text: String) {
        // Wait up to 5s for init with 50ms polling
        val deadlineMs = System.currentTimeMillis() + 5_000L
        while (!initialized && !initFailed && System.currentTimeMillis() < deadlineMs) {
            kotlinx.coroutines.delay(50)
        }
        if (!initialized) return

        tts.language = Locale.US

        suspendCancellableCoroutine { continuation ->
            val utteranceId = UUID.randomUUID().toString()

            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (continuation.isActive) continuation.resume(Unit)
                }
                override fun onError(utteranceId: String?) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(RuntimeException("TTS error for utterance: $utteranceId"))
                    }
                }
            })

            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)

            continuation.invokeOnCancellation { tts.stop() }
        }
    }

    override fun stop() {
        tts.stop()
    }

    override fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}

class FakeTtsEngine : TtsEngine {
    val spokenTexts = mutableListOf<String>()
    var stopCount = 0
    var isShutdown = false

    override suspend fun speak(text: String) {
        spokenTexts.add(text)
    }

    override fun stop() {
        stopCount++
    }

    override fun shutdown() {
        isShutdown = true
    }
}
