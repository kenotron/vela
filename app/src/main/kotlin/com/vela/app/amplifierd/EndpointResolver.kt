package com.vela.app.amplifierd

import android.util.Log
import com.vela.app.ssh.MdnsDiscoveryService
import com.vela.app.ssh.NodeEndpoint
import com.vela.app.ssh.SshNode
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves the best reachable [AmplifierdClient] for a node by probing each
 * [NodeEndpoint] in priority order: Mdns (LAN fastest) → Tailscale → Direct.
 *
 * Each candidate URL is probed with a real GET /health before being accepted.
 * Returns null if all endpoints fail or the node has no endpoints.
 *
 * Callers receive a client already pointed at the winning URL — no raw URLs escape.
 */
@Singleton
class EndpointResolver @Inject constructor(
    private val mdnsDiscovery: MdnsDiscoveryService,
) {
    /**
     * Returns the first [AmplifierdClient] whose /health responds with 200,
     * probing in priority order. Returns null if no endpoint is reachable.
     */
    suspend fun resolve(node: SshNode): AmplifierdClient? {
        if (node.endpoints.isEmpty()) {
            // Legacy node (no endpoints yet — pre-migration or pre-mDNS-discovery).
            // Fall back to the stored url field so existing nodes still work.
            val fallbackUrl = node.url.takeIf { it.isNotBlank() } ?: return null
            return try {
                val client = AmplifierdClient(fallbackUrl, node.token)
                if (client.health()) client else null
            } catch (_: Exception) { null }
        }
        for (endpoint in prioritized(node.endpoints)) {
            val url = toUrl(endpoint) ?: continue      // mDNS not resolved yet → skip
            val client = try {
                AmplifierdClient(url, node.token)
            } catch (_: Exception) { continue }
            val reachable = try { client.health() } catch (_: Exception) { false }
            if (reachable) {
                Log.d(TAG, "resolve '${node.label}': ${endpoint::class.simpleName} → $url")
                return client
            }
        }
        Log.w(TAG, "resolve '${node.label}': all endpoints unreachable")
        return null
    }

    /** Converts an endpoint to its HTTP URL, or null if not yet resolvable (mDNS pending). */
    fun toUrl(endpoint: NodeEndpoint): String? = when (endpoint) {
        is NodeEndpoint.Direct    -> endpoint.url
        is NodeEndpoint.Tailscale -> endpoint.url
        is NodeEndpoint.Mdns      -> mdnsDiscovery.resolvedUrl(endpoint.serviceName)
    }

    private fun prioritized(endpoints: List<NodeEndpoint>): List<NodeEndpoint> =
        endpoints.sortedBy {
            when (it) {
                is NodeEndpoint.Mdns      -> 0   // LAN is fastest
                is NodeEndpoint.Tailscale -> 1
                is NodeEndpoint.Direct    -> 2
            }
        }

    companion object {
        private const val TAG = "EndpointResolver"
    }
}
