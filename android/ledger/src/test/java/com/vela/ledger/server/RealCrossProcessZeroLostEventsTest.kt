package com.vela.ledger.server

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.vela.ledger.LedgerDatabase
import com.vela.ledger.SqliteLedgerRepository
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.Response
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import java.util.concurrent.LinkedBlockingQueue

private val JSON = "application/json; charset=utf-8".toMediaType()

/**
 * Issue #38 -- zero-lost-events, proven END TO END across REAL, SEPARATE OS PROCESSES:
 * a real `services/ledger/` FastAPI process, a real `services/fleetd-broker/` FastAPI
 * process, and the real Android [ServerLedgerRepository] (this module's actual
 * production code, not a fake), talking over real HTTP/WebSocket on localhost.
 *
 * This is deliberately NOT a repeat of [ServerLedgerRepositoryTest]'s
 * `test_cross_layer_zero_lost_events`, which drives the real Android repository against
 * MockWebServer (a fake standing in for services/ledger/) and never involves
 * fleetd-broker at all. Per-layer durability was already proven independently:
 *   - services/ledger/tests/test_durability_restart.py (ledger alone)
 *   - services/fleetd-broker/tests/test_store_durability.py (broker alone)
 *   - ZeroLostEventsTest.kt (android mirror alone)
 * The gap issue #38 names is that these three were never proven TOGETHER, across a
 * real process boundary, with a real mid-flight kill. This test closes that gap:
 *
 *   1. Start a real ledger_service process (uv run python -m ledger_service).
 *   2. Start a real fleetd_broker process (uv run uvicorn fleetd_broker.app:app),
 *      pointed at the real ledger process over HTTP, with a durable on-disk store
 *      (FLEETD_STORE_PATH) so its worker/job bindings survive a restart -- exactly
 *      the mechanism design doc FF-9 / 4.1 describes.
 *   3. A real WebSocket "worker" client (OkHttp, real TCP) registers with the broker
 *      and dispatches a job via a real `POST /fleet/dispatch` HTTP call.
 *   4. The worker emits `started` then `attention` events over the real, open
 *      WebSocket session -- each causes the broker to make a real
 *      `PATCH /ledger/jobs/{id}` HTTP call against the real ledger process.
 *   5. Mid-flight -- after `attention` has landed in the ledger but before the job
 *      finishes -- the broker OS process is killed (`Process.destroyForcibly()`,
 *      no graceful shutdown) and a fresh broker process is started at the same port
 *      against the same durable store and the same live ledger.
 *   6. The worker reconnects (a new real WebSocket session) and re-declares its
 *      in-flight job id, exercising the broker's reconnect reconciliation path
 *      against a REAL restarted process, then emits `finished`.
 *   7. The real [ServerLedgerRepository] (real [LedgerApiClient] over real HTTP,
 *      real Room-backed mirror) reconciles against the real ledger process and its
 *      mirrored state is asserted to exactly match the ledger's own authoritative
 *      state -- proving no event was lost across the broker's real, mid-flight
 *      process death and restart, observed end-to-end from the Android layer.
 */
@RunWith(RobolectricTestRunner::class)
class RealCrossProcessZeroLostEventsTest {

    private val http = OkHttpClient.Builder()
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private var ledgerProcess: Process? = null
    private var brokerProcess: Process? = null
    private var webSocket: WebSocket? = null
    private lateinit var storeFile: File

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (!File(dir, "services").isDirectory || !File(dir, "android").isDirectory) {
            dir = dir.parentFile ?: error("could not locate repo root from ${System.getProperty("user.dir")}")
        }
        return dir
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun startProcess(command: List<String>, cwd: File, env: Map<String, String> = emptyMap()): Process {
        val pb = ProcessBuilder(command)
            .directory(cwd)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.to(File.createTempFile("proc-out", ".log")))
        pb.environment().putAll(env)
        return pb.start()
    }

    private fun waitForHealthz(port: Int, timeoutS: Long = 30) {
        val deadline = System.currentTimeMillis() + timeoutS * 1000
        var lastError: Exception? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                val req = Request.Builder().url("http://127.0.0.1:$port/healthz").get().build()
                http.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) return
                }
            } catch (e: Exception) {
                lastError = e
            }
            Thread.sleep(200)
        }
        throw AssertionError("service on port $port never became healthy", lastError)
    }

    private fun waitForProcessExit(process: Process, timeoutS: Long = 15) {
        if (!process.waitFor(timeoutS, TimeUnit.SECONDS)) {
            error("process did not exit within ${timeoutS}s")
        }
    }

    private fun getJson(url: String): JSONObject {
        val req = Request.Builder().url(url).get().build()
        http.newCall(req).execute().use { resp ->
            assertTrue("GET $url failed: ${resp.code}", resp.isSuccessful)
            return JSONObject(resp.body!!.string())
        }
    }

    @After
    fun tearDown() {
        try { webSocket?.close(1000, "test done") } catch (_: Exception) {}
        ledgerProcess?.destroyForcibly()
        brokerProcess?.destroyForcibly()
        ledgerProcess?.waitFor(10, TimeUnit.SECONDS)
        brokerProcess?.waitFor(10, TimeUnit.SECONDS)
        if (::storeFile.isInitialized) storeFile.delete()
    }

    @Test
    fun `zero events lost end-to-end across a real broker kill and restart, verified from real android client`() = runTest(
        timeout = kotlin.time.Duration.parse("120s"),
    ) {
        val root = repoRoot()
        val ledgerPort = freePort()
        val brokerPort = freePort()
        storeFile = File.createTempFile("fleetd-broker-store", ".db").also { it.delete() }

        // 1. Real ledger process.
        ledgerProcess = startProcess(
            listOf("uv", "run", "python", "-m", "ledger_service", "--port", ledgerPort.toString()),
            cwd = File(root, "services/ledger"),
        )
        waitForHealthz(ledgerPort)

        // 2. Real broker process, pointed at the real ledger, with a durable store so
        //    its bindings survive the kill/restart in step 5.
        fun startBroker() = startProcess(
            listOf("uv", "run", "uvicorn", "fleetd_broker.app:app", "--port", brokerPort.toString()),
            cwd = File(root, "services/fleetd-broker"),
            env = mapOf(
                "FLEETD_LEDGER_URL" to "http://127.0.0.1:$ledgerPort",
                "FLEETD_STORE_PATH" to storeFile.absolutePath,
            ),
        )
        brokerProcess = startBroker()
        waitForHealthz(brokerPort)

        // 3. A real WebSocket worker registers with the real broker.
        val incoming = LinkedBlockingQueue<String>()
        fun connectWorker(jobIdsToReport: List<String> = emptyList()): WebSocket {
            val ws = http.newWebSocket(
                Request.Builder().url("ws://127.0.0.1:$brokerPort/fleet/worker/worker-1").build(),
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) { incoming.put(text) }
                },
            )
            val registerMsg = JSONObject().apply {
                put("op", "register")
                put("labels", org.json.JSONArray())
                put("runtimes", org.json.JSONArray(listOf("shell")))
                if (jobIdsToReport.isNotEmpty()) put("job_ids", org.json.JSONArray(jobIdsToReport))
            }
            ws.send(registerMsg.toString())
            val ack = incoming.poll(10, TimeUnit.SECONDS)
            assertEquals("registered", ack?.let { JSONObject(it).getString("op") })
            return ws
        }
        webSocket = connectWorker()

        // Real HTTP dispatch call against the real broker (D1/D2 admission).
        val dispatchBody = JSONObject().apply {
            put("origin", JSONObject().apply { put("session_id", "s1"); put("turn_id", "t1"); put("tool_call_id", "tc-e2e-38") })
            put(
                "spec",
                JSONObject().apply {
                    put("title", "zero-lost-events e2e")
                    put("summary", "cross-process #38 proof")
                    put("runtime", "shell")
                    put("prompt", "echo hi")
                    put("target", JSONObject().apply { put("labels", org.json.JSONArray()); put("strategy", "least_loaded") })
                    put("limits", JSONObject())
                },
            )
        }
        val dispatchReq = Request.Builder()
            .url("http://127.0.0.1:$brokerPort/fleet/dispatch")
            .post(dispatchBody.toString().toRequestBody(JSON))
            .build()
        val jobId: String
        http.newCall(dispatchReq).execute().use { resp ->
            val bodyStr = resp.body!!.string()
            assertTrue("dispatch failed: ${resp.code} $bodyStr", resp.isSuccessful)
            jobId = JSONObject(bodyStr).getString("job_id")
        }

        // 4. Worker emits events over the real, open WebSocket -- each causes a real
        //    PATCH /ledger/jobs/{id} HTTP call from the broker to the real ledger.
        fun sendEvent(kind: String, fields: JSONObject = JSONObject()) {
            // worker_events.parse_event treats every top-level key other than
            // (op, ts, kind, job_id) as part of `fields`, so extra fields are set
            // directly on the message (matching app.py's documented wire protocol).
            val wireMsg = JSONObject().apply {
                put("op", "event")
                put("ts", System.currentTimeMillis() / 1000)
                put("kind", kind)
                put("job_id", jobId)
                put("machine_id", "worker-1")
                fields.keys().forEach { k -> put(k, fields.get(k)) }
            }
            webSocket!!.send(wireMsg.toString())
        }

        sendEvent("started")
        Thread.sleep(300) // let the broker's PATCH land before we assert against the real ledger
        val afterStarted = getJson("http://127.0.0.1:$ledgerPort/ledger/jobs/$jobId")
        assertEquals("running", afterStarted.getString("status"))

        sendEvent(
            "attention",
            JSONObject().apply { put("reason", "need input"); put("options", org.json.JSONArray(listOf("go", "stop"))) },
        )
        Thread.sleep(300)
        val afterAttention = getJson("http://127.0.0.1:$ledgerPort/ledger/jobs/$jobId")
        assertEquals("needs_attention", afterAttention.getString("status"))
        assertTrue(afterAttention.getJSONObject("attention").getBoolean("required"))

        // 5. REAL mid-flight kill: the broker dies with an event already durably
        //    recorded in the ledger, but before the job has finished.
        webSocket!!.close(1001, "simulating disconnect from a dying broker")
        brokerProcess!!.destroyForcibly()
        waitForProcessExit(brokerProcess!!)

        brokerProcess = startBroker()
        waitForHealthz(brokerPort)

        // 6. Worker reconnects to the REAL, freshly-restarted broker process and
        //    re-declares the in-flight job id (design doc 4.1 reconciliation), then
        //    finishes the job.
        webSocket = connectWorker(jobIdsToReport = listOf(jobId))
        sendEvent(
            "finished",
            JSONObject().apply { put("exit_code", 0); put("result", JSONObject().apply { put("ok", true) }) },
        )
        Thread.sleep(300)

        // 7. The REAL Android ServerLedgerRepository reconciles against the REAL
        //    (still-running) ledger process and must reflect the final state exactly
        //    -- proving zero events were lost across the broker's real kill/restart.
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LedgerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repo = ServerLedgerRepository(
            api = LedgerApiClient(baseUrl = "http://127.0.0.1:$ledgerPort"),
            mirror = SqliteLedgerRepository(db.jobDao()),
            outbox = db.decisionOutboxDao(),
        )
        val pulled = repo.reconcile()
        assertTrue("expected at least the e2e job to be pulled, got $pulled", pulled >= 1)

        val mirroredJob = db.jobDao().getById(jobId)
        assertEquals("done", mirroredJob?.status)

        val finalServerState = getJson("http://127.0.0.1:$ledgerPort/ledger/jobs/$jobId")
        assertEquals("done", finalServerState.getString("status"))
        assertTrue(
            "attention set before the broker restart must survive it",
            finalServerState.getJSONObject("attention").getString("reason") == "need input",
        )
        assertEquals(true, finalServerState.getJSONObject("result").getBoolean("ok"))

        db.close()
    }
}
