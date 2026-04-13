package com.vela.app.ui.conversation

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.vela.app.a2ui.VelaUiParser
import com.vela.app.a2ui.VelaUiSurface
import com.vela.app.domain.model.Message
import com.vela.app.domain.model.MessageRole
import com.vela.app.ui.components.MarkdownText
import com.vela.app.ui.components.VoiceButton
import com.vela.app.voice.SpeechTranscriber
import com.vela.app.voice.TranscriptState
import com.vela.app.voice.VoiceCapture
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

@Composable
fun ConversationScreen(
    speechTranscriber: SpeechTranscriber? = null,
    viewModel: ConversationViewModel = hiltViewModel(),
) {
    val context        = LocalContext.current
    val messages       by viewModel.messages.collectAsState()
    val isProcessing   by viewModel.isProcessing.collectAsState()
    val streamingResp  by viewModel.streamingResponse.collectAsState()

    var textInput by remember { mutableStateOf("") }

    val voiceCapture = remember(speechTranscriber) {
        speechTranscriber?.let { VoiceCapture(it) }
    }
    DisposableEffect(voiceCapture) { onDispose { voiceCapture?.destroy() } }

    val idleFlow        = remember { MutableStateFlow<TranscriptState>(TranscriptState.Idle) }
    val transcriptState by (voiceCapture?.transcriptState ?: idleFlow).collectAsState()
    val isListening     = transcriptState is TranscriptState.Listening ||
                          transcriptState is TranscriptState.Partial

    LaunchedEffect(transcriptState) {
        if (transcriptState is TranscriptState.Final)
            viewModel.onVoiceInput((transcriptState as TranscriptState.Final).text)
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

    val listState  = rememberLazyListState()
    val totalItems = messages.size + if (streamingResp != null) 1 else 0
    LaunchedEffect(totalItems) {
        if (totalItems > 0) listState.animateScrollToItem(totalItems - 1)
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
                    placeholder = { Text("Message or speak\u2026") },
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
        if (messages.isEmpty() && streamingResp == null) {
            Box(Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                Text(
                    "Type a message or tap the mic",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // All messages (USER, ASSISTANT, TOOL_CALL) in timestamp order from DB.
                // TOOL_CALL rows were inserted before the ASSISTANT response, so they
                // naturally appear in the correct timeline position.
                items(messages, key = { it.id }) { msg ->
                    when (msg.role) {
                        MessageRole.TOOL_CALL -> ToolCallBlockItem(msg)
                        else                  -> MessageBubble(msg)
                    }
                }

                // Live streaming response for the current in-flight turn
                streamingResp?.let {
                    item(key = "streaming") { StreamingBubble(it) }
                }
            }
        }
    }
}

// ─── Message bubble ───────────────────────────────────────────────────────────

private val UserShape      = RoundedCornerShape(18.dp, 18.dp,  4.dp, 18.dp)
private val AssistantShape = RoundedCornerShape( 4.dp, 18.dp, 18.dp, 18.dp)

@Composable
private fun MessageBubble(message: Message) {
    val isUser = message.role == MessageRole.USER
    val maxW   = (LocalConfiguration.current.screenWidthDp * 0.82).dp
    val cs     = MaterialTheme.colorScheme

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        if (!isUser) {
            val velaPayload = remember(message.content) { VelaUiParser.parse(message.content) }
            if (velaPayload != null) {
                VelaUiSurface(
                    velaPayload,
                    Modifier.widthIn(max = maxW)
                            .background(cs.surface, AssistantShape)
                            .padding(12.dp),
                )
                return@Row
            }
        }
        Box(
            Modifier
                .widthIn(max = maxW)
                .background(
                    if (isUser) cs.primaryContainer else cs.secondaryContainer,
                    if (isUser) UserShape else AssistantShape,
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            if (isUser) {
                Text(message.content, style = MaterialTheme.typography.bodyMedium,
                     color = cs.onPrimaryContainer)
            } else {
                MarkdownText(text = message.content, color = cs.onSecondaryContainer)
            }
        }
    }
}

// ─── Streaming bubble ─────────────────────────────────────────────────────────

@Composable
private fun StreamingBubble(text: String) {
    val maxW = (LocalConfiguration.current.screenWidthDp * 0.82).dp
    val cs   = MaterialTheme.colorScheme
    val inf  = rememberInfiniteTransition(label = "pulse")
    val alpha by inf.animateFloat(0.3f, 1f,
        infiniteRepeatable(tween(600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "a")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Box(
            Modifier.widthIn(max = maxW)
                    .background(cs.secondaryContainer, AssistantShape)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Column {
                if (text.isNotEmpty()) {
                    MarkdownText(text = text, color = cs.onSecondaryContainer)
                    Spacer(Modifier.height(4.dp))
                }
                Box(Modifier.size(8.dp).alpha(alpha)
                            .background(cs.onSecondaryContainer.copy(alpha = 0.4f), CircleShape))
            }
        }
    }
}

// ─── Tool call block ──────────────────────────────────────────────────────────

/**
 * Renders a TOOL_CALL message as an inline card. The card is updated in-place
 * by Room when the tool finishes — Compose re-renders automatically via the
 * reactive Flow, so the block stays in its original timeline position.
 */
@Composable
private fun ToolCallBlockItem(message: Message) {
    val meta = remember(message.toolMeta) {
        runCatching { JSONObject(message.toolMeta ?: "{}") }.getOrDefault(JSONObject())
    }
    val displayName    = meta.optString("displayName", message.content)
    val icon           = meta.optString("icon", "\uD83D\uDD27")   // 🔧
    val summary        = meta.optString("summary", "")
    val status         = meta.optString("status", "in_progress")
    val resultSnippet  = meta.optString("resultSnippet", "").takeIf { it.isNotBlank() }

    val isDone = status == "done"
    val isErr  = status == "error"
    val cs     = MaterialTheme.colorScheme

    val borderColor = when {
        isDone -> cs.primary.copy(alpha = 0.35f)
        isErr  -> cs.error.copy(alpha = 0.4f)
        else   -> cs.outline.copy(alpha = 0.3f)
    }
    val bgColor = when {
        isDone -> cs.primaryContainer.copy(alpha = 0.25f)
        isErr  -> cs.errorContainer.copy(alpha = 0.25f)
        else   -> cs.surfaceVariant.copy(alpha = 0.4f)
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Row(
            modifier = Modifier
                .widthIn(max = (LocalConfiguration.current.screenWidthDp * 0.82).dp)
                .background(bgColor, RoundedCornerShape(12.dp))
                .border(1.dp, borderColor, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(icon, fontSize = 18.sp)

            Column(Modifier.weight(1f)) {
                Text(
                    displayName,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = cs.onSurface,
                )
                if (summary.isNotBlank()) {
                    Text(
                        "\u201c$summary\u201d",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                }
                // Status indicator
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (!isDone && !isErr) {
                        val inf = rememberInfiniteTransition(label = "spin")
                        val a by inf.animateFloat(0.4f, 1f,
                            infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "b")
                        Box(Modifier.size(6.dp).alpha(a).background(cs.primary, CircleShape))
                    } else {
                        Text(
                            if (isDone) "\u2713" else "\u2717",
                            fontSize = 11.sp,
                            color = if (isDone) cs.primary else cs.error,
                        )
                    }
                    Text(
                        when (status) {
                            "done"  -> if (resultSnippet != null) "Done" else "Done"
                            "error" -> "Failed"
                            else    -> "Working\u2026"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isDone) cs.primary else if (isErr) cs.error else cs.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
