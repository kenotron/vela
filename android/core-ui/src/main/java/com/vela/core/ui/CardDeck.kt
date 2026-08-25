package com.vela.core.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** A single item in the mock attention queue rendered by [CardDeck]. */
data class AttentionCard(
    val id: String,
    val title: String,
    val summary: String,
)

/** Decision the user made about the top card of the deck. */
enum class CardDecision {
    ACCEPT,
    DISMISS,
    DEFER,
}

/**
 * Mock card-deck (attention queue) surface. Renders the top card of [cards] and
 * supports swipe-to-decide: swipe right accepts, swipe left dismisses, swipe up
 * defers.
 *
 * This is intentionally the simplest possible gesture implementation so that a
 * deterministic Compose semantics test (`performTouchInput { swipeLeft() }`) can
 * assert against it without AI-judged UI drive.
 */
@Composable
fun CardDeck(
    cards: List<AttentionCard>,
    onDecision: (AttentionCard, CardDecision) -> Unit,
    modifier: Modifier = Modifier,
) {
    val topCard = cards.firstOrNull()

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("card_deck")
            .semantics { contentDescription = "Attention queue card deck" },
        contentAlignment = Alignment.Center,
    ) {
        if (topCard == null) {
            Text(
                text = "No pending items",
                modifier = Modifier.testTag("card_deck_empty"),
            )
        } else {
            val offsetX = remember(topCard.id) { Animatable(0f) }
            val coroutineScope = rememberCoroutineScope()
            var dragTotalX by remember(topCard.id) { mutableStateOf(0f) }
            var dragTotalY by remember(topCard.id) { mutableStateOf(0f) }

            Card(
                modifier = Modifier
                    .padding(24.dp)
                    .testTag("card_deck_top_card")
                    .semantics { contentDescription = "Card: ${topCard.title}" }
                    .pointerInput(topCard.id) {
                        detectDragGestures(
                            onDragEnd = {
                                val threshold = 120f
                                when {
                                    dragTotalY <= -threshold && kotlin.math.abs(dragTotalY) >= kotlin.math.abs(dragTotalX) ->
                                        onDecision(topCard, CardDecision.DEFER)
                                    dragTotalX <= -threshold -> onDecision(topCard, CardDecision.DISMISS)
                                    dragTotalX >= threshold -> onDecision(topCard, CardDecision.ACCEPT)
                                }
                                dragTotalX = 0f
                                dragTotalY = 0f
                                coroutineScope.launch { offsetX.snapTo(0f) }
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                dragTotalX += dragAmount.x
                                dragTotalY += dragAmount.y
                            },
                        )
                    },
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            ) {
                Box(modifier = Modifier.padding(24.dp)) {
                    Text(text = topCard.title, style = MaterialTheme.typography.titleLarge)
                }
            }
        }
    }
}
