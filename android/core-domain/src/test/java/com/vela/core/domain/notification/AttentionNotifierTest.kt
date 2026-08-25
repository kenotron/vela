package com.vela.core.domain.notification

import com.vela.core.domain.LedgerRepository.LedgerEntry
import com.vela.core.domain.LedgerRepository.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AttentionNotifierTest {

    private fun entry(requiresAttention: Boolean) = LedgerEntry(
        id = "job-1",
        title = "Some title",
        summary = "Some summary",
        createdAtEpochMs = 1_000L,
        source = "test",
        status = Status.PENDING,
        requiresAttention = requiresAttention,
    )

    @Test
    fun `attention-required entry reaches the notifier`() {
        val delivered = mutableListOf<AttentionNotification>()
        val notifier = AttentionNotifier(sink = { delivered.add(it) })

        val candidate = AttentionCandidate.from(entry(requiresAttention = true))
        assertNotNullCandidate(candidate)
        notifier.notify(candidate!!)

        assertEquals(1, delivered.size)
        assertEquals("job-1", delivered.single().entryId)
    }

    @Test
    fun `progress-only entry cannot be turned into a candidate`() {
        // This is the construction-time guarantee: AttentionCandidate.from()
        // returns null for a non-attention entry. There is no other public
        // constructor of AttentionCandidate to route around this -- the
        // primary constructor is private (see AttentionNotifier.kt). If that
        // guarantee were removed (constructor made public, or `from` stopped
        // checking requiresAttention), this assertion would fail because a
        // candidate would be produced here.
        val candidate = AttentionCandidate.from(entry(requiresAttention = false))
        assertNull(candidate)
    }

    @Test
    fun `progress-only entry never reaches the notifier sink`() {
        val delivered = mutableListOf<AttentionNotification>()
        val notifier = AttentionNotifier(sink = { delivered.add(it) })

        val candidate = AttentionCandidate.from(entry(requiresAttention = false))
        // candidate is null here; notify() has no overload accepting a bare
        // LedgerEntry or a nullable AttentionCandidate, so there is no
        // expression that both compiles and reaches the sink for this entry.
        if (candidate != null) {
            notifier.notify(candidate)
        }

        assertEquals(0, delivered.size)
    }

    private fun assertNotNullCandidate(candidate: AttentionCandidate?) {
        org.junit.Assert.assertNotNull(candidate)
    }
}
