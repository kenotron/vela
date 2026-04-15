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
import com.vela.app.data.db.ConversationDao
import com.vela.app.data.db.ConversationEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

enum class TranscriptionProvider { GEMINI, WHISPER }

@HiltViewModel
class RecordingViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    val repository: RecordingRepository,
    private val transcriptionService: TranscriptionService,
    private val conversationDao: ConversationDao,
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

    fun transcribe(provider: TranscriptionProvider, googleApiKey: String, openAiApiKey: String) {
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

    fun createVaultSession(onCreated: (conversationId: String) -> Unit) {
        val transcript = repository.state.value.transcript ?: return
        viewModelScope.launch {
            val conv = ConversationEntity(
                id        = UUID.randomUUID().toString(),
                title     = "Recording ${java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.US).format(java.util.Date())}",
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                mode      = "vault",
            )
            conversationDao.insert(conv)
            // Store transcript as first message to process — the harness will pick it up
            onCreated(conv.id)
        }
    }

    fun reset() = repository.reset()

    override fun onCleared() {
        runCatching { context.unbindService(serviceConnection) }
        super.onCleared()
    }
}
