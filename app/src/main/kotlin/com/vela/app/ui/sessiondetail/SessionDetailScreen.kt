package com.vela.app.ui.sessiondetail

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.vela.app.ui.approval.ApprovalGateSheet
import com.vela.app.ui.approval.ApprovalSheetViewModel
import com.vela.app.ui.theme.VelaColors

/**
 * Screen 4: Session Detail — Turn History + Session Input Bar.
 *
 * Design: DESIGN.md §8 (Screen 4)
 * Layout:
 *   - App bar: back + session title + running dot
 *   - Session title hero area
 *   - LazyColumn of user/agent turns (scrollable, auto-scrolls to bottom)
 *   - SessionInputBar pinned at the bottom (keyboard-aware via imePadding)
 *   - ApprovalGateSheet modal when approval is requested
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailScreen(
    navController: NavController,
    viewModel: SessionDetailViewModel = hiltViewModel(),
) {
    val turns         by viewModel.turns.collectAsStateWithLifecycle()
    val isLoading     by viewModel.isLoading.collectAsStateWithLifecycle()
    val sessionStatus by viewModel.sessionStatus.collectAsStateWithLifecycle()
    val inputText     by viewModel.inputText.collectAsStateWithLifecycle()
    val attachments   by viewModel.attachments.collectAsStateWithLifecycle()
    val isRecording   by viewModel.isRecording.collectAsStateWithLifecycle()
    val approvalReq   by viewModel.approvalRequest.collectAsStateWithLifecycle()
    val statusMessage by viewModel.statusMessage.collectAsStateWithLifecycle()
    val sessionName    by viewModel.sessionName.collectAsStateWithLifecycle()
    val sessionDeleted by viewModel.sessionDeleted.collectAsStateWithLifecycle()

    var showGearMenu      by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // Navigate back when session is deleted
    LaunchedEffect(sessionDeleted) {
        if (sessionDeleted) navController.popBackStack()
    }

    val isRunning = isLoading

    val ctx = LocalContext.current
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current

    // Scroll to bottom once on initial load so you land at the latest turn
    var didInitialScroll by remember { mutableStateOf(false) }
    LaunchedEffect(turns.isNotEmpty()) {
        if (turns.isNotEmpty() && !didInitialScroll) {
            listState.scrollToItem(turns.size)   // +1 accounts for the title hero item
            didInitialScroll = true
        }
    }

    // Reactive send-scroll: captures turns.size when user taps Send, then fires
    // animateScrollToItem once the new user turn actually appears in the list.
    // Replaces the old delay(80) approach which raced against the async StateFlow update.
    var turnsSizeAtSend by remember { mutableStateOf(-1) }
    LaunchedEffect(turns.size) {
        if (turnsSizeAtSend >= 0 && turns.size > turnsSizeAtSend) {
            // New turn landed — scroll to it. +1 for the title hero item offset.
            listState.animateScrollToItem(turnsSizeAtSend + 1)
            turnsSizeAtSend = -1
        }
    }

    // Photo picker
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? -> uri?.let { viewModel.addAttachment(it) } }

    // Mic permission launcher
    val recordLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.startVoiceRecording()
    }

    Scaffold(
        containerColor = VelaColors.Abyss,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text  = when {
                            sessionName.isNotBlank() -> sessionName
                            else -> turns.firstOrNull { it.isUser }?.text?.take(40)
                                ?.let { if (it.length == 40) "$it…" else it }
                                ?: "Session"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        color = VelaColors.TextPrimary,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector        = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint               = VelaColors.Accent,
                        )
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showGearMenu = true }) {
                            Icon(
                                imageVector        = Icons.Default.Settings,
                                contentDescription = "Session options",
                                tint               = VelaColors.TextSecondary,
                            )
                        }
                        DropdownMenu(
                            expanded         = showGearMenu,
                            onDismissRequest = { showGearMenu = false },
                        ) {
                            DropdownMenuItem(
                                text    = { Text("Delete session", color = Color(0xFFF38BA8)) },
                                onClick = {
                                    showGearMenu      = false
                                    showDeleteConfirm = true
                                },
                            )
                        }
                    }
                    if (isRunning) RunningDotIndicator()
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = VelaColors.Abyss),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding(),
        ) {
            // ── Turn list ─────────────────────────────────────────────────
            LazyColumn(
                state           = listState,
                modifier        = Modifier.weight(1f).fillMaxWidth(),
                contentPadding  = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Session title hero
                item {
                    SessionTitleArea(
                        title     = turns.firstOrNull { it.isUser }?.text?.take(60) ?: "New Session",
                        isRunning = isRunning,
                    )
                }

                items(turns) { turn ->
                    if (turn.isUser) UserTurnItem(text = turn.text)
                    else             AgentTurnItem(content = turn)
                }

                // Pulsing three-dot typing indicator while streaming
                if (isLoading) {
                    item { TypingIndicator() }
                }

                // Dynamic flex-filler: viewport minus the measured height of the last
                // user turn + last assistant turn. Shrinks as streaming content grows,
                // ensuring the user message stays pinned at the top without trailing void.
                item {
                    val info = listState.layoutInfo
                    val vh   = info.viewportEndOffset - info.viewportStartOffset
                    val spacerPx by remember(info) {
                        derivedStateOf {
                            // Sum height of the 3 items just before this spacer
                            val thisIdx   = info.totalItemsCount - 1
                            val prevItems = info.visibleItemsInfo.filter { it.index in (thisIdx - 3) until thisIdx }
                            val prevH     = prevItems.sumOf { it.size } + prevItems.size * 16 // 16px ≈ spacedBy(16.dp) in px
                            maxOf(vh - prevH, 0)
                        }
                    }
                    Spacer(Modifier.height(with(LocalDensity.current) { spacerPx.toDp() }))
                }
            }

            // ── Session input bar ─────────────────────────────────────────
            // ── Retry / status strip ─────────────────────────────────────────
            // ── RESUMING strip ───────────────────────────────────────────────
            if (sessionStatus == SessionStatus.RESUMING) {
                Surface(
                    color    = VelaColors.SurfaceRaised,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier              = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier    = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color       = VelaColors.Running,
                        )
                        Text(
                            text  = "Resuming session…",
                            style = MaterialTheme.typography.labelSmall,
                            color = VelaColors.TextSecondary,
                        )
                    }
                }
            }

            // ── Error / ended state ───────────────────────────────────────────
            if (sessionStatus == SessionStatus.ERROR) {
                OutlinedButton(
                    onClick  = { viewModel.retry() },
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(vertical = 8.dp),
                    shape    = RoundedCornerShape(20.dp),
                    border   = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    Text("↺ Try again")
                }
            }

            statusMessage?.let { msg ->
                Surface(
                    color    = VelaColors.SurfaceRaised,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text     = msg,
                        style    = MaterialTheme.typography.labelSmall,
                        color    = VelaColors.Running,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
            }

            // ── Steer strip: visible while streaming + input non-blank ─────────
            // Lets the user redirect the running AI by injecting a mid-loop message.
            if (isLoading && inputText.isNotBlank()) {
                Surface(
                    color    = VelaColors.SurfaceRaised,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier              = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment     = Alignment.CenterVertically,
                    ) {
                        Text(
                            text  = "Redirect AI mid-run",
                            style = MaterialTheme.typography.labelSmall,
                            color = VelaColors.Running,
                        )
                        TextButton(
                            onClick = {
                                keyboardController?.hide()
                                viewModel.steer(inputText)
                                viewModel.clearInputText()
                            }
                        ) {
                            Text("→ Steer", color = VelaColors.Running)
                        }
                    }
                }
            }

            // ── Inline approval card ──────────────────────────────────────────
            approvalReq?.let { (approvalId, question) ->
                Surface(
                    color    = Color(0xFF2A2000),
                    modifier = Modifier.fillMaxWidth(),
                    border   = BorderStroke(1.dp, Color(0xFFFAB387)),
                    shape    = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text  = "⚡ Needs your input",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFFAB387),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text  = question,
                            style = MaterialTheme.typography.bodySmall,
                            color = VelaColors.TextPrimary,
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { viewModel.approveRequest(approvalId) },
                                colors  = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFFAB387),
                                    contentColor   = Color(0xFF1C1B1F),
                                ),
                            ) { Text("Approve") }
                            OutlinedButton(onClick = { viewModel.denyRequest(approvalId) }) {
                                Text("Deny")
                            }
                        }
                    }
                }
            }

            SessionInputBar(
                text               = inputText,
                onTextChange       = viewModel::updateInputText,
                onSend             = {
                    // Capture size before send — LaunchedEffect(turns.size) fires the
                    // scroll once the new user turn actually appears in the StateFlow.
                    turnsSizeAtSend = turns.size
                    keyboardController?.hide()
                    viewModel.sendMessage()
                },
                onVoiceStart       = {
                    val granted = ContextCompat.checkSelfPermission(
                        ctx, Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                    if (granted) viewModel.startVoiceRecording()
                    else recordLauncher.launch(Manifest.permission.RECORD_AUDIO)
                },
                onVoiceStop        = viewModel::stopVoiceRecording,
                isRecording        = isRecording,
                onAttachImage      = {
                    imagePicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                attachments        = attachments,
                onRemoveAttachment = viewModel::removeAttachment,
                isLoading          = isLoading,
                hasOpenAiKey       = viewModel.hasOpenAiKey,
            )
        }

        // ── Approval gate modal sheet ─────────────────────────────────────
        approvalReq?.let { (approvalId, question) ->
            ApprovalGateSheet(
                request   = ApprovalSheetViewModel.ApprovalRequest(
                    sessionId   = approvalId,
                    question    = question,
                    contextText = null,
                ),
                onApprove = { viewModel.approveRequest(approvalId) },
                onDeny    = { viewModel.denyRequest(approvalId) },
            )
        }

        // ── Delete session confirm ─────────────────────────────────────────
        if (showDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                containerColor   = VelaColors.SurfacePeak,
                title = {
                    Text("Delete session?", style = MaterialTheme.typography.titleMedium, color = VelaColors.TextPrimary)
                },
                text = {
                    Text(
                        "This session and its history will be permanently removed.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = VelaColors.TextSecondary,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        showDeleteConfirm = false
                        viewModel.deleteSession()
                    }) {
                        Text("Delete", color = Color(0xFFF38BA8))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirm = false }) {
                        Text("Cancel", color = VelaColors.TextSecondary)
                    }
                },
            )
        }
    }
}

// ── Private sub-composables ───────────────────────────────────────────────────

/**
 * Session title area displayed below the app bar.
 */
@Composable
private fun SessionTitleArea(
    title: String,
    isRunning: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text  = title,
            style = MaterialTheme.typography.displayMedium,
            color = VelaColors.TextPrimary,
        )

        val (pillColor, pillText, pillTextColor) = if (isRunning) {
            Triple(VelaColors.RunningContainer, "RUNNING", VelaColors.RunningOnContainer)
        } else {
            Triple(VelaColors.DoneContainer, "DONE", VelaColors.DoneOnContainer)
        }

        Surface(
            shape    = RoundedCornerShape(8.dp),
            color    = pillColor,
            modifier = Modifier.height(26.dp),
        ) {
            Box(
                modifier         = Modifier.padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text  = pillText,
                    style = MaterialTheme.typography.labelSmall,
                    color = pillTextColor,
                )
            }
        }
    }
}

/**
 * Amber breathing dot shown in the app bar when session is RUNNING.
 */
@Composable
private fun RunningDotIndicator(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(end = 16.dp)
            .size(8.dp)
            .background(VelaColors.Running, CircleShape),
    )
}
