package com.vela.ledger

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity modeling the C3 `job` resource exactly (design doc §4.2):
 *
 * ```
 * job {
 *   job_id            uuid
 *   created_at        ts
 *   updated_at        ts
 *   origin            { session_id, turn_id, tool_call_id }
 *   spec              { … }
 *   status            accepted | running | needs_attention | blocked | done | failed | cancelled
 *   attention         { required: bool, reason: str, options: [...], deadline?: ts }
 *   progress          [ { ts, message, percent?, source } ]
 *   result            { … } | null
 *   cost              { usd?, tokens? }
 * }
 * ```
 *
 * Nested objects (`origin`, `attention.options`, `progress`, `result`) are flattened /
 * serialized to columns at this storage layer; [SqliteLedgerRepository] maps this shape
 * to/from the richer nested [JobRecord] public API.
 */
@Entity(tableName = "jobs")
data class JobEntity(
    @PrimaryKey @ColumnInfo(name = "job_id") val jobId: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,

    // origin { session_id, turn_id, tool_call_id }
    @ColumnInfo(name = "origin_session_id") val originSessionId: String,
    @ColumnInfo(name = "origin_turn_id") val originTurnId: String,
    @ColumnInfo(name = "origin_tool_call_id") val originToolCallId: String,

    // spec { … } - serialized
    @ColumnInfo(name = "spec_json") val specJson: String,

    // status: accepted | running | needs_attention | blocked | done | failed | cancelled
    @ColumnInfo(name = "status") val status: String,

    // attention { required, reason, options, deadline? }
    @ColumnInfo(name = "attention_required") val attentionRequired: Boolean,
    @ColumnInfo(name = "attention_reason") val attentionReason: String?,
    @ColumnInfo(name = "attention_options_json") val attentionOptionsJson: String?,
    @ColumnInfo(name = "attention_deadline") val attentionDeadline: Long?,

    // progress [ { ts, message, percent?, source } ] - append-only, serialized list
    @ColumnInfo(name = "progress_json") val progressJson: String,

    // result { … } | null - serialized
    @ColumnInfo(name = "result_json") val resultJson: String?,

    // cost { usd?, tokens? }
    @ColumnInfo(name = "cost_usd") val costUsd: Double?,
    @ColumnInfo(name = "cost_tokens") val costTokens: Long?,

    /**
     * Forward-looking field for the future sync/mirror mode (Stage 2, lane 2.1's
     * server-side ledger). Not part of the C3 job resource shape itself, but reserved
     * for row-level conflict resolution: increments on every local write, and will be
     * compared against the server's authoritative version once sync exists.
     *
     * No sync logic reads or writes this field's semantics yet — this lane only
     * increments it locally so the column and the invariant ("locally incremented on
     * every write") are already in place when sync is built.
     */
    @ColumnInfo(name = "server_authoritative_version", defaultValue = "0")
    val serverAuthoritativeVersion: Long = 0,
)
