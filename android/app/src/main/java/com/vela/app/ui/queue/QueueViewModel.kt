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

    fun onDecision(scope: CoroutineScope, card: AttentionCard, decision: CardDecision) {
        val status = when (decision) {
            CardDecision.ACCEPT -> Status.ACCEPTED
            CardDecision.DISMISS -> Status.DISMISSED
        }
        scope.launch {
            ledgerRepository.recordDecision(
                card.id,
                Decision(status = status, decidedAtEpochMs = System.currentTimeMillis()),
            )
        }
    }
}
