package com.vela.app.ui.queue

import com.vela.core.domain.LedgerRepository
import com.vela.core.domain.LedgerRepository.Decision
import com.vela.core.domain.LedgerRepository.Status
import com.vela.core.ui.AttentionCard
import com.vela.core.ui.CardDecision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Bridges the real [LedgerRepository] (goal item 3) into the [AttentionCard]
 * shape [com.vela.core.ui.CardDeck] renders, modeled after the pattern in
 * `LiveActivityViewModel`.
 */
class QueueViewModel(private val ledgerRepository: LedgerRepository) {

    private val _cards = MutableStateFlow<List<AttentionCard>>(emptyList())
    val cards: StateFlow<List<AttentionCard>> = _cards.asStateFlow()

    fun start(scope: CoroutineScope) {
        scope.launch {
            ledgerRepository.observeEntries().collect { entries ->
                _cards.value = entries
                    .filter { it.status == Status.PENDING }
                    .map { AttentionCard(id = it.id, title = it.title, summary = it.summary) }
            }
        }
    }

    /**
     * Records a swipe decision against the ledger.
     *
     * Optimistically removes [card] from [cards] immediately so the UI reflects
     * the decision without waiting on the network/repository round trip. If the
     * repository call fails, the card is restored to [cards] (rollback) -- but
     * only if it hasn't already been superseded by a newer emission (e.g. a
     * fresh `observeEntries()` collection that no longer contains it).
     */
    fun onDecision(scope: CoroutineScope, card: AttentionCard, decision: CardDecision) {
        val status = when (decision) {
            CardDecision.ACCEPT -> Status.ACCEPTED
            CardDecision.DISMISS -> Status.DISMISSED
            CardDecision.DEFER -> Status.DEFERRED
        }

        val originalCards = _cards.value
        _cards.value = originalCards.filterNot { it.id == card.id }

        scope.launch {
            try {
                ledgerRepository.recordDecision(
                    card.id,
                    Decision(status = status, decidedAtEpochMs = System.currentTimeMillis()),
                )
            } catch (e: Exception) {
                // Rollback: only restore if the current state still reflects our
                // optimistic removal (i.e. hasn't been superseded by a newer
                // observeEntries() emission).
                val current = _cards.value
                if (current.none { it.id == card.id } && current == originalCards.filterNot { it.id == card.id }) {
                    _cards.value = originalCards
                }
            }
        }
    }
}
