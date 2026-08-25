package com.vela.core.domain.notification

import com.vela.core.domain.LedgerRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Minimal hand-rolled fake of [LedgerRepository] for testing
 * [NotificationOutcomeTracker.observe] against a live entry stream, following
 * this module's existing plain-JUnit4 fake style (no mocking library).
 */
class FakeLedgerRepository : LedgerRepository {

    private val entries = MutableStateFlow<List<LedgerRepository.LedgerEntry>>(emptyList())

    fun setEntries(newEntries: List<LedgerRepository.LedgerEntry>) {
        entries.value = newEntries
    }

    override fun observeEntries(): StateFlow<List<LedgerRepository.LedgerEntry>> = entries

    override suspend fun get(id: String): LedgerRepository.LedgerEntry? =
        entries.value.firstOrNull { it.id == id }

    override suspend fun append(entry: LedgerRepository.LedgerEntry) {
        entries.value = entries.value + entry
    }

    override suspend fun recordDecision(entryId: String, decision: LedgerRepository.Decision) {
        entries.value = entries.value.map {
            if (it.id == entryId) it.copy(status = decision.status) else it
        }
    }
}
