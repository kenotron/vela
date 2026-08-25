package com.vela.core.domain.notification

import com.vela.core.domain.LedgerRepository
import com.vela.core.domain.LedgerRepository.Status
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationOutcomeTrackerTest {

    private fun candidateFor(entryId: String, status: Status = Status.PENDING): AttentionCandidate {
        val entry = LedgerRepository.LedgerEntry(
            id = entryId,
            title = "title-$entryId",
            summary = "summary-$entryId",
            createdAtEpochMs = 0L,
            source = "test",
            status = status,
            requiresAttention = true,
        )
        return requireNotNull(AttentionCandidate.from(entry))
    }

    @Test
    fun `decided outcome via ACCEPTED increments decidedCount`() = runTest {
        val delivered = mutableListOf<AttentionNotification>()
        val tracker = NotificationOutcomeTracker(sink = { delivered.add(it) })
        val notifier = AttentionNotifier(tracker)

        notifier.notify(candidateFor("entry-1"))
        tracker.recordOutcome("entry-1", Status.ACCEPTED)

        assertEquals(1, delivered.size)
        assertEquals(0, tracker.dismissedCount)
        assertEquals(1, tracker.decidedCount)
        assertEquals(0.0, tracker.ratio(), 0.0)
    }

    @Test
    fun `dismissed outcome via DISMISSED increments dismissedCount`() = runTest {
        val delivered = mutableListOf<AttentionNotification>()
        val tracker = NotificationOutcomeTracker(sink = { delivered.add(it) })
        val notifier = AttentionNotifier(tracker)

        notifier.notify(candidateFor("entry-2"))
        tracker.recordOutcome("entry-2", Status.DISMISSED)

        assertEquals(1, delivered.size)
        assertEquals(1, tracker.dismissedCount)
        assertEquals(0, tracker.decidedCount)
        assertEquals(Double.POSITIVE_INFINITY, tracker.ratio(), 0.0)
    }

    @Test
    fun `DEFERRED counts as decided per documented interpretation`() = runTest {
        val tracker = NotificationOutcomeTracker(sink = {})
        val notifier = AttentionNotifier(tracker)

        notifier.notify(candidateFor("entry-3"))
        tracker.recordOutcome("entry-3", Status.DEFERRED)

        assertEquals(0, tracker.dismissedCount)
        assertEquals(1, tracker.decidedCount)
    }

    @Test
    fun `PENDING status is not terminal and produces no outcome`() = runTest {
        val tracker = NotificationOutcomeTracker(sink = {})
        val notifier = AttentionNotifier(tracker)

        notifier.notify(candidateFor("entry-4"))
        tracker.recordOutcome("entry-4", Status.PENDING)

        assertEquals(0, tracker.dismissedCount)
        assertEquals(0, tracker.decidedCount)
    }

    @Test
    fun `outcome for entryId never delivered is a no-op`() = runTest {
        val tracker = NotificationOutcomeTracker(sink = {})

        tracker.recordOutcome("never-delivered", Status.DISMISSED)

        assertEquals(0, tracker.dismissedCount)
        assertEquals(0, tracker.decidedCount)
    }

    @Test
    fun `repeated outcome for same entryId is idempotent`() = runTest {
        val tracker = NotificationOutcomeTracker(sink = {})
        val notifier = AttentionNotifier(tracker)

        notifier.notify(candidateFor("entry-5"))
        tracker.recordOutcome("entry-5", Status.DISMISSED)
        tracker.recordOutcome("entry-5", Status.DISMISSED)
        tracker.recordOutcome("entry-5", Status.ACCEPTED)

        assertEquals(1, tracker.dismissedCount)
        assertEquals(0, tracker.decidedCount)
    }

    @Test
    fun `ratio computation across a mixed sequence`() = runTest {
        val tracker = NotificationOutcomeTracker(sink = {})
        val notifier = AttentionNotifier(tracker)

        // 3 dismissed, 6 decided (mix of ACCEPTED and DEFERRED) => ratio 3/6 = 0.5
        val dismissedIds = listOf("d1", "d2", "d3")
        val acceptedIds = listOf("a1", "a2", "a3", "a4")
        val deferredIds = listOf("f1", "f2")

        (dismissedIds + acceptedIds + deferredIds).forEach { notifier.notify(candidateFor(it)) }

        dismissedIds.forEach { tracker.recordOutcome(it, Status.DISMISSED) }
        acceptedIds.forEach { tracker.recordOutcome(it, Status.ACCEPTED) }
        deferredIds.forEach { tracker.recordOutcome(it, Status.DEFERRED) }

        assertEquals(3, tracker.dismissedCount)
        assertEquals(6, tracker.decidedCount)
        assertEquals(0.5, tracker.ratio(), 1e-9)
    }

    @Test
    fun `observe auto-detects terminal transitions from a live ledger flow`() = runTest(UnconfinedTestDispatcher()) {
        val ledger = FakeLedgerRepository()
        val tracker = NotificationOutcomeTracker(sink = {})
        val notifier = AttentionNotifier(tracker)

        val entryId = "auto-1"
        ledger.setEntries(
            listOf(
                LedgerRepository.LedgerEntry(
                    id = entryId,
                    title = "t",
                    summary = "s",
                    createdAtEpochMs = 0L,
                    source = "test",
                    status = Status.PENDING,
                    requiresAttention = true,
                ),
            ),
        )
        notifier.notify(candidateFor(entryId))

        tracker.observe(ledger, backgroundScope)
        advanceUntilIdle()

        // Simulate the user acting on the entry: ledger emits the updated status.
        ledger.setEntries(
            listOf(
                LedgerRepository.LedgerEntry(
                    id = entryId,
                    title = "t",
                    summary = "s",
                    createdAtEpochMs = 0L,
                    source = "test",
                    status = Status.DISMISSED,
                    requiresAttention = true,
                ),
            ),
        )
        advanceUntilIdle()

        assertEquals(1, tracker.dismissedCount)
        assertEquals(0, tracker.decidedCount)
    }
}
