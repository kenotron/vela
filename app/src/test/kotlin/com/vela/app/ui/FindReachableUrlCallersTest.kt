package com.vela.app.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Structural tests verifying that all callers of the removed `findReachableUrl` method
 * have been migrated to use `clientForNode` (via EndpointResolver) instead.
 *
 * Written in TDD RED phase — before the fix is applied — so these tests fail initially
 * (both files still contain `findReachableUrl`) and pass once the fix lands.
 *
 * Pattern mirrors [com.vela.app.amplifierd.AmplifierdRepositoryTest].
 */
class FindReachableUrlCallersTest {

    private val nodeDetailVmSrc: String by lazy {
        java.io.File(
            "src/main/kotlin/com/vela/app/ui/nodedetail/NodeDetailViewModel.kt"
        ).readText()
    }

    private val homeVmSrc: String by lazy {
        java.io.File(
            "src/main/kotlin/com/vela/app/ui/home/HomeViewModel.kt"
        ).readText()
    }

    // ── NodeDetailViewModel ───────────────────────────────────────────────────────────────

    @Test fun `NodeDetailViewModel does not call findReachableUrl`() {
        assertThat(nodeDetailVmSrc).doesNotContain("findReachableUrl")
    }

    @Test fun `NodeDetailViewModel refreshConnectivity uses clientForNode`() {
        assertThat(nodeDetailVmSrc).contains("amplifierd.clientForNode(n) != null")
    }

    // ── HomeViewModel ─────────────────────────────────────────────────────────────────────

    @Test fun `HomeViewModel does not call findReachableUrl`() {
        assertThat(homeVmSrc).doesNotContain("findReachableUrl")
    }

    /**
     * Connectivity checking has been moved to ConnectivityPoller.
     * HomeViewModel now delegates to the poller rather than calling clientForNode directly.
     */
    @Test fun `HomeViewModel delegates connectivity to ConnectivityPoller`() {
        assertThat(homeVmSrc).contains("ConnectivityPoller")
        assertThat(homeVmSrc).contains("poller.nodeConnectivity")
    }

    @Test fun `HomeViewModel does not call clientForNode directly`() {
        assertThat(homeVmSrc).doesNotContain("clientForNode")
    }
}
