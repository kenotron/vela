package com.vela.app.amplifierd

import com.vela.app.ssh.NodeType
import com.vela.app.ssh.SshNodeRegistry
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository that vends [AmplifierdClient] instances for live nodes.
 *
 * One client is created per call to [clientFor] — no caching — so each call
 * gets a fresh client. Callers that want to reuse a client should hold onto
 * the result themselves.
 *
 * Returns null if the node does not exist, is not of type [NodeType.AMPLIFIERD],
 * or has a blank URL.
 */
@Singleton
class AmplifierdRepository @Inject constructor(
    private val registry: SshNodeRegistry,
) {
    /**
     * Returns a client for the given [nodeId], or null if the node is not
     * an AMPLIFIERD-type node or has no URL configured.
     */
    fun clientFor(nodeId: String): AmplifierdClient? {
        val node = registry.cache.find { it.id == nodeId }
        if (node?.type != NodeType.AMPLIFIERD || node.url.isBlank()) return null
        return AmplifierdClient(node.url, node.token)
    }
}
