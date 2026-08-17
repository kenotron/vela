package com.vela.events

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Thin aggregator that runs the raw [C2Event] stream through a
 * [ToolCallCorrelator] to produce correctly-attributed activity. All the
 * correlation logic lives in [ToolCallCorrelator]; this class exists only
 * to give UI/narration consumers a single `Flow<AttributedActivity>` seam.
 */
class ActivityFeed(private val correlator: ToolCallCorrelator = ToolCallCorrelator()) {
    fun process(events: Flow<C2Event>): Flow<ToolCallCorrelator.AttributedActivity> =
        events.map { correlator.accept(it) }
}
