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
 * Bridges the real [AmplifierToolLoopClient] (C1 tool-loop, goal item 4) and
 * real voice turns (see [ingestVoiceTurn], issue #33) into the single
 * [TranscriptMessage] shape [com.vela.core.ui.ChatTranscript] renders. A
 * message typed via [sendMessage] and a turn narrated via [ingestVoiceTurn]
 * both land in the same [messages] stream, appended in causal/collection
 * order -- there is exactly one session-wide transcript, not two.
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
        val historyForThisTurn = _messages.value.map { msg ->
            val role = if (msg.speaker == TranscriptMessage.Speaker.USER) "user" else "assistant"
            role to msg.text
        }

        val userMessage = TranscriptMessage(
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
                it + TranscriptMessage(
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
            it + TranscriptMessage(
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
                        it + TranscriptMessage(
                            id = UUID.randomUUID().toString(),
                            speaker = TranscriptMessage.Speaker.ASSISTANT,
                            text = text,
                        )
                    }
                }
            }
        }
    }
}
