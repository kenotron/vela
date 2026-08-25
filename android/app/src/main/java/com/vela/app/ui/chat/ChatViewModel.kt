package com.vela.app.ui.chat

import com.vela.core.domain.LedgerRepository
import com.vela.core.domain.LedgerRepository.Decision
import com.vela.core.domain.LedgerRepository.Status
import com.vela.core.domain.notification.AttentionCandidate
import com.vela.core.ui.TranscriptMessage
import com.vela.hosttools.AmplifierToolLoopClient
import com.vela.voice.handoff.TierCoordinator.TierEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Bridges the real [AmplifierToolLoopClient] (C1 tool-loop, goal item 4),
 * real voice turns (see [ingestVoiceTurn], issue #33), and the ledger's
 * approval gate (see [observeLedgerApprovals] / [resolveApproval], issue
 * #35 -- built on issue #18's `AttentionCandidate`/`LedgerRepository`) into
 * the single [TranscriptMessage] stream [com.vela.core.ui.ChatTranscript]
 * renders. A message typed via [sendMessage], a turn narrated via
 * [ingestVoiceTurn], and an approval prompt surfaced by
 * [observeLedgerApprovals] all land in the same [messages] stream, appended
 * in causal/collection order -- there is exactly one session-wide
 * transcript, not several.
 *
 * [ledgerRepository] is optional (defaults to `null`) so existing callers
 * and tests that only exercise [sendMessage]/[ingestVoiceTurn] are
 * unaffected; the real app wires the real [LedgerRepository] (see
 * `VelaAppContainer.ledgerRepository`, `MainActivity`).
 */
class ChatViewModel(
    private val toolLoopClient: AmplifierToolLoopClient,
    private val ledgerRepository: LedgerRepository? = null,
) {

    private val _messages = MutableStateFlow<List<TranscriptMessage>>(emptyList())
    val messages: StateFlow<List<TranscriptMessage>> = _messages.asStateFlow()

    /** Tracks ledger entry id -> posted transcript message id, so a re-emission of
     * [LedgerRepository.observeEntries] (e.g. an unrelated entry changing) does not
     * re-post a prompt already surfaced for the same entry. */
    private val postedApprovalsByEntryId = ConcurrentHashMap<String, String>()

    /**
     * Word heuristic for resolving a pending [TranscriptMessage.Approval] by voice
     * (issue #35's "if a voice path already exists" branch -- see [ingestVoiceTurn]).
     * The codebase's only other voice-approval mechanism, `events.ApprovalVoiceBridge`,
     * targets an entirely different id space (an HTTP `ApprovalClient`'s approval ids,
     * unrelated to [LedgerRepository] entry ids) and is unwired/dead code, so it is not
     * a drop-in path for [TranscriptMessage.Approval] -- this is a small, self-contained
     * equivalent scoped to this feature's own entries, using the same non-NLU
     * substring-containment heuristic (deliberately simple, matching that precedent).
     */
    private val voiceAcceptWords = listOf("yes", "approve", "confirm", "accept")
    private val voiceDeclineWords = listOf("no", "deny", "decline", "reject")

    fun sendMessage(scope: CoroutineScope, text: String) {
        if (text.isBlank()) return

        // Snapshot the conversation-so-far as role/content pairs BEFORE adding this new
        // user message, so it becomes the history sent alongside this turn. Without this,
        // AmplifierToolLoopClient.runTurn() (which is stateless per call by design) sends
        // only the newest message each time -- messages sit together in the UI list but the
        // server genuinely never sees earlier turns, a real bug this fixes.
        //
        // Only Chat entries carry role/content turns the server understands; Approval
        // entries are a chat-surface presentation concern and are excluded from history.
        val historyForThisTurn = _messages.value
            .filterIsInstance<TranscriptMessage.Chat>()
            .map { msg ->
                val role = if (msg.speaker == TranscriptMessage.Speaker.USER) "user" else "assistant"
                role to msg.text
            }

        val userMessage = TranscriptMessage.Chat(
            id = UUID.randomUUID().toString(),
            speaker = TranscriptMessage.Speaker.USER,
            text = text,
        )
        _messages.update { it + userMessage }

        scope.launch {
            val responseText = try {
                toolLoopClient.runTurn(text, history = historyForThisTurn).finalContent
            } catch (e: Exception) {
                "Error contacting server: ${e.message}"
            }
            _messages.update {
                it + TranscriptMessage.Chat(
                    id = UUID.randomUUID().toString(),
                    speaker = TranscriptMessage.Speaker.ASSISTANT,
                    text = responseText,
                )
            }
        }
    }

    /**
     * Folds a real voice turn into the same transcript [messages] stream
     * [sendMessage] appends to (issue #33). The caller has already spoken
     * [utterance] into [com.vela.voice.handoff.TierCoordinator.handle] and
     * passes the resulting [events] flow here.
     *
     * Issue #35's spoken-resolution path: if there is a pending
     * [TranscriptMessage.Approval] when [utterance] arrives and [utterance]
     * is recognized as an accept/decline word (see [voiceAcceptWords] /
     * [voiceDeclineWords]), this resolves that approval via [resolveApproval]
     * instead of routing the utterance through the tier pipeline -- a spoken
     * "yes" while a prompt is outstanding answers the prompt, it is not a new
     * query. [events] is not collected in that case (the caller's
     * `TierCoordinator.handle(utterance)` call itself has no side effect
     * until collected, so this is safe to skip). The spoken utterance is
     * still appended as a user [TranscriptMessage.Chat] entry either way, so
     * what was said remains visible in the transcript.
     *
     * Otherwise, behavior is unchanged from before this branch existed: the
     * utterance is appended immediately (so it's visible the moment the turn
     * starts, matching how [sendMessage] appends the user message before the
     * response arrives), then one assistant message is appended per relevant
     * [TierEvent] as they stream in, preserving causal order.
     *
     * [TierEvent.RespondDirectly] carries no answer text of its own (the
     * fast-tier answer is spoken elsewhere in the pipeline -- see
     * `TierCoordinator`'s kdoc) so it does not append a transcript entry by
     * itself; every other event maps directly to an assistant line.
     */
    fun ingestVoiceTurn(scope: CoroutineScope, utterance: String, events: Flow<TierEvent>) {
        _messages.update {
            it + TranscriptMessage.Chat(
                id = UUID.randomUUID().toString(),
                speaker = TranscriptMessage.Speaker.USER,
                text = utterance,
            )
        }

        if (tryResolvePendingApprovalByVoice(scope, utterance)) return

        scope.launch {
            events.collect { event ->
                val text = when (event) {
                    is TierEvent.RespondDirectly -> null
                    is TierEvent.Acknowledged -> event.acknowledgement
                    is TierEvent.Narrating -> event.text
                    is TierEvent.Completed -> event.resultText
                    is TierEvent.Failed -> "Error: ${event.message}"
                }
                if (text != null) {
                    _messages.update {
                        it + TranscriptMessage.Chat(
                            id = UUID.randomUUID().toString(),
                            speaker = TranscriptMessage.Speaker.ASSISTANT,
                            text = text,
                        )
                    }
                }
            }
        }
    }

    /**
     * If a [TranscriptMessage.Approval] is currently [TranscriptMessage.Approval.Status.PENDING]
     * and [utterance] contains a recognized accept/decline word, resolves that approval via
     * [resolveApproval] and returns `true`. Returns `false` (no-op) if there is no pending
     * approval, or [utterance] doesn't match either word list -- in which case the caller
     * proceeds with normal tier-pipeline handling. The oldest pending approval is chosen when
     * more than one is outstanding, matching first-in-first-out expectations.
     */
    private fun tryResolvePendingApprovalByVoice(scope: CoroutineScope, utterance: String): Boolean {
        val pending = _messages.value.firstOrNull {
            it is TranscriptMessage.Approval && it.status == TranscriptMessage.Approval.Status.PENDING
        } as? TranscriptMessage.Approval ?: return false

        val text = utterance.lowercase()
        val accept = voiceAcceptWords.any { text.contains(it) }
        val decline = !accept && voiceDeclineWords.any { text.contains(it) }
        if (!accept && !decline) return false

        resolveApproval(scope, pending.id, approved = accept)
        return true
    }

    /**
     * Surfaces an approval-gate decision (issue #18's `AttentionCandidate` /
     * `LedgerRepository.LedgerEntry`) as a distinct, flagged entry in the
     * chat transcript, per issue #35. [entryId] is the ledger entry id this
     * prompt corresponds to -- callers deriving this from an
     * `AttentionCandidate` should pass `candidate.entry.id`. Returns the new
     * transcript message's id, which the caller passes back to
     * [resolveApproval] once the user responds (via a tap in
     * [com.vela.core.ui.ChatTranscript]'s approve/deny actions, or via an
     * existing voice-response path such as `ApprovalVoiceBridge`).
     *
     * Most callers should not need to call this directly -- see
     * [observeLedgerApprovals], which calls it automatically for every live
     * [AttentionCandidate] the ledger produces. It remains public for tests
     * and for any caller that already has a candidate in hand outside that
     * observation loop.
     */
    fun postApprovalPrompt(entryId: String, promptText: String): String {
        val messageId = UUID.randomUUID().toString()
        _messages.update {
            it + TranscriptMessage.Approval(
                id = messageId,
                entryId = entryId,
                promptText = promptText,
            )
        }
        return messageId
    }

    /**
     * Records the user's response to a previously-posted approval prompt
     * ([postApprovalPrompt] / [observeLedgerApprovals]). The original
     * [TranscriptMessage.Approval] entry is updated in place to
     * [TranscriptMessage.Approval.Status.APPROVED] or
     * [TranscriptMessage.Approval.Status.DENIED] -- it is never removed --
     * and a follow-up [TranscriptMessage.Chat] entry recording the outcome is
     * appended, so the resolution is always visible as its own transcript
     * entry rather than a silently vanished prompt. A [messageId] that does
     * not match any pending approval (already resolved, or unknown) is a
     * no-op: no follow-up entry is appended and no other entry is touched.
     *
     * When [ledgerRepository] is configured, this also calls
     * [LedgerRepository.recordDecision] against the entry's [ledgerRepository]
     * -- `ACCEPTED` for [approved] `true`, `DISMISSED` for `false` -- so a
     * decision made in chat is reflected back to the ledger the same way a
     * decision made on the Queue tab is ([QueueViewModel] uses the same two
     * statuses for its accept/dismiss actions). [ledgerRepository] being
     * unset ([resolveApproval]-only usage, e.g. in tests) skips this without
     * affecting the transcript update above.
     */
    fun resolveApproval(scope: CoroutineScope, messageId: String, approved: Boolean) {
        val current = _messages.value
        val pending = current.firstOrNull {
            it is TranscriptMessage.Approval &&
                it.id == messageId &&
                it.status == TranscriptMessage.Approval.Status.PENDING
        } as? TranscriptMessage.Approval ?: return

        val resolvedStatus = if (approved) {
            TranscriptMessage.Approval.Status.APPROVED
        } else {
            TranscriptMessage.Approval.Status.DENIED
        }

        _messages.update { list ->
            list.map { msg ->
                if (msg is TranscriptMessage.Approval && msg.id == messageId) {
                    msg.copy(status = resolvedStatus)
                } else {
                    msg
                }
            } + TranscriptMessage.Chat(
                id = UUID.randomUUID().toString(),
                speaker = TranscriptMessage.Speaker.ASSISTANT,
                text = if (approved) {
                    "Approved: ${pending.promptText}"
                } else {
                    "Denied: ${pending.promptText}"
                },
            )
        }

        ledgerRepository?.let { repo ->
            scope.launch {
                repo.recordDecision(
                    pending.entryId,
                    Decision(
                        status = if (approved) Status.ACCEPTED else Status.DISMISSED,
                        decidedAtEpochMs = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    /**
     * The actual wiring point from the ledger's pending approvals to the
     * chat surface, per issue #35: observes [ledgerRepository] (when
     * configured) and, for every [AttentionCandidate] the ledger produces
     * (i.e. every [LedgerRepository.LedgerEntry] with `requiresAttention ==
     * true` -- see [AttentionCandidate.from]) that is still
     * [Status.PENDING] and hasn't already been posted, calls
     * [postApprovalPrompt] automatically so the prompt appears in the chat
     * transcript without any caller having to notice the entry itself.
     * [postedApprovalsByEntryId] prevents re-posting the same entry on a
     * later, unrelated `observeEntries()` emission. A no-op if
     * [ledgerRepository] is null (the app's real composition root always
     * configures one -- see `VelaAppContainer.ledgerRepository`,
     * `MainActivity`).
     *
     * Returns the launched [kotlinx.coroutines.Job] (or `null` if
     * [ledgerRepository] is unset) so a caller that needs a bounded
     * lifetime -- e.g. a test using a [CoroutineScope] whose completion is
     * awaited, since [LedgerRepository.observeEntries] never completes on
     * its own -- can cancel it explicitly.
     */
    fun observeLedgerApprovals(scope: CoroutineScope): Job? {
        val repo = ledgerRepository ?: return null
        return scope.launch {
            repo.observeEntries().collect { entries ->
                entries
                    .filter { it.status == Status.PENDING }
                    .mapNotNull { AttentionCandidate.from(it) }
                    .forEach { candidate ->
                        val entry = candidate.entry
                        postedApprovalsByEntryId.computeIfAbsent(entry.id) {
                            postApprovalPrompt(entryId = entry.id, promptText = entry.summary)
                        }
                    }
            }
        }
    }
}
