package com.vela.app.ui.queue

import com.vela.core.domain.LedgerRepository
import com.vela.core.domain.LedgerRepository.Decision
import com.vela.core.domain.LedgerRepository.LedgerEntry
import com.vela.core.domain.LedgerRepository.Status
import com.vela.core.ui.AttentionCard
import com.vela.core.ui.CardDecision
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.ExperimentalCoroutinesApi

/** In-memory fake [LedgerRepository] for unit testing without mockk. */
private class FakeLedgerRepository : LedgerRepository {
    private val entriesFlow = MutableStateFlow<List<LedgerEntry>>(emptyList())
    val recordedDecisions = mutableListOf<Pair<String, Decision>>()

    /** When set, [recordDecision] throws this instead of recording. */
    var failureToThrow: Exception? = null

    override fun observeEntries(): Flow<List<LedgerEntry>> = entriesFlow

    override suspend fun get(id: String): LedgerEntry? = entriesFlow.value.firstOrNull { it.id == id }

    override suspend fun append(entry: LedgerEntry) {
        entriesFlow.value = entriesFlow.value + entry
    }

    override suspend fun recordDecision(entryId: String, decision: Decision) {
        failureToThrow?.let { throw it }
        recordedDecisions.add(entryId to decision)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class QueueViewModelTest {

    private fun card(id: String = "c1") = AttentionCard(id = id, title = "Title $id", summary = "Summary $id")

    @Test
    fun `onDecision ACCEPT records ACCEPTED status and removes card optimistically`() = runTest {
        val repo = FakeLedgerRepository()
        val viewModel = QueueViewModel(repo)
        seedCard(viewModel, card("c1"))

        viewModel.onDecision(this, card("c1"), CardDecision.ACCEPT)
        // Optimistic removal happens synchronously, before the launched coroutine runs.
        assertTrue(viewModel.cards.value.none { it.id == "c1" })

        advanceUntilIdle()

        assertEquals(1, repo.recordedDecisions.size)
        val (entryId, decision) = repo.recordedDecisions.first()
        assertEquals("c1", entryId)
        assertEquals(Status.ACCEPTED, decision.status)
        assertTrue(viewModel.cards.value.none { it.id == "c1" })
    }

    @Test
    fun `onDecision DISMISS records DISMISSED status`() = runTest {
        val repo = FakeLedgerRepository()
        val viewModel = QueueViewModel(repo)
        seedCard(viewModel, card("c1"))

        viewModel.onDecision(this, card("c1"), CardDecision.DISMISS)
        advanceUntilIdle()

        assertEquals(Status.DISMISSED, repo.recordedDecisions.first().second.status)
    }

    @Test
    fun `onDecision DEFER records DEFERRED status`() = runTest {
        val repo = FakeLedgerRepository()
        val viewModel = QueueViewModel(repo)
        seedCard(viewModel, card("c1"))

        viewModel.onDecision(this, card("c1"), CardDecision.DEFER)
        advanceUntilIdle()

        assertEquals(Status.DEFERRED, repo.recordedDecisions.first().second.status)
    }

    @Test
    fun `onDecision rolls back optimistic removal when repository call fails`() = runTest {
        val repo = FakeLedgerRepository()
        repo.failureToThrow = RuntimeException("network down")
        val viewModel = QueueViewModel(repo)
        seedCard(viewModel, card("c1"))

        viewModel.onDecision(this, card("c1"), CardDecision.ACCEPT)
        // Optimistically removed immediately.
        assertTrue(viewModel.cards.value.none { it.id == "c1" })

        advanceUntilIdle()

        // Restored after the failed repository call.
        assertEquals(1, viewModel.cards.value.size)
        assertEquals("c1", viewModel.cards.value.first().id)
    }

    /** Seeds [viewModel]'s cards StateFlow directly for test setup (bypassing start()). */
    private fun seedCard(viewModel: QueueViewModel, vararg cards: AttentionCard) {
        val field = QueueViewModel::class.java.getDeclaredField("_cards")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flow = field.get(viewModel) as MutableStateFlow<List<AttentionCard>>
        flow.value = cards.toList()
    }
}
