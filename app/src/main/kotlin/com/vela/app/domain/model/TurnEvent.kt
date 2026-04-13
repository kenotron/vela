package com.vela.app.domain.model

import java.util.UUID

sealed class TurnEventType(val tag: String) {
    object Text : TurnEventType("text")
    object Tool : TurnEventType("tool")
}

enum class ToolStatus { RUNNING, DONE, ERROR }

/**
 * A single event within an AI turn. Ordered by [seq] — an integer counter
 * assigned at creation time, never derived from wall-clock time.
 *
 * Text events accumulate content as tokens arrive (updated in-place).
 * Tool events start as RUNNING and are updated to DONE/ERROR when the
 * tool completes — same row, same position in the list.
 */
data class TurnEvent(
    val id: String = UUID.randomUUID().toString(),
    val turnId: String,
    val seq: Int,
    val type: String,               // TurnEventType.tag

    // Text event
    val text: String? = null,

    // Tool event
    val toolName: String? = null,
    val toolDisplayName: String? = null,
    val toolIcon: String? = null,
    val toolSummary: String? = null,
    val toolArgs: String? = null,
    val toolResult: String? = null,
    val toolStatus: String? = null, // ToolStatus name
)
