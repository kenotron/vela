package com.vela.app.ui.conversation

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
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
    val engineState by viewModel.engineState.collectAsState()
    val streamingResponse by viewModel.streamingResponse.collectAsState()
    val toolExecutionState by viewModel.toolExecutionState.collectAsState()
    val agentStep by viewModel.agentStep.collectAsState()

    var textInput by remember { mutableStateOf("") }

    val voiceCapture: VoiceCapture? = remember(speechTranscriber) {
        speechTranscriber?.let { VoiceCapture(it) }
    }

    DisposableEffect(voiceCapture) {
        onDispose { voiceCapture?.destroy() }
    }

    val idleFlow = remember { MutableStateFlow<TranscriptState>(TranscriptState.Idle) }
    val transcriptState by (voiceCapture?.transcriptState ?: idleFlow).collectAsState()
    val isListening = transcriptState is TranscriptState.Listening ||
        transcriptState is TranscriptState.Partial

    LaunchedEffect(transcriptState) {
        val state = transcriptState
        if (state is TranscriptState.Final) viewModel.onVoiceInput(state.text)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) voiceCapture?.startCapture() }

    fun handleSendText() {
        val trimmed = textInput.trim()
        if (trimmed.isNotBlank()) { viewModel.onTextInput(trimmed); textInput = "" }
    }

    fun handleVoiceToggle() {
        if (voiceCapture == null) return
        if (isListening) {
            voiceCapture.stopCapture()
        } else {
            val hasPermission = ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
            if (hasPermission) voiceCapture.startCapture()
            else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    when (engineState) {
        EngineState.ModelNotReady -> {
            Column(
                modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "Download AI model to begin",
                    modifier = Modifier.semantics { contentDescription = "Model download required" },
                )
                Button(
                    onClick = { /* handled by MainActivity download flow */ },
                    modifier = Modifier.semantics { contentDescription = "Download model" },
                ) { Text("Download") }
            }
        }

        EngineState.ModelReady -> {
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
                        VoiceButton(isListening = isListening, onToggle = { handleVoiceToggle() })
                    }
                },
            ) { paddingValues ->
                Box(
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
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
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            items(messages) { message -> MessageBubble(message = message) }

                            toolExecutionState?.let { toolName ->
                                item { ToolExecutionChip(toolName = toolName, step = agentStep) }
                            }

                            streamingResponse?.let { partial ->
                                item { StreamingBubble(text = partial) }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─── Bubble shape helpers ─────────────────────────────────────────────────────

private val UserBubbleShape = RoundedCornerShape(
    topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp,
)
private val AssistantBubbleShape = RoundedCornerShape(
    topStart = 4.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 18.dp,
)

// ─── MessageBubble ────────────────────────────────────────────────────────────

/**
 * Renders a completed message bubble, constrained to 82% of screen width.
 * If [message.content] parses as Vela-UI JSON, renders the structured [VelaUiSurface].
 * User messages are right-aligned with the primaryContainer colour.
 * Assistant messages are left-aligned with secondaryContainer.
 */
@Composable
private fun MessageBubble(message: Message) {
    val isUser = message.role == MessageRole.USER
    val maxWidth = (LocalConfiguration.current.screenWidthDp * 0.82).dp

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        if (!isUser) {
            val velaPayload = remember(message.content) { VelaUiParser.parse(message.content) }
            if (velaPayload != null) {
                VelaUiSurface(
                    payload = velaPayload,
                    modifier = Modifier
                        .widthIn(max = maxWidth)
                        .background(MaterialTheme.colorScheme.surface, AssistantBubbleShape)
                        .padding(12.dp),
                )
                return@Row
            }
        }

        Text(
            text = message.content,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .widthIn(max = maxWidth)
                .background(
                    color = if (isUser) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.secondaryContainer,
                    shape = if (isUser) UserBubbleShape else AssistantBubbleShape,
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
            color = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

// ─── StreamingBubble ──────────────────────────────────────────────────────────

/**
 * Live streaming bubble — visually distinct from committed messages.
 * Shows the partial text with three animated dots to signal active generation.
 */
@Composable
private fun StreamingBubble(text: String) {
    val maxWidth = (LocalConfiguration.current.screenWidthDp * 0.82).dp
    val infinite = rememberInfiniteTransition(label = "streaming")
    val dotAlpha by infinite.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dot",
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = maxWidth)
                .background(MaterialTheme.colorScheme.secondaryContainer, AssistantBubbleShape)
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .semantics { contentDescription = "Assistant is responding" },
        ) {
            if (text.isNotEmpty()) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(modifier = Modifier.size(4.dp))
            }
            // Animated pulse dot — signals live generation
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .alpha(dotAlpha)
                    .background(
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.5f),
                        shape = CircleShape,
                    ),
            )
        }
    }
}

// ─── ToolExecutionChip ────────────────────────────────────────────────────────

/**
 * Chip shown while a tool is executing between inference passes.
 * When [step] is non-null we're in a multi-step agentic loop — shows "Step N/M".
 */
@Composable
private fun ToolExecutionChip(toolName: String, step: AgentStep? = null) {
    val label = buildString {
        append("🔧 Using $toolName")
        if (step != null) append(" · step ${step.current}/${step.max}")
        append("…")
    }
    Row(modifier = Modifier.fillMaxWidth()) {
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
