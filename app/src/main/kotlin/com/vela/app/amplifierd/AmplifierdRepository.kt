package com.vela.app.amplifierd

import android.util.Log
import com.vela.app.ssh.NodeType
import com.vela.app.ssh.SshNode
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository that vends [AmplifierdClient] and [AmplifierdStreamClient] instances.
 *
 * All methods that return clients now resolve the best reachable endpoint first
 * (via [EndpointResolver]) and run a /health probe before returning.
 * Returns null if the node is unreachable or not of type [NodeType.AMPLIFIERD].
 */
@Singleton
class AmplifierdRepository @Inject constructor(
    private val resolver: EndpointResolver,
) {
    /**
     * Resolves the best reachable endpoint for [node] and returns an [AmplifierdClient]
     * already pointed at the winning URL. Performs a /health probe on each candidate.
     *
     * This is a suspend function — call it from a coroutine (all existing callers already do).
     */
    suspend fun clientForNode(node: SshNode?): AmplifierdClient? {
        if (node == null) {
            Log.w(TAG, "clientForNode: node is null")
            return null
        }
        if (node.type != NodeType.AMPLIFIERD) {
            Log.w(TAG, "clientForNode: '${node.label}' is ${node.type}, not AMPLIFIERD")
            return null
        }
        return resolver.resolve(node)
    }

    /**
     * Resolves the best reachable endpoint and returns an [AmplifierdStreamClient]
     * pointed at the same URL as the resolved [AmplifierdClient].
     * Both clients share the same base URL — avoids stale-URL bugs in streaming.
     */
    suspend fun streamClientForNode(node: SshNode?): AmplifierdStreamClient? {
        val client = clientForNode(node) ?: return null
        return AmplifierdStreamClient(client.baseUrl, node!!.token)
    }

    companion object {
        private const val TAG = "AmplifierdRepository"
    }
}
