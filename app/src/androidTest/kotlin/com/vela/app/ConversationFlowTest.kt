package com.vela.app

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.vela.app.ai.FakeGemmaEngine
import com.vela.app.ai.IntentExtractor
import com.vela.app.audio.FakeTtsEngine
import com.vela.app.data.repository.ConversationRepository
import com.vela.app.domain.model.Message
import com.vela.app.ui.conversation.ConversationScreen
import com.vela.app.ui.conversation.ConversationViewModel
import com.vela.app.ui.conversation.EngineState
import com.vela.app.ui.theme.VelaTheme
import com.vela.app.voice.FakeSpeechTranscriber
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationFlowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var viewModel: ConversationViewModel
    private lateinit var fakeGemmaEngine: FakeGemmaEngine
    private lateinit var fakeTts: FakeTtsEngine
    private lateinit var fakeTranscriber: FakeSpeechTranscriber
    private lateinit var inMemoryRepo: InMemoryConversationRepository
    private lateinit var intentExtractor: IntentExtractor

    @Before
    fun setUp() {
        fakeGemmaEngine = FakeGemmaEngine()
        fakeTts = FakeTtsEngine()
        fakeTranscriber = FakeSpeechTranscriber()
        inMemoryRepo = InMemoryConversationRepository()
        intentExtractor = IntentExtractor(FakeGemmaEngine())
        viewModel = ConversationViewModel(
            gemmaEngine = fakeGemmaEngine,
            repository = inMemoryRepo,
            intentExtractor = intentExtractor,
            ttsEngine = fakeTts,
        )
    }

    @Test
    fun modelNotReadyStateShowsDownloadPrompt() {
        viewModel.setEngineState(EngineState.ModelNotReady)
        composeTestRule.setContent {
            VelaTheme {
                ConversationScreen(
                    speechTranscriber = fakeTranscriber,
                    viewModel = viewModel,
                )
            }
        }
        composeTestRule.onNodeWithContentDescription("Model download required").assertExists()
        composeTestRule.onNodeWithContentDescription("Download model").assertExists()
    }

    @Test
    fun modelReadyStateShowsVoiceButton() {
        composeTestRule.setContent {
            VelaTheme {
                ConversationScreen(
                    speechTranscriber = fakeTranscriber,
                    viewModel = viewModel,
                )
            }
        }
        composeTestRule.onNodeWithContentDescription("Start voice input").assertExists()
    }

    @Test
    fun fullConversationFlowShowsAssistantResponse() {
        composeTestRule.setContent {
            VelaTheme {
                ConversationScreen(
                    speechTranscriber = fakeTranscriber,
                    viewModel = viewModel,
                )
            }
        }

        fakeTranscriber.emitFinal("what time is it")

        composeTestRule.waitUntil(timeoutMillis = 5000) {
            viewModel.messages.value.size >= 2
        }

        composeTestRule
            .onNodeWithText("Hello! I'm Vela, your on-device AI assistant. How can I help?")
            .assertExists()
        assertThat(fakeTts.spokenTexts).isNotEmpty()
    }

    private class InMemoryConversationRepository : ConversationRepository {
        private val _messages = MutableStateFlow<List<Message>>(emptyList())

        override fun getMessages(): Flow<List<Message>> = _messages

        override suspend fun saveMessage(message: Message) {
            _messages.value = _messages.value + message
        }
    }
}
