package com.vela.app.ui.chat

import com.vela.core.ui.TranscriptMessage
import com.vela.hosttools.AmplifierToolLoopClient
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Bridges the real [AmplifierToolLoopClient] (C1 tool-loop, goal item 4) into
 * the [TranscriptMessage] shape [com.vela.core.ui.ChatTranscript] renders.
 */
class ChatViewModel(private val toolLoopClient: AmplifierToolLoopClient) {

    private val _messages = MutableStateFlow<List<TranscriptMessage>>(emptyList())
    val messages: StateFlow<List<TranscriptMessage>> = _messages.asStateFlow()

    fun sendMessage(scope: CoroutineScope, text: String) {
        if (text.isBlank()) return
        val userMessage = TranscriptMessage(
            id = UUID.randomUUID().toString(),
            speaker = TranscriptMessage.Speaker.USER,
            text = text,
        )
        _messages.update { it + userMessage }

        scope.launch {
            val responseText = try {
                toolLoopClient.runTurn(text).finalContent
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
}
