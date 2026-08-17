package com.vela.ledger

/**
 * Public, nested-shape API mirroring the C3 job resource (design doc §4.2) exactly.
 * This is the type callers of [SqliteLedgerRepository] work with; [JobEntity] is the
 * flattened Room storage representation, mapped to/from this type internally.
 */
data class JobRecord(
    val jobId: String,
    val createdAt: Long,
    val updatedAt: Long,
    val origin: Origin,
    val spec: String,
    val status: JobStatus,
    val attention: Attention,
    val progress: List<ProgressEntry>,
    val result: String?,
    val cost: Cost,
) {
    data class Origin(
        val sessionId: String,
        val turnId: String,
        val toolCallId: String,
    )

    data class Attention(
        val required: Boolean,
        val reason: String?,
        val options: List<String>,
        val deadline: Long?,
    )

    data class ProgressEntry(
        val ts: Long,
        val message: String,
        val percent: Int?,
        val source: String,
    )

    data class Cost(
        val usd: Double?,
        val tokens: Long?,
    )
}

enum class JobStatus {
    ACCEPTED,
    RUNNING,
    NEEDS_ATTENTION,
    BLOCKED,
    DONE,
    FAILED,
    CANCELLED;

    /** The C3 wire representation, e.g. "needs_attention". */
    fun wireValue(): String = name.lowercase()

    companion object {
        fun fromWireValue(value: String): JobStatus =
            entries.firstOrNull { it.wireValue() == value }
                ?: throw IllegalArgumentException("Unknown job status: $value")
    }
}

/** A decision recorded against a job (item 4): updates status, clears attention.required. */
data class Decision(
    val newStatus: JobStatus,
    val decidedAt: Long,
)
