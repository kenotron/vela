package com.vela.app

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vela.app.ai.FakeGemmaEngine
import com.vela.app.audio.FakeTtsEngine
import com.vela.app.data.repository.ConversationRepository
import com.vela.app.domain.model.Message
import com.vela.app.ui.conversation.ConversationScreen
import com.vela.app.ui.conversation.ConversationViewModel
import com.vela.app.ui.theme.VelaTheme
import com.vela.app.voice.FakeSpeechTranscriber
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var viewModel: ConversationViewModel
    private lateinit var fakeTranscriber: FakeSpeechTranscriber
    private lateinit var fakeTts: FakeTtsEngine
    private lateinit var inMemoryRepo: InMemoryConversationRepository

    @Before
    fun setUp() {
        fakeTranscriber = FakeSpeechTranscriber()
        fakeTts = FakeTtsEngine()
        inMemoryRepo = InMemoryConversationRepository()
        viewModel = ConversationViewModel(
            gemmaEngine = FakeGemmaEngine(),
            repository = inMemoryRepo,
            ttsEngine = fakeTts,
        )
    }

    @Test
    fun conversationScreenShowsVoiceButton() {
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
    fun tappingVoiceButtonTogglesState() {
        composeTestRule.setContent {
            VelaTheme {
                ConversationScreen(
                    speechTranscriber = fakeTranscriber,
                    viewModel = viewModel,
                )
            }
        }
        composeTestRule.onNodeWithContentDescription("Start voice input").performClick()
        composeTestRule.onNodeWithContentDescription("Stop voice input").assertExists()
        composeTestRule.onNodeWithContentDescription("Start voice input").assertDoesNotExist()
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

        fakeTranscriber.emitFinal("hello vela")

        composeTestRule.waitUntil(timeoutMillis = 5000) {
            viewModel.messages.value.size >= 2
        }

        composeTestRule
            .onNodeWithText("Hello! I'm Vela, your on-device AI assistant. How can I help?")
            .assertExists()
    }

    private class InMemoryConversationRepository : ConversationRepository {
        private val _messages = MutableStateFlow<List<Message>>(emptyList())

        override fun getMessages(): Flow<List<Message>> = _messages

        override suspend fun saveMessage(message: Message) {
            _messages.value = _messages.value + message
        }
    }
}
