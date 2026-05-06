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

    /**
     * Returns all candidate amplifierd URLs for [node], in priority order:
     * 1. Explicit tailscaleUrl (works cross-network when TS is active)
     * 2. Stored primary url (LAN IP from SSH bootstrap)
     * 3. URLs derived from hosts list using the stored url's port
     *
     * Deduplicated — same URL appears only once.
     */
    fun candidateUrls(node: SshNode): List<String> {
        val port = node.url.substringAfterLast(":").substringBefore("/").toIntOrNull() ?: 8410
        return buildList {
            if (node.tailscaleUrl.isNotBlank()) add(node.tailscaleUrl)
            if (node.url.isNotBlank()) add(node.url)
            node.hosts.forEach { host ->
                val derived = "http://$host:$port"
                if (derived !in this) add(derived)
            }
        }
    }

    /**
     * Tries each candidate URL in priority order and returns the first one that
     * responds to GET /health with 200. Returns null if all URLs are unreachable.
     */
    suspend fun findReachableUrl(node: SshNode): String? {
        if (node.type != NodeType.AMPLIFIERD || node.token.isBlank()) return null
        for (url in candidateUrls(node)) {
            val reachable = try {
                AmplifierdClient(url, node.token).health()
            } catch (_: Exception) { false }
            if (reachable) return url
        }
        return null
    }

    companion object {
        private const val TAG = "AmplifierdRepository"
    }
}
