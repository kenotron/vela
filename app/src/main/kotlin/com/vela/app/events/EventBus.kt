package com.vela.app.events

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/** A single event routed through the in-process pub/sub bus. */
data class VelaEvent(val topic: String, val payload: String)

/**
 * In-process pub/sub bus for cross-mini-app communication and Vela system events.
 *
 * Topics follow `{mini-app-type}:{event-name}` convention, e.g. `recipe:ingredients-ready`.
 * System events published by Vela itself: `vela:theme-changed`, `vela:vault-changed`,
 * `vela:vault-synced`, `vela:layout-changed`, `vela:ai-interrupted`, `vela:sync-failed`.
 *
 * [tryPublish] is safe to call from any thread including Binder threads (used by
 * `@JavascriptInterface` methods). [events] is collected by [VelaJSInterface.Events.subscribe]
 * coroutines and by future Kotlin subscribers.
 */
@Singleton
class EventBus @Inject constructor() {
    private val _events = MutableSharedFlow<VelaEvent>(extraBufferCapacity = 64)

    /** Subscribe to all events. Filter by [VelaEvent.topic] in the collector. */
    val events: SharedFlow<VelaEvent> = _events.asSharedFlow()

    /**
     * Non-suspending emit — safe on any thread. Returns `false` only if the
     * 64-event buffer is full (extremely unlikely in practice; log and drop).
     */
    fun tryPublish(topic: String, payload: String): Boolean =
        _events.tryEmit(VelaEvent(topic, payload))
}
