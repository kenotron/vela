package com.vela.app.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
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

    // Completes exactly once when the TextToSpeech init callback fires.
    // true = SUCCESS, false = engine unavailable.
    // No polling needed — callers suspend on await() until the deferred is resolved.
    private val initResult = CompletableDeferred<Boolean>()

    private val tts = TextToSpeech(context) { status ->
        initResult.complete(status == TextToSpeech.SUCCESS)
    }

    override suspend fun speak(text: String) {
        // Suspend until TTS is ready, with a 5s safety timeout for broken engines.
        val ready = withTimeoutOrNull(5_000L) { initResult.await() } ?: false
        if (!ready) return

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
