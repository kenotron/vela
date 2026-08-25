package com.vela.app.ui.chat

import com.vela.core.ui.TranscriptMessage
import com.vela.hosttools.AmplifierToolLoopClient
import com.vela.voice.handoff.TierCoordinator.TierEvent
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Bridges the real [AmplifierToolLoopClient] (C1 tool-loop, goal item 4),
 * real voice turns (see [ingestVoiceTurn], issue #33), and approval-gate
 * prompts (see [postApprovalPrompt] / [resolveApproval], issue #35) into
 * the single [TranscriptMessage] stream [com.vela.core.ui.ChatTranscript]
 * renders. A message typed via [sendMessage], a turn narrated via
 * [ingestVoiceTurn], and an approval prompt posted via [postApprovalPrompt]
 * all land in the same [messages] stream, appended in causal/collection
 * order -- there is exactly one session-wide transcript, not several.
 */
class ChatViewModel(private val toolLoopClient: AmplifierToolLoopClient) {

    private val _messages = MutableStateFlow<List<TranscriptMessage>>(emptyList())
    val messages: StateFlow<List<TranscriptMessage>> = _messages.asStateFlow()

    fun sendMessage(scope: CoroutineScope, text: String) {
        if (text.isBlank()) return

        // Snapshot the conversation-so-far as role/content pairs BEFORE adding this new
        // user message, so it becomes the history sent alongside this turn. Without this,
        // AmplifierToolLoopClient.runTurn() (which is stateless per call by design) sends
        // only the newest message each time -- messages sit together in the UI list but the
        // server genuinely never sees earlier turns, a real bug this fixes.
        //
        // Only Chat entries carry role/content turns the server understands; Approval
        // entries are a chat-surface presentation concern and are excluded from history.
        val historyForThisTurn = _messages.value
            .filterIsInstance<TranscriptMessage.Chat>()
            .map { msg ->
                val role = if (msg.speaker == TranscriptMessage.Speaker.USER) "user" else "assistant"
                role to msg.text
            }

        val userMessage = TranscriptMessage.Chat(
            id = UUID.randomUUID().toString(),
            speaker = TranscriptMessage.Speaker.USER,
            text = text,
        )
        _messages.update { it + userMessage }

        scope.launch {
            val responseText = try {
                toolLoopClient.runTurn(text, history = historyForThisTurn).finalContent
            } catch (e: Exception) {
                "Error contacting server: ${e.message}"
            }
            _messages.update {
                it + TranscriptMessage.Chat(
                    id = UUID.randomUUID().toString(),
                    speaker = TranscriptMessage.Speaker.ASSISTANT,
                    text = responseText,
                )
            }
        }
    }

    /**
     * Folds a real voice turn into the same transcript [messages] stream
     * [sendMessage] appends to (issue #33). The caller has already spoken
     * [utterance] into [com.vela.voice.handoff.TierCoordinator.handle] and
     * passes the resulting [events] flow here; this appends the user's
     * utterance immediately (so it's visible the moment the turn starts,
     * matching how [sendMessage] appends the user message before the
     * response arrives), then appends one assistant message per relevant
     * [TierEvent] as they stream in, preserving causal order.
     *
     * [TierEvent.RespondDirectly] carries no answer text of its own (the
     * fast-tier answer is spoken elsewhere in the pipeline -- see
     * `TierCoordinator`'s kdoc) so it does not append a transcript entry by
     * itself; every other event maps directly to an assistant line.
     */
    fun ingestVoiceTurn(scope: CoroutineScope, utterance: String, events: Flow<TierEvent>) {
        _messages.update {
            it + TranscriptMessage.Chat(
                id = UUID.randomUUID().toString(),
                speaker = TranscriptMessage.Speaker.USER,
                text = utterance,
            )
        }

        scope.launch {
            events.collect { event ->
                val text = when (event) {
                    is TierEvent.RespondDirectly -> null
                    is TierEvent.Acknowledged -> event.acknowledgement
                    is TierEvent.Narrating -> event.text
                    is TierEvent.Completed -> event.resultText
                    is TierEvent.Failed -> "Error: ${event.message}"
                }
                if (text != null) {
                    _messages.update {
                        it + TranscriptMessage.Chat(
                            id = UUID.randomUUID().toString(),
                            speaker = TranscriptMessage.Speaker.ASSISTANT,
                            text = text,
                        )
                    }
                }
            }
        }
    }

    /**
     * Surfaces an approval-gate decision (issue #18's `AttentionCandidate` /
     * `LedgerRepository.LedgerEntry`) as a distinct, flagged entry in the
     * chat transcript, per issue #35. [entryId] is the ledger entry id this
     * prompt corresponds to -- callers deriving this from an
     * `AttentionCandidate` should pass `candidate.entry.id`. Returns the new
     * transcript message's id, which the caller passes back to
     * [resolveApproval] once the user responds (via a tap in
     * [com.vela.core.ui.ChatTranscript]'s approve/deny actions, or via an
     * existing voice-response path such as `ApprovalVoiceBridge`).
     */
    fun postApprovalPrompt(entryId: String, promptText: String): String {
        val messageId = UUID.randomUUID().toString()
        _messages.update {
            it + TranscriptMessage.Approval(
                id = messageId,
                entryId = entryId,
                promptText = promptText,
            )
        }
        return messageId
    }

    /**
     * Records the user's response to a previously-posted approval prompt
     * ([postApprovalPrompt]). The original [TranscriptMessage.Approval] entry
     * is updated in place to [TranscriptMessage.Approval.Status.APPROVED] or
     * [TranscriptMessage.Approval.Status.DENIED] -- it is never removed --
     * and a follow-up [TranscriptMessage.Chat] entry recording the outcome is
     * appended, so the resolution is always visible as its own transcript
     * entry rather than a silently vanished prompt. A [messageId] that does
     * not match any pending approval (already resolved, or unknown) is a
     * no-op: no follow-up entry is appended and no other entry is touched.
     */
    fun resolveApproval(messageId: String, approved: Boolean) {
        val current = _messages.value
        val pending = current.firstOrNull {
            it is TranscriptMessage.Approval &&
                it.id == messageId &&
                it.status == TranscriptMessage.Approval.Status.PENDING
        } as? TranscriptMessage.Approval ?: return

        val resolvedStatus = if (approved) {
            TranscriptMessage.Approval.Status.APPROVED
        } else {
            TranscriptMessage.Approval.Status.DENIED
        }

        _messages.update { list ->
            list.map { msg ->
                if (msg is TranscriptMessage.Approval && msg.id == messageId) {
                    msg.copy(status = resolvedStatus)
                } else {
                    msg
                }
            } + TranscriptMessage.Chat(
                id = UUID.randomUUID().toString(),
                speaker = TranscriptMessage.Speaker.ASSISTANT,
                text = if (approved) {
                    "Approved: ${pending.promptText}"
                } else {
                    "Denied: ${pending.promptText}"
                },
            )
        }
    }
}
