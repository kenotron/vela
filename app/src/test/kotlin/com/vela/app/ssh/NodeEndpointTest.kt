package com.vela.app.ssh

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * RED → GREEN: verifies NodeEndpoint sealed class structure and JSON serialization.
 *
 * Serialization format from spec:
 *   Direct    → {"type":"direct","url":"http://10.0.0.50:8410"}
 *   Tailscale → {"type":"tailscale","url":"http://100.x.x.x:8410"}
 *   Mdns      → {"type":"mdns","serviceName":"ken-mac._amplifierd._tcp.local."}
 */
class NodeEndpointTest {

    private val json = Json { classDiscriminator = "type" }

    // ── Serialization ────────────────────────────────────────────────────────

    @Test
    fun `Direct serializes with type=direct and url field`() {
        val endpoint: NodeEndpoint = NodeEndpoint.Direct(url = "http://10.0.0.50:8410")
        val encoded = json.encodeToString(endpoint)
        assertThat(encoded).isEqualTo("""{"type":"direct","url":"http://10.0.0.50:8410"}""")
    }

    @Test
    fun `Tailscale serializes with type=tailscale and url field`() {
        val endpoint: NodeEndpoint = NodeEndpoint.Tailscale(url = "http://100.64.0.1:8410")
        val encoded = json.encodeToString(endpoint)
        assertThat(encoded).isEqualTo("""{"type":"tailscale","url":"http://100.64.0.1:8410"}""")
    }

    @Test
    fun `Mdns serializes with type=mdns and serviceName field`() {
        val endpoint: NodeEndpoint = NodeEndpoint.Mdns(serviceName = "ken-mac._amplifierd._tcp.local.")
        val encoded = json.encodeToString(endpoint)
        assertThat(encoded).isEqualTo("""{"type":"mdns","serviceName":"ken-mac._amplifierd._tcp.local."}""")
    }

    // ── Deserialization ──────────────────────────────────────────────────────

    @Test
    fun `Direct deserializes from JSON`() {
        val decoded = json.decodeFromString<NodeEndpoint>(
            """{"type":"direct","url":"http://10.0.0.50:8410"}"""
        )
        assertThat(decoded).isEqualTo(NodeEndpoint.Direct(url = "http://10.0.0.50:8410"))
    }

    @Test
    fun `Tailscale deserializes from JSON`() {
        val decoded = json.decodeFromString<NodeEndpoint>(
            """{"type":"tailscale","url":"http://100.64.0.1:8410"}"""
        )
        assertThat(decoded).isEqualTo(NodeEndpoint.Tailscale(url = "http://100.64.0.1:8410"))
    }

    @Test
    fun `Mdns deserializes from JSON`() {
        val decoded = json.decodeFromString<NodeEndpoint>(
            """{"type":"mdns","serviceName":"ken-mac._amplifierd._tcp.local."}"""
        )
        assertThat(decoded).isEqualTo(NodeEndpoint.Mdns(serviceName = "ken-mac._amplifierd._tcp.local."))
    }

    // ── Class structure ──────────────────────────────────────────────────────

    @Test
    fun `Direct has url property`() {
        val endpoint = NodeEndpoint.Direct(url = "http://10.0.0.50:8410")
        assertThat(endpoint.url).isEqualTo("http://10.0.0.50:8410")
    }

    @Test
    fun `Tailscale has url property`() {
        val endpoint = NodeEndpoint.Tailscale(url = "http://100.64.0.1:8410")
        assertThat(endpoint.url).isEqualTo("http://100.64.0.1:8410")
    }

    @Test
    fun `Mdns has serviceName property`() {
        val endpoint = NodeEndpoint.Mdns(serviceName = "ken-mac._amplifierd._tcp.local.")
        assertThat(endpoint.serviceName).isEqualTo("ken-mac._amplifierd._tcp.local.")
    }

    @Test
    fun `all three subclasses are NodeEndpoint`() {
        val direct: NodeEndpoint = NodeEndpoint.Direct(url = "http://10.0.0.50:8410")
        val tailscale: NodeEndpoint = NodeEndpoint.Tailscale(url = "http://100.x.x.x:8410")
        val mdns: NodeEndpoint = NodeEndpoint.Mdns(serviceName = "ken-mac._amplifierd._tcp.local.")

        assertThat(direct).isInstanceOf(NodeEndpoint::class.java)
        assertThat(tailscale).isInstanceOf(NodeEndpoint::class.java)
        assertThat(mdns).isInstanceOf(NodeEndpoint::class.java)
    }
}
