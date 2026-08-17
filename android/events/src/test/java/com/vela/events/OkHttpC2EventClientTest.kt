package com.vela.events

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
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

    @Test
    fun `parses interleaved data and comment lines, ignoring comments`() = runBlocking {
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

    @Test
    fun `reaches CONNECTED state on successful open`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setBody(": connected\n\n")
                .setHeader("Content-Type", "text/event-stream"),
        )

        val client = OkHttpC2EventClient(client = OkHttpClient())
        val baseUrl = server.url("/").toString().trimEnd('/')
        client.connect(baseUrl, "test-token")

        val state = client.connectionState.first { it == C2EventClient.ConnectionState.CONNECTED }
        assertEquals(C2EventClient.ConnectionState.CONNECTED, state)

        client.disconnect()
    }
}
