package com.vela.app.ui.conversation

    import android.Manifest
    import android.content.pm.PackageManager
    import android.media.AudioFormat
    import android.media.AudioRecord
    import android.media.MediaRecorder
    import androidx.activity.compose.rememberLauncherForActivityResult
    import androidx.activity.result.contract.ActivityResultContracts
    import androidx.compose.foundation.background
    import androidx.compose.foundation.layout.Arrangement
    import androidx.compose.foundation.layout.Box
    import androidx.compose.foundation.layout.PaddingValues
    import androidx.compose.foundation.layout.fillMaxSize
    import androidx.compose.foundation.layout.fillMaxWidth
    import androidx.compose.foundation.layout.padding
    import androidx.compose.foundation.lazy.LazyColumn
    import androidx.compose.foundation.lazy.items
    import androidx.compose.foundation.shape.RoundedCornerShape
    import androidx.compose.material3.CircularProgressIndicator
    import androidx.compose.material3.MaterialTheme
    import androidx.compose.material3.Scaffold
    import androidx.compose.material3.Text
    import androidx.compose.runtime.Composable
    import androidx.compose.runtime.DisposableEffect
    import androidx.compose.runtime.collectAsState
    import androidx.compose.runtime.getValue
    import androidx.compose.runtime.remember
    import androidx.compose.ui.Alignment
    import androidx.compose.ui.Modifier
    import androidx.compose.ui.platform.LocalContext
    import androidx.compose.ui.unit.dp
    import androidx.core.content.ContextCompat
    import androidx.hilt.navigation.compose.hiltViewModel
    import com.vela.app.domain.model.Message
    import com.vela.app.domain.model.MessageRole
    import com.vela.app.ui.components.VoiceButton
    import com.vela.app.voice.AudioRecordWrapper
    import com.vela.app.voice.VoiceCapture

    @Composable
    fun ConversationScreen(viewModel: ConversationViewModel = hiltViewModel()) {
        val context = LocalContext.current
        val messages by viewModel.messages.collectAsState()
        val isProcessing by viewModel.isProcessing.collectAsState()

        val voiceCapture = remember {
            VoiceCapture(
                outputDir = context.cacheDir,
                audioRecordFactory = {
                    val minBufSize = AudioRecord.getMinBufferSize(
                        16000,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                    )
                    val audioRecord = AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        16000,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        minBufSize,
                    )
                    object : AudioRecordWrapper {
                        override fun startRecording() = audioRecord.startRecording()
                        override fun stop() = audioRecord.stop()
                        override fun read(buffer: ByteArray, offsetInBytes: Int, sizeInBytes: Int): Int =
                            audioRecord.read(buffer, offsetInBytes, sizeInBytes)
                        override fun release() = audioRecord.release()
                    }
                },
            )
        }
        DisposableEffect(Unit) {
            onDispose {
                if (voiceCapture.isRecording.value) {
                    voiceCapture.stopCapture()
                }
            }
        }
        val isRecording by voiceCapture.isRecording.collectAsState()

        val permissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) { granted ->
            if (granted) {
                voiceCapture.startCapture()
            }
        }

        Scaffold(
            floatingActionButton = {
                VoiceButton(
                    isListening = isRecording,
                    onToggle = {
                        if (isRecording) {
                            val path = voiceCapture.stopCapture()
                            if (path != null) {
                                viewModel.onVoiceInput(path)
                            }
                        } else {
                            val hasPermission = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO,
                            ) == PackageManager.PERMISSION_GRANTED
                            if (hasPermission) {
                                voiceCapture.startCapture()
                            } else {
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    },
                )
            },
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            ) {
                if (messages.isEmpty() && !isProcessing) {
                    Text(
                        text = "Tap the microphone to start",
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(messages) { message ->
                            MessageBubble(message = message)
                        }
                    }
                }
                if (isProcessing) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
        }
    }

    @Composable
    private fun MessageBubble(message: Message) {
        val isUser = message.role == MessageRole.USER
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Text(
                text = message.content,
                modifier = Modifier
                    .background(
                        color = if (isUser) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer
                        },
                        shape = RoundedCornerShape(8.dp),
                    )
                    .padding(12.dp),
            )
        }
    }
    