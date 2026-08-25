package com.vela.app

import com.vela.core.domain.HostToolRegistry
import com.vela.hosttools.AmplifierToolLoopClient
import com.vela.voice.handoff.SlowTierEvent
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [AmplifierToolLoopSlowTierGateway] (issue #61). Uses a real
 * [AmplifierToolLoopClient] against a [MockWebServer] (matching the SSE
 * chat-completions contract [AmplifierToolLoopClient] speaks) rather than a fake client,
 * so these tests exercise the real bridge end-to-end.
 */
class AmplifierToolLoopSlowTierGatewayTest {

    private lateinit var server: MockWebServer
    private lateinit var registry: HostToolRegistry

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        registry = emptyRegistry()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun emptyRegistry(): HostToolRegistry = object : HostToolRegistry {
        override fun all() = emptyList<com.vela.core.domain.HostTool>()
        override fun find(name: String): com.vela.core.domain.HostTool? = null
    }

    private fun clientFor(baseUrl: String) = AmplifierToolLoopClient(
        baseUrl = baseUrl,
        apiKey = "test-key",
        registry = registry,
        clientSessionId = "test-session",
    )

    @Test
    fun `dispatch emits Completed on a normal assistant turn`() = runTest {
        val sse = "data: {\"choices\":[{\"delta\":{\"content\":\"Booked for 3pm.\"},\"finish_reason\":\"stop\"}]}\n\n" +
            "data: [DONE]\n\n"
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sse),
        )

        val gateway = AmplifierToolLoopSlowTierGateway(clientFor(server.url("/").toString().trimEnd('/')))

        val events = mutableListOf<SlowTierEvent>()
        gateway.dispatch("schedule a meeting").collect { events.add(it) }

        assertEquals(listOf(SlowTierEvent.Completed("Booked for 3pm.")), events)
    }

    @Test
    fun `dispatch emits Failed when the server request fails`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))

        val gateway = AmplifierToolLoopSlowTierGateway(clientFor(server.url("/").toString().trimEnd('/')))

        val events = mutableListOf<SlowTierEvent>()
        gateway.dispatch("schedule a meeting").collect { events.add(it) }

        assertEquals(1, events.size)
        assertTrue(events.single() is SlowTierEvent.Failed)
        assertTrue((events.single() as SlowTierEvent.Failed).message.contains("500"))
    }
}
