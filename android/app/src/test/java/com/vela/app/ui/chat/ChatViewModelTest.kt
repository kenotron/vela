package com.vela.app.ui.chat

import com.vela.core.domain.HostToolRegistry
import com.vela.core.domain.LedgerRepository
import com.vela.core.domain.LedgerRepository.Decision
import com.vela.core.domain.LedgerRepository.LedgerEntry
import com.vela.core.domain.LedgerRepository.Status
import com.vela.core.ui.TranscriptMessage
import com.vela.hosttools.AmplifierToolLoopClient
import com.vela.voice.handoff.TierCoordinator.TierEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AmplifierToolLoopClient] is a concrete class (real HTTP client), not an
 * interface, so it can't be faked the way `FakeLedgerRepository` (below,
 * same pattern as `QueueViewModelTest`'s) fakes an interface. These tests
 * never invoke [sendMessage] against it (which would make a real network
 * call); instead, for tests that need pre-existing chat-sourced messages in
 * the stream, we seed [ChatViewModel]'s private `_messages` StateFlow
 * directly via reflection -- the same technique `QueueViewModelTest.seedCard`
 * uses. [ingestVoiceTurn] itself has no dependency on the tool loop client,
 * so it is exercised directly against a real [ChatViewModel] instance.
 */
private val emptyRegistry = object : HostToolRegistry {
    override fun all() = emptyList<com.vela.core.domain.HostTool>()
    override fun find(name: String) = null
}

private fun dummyClient(): AmplifierToolLoopClient = AmplifierToolLoopClient(
    baseUrl = "http://unused.invalid",
    apiKey = "unused",
    registry = emptyRegistry,
    clientSessionId = "test-session",
)

/** Seeds [viewModel]'s messages StateFlow directly for test setup (bypassing sendMessage/network). */
private fun seedMessages(viewModel: ChatViewModel, vararg messages: TranscriptMessage) {
    val field = ChatViewModel::class.java.getDeclaredField("_messages")
    field.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    val flow = field.get(viewModel) as MutableStateFlow<List<TranscriptMessage>>
    flow.value = messages.toList()
}

/** In-memory fake [LedgerRepository], same pattern as `QueueViewModelTest`'s. */
private class FakeLedgerRepository : LedgerRepository {
    private val entriesFlow = MutableStateFlow<List<LedgerEntry>>(emptyList())
    val recordedDecisions = mutableListOf<Pair<String, Decision>>()

    override fun observeEntries(): Flow<List<LedgerEntry>> = entriesFlow

    override suspend fun get(id: String): LedgerEntry? = entriesFlow.value.firstOrNull { it.id == id }

    override suspend fun append(entry: LedgerEntry) {
        entriesFlow.value = entriesFlow.value + entry
    }

    override suspend fun recordDecision(entryId: String, decision: Decision) {
        recordedDecisions.add(entryId to decision)
        entriesFlow.value = entriesFlow.value.map {
            if (it.id == entryId) it.copy(status = decision.status) else it
        }
    }

    fun seed(entry: LedgerEntry) {
        entriesFlow.value = entriesFlow.value + entry
    }
}

private fun attentionEntry(id: String = "e1", summary: String = "Delete file foo.txt?") = LedgerEntry(
    id = id,
    title = "Title $id",
    summary = summary,
    createdAtEpochMs = 0L,
    source = "test",
    status = Status.PENDING,
    requiresAttention = true,
)

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    @Test
    fun `ingestVoiceTurn appends the utterance then each tier event as an assistant message`() = runTest {
        val viewModel = ChatViewModel(dummyClient())

        val events = flow {
            emit(TierEvent.Acknowledged("what's the weather", "I'll get on that."))
            emit(TierEvent.Narrating("Checking forecast..."))
            emit(TierEvent.Completed("It's sunny."))
        }

        viewModel.ingestVoiceTurn(this, "what's the weather", events)
        advanceUntilIdle()

        val messages = viewModel.messages.value.filterIsInstance<TranscriptMessage.Chat>()
        assertEquals(4, messages.size)
        assertEquals(TranscriptMessage.Speaker.USER, messages[0].speaker)
        assertEquals("what's the weather", messages[0].text)
        assertEquals(TranscriptMessage.Speaker.ASSISTANT, messages[1].speaker)
        assertEquals("I'll get on that.", messages[1].text)
        assertEquals(TranscriptMessage.Speaker.ASSISTANT, messages[2].speaker)
        assertEquals("Checking forecast...", messages[2].text)
        assertEquals(TranscriptMessage.Speaker.ASSISTANT, messages[3].speaker)
        assertEquals("It's sunny.", messages[3].text)
    }

    @Test
    fun `ingestVoiceTurn RespondDirectly appends only the user utterance, no extra assistant line`() = runTest {
        val viewModel = ChatViewModel(dummyClient())

        val events = flow { emit(TierEvent.RespondDirectly("what time is it")) }

        viewModel.ingestVoiceTurn(this, "what time is it", events)
        advanceUntilIdle()

        val messages = viewModel.messages.value.filterIsInstance<TranscriptMessage.Chat>()
        assertEquals(1, messages.size)
        assertEquals(TranscriptMessage.Speaker.USER, messages[0].speaker)
        assertEquals("what time is it", messages[0].text)
    }

    @Test
    fun `ingestVoiceTurn Failed maps to an error-prefixed assistant message`() = runTest {
        val viewModel = ChatViewModel(dummyClient())

        val events = flow { emit(TierEvent.Failed("slow tier timed out")) }
        viewModel.ingestVoiceTurn(this, "do the thing", events)
        advanceUntilIdle()

        val messages = viewModel.messages.value.filterIsInstance<TranscriptMessage.Chat>()
        assertEquals(2, messages.size)
        assertEquals(TranscriptMessage.Speaker.ASSISTANT, messages[1].speaker)
        assertEquals("Error: slow tier timed out", messages[1].text)
    }

    @Test
    fun `a chat-typed message and a voice turn land in the same causally-ordered transcript stream`() = runTest {
        val viewModel = ChatViewModel(dummyClient())

        // Simulate a prior chat turn already having appended these two messages
        // (exactly what sendMessage would have produced), without making a real
        // network call.
        seedMessages(
            viewModel,
            TranscriptMessage.Chat(id = "u1", speaker = TranscriptMessage.Speaker.USER, text = "typed message"),
            TranscriptMessage.Chat(id = "a1", speaker = TranscriptMessage.Speaker.ASSISTANT, text = "typed reply"),
        )

        val events = flow { emit(TierEvent.Completed("spoken reply")) }
        viewModel.ingestVoiceTurn(this, "spoken utterance", events)
        advanceUntilIdle()

        val messages = viewModel.messages.value.filterIsInstance<TranscriptMessage.Chat>()
        assertEquals(4, messages.size)
        // Both message "kinds" are indistinguishable TranscriptMessage.Chat entries in one ordered stream.
        assertEquals(TranscriptMessage.Speaker.USER, messages[0].speaker)
        assertEquals("typed message", messages[0].text)
        assertEquals(TranscriptMessage.Speaker.ASSISTANT, messages[1].speaker)
        assertEquals("typed reply", messages[1].text)
        assertEquals(TranscriptMessage.Speaker.USER, messages[2].speaker)
        assertEquals("spoken utterance", messages[2].text)
        assertEquals(TranscriptMessage.Speaker.ASSISTANT, messages[3].speaker)
        assertEquals("spoken reply", messages[3].text)
    }

    @Test
    fun `postApprovalPrompt appends a distinct pending Approval entry`() = runTest {
        val viewModel = ChatViewModel(dummyClient())

        val messageId = viewModel.postApprovalPrompt(entryId = "entry-1", promptText = "Delete file foo.txt?")

        val messages = viewModel.messages.value
        assertEquals(1, messages.size)
        val approval = messages[0] as TranscriptMessage.Approval
        assertEquals(messageId, approval.id)
        assertEquals("entry-1", approval.entryId)
        assertEquals("Delete file foo.txt?", approval.promptText)
        assertEquals(TranscriptMessage.Approval.Status.PENDING, approval.status)
    }

    @Test
    fun `resolveApproval with approved=true updates the entry in place and appends a follow-up entry`() = runTest {
        val viewModel = ChatViewModel(dummyClient())
        val messageId = viewModel.postApprovalPrompt(entryId = "entry-1", promptText = "Delete file foo.txt?")

        viewModel.resolveApproval(this, messageId, approved = true)

        val messages = viewModel.messages.value
        assertEquals(2, messages.size)
        // Original entry is updated in place, not removed.
        val approval = messages[0] as TranscriptMessage.Approval
        assertEquals(messageId, approval.id)
        assertEquals(TranscriptMessage.Approval.Status.APPROVED, approval.status)
        // Follow-up entry records the resolution.
        val followUp = messages[1] as TranscriptMessage.Chat
        assertEquals(TranscriptMessage.Speaker.ASSISTANT, followUp.speaker)
        assertEquals("Approved: Delete file foo.txt?", followUp.text)
    }

    @Test
    fun `resolveApproval with approved=false updates the entry in place and appends a denial follow-up`() = runTest {
        val viewModel = ChatViewModel(dummyClient())
        val messageId = viewModel.postApprovalPrompt(entryId = "entry-2", promptText = "Send email to team?")

        viewModel.resolveApproval(this, messageId, approved = false)

        val messages = viewModel.messages.value
        assertEquals(2, messages.size)
        val approval = messages[0] as TranscriptMessage.Approval
        assertEquals(TranscriptMessage.Approval.Status.DENIED, approval.status)
        val followUp = messages[1] as TranscriptMessage.Chat
        assertEquals("Denied: Send email to team?", followUp.text)
    }

    @Test
    fun `resolveApproval is a no-op for an unknown or already-resolved message id`() = runTest {
        val viewModel = ChatViewModel(dummyClient())
        val messageId = viewModel.postApprovalPrompt(entryId = "entry-1", promptText = "Delete file foo.txt?")
        viewModel.resolveApproval(this, messageId, approved = true)
        val afterFirstResolution = viewModel.messages.value

        viewModel.resolveApproval(this, messageId, approved = false)
        viewModel.resolveApproval(this, "nonexistent-id", approved = true)

        assertEquals(afterFirstResolution, viewModel.messages.value)
    }

    @Test
    fun `an approval prompt interleaves in causal order with chat and voice entries`() = runTest {
        val viewModel = ChatViewModel(dummyClient())

        seedMessages(
            viewModel,
            TranscriptMessage.Chat(id = "u1", speaker = TranscriptMessage.Speaker.USER, text = "please back this up"),
        )
        val approvalId = viewModel.postApprovalPrompt(entryId = "entry-1", promptText = "Overwrite backup?")
        viewModel.resolveApproval(this, approvalId, approved = true)

        val messages = viewModel.messages.value
        assertEquals(3, messages.size)
        assertEquals("u1", (messages[0] as TranscriptMessage.Chat).id)
        assertEquals(approvalId, (messages[1] as TranscriptMessage.Approval).id)
        assertEquals(TranscriptMessage.Approval.Status.APPROVED, (messages[1] as TranscriptMessage.Approval).status)
        assertEquals("Approved: Overwrite backup?", (messages[2] as TranscriptMessage.Chat).text)
    }

    @Test
    fun `observeLedgerApprovals posts a chat approval prompt for a live AttentionCandidate entry`() = runTest {
        val repo = FakeLedgerRepository()
        val viewModel = ChatViewModel(dummyClient(), ledgerRepository = repo)

        val ledgerJob = viewModel.observeLedgerApprovals(this)
        repo.seed(attentionEntry(id = "e1", summary = "Delete file foo.txt?"))
        advanceUntilIdle()

        val messages = viewModel.messages.value
        assertEquals(1, messages.size)
        val approval = messages[0] as TranscriptMessage.Approval
        assertEquals("e1", approval.entryId)
        assertEquals("Delete file foo.txt?", approval.promptText)
        assertEquals(TranscriptMessage.Approval.Status.PENDING, approval.status)
        ledgerJob?.cancel()
    }

    @Test
    fun `observeLedgerApprovals ignores an entry that does not require attention`() = runTest {
        val repo = FakeLedgerRepository()
        val viewModel = ChatViewModel(dummyClient(), ledgerRepository = repo)

        val ledgerJob = viewModel.observeLedgerApprovals(this)
        repo.seed(attentionEntry(id = "e1").copy(requiresAttention = false))
        advanceUntilIdle()

        assertTrue(viewModel.messages.value.isEmpty())
        ledgerJob?.cancel()
    }

    @Test
    fun `observeLedgerApprovals does not re-post the same entry on a later unrelated emission`() = runTest {
        val repo = FakeLedgerRepository()
        val viewModel = ChatViewModel(dummyClient(), ledgerRepository = repo)

        val ledgerJob = viewModel.observeLedgerApprovals(this)
        repo.seed(attentionEntry(id = "e1"))
        advanceUntilIdle()
        // A later, unrelated entry appears in the same observeEntries() emission.
        repo.seed(attentionEntry(id = "e1", summary = "different text should not matter").copy())
        repo.seed(attentionEntry(id = "e2", summary = "a genuinely new entry"))
        advanceUntilIdle()

        val approvals = viewModel.messages.value.filterIsInstance<TranscriptMessage.Approval>()
        assertEquals(2, approvals.size)
        assertEquals(setOf("e1", "e2"), approvals.map { it.entryId }.toSet())
        // Only one prompt was ever posted per entry id.
        assertEquals(1, approvals.count { it.entryId == "e1" })
        ledgerJob?.cancel()
    }

    @Test
    fun `resolveApproval with a configured ledgerRepository records ACCEPTED for an approval`() = runTest {
        val repo = FakeLedgerRepository()
        val viewModel = ChatViewModel(dummyClient(), ledgerRepository = repo)
        val ledgerJob = viewModel.observeLedgerApprovals(this)
        repo.seed(attentionEntry(id = "e1", summary = "Delete file foo.txt?"))
        advanceUntilIdle()
        val approvalId = (viewModel.messages.value[0] as TranscriptMessage.Approval).id

        viewModel.resolveApproval(this, approvalId, approved = true)
        advanceUntilIdle()

        assertEquals(1, repo.recordedDecisions.size)
        val (entryId, decision) = repo.recordedDecisions.first()
        assertEquals("e1", entryId)
        assertEquals(Status.ACCEPTED, decision.status)
        ledgerJob?.cancel()
    }

    @Test
    fun `resolveApproval with a configured ledgerRepository records DISMISSED for a denial`() = runTest {
        val repo = FakeLedgerRepository()
        val viewModel = ChatViewModel(dummyClient(), ledgerRepository = repo)
        val ledgerJob = viewModel.observeLedgerApprovals(this)
        repo.seed(attentionEntry(id = "e1", summary = "Delete file foo.txt?"))
        advanceUntilIdle()
        val approvalId = (viewModel.messages.value[0] as TranscriptMessage.Approval).id

        viewModel.resolveApproval(this, approvalId, approved = false)
        advanceUntilIdle()

        assertEquals(1, repo.recordedDecisions.size)
        assertEquals(Status.DISMISSED, repo.recordedDecisions.first().second.status)
        ledgerJob?.cancel()
    }

    @Test
    fun `resolveApproval without a configured ledgerRepository does not throw and still updates the transcript`() = runTest {
        val viewModel = ChatViewModel(dummyClient())
        val messageId = viewModel.postApprovalPrompt(entryId = "entry-1", promptText = "Delete file foo.txt?")

        viewModel.resolveApproval(this, messageId, approved = true)
        advanceUntilIdle()

        val approval = viewModel.messages.value[0] as TranscriptMessage.Approval
        assertEquals(TranscriptMessage.Approval.Status.APPROVED, approval.status)
    }
}
