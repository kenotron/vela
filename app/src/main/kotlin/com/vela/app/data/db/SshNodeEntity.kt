package com.vela.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ssh_nodes")
data class SshNodeEntity(
    @PrimaryKey val id: String,
    val label:    String,
    /** Comma-separated ordered list of IPs/hostnames (SSH nodes). */
    val hosts:    String,
    val port:     Int,
    val username: String,
    val addedAt:  Long,
    /** "ssh" or "amplifierd". Default "ssh" for backward compat. */
    val nodeType: String = "ssh",
    /** amplifierd base URL. Empty for SSH nodes. */
    val url:      String = "",
    /** amplifierd token. Empty for SSH nodes. */
    val token:    String = "",
    /** Tailscale IP URL (e.g. http://100.x.x.x:8410). Empty when not on Tailscale. */
    @ColumnInfo(name = "tailscale_url") val tailscaleUrl: String = "",
    /** BootstrapStatus enum name; default "UNPROVISIONED" for existing rows. */
    val bootstrapStatus: String = "UNPROVISIONED",
    /** Workspace directory used as cwd when amplifierd runs sessions. */
    @ColumnInfo(name = "workspace_dir") val workspaceDir: String = "~",
)
