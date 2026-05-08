package com.vela.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ssh_nodes")
data class SshNodeEntity(
    @PrimaryKey val id: String,
    val label: String,
    /** Comma-separated ordered list of IPs/hostnames (SSH nodes). */
    val hosts: String,
    val port: Int,
    val username: String,
    val addedAt: Long,
    /** "ssh" or "amplifierd". Default "ssh" for backward compat. */
    val nodeType: String = "ssh",
    /** amplifierd base URL. Deprecated — use endpoints column instead. */
    val url: String = "",
    /** amplifierd token. Empty for SSH nodes. */
    val token: String = "",
    /** Tailscale IP URL. Deprecated — use endpoints column instead. */
    @ColumnInfo(name = "tailscale_url") val tailscaleUrl: String = "",
    /** BootstrapStatus enum name; default "UNPROVISIONED" for existing rows. */
    val bootstrapStatus: String = "UNPROVISIONED",
    /** Workspace directory used as cwd when amplifierd runs sessions. */
    @ColumnInfo(name = "workspace_dir") val workspaceDir: String = "~",
    // ── v18 columns ──
    /** Stable hardware UUID from /health machine_id. Empty for pre-v18 rows. */
    @ColumnInfo(name = "machine_id") val machineId: String = "",
    /** JSON array of NodeEndpoint objects. Format: [{"type":"direct","url":"..."},{"type":"tailscale","url":"..."}].
     *  Backfilled from url + tailscale_url during MIGRATION_17_18.
     *  Empty array "[]" for SSH-only nodes. */
    val endpoints: String = "[]",
)
