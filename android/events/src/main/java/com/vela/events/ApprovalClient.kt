package com.vela.events

import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject

/**
 * Client for lane 3.1's F2 approval-gate decision route:
 * `POST /v1/approvals/{approvalId}/decision`. Fire-and-forget by design —
 * the server times out pending approvals ~30s server-side and auto-declines,
 * so no client-side retry logic is required beyond surfacing basic errors.
 */
interface ApprovalClient {
    suspend fun decide(baseUrl: String, bearerToken: String, approvalId: String, accept: Boolean): Result<Unit>
}

class OkHttpApprovalClient(
    private val client: OkHttpClient = OkHttpClient(),
) : ApprovalClient {

    private val jsonMediaType = "application/json".toMediaType()

    override suspend fun decide(
        baseUrl: String,
        bearerToken: String,
        approvalId: String,
        accept: Boolean,
    ): Result<Unit> {
        val body = JSONObject().put("action", if (accept) "accept" else "decline").toString()
        val request = Request.Builder()
            .url("$baseUrl/v1/approvals/$approvalId/decision")
            .header("Authorization", "Bearer $bearerToken")
            .post(body.toRequestBody(jsonMediaType))
            .build()

        return runCatching {
            suspendCancellableCoroutine { continuation ->
                val call = client.newCall(request)
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(
                    object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            continuation.resumeWithException(e)
                        }

                        override fun onResponse(call: Call, response: Response) {
                            response.use {
                                if (it.isSuccessful) {
                                    continuation.resume(Unit)
                                } else {
                                    continuation.resumeWithException(
                                        IOException("approval decision failed: HTTP ${it.code}"),
                                    )
                                }
                            }
                        }
                    },
                )
            }
        }
    }
}
