package com.vela.hosttools

import com.vela.core.domain.LedgerRepository
import com.vela.core.domain.LedgerRepository.Decision
import com.vela.core.domain.LedgerRepository.LedgerEntry
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow

/**
 * Pure in-memory stub implementation of [LedgerRepository], LOCAL to
 * host-tools. This is NOT lane 1.5's real ledger (which does not exist as a
 * gradle module yet — its SQLite-backed implementation is out of scope here).
 * It exists solely so [DispatchToFleetTool] has a concrete ledger to write
 * through for its stub fleet plane, per the goal file:
 *
 *   "provide your OWN in-memory stub implementation of LedgerRepository
 *   LOCAL to android/host-tools ... purely so dispatch_to_fleet has
 *   something to write through and your tests can exercise it."
 *
 * Thread-safe via ConcurrentHashMap; not persisted; not durable across
 * process restarts (durability is lane 1.5's job).
 */
class InMemoryLedgerRepository : LedgerRepository {
    private val entries = ConcurrentHashMap<String, LedgerEntry>()
    private val decisions = ConcurrentHashMap<String, Decision>()
    private val entriesFlow = MutableStateFlow<List<LedgerEntry>>(emptyList())

    override fun observeEntries(): Flow<List<LedgerEntry>> = entriesFlow.asStateFlow()

    override suspend fun get(id: String): LedgerEntry? = entries[id]

    override suspend fun append(entry: LedgerEntry) {
        entries[entry.id] = entry
        entriesFlow.value = entries.values.sortedByDescending { it.createdAtEpochMs }
    }

    override suspend fun recordDecision(entryId: String, decision: Decision) {
        val existing = entries[entryId] ?: return
        decisions[entryId] = decision
        entries[entryId] = existing.copy(status = decision.status)
        entriesFlow.value = entries.values.sortedByDescending { it.createdAtEpochMs }
    }

    /** Test/debug helper: total entries recorded so far. */
    fun size(): Int = entries.size
}
