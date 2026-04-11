package com.vela.app.ui.conversation

import com.google.common.truth.Truth.assertThat
import com.vela.app.ai.FakeGemmaEngine
import com.vela.app.domain.model.MessageRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

class ConversationViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun initialMessagesListIsEmpty() {
        val viewModel = ConversationViewModel(FakeGemmaEngine())
        assertThat(viewModel.messages.value).isEmpty()
    }

    @Test
    fun onVoiceInputAddsUserMessage() = runTest(testDispatcher) {
        val viewModel = ConversationViewModel(FakeGemmaEngine())
        viewModel.onVoiceInput("/tmp/test_audio.wav")
        advanceUntilIdle()
        assertThat(viewModel.messages.value).isNotEmpty()
        assertThat(viewModel.messages.value.first().role).isEqualTo(MessageRole.USER)
    }

    @Test
    fun onVoiceInputTriggersAssistantResponse() = runTest(testDispatcher) {
        val viewModel = ConversationViewModel(FakeGemmaEngine())
        viewModel.onVoiceInput("/tmp/test_audio.wav")
        advanceUntilIdle()
        assertThat(viewModel.messages.value).hasSize(2)
        assertThat(viewModel.messages.value[0].role).isEqualTo(MessageRole.USER)
        assertThat(viewModel.messages.value[1].role).isEqualTo(MessageRole.ASSISTANT)
        assertThat(viewModel.messages.value[1].content)
            .isEqualTo("Hello! I'm Vela, your on-device AI assistant. How can I help?")
    }

    @Test
    fun isProcessingIsTrueWhileGemmaRuns() = runTest(testDispatcher) {
        val viewModel = ConversationViewModel(FakeGemmaEngine())
        viewModel.onVoiceInput("/tmp/test_audio.wav")
        advanceTimeBy(50)
        assertThat(viewModel.isProcessing.value).isTrue()
        advanceUntilIdle()
        assertThat(viewModel.isProcessing.value).isFalse()
    }
}
