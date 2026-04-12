package com.vela.app.ui.conversation

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.vela.app.a2ui.VelaUiParser
import com.vela.app.a2ui.VelaUiSurface
import com.vela.app.domain.model.Message
import com.vela.app.domain.model.MessageRole
import com.vela.app.ui.components.VoiceButton
import com.vela.app.voice.SpeechTranscriber
import com.vela.app.voice.TranscriptState
import com.vela.app.voice.VoiceCapture
import kotlinx.coroutines.flow.MutableStateFlow

@Composable
fun ConversationScreen(
    speechTranscriber: SpeechTranscriber? = null,
    viewModel: ConversationViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val messages by viewModel.messages.collectAsState()
    val isProcessing by viewModel.isProcessing.collectAsState()
    val engineState by viewModel.engineState.collectAsState()
    val streamingResponse by viewModel.streamingResponse.collectAsState()

    var textInput by remember { mutableStateOf("") }

    val voiceCapture: VoiceCapture? = remember(speechTranscriber) {
        speechTranscriber?.let { VoiceCapture(it) }
    }

    DisposableEffect(voiceCapture) {
        onDispose {
            voiceCapture?.destroy()
        }
    }

    val idleFlow = remember { MutableStateFlow<TranscriptState>(TranscriptState.Idle) }
    val transcriptState by (voiceCapture?.transcriptState ?: idleFlow).collectAsState()
    val isListening = transcriptState is TranscriptState.Listening ||
        transcriptState is TranscriptState.Partial

    LaunchedEffect(transcriptState) {
        val state = transcriptState
        if (state is TranscriptState.Final) {
            viewModel.onVoiceInput(state.text)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            voiceCapture?.startCapture()
        }
    }

    fun handleSendText() {
        val trimmed = textInput.trim()
        if (trimmed.isNotBlank()) {
            viewModel.onTextInput(trimmed)
            textInput = ""
        }
    }

    fun handleVoiceToggle() {
        if (voiceCapture == null) return
        if (isListening) {
            voiceCapture.stopCapture()
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
    }

    when (engineState) {
        EngineState.ModelNotReady -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "Download AI model to begin",
                    modifier = Modifier.semantics { contentDescription = "Model download required" },
                )
                Button(
                    onClick = { /* TODO: trigger model download */ },
                    modifier = Modifier.semantics { contentDescription = "Download model" },
                ) {
                    Text("Download")
                }
            }
        }

        EngineState.ModelReady -> {
            val listState = rememberLazyListState()

            // Auto-scroll to bottom when messages list grows
            LaunchedEffect(messages.size) {
                if (messages.isNotEmpty()) {
                    listState.animateScrollToItem(messages.size - 1)
                }
            }

            // Also scroll during streaming as the bubble grows
            LaunchedEffect(streamingResponse?.length) {
                if (streamingResponse != null && messages.isNotEmpty()) {
                    listState.animateScrollToItem(messages.size - 1)
                }
            }

            Scaffold(
                bottomBar = {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .imePadding(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = textInput,
                            onValueChange = { textInput = it },
                            modifier = Modifier
                                .weight(1f)
                                .semantics { contentDescription = "Message input" },
                            placeholder = { Text("Message or speak…") },
                            maxLines = 4,
                            shape = RoundedCornerShape(24.dp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { handleSendText() }),
                        )
                        if (textInput.isNotBlank()) {
                            IconButton(
                                onClick = { handleSendText() },
                                modifier = Modifier.semantics { contentDescription = "Send message" },
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        VoiceButton(
                            isListening = isListening,
                            onToggle = { handleVoiceToggle() },
                        )
                    }
                },
            ) { paddingValues ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                ) {
                    val showEmpty = messages.isEmpty() && streamingResponse == null && !isProcessing
                    if (showEmpty) {
                        Text(
                            text = "Type a message or tap the mic to speak",
                            modifier = Modifier.align(Alignment.Center),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(messages) { message ->
                                MessageBubble(message = message)
                            }
                            // Live streaming bubble — shown while Gemma 4 is generating
                            streamingResponse?.let { partial ->
                                item {
                                    StreamingBubble(text = partial)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Renders a completed message bubble.
 * If [message.content] parses as Vela-UI JSON, renders the structured [VelaUiSurface].
 * Otherwise falls back to plain text.
 */
@Composable
private fun MessageBubble(message: Message) {
    val isUser = message.role == MessageRole.USER
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        if (!isUser) {
            // Try to parse as Vela-UI / A2UI structured response
            val velaPayload = remember(message.content) { VelaUiParser.parse(message.content) }
            if (velaPayload != null) {
                VelaUiSurface(
                    payload = velaPayload,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(12.dp),
                        )
                        .padding(12.dp),
                )
                return@Box
            }
        }
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

/**
 * Live streaming bubble shown while Gemma 4 generates a response token by token.
 * Shows the partial text with a blinking cursor indicator to signal active generation.
 */
@Composable
private fun StreamingBubble(text: String) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            // Append a cursor character so the user sees active generation
            text = "$text▍",
            modifier = Modifier
                .background(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(8.dp),
                )
                .padding(12.dp)
                .semantics { contentDescription = "Assistant is responding" },
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}
