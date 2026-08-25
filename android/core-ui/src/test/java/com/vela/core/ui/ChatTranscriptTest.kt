package com.vela.core.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Compose semantics assertions for [ChatTranscript], per issue #35: an
 * approval prompt must render as a distinct message type (not indistinguishable
 * from a normal assistant [TranscriptMessage.Chat] message), and resolving it
 * must be reflected in the transcript rather than silently vanishing.
 *
 * Runs as a JVM unit test via Robolectric (see `core-ui/build.gradle.kts`) --
 * no physical device or emulator required, per this host's capability limits.
 */
@RunWith(RobolectricTestRunner::class)
class ChatTranscriptTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `a pending approval message renders distinctly from a chat message with approve and deny actions`() {
        val messages = listOf(
            TranscriptMessage.Chat(id = "c1", speaker = TranscriptMessage.Speaker.ASSISTANT, text = "Hi there"),
            TranscriptMessage.Approval(id = "a1", entryId = "entry-1", promptText = "Delete file foo.txt?"),
        )

        composeTestRule.setContent {
            ChatTranscript(messages = messages)
        }

        // The normal chat message renders as before.
        composeTestRule.onNodeWithTag("chat_message_c1").assertIsDisplayed()

        // The approval prompt renders as its own distinct entry, with the prompt text
        // and both actions visible -- never mistakable for a plain assistant message.
        composeTestRule.onNodeWithTag("chat_approval_a1").assertIsDisplayed()
        composeTestRule.onNodeWithText("Approval needed").assertIsDisplayed()
        composeTestRule.onNodeWithText("Delete file foo.txt?").assertIsDisplayed()
        composeTestRule.onNodeWithTag("chat_approval_a1_approve").assertIsDisplayed()
        composeTestRule.onNodeWithTag("chat_approval_a1_deny").assertIsDisplayed()
    }

    @Test
    fun `tapping approve invokes onApprove with the message id`() {
        var approvedId: String? = null
        val messages = listOf(
            TranscriptMessage.Approval(id = "a1", entryId = "entry-1", promptText = "Delete file foo.txt?"),
        )

        composeTestRule.setContent {
            ChatTranscript(messages = messages, onApprove = { approvedId = it })
        }

        composeTestRule.onNodeWithTag("chat_approval_a1_approve").performClick()

        assert(approvedId == "a1") { "expected onApprove to be invoked with a1, got $approvedId" }
    }

    @Test
    fun `tapping deny invokes onDeny with the message id`() {
        var deniedId: String? = null
        val messages = listOf(
            TranscriptMessage.Approval(id = "a1", entryId = "entry-1", promptText = "Delete file foo.txt?"),
        )

        composeTestRule.setContent {
            ChatTranscript(messages = messages, onDeny = { deniedId = it })
        }

        composeTestRule.onNodeWithTag("chat_approval_a1_deny").performClick()

        assert(deniedId == "a1") { "expected onDeny to be invoked with a1, got $deniedId" }
    }

    @Test
    fun `a resolved approval no longer shows approve or deny actions but remains visible`() {
        val messages = listOf(
            TranscriptMessage.Approval(
                id = "a1",
                entryId = "entry-1",
                promptText = "Delete file foo.txt?",
                status = TranscriptMessage.Approval.Status.APPROVED,
            ),
        )

        composeTestRule.setContent {
            ChatTranscript(messages = messages)
        }

        // The prompt is not silently vanished -- it remains in the transcript, now
        // showing the resolved status instead of pending actions.
        composeTestRule.onNodeWithTag("chat_approval_a1").assertIsDisplayed()
        composeTestRule.onNodeWithText("Approved").assertIsDisplayed()
        composeTestRule.onNodeWithText("Delete file foo.txt?").assertIsDisplayed()
    }
}
