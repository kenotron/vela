package com.vela.events

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Verifies the SSE parser tolerates `:`-prefixed comment/keepalive lines
 * (never treats them as data/errors) and emits parsed [C2Event]s in order
 * for real `data:` lines, against a canned response served by MockWebServer.
 */
class OkHttpC2EventClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // Both tests below previously had NO timeout at all: if the SSE
    // connection or event flow ever silently failed to deliver (network
    // issue, parsing regression, OkHttp EventSource never invoking onEvent),
    // `take(2).toList()` / `first { ... }` would suspend forever with the
    // JVM sitting at 0% CPU parked in a futex wait -- a real, reproducible
    // hang observed on this host, not just a hypothetical. withTimeout make
    // that failure mode surface as a fast, clear TimeoutCancellationException
    // instead of an indefinite hang.
    @Test
    fun `parses interleaved data and comment lines, ignoring comments`() = runBlocking {
        withTimeout(10_000) {
        val body = buildString {
            append(": connected\n\n")
            append(
                """data: {"type":"tool/started","sessionId":"s1","turnId":"t1","toolCallId":"tc1","name":"search"}""" +
                    "\n\n",
            )
            append(": keepalive\n\n")
            append(
                """data: {"type":"tool/completed","sessionId":"s1","turnId":"t1","toolCallId":"tc1",""" +
                    """"name":"search","durationMs":42}""" + "\n\n",
            )
            append(": keepalive\n\n")
        }

        server.enqueue(
            MockResponse()
                .setBody(body)
                .setHeader("Content-Type", "text/event-stream"),
        )

        val client = OkHttpC2EventClient(client = OkHttpClient())
        val baseUrl = server.url("/").toString().trimEnd('/')

        client.connect(baseUrl, "test-token")

        val received = client.events.take(2).toList()

        assertEquals(2, received.size)
        assertTrue(received[0] is C2Event.ToolStarted)
        assertTrue(received[1] is C2Event.ToolCompleted)
        assertEquals("tc1", (received[0] as C2Event.ToolStarted).toolCallId)
        assertEquals(42L, (received[1] as C2Event.ToolCompleted).durationMs)

        client.disconnect()
        }
    }

    @Test
    fun `reaches CONNECTED state on successful open`() = runBlocking {
        withTimeout(10_000) {
        server.enqueue(
            MockResponse()
                .setBody(": connected\n\n")
                .setHeader("Content-Type", "text/event-stream"),
        )

        val client = OkHttpC2EventClient(client = OkHttpClient())
        val baseUrl = server.url("/").toString().trimEnd('/')

        // connectionState is backed by a MutableStateFlow, which only ever
        // replays its LATEST value to a new collector -- it never buffers
        // transient intermediate states. A `first { CONNECTED }`-style wait
        // started even microseconds too late can race past a CONNECTED ->
        // DISCONNECTED transition that already happened (this response has
        // no further data after the one comment line, so OkHttp's
        // EventSource can open and close it very quickly) and then hang
        // forever waiting for a transition that will never repeat.
        //
        // Three earlier attempts to close this race by controlling WHEN the
        // collector starts all reproduced the hang or a flake, because they
        // still relied on `first{}` catching a transient value at the
        // right moment: (1) collector started after connect() -- hung
        // every time; (2) `async { ... }` before connect() -- still hung
        // intermittently, since plain `async` is merely *scheduled*, not
        // guaranteed to actually run before a fast non-suspending
        // `connect()` call returns; (3) `async(Dispatchers.Default)` --
        // reduced the failure rate but did not eliminate it (still flaked
        // under the load of the full multi-module test suite even though
        // it passed reliably in isolation).
        //
        // The fix that actually removes the race: never rely on catching a
        // transient value in flight at all. Collect into an accumulating
        // list from the moment collection starts (guaranteed, via
        // CoroutineStart.UNDISPATCHED + an explicit `subscribed` signal, to
        // be running before connect() is called) and poll that list --
        // every emission is recorded, so no transition can ever be missed
        // regardless of scheduling.
        val subscribed = CompletableDeferred<Unit>()
        val observedStates = mutableListOf<C2EventClient.ConnectionState>()
        val collectJob = launch(start = CoroutineStart.UNDISPATCHED) {
            client.connectionState.collect { state ->
                observedStates += state
                if (!subscribed.isCompleted) subscribed.complete(Unit)
            }
        }
        subscribed.await()

        client.connect(baseUrl, "test-token")

        while (C2EventClient.ConnectionState.CONNECTED !in observedStates &&
            C2EventClient.ConnectionState.ERROR !in observedStates
        ) {
            kotlinx.coroutines.delay(10)
        }
        collectJob.cancel()

        assertTrue(
            "expected CONNECTED among observed states, got: $observedStates",
            C2EventClient.ConnectionState.CONNECTED in observedStates,
        )

        client.disconnect()
        }
    }
}
