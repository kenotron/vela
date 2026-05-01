package com.vela.app.amplifierd

import android.util.Log
import com.vela.app.ssh.NodeType
import com.vela.app.ssh.SshNode
import com.vela.app.ssh.SshNodeRegistry
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository that vends [AmplifierdClient] and [AmplifierdStreamClient] instances.
 *
 * Returns null if the node does not exist, is not of type [NodeType.AMPLIFIERD],
 * or has a blank URL.
 */
@Singleton
class AmplifierdRepository @Inject constructor(
    private val registry: SshNodeRegistry,
) {
    /**
     * Returns a client for the given [nodeId] by searching [SshNodeRegistry.cache].
     *
     * Prefer [clientForNode] when the caller already holds the [SshNode] domain object.
     */
    fun clientFor(nodeId: String): AmplifierdClient? {
        val node = registry.cache.find { it.id == nodeId }
        return clientForNode(node)
    }

    /**
     * Returns a client built directly from a known [SshNode], bypassing the
     * in-memory cache. Use this when the caller already has the node object.
     */
    fun clientForNode(node: SshNode?): AmplifierdClient? {
        if (node == null) {
            Log.w(TAG, "clientForNode: node is null")
            return null
        }
        if (node.type != NodeType.AMPLIFIERD) {
            Log.w(TAG, "clientForNode: node '${node.label}' is type ${node.type}, not AMPLIFIERD")
            return null
        }
        if (node.url.isBlank()) {
            Log.w(TAG, "clientForNode: node '${node.label}' has blank URL")
            return null
        }
        Log.d(TAG, "clientForNode: creating client for '${node.label}' -> ${node.url}")
        return AmplifierdClient(node.url, node.token)
    }

    /**
     * Returns an SSE streaming client for [node], used for live session execution.
     * Returns null for the same reasons as [clientForNode].
     */
    fun streamClientForNode(node: SshNode?): AmplifierdStreamClient? {
        if (node == null || node.type != NodeType.AMPLIFIERD || node.url.isBlank()) return null
        return AmplifierdStreamClient(node.url, node.token)
    }

    companion object {
        private const val TAG = "AmplifierdRepository"
    }
}
