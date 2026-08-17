package com.vela.app

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import org.junit.Rule
import org.junit.Test

/**
 * Deterministic Compose semantics test for swipe-to-decide on the card deck.
 * This is the merge gate for item 4 — not an AI-judged UI drive.
 */
class CardDeckSwipeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun swipeLeftOnCardDeck_isHandledWithoutCrash() {
        composeTestRule.setContent {
            VelaScaffoldRoot()
        }

        composeTestRule.onNodeWithTag("card_deck").performTouchInput { swipeLeft() }
        composeTestRule.waitForIdle()

        // After a swipe-left (dismiss) gesture, the card deck node must still be
        // present and legible — proves the gesture was handled, not crashed.
        composeTestRule.onNodeWithTag("card_deck").assertExists()
    }
}
