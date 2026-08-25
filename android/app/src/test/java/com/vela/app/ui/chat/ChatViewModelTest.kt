package com.vela.app.ui.chat

import com.vela.core.domain.HostToolRegistry
import com.vela.core.ui.TranscriptMessage
import com.vela.hosttools.AmplifierToolLoopClient
import com.vela.voice.handoff.TierCoordinator.TierEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [AmplifierToolLoopClient] is a concrete class (real HTTP client), not an
 * interface, so it can't be faked the way `FakeLedgerRepository` fakes an
 * interface in `QueueViewModelTest`. These tests never invoke [sendMessage]
 * against it (which would make a real network call); instead, for tests that
 * need pre-existing chat-sourced messages in the stream, we seed
 * [ChatViewModel]'s private `_messages` StateFlow directly via reflection --
 * the same technique `QueueViewModelTest.seedCard` uses. [ingestVoiceTurn]
 * itself has no dependency on the tool loop client, so it is exercised
 * directly against a real [ChatViewModel] instance.
 */
private val emptyRegistry = object : HostToolRegistry {
    override fun all() = emptyList<com.vela.core.domain.HostTool>()
    override fun find(name: String) = null
}

private fun dummyClient(): AmplifierToolLoopClient = AmplifierToolLoopClient(
    baseUrl = "http://unused.invalid",
    apiKey = "unused",
    registry = emptyRegistry,
    clientSessionId = "test-session",
)

/** Seeds [viewModel]'s messages StateFlow directly for test setup (bypassing sendMessage/network). */
private fun seedMessages(viewModel: ChatViewModel, vararg messages: TranscriptMessage) {
    val field = ChatViewModel::class.java.getDeclaredField("_messages")
    field.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    val flow = field.get(viewModel) as MutableStateFlow<List<TranscriptMessage>>
    flow.value = messages.toList()
}

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    @Test
    fun `ingestVoiceTurn appends the utterance then each tier event as an assistant message`() = runTest {
        val viewModel = ChatViewModel(dummyClient())

        val events = flow {
            emit(TierEvent.Acknowledged("what's the weather", "I'll get on that."))
            emit(TierEvent.Narrating("Checking forecast..."))
            emit(TierEvent.Completed("It's sunny."))
        }

        viewModel.ingestVoiceTurn(this, "what's the weather", events)
        advanceUntilIdle()

        val messages = viewModel.messages.value
        assertEquals(4, messages.size)
        assertEquals(TranscriptMessage.Speaker.USER, messages[0].speaker)
        assertEquals("what's the weather", messages[0].text)
        assertEquals(TranscriptMessage.Speaker.ASSISTANT, messages[1].speaker)
        assertEquals("I'll get on that.", messages[1].text)
        assertEquals(TranscriptMessage.Speaker.ASSISTANT, messages[2].speaker)
        assertEquals("Checking forecast...", messages[2].text)
        assertEquals(TranscriptMessage.Speaker.ASSISTANT, messages[3].speaker)
        assertEquals("It's sunny.", messages[3].text)
    }

    @Test
    fun `ingestVoiceTurn RespondDirectly appends only the user utterance, no extra assistant line`() = runTest {
        val viewModel = ChatViewModel(dummyClient())

        val events = flow { emit(TierEvent.RespondDirectly("what time is it")) }

        viewModel.ingestVoiceTurn(this, "what time is it", events)
        advanceUntilIdle()

        val messages = viewModel.messages.value
        assertEquals(1, messages.size)
        assertEquals(TranscriptMessage.Speaker.USER, messages[0].speaker)
        assertEquals("what time is it", messages[0].text)
    }

    @Test
    fun `ingestVoiceTurn Failed maps to an error-prefixed assistant message`() = runTest {
        val viewModel = ChatViewModel(dummyClient())

        val events = flow { emit(TierEvent.Failed("slow tier timed out")) }
        viewModel.ingestVoiceTurn(this, "do the thing", events)
        advanceUntilIdle()

        val messages = viewModel.messages.value
        assertEquals(2, messages.size)
        assertEquals(TranscriptMessage.Speaker.ASSISTANT, messages[1].speaker)
        assertEquals("Error: slow tier timed out", messages[1].text)
    }

    @Test
    fun `a chat-typed message and a voice turn land in the same causally-ordered transcript stream`() = runTest {
        val viewModel = ChatViewModel(dummyClient())

        // Simulate a prior chat turn already having appended these two messages
        // (exactly what sendMessage would have produced), without making a real
        // network call.
        seedMessages(
            viewModel,
            TranscriptMessage(id = "u1", speaker = TranscriptMessage.Speaker.USER, text = "typed message"),
            TranscriptMessage(id = "a1", speaker = TranscriptMessage.Speaker.ASSISTANT, text = "typed reply"),
        )

        val events = flow { emit(TierEvent.Completed("spoken reply")) }
        viewModel.ingestVoiceTurn(this, "spoken utterance", events)
        advanceUntilIdle()

        val messages = viewModel.messages.value
        assertEquals(4, messages.size)
        // Both message "kinds" are indistinguishable TranscriptMessage entries in one ordered stream.
        assertEquals(TranscriptMessage.Speaker.USER, messages[0].speaker)
        assertEquals("typed message", messages[0].text)
        assertEquals(TranscriptMessage.Speaker.ASSISTANT, messages[1].speaker)
        assertEquals("typed reply", messages[1].text)
        assertEquals(TranscriptMessage.Speaker.USER, messages[2].speaker)
        assertEquals("spoken utterance", messages[2].text)
        assertEquals(TranscriptMessage.Speaker.ASSISTANT, messages[3].speaker)
        assertEquals("spoken reply", messages[3].text)
    }
}
