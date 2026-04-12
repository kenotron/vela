package com.vela.app.ui.conversation

import com.google.common.truth.Truth.assertThat
import com.vela.app.ai.FakeGemmaEngine
import com.vela.app.ai.tools.ToolRegistry
import com.vela.app.audio.FakeTtsEngine
import com.vela.app.data.repository.ConversationRepository
import com.vela.app.domain.model.Message
import com.vela.app.domain.model.MessageRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

class FakeConversationRepository : ConversationRepository {
    private val _messages = MutableStateFlow<List<Message>>(emptyList())

    override fun getMessages(): Flow<List<Message>> = _messages

    override suspend fun saveMessage(message: Message) {
        _messages.value = _messages.value + message
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeRepository: FakeConversationRepository
    private lateinit var fakeGemmaEngine: FakeGemmaEngine
    private lateinit var fakeTts: FakeTtsEngine

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeRepository = FakeConversationRepository()
        fakeGemmaEngine = FakeGemmaEngine()
        fakeTts = FakeTtsEngine()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val emptyRegistry = ToolRegistry(emptyList())

    private fun createViewModel() = ConversationViewModel(
        gemmaEngine = fakeGemmaEngine,
        repository = fakeRepository,
        ttsEngine = fakeTts,
        toolRegistry = emptyRegistry,
    )

    @Test
    fun initialMessagesListIsEmpty() {
        val viewModel = createViewModel()
        assertThat(viewModel.messages.value).isEmpty()
    }

    @Test
    fun onVoiceInputAddsUserMessage() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        viewModel.onVoiceInput("Turn on the lights")
        advanceUntilIdle()
        val userMessages = viewModel.messages.value.filter { it.role == MessageRole.USER }
        assertThat(userMessages).hasSize(1)
        assertThat(userMessages.first().content).isEqualTo("Turn on the lights")
    }

    @Test
    fun onVoiceInputAddsAssistantResponseAfterProcessing() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        viewModel.onVoiceInput("Hello")
        advanceUntilIdle()
        val assistantMessages = viewModel.messages.value.filter { it.role == MessageRole.ASSISTANT }
        assertThat(assistantMessages).hasSize(1)
        assertThat(assistantMessages.first().content).isNotEmpty()
    }

    @Test
    fun onTextInputAddsUserAndAssistantMessages() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        viewModel.onTextInput("What time is it?")
        advanceUntilIdle()
        assertThat(viewModel.messages.value).hasSize(2)
        assertThat(viewModel.messages.value[0].role).isEqualTo(MessageRole.USER)
        assertThat(viewModel.messages.value[1].role).isEqualTo(MessageRole.ASSISTANT)
    }

    @Test
    fun isProcessingIsFalseInitially() {
        val viewModel = createViewModel()
        assertThat(viewModel.isProcessing.value).isFalse()
    }

    @Test
    fun streamingResponseIsNullWhenIdle() {
        val viewModel = createViewModel()
        assertThat(viewModel.streamingResponse.value).isNull()
    }

    @Test
    fun streamingResponseIsNullAfterProcessingCompletes() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        viewModel.onTextInput("hi")
        advanceUntilIdle()
        assertThat(viewModel.streamingResponse.value).isNull()
    }

    @Test
    fun setEngineStateChangesState() {
        val viewModel = createViewModel()
        viewModel.setEngineState(EngineState.ModelNotReady)
        assertThat(viewModel.engineState.value).isEqualTo(EngineState.ModelNotReady)
    }

    @Test
    fun assistantResponseMatchesFakeEngineOutput() = runTest(testDispatcher) {
        val customResponse = "Custom canned response for test"
        val engine = FakeGemmaEngine(response = customResponse)
        val viewModel = ConversationViewModel(
            gemmaEngine = engine,
            repository = fakeRepository,
            ttsEngine = fakeTts,
            toolRegistry = ToolRegistry(emptyList()),
        )
        viewModel.onTextInput("test")
        advanceUntilIdle()
        val assistant = viewModel.messages.value.last()
        assertThat(assistant.role).isEqualTo(MessageRole.ASSISTANT)
        assertThat(assistant.content).isEqualTo(customResponse)
    }
}
