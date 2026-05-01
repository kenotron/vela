package com.vela.app.ui.voice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the full-screen voice capture overlay.
 *
 * Manages phase transitions (RECORDING → REVIEW) and exposes the elapsed
 * timer and transcript for display. Deliberately dependency-free so it is
 * trivially unit-testable without any coroutine setup.
 */
@HiltViewModel
class VoiceOverlayViewModel @Inject constructor() : ViewModel() {

    enum class VoicePhase { RECORDING, REVIEW }

    private val _phase = MutableStateFlow(VoicePhase.RECORDING)
    val phase: StateFlow<VoicePhase> = _phase

    private val _transcript = MutableStateFlow("")
    val transcript: StateFlow<String> = _transcript

    private val _elapsedMs = MutableStateFlow(0L)
    val elapsedMs: StateFlow<Long> = _elapsedMs

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording

    private var timerJob: Job? = null

    fun startRecording() {
        _isRecording.value = true
        _elapsedMs.value = 0L
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            val startMs = System.currentTimeMillis()
            while (_isRecording.value) {
                _elapsedMs.value = System.currentTimeMillis() - startMs
                delay(100L)
            }
        }
    }

    fun stopRecording() {
        _isRecording.value = false
        timerJob?.cancel()
        timerJob = null
        _phase.value = VoicePhase.REVIEW
    }

    fun discard() {
        _isRecording.value = false
        timerJob?.cancel()
        timerJob = null
        _phase.value = VoicePhase.RECORDING
        _transcript.value = ""
        _elapsedMs.value = 0L
    }

    companion object {
        /**
         * Formats elapsed milliseconds as "M:SS"
         * e.g. 42 000 → "0:42", 605 000 → "10:05".
         */
        fun formatElapsedMs(ms: Long): String {
            val s = ms / 1_000L
            return "${s / 60}:${"%02d".format(s % 60)}"
        }
    }
}
