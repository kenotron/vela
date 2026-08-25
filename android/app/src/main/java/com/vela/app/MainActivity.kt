package com.vela.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.vela.app.ui.activity.LiveActivityScreen
import com.vela.app.ui.activity.LiveActivityUiState
import com.vela.app.ui.activity.LiveActivityViewModel
import com.vela.app.ui.chat.ChatViewModel
import com.vela.app.ui.queue.QueueViewModel
import com.vela.core.ui.CardDeck
import com.vela.core.ui.ChatTranscript

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    VelaScaffoldRoot()
                }
            }
        }
    }
}

@Composable
fun VelaScaffoldRoot() {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Queue", "Chat", "Activity")

    val context = LocalContext.current
    val container = remember { VelaAppContainer.getInstance(context) }
    val coroutineScope = rememberCoroutineScope()

    val queueViewModel = remember { QueueViewModel(container.ledgerRepository) }
    val chatViewModel = remember { ChatViewModel(container.toolLoopClient, container.ledgerRepository) }
    val liveActivityViewModel = remember { LiveActivityViewModel(container.c2EventClient) }

    LaunchedEffect(Unit) {
        queueViewModel.start(coroutineScope)
        // Issue #35: the wiring point from the ledger's pending approvals to the chat
        // surface -- every live AttentionCandidate the ledger produces is surfaced as a
        // distinct, flagged entry in chatViewModel.messages automatically.
        chatViewModel.observeLedgerApprovals(coroutineScope)
        liveActivityViewModel.start(
            coroutineScope,
            baseUrl = BuildConfig.VELA_SERVER_BASE_URL,
            bearerToken = BuildConfig.VELA_SERVER_BEARER_TOKEN,
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab, modifier = Modifier.testTag("main_tab_row")) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { androidx.compose.material3.Text(title) },
                    modifier = Modifier.testTag("tab_$title"),
                )
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(8.dp)) {
            if (selectedTab == 0) {
                val cards by queueViewModel.cards.collectAsState()
                // CardDeck.kt's empty-state branch was fixed (goal #29) to use a
                // structural if/else instead of an early `return@Box`, so the
                // SlotTable crash this workaround guarded against no longer applies.
                // CardDeck is now always composed directly.
                CardDeck(
                    cards = cards,
                    onDecision = { card, decision -> queueViewModel.onDecision(coroutineScope, card, decision) },
                )
            } else if (selectedTab == 1) {
                val messages by chatViewModel.messages.collectAsState()
                var chatInput by remember { mutableStateOf("") }

                Column(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        ChatTranscript(
                            messages = messages,
                            onApprove = { messageId -> chatViewModel.resolveApproval(coroutineScope, messageId, approved = true) },
                            onDeny = { messageId -> chatViewModel.resolveApproval(coroutineScope, messageId, approved = false) },
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = chatInput,
                            onValueChange = { chatInput = it },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("chat_input"),
                            placeholder = { Text("Message") },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(
                                onSend = {
                                    chatViewModel.sendMessage(coroutineScope, chatInput)
                                    chatInput = ""
                                },
                            ),
                        )
                        Button(
                            onClick = {
                                chatViewModel.sendMessage(coroutineScope, chatInput)
                                chatInput = ""
                            },
                            modifier = Modifier.testTag("chat_send"),
                        ) {
                            Text("Send")
                        }
                        // Issue #61: real TierCoordinator.handle() -> ChatViewModel.ingestVoiceTurn
                        // wiring, gated behind a distinct action since there is no mic-capture
                        // pipeline in this app yet (out of scope -- see goal SCOPE-OUTS). This is
                        // a genuine, working utterance-string -> ChatViewModel code path; it just
                        // simulates the utterance source (this text field) instead of real audio.
                        Button(
                            onClick = {
                                val utterance = chatInput
                                chatInput = ""
                                chatViewModel.ingestVoiceTurn(
                                    coroutineScope,
                                    utterance,
                                    container.tierCoordinator.handle(utterance),
                                )
                            },
                            modifier = Modifier.testTag("voice_turn_test"),
                        ) {
                            Text("Voice Turn (test)")
                        }
                    }
                }
            } else {
                val activityState by liveActivityViewModel.uiState.collectAsState()
                LiveActivityScreen(
                    state = activityState,
                    onApprove = { liveActivityViewModel.onApprovalResolved(it) },
                    onDecline = { liveActivityViewModel.onApprovalResolved(it) },
                )
            }
        }
    }
}
