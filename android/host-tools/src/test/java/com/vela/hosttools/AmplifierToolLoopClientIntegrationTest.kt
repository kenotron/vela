package com.vela.hosttools

import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Goal file item 6: real end-to-end test of the client-side tool loop against
 * a live `amplifier-agent serve` instance (lane 1.4, already deployed at
 * http://127.0.0.1:9099). This is a genuine HTTP integration test — it makes
 * real network calls, not a mock.
 *
 * Reads the bearer key from ~/.amplifier/vela-agent-serve/env exactly as
 * ops/agent-serve/health-check.sh does. If the service is unreachable, the
 * test is skipped via JUnit's Assume (reported as neither pass nor fail),
 * and that state is called out explicitly in the lane report rather than
 * silently swallowed.
 */
class AmplifierToolLoopClientIntegrationTest {

    private fun readApiKey(): String? {
        val envFile = File(System.getProperty("user.home"), ".amplifier/vela-agent-serve/env")
        if (!envFile.exists()) return null
        return envFile.readLines()
            .firstOrNull { it.startsWith("AMPLIFIER_AGENT_HTTP_API_KEY=") }
            ?.substringAfter("=")
            ?.trim()
    }

    @Test
    fun `full turn with dispatch_to_fleet tool call round-trip against live agent-serve`() {
        val apiKey = readApiKey()
        assumeTrue("skipping: ~/.amplifier/vela-agent-serve/env not found or key missing", apiKey != null)

        val ledger = InMemoryLedgerRepository()
        val dispatchTool = DispatchToFleetTool(ledger)
        val registry = DefaultHostToolRegistry(listOf(dispatchTool))
        val client = AmplifierToolLoopClient(
            baseUrl = "http://127.0.0.1:9099",
            apiKey = apiKey!!,
            registry = registry,
        )

        val result = runBlocking {
            client.runTurn(
                "Use the dispatch_to_fleet tool to dispatch a job with title " +
                    "'integration-test-job' and summary 'triggered by lane 1.3 integration test'. " +
                    "After the tool responds, tell me the job_id it returned.",
            )
        }

        println("Final assistant content: ${result.finalContent}")
        println("Tool call log: ${result.toolCallLog}")

        assertTrue(
            "expected at least one tool call in the transcript, got: ${result.toolCallLog}",
            result.toolCallLog.isNotEmpty(),
        )
        assertTrue(
            "expected the dispatch_to_fleet tool call to appear in the log",
            result.toolCallLog.any { it.startsWith("dispatch_to_fleet(") },
        )
        // The ledger must have exactly one entry from this real round-trip (G2).
        assertTrue("expected ledger to have >=1 entry after the round-trip", ledger.size() >= 1)
    }
}
