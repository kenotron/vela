package com.vela.app.ui.conversation

import com.google.common.truth.Truth.assertThat
import com.vela.app.ai.FakeGemmaEngine
import com.vela.app.ai.IntentExtractor
import com.vela.app.audio.FakeTtsEngine
import com.vela.app.data.repository.ConversationRepository
import com.vela.app.domain.model.Message
import com.vela.app.domain.model.MessageRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
    private lateinit var intentExtractor: IntentExtractor

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeRepository = FakeConversationRepository()
        fakeGemmaEngine = FakeGemmaEngine()
        fakeTts = FakeTtsEngine()
        intentExtractor = IntentExtractor(FakeGemmaEngine())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel() = ConversationViewModel(
        gemmaEngine = fakeGemmaEngine,
        repository = fakeRepository,
        intentExtractor = intentExtractor,
        ttsEngine = fakeTts,
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
        assertThat(viewModel.messages.value).isNotEmpty()
        assertThat(viewModel.messages.value.first().role).isEqualTo(MessageRole.USER)
        assertThat(viewModel.messages.value.first().content).isEqualTo("Turn on the lights")
    }

    @Test
    fun onVoiceInputTriggersAssistantResponse() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        viewModel.onVoiceInput("Turn on the lights")
        advanceUntilIdle()
        assertThat(viewModel.messages.value).hasSize(2)
        assertThat(viewModel.messages.value[0].role).isEqualTo(MessageRole.USER)
        assertThat(viewModel.messages.value[1].role).isEqualTo(MessageRole.ASSISTANT)
    }

    @Test
    fun isProcessingIsTrueWhileGemmaRuns() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        viewModel.onVoiceInput("Turn on the lights")
        advanceTimeBy(50)
        assertThat(viewModel.isProcessing.value).isTrue()
        advanceUntilIdle()
        assertThat(viewModel.isProcessing.value).isFalse()
    }

    @Test
    fun onVoiceInputTriggersTextToSpeech() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        viewModel.onVoiceInput("Turn on the lights")
        advanceUntilIdle()
        assertThat(fakeTts.spokenTexts).isNotEmpty()
    }

    @Test
    fun onVoiceInputWithTranscriptStoresOriginalText() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        val transcript = "Play some jazz music"
        viewModel.onVoiceInput(transcript)
        advanceUntilIdle()
        assertThat(viewModel.messages.value.first().content).isEqualTo(transcript)
    }

    @Test
    fun engineStateIsModelReadyByDefault() {
        val viewModel = createViewModel()
        assertThat(viewModel.engineState.value).isEqualTo(EngineState.ModelReady)
    }
}
