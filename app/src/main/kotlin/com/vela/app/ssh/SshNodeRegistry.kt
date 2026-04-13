package com.vela.app.ssh

    import com.vela.app.data.db.SshNodeDao
    import com.vela.app.data.db.SshNodeEntity
    import kotlinx.coroutines.flow.Flow
    import kotlinx.coroutines.flow.map
    import javax.inject.Inject
    import javax.inject.Singleton

    @Singleton
    class SshNodeRegistry @Inject constructor(private val dao: SshNodeDao) {

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

        private fun SshNodeEntity.toDomain() = SshNode(
            id       = id,
            label    = label,
            hosts    = hosts.split(",").map { it.trim() }.filter { it.isNotBlank() },
            port     = port,
            username = username,
            addedAt  = addedAt,
        )
        private fun SshNode.toEntity() = SshNodeEntity(
            id       = id,
            label    = label,
            hosts    = hosts.joinToString(","),
            port     = port,
            username = username,
            addedAt  = addedAt,
        )
    }
    