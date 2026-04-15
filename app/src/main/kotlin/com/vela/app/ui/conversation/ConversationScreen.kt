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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.SolidColor
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
import com.vela.app.data.db.VaultEntity
import com.vela.app.domain.model.Conversation
import com.vela.app.ui.components.MarkdownText
import com.vela.app.ui.nodes.NodesScreen
    import com.vela.app.ui.settings.AiSettingsScreen
    import com.vela.app.ui.settings.ConnectionsSettingsScreen
    import com.vela.app.ui.settings.SettingsScreen
    import com.vela.app.ui.settings.VaultDetailScreen
    import com.vela.app.ui.settings.VaultsSettingsScreen
import com.vela.app.voice.SpeechTranscriber
import com.vela.app.voice.TranscriptState
import com.vela.app.voice.VoiceCapture
import kotlinx.coroutines.flow.MutableStateFlow
import android.provider.OpenableColumns
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.ui.draw.clip
import java.text.SimpleDateFormat
import java.util.*

private enum class Page {
    CHAT, SESSIONS, NODES, SETTINGS,
    SETTINGS_AI, SETTINGS_CONNECTIONS, SETTINGS_VAULTS,
    VAULT_DETAIL, RECORDING,
}

private data class AttachmentItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val uri: android.net.Uri,
    val displayName: String,
    val mimeType: String,
)

/** Pure helper — testable without Android deps by accepting plain strings. */
internal fun buildAttachedMessage(
    text: String,
    attachments: List<Pair<String, String>>,  // (displayName, uri.toString())
): String = if (attachments.isEmpty()) text
    else buildString {
        append(text)
        appendLine()
        appendLine()
        appendLine("Attached files:")
        attachments.forEach { (name, uri) ->
            appendLine("- $name ($uri)")
        }
    }

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
                onRecord       = { prevPage = page; page = Page.RECORDING },
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
                onNavigateToAi          = { prevPage = page; page = Page.SETTINGS_AI },
                onNavigateToConnections = { prevPage = page; page = Page.SETTINGS_CONNECTIONS },
                onNavigateToVaults      = { prevPage = page; page = Page.SETTINGS_VAULTS },
                onNavigateToRecording   = { prevPage = page; page = Page.RECORDING },
            )
            Page.RECORDING -> com.vela.app.ui.recording.RecordingScreen(
                onNavigateBack = { prevPage = Page.SETTINGS; page = Page.SETTINGS },
                onVaultSessionCreated = { convId, stagedMessage ->
                    viewModel.switchToConversation(convId)
                    viewModel.setPendingInput(stagedMessage)
                    prevPage = Page.CHAT
                    page = Page.CHAT
                },
            )
            Page.SETTINGS_AI -> AiSettingsScreen(
                onNavigateBack = { prevPage = Page.SETTINGS; page = Page.SETTINGS }
            )
            Page.SETTINGS_CONNECTIONS -> ConnectionsSettingsScreen(
                onNavigateBack = { prevPage = Page.SETTINGS; page = Page.SETTINGS }
            )
            Page.SETTINGS_VAULTS -> VaultsSettingsScreen(
                onNavigateBack = { prevPage = Page.SETTINGS; page = Page.SETTINGS },
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
    onRecord: () -> Unit = {},
) {
    val context          = LocalContext.current
    val turnsWithEvents  by viewModel.turnsWithEvents.collectAsState()
    val activeTitle      by viewModel.activeTitle.collectAsState()
    val activeTurnId     by viewModel.activeTurnId.collectAsState()
    val streamingTextMap by viewModel.streamingText.collectAsState()
    val allVaults             by viewModel.allVaults.collectAsState()
    val sessionActiveVaultIds by viewModel.sessionActiveVaultIds.collectAsState()
    val pendingInput          by viewModel.pendingInput.collectAsState()

    var textInput by remember { mutableStateOf("") }
    LaunchedEffect(pendingInput) {
        val text = pendingInput
        if (!text.isNullOrBlank()) {
            textInput = text
            viewModel.consumePendingInput()
        }
    }
    val voiceCapture = remember(speechTranscriber) { speechTranscriber?.let { VoiceCapture(it) } }
    DisposableEffect(voiceCapture) { onDispose { voiceCapture?.destroy() } }
    val idleFlow = remember { MutableStateFlow<TranscriptState>(TranscriptState.Idle) }
    val transcriptState by (voiceCapture?.transcriptState ?: idleFlow).collectAsState()
    val isListening = transcriptState is TranscriptState.Listening || transcriptState is TranscriptState.Partial
    LaunchedEffect(transcriptState) { if (transcriptState is TranscriptState.Final) viewModel.onVoiceInput((transcriptState as TranscriptState.Final).text) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) voiceCapture?.startCapture() }

    val attachments = remember { androidx.compose.runtime.mutableStateListOf<AttachmentItem>() }

    val photoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            attachments.add(AttachmentItem(
                uri         = uri,
                displayName = uri.lastPathSegment?.substringAfterLast('/') ?: "image",
                mimeType    = "image/*",
            ))
        }
    }

    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val name = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && idx >= 0) cursor.getString(idx) else null
            } ?: uri.lastPathSegment ?: "file"
            val mime = context.contentResolver.getType(uri) ?: "*/*"
            attachments.add(AttachmentItem(uri = uri, displayName = name, mimeType = mime))
        }
    }

    var pendingCameraUri by remember { mutableStateOf<android.net.Uri?>(null) }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val uri = pendingCameraUri
        if (success && uri != null) {
            attachments.add(AttachmentItem(
                uri         = uri,
                displayName = "photo_${System.currentTimeMillis()}.jpg",
                mimeType    = "image/jpeg",
            ))
            pendingCameraUri = null
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val cacheDir = java.io.File(context.cacheDir, "camera").also { it.mkdirs() }
            val photoFile = java.io.File(cacheDir, "vela_${System.currentTimeMillis()}.jpg")
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", photoFile
            )
            pendingCameraUri = uri
            cameraLauncher.launch(uri)
        }
    }

    fun handleSend() {
        val text     = textInput.trim()
        val attPairs = attachments.map { Pair(it.uri, it.mimeType) }
        if (text.isNotBlank() || attPairs.isNotEmpty()) {
            viewModel.sendMessage(text, attPairs)
            textInput = ""
            attachments.clear()
        }
    }
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
                    IconButton(onClick = { viewModel.newSession() }) {
                        Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary)
                    }
                },
            )
        },
        bottomBar = {
            ComposerBox(
                value                 = textInput,
                onValueChange         = { textInput = it },
                onSend                = { handleSend() },
                onRecord              = onRecord,
                allVaults             = allVaults,
                sessionActiveVaultIds = sessionActiveVaultIds,
                onToggleVault         = { viewModel.toggleVaultForSession(it) },
                speechTranscriber     = speechTranscriber,
                isListening           = isListening,
                onMicClick            = { handleMic() },
                attachments           = attachments,
                onRemoveAttachment    = { id -> attachments.removeIf { it.id == id } },
                onPickPhoto           = { photoLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onPickFile            = { fileLauncher.launch(arrayOf("*/*")) },
                onCamera              = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (turnsWithEvents.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Start a conversation", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    state          = listState,
                    modifier       = Modifier.fillMaxSize(),
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
}

// ---- Turn item model --------------------------------------------------------

private data class ToolGroup(val events: List<TurnEventEntity>)
private data class TextEvt(val event: TurnEventEntity)
private sealed class TurnItem {
    data class Tools(val group: ToolGroup) : TurnItem()
    data class Text(val evt: TextEvt) : TurnItem()
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

        // Group consecutive tool events; text events break groups.
        val items: List<TurnItem> = buildList {
            val pending = mutableListOf<TurnEventEntity>()
            twe.sortedEvents.forEach { event ->
                when (event.type) {
                    "tool" -> pending.add(event)
                    else   -> {
                        if (pending.isNotEmpty()) {
                            add(TurnItem.Tools(ToolGroup(pending.toList())))
                            pending.clear()
                        }
                        if (!event.text.isNullOrBlank()) {
                            add(TurnItem.Text(TextEvt(event)))
                        }
                    }
                }
            }
            if (pending.isNotEmpty()) add(TurnItem.Tools(ToolGroup(pending.toList())))
        }

        items.forEach { item ->
            key(when (item) {
                is TurnItem.Tools -> item.group.events.first().id
                is TurnItem.Text  -> item.evt.event.id
            }) {
                when (item) {
                    is TurnItem.Tools -> ToolGroupRow(item.group.events)
                    is TurnItem.Text  -> TextEventRow(
                        text      = item.evt.event.text ?: "",
                        streaming = false,
                        maxW      = maxW,
                    )
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
private fun ToolGroupRow(events: List<TurnEventEntity>) {
    if (events.isEmpty()) return

    var expanded by remember { mutableStateOf(false) }
    val last      = events.last()
    val isRunning = last.toolStatus == null || last.toolStatus == "running"
    val isError   = events.any { it.toolStatus == "error" }

    val borderColor = when {
        isError   -> MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
        isRunning -> MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
        else      -> MaterialTheme.colorScheme.outlineVariant
    }
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier = Modifier.padding(start = 16.dp, end = 32.dp)) {
        // Left-border strip + single summary row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    enabled = events.size > 1,
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                ) { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Left border
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(IntrinsicSize.Min)
                    .defaultMinSize(minHeight = 20.dp)
                    .background(borderColor, shape = RoundedCornerShape(1.dp))
            )
            Spacer(Modifier.width(8.dp))
            // Icon + label
            Text(
                text     = (last.toolIcon ?: "🔧") + "  " + smartLabel(last),
                style    = MaterialTheme.typography.labelSmall,
                color    = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (events.size > 1) {
                Spacer(Modifier.width(4.dp))
                Text(
                    text  = if (expanded) "▲" else "+${events.size - 1}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }

        // Expanded: show all events
        if (expanded && events.size > 1) {
            Spacer(Modifier.height(2.dp))
            events.forEach { event ->
                Row(
                    modifier = Modifier.padding(start = 10.dp, top = 1.dp, bottom = 1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(IntrinsicSize.Min)
                            .defaultMinSize(minHeight = 16.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    )
                    Spacer(Modifier.width(8.dp))
                    val doneAlpha = if (event.toolStatus == "done") 0.6f else 1f
                    Text(
                        text     = (event.toolIcon ?: "🔧") + "  " + smartLabel(event),
                        style    = MaterialTheme.typography.labelSmall,
                        color    = textColor.copy(alpha = doneAlpha),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun smartLabel(event: TurnEventEntity): String {
    val s = event.toolSummary
    return when (event.toolName) {
        "read_file"   -> if (!s.isNullOrBlank()) "Reading $s"      else "Reading file"
        "write_file"  -> if (!s.isNullOrBlank()) "Writing $s"      else "Writing file"
        "edit_file"   -> if (!s.isNullOrBlank()) "Editing $s"      else "Editing file"
        "glob"        -> if (!s.isNullOrBlank()) "Finding $s"      else "Finding files"
        "grep"        -> if (!s.isNullOrBlank()) "Searching: $s"   else "Searching content"
        "bash"        -> if (!s.isNullOrBlank()) "$ $s"            else "Running command"
        "search_web"  -> if (!s.isNullOrBlank()) "Web: $s"         else "Web search"
        "fetch_url"   -> if (!s.isNullOrBlank()) "Fetching $s"     else "Fetching URL"
        "todo"        -> if (!s.isNullOrBlank()) "Todos: $s"       else "Updating todos"
        "load_skill"  -> if (!s.isNullOrBlank()) "Skill: $s"       else "Loading skill"
        else          -> buildString {
            append(event.toolDisplayName ?: event.toolName ?: "Tool")
            if (!s.isNullOrBlank()) { append(": "); append(s) }
        }
    }
}

// ---- ComposerBox ------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComposerBox(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onRecord: () -> Unit,
    allVaults: List<VaultEntity>,
    sessionActiveVaultIds: Set<String>,
    onToggleVault: (String) -> Unit,
    speechTranscriber: SpeechTranscriber?,
    isListening: Boolean,
    onMicClick: () -> Unit,
    attachments: List<AttachmentItem>,
    onRemoveAttachment: (String) -> Unit,
    onPickPhoto: () -> Unit,
    onPickFile: () -> Unit,
    onCamera: () -> Unit,
) {
    var showVaultPicker    by remember { mutableStateOf(false) }
    var showAttachmentSheet by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(28.dp),
        tonalElevation = 3.dp,
        shadowElevation = 4.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)) {

            // ── Row 1: mic + text input ───────────────────────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 4.dp),
            ) {
                // Text field — no outline, outer Surface provides the container shape
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 48.dp)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isEmpty()) {
                        Text(
                            text  = "Type a message\u2026",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        )
                    }
                    BasicTextField(
                        value         = value,
                        onValueChange = onValueChange,
                        modifier      = Modifier.fillMaxWidth(),
                        textStyle     = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        maxLines      = 6,
                        cursorBrush   = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions.Default,
                        keyboardActions = KeyboardActions.Default,
                    )
                }
            }

            // ── Attachment chip row (between text input and action bar) ────────────
            if (attachments.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(attachments, key = { it.id }) { attachment ->
                        AttachmentChip(
                            attachment = attachment,
                            onRemove   = { onRemoveAttachment(attachment.id) },
                        )
                    }
                }
            }

            // ── Row 2: action bar ─────────────────────────────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 4.dp, bottom = 2.dp),
            ) {
                // [+] Attachment — opens AttachmentSheet
                IconButton(
                    onClick  = { showAttachmentSheet = true },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "Attach",
                        tint     = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }

                Spacer(Modifier.width(4.dp))

                // [🏛 Vault] chip — shows active vaults, tap to open picker
                if (allVaults.isNotEmpty()) {
                    val chipLabel = when {
                        sessionActiveVaultIds.isEmpty() ||
                        sessionActiveVaultIds.size == allVaults.size -> "All vaults"
                        sessionActiveVaultIds.size == 1              ->
                            allVaults.firstOrNull { it.id in sessionActiveVaultIds }?.name ?: "1 vault"
                        else -> "${sessionActiveVaultIds.size} vaults"
                    }
                    FilterChip(
                        selected    = true,
                        onClick     = { showVaultPicker = true },
                        label       = { Text(chipLabel, style = MaterialTheme.typography.labelSmall) },
                        leadingIcon = { Icon(Icons.Default.Folder, null, modifier = Modifier.size(14.dp)) },
                        modifier    = Modifier.height(30.dp),
                        shape       = RoundedCornerShape(15.dp),
                    )
                }

                Spacer(Modifier.weight(1f))

                // [→ send] — animates in/out based on whether there is text
                AnimatedVisibility(
                    visible = value.isNotBlank() || attachments.isNotEmpty(),
                    enter   = fadeIn() + scaleIn(initialScale = 0.8f),
                    exit    = fadeOut() + scaleOut(targetScale = 0.8f),
                ) {
                    IconButton(
                        onClick  = { if (value.isNotBlank() || attachments.isNotEmpty()) onSend() },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            tint     = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                // Mic button — voice to text — rightmost in action bar
                IconButton(
                    onClick = onMicClick,
                    modifier = Modifier.size(36.dp),
                    enabled = speechTranscriber != null,
                ) {
                    Icon(
                        imageVector = if (isListening) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = if (isListening) "Stop listening" else "Start voice input",
                        tint = when {
                            isListening -> MaterialTheme.colorScheme.error
                            speechTranscriber == null -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }

    // Attachment sheet
    if (showAttachmentSheet) {
        AttachmentSheet(
            onDismiss   = { showAttachmentSheet = false },
            onRecord    = { showAttachmentSheet = false; onRecord() },
            onPickPhoto = { showAttachmentSheet = false; onPickPhoto() },
            onPickFile  = { showAttachmentSheet = false; onPickFile() },
            onCamera    = { showAttachmentSheet = false; onCamera() },
        )
    }

    // Vault picker bottom sheet
    if (showVaultPicker && allVaults.isNotEmpty()) {
        ModalBottomSheet(onDismissRequest = { showVaultPicker = false }) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 32.dp),
            ) {
                Text(
                    "Vaults for this session",
                    style    = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                allVaults.forEach { vault ->
                    ListItem(
                        headlineContent = { Text(vault.name) },
                        supportingContent = {
                            Text(
                                if (vault.id in sessionActiveVaultIds) "Active" else "Inactive",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked         = vault.id in sessionActiveVaultIds,
                                onCheckedChange = { onToggleVault(vault.id) },
                            )
                        },
                        modifier = Modifier.clickable { onToggleVault(vault.id) },
                    )
                }
            }
        }
    }
}

// ---- AttachmentSheet --------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AttachmentSheet(
    onDismiss: () -> Unit,
    onRecord: () -> Unit,
    onPickPhoto: () -> Unit,
    onPickFile: () -> Unit,
    onCamera: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Attach",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 4.dp),
            )

            // ── Featured: Record Transcription ──────────────────────────────
            Card(
                onClick = onRecord,
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
                shape = RoundedCornerShape(16.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.RecordVoiceOver,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(32.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Record Transcription",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            "Capture audio and transcribe to vault",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f),
                    )
                }
            }

            // ── Standard options: Camera · Photos · Files ────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AttachOption(
                    icon = Icons.Default.CameraAlt,
                    label = "Camera",
                    onClick = { onCamera(); onDismiss() },
                    modifier = Modifier.weight(1f),
                )
                AttachOption(
                    icon = Icons.Default.PhotoLibrary,
                    label = "Photos",
                    onClick = { onPickPhoto(); onDismiss() },
                    modifier = Modifier.weight(1f),
                )
                AttachOption(
                    icon = Icons.Default.InsertDriveFile,
                    label = "Files",
                    onClick = { onPickFile(); onDismiss() },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun AttachOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FilledTonalIconButton(
            onClick = onClick,
            modifier = Modifier.size(56.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(24.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AttachmentChip(attachment: AttachmentItem, onRemove: () -> Unit) {
    Box(
        modifier = Modifier
            .size(width = 64.dp, height = 56.dp)
            .clip(RoundedCornerShape(8.dp))
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = if (attachment.mimeType.startsWith("image"))
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Column(
                modifier = Modifier.padding(4.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = if (attachment.mimeType.startsWith("image"))
                        Icons.Default.Image
                    else
                        Icons.Default.InsertDriveFile,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    attachment.displayName.take(8),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(
            onClick = onRemove,
            modifier = Modifier
                .size(20.dp)
                .align(Alignment.TopEnd),
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Remove",
                modifier = Modifier.size(14.dp),
            )
        }
    }
}
