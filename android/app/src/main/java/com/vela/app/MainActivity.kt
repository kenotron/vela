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
    val chatViewModel = remember { ChatViewModel(container.toolLoopClient) }
    val liveActivityViewModel = remember { LiveActivityViewModel(container.c2EventClient) }

    LaunchedEffect(Unit) {
        queueViewModel.start(coroutineScope)
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
                // NOTE: com.vela.core.ui.CardDeck's empty-state branch (topCard == null)
                // uses an early `return@Box` that corrupts Compose's SlotTable on this
                // toolchain (androidx.compose.runtime 1.6.1 / compiler 1.5.8), causing a
                // reproducible `ArrayIndexOutOfBoundsException` in SlotTableKt.key at
                // first composition whenever cards is empty -- which it always is before
                // the ledger's first emission. Root cause lives in android/core-ui/
                // (out of this lane's file ownership), so it is not edited here; recorded
                // as a residual for core-ui. As an app-side workaround, CardDeck is only
                // invoked once cards is non-empty (verified crash-free); the empty state
                // is rendered locally with the same semantics/testTag CardDeck used, so
                // instrumented tests asserting on "card_deck" still pass.
                if (cards.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("card_deck")
                            .semantics { contentDescription = "Attention queue card deck" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = "No pending items", modifier = Modifier.testTag("card_deck_empty"))
                    }
                } else {
                    CardDeck(
                        cards = cards,
                        onDecision = { card, decision -> queueViewModel.onDecision(coroutineScope, card, decision) },
                    )
                }
            } else if (selectedTab == 1) {
                val messages by chatViewModel.messages.collectAsState()
                var chatInput by remember { mutableStateOf("") }

                Column(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        ChatTranscript(messages = messages)
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
