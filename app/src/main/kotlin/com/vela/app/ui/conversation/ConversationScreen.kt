package com.vela.app.ui.conversation

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.vela.app.a2ui.VelaUiParser
import com.vela.app.a2ui.VelaUiSurface
import com.vela.app.domain.model.Conversation
import com.vela.app.domain.model.Message
import com.vela.app.domain.model.MessageRole
import com.vela.app.ui.components.MarkdownText
import com.vela.app.ui.components.VoiceButton
import com.vela.app.ui.nodes.NodesScreen
import com.vela.app.voice.SpeechTranscriber
import com.vela.app.voice.TranscriptState
import com.vela.app.voice.VoiceCapture
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

private enum class Page { CHAT, SESSIONS, NODES }

// ---- Root -------------------------------------------------------------------

@Composable
fun ConversationRoot(
    speechTranscriber: SpeechTranscriber? = null,
    viewModel: ConversationViewModel = hiltViewModel(),
) {
    var page by remember { mutableStateOf(Page.CHAT) }
    BackHandler(enabled = page != Page.CHAT) { page = Page.CHAT }

    AnimatedContent(
        targetState = page,
        transitionSpec = {
            if (targetState != Page.CHAT) {
                slideInHorizontally { it } + fadeIn() togetherWith slideOutHorizontally { -it / 3 } + fadeOut()
            } else {
                slideInHorizontally { -it / 3 } + fadeIn() togetherWith slideOutHorizontally { it } + fadeOut()
            }
        },
        label = "page-swap",
    ) { current ->
        when (current) {
            Page.CHAT     -> ConversationScreen(
                speechTranscriber = speechTranscriber,
                viewModel         = viewModel,
                onOpenSessions    = { page = Page.SESSIONS },
                onOpenNodes       = { page = Page.NODES },
            )
            Page.SESSIONS -> SessionsPage(
                viewModel = viewModel,
                onBack    = { page = Page.CHAT },
                onSelect  = { page = Page.CHAT },
            )
            Page.NODES    -> NodesScreen(onBack = { page = Page.CHAT })
        }
    }
}

// ---- Sessions page ----------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionsPage(viewModel: ConversationViewModel, onBack: () -> Unit, onSelect: () -> Unit) {
    val conversations by viewModel.conversations.collectAsState()
    val activeId      by viewModel.activeConversationId.collectAsState()
    var query         by remember { mutableStateOf("") }

    val filtered = remember(conversations, query) {
        if (query.isBlank()) conversations
        else conversations.filter { it.title.contains(query, ignoreCase = true) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { Text("Chats", style = MaterialTheme.typography.titleLarge) },
                actions = {
                    FilledTonalIconButton(onClick = { viewModel.newSession(); onSelect() }) { Icon(Icons.Default.Add, "New chat") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), placeholder = { Text("Search chats\u2026") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true, shape = RoundedCornerShape(28.dp), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search))
            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (query.isBlank()) "No chats yet \u2014 tap + to start." else "No chats matching \u201c$query\u201d", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(filtered, key = { it.id }) { conv ->
                        SessionCard(conv = conv, isActive = conv.id == activeId, onClick = { viewModel.switchSession(conv.id); onSelect() }, onDelete = { viewModel.deleteSession(conv.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionCard(conv: Conversation, isActive: Boolean, onClick: () -> Unit, onDelete: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val dateStr = remember(conv.updatedAt) {
        val delta = System.currentTimeMillis() - conv.updatedAt
        when { delta < 60_000L -> "Just now"; delta < 3_600_000L -> "${delta/60_000}m ago"; delta < 86_400_000L -> "${delta/3_600_000}h ago"; delta < 7*86_400_000L -> "${delta/86_400_000}d ago"; else -> SimpleDateFormat("MMM d", Locale.US).format(Date(conv.updatedAt)) }
    }
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(14.dp),
        colors   = CardDefaults.cardColors(containerColor = if (isActive) cs.primaryContainer.copy(alpha = 0.55f) else cs.surfaceContainerLow),
        border   = if (isActive) BorderStroke(1.dp, cs.primary.copy(alpha = 0.4f)) else null,
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(42.dp).background(if (isActive) cs.primary.copy(alpha=0.15f) else cs.surfaceContainerHigh, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.ChatBubbleOutline, null, tint = if (isActive) cs.primary else cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(conv.title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal), color = if (isActive) cs.primary else cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(dateStr, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Delete, "Delete", tint = cs.onSurfaceVariant.copy(alpha = 0.6f), modifier = Modifier.size(18.dp))
            }
        }
    }
}

// ---- Conversation screen ----------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
    speechTranscriber: SpeechTranscriber? = null,
    viewModel: ConversationViewModel = hiltViewModel(),
    onOpenSessions: () -> Unit = {},
    onOpenNodes: () -> Unit = {},
) {
    val context       = LocalContext.current
    val messages      by viewModel.messages.collectAsState()
    val activeTitle   by viewModel.activeTitle.collectAsState()
    val streamingResp by viewModel.streamingResponse.collectAsState()
    var textInput by remember { mutableStateOf("") }

    val voiceCapture = remember(speechTranscriber) { speechTranscriber?.let { VoiceCapture(it) } }
    DisposableEffect(voiceCapture) { onDispose { voiceCapture?.destroy() } }
    val idleFlow        = remember { MutableStateFlow<TranscriptState>(TranscriptState.Idle) }
    val transcriptState by (voiceCapture?.transcriptState ?: idleFlow).collectAsState()
    val isListening     = transcriptState is TranscriptState.Listening || transcriptState is TranscriptState.Partial
    LaunchedEffect(transcriptState) { if (transcriptState is TranscriptState.Final) viewModel.onVoiceInput((transcriptState as TranscriptState.Final).text) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) voiceCapture?.startCapture() }

    fun handleSend() { val t = textInput.trim(); if (t.isNotBlank()) { viewModel.onTextInput(t); textInput = "" } }
    fun handleMic() {
        if (voiceCapture == null) return
        if (isListening) voiceCapture.stopCapture()
        else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) voiceCapture.startCapture()
        else permLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    val listState = rememberLazyListState()
    val total = messages.size + if (streamingResp != null) 1 else 0
    LaunchedEffect(total) { if (total > 0) listState.animateScrollToItem(total - 1) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                navigationIcon = { IconButton(onClick = onOpenSessions) { Icon(Icons.Default.ChatBubbleOutline, "All chats") } },
                title = { Text(activeTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium) },
                actions = {
                    // Nodes button
                    IconButton(onClick = onOpenNodes) { Icon(Icons.Default.Hub, "Vela nodes", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    IconButton(onClick = { viewModel.newSession() }) { Icon(Icons.Default.Add, "New chat", tint = MaterialTheme.colorScheme.primary) }
                },
            )
        },
        bottomBar = {
            Row(modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp).imePadding(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = textInput, onValueChange = { textInput = it }, modifier = Modifier.weight(1f), placeholder = { Text("Message\u2026") }, maxLines = 4, shape = RoundedCornerShape(24.dp), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { handleSend() }))
                if (textInput.isNotBlank()) IconButton(onClick = { handleSend() }) { Icon(Icons.AutoMirrored.Filled.Send, null, tint = MaterialTheme.colorScheme.primary) }
                VoiceButton(isListening = isListening, onToggle = { handleMic() })
            }
        },
    ) { pad ->
        if (messages.isEmpty() && streamingResp == null) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Text("Start a conversation", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(messages, key = { it.id }) { msg ->
                    when (msg.role) { MessageRole.TOOL_CALL -> ToolCallBlockItem(msg); else -> MessageBubble(msg) }
                }
                streamingResp?.let { item(key = "streaming") { StreamingBubble(it) } }
            }
        }
    }
}

// ---- Message bubbles — neutral palette (no blue/purple, content-first) ------

private val UserShape      = RoundedCornerShape(18.dp, 18.dp,  4.dp, 18.dp)
private val AssistantShape = RoundedCornerShape( 4.dp, 18.dp, 18.dp, 18.dp)

@Composable
private fun MessageBubble(message: Message) {
    val isUser = message.role == MessageRole.USER
    val maxW   = (LocalConfiguration.current.screenWidthDp * 0.82).dp
    val cs     = MaterialTheme.colorScheme

    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        if (!isUser) {
            val payload = remember(message.content) { VelaUiParser.parse(message.content) }
            if (payload != null) {
                VelaUiSurface(payload, Modifier.widthIn(max = maxW).background(cs.surface, AssistantShape).padding(12.dp))
                return@Row
            }
        }
        Box(
            Modifier.widthIn(max = maxW)
                .background(
                    // User: subtle neutral elevation — no colour
                    // Assistant: transparent, content speaks for itself
                    if (isUser) cs.surfaceContainerHighest else cs.surface,
                    if (isUser) UserShape else AssistantShape,
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            if (isUser)
                Text(message.content, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface)
            else
                MarkdownText(text = message.content, color = cs.onSurface)
        }
    }
}

@Composable
private fun StreamingBubble(text: String) {
    val maxW  = (LocalConfiguration.current.screenWidthDp * 0.82).dp
    val cs    = MaterialTheme.colorScheme
    val inf   = rememberInfiniteTransition(label = "pulse")
    val alpha by inf.animateFloat(0.3f, 1f, infiniteRepeatable(tween(600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "a")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Box(Modifier.widthIn(max = maxW).background(cs.surface, AssistantShape).padding(horizontal = 14.dp, vertical = 10.dp)) {
            Column {
                if (text.isNotEmpty()) { MarkdownText(text = text, color = cs.onSurface); Spacer(Modifier.height(4.dp)) }
                Box(Modifier.size(8.dp).alpha(alpha).background(cs.onSurface.copy(alpha = 0.3f), CircleShape))
            }
        }
    }
}

// ---- Tool call block — minimal, neutral ------------------------------------

@Composable
private fun ToolCallBlockItem(message: Message) {
    val meta        = remember(message.toolMeta) { runCatching { JSONObject(message.toolMeta ?: "{}") }.getOrDefault(JSONObject()) }
    val displayName = meta.optString("displayName", message.content)
    val icon        = meta.optString("icon", "\uD83D\uDD27")
    val summary     = meta.optString("summary", "")
    val status      = meta.optString("status", "in_progress")
    val isDone = status == "done";  val isErr = status == "error"
    val cs = MaterialTheme.colorScheme

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Row(
            modifier = Modifier
                .widthIn(max = (LocalConfiguration.current.screenWidthDp * 0.82).dp)
                .background(cs.surfaceContainerLow, RoundedCornerShape(10.dp))
                .border(1.dp, cs.outlineVariant, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(icon, fontSize = 16.sp)
            Column(Modifier.weight(1f)) {
                Text(displayName, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), color = cs.onSurface)
                if (summary.isNotBlank()) Text("\u201c$summary\u201d", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (!isDone && !isErr) {
                        val inf = rememberInfiniteTransition(label = "spin")
                        val a by inf.animateFloat(0.4f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "b")
                        Box(Modifier.size(5.dp).alpha(a).background(cs.onSurfaceVariant, CircleShape))
                    } else {
                        Text(if (isDone) "\u2713" else "\u2717", fontSize = 10.sp, color = if (isDone) cs.primary else cs.error)
                    }
                    Text(when(status) { "done"->"Done"; "error"->"Failed"; else->"Working\u2026" }, style = MaterialTheme.typography.labelSmall, color = if (isDone) cs.primary else if (isErr) cs.error else cs.onSurfaceVariant)
                }
            }
        }
    }
}
