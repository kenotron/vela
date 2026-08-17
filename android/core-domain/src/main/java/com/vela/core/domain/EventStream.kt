package com.vela.core.domain

import kotlinx.coroutines.flow.Flow

/**
 * Domain interface for the cross-cutting event stream (structured telemetry /
 * activity feed consumed by UI surfaces and diagnostics). Implemented across
 * lanes; this lane (1.1) defines the contract only.
 */
interface EventStream {

    /** Subscribe to all events, optionally filtered by [types]. */
    fun observe(types: Set<EventType> = emptySet()): Flow<DomainEvent>

    /** Publish an event to the stream. */
    suspend fun publish(event: DomainEvent)

    enum class EventType {
        VOICE,
        TOOL,
        LEDGER,
        UI,
        SYSTEM,
    }

    data class DomainEvent(
        val type: EventType,
        val name: String,
        val timestampEpochMs: Long,
        val payloadJson: String? = null,
    )
}
