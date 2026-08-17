package com.vela.voice.narrator

import com.vela.events.C2Event
import com.vela.events.ToolCallCorrelator.AttributedActivity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect

/**
 * Narration output seam. Producing synthesized speech audio (TTS) requires
 * whatever pipeline `LiveKitVoiceTransport`/the voice-worker ultimately uses
 * — that's out of scope for this lane (no TTS synthesis exists in this
 * codebase yet). [Narrator] instead produces narration TEXT lines from real
 * C2 events; a [NarrationSink] is the hand-off point to that pipeline.
 */
fun interface NarrationSink {
    fun narrate(text: String)
}

/**
 * Feeds real C2 events (via [AttributedActivity], already correlated by
 * `ToolCallCorrelator` for correct sub-agent attribution) into voice
 * narration (V5).
 *
 * V4 constraint: narration must only ever be derived from real event
 * fields — no synthetic reassurance filler ("still thinking…" loops). This
 * is enforced structurally: [narrationFor] is a pure function of the event
 * payload; there is no code path that emits text absent a real event, and
 * chatty deltas ([C2Event.ThinkingDelta]) are deliberately filtered out
 * (see below) rather than turned into filler.
 */
class Narrator {

    /**
     * Pure conversion from an attributed activity to a short narration
     * line, or `null` if this event type should not be narrated.
     *
     * Deliberately NOT narrated:
     *  - [C2Event.ThinkingDelta]: per-token/per-chunk deltas are too chatty
     *    to narrate individually and would function as filler once
     *    smoothed into a "still thinking…" cadence — exactly what V4
     *    disallows. [C2Event.ThinkingFinal] (the settled summary) IS
     *    narrated instead, since it carries real, complete content.
     */
    fun narrationFor(activity: AttributedActivity): String? {
        val agentLabel = activity.agentName

        return when (val event = activity.event) {
            is C2Event.ToolStarted -> if (agentLabel != null) {
                "Delegating to $agentLabel: starting ${event.name}"
            } else {
                "Starting ${event.name}"
            }

            is C2Event.ToolCompleted -> if (agentLabel != null) {
                "Delegated to $agentLabel: ${event.name} completed in ${event.durationMs}ms"
            } else {
                "${event.name} completed in ${event.durationMs}ms"
            }

            is C2Event.Progress -> event.message.ifBlank { null }

            is C2Event.ThinkingDelta -> null // too chatty; see doc above

            is C2Event.ThinkingFinal -> event.text.ifBlank { null }

            is C2Event.Error -> "Error: ${event.message}"

            is C2Event.Usage -> null // internal accounting, not narration-worthy

            is C2Event.ApprovalRequested -> "Approval needed: ${event.toolName}"
        }
    }

    /**
     * Thin flow collector: derives narration text via [narrationFor] and
     * forwards non-null results to [sink]. This is the entire "feeds real
     * C2 events into voice narration" behavior — all narration-content
     * logic lives in the pure function above.
     */
    suspend fun narrate(activities: Flow<AttributedActivity>, sink: NarrationSink) {
        activities.collect { activity ->
            narrationFor(activity)?.let { sink.narrate(it) }
        }
    }
}
