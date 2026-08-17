package com.vela.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.vela.app.ui.activity.LiveActivityScreen
import com.vela.app.ui.activity.LiveActivityUiState
import com.vela.core.ui.AttentionCard
import com.vela.core.ui.CardDeck
import com.vela.core.ui.CardDecision
import com.vela.core.ui.ChatTranscript
import com.vela.core.ui.TranscriptMessage

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

private val mockCards = listOf(
    AttentionCard(id = "1", title = "Reply to Alex", summary = "Draft a reply about the Q3 offsite."),
    AttentionCard(id = "2", title = "Approve calendar hold", summary = "1:1 with Priya moved to 3pm."),
    AttentionCard(id = "3", title = "Review expense report", summary = "Submitted by finance for October."),
)

private val mockMessages = listOf(
    TranscriptMessage("m1", TranscriptMessage.Speaker.USER, "What's on my plate today?"),
    TranscriptMessage(
        "m2",
        TranscriptMessage.Speaker.ASSISTANT,
        "You have 3 items in your queue: a reply to Alex, a calendar hold, and an expense report.",
    ),
)

@Composable
fun VelaScaffoldRoot() {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Queue", "Chat", "Activity")

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
                var cards by remember { mutableStateOf(mockCards) }
                CardDeck(
                    cards = cards,
                    onDecision = { card, _ -> cards = cards.filterNot { it.id == card.id } },
                )
            } else if (selectedTab == 1) {
                ChatTranscript(messages = mockMessages)
            } else {
                // Residual (see goal Task 2): a real `LiveActivityViewModel`
                // needs a `baseUrl`/bearer token from app-level config/DI
                // that doesn't exist yet in this lane's scope. Render an
                // empty state wired to no-op callbacks so the tab and
                // composable are exercised end-to-end; real server wiring
                // is out of scope here.
                LiveActivityScreen(
                    state = LiveActivityUiState(),
                    onApprove = {},
                    onDecline = {},
                )
            }
        }
    }
}
