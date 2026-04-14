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
import androidx.compose.material.icons.filled.Settings
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
import com.vela.app.data.db.TurnEventEntity
import com.vela.app.data.db.TurnWithEvents
import com.vela.app.domain.model.Conversation
import com.vela.app.ui.components.MarkdownText
import com.vela.app.ui.components.VoiceButton
import com.vela.app.ui.nodes.NodesScreen
    import com.vela.app.ui.settings.SettingsScreen
    import com.vela.app.ui.settings.VaultDetailScreen
import com.vela.app.voice.SpeechTranscriber
import com.vela.app.voice.TranscriptState
import com.vela.app.voice.VoiceCapture
import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.*

private enum class Page { CHAT, SESSIONS, NODES, SETTINGS, VAULT_DETAIL }

@Composable
fun ConversationRoot(
    speechTranscriber: SpeechTranscriber? = null,
    viewModel: ConversationViewModel = hiltViewModel(),
) {
    var page          by remember { mutableStateOf(Page.CHAT) }
    var prevPage      by remember { mutableStateOf(Page.CHAT) }
    var detailVaultId by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = page != Page.CHAT) {
        val dest = prevPage
        prevPage = Page.CHAT
        page = dest
    }

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
    ) { p ->
        when (p) {
            Page.CHAT     -> ConversationScreen(
                speechTranscriber,
                viewModel,
                onOpenSessions = { prevPage = page; page = Page.SESSIONS },
                onOpenSettings = { prevPage = page; page = Page.SETTINGS },
            )
            Page.SESSIONS -> SessionsPage(
                viewModel,
                onBack   = { prevPage = Page.CHAT; page = Page.CHAT },
                onSelect = { prevPage = Page.CHAT; page = Page.CHAT },
            )
            Page.NODES    -> NodesScreen(onBack = {
                val dest = prevPage; prevPage = Page.CHAT; page = dest
            })
            Page.SETTINGS -> SettingsScreen(
                onNavigateBack          = { prevPage = Page.CHAT; page = Page.CHAT },
                onNavigateToNodes       = { prevPage = page; page = Page.NODES },
                onNavigateToVaultDetail = { vaultId ->
                    detailVaultId = vaultId
                    prevPage = page
                    page = Page.VAULT_DETAIL
                },
            )
            Page.VAULT_DETAIL -> {
                val vaultId = detailVaultId
                if (vaultId != null) {
                    VaultDetailScreen(
                        vaultId        = vaultId,
                        onNavigateBack = { val dest = prevPage; prevPage = Page.CHAT; page = dest },
                    )
                }
            }
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
        if (query.isBlank()) conversations else conversations.filter { it.title.contains(query, ignoreCase = true) }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { Text("Chats", style = MaterialTheme.typography.titleLarge) },
                actions = { FilledTonalIconButton(onClick = { viewModel.newSession(); onSelect() }) { Icon(Icons.Default.Add, null) } },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth().padding(16.dp), placeholder = { Text("Search\u2026") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true, shape = RoundedCornerShape(28.dp), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search))
            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (query.isBlank()) "No chats yet \u2014 tap + to start." else "No results", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(filtered, key = { it.id }) { conv ->
                        SessionCard(conv, isActive = conv.id == activeId, onClick = { viewModel.switchSession(conv.id); onSelect() }, onDelete = { viewModel.deleteSession(conv.id) })
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
        val d = System.currentTimeMillis() - conv.updatedAt
        when { d < 60_000L -> "Just now"; d < 3_600_000L -> "${d/60_000}m ago"; d < 86_400_000L -> "${d/3_600_000}h ago"; d < 7*86_400_000L -> "${d/86_400_000}d ago"; else -> SimpleDateFormat("MMM d", Locale.US).format(Date(conv.updatedAt)) }
    }
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = if (isActive) cs.primaryContainer.copy(alpha = 0.55f) else cs.surfaceContainerLow),
        border = if (isActive) BorderStroke(1.dp, cs.primary.copy(alpha = 0.4f)) else null) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(42.dp).background(if (isActive) cs.primary.copy(alpha=0.15f) else cs.surfaceContainerHigh, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.ChatBubbleOutline, null, tint = if (isActive) cs.primary else cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(conv.title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal), color = if (isActive) cs.primary else cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(dateStr, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Delete, null, tint = cs.onSurfaceVariant.copy(alpha=0.6f), modifier = Modifier.size(18.dp)) }
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
    onOpenSettings: () -> Unit = {},
) {
    val context          = LocalContext.current
    val turnsWithEvents  by viewModel.turnsWithEvents.collectAsState()
    val activeTitle      by viewModel.activeTitle.collectAsState()
    val activeTurnId     by viewModel.activeTurnId.collectAsState()
    val streamingTextMap by viewModel.streamingText.collectAsState()

    var textInput by remember { mutableStateOf("") }
    val voiceCapture = remember(speechTranscriber) { speechTranscriber?.let { VoiceCapture(it) } }
    DisposableEffect(voiceCapture) { onDispose { voiceCapture?.destroy() } }
    val idleFlow = remember { MutableStateFlow<TranscriptState>(TranscriptState.Idle) }
    val transcriptState by (voiceCapture?.transcriptState ?: idleFlow).collectAsState()
    val isListening = transcriptState is TranscriptState.Listening || transcriptState is TranscriptState.Partial
    LaunchedEffect(transcriptState) { if (transcriptState is TranscriptState.Final) viewModel.onVoiceInput((transcriptState as TranscriptState.Final).text) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) voiceCapture?.startCapture() }

    fun handleSend() { val t = textInput.trim(); if (t.isNotBlank()) { viewModel.onTextInput(t); textInput = "" } }
    fun handleMic() {
        if (voiceCapture == null) return
        if (isListening) voiceCapture.stopCapture()
        else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) voiceCapture.startCapture()
        else permLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    val listState = rememberLazyListState()
    LaunchedEffect(turnsWithEvents.size, streamingTextMap.size) {
        if (turnsWithEvents.isNotEmpty()) {
            listState.animateScrollToItem(turnsWithEvents.size - 1)
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                navigationIcon = { IconButton(onClick = onOpenSessions) { Icon(Icons.Default.ChatBubbleOutline, null) } },
                title = { Text(activeTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium) },
                actions = {
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    IconButton(onClick = { viewModel.newSession() }) { Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary) }
                },
            )
        },
        bottomBar = {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp).imePadding(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = textInput, onValueChange = { textInput = it }, modifier = Modifier.weight(1f), placeholder = { Text("Message\u2026") }, maxLines = 4, shape = RoundedCornerShape(24.dp), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { handleSend() }))
                if (textInput.isNotBlank()) IconButton(onClick = { handleSend() }) { Icon(Icons.AutoMirrored.Filled.Send, null, tint = MaterialTheme.colorScheme.primary) }
                VoiceButton(isListening = isListening, onToggle = { handleMic() })
            }
        },
    ) { pad ->
        if (turnsWithEvents.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Text("Start a conversation", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                state          = listState,
                modifier       = Modifier.fillMaxSize().padding(pad),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // ONE rendering path for ALL turns — live or complete, events always present.
                // TurnWithEvents.sortedEvents gives us seq-ordered events from @Relation.
                items(turnsWithEvents, key = { it.turn.id }) { twe ->
                    TurnRow(
                        twe           = twe,
                        streamingText = streamingTextMap[twe.turn.id],
                        isLive        = twe.turn.id == activeTurnId,
                    )
                }
            }
        }
    }
}

// ---- Turn row ---------------------------------------------------------------

private val AssistantShape = RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp)
private val UserShape      = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)

@Composable
private fun TurnRow(twe: TurnWithEvents, streamingText: String?, isLive: Boolean) {
    val maxW = (LocalConfiguration.current.screenWidthDp * 0.85).dp
    val cs   = MaterialTheme.colorScheme

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {

        // User bubble
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Box(Modifier.widthIn(max = maxW).background(cs.surfaceContainerHighest, UserShape).padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(twe.turn.userMessage, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface)
            }
        }

        // Events in seq order — tool events and text events interleaved
        // Uses @Relation-loaded events, sorted in Kotlin. Stable key = event.id.
        twe.sortedEvents.forEach { event ->
            key(event.id) {
                when (event.type) {
                    "tool" -> ToolEventRow(event, maxW)
                    "text" -> if (!event.text.isNullOrBlank()) {
                        TextEventRow(event.text, streaming = false, maxW = maxW)
                    }
                }
            }
        }

        // In-memory streaming text for the live turn (not yet committed as a text TurnEvent)
        if (isLive) {
            val hasStreamingContent = !streamingText.isNullOrEmpty()
            val hasNoTextEvents     = twe.sortedEvents.none { it.type == "text" }

            if (hasStreamingContent) {
                TextEventRow(streamingText!!, streaming = true, maxW = maxW)
            } else if (hasNoTextEvents) {
                // Waiting for first content — show a pulsing indicator
                val inf   = rememberInfiniteTransition(label = "wait")
                val alpha by inf.animateFloat(0.2f, 0.7f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a")
                Box(Modifier.size(8.dp).alpha(alpha).background(cs.onSurface.copy(alpha = 0.3f), CircleShape))
            }
        }
    }
}

@Composable
private fun TextEventRow(text: String, streaming: Boolean, maxW: androidx.compose.ui.unit.Dp) {
    val cs  = MaterialTheme.colorScheme
    val inf = rememberInfiniteTransition(label = "pulse")
    val alpha by inf.animateFloat(0.3f, 1f, infiniteRepeatable(tween(600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "a")

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Box(Modifier.widthIn(max = maxW).background(cs.surface, AssistantShape).padding(horizontal = 14.dp, vertical = 10.dp)) {
            Column {
                MarkdownText(text = text, color = cs.onSurface)
                if (streaming) {
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.size(7.dp).alpha(alpha).background(cs.onSurface.copy(alpha = 0.3f), CircleShape))
                }
            }
        }
    }
}

@Composable
private fun ToolEventRow(event: TurnEventEntity, maxW: androidx.compose.ui.unit.Dp) {
    val cs     = MaterialTheme.colorScheme
    val isDone = event.toolStatus == "done"
    val isErr  = event.toolStatus == "error"
    val running = !isDone && !isErr

    val bg     = when { isDone -> cs.surfaceContainerLow; isErr -> cs.errorContainer.copy(alpha=0.25f); else -> cs.surfaceVariant.copy(alpha=0.4f) }
    val border = when { isDone -> cs.outlineVariant; isErr -> cs.error.copy(alpha=0.4f); else -> cs.outline.copy(alpha=0.3f) }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Row(
            modifier = Modifier.widthIn(max = maxW).background(bg, RoundedCornerShape(10.dp)).border(1.dp, border, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(event.toolIcon ?: "\uD83D\uDD27", fontSize = 16.sp)
            Column(Modifier.weight(1f)) {
                Text(event.toolDisplayName ?: event.toolName ?: "", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold), color = cs.onSurface)
                if (!event.toolSummary.isNullOrBlank()) Text("\u201c${event.toolSummary}\u201d", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (running) {
                        val inf = rememberInfiniteTransition(label = "spin")
                        val a by inf.animateFloat(0.4f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "b")
                        Box(Modifier.size(5.dp).alpha(a).background(cs.onSurfaceVariant, CircleShape))
                    } else {
                        Text(if (isDone) "\u2713" else "\u2717", fontSize = 10.sp, color = if (isDone) cs.primary else cs.error)
                    }
                    Text(when(event.toolStatus) { "done"->"Done"; "error"->"Failed"; else->"Working\u2026" }, style = MaterialTheme.typography.labelSmall, color = if (isDone) cs.primary else if (isErr) cs.error else cs.onSurfaceVariant)
                }
            }
        }
    }
}
