package com.vela.events

import java.util.ArrayDeque

/**
 * Correlates streaming C2 events (which may lack their own `toolCallId`) to
 * the correct in-flight tool call, applying the "claim-first-unclaimed"
 * semantics from the `eb207e74` lesson (design doc §10 Stage 0 table):
 * never assume single-delegate/single-stream FIFO ordering.
 *
 * A FIFO queue of open tool calls is kept per `(sessionId, agentName)` key.
 * Under N-way parallel delegation there are N concurrently open tool calls,
 * one per distinct `agentName` — a per-key queue guarantees a `progress` or
 * `thinking` chunk for agent A can never be attributed to agent B's tool
 * call, even when their events interleave on the wire.
 *
 * This class is intentionally plain (no coroutines/Android dependency) and
 * thread-confined — callers are expected to funnel events through it
 * sequentially (e.g. via a single `Flow.map`), matching how the SSE source
 * delivers events one at a time.
 */
class ToolCallCorrelator {

    data class AttributedActivity(
        val agentName: String?,
        val sessionId: String,
        val toolCallId: String?,
        val toolName: String?,
        val event: C2Event,
    )

    private data class OpenCall(val toolCallId: String, val toolName: String)

    /** FIFO queue of open (started, not yet completed) tool calls per (sessionId, agentName). */
    private val openByKey = mutableMapOf<Key, ArrayDeque<OpenCall>>()

    private data class Key(val sessionId: String, val agentName: String?)

    fun accept(event: C2Event): AttributedActivity {
        return when (event) {
            is C2Event.ToolStarted -> {
                val key = Key(event.sessionId, event.agentName)
                openByKey.getOrPut(key) { ArrayDeque() }.addLast(OpenCall(event.toolCallId, event.name))
                AttributedActivity(
                    agentName = event.agentName,
                    sessionId = event.sessionId,
                    toolCallId = event.toolCallId,
                    toolName = event.name,
                    event = event,
                )
            }

            is C2Event.ToolCompleted -> {
                val key = Key(event.sessionId, event.agentName)
                val queue = openByKey[key]
                if (queue != null) {
                    // Remove the matching entry by toolCallId (always present
                    // on tool/completed per the wire contract) rather than
                    // blindly popping the head — under parallel delegation
                    // completions can arrive out of start order.
                    val iterator = queue.iterator()
                    while (iterator.hasNext()) {
                        if (iterator.next().toolCallId == event.toolCallId) {
                            iterator.remove()
                            break
                        }
                    }
                    if (queue.isEmpty()) openByKey.remove(key)
                }
                AttributedActivity(
                    agentName = event.agentName,
                    sessionId = event.sessionId,
                    toolCallId = event.toolCallId,
                    toolName = event.name,
                    event = event,
                )
            }

            is C2Event.Progress -> attributeFromOldestOpen(event.sessionId, event.agentName, event)
            is C2Event.ThinkingDelta -> attributeFromOldestOpen(event.sessionId, event.agentName, event)
            is C2Event.ThinkingFinal -> attributeFromOldestOpen(event.sessionId, event.agentName, event)

            is C2Event.Usage -> AttributedActivity(
                agentName = event.agentName,
                sessionId = event.sessionId,
                toolCallId = null,
                toolName = null,
                event = event,
            )

            is C2Event.Error -> AttributedActivity(
                agentName = event.agentName,
                sessionId = event.sessionId,
                toolCallId = null,
                toolName = null,
                event = event,
            )

            is C2Event.ApprovalRequested -> AttributedActivity(
                agentName = event.agentName,
                sessionId = event.sessionId,
                toolCallId = null,
                toolName = event.toolName,
                event = event,
            )
        }
    }

    private fun attributeFromOldestOpen(sessionId: String, agentName: String?, event: C2Event): AttributedActivity {
        val key = Key(sessionId, agentName)
        // Peek, don't dequeue: multiple progress/thinking chunks may arrive
        // for the same still-open tool call before it completes.
        val oldest = openByKey[key]?.peekFirst()
        return AttributedActivity(
            agentName = agentName,
            sessionId = sessionId,
            toolCallId = oldest?.toolCallId,
            toolName = oldest?.toolName,
            event = event,
        )
    }
}
