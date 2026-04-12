package com.vela.app.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class AndroidSpeechTranscriber(private val context: Context) : SpeechTranscriber {

    private val _transcriptState = MutableStateFlow<TranscriptState>(TranscriptState.Idle)
    override val transcriptState: StateFlow<TranscriptState> = _transcriptState

    private var recognizer: SpeechRecognizer? = null

    private val recognitionIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            _transcriptState.value = TranscriptState.Listening
        }

        override fun onBeginningOfSpeech() {}

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
            val message = when (error) {
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                SpeechRecognizer.ERROR_NETWORK -> "Network error"
                SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                SpeechRecognizer.ERROR_SERVER -> "Server error"
                SpeechRecognizer.ERROR_CLIENT -> "Client error"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
                SpeechRecognizer.ERROR_NO_MATCH -> "No speech match"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "Too many requests"
                SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "Server disconnected"
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "Language not supported"
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Language unavailable"
                SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> "Cannot check support"
                SpeechRecognizer.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS -> "Cannot listen to download events"
                else -> "Unknown error"
            }
            _transcriptState.value = TranscriptState.Error(message)
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            _transcriptState.value = if (!matches.isNullOrEmpty()) {
                TranscriptState.Final(matches[0])
            } else {
                TranscriptState.Error("No recognition results")
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                _transcriptState.value = TranscriptState.Partial(matches[0])
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    override fun startListening() {
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        val rec = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = rec
        rec.setRecognitionListener(listener)
        _transcriptState.value = TranscriptState.Listening
        rec.startListening(recognitionIntent)
    }

    override fun stopListening() {
        recognizer?.stopListening()
        _transcriptState.value = TranscriptState.Idle
    }

    override fun destroy() {
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        _transcriptState.value = TranscriptState.Idle
    }
}
