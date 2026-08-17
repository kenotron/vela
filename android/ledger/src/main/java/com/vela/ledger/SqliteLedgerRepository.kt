package com.vela.ledger

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Local SQLite (Room-backed) implementation of the richer C3 job resource model.
 *
 * NOTE: this is a NEW, separate API in the `com.vela.ledger` package. It does NOT
 * implement `com.vela.core.domain.LedgerRepository` (owned by lane 1.1) — that
 * interface's `LedgerEntry` shape is a simpler flat model that predates the full C3
 * job resource schema. Reconciling the two (e.g. making the simpler interface a view
 * over this richer store) is left as a residual for a future lane.
 */
class SqliteLedgerRepository(private val dao: JobDao) {

    suspend fun createJob(job: JobRecord) {
        dao.insert(job.toEntity())
    }

    suspend fun updateStatus(jobId: String, status: JobStatus, updatedAt: Long) {
        val existing = dao.getById(jobId) ?: throw NoSuchElementException("No job: $jobId")
        dao.update(
            existing.copy(
                status = status.wireValue(),
                updatedAt = updatedAt,
                serverAuthoritativeVersion = existing.serverAuthoritativeVersion + 1,
            )
        )
    }

    /** Append-only progress write: read-modify-write the progress list (item 4/5 support). */
    suspend fun appendProgress(jobId: String, entry: JobRecord.ProgressEntry, updatedAt: Long) {
        val existing = dao.getById(jobId) ?: throw NoSuchElementException("No job: $jobId")
        val progress = decodeProgress(existing.progressJson).toMutableList()
        progress += entry
        dao.update(
            existing.copy(
                progressJson = encodeProgress(progress),
                updatedAt = updatedAt,
                serverAuthoritativeVersion = existing.serverAuthoritativeVersion + 1,
            )
        )
    }

    /**
     * Records a human decision (item 4): updates status per the decision AND clears
     * attention.required. Bumps updated_at and the forward-looking
     * server_authoritative_version.
     */
    suspend fun recordDecision(jobId: String, decision: Decision) {
        val existing = dao.getById(jobId) ?: throw NoSuchElementException("No job: $jobId")
        dao.update(
            existing.copy(
                status = decision.newStatus.wireValue(),
                attentionRequired = false,
                updatedAt = decision.decidedAt,
                serverAuthoritativeVersion = existing.serverAuthoritativeVersion + 1,
            )
        )
    }

    suspend fun getJob(jobId: String): JobRecord? = dao.getById(jobId)?.toRecord()

    fun observeAll(): Flow<List<JobRecord>> = dao.observeAll().map { list -> list.map { it.toRecord() } }

    /** The card deck's backing query (item 3): only jobs with attention.required == true. */
    fun observeAttentionQueue(): Flow<List<JobRecord>> =
        dao.observeAttentionRequired().map { list -> list.map { it.toRecord() } }
}

// --- Mapping between the flattened JobEntity and the nested public JobRecord ---

internal fun JobRecord.toEntity(): JobEntity = JobEntity(
    jobId = jobId,
    createdAt = createdAt,
    updatedAt = updatedAt,
    originSessionId = origin.sessionId,
    originTurnId = origin.turnId,
    originToolCallId = origin.toolCallId,
    specJson = spec,
    status = status.wireValue(),
    attentionRequired = attention.required,
    attentionReason = attention.reason,
    attentionOptionsJson = encodeStringList(attention.options),
    attentionDeadline = attention.deadline,
    progressJson = encodeProgress(progress),
    resultJson = result,
    costUsd = cost.usd,
    costTokens = cost.tokens,
)

internal fun JobEntity.toRecord(): JobRecord = JobRecord(
    jobId = jobId,
    createdAt = createdAt,
    updatedAt = updatedAt,
    origin = JobRecord.Origin(originSessionId, originTurnId, originToolCallId),
    spec = specJson,
    status = JobStatus.fromWireValue(status),
    attention = JobRecord.Attention(
        required = attentionRequired,
        reason = attentionReason,
        options = attentionOptionsJson?.let { decodeStringList(it) } ?: emptyList(),
        deadline = attentionDeadline,
    ),
    progress = decodeProgress(progressJson),
    result = resultJson,
    cost = JobRecord.Cost(costUsd, costTokens),
)

// --- Minimal manual JSON encode/decode (no serialization library dependency present
// in this project; the value shapes here are simple enough to hand-roll safely) ---

private fun jsonEscape(s: String): String = buildString {
    for (c in s) {
        when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(c)
        }
    }
}

internal fun encodeStringList(items: List<String>): String =
    items.joinToString(prefix = "[", postfix = "]") { "\"${jsonEscape(it)}\"" }

internal fun decodeStringList(json: String): List<String> {
    val inner = json.trim().removeSurrounding("[", "]").trim()
    if (inner.isEmpty()) return emptyList()
    return splitTopLevel(inner).map { unquote(it) }
}

internal fun encodeProgress(entries: List<JobRecord.ProgressEntry>): String =
    entries.joinToString(prefix = "[", postfix = "]") { e ->
        val percentField = if (e.percent != null) "\"percent\":${e.percent}," else ""
        "{\"ts\":${e.ts},\"message\":\"${jsonEscape(e.message)}\",$percentField\"source\":\"${jsonEscape(e.source)}\"}"
    }

internal fun decodeProgress(json: String): List<JobRecord.ProgressEntry> {
    val inner = json.trim().removeSurrounding("[", "]").trim()
    if (inner.isEmpty()) return emptyList()
    return splitTopLevel(inner).map { obj ->
        val fields = parseFlatObject(obj)
        JobRecord.ProgressEntry(
            ts = fields.getValue("ts").toLong(),
            message = unquote(fields.getValue("message")),
            percent = fields["percent"]?.toIntOrNull(),
            source = unquote(fields.getValue("source")),
        )
    }
}

/** Splits a comma-separated top-level list of JSON values, respecting nested {}/[] and quotes. */
private fun splitTopLevel(s: String): List<String> {
    val result = mutableListOf<String>()
    var depth = 0
    var inString = false
    var escape = false
    val current = StringBuilder()
    for (c in s) {
        when {
            escape -> {
                current.append(c)
                escape = false
            }
            inString && c == '\\' -> {
                current.append(c)
                escape = true
            }
            c == '"' -> {
                inString = !inString
                current.append(c)
            }
            !inString && (c == '{' || c == '[') -> {
                depth++
                current.append(c)
            }
            !inString && (c == '}' || c == ']') -> {
                depth--
                current.append(c)
            }
            !inString && depth == 0 && c == ',' -> {
                result += current.toString().trim()
                current.clear()
            }
            else -> current.append(c)
        }
    }
    if (current.isNotBlank()) result += current.toString().trim()
    return result
}

/** Parses a flat (non-nested) JSON object string like {"a":1,"b":"x"} into a field map. */
private fun parseFlatObject(obj: String): Map<String, String> {
    val inner = obj.trim().removeSurrounding("{", "}")
    val fields = mutableMapOf<String, String>()
    for (pair in splitTopLevel(inner)) {
        val idx = pair.indexOf(':')
        if (idx < 0) continue
        val key = unquote(pair.substring(0, idx).trim())
        val value = pair.substring(idx + 1).trim()
        fields[key] = value
    }
    return fields
}

private fun unquote(s: String): String {
    val t = s.trim()
    if (t.length >= 2 && t.first() == '"' && t.last() == '"') {
        val body = t.substring(1, t.length - 1)
        return buildString {
            var i = 0
            while (i < body.length) {
                val c = body[i]
                if (c == '\\' && i + 1 < body.length) {
                    when (body[i + 1]) {
                        '"' -> append('"')
                        '\\' -> append('\\')
                        'n' -> append('\n')
                        'r' -> append('\r')
                        't' -> append('\t')
                        else -> append(body[i + 1])
                    }
                    i += 2
                } else {
                    append(c)
                    i += 1
                }
            }
        }
    }
    return t
}
