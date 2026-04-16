package com.vela.app.recording

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class TranscriptionProvider { GEMINI, WHISPER }

@HiltViewModel
class RecordingViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    val repository: RecordingRepository,
    private val transcriptionService: TranscriptionService,
) : ViewModel() {

    private var recordingService: RecordingService? = null
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            recordingService = (binder as RecordingService.RecordingBinder).getService()
            recordingService?.startRecording()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            recordingService = null
        }
    }

    fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun startRecording() {
        val intent = Intent(context, RecordingService::class.java)
        ContextCompat.startForegroundService(context, intent)
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    fun stopRecording() {
        recordingService?.stopRecording()
        context.unbindService(serviceConnection)
        recordingService = null
    }

    /**
     * Stop the current recording and immediately kick off AI transcription.
     * Reads the API key from SharedPrefs — prefers Gemini, falls back to Whisper.
     */
    fun stopAndTranscribe() {
        stopRecording()
        val prefs = context.getSharedPreferences("amplifier_prefs", android.content.Context.MODE_PRIVATE)
        val googleKey = prefs.getString("google_api_key", "").orEmpty()
        val openAiKey = prefs.getString("openai_api_key", "").orEmpty()
        val provider  = if (googleKey.isNotBlank()) TranscriptionProvider.GEMINI
                        else TranscriptionProvider.WHISPER
        transcribe(provider, googleKey, openAiKey)
    }

    private fun transcribe(provider: TranscriptionProvider, googleApiKey: String, openAiApiKey: String) {
        val file = repository.state.value.outputFile ?: return
        repository.onTranscribing()
        viewModelScope.launch {
            val result = when (provider) {
                TranscriptionProvider.GEMINI  -> transcriptionService.transcribeWithGemini(file, googleApiKey)
                TranscriptionProvider.WHISPER -> transcriptionService.transcribeWithWhisper(file, openAiApiKey)
            }
            result.fold(
                onSuccess = { repository.onTranscriptReady(it) },
                onFailure = { repository.onError(it.message ?: "Transcription failed") },
            )
        }
    }

    fun reset() = repository.reset()

    override fun onCleared() {
        runCatching { context.unbindService(serviceConnection) }
        super.onCleared()
    }
}
