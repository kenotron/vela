package com.vela.app.ui.conversation

import android.Manifest
import android.content.pm.PackageManager
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material.icons.Icons
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
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

/**
 * Pure helper: when an agent chip is active, wrap the user's message into a
 * directive instructing the assistant to call `delegate` with that agent.
 *
 * Returns [input] unchanged when [agentName] is null.
 */
internal fun buildAgentScopedInput(agentName: String?, input: String): String =
    if (agentName == null) input else
        "Use the `delegate` tool with agent=\"$agentName\" and instruction set to the " +
        "following user message, then return its result. Do not respond directly first.\n\n" +
        "User message:\n$input"

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

    // Vault state
    val allVaults             by viewModel.allVaults.collectAsState()
    val sessionActiveVaultIds by viewModel.sessionActiveVaultIds.collectAsState()
    var showVaultMenu         by remember { mutableStateOf(false) }

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

    LaunchedEffect(pendingTranscript) {
        val transcript = pendingTranscript ?: return@LaunchedEffect
        viewModel.consumePendingTranscript()
        val tmpFile = java.io.File(context.cacheDir, "transcript_${System.currentTimeMillis()}.txt")
        try {
            tmpFile.writeText(transcript)
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", tmpFile
            )
            attachments.add(AttachmentItem(uri = uri, displayName = tmpFile.name, mimeType = "text/plain"))
        } catch (_: Exception) {
            textInput = transcript
        }
    }

    val photoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
            attachments.add(AttachmentItem(uri = uri, displayName = uri.lastPathSegment?.substringAfterLast('/') ?: "image", mimeType = mime))
        }
    }

    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && idx >= 0) c.getString(idx) else null
            } ?: uri.lastPathSegment ?: "file"
            val mime = context.contentResolver.getType(uri) ?: "*/*"
            attachments.add(AttachmentItem(uri = uri, displayName = name, mimeType = mime))
        }
    }

    var pendingCameraUri by remember { mutableStateOf<android.net.Uri?>(null) }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = pendingCameraUri
        if (success && uri != null) {
            attachments.add(AttachmentItem(uri = uri, displayName = "photo_${System.currentTimeMillis()}.jpg", mimeType = "image/jpeg"))
            pendingCameraUri = null
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            val cacheDir = java.io.File(context.cacheDir, "camera").also { it.mkdirs() }
            val photoFile = java.io.File(cacheDir, "vela_${System.currentTimeMillis()}.jpg")
            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", photoFile)
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
        if (turnsWithEvents.isNotEmpty()) listState.scrollToItem(turnsWithEvents.size - 1)
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    // imePadding() MUST be before fillMaxSize() — it reduces the incoming constraints
    // so the Scaffold inside shrinks when the keyboard appears, which naturally pushes
    // the bottomBar (ComposerBox) up to sit flush above the keyboard.
    // Reversed order (fillMaxSize.imePadding) silently fails: fillMaxSize locks the
    // size first, then imePadding has nothing left to reduce.
    Box(modifier = Modifier.imePadding().fillMaxSize()) {
        ConversationBackground()

    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = Color.Transparent,
        // Scaffold handles zero insets — keyboard handled by the outer Box's imePadding,
        // nav-bar handled inside ComposerBox's Column so the Surface background fills it.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            CenterAlignedTopAppBar(
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor          = Color.Transparent,
                    scrolledContainerColor  = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                ),
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Default.Menu, contentDescription = "Open menu")
                    }
                },
                title = {
                    Text(activeTitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                         style = MaterialTheme.typography.titleMedium)
                },
                actions = {
                    // ── Vault selector ───────────────────────────────────────
                    if (allVaults.isNotEmpty()) {
                        Box {
                            IconButton(onClick = { showVaultMenu = true }) {
                                Icon(
                                    imageVector        = Icons.Default.Folder,
                                    contentDescription = "Select vaults",
                                    tint               = if (sessionActiveVaultIds.isNotEmpty())
                                                             MaterialTheme.colorScheme.primary
                                                         else
                                                             MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            DropdownMenu(
                                expanded          = showVaultMenu,
                                onDismissRequest  = { showVaultMenu = false },
                            ) {
                                Text(
                                    text     = "Active vaults",
                                    style    = MaterialTheme.typography.labelSmall,
                                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                                )
                                HorizontalDivider()
                                allVaults.forEach { vault ->
                                    val active = vault.id in sessionActiveVaultIds
                                    DropdownMenuItem(
                                        text         = { Text(vault.name) },
                                        onClick      = { viewModel.toggleVaultForSession(vault.id) },
                                        leadingIcon  = {
                                            if (active) Icon(Icons.Default.Check, null,
                                                             tint = MaterialTheme.colorScheme.primary)
                                            else Spacer(Modifier.size(24.dp))
                                        },
                                    )
                                }
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
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
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            if (turnsWithEvents.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Start a conversation", style = MaterialTheme.typography.bodyMedium,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    state               = listState,
                    modifier            = Modifier.fillMaxSize(),
                    contentPadding      = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
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
    } // closes outer imePadding Box
}
