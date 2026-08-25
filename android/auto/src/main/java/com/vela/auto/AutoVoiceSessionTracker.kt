package com.vela.auto

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Instrumentation for Auto-initiated voice sessions (issue #51).
 *
 * The product question #51 exists to answer is: "is the car use case
 * real?" -- this is a simple, queryable counter, not a dashboard. Every
 * Auto-initiated voice session emits a recordable "started" event and,
 * later, a recordable "completed" event, and [snapshot] gives a point-in-
 * time, queryable count of both plus recent session records for ad-hoc
 * inspection/export.
 *
 * Thread-safety: session start/completion callbacks may be invoked from a
 * coroutine dispatcher; all mutable state here is backed by atomics /
 * a thread-safe list, so no external synchronization is required.
 */
public interface AutoVoiceSessionTracker {
    /** Record that an Auto-initiated voice session has started. */
    public fun onSessionStarted(utterance: String)

    /**
     * Record that an Auto-initiated voice session has completed.
     * [success] is true when the session reached a normal terminal
     * outcome (direct response or slow-tier completion); false for a
     * slow-tier failure or an underlying flow failure/cancellation.
     */
    public fun onSessionCompleted(utterance: String, success: Boolean)

    /** Point-in-time queryable snapshot of recorded session counts. */
    public fun snapshot(): Snapshot

    /** Queryable instrumentation snapshot for #51's "is this real?" signal. */
    public data class Snapshot(
        val startedCount: Int,
        val completedCount: Int,
        val succeededCount: Int,
        val failedCount: Int,
    )
}

/**
 * Default in-memory [AutoVoiceSessionTracker]. A single, process-lifetime
 * instance is sufficient for the "queryable count with tests" scope #51
 * asks for; persisting/exporting these counts (e.g. to `:events` or a
 * backend analytics sink) is a residual, left for when a real Auto
 * integration determines what "recordable" needs to mean in production.
 */
public class InMemoryAutoVoiceSessionTracker : AutoVoiceSessionTracker {

    private val startedCount = AtomicInteger(0)
    private val completedCount = AtomicInteger(0)
    private val succeededCount = AtomicInteger(0)
    private val failedCount = AtomicInteger(0)
    private val lastEventAtMillis = AtomicLong(0L)

    private val records = CopyOnWriteArrayList<SessionRecord>()

    /** A single recorded lifecycle event, for ad-hoc inspection/export. */
    public data class SessionRecord(
        val utterance: String,
        val kind: Kind,
        val success: Boolean?,
    ) {
        public enum class Kind { STARTED, COMPLETED }
    }

    override fun onSessionStarted(utterance: String) {
        startedCount.incrementAndGet()
        records.add(SessionRecord(utterance, SessionRecord.Kind.STARTED, success = null))
    }

    override fun onSessionCompleted(utterance: String, success: Boolean) {
        completedCount.incrementAndGet()
        if (success) succeededCount.incrementAndGet() else failedCount.incrementAndGet()
        records.add(SessionRecord(utterance, SessionRecord.Kind.COMPLETED, success = success))
    }

    override fun snapshot(): AutoVoiceSessionTracker.Snapshot = AutoVoiceSessionTracker.Snapshot(
        startedCount = startedCount.get(),
        completedCount = completedCount.get(),
        succeededCount = succeededCount.get(),
        failedCount = failedCount.get(),
    )

    /** Recent session records, oldest first. Primarily for tests/debugging. */
    public fun records(): List<SessionRecord> = records.toList()
}
