package com.vela.ledger.server

import com.vela.ledger.JobRecord
import com.vela.ledger.JobStatus
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.resume

private val JSON = "application/json; charset=utf-8".toMediaType()

/** Thrown when the server rejects a decision because the job is already terminal (§5.3, HTTP 409). */
class JobAlreadyTerminalException(val jobId: String) : Exception("Job $jobId is already in a terminal state")

/** Thrown for any other non-2xx HTTP response. */
class LedgerHttpException(val code: Int, message: String) : IOException(message)

/**
 * REST client for `services/ledger/`'s C3 API (design doc §4.1). Deliberately thin --
 * one method per endpoint, JSON encode/decode delegated to [JobWire]. No retry logic
 * here; retry/backoff and mirror-mode fallback are [ServerLedgerRepository]'s job (§5.5).
 */
class LedgerApiClient(
    private val baseUrl: String,
    private val apiKey: String? = null,
    private val client: OkHttpClient = OkHttpClient(),
) {
    private fun url(path: String) = baseUrl.trimEnd('/') + path

    private fun requestBuilder(path: String): Request.Builder {
        val b = Request.Builder().url(url(path))
        if (!apiKey.isNullOrBlank()) b.addHeader("Authorization", "Bearer $apiKey")
        return b
    }

    suspend fun createJob(record: JobRecord): WireJob {
        val body = JobWire.encodeCreateRequest(record).toRequestBody(JSON)
        val req = requestBuilder("/ledger/jobs").post(body).build()
        return JobWire.decodeJob(execute(req))
    }

    suspend fun getJob(jobId: String): WireJob {
        val req = requestBuilder("/ledger/jobs/$jobId").get().build()
        return JobWire.decodeJob(execute(req))
    }

    /** `since` is an epoch-ms `created_at` watermark (§5.4 full reconciliation). Null = unbounded. */
    suspend fun listJobs(since: Long? = null, limit: Int? = null): List<WireJob> {
        val params = buildList {
            if (since != null) add("since=$since")
            if (limit != null) add("limit=$limit")
        }
        val path = "/ledger/jobs" + if (params.isNotEmpty()) "?" + params.joinToString("&") else ""
        val req = requestBuilder(path).get().build()
        return JobWire.decodeJobList(execute(req))
    }

    suspend fun attentionJobs(): List<WireJob> {
        val req = requestBuilder("/ledger/attention").get().build()
        return JobWire.decodeJobList(execute(req))
    }

    suspend fun patchJob(
        jobId: String,
        status: JobStatus? = null,
        progressEntry: JobRecord.ProgressEntry? = null,
        attention: JobRecord.Attention? = null,
        result: String? = null,
        cost: JobRecord.Cost? = null,
    ): WireJob {
        val body = JobWire.encodePatchRequest(status, progressEntry, attention, result, cost).toRequestBody(JSON)
        val req = requestBuilder("/ledger/jobs/$jobId").patch(body).build()
        return JobWire.decodeJob(execute(req))
    }

    /**
     * Records a decision. Throws [JobAlreadyTerminalException] on HTTP 409 (§5.3 --
     * the server-wins terminal-state guard) so [ServerLedgerRepository] can surface it
     * as "already resolved" rather than a generic failure.
     */
    suspend fun decideJob(jobId: String, newStatus: JobStatus, decidedAt: Long): WireJob {
        val body = JobWire.encodeDecisionRequest(newStatus, decidedAt).toRequestBody(JSON)
        val req = requestBuilder("/ledger/jobs/$jobId/decision").post(body).build()
        return JobWire.decodeJob(execute(req, jobId))
    }

    /** Liveness probe backing mirror-mode fallback detection (§5.5). */
    suspend fun healthz(): Boolean = try {
        val req = requestBuilder("/healthz").get().build()
        execute(req)
        true
    } catch (_: Exception) {
        false
    }

    private suspend fun execute(request: Request, jobIdForConflict: String? = null): String =
        suspendCancellableCoroutine { cont ->
            val call = client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        cont.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        response.use {
                            val bodyString = it.body?.string().orEmpty()
                            when {
                                it.isSuccessful -> cont.resume(bodyString)
                                it.code == 409 && jobIdForConflict != null ->
                                    cont.resumeWithException(JobAlreadyTerminalException(jobIdForConflict))
                                else -> cont.resumeWithException(
                                    LedgerHttpException(it.code, "HTTP ${it.code}: $bodyString"),
                                )
                            }
                        }
                    }
                },
            )
        }
}
