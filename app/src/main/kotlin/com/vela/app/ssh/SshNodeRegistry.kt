package com.vela.app.ssh

import com.vela.app.data.db.SshNodeDao
import com.vela.app.data.db.SshNodeEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
open class SshNodeRegistry @Inject constructor(private val dao: SshNodeDao) {

    fun allFlow(): Flow<List<SshNode>> =
        dao.getAllNodes().map { it.map { e -> e.toDomain() } }

    @Volatile var cache: List<SshNode> = emptyList()
    fun updateCache(nodes: List<SshNode>) { cache = nodes }

    fun findByLabel(label: String): SshNode? =
        cache.firstOrNull { it.label.equals(label, ignoreCase = true) }
            ?: cache.firstOrNull { it.primaryHost.equals(label, ignoreCase = true) }

    fun allSync(): List<SshNode> = cache

    suspend fun addNode(node: SshNode)    = dao.insert(node.toEntity())
    suspend fun updateNode(node: SshNode) = dao.insert(node.toEntity())
    suspend fun removeNode(id: String)    = dao.delete(id)

    // ── Bootstrap-lifecycle writers ───────────────────────────────────────────

    /** Promote an SSH node to an amplifierd node in a single transaction. */
    open suspend fun promoteToAmplifierd(nodeId: String, url: String, token: String) {
        dao.promoteToAmplifierd(nodeId, "amplifierd", url, token, BootstrapStatus.RUNNING.name)
    }

    /** Flag a running amplifierd node as stale (newer version available). */
    suspend fun markStale(nodeId: String) {
        dao.updateBootstrapStatus(nodeId, BootstrapStatus.STALE.name)
    }

    /** Update only the bootstrap lifecycle column. */
    open suspend fun updateBootstrapStatus(nodeId: String, status: BootstrapStatus) {
        dao.updateBootstrapStatus(nodeId, status.name)
    }

    // ── Entity ↔ Domain mapping ───────────────────────────────────────────────

    private fun SshNodeEntity.toDomain() = SshNode(
        id       = id,
        label    = label,
        hosts    = hosts.split(",").map { it.trim() }.filter { it.isNotBlank() },
        port     = port,
        username = username,
        addedAt  = addedAt,
        type     = if (nodeType == "amplifierd") NodeType.AMPLIFIERD else NodeType.SSH,
        url      = url,
        token    = token,
        bootstrapStatus = parseBootstrapStatus(bootstrapStatus),
        workspaceDir    = workspaceDir,
    )

    private fun SshNode.toEntity() = SshNodeEntity(
        id       = id,
        label    = label,
        hosts    = hosts.joinToString(","),
        port     = port,
        username = username,
        addedAt  = addedAt,
        nodeType = if (type == NodeType.AMPLIFIERD) "amplifierd" else "ssh",
        url      = url,
        token    = token,
        bootstrapStatus = bootstrapStatus.name,
        workspaceDir    = workspaceDir,
    )

    /** Tolerant parse — unknown / corrupt strings fall back to UNPROVISIONED. */
    private fun parseBootstrapStatus(raw: String): BootstrapStatus =
        runCatching { BootstrapStatus.valueOf(raw) }.getOrDefault(BootstrapStatus.UNPROVISIONED)
}
