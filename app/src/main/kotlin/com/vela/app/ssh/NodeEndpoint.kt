package com.vela.app.ssh

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A typed transport endpoint for reaching an amplifierd node.
 *
 * Serialized as JSON in the SshNodeEntity.endpoints column. Examples:
 * ```json
 * {"type":"direct","url":"http://10.0.0.50:8410"}
 * {"type":"tailscale","url":"http://100.x.x.x:8410"}
 * {"type":"mdns","serviceName":"ken-mac._amplifierd._tcp.local."}
 * ```
 *
 * The [Mdns] endpoint stores only the mDNS service name — no IP address.
 * The resolved IP is always ephemeral, held in memory by MdnsDiscoveryService.
 */
@Serializable
sealed class NodeEndpoint {

    /** Explicit IP/URL — set during SSH bootstrap or manual entry. */
    @Serializable
    @SerialName("direct")
    data class Direct(val url: String) : NodeEndpoint()

    /** Tailscale IP — tried when not on the same LAN. */
    @Serializable
    @SerialName("tailscale")
    data class Tailscale(val url: String) : NodeEndpoint()

    /**
     * mDNS service name — no stored IP, resolved live by MdnsDiscoveryService.
     * Persisted once a service is matched to this node so future app starts know
     * to expect mDNS for this node.
     */
    @Serializable
    @SerialName("mdns")
    data class Mdns(val serviceName: String) : NodeEndpoint()
}
