package com.vela.app.ssh

import com.vela.app.data.db.SshNodeDao
import com.vela.app.data.db.SshNodeEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** kotlinx.serialization Json instance for NodeEndpoint list encoding/decoding. */
// matches the JSON format: {"type":"direct",...}
// forward-compatible: unknown subtypes become empty list
private val endpointJson = Json {
    classDiscriminator = "type"
    ignoreUnknownKeys = true
}

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

    /** Update editable connection fields (label, host, port, username, workspaceDir) in-place. */
    suspend fun updateConnection(node: SshNode) {
        dao.updateConnection(
            id           = node.id,
            label        = node.label,
            hosts        = node.hosts.joinToString(","),
            port         = node.port,
            username     = node.username,
            workspaceDir = node.workspaceDir,
        )
    }

    // ── Bootstrap-lifecycle writers ────────────────────────────────────────────

    /**
     * Promote an SSH node to an amplifierd node. Builds initial NodeEndpoint list from
     * url/tailscaleUrl and persists it. machineId defaults to "" — Phase 2 NodeBootstrapper
     * will pass the real value once it reads machine_id from the /health response.
     */
    open suspend fun promoteToAmplifierd(
        nodeId: String,
        url: String,
        tailscaleUrl: String = "",
        token: String,
        machineId: String = "",
    ) {
        val initialEndpoints = buildList<NodeEndpoint> {
            if (tailscaleUrl.isNotBlank()) add(NodeEndpoint.Tailscale(tailscaleUrl))
            if (url.isNotBlank()) add(NodeEndpoint.Direct(url))
        }
        val endpointsJson = endpointJson.encodeToString(initialEndpoints)
        dao.promoteToAmplifierd(
            id           = nodeId,
            type         = "amplifierd",
            url          = url,
            tailscaleUrl = tailscaleUrl,
            token        = token,
            status       = BootstrapStatus.RUNNING.name,
            machineId    = machineId,
            endpoints    = endpointsJson,
        )
    }

    /** Flag a running amplifierd node as stale (newer version available). */
    suspend fun markStale(nodeId: String) = dao.updateBootstrapStatus(nodeId, BootstrapStatus.STALE.name)

    /** Update only the bootstrap lifecycle column. */
    open suspend fun updateBootstrapStatus(nodeId: String, status: BootstrapStatus) =
        dao.updateBootstrapStatus(nodeId, status.name)

    // ── Entity ↔ Domain mapping ────────────────────────────────────────────────

    private fun SshNodeEntity.toDomain() = SshNode(
        id              = id,
        label           = label,
        hosts           = hosts.split(",").map { it.trim() }.filter { it.isNotBlank() },
        port            = port,
        username        = username,
        addedAt         = addedAt,
        type            = if (nodeType == "amplifierd") NodeType.AMPLIFIERD else NodeType.SSH,
        url             = url,
        tailscaleUrl    = tailscaleUrl,
        token           = token,
        machineId       = machineId,
        endpoints       = parseEndpoints(endpoints),
        bootstrapStatus = parseBootstrapStatus(bootstrapStatus),
        workspaceDir    = workspaceDir,
    )

    private fun SshNode.toEntity() = SshNodeEntity(
        id              = id,
        label           = label,
        hosts           = hosts.joinToString(","),
        port            = port,
        username        = username,
        addedAt         = addedAt,
        nodeType        = if (type == NodeType.AMPLIFIERD) "amplifierd" else "ssh",
        url             = url,
        tailscaleUrl    = tailscaleUrl,
        token           = token,
        machineId       = machineId,
        endpoints       = endpointJson.encodeToString(endpoints),
        bootstrapStatus = bootstrapStatus.name,
        workspaceDir    = workspaceDir,
    )

    private fun parseEndpoints(json: String): List<NodeEndpoint> =
        runCatching { endpointJson.decodeFromString<List<NodeEndpoint>>(json) }.getOrDefault(emptyList())

    /** Tolerant parse — unknown / corrupt strings fall back to UNPROVISIONED. */
    private fun parseBootstrapStatus(raw: String): BootstrapStatus =
        runCatching { BootstrapStatus.valueOf(raw) }.getOrDefault(BootstrapStatus.UNPROVISIONED)
}
