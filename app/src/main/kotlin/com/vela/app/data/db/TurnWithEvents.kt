package com.vela.app.data.db

import androidx.room.Embedded
import androidx.room.Relation

/**
 * Room relation: one Turn + all its TurnEvents loaded in a single query.
 * Events are sorted by seq in [sortedEvents] — Room doesn't guarantee
 * child order, so we sort in Kotlin.
 *
 * This is the ONLY representation used by the UI — no separate "live turn"
 * vs "completed turn" paths. The status field on [turn] distinguishes them.
 * Events are always present regardless of turn status.
 */
data class TurnWithEvents(
    @Embedded val turn: TurnEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "turnId",
    )
    val events: List<TurnEventEntity>,
) {
    /** Events in correct display order. Seq is the ordering primitive — never timestamp. */
    val sortedEvents: List<TurnEventEntity> get() = events.sortedBy { it.seq }

    val isRunning: Boolean  get() = turn.status == "running"
    val isComplete: Boolean get() = turn.status == "complete"
    val isError: Boolean    get() = turn.status == "error"
}
