package com.vela.app.ui.navigation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * RED → GREEN: verifies [Routes] constants and helper functions produce
 * the exact URL strings expected by the NavHost graph.
 *
 * Pure JVM tests — no Compose or Android needed.
 */
class AppNavigationTest {

    // ── Constants
    @Test fun `HOME constant is home`() {
        assertThat(Routes.HOME).isEqualTo("home")
    }
    @Test fun `CONNECT_NODE constant is connect`() {
        assertThat(Routes.CONNECT_NODE).isEqualTo("connect")
    }
    @Test fun `NODE_DETAIL pattern contains nodeId placeholder`() {
        assertThat(Routes.NODE_DETAIL).contains("{nodeId}")
    }
    @Test fun `SESSION_DETAIL pattern contains sessionId placeholder`() {
        assertThat(Routes.SESSION_DETAIL).contains("{sessionId}")
    }

    // ── Helper functions
    @Test fun `nodeDetail builds correct route`() {
        assertThat(Routes.nodeDetail("node-7")).isEqualTo("node/node-7")
    }
    @Test fun `nodeDetail handles node IDs with special chars`() {
        assertThat(Routes.nodeDetail("my-node_01")).isEqualTo("node/my-node_01")
    }
    @Test fun `sessionList builds correct route`() {
        assertThat(Routes.sessionList("node-7", "proj-abc"))
            .isEqualTo("node/node-7/project/proj-abc")
    }
    @Test fun `sessionDetail builds correct route`() {
        assertThat(Routes.sessionDetail("sess-99")).isEqualTo("session/sess-99")
    }
    @Test fun `coordinator builds correct route`() {
        assertThat(Routes.coordinator("sess-99")).isEqualTo("session/sess-99/coordinator")
    }
    @Test fun `nodeConfig builds correct route`() {
        assertThat(Routes.nodeConfig("node-7")).isEqualTo("node/node-7/config")
    }

    // ── Phase 2 wiring ──────────────────────────────────────────────────────────

    @Test fun `AppNavigation sources HomeScreen (not placeholder)`() {
        val src = java.io.File(
            "src/main/kotlin/com/vela/app/ui/navigation/AppNavigation.kt"
        ).readText()
        assertThat(src).contains("HomeScreen(navController)")
        assertThat(src).doesNotContain("HomeScreenPlaceholder")
    }

    @Test fun `AppNavigation sources NodeDetailScreen (not placeholder)`() {
        val src = java.io.File(
            "src/main/kotlin/com/vela/app/ui/navigation/AppNavigation.kt"
        ).readText()
        assertThat(src).contains("NodeDetailScreen(navController)")
        assertThat(src).doesNotContain("NodeDetailPlaceholder")
    }

    // ── Phase 3 wiring ────────────────────────────────────────────────────────

    @Test fun `AppNavigation sources SessionListScreen (not placeholder)`() {
        val src = java.io.File(
            "src/main/kotlin/com/vela/app/ui/navigation/AppNavigation.kt"
        ).readText()
        assertThat(src).contains("SessionListScreen(navController)")
        assertThat(src).doesNotContain("SessionListPlaceholder")
    }
}
