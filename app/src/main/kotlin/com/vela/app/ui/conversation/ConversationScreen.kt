package com.vela.app.ui.conversation

import android.Manifest
import android.content.pm.PackageManager
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.vela.app.data.db.VaultEntity
import com.vela.app.ui.components.ConversationBackground
import com.vela.app.voice.SpeechTranscriber
import com.vela.app.voice.TranscriptState
import com.vela.app.voice.VoiceCapture
import kotlinx.coroutines.flow.MutableStateFlow

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
    onOpenDrawer: () -> Unit = {},
    onRecord: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    ConversationScreen(
        speechTranscriber = speechTranscriber,
        viewModel         = viewModel,
        onOpenDrawer      = onOpenDrawer,
        onRecord          = onRecord,
        modifier          = modifier,
    )
}

// ── Conversation screen ────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
    speechTranscriber: SpeechTranscriber? = null,
    viewModel: ConversationViewModel = hiltViewModel(),
    onOpenDrawer: () -> Unit = {},
    onRecord: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context          = LocalContext.current
    val turnsWithEvents  by viewModel.turnsWithEvents.collectAsState()
    val activeTitle      by viewModel.activeTitle.collectAsState()
    val activeTurnId     by viewModel.activeTurnId.collectAsState()
    val streamingTextMap by viewModel.streamingText.collectAsState()
    val pendingInput     by viewModel.pendingInput.collectAsState()

    // Vault pills state
    val allVaults           by viewModel.allVaults.collectAsState()
    val sessionActiveVaultIds by viewModel.sessionActiveVaultIds.collectAsState()

    var textInput by remember { mutableStateOf("") }
    LaunchedEffect(pendingInput) {
        val text = pendingInput
        if (!text.isNullOrBlank()) {
            textInput = text
            viewModel.consumePendingInput()
        }
    }

    val pendingTranscript by viewModel.pendingTranscript.collectAsState()
    val voiceCapture = remember(speechTranscriber) { speechTranscriber?.let { VoiceCapture(it) } }
    DisposableEffect(voiceCapture) { onDispose { voiceCapture?.destroy() } }
    val idleFlow = remember { MutableStateFlow<TranscriptState>(TranscriptState.Idle) }
    val transcriptState by (voiceCapture?.transcriptState ?: idleFlow).collectAsState()
    val isListening = transcriptState is TranscriptState.Listening || transcriptState is TranscriptState.Partial
    LaunchedEffect(transcriptState) { if (transcriptState is TranscriptState.Final) viewModel.onVoiceInput((transcriptState as TranscriptState.Final).text) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) voiceCapture?.startCapture() }

    val attachments = remember { androidx.compose.runtime.mutableStateListOf<AttachmentItem>() }

    // When a transcript arrives from the recording flow, save it to a temp .txt file
    // and stage it as a composer attachment — the user just adds a message and sends.
    LaunchedEffect(pendingTranscript) {
        val transcript = pendingTranscript ?: return@LaunchedEffect
        viewModel.consumePendingTranscript()
        val tmpFile = java.io.File(context.cacheDir, "transcript_${System.currentTimeMillis()}.txt")
        try {
            tmpFile.writeText(transcript)
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", tmpFile
            )
            attachments.add(AttachmentItem(
                uri         = uri,
                displayName = tmpFile.name,
                mimeType    = "text/plain",
            ))
        } catch (_: Exception) {
            textInput = transcript   // fallback: paste into text field
        }
    }

    val photoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
            attachments.add(AttachmentItem(
                uri         = uri,
                displayName = uri.lastPathSegment?.substringAfterLast('/') ?: "image",
                mimeType    = mime,
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
            listState.scrollToItem(turnsWithEvents.size - 1)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            CenterAlignedTopAppBar(
                // ── Hamburger — opens drawer ──────────────────────────────
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Default.Menu, contentDescription = "Open menu")
                    }
                },
                title = {
                    Text(
                        text     = activeTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style    = MaterialTheme.typography.titleMedium,
                    )
                },
                actions = {
                    // New chat shortcut
                    IconButton(onClick = { viewModel.newSession() }) {
                        Icon(Icons.Default.Add, contentDescription = "New chat", tint = MaterialTheme.colorScheme.primary)
                    }
                },
            )
        },
        bottomBar = {
            Column {
                // ── Vault pills (active vault filters) ───────────────────
                if (allVaults.isNotEmpty()) {
                    VaultPillsRow(
                        vaults    = allVaults,
                        activeIds = sessionActiveVaultIds,
                        onToggle  = viewModel::toggleVaultForSession,
                    )
                }
                ComposerBox(
                    value              = textInput,
                    onValueChange      = { textInput = it },
                    onSend             = { handleSend() },
                    onRecord           = onRecord,
                    speechTranscriber  = speechTranscriber,
                    isListening        = isListening,
                    onMicClick         = { handleMic() },
                    attachments        = attachments,
                    onRemoveAttachment = { id -> attachments.removeIf { it.id == id } },
                    onPickPhoto        = { photoLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    onPickFile         = { fileLauncher.launch(arrayOf("*/*")) },
                    onCamera           = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
                )
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            ConversationBackground()
            if (turnsWithEvents.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text  = "Start a conversation",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    state          = listState,
                    modifier       = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
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

// ── Vault pills row ───────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VaultPillsRow(
    vaults: List<VaultEntity>,
    activeIds: Set<String>,
    onToggle: (String) -> Unit,
) {
    LazyRow(
        contentPadding      = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(vaults, key = { it.id }) { vault ->
            val selected = vault.id in activeIds
            FilterChip(
                selected    = selected,
                onClick     = { onToggle(vault.id) },
                label       = {
                    Text(
                        text  = vault.name,
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
                leadingIcon = when {
                    selected -> {{ Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp)) }}
                    else     -> {{ Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(14.dp)) }}
                },
            )
        }
    }
}
