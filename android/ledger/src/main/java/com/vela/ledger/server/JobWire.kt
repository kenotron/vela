package com.vela.ledger.server

import com.vela.ledger.JobRecord
import com.vela.ledger.JobStatus
import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON <-> [JobRecord] mapping for the C3 `Job` wire model (design doc
 * `docs/designs/2026-08-24-vela-server-ledger.md` §4.2).
 *
 * `version` (design doc §4.3, `server_authoritative_version` on the wire) is read
 * defensively: `services/ledger/` has NOT yet been extended to emit it (that is a
 * services-ledger change, out of this lane's ownership per the goal file), so we
 * tolerate its absence (`null`) rather than requiring it. When absent, staleness
 * detection falls back to `updated_at` comparison (coarser, per design doc §5.3) --
 * this fallback is intentional, not a bug.
 */
object JobWire {

    fun encodeCreateRequest(record: JobRecord): String = JSONObject().apply {
        put("job_id", record.jobId)
        put("created_at", record.createdAt)
        put("updated_at", record.updatedAt)
        put(
            "origin",
            JSONObject().apply {
                put("session_id", record.origin.sessionId)
                put("turn_id", record.origin.turnId)
                put("tool_call_id", record.origin.toolCallId)
            },
        )
        put("spec", safeJsonOrString(record.spec))
        put("status", record.status.wireValue())
        put(
            "attention",
            JSONObject().apply {
                put("required", record.attention.required)
                put("reason", record.attention.reason)
                put("options", JSONArray(record.attention.options))
                put("deadline", record.attention.deadline)
            },
        )
        put("progress", encodeProgress(record.progress))
        put("result", record.result?.let { safeJsonOrString(it) })
        put(
            "cost",
            JSONObject().apply {
                put("usd", record.cost.usd)
                put("tokens", record.cost.tokens)
            },
        )
    }.toString()

    fun decodeJob(json: String): WireJob = decodeJob(JSONObject(json))

    fun decodeJob(obj: JSONObject): WireJob {
        val origin = obj.getJSONObject("origin")
        val attention = obj.getJSONObject("attention")
        val cost = obj.optJSONObject("cost")
        val record = JobRecord(
            jobId = obj.getString("job_id"),
            createdAt = obj.getLong("created_at"),
            updatedAt = obj.getLong("updated_at"),
            origin = JobRecord.Origin(
                sessionId = origin.getString("session_id"),
                turnId = origin.getString("turn_id"),
                toolCallId = origin.getString("tool_call_id"),
            ),
            spec = obj.get("spec").let { if (it is String) it else it.toString() },
            status = JobStatus.fromWireValue(obj.getString("status")),
            attention = JobRecord.Attention(
                required = attention.getBoolean("required"),
                reason = attention.optString("reason").ifBlankNull(),
                options = attention.optJSONArray("options")?.let { decodeStringArray(it) } ?: emptyList(),
                deadline = if (attention.isNull("deadline")) null else attention.optLong("deadline"),
            ),
            progress = obj.optJSONArray("progress")?.let { decodeProgress(it) } ?: emptyList(),
            result = if (obj.isNull("result") || !obj.has("result")) {
                null
            } else {
                obj.get("result").let { if (it is String) it else it.toString() }
            },
            cost = JobRecord.Cost(
                usd = cost?.let { if (it.isNull("usd")) null else it.optDouble("usd") },
                tokens = cost?.let { if (it.isNull("tokens")) null else it.optLong("tokens") },
            ),
        )
        val version = if (obj.has("version") && !obj.isNull("version")) obj.getLong("version") else null
        return WireJob(record, version)
    }

    fun decodeJobList(json: String): List<WireJob> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { decodeJob(arr.getJSONObject(it)) }
    }

    fun encodePatchRequest(
        status: JobStatus? = null,
        progressEntry: JobRecord.ProgressEntry? = null,
        attention: JobRecord.Attention? = null,
        result: String? = null,
        cost: JobRecord.Cost? = null,
    ): String = JSONObject().apply {
        if (status != null) put("status", status.wireValue())
        if (progressEntry != null) {
            put(
                "progress",
                JSONObject().apply {
                    put("ts", progressEntry.ts)
                    put("message", progressEntry.message)
                    if (progressEntry.percent != null) put("percent", progressEntry.percent)
                    put("source", progressEntry.source)
                },
            )
        }
        if (attention != null) {
            put(
                "attention",
                JSONObject().apply {
                    put("required", attention.required)
                    put("reason", attention.reason)
                    put("options", JSONArray(attention.options))
                    put("deadline", attention.deadline)
                },
            )
        }
        if (result != null) put("result", safeJsonOrString(result))
        if (cost != null) {
            put(
                "cost",
                JSONObject().apply {
                    put("usd", cost.usd)
                    put("tokens", cost.tokens)
                },
            )
        }
    }.toString()

    fun encodeDecisionRequest(newStatus: JobStatus, decidedAt: Long): String = JSONObject().apply {
        put("new_status", newStatus.wireValue())
        put("decided_at", decidedAt)
    }.toString()

    private fun encodeProgress(entries: List<JobRecord.ProgressEntry>): JSONArray = JSONArray().apply {
        entries.forEach { e ->
            put(
                JSONObject().apply {
                    put("ts", e.ts)
                    put("message", e.message)
                    if (e.percent != null) put("percent", e.percent)
                    put("source", e.source)
                },
            )
        }
    }

    private fun decodeProgress(arr: JSONArray): List<JobRecord.ProgressEntry> =
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            JobRecord.ProgressEntry(
                ts = o.getLong("ts"),
                message = o.getString("message"),
                percent = if (o.has("percent") && !o.isNull("percent")) o.getInt("percent") else null,
                source = o.getString("source"),
            )
        }

    private fun decodeStringArray(arr: JSONArray): List<String> = (0 until arr.length()).map { arr.getString(it) }

    /** `spec`/`result` are opaque JSON objects on the wire but strings in [JobRecord]; re-parse if valid JSON. */
    private fun safeJsonOrString(s: String): Any = try {
        JSONObject(s)
    } catch (_: Exception) {
        try {
            JSONArray(s)
        } catch (_: Exception) {
            s
        }
    }

    private fun String?.ifBlankNull(): String? = if (this.isNullOrBlank()) null else this
}

/** A decoded server [JobRecord] plus its optional [version] (§4.3 -- absent until the server adds it). */
data class WireJob(val record: JobRecord, val version: Long?)
