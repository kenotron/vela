package com.vela.app.recording

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

enum class RecordingStatus { IDLE, RECORDING, TRANSCRIBING, DONE, ERROR }

data class RecordingState(
    val status: RecordingStatus = RecordingStatus.IDLE,
    val elapsedSeconds: Int = 0,
    val outputFile: File? = null,
    val transcript: String? = null,
    val error: String? = null,
)

@Singleton
class RecordingRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(RecordingState())
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    private var timerJob: kotlinx.coroutines.Job? = null

    val recordingsDir: File get() = File(context.filesDir, "recordings").also { it.mkdirs() }

    fun onRecordingStarted(outputFile: File) {
        _state.value = RecordingState(status = RecordingStatus.RECORDING, outputFile = outputFile)
        timerJob?.cancel()
        timerJob = scope.launch {
            while (true) {
                delay(1000)
                _state.value = _state.value.copy(elapsedSeconds = _state.value.elapsedSeconds + 1)
            }
        }
    }

    fun onRecordingStopped() {
        timerJob?.cancel()
        _state.value = _state.value.copy(status = RecordingStatus.IDLE)
    }

    fun onTranscribing() {
        _state.value = _state.value.copy(status = RecordingStatus.TRANSCRIBING)
    }

    fun onTranscriptReady(transcript: String) {
        _state.value = _state.value.copy(status = RecordingStatus.DONE, transcript = transcript)
    }

    fun onError(message: String) {
        timerJob?.cancel()
        _state.value = _state.value.copy(status = RecordingStatus.ERROR, error = message)
    }

    fun reset() {
        timerJob?.cancel()
        _state.value = RecordingState()
    }

    /** Instance delegate — keeps the spec's API while the logic lives in the companion. */
    fun formatElapsed(seconds: Int): String = Companion.formatElapsed(seconds)

    companion object {
        fun formatElapsed(seconds: Int): String {
            val m = seconds / 60
            val s = seconds % 60
            return "%02d:%02d".format(m, s)
        }
    }
}
