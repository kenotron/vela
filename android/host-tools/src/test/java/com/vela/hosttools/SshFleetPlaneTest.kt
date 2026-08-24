package com.vela.hosttools

import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [SshFleetPlane] against a [MockWebServer] standing in for
 * `vela-agentd`'s `POST /fleet/dispatch` (F0.2). No real SSH or real
 * vela-agentd process involved -- this proves the HTTP client mapping only.
 */
class SshFleetPlaneTest {

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
    fun `dispatch succeeds when server returns reachable true`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    JSONObject()
                        .put("job_id", "job-abc")
                        .put("reachable", true)
                        .put("machine_id", "localhost")
                        .put("detail", "launched on user@localhost")
                        .toString(),
                ),
        )

        val plane = SshFleetPlane(baseUrl = server.url("/").toString().trimEnd('/'), apiKey = "test-key")
        val jobSpec = JSONObject()
            .put("job_id", "job-abc")
            .put("runtime", "shell")
            .toString()

        val outcome = plane.dispatch(jobSpec)

        assertTrue(outcome.reachable)
        assertEquals("localhost", outcome.machineId)
        assertEquals("launched on user@localhost", outcome.detail)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/fleet/dispatch", recorded.path)
        assertEquals("Bearer test-key", recorded.getHeader("Authorization"))
        val sentBody = JSONObject(recorded.body.readUtf8())
        assertEquals("job-abc", sentBody.getString("job_id"))
        assertEquals("shell", sentBody.getString("runtime"))
    }

    @Test
    fun `dispatch reports unreachable on non-2xx error response`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setBody(
                    JSONObject()
                        .put("error", JSONObject().put("message", "UNREACHABLE: SSH connect timed out"))
                        .toString(),
                ),
        )

        val plane = SshFleetPlane(baseUrl = server.url("/").toString().trimEnd('/'), apiKey = "test-key")
        val jobSpec = JSONObject().put("job_id", "job-err").put("runtime", "shell").toString()

        val outcome = plane.dispatch(jobSpec)

        assertFalse(outcome.reachable)
        assertTrue(outcome.detail.contains("UNREACHABLE"))
    }

    @Test
    fun `dispatch reports unreachable on client timeout (no response from server)`() {
        server.enqueue(
            MockResponse()
                .setSocketPolicy(SocketPolicy.NO_RESPONSE),
        )

        val client = okhttp3.OkHttpClient.Builder()
            .callTimeout(300, TimeUnit.MILLISECONDS)
            .connectTimeout(300, TimeUnit.MILLISECONDS)
            .readTimeout(300, TimeUnit.MILLISECONDS)
            .build()
        val plane = SshFleetPlane(baseUrl = server.url("/").toString().trimEnd('/'), apiKey = "test-key", client = client)
        val jobSpec = JSONObject().put("job_id", "job-timeout").put("runtime", "shell").toString()

        val outcome = plane.dispatch(jobSpec)

        assertFalse(outcome.reachable)
        assertTrue(outcome.detail.contains("UNREACHABLE"))
    }

    @Test
    fun `dispatch defaults runtime and argv when jobSpec only has title and summary`() {
        // DispatchToFleetTool.run() today only sends {title, summary, targetHint?} --
        // SshFleetPlane must still produce a launchable request.
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(JSONObject().put("job_id", "generated").put("reachable", true).toString()),
        )

        val plane = SshFleetPlane(baseUrl = server.url("/").toString().trimEnd('/'), apiKey = "test-key")
        val jobSpec = JSONObject().put("title", "t").put("summary", "s").toString()

        val outcome = plane.dispatch(jobSpec)

        assertTrue(outcome.reachable)
        val recorded = server.takeRequest()
        val sentBody = JSONObject(recorded.body.readUtf8())
        assertEquals("shell", sentBody.getString("runtime"))
        assertTrue(sentBody.getJSONArray("argv").length() > 0)
    }
}
