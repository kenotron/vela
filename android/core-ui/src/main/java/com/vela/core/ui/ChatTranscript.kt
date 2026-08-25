package com.vela.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * A single chat/transcript message. Source-agnostic: may originate from a
 * typed chat message, a voice turn (see `ChatViewModel` in `android/app`,
 * the real backing source for issue #33), or an approval prompt surfaced
 * from the attention/ledger gate (issue #18, issue #35). [Chat] messages
 * from either input path render identically -- once a message becomes a
 * [Chat], its origin (typed vs. spoken) is indistinguishable. [Approval]
 * messages are visually and semantically distinct so they cannot be
 * mistaken for a normal assistant message.
 */
sealed class TranscriptMessage {
    abstract val id: String

    /** A normal chat bubble -- either a user-typed or user-spoken utterance, or an assistant reply. */
    data class Chat(
        override val id: String,
        val speaker: Speaker,
        val text: String,
    ) : TranscriptMessage()

    /**
     * A distinct, flagged transcript entry representing an approval gate
     * decision (see `AttentionCandidate` / `LedgerRepository.LedgerEntry` in
     * `core-domain`'s `notification` package). [entryId] correlates back to
     * the ledger entry that raised this prompt. While [status] is
     * [Status.PENDING], [ChatTranscript] renders approve/deny actions. Once
     * resolved, this entry's [status] is updated in place -- it is never
     * removed from the transcript -- and [com.vela.app.ui.chat.ChatViewModel]
     * additionally appends a follow-up [Chat] entry recording the outcome, so
     * the resolution is never silently invisible.
     */
    data class Approval(
        override val id: String,
        val entryId: String,
        val promptText: String,
        val status: Status = Status.PENDING,
    ) : TranscriptMessage() {
        enum class Status { PENDING, APPROVED, DENIED }
    }

    enum class Speaker { USER, ASSISTANT }
}

/**
 * Chat/transcript surface. Pure and stateless: renders whatever [messages]
 * list it is given. The caller (see `MainActivity`'s `VelaScaffoldRoot`) is
 * responsible for supplying a real, injectable source -- e.g.
 * `ChatViewModel.messages.collectAsState()` -- rather than a hardcoded list.
 * A mock list may still be used for `@Preview` fixtures.
 *
 * [onApprove] and [onDeny] are invoked with a pending [TranscriptMessage.Approval]'s
 * [TranscriptMessage.id] when the user taps the corresponding action; both
 * default to no-ops so existing callers that don't yet wire approval
 * resolution keep compiling unchanged.
 */
@Composable
fun ChatTranscript(
    messages: List<TranscriptMessage>,
    modifier: Modifier = Modifier,
    onApprove: (String) -> Unit = {},
    onDeny: (String) -> Unit = {},
) {
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .testTag("chat_transcript")
            .semantics { contentDescription = "Chat transcript" },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(messages, key = { it.id }) { message ->
            when (message) {
                is TranscriptMessage.Chat -> ChatBubble(message)
                is TranscriptMessage.Approval -> ApprovalBubble(message, onApprove, onDeny)
            }
        }
    }
}

@Composable
private fun ChatBubble(message: TranscriptMessage.Chat) {
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

@Composable
private fun ApprovalBubble(
    message: TranscriptMessage.Approval,
    onApprove: (String) -> Unit,
    onDeny: (String) -> Unit,
) {
    val statusLabel = when (message.status) {
        TranscriptMessage.Approval.Status.PENDING -> "Approval needed"
        TranscriptMessage.Approval.Status.APPROVED -> "Approved"
        TranscriptMessage.Approval.Status.DENIED -> "Denied"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .background(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(8.dp),
            )
            .padding(12.dp)
            .testTag("chat_approval_${message.id}")
            .semantics {
                contentDescription = "$statusLabel: ${message.promptText}"
            },
    ) {
        Text(text = statusLabel, style = MaterialTheme.typography.labelMedium)
        Text(text = message.promptText, style = MaterialTheme.typography.bodyMedium)

        if (message.status == TranscriptMessage.Approval.Status.PENDING) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { onApprove(message.id) },
                    modifier = Modifier.testTag("chat_approval_${message.id}_approve"),
                ) {
                    Text("Approve")
                }
                OutlinedButton(
                    onClick = { onDeny(message.id) },
                    modifier = Modifier.testTag("chat_approval_${message.id}_deny"),
                ) {
                    Text("Deny")
                }
            }
        }
    }
}
