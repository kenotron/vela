package com.vela.events

import com.vela.core.domain.VoiceTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterIsInstance

/**
 * Secondary (voice) confirmation channel for lane 3.1's F2 approval gate.
 *
 * The primary path is the touch-based Accept/Decline card rendered by
 * `LiveActivityScreen` (Task 2). This bridge is a deliberately simple
 * word-matching heuristic — NOT NLU — that lets a spoken "yes"/"approve" or
 * "no"/"decline" resolve a pending approval while it's outstanding, per the
 * goal's "both by touch and by voice" requirement. It listens to
 * [VoiceTransport.incomingEvents] for final transcript deltas and, if one is
 * received while [pendingApprovalId] is non-null, calls [ApprovalClient.decide]
 * exactly once and clears the pending id.
 *
 * Kept intentionally minimal: no synonym expansion, no ambiguity resolution
 * beyond simple substring containment, and no retry — matching the same
 * fire-and-forget semantics as the touch path (server auto-declines pending
 * approvals ~30s server-side regardless).
 */
class ApprovalVoiceBridge(
    private val voiceTransport: VoiceTransport,
    private val approvalClient: ApprovalClient,
    private val baseUrl: String,
    private val bearerToken: String,
) {
    /** Set by the caller (e.g. the UI layer) when an approval becomes pending; cleared once resolved. */
    var pendingApprovalId: String? = null

    private val acceptWords = listOf("yes", "approve", "confirm", "accept")
    private val declineWords = listOf("no", "deny", "decline", "reject")

    /**
     * Collects the voice transport's incoming events for the lifetime of the
     * caller's coroutine scope, resolving [pendingApprovalId] via voice when
     * a matching final transcript arrives.
     */
    suspend fun listen() {
        voiceTransport.incomingEvents
            .filterIsInstance<VoiceTransport.VoiceEvent.TranscriptDelta>()
            .collectFinalDeltas { delta -> handleTranscript(delta) }
    }

    private suspend fun Flow<VoiceTransport.VoiceEvent.TranscriptDelta>.collectFinalDeltas(
        onFinal: suspend (VoiceTransport.VoiceEvent.TranscriptDelta) -> Unit,
    ) {
        collect { delta -> if (delta.isFinal) onFinal(delta) }
    }

    private suspend fun handleTranscript(delta: VoiceTransport.VoiceEvent.TranscriptDelta) {
        val approvalId = pendingApprovalId ?: return
        val text = delta.text.lowercase()

        val accept = acceptWords.any { text.contains(it) }
        val decline = !accept && declineWords.any { text.contains(it) }
        if (!accept && !decline) return

        pendingApprovalId = null
        approvalClient.decide(baseUrl, bearerToken, approvalId, accept)
    }
}
