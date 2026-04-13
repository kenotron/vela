package com.vela.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One event within a Turn.
 * Ordered by [seq] — a monotonic counter assigned at creation, never a timestamp.
 *
 * type = "text": [text] carries the content.
 * type = "tool": [toolName]/[toolStatus] carry tool call state.
 *   Created with toolStatus="running", updated in-place to "done"/"error".
 *   Never moved in the list — seq is fixed at insertion.
 */
@Entity(tableName = "turn_events")
data class TurnEventEntity(
    @PrimaryKey val id: String,
    val turnId: String,
    val seq: Int,                   // ORDER BY seq ASC — never changes
    val type: String,               // "text" | "tool"

    // Text event
    val text: String? = null,

    // Tool event
    val toolName: String? = null,
    val toolDisplayName: String? = null,
    val toolIcon: String? = null,
    val toolSummary: String? = null,
    val toolArgs: String? = null,
    val toolResult: String? = null,
    val toolStatus: String? = null, // "running" | "done" | "error"
)
