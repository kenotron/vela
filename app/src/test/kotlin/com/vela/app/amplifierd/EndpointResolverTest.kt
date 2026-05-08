package com.vela.app.amplifierd

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Source-file structural tests for [EndpointResolver].
 *
 * Written in TDD RED phase — before the file exists — following the same
 * structural-test pattern used by [AmplifierdClientApiMethodsTest].
 *
 * These verify the exact shape of the file (singleton annotation, public API
 * surface, priority ordering comment) without requiring compilation of the
 * full Android module.
 */
class EndpointResolverTest {

    private val src: String by lazy {
        java.io.File(
            "src/main/kotlin/com/vela/app/amplifierd/EndpointResolver.kt"
        ).readText()
    }

    // ── package / class declarations ────────────────────────────────────────

    @Test fun `file declares correct package`() {
        assertThat(src).contains("package com.vela.app.amplifierd")
    }

    @Test fun `class is annotated Singleton`() {
        assertThat(src).contains("@Singleton")
    }

    @Test fun `class is annotated Inject constructor`() {
        assertThat(src).contains("@Inject constructor")
    }

    @Test fun `class takes MdnsDiscoveryService as constructor parameter`() {
        assertThat(src).contains("mdnsDiscovery: MdnsDiscoveryService")
    }

    // ── public API ───────────────────────────────────────────────────────────

    @Test fun `resolve is a suspend function returning AmplifierdClient nullable`() {
        assertThat(src).contains("suspend fun resolve(node: SshNode): AmplifierdClient?")
    }

    @Test fun `toUrl is a public function returning String nullable`() {
        assertThat(src).contains("fun toUrl(endpoint: NodeEndpoint): String?")
    }

    // ── priority ordering ────────────────────────────────────────────────────

    @Test fun `Mdns has priority 0 (fastest - LAN)`() {
        assertThat(src).contains("is NodeEndpoint.Mdns")
        assertThat(src).contains("-> 0")
    }

    @Test fun `Tailscale has priority 1`() {
        assertThat(src).contains("is NodeEndpoint.Tailscale")
        assertThat(src).contains("-> 1")
    }

    @Test fun `Direct has priority 2`() {
        assertThat(src).contains("is NodeEndpoint.Direct")
        assertThat(src).contains("-> 2")
    }

    // ── toUrl routing ────────────────────────────────────────────────────────

    @Test fun `toUrl returns url for Direct endpoint`() {
        assertThat(src).contains("is NodeEndpoint.Direct    -> endpoint.url")
    }

    @Test fun `toUrl returns url for Tailscale endpoint`() {
        assertThat(src).contains("is NodeEndpoint.Tailscale -> endpoint.url")
    }

    @Test fun `toUrl delegates to mdnsDiscovery for Mdns endpoint`() {
        assertThat(src).contains("is NodeEndpoint.Mdns      -> mdnsDiscovery.resolvedUrl(endpoint.serviceName)")
    }

    // ── fallback / legacy behaviour ──────────────────────────────────────────

    @Test fun `resolve falls back to node url when endpoints list is empty`() {
        assertThat(src).contains("node.endpoints.isEmpty()")
        assertThat(src).contains("node.url")
    }

    // ── logging ──────────────────────────────────────────────────────────────

    @Test fun `TAG companion constant is defined as EndpointResolver`() {
        assertThat(src).contains("""const val TAG = "EndpointResolver"""")
    }
}
