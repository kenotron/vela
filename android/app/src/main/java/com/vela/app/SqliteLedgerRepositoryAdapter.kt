package com.vela.app

import com.vela.core.domain.LedgerRepository
import com.vela.core.domain.LedgerRepository.Decision
import com.vela.core.domain.LedgerRepository.LedgerEntry
import com.vela.core.domain.LedgerRepository.Status
import com.vela.ledger.Decision as JobDecision
import com.vela.ledger.JobDao
import com.vela.ledger.JobRecord
import com.vela.ledger.JobStatus
import com.vela.ledger.SqliteLedgerRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Adapter implementing `com.vela.core.domain.LedgerRepository` (the "simple flat"
 * contract Queue/Chat consume) by delegating to [SqliteLedgerRepository] (the real
 * SQLite/Room-backed C3 job resource store under android/ledger/, which this lane
 * may not modify).
 *
 * This is composition-root glue code living in android/app/ per the goal file's
 * explicit instruction, NOT a change to android/ledger/ internals.
 *
 * FIELD MAPPING RESIDUALS (documented per goal instructions -- do not invent data):
 *  - [LedgerEntry.title] has no equivalent field in [JobRecord]; we synthesize it
 *    from `attention.reason` when present, else fall back to the job id. A real
 *    "title" concept does not exist in the C3 job schema.
 *  - [LedgerEntry.summary] is similarly synthesized: we use the attention reason
 *    text if present, else the raw `spec` JSON (truncated) as a best-effort summary.
 *  - [LedgerEntry.source] maps to `origin.sessionId` (closest available "where did
 *    this come from" field on JobRecord).
 *  - [LedgerEntry.status] maps [JobStatus] -> [Status] via [toLedgerStatus] below;
 *    JobStatus has more granularity (RUNNING, BLOCKED, DONE, FAILED, CANCELLED)
 *    than the domain Status enum (PENDING/ACCEPTED/DEFERRED/DISMISSED). Multiple
 *    JobStatus values collapse onto the same Status -- this is lossy in one
 *    direction (JobStatus -> Status) and reconstructed only approximately in the
 *    other (Status -> JobStatus) inside [recordDecision]/[append]. A future lane
 *    reconciling the two schemas should treat this mapping as provisional.
 *  - [append]/[recordDecision] on the domain interface operate on the flat
 *    [LedgerEntry] shape; because [JobRecord] requires additional fields (spec,
 *    attention, cost, progress) that a caller of the simple interface doesn't
 *    supply, [append] synthesizes minimal defaults for those fields. This is
 *    acceptable for the Queue tab's read path (`observeEntries`) which is the
 *    only path currently exercised by the UI; write-path fidelity for a caller
 *    using the domain interface directly is a residual.
 */
class SqliteLedgerRepositoryAdapter(dao: JobDao) : LedgerRepository {

    private val delegate = SqliteLedgerRepository(dao)

    override fun observeEntries(): Flow<List<LedgerEntry>> =
        delegate.observeAll().map { jobs -> jobs.map { it.toLedgerEntry() } }

    override suspend fun get(id: String): LedgerEntry? = delegate.getJob(id)?.toLedgerEntry()

    override suspend fun append(entry: LedgerEntry) {
        val now = entry.createdAtEpochMs
        delegate.createJob(
            JobRecord(
                jobId = entry.id,
                createdAt = now,
                updatedAt = now,
                origin = JobRecord.Origin(
                    sessionId = entry.source,
                    turnId = "",
                    toolCallId = "",
                ),
                spec = entry.summary,
                status = entry.status.toJobStatus(),
                attention = JobRecord.Attention(
                    required = entry.status == Status.PENDING,
                    reason = entry.title,
                    options = emptyList(),
                    deadline = null,
                ),
                progress = emptyList(),
                result = null,
                cost = JobRecord.Cost(usd = null, tokens = null),
            ),
        )
    }

    override suspend fun recordDecision(entryId: String, decision: Decision) {
        delegate.recordDecision(
            entryId,
            JobDecision(newStatus = decision.status.toJobStatus(), decidedAt = decision.decidedAtEpochMs),
        )
    }
}

private fun JobRecord.toLedgerEntry(): LedgerEntry = LedgerEntry(
    id = jobId,
    title = attention.reason ?: jobId,
    summary = attention.reason ?: spec.take(200),
    createdAtEpochMs = createdAt,
    source = origin.sessionId,
    status = status.toLedgerStatus(),
    requiresAttention = attention.required,
)

private fun JobStatus.toLedgerStatus(): Status = when (this) {
    JobStatus.ACCEPTED -> Status.ACCEPTED
    JobStatus.RUNNING -> Status.ACCEPTED
    JobStatus.NEEDS_ATTENTION -> Status.PENDING
    JobStatus.BLOCKED -> Status.DEFERRED
    JobStatus.DONE -> Status.ACCEPTED
    JobStatus.FAILED -> Status.DISMISSED
    JobStatus.CANCELLED -> Status.DISMISSED
}

private fun Status.toJobStatus(): JobStatus = when (this) {
    Status.PENDING -> JobStatus.NEEDS_ATTENTION
    Status.ACCEPTED -> JobStatus.ACCEPTED
    Status.DEFERRED -> JobStatus.BLOCKED
    Status.DISMISSED -> JobStatus.CANCELLED
}
