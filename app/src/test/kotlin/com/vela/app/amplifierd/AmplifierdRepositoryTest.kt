package com.vela.app.amplifierd

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Source-file structural tests for the refactored [AmplifierdRepository].
 *
 * Written in TDD RED phase — before the new implementation exists — following the
 * same structural-test pattern used by [EndpointResolverTest].
 *
 * These tests verify:
 *  - The class receives [EndpointResolver] as a constructor parameter.
 *  - [clientForNode] and [streamClientForNode] are `suspend` functions.
 *  - [clientForNode] delegates to [EndpointResolver.resolve].
 *  - Legacy methods (findReachableUrl, candidateUrls, clientFor) are removed.
 */
class AmplifierdRepositoryTest {

    private val src: String by lazy {
        java.io.File(
            "src/main/kotlin/com/vela/app/amplifierd/AmplifierdRepository.kt"
        ).readText()
    }

    // ── package / class declarations ──────────────────────────────────────────

    @Test fun `file declares correct package`() {
        assertThat(src).contains("package com.vela.app.amplifierd")
    }

    @Test fun `class is annotated Singleton`() {
        assertThat(src).contains("@Singleton")
    }

    @Test fun `class is annotated Inject constructor`() {
        assertThat(src).contains("@Inject constructor")
    }

    // ── constructor parameters ────────────────────────────────────────────────

    @Test fun `constructor takes SshNodeRegistry`() {
        assertThat(src).contains("registry: SshNodeRegistry")
    }

    @Test fun `constructor takes EndpointResolver`() {
        assertThat(src).contains("resolver: EndpointResolver")
    }

    // ── public API ────────────────────────────────────────────────────────────

    @Test fun `clientForNode is a suspend fun returning AmplifierdClient nullable`() {
        assertThat(src).contains("suspend fun clientForNode(node: SshNode?): AmplifierdClient?")
    }

    @Test fun `streamClientForNode is a suspend fun returning AmplifierdStreamClient nullable`() {
        assertThat(src).contains("suspend fun streamClientForNode(node: SshNode?): AmplifierdStreamClient?")
    }

    @Test fun `clientForNode delegates to resolver resolve`() {
        assertThat(src).contains("resolver.resolve(node)")
    }

    @Test fun `streamClientForNode delegates to clientForNode`() {
        assertThat(src).contains("clientForNode(node)")
    }

    // ── removed API ───────────────────────────────────────────────────────────

    @Test fun `findReachableUrl is removed`() {
        assertThat(src).doesNotContain("fun findReachableUrl")
    }

    @Test fun `candidateUrls is removed`() {
        assertThat(src).doesNotContain("fun candidateUrls")
    }

    @Test fun `clientFor nodeId overload is removed`() {
        assertThat(src).doesNotContain("fun clientFor(")
    }

    // ── logging ───────────────────────────────────────────────────────────────

    @Test fun `TAG companion constant is defined as AmplifierdRepository`() {
        assertThat(src).contains("""const val TAG = "AmplifierdRepository"""")
    }
}
