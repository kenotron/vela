package com.vela.events

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONObject

/**
 * Client for `vela-agentd`'s C2 SSE route (`GET /v1/events`, lane 3.1,
 * already merged). Consumes the tee'd event stream as-is; this module never
 * changes the wire contract.
 */
interface C2EventClient {
    /** Stream of parsed, forward-compatible [C2Event]s. */
    val events: Flow<C2Event>

    /** Current connection state. */
    val connectionState: Flow<ConnectionState>

    /** Establish the SSE connection to `{baseUrl}/v1/events`. */
    suspend fun connect(baseUrl: String, bearerToken: String)

    /** Tear down the SSE connection. */
    suspend fun disconnect()

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        RECONNECTING,
        ERROR,
    }
}

/**
 * OkHttp-SSE backed implementation. The [OkHttpClient] is injectable so
 * tests can substitute a client pointed at a `MockWebServer`.
 */
class OkHttpC2EventClient(
    private val client: OkHttpClient = OkHttpClient(),
) : C2EventClient {

    private val eventsFlow = MutableSharedFlow<C2Event>(extraBufferCapacity = 64)
    private val stateFlow = MutableStateFlow(C2EventClient.ConnectionState.DISCONNECTED)
    private var eventSource: EventSource? = null

    override val events: Flow<C2Event> = eventsFlow.asSharedFlow()
    override val connectionState: Flow<C2EventClient.ConnectionState> = stateFlow.asStateFlow()

    override suspend fun connect(baseUrl: String, bearerToken: String) {
        stateFlow.value = C2EventClient.ConnectionState.CONNECTING

        val request = Request.Builder()
            .url("$baseUrl/v1/events")
            .header("Authorization", "Bearer $bearerToken")
            .build()

        val listener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                stateFlow.value = C2EventClient.ConnectionState.CONNECTED
            }

            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String,
            ) {
                // Comment lines (": connected", ": keepalive") never reach
                // onEvent per OkHttp-SSE semantics — only `data:` lines do.
                // Defensive guard against blank/non-JSON payloads regardless.
                val trimmed = data.trim()
                if (trimmed.isEmpty()) return
                val json = runCatching { JSONObject(trimmed) }.getOrNull() ?: return
                val event = C2Event.fromJson(json) ?: return
                eventsFlow.tryEmit(event)
            }

            override fun onClosed(eventSource: EventSource) {
                stateFlow.value = C2EventClient.ConnectionState.DISCONNECTED
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                // OkHttp's EventSource does not auto-retry once onFailure is
                // called; treat any failure as terminal from this wrapper's
                // perspective. A 5xx response is treated as retryable
                // (RECONNECTING) — a caller that wants automatic reconnect
                // should call connect() again after observing that state.
                if (response != null && response.code in 500..599) {
                    stateFlow.value = C2EventClient.ConnectionState.RECONNECTING
                } else {
                    stateFlow.value = C2EventClient.ConnectionState.ERROR
                }
            }
        }

        eventSource = EventSources.createFactory(client).newEventSource(request, listener)
    }

    override suspend fun disconnect() {
        eventSource?.cancel()
        eventSource = null
        stateFlow.value = C2EventClient.ConnectionState.DISCONNECTED
    }
}
