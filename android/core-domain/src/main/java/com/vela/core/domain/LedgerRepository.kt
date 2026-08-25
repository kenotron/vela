package com.vela.core.domain

import kotlinx.coroutines.flow.Flow

/**
 * Domain interface for the durable decision ledger (attention queue history,
 * decisions, undo). Implemented by lane 1.5. This lane (1.1) defines the
 * contract only.
 */
interface LedgerRepository {

    /** Reactive stream of all ledger entries, newest first. */
    fun observeEntries(): Flow<List<LedgerEntry>>

    /** Fetch a single entry by id, or null if not found. */
    suspend fun get(id: String): LedgerEntry?

    /** Append a new entry to the ledger. */
    suspend fun append(entry: LedgerEntry)

    /** Record a decision (accept/defer/dismiss/undo) against an existing entry. */
    suspend fun recordDecision(entryId: String, decision: Decision)

    data class LedgerEntry(
        val id: String,
        val title: String,
        val summary: String,
        val createdAtEpochMs: Long,
        val source: String,
        val status: Status,
        /**
         * Attention gate (#47, epic #19): true when this entry represents work that
         * needs a human decision (accept/defer/dismiss), false for progress-only
         * updates that require no action. Set at construction so it is part of the
         * entry's identity, not inferred later from status/category. This is the
         * sole basis on which [com.vela.core.domain.notification.AttentionNotifier]
         * can ever be reached -- see that package for the enforced guarantee.
         */
        val requiresAttention: Boolean,
    )

    enum class Status {
        PENDING,
        ACCEPTED,
        DEFERRED,
        DISMISSED,
    }

    data class Decision(
        val status: Status,
        val decidedAtEpochMs: Long,
        val note: String? = null,
    )
}
