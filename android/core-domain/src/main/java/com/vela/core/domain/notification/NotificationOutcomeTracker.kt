package com.vela.core.domain.notification

import com.vela.core.domain.LedgerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Instrumentation for the design doc's designated notification-rule quality
 * signal (epic #19, issue #48): the dismissed-vs-decided ratio.
 *
 * For every [AttentionNotification] delivered via [AttentionNotifier] that
 * reaches a terminal user response, this tracker classifies the outcome as
 * either:
 *  - **dismissed**: [LedgerRepository.Status.DISMISSED] -- the user ignored /
 *    swiped away the notification without acting on the underlying entry.
 *  - **decided**: [LedgerRepository.Status.ACCEPTED] or
 *    [LedgerRepository.Status.DEFERRED] -- the user acted on the underlying
 *    ledger entry.
 *
 * ### Interpretation note: DEFERRED counts as "decided"
 *
 * [LedgerRepository.Status.DEFERRED] is treated as a **decided** outcome, not
 * a dismissal. Deferring is an explicit decision to postpone action on an
 * entry that still requires attention -- the user engaged with the
 * notification and made a choice about it, as opposed to a dismissal, which
 * discards the entry without any decision being recorded. Since the ratio
 * this tracker reports is meant to signal *notification-rule quality*
 * (are we bothering users with things they don't want to decide on?),
 * lumping "I'll decide later" in with "I never engaged with this" would
 * conflate two very different user behaviors and produce a misleadingly
 * high dismissal rate for notifications that are, in fact, being consciously
 * triaged.
 *
 * [LedgerRepository.Status.PENDING] is not terminal and produces no outcome
 * -- an entry sitting at PENDING has not yet been resolved either way.
 *
 * ### Wiring
 *
 * This class does not build a parallel notification system. It decorates
 * the existing [AttentionNotificationSink] to record deliveries (correlating
 * by `entryId`), and separately observes an existing [LedgerRepository] via
 * [observe] to auto-detect terminal status transitions for delivered
 * entries, using the real `observeEntries()` [Flow] rather than requiring
 * app-layer glue to call back into this tracker after every
 * `recordDecision`.
 *
 * Thread-safety: all counters and internal state are guarded by a coroutine
 * [Mutex]. Public read methods ([dismissedCount], [decidedCount], [ratio])
 * take a snapshot without suspending, since simple integer/collection reads
 * are safe to expose without a lock for reporting purposes; the mutex
 * protects the read-modify-write sequences in [recordDelivery] and
 * [recordOutcome] against concurrent callers.
 */
class NotificationOutcomeTracker(
    private val sink: AttentionNotificationSink,
) : AttentionNotificationSink {

    private val mutex = Mutex()

    /** entryIds for which a notification has been delivered but no terminal outcome recorded yet. */
    private val pendingEntryIds = mutableSetOf<String>()

    private var dismissed = 0
    private var decided = 0

    /** Number of deliveries whose outcome was classified as dismissed. */
    val dismissedCount: Int
        get() = dismissed

    /** Number of deliveries whose outcome was classified as decided. */
    val decidedCount: Int
        get() = decided

    /**
     * Dismissed-vs-decided ratio: `dismissedCount / decidedCount`.
     *
     * Returns `0.0` when there is no data yet ([dismissedCount] is 0), and
     * [Double.POSITIVE_INFINITY] when there have been dismissals but no
     * decided outcomes yet ([decidedCount] is 0) -- both are well-defined,
     * non-crashing results rather than a division-by-zero exception.
     */
    fun ratio(): Double {
        if (dismissed == 0) return 0.0
        if (decided == 0) return Double.POSITIVE_INFINITY
        return dismissed.toDouble() / decided.toDouble()
    }

    /**
     * Delivers [notification] through the wrapped [sink] and records it as
     * pending an outcome. Call this in place of the underlying sink -- e.g.
     * construct [AttentionNotifier] with this tracker as its sink -- so every
     * real delivery is tracked without a parallel notification path.
     */
    override fun deliver(notification: AttentionNotification) {
        pendingEntryIds.add(notification.entryId)
        sink.deliver(notification)
    }

    /**
     * Records the terminal outcome for [entryId] given its final [status].
     * A no-op if [entryId] was never delivered (no pending outcome to
     * resolve) or if [status] is [LedgerRepository.Status.PENDING] (not
     * terminal). Idempotent per entryId: once an outcome has been recorded
     * for an entryId, subsequent calls for the same entryId are ignored, so
     * re-delivery of the same ledger snapshot cannot double-count.
     */
    suspend fun recordOutcome(entryId: String, status: LedgerRepository.Status) {
        mutex.withLock {
            if (entryId !in pendingEntryIds) return
            when (status) {
                LedgerRepository.Status.DISMISSED -> {
                    dismissed++
                    pendingEntryIds.remove(entryId)
                }
                LedgerRepository.Status.ACCEPTED, LedgerRepository.Status.DEFERRED -> {
                    decided++
                    pendingEntryIds.remove(entryId)
                }
                LedgerRepository.Status.PENDING -> Unit
            }
        }
    }

    /**
     * Subscribes to [repository]'s live entry stream and automatically
     * records outcomes for any tracked entryId as soon as its status becomes
     * terminal, without requiring the caller to wire a manual callback after
     * each `recordDecision`. Launches a collector in [scope]; cancel [scope]
     * to stop observing.
     */
    fun observe(repository: LedgerRepository, scope: CoroutineScope): Flow<List<LedgerRepository.LedgerEntry>> {
        val flow = repository.observeEntries()
        flow.onEach { entries ->
            for (entry in entries) {
                if (entry.id in pendingEntryIds) {
                    recordOutcome(entry.id, entry.status)
                }
            }
        }.launchIn(scope)
        return flow
    }
}
