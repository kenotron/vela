    package com.vela.app.ui.conversation

    import android.Manifest
    import android.content.pm.PackageManager
    import androidx.activity.compose.rememberLauncherForActivityResult
    import androidx.activity.result.contract.ActivityResultContracts
    import androidx.compose.animation.core.*
    import androidx.compose.foundation.background
    import androidx.compose.foundation.layout.*
    import androidx.compose.foundation.lazy.LazyColumn
    import androidx.compose.foundation.lazy.items
    import androidx.compose.foundation.lazy.rememberLazyListState
    import androidx.compose.foundation.shape.CircleShape
    import androidx.compose.foundation.shape.RoundedCornerShape
    import androidx.compose.foundation.text.KeyboardActions
    import androidx.compose.foundation.text.KeyboardOptions
    import androidx.compose.material.icons.Icons
    import androidx.compose.material.icons.automirrored.filled.Send
    import androidx.compose.material3.*
    import androidx.compose.runtime.*
    import androidx.compose.ui.Alignment
    import androidx.compose.ui.Modifier
    import androidx.compose.ui.draw.alpha
    import androidx.compose.ui.platform.LocalConfiguration
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
        val streamingResponse by viewModel.streamingResponse.collectAsState()
        val toolState by viewModel.toolState.collectAsState()

        var textInput by remember { mutableStateOf("") }

        val voiceCapture: VoiceCapture? = remember(speechTranscriber) {
            speechTranscriber?.let { VoiceCapture(it) }
        }
        DisposableEffect(voiceCapture) { onDispose { voiceCapture?.destroy() } }

        val idleFlow = remember { MutableStateFlow<TranscriptState>(TranscriptState.Idle) }
        val transcriptState by (voiceCapture?.transcriptState ?: idleFlow).collectAsState()
        val isListening = transcriptState is TranscriptState.Listening ||
                          transcriptState is TranscriptState.Partial

        LaunchedEffect(transcriptState) {
            if (transcriptState is TranscriptState.Final) {
                viewModel.onVoiceInput((transcriptState as TranscriptState.Final).text)
            }
        }

        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted -> if (granted) voiceCapture?.startCapture() }

        fun handleSend() {
            val t = textInput.trim()
            if (t.isNotBlank()) { viewModel.onTextInput(t); textInput = "" }
        }

        fun handleMic() {
            if (voiceCapture == null) return
            if (isListening) voiceCapture.stopCapture()
            else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                     == PackageManager.PERMISSION_GRANTED) voiceCapture.startCapture()
            else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        val listState = rememberLazyListState()
        LaunchedEffect(messages.size) {
            if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
        }
        LaunchedEffect(streamingResponse?.length) {
            if (streamingResponse != null && messages.isNotEmpty())
                listState.animateScrollToItem(messages.size - 1)
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
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Message or speak…") },
                        maxLines = 4,
                        shape = RoundedCornerShape(24.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { handleSend() }),
                    )
                    if (textInput.isNotBlank()) {
                        IconButton(onClick = { handleSend() }) {
                            Icon(Icons.AutoMirrored.Filled.Send, null,
                                 tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    VoiceButton(isListening = isListening, onToggle = { handleMic() })
                }
            }
        ) { paddingValues ->
            Box(Modifier.fillMaxSize().padding(paddingValues)) {
                if (messages.isEmpty() && streamingResponse == null && !isProcessing) {
                    Text(
                        "Type a message or tap the mic to speak",
                        modifier = Modifier.align(Alignment.Center),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(messages) { MessageBubble(it) }

                        toolState?.let { item { ToolChip(it) } }

                        streamingResponse?.let { item { StreamingBubble(it) } }
                    }
                }
            }
        }
    }

    // ─── Bubble shape helpers ───────────────────────────────────────────────────

    private val UserShape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)
    private val AssistantShape = RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp)

    @Composable
    private fun MessageBubble(message: Message) {
        val isUser = message.role == MessageRole.USER
        val maxW = (LocalConfiguration.current.screenWidthDp * 0.82).dp
        Row(Modifier.fillMaxWidth(),
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
            if (!isUser) {
                val payload = remember(message.content) { VelaUiParser.parse(message.content) }
                if (payload != null) {
                    VelaUiSurface(payload,
                        Modifier.widthIn(max = maxW)
                                .background(MaterialTheme.colorScheme.surface, AssistantShape)
                                .padding(12.dp))
                    return@Row
                }
            }
            Text(
                message.content,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .widthIn(max = maxW)
                    .background(
                        if (isUser) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.secondaryContainer,
                        if (isUser) UserShape else AssistantShape,
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                color = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }

    @Composable
    private fun StreamingBubble(text: String) {
        val maxW = (LocalConfiguration.current.screenWidthDp * 0.82).dp
        val inf = rememberInfiniteTransition(label = "dot")
        val alpha by inf.animateFloat(0.3f, 1f,
            infiniteRepeatable(tween(600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "a")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
            Column(
                Modifier.widthIn(max = maxW)
                        .background(MaterialTheme.colorScheme.secondaryContainer, AssistantShape)
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                        .semantics { contentDescription = "Assistant is responding" }
            ) {
                if (text.isNotEmpty()) {
                    Text(text, style = MaterialTheme.typography.bodyMedium,
                         color = MaterialTheme.colorScheme.onSecondaryContainer)
                    Spacer(Modifier.size(4.dp))
                }
                Box(Modifier.size(8.dp).alpha(alpha)
                        .background(MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.5f),
                                    CircleShape))
            }
        }
    }

    @Composable
    private fun ToolChip(toolName: String) {
        val label = "🔧 Using $toolName…"
        Row(Modifier.fillMaxWidth()) {
            AssistChip(
                onClick = {},
                label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                modifier = Modifier.semantics { contentDescription = label },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    labelColor = MaterialTheme.colorScheme.onTertiaryContainer,
                ),
            )
        }
    }
    