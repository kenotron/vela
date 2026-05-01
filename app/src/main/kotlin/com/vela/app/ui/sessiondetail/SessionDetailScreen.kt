package com.vela.app.ui.sessiondetail

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
    val inputText     by viewModel.inputText.collectAsStateWithLifecycle()
    val attachments   by viewModel.attachments.collectAsStateWithLifecycle()
    val isRecording   by viewModel.isRecording.collectAsStateWithLifecycle()
    val approvalReq   by viewModel.approvalRequest.collectAsStateWithLifecycle()
    val statusMessage by viewModel.statusMessage.collectAsStateWithLifecycle()
    val sessionName   by viewModel.sessionName.collectAsStateWithLifecycle()

    val isRunning = isLoading || turns.any { !it.isUser && it.toolCalls.any { tc -> tc.isRunning } }

    val ctx = LocalContext.current
    val listState = rememberLazyListState()

    // Auto-scroll to bottom when new turns arrive
    LaunchedEffect(turns.size) {
        if (turns.isNotEmpty()) {
            listState.animateScrollToItem(turns.size - 1)
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
        containerColor      = VelaColors.Abyss,
        contentWindowInsets = WindowInsets(0), // root Scaffold already handles system bars; avoid double nav bar padding
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

                // Breathing "..." indicator while streaming
                if (isLoading) {
                    item {
                        Text(
                            text  = "…",
                            style = MaterialTheme.typography.bodyLarge,
                            color = VelaColors.TextTertiary,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }

                item { Spacer(Modifier.height(8.dp)) }
            }

            // ── Session input bar ─────────────────────────────────────────
            // ── Retry / status strip ─────────────────────────────────────────
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

            SessionInputBar(
                text               = inputText,
                onTextChange       = viewModel::updateInputText,
                onSend             = {
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
