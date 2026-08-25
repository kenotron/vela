package com.vela.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * A single chat/transcript message. Source-agnostic: may originate from a
 * typed chat message or from a voice turn (see `ChatViewModel` in
 * `android/app`, which is the real backing source for issue #33). Once a
 * message becomes a [TranscriptMessage], its origin (typed vs. spoken) is
 * indistinguishable -- both render identically here.
 */
data class TranscriptMessage(
    val id: String,
    val speaker: Speaker,
    val text: String,
) {
    enum class Speaker { USER, ASSISTANT }
}

/**
 * Chat/transcript surface. Pure and stateless: renders whatever [messages]
 * list it is given. The caller (see `MainActivity`'s `VelaScaffoldRoot`) is
 * responsible for supplying a real, injectable source -- e.g.
 * `ChatViewModel.messages.collectAsState()` -- rather than a hardcoded list.
 * A mock list may still be used for `@Preview` fixtures.
 */
@Composable
fun ChatTranscript(
    messages: List<TranscriptMessage>,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .testTag("chat_transcript")
            .semantics { contentDescription = "Chat transcript" },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(messages, key = { it.id }) { message ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .testTag("chat_message_${message.id}"),
            ) {
                Text(
                    text = if (message.speaker == TranscriptMessage.Speaker.USER) "You" else "Vela",
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(text = message.text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
