package com.vela.app.ui.home

import androidx.compose.ui.graphics.toArgb
import com.google.common.truth.Truth.assertThat
import com.vela.app.ssh.BootstrapStatus
import com.vela.app.ssh.NodeConnectivity
import com.vela.app.ssh.NodeType
import com.vela.app.ssh.SshNode
import com.vela.app.ui.theme.VelaColors
import org.junit.Test

/**
 * RED → GREEN: verifies the pure color-mapping and status-derivation logic
 * for node tile card backgrounds, dot colors, and chip labels.
 *
 * All functions under test are top-level `internal` functions in NodeTile.kt.
 * JVM-only — no Compose rendering needed.
 * androidx.compose.ui.graphics.Color is a Kotlin value class; no Android calls.
 */
class NodeTileColorTest {

    // ── cardBackgroundFor ──────────────────────────────────────────────────

    @Test fun `cardBackground for Busy is amber-tinted`() {
        assertThat(cardBackgroundFor(NodeTileStatus.Busy).toArgb())
            .isEqualTo(0xFF151209.toInt())
    }

    @Test fun `cardBackground for Ready is teal-tinted`() {
        assertThat(cardBackgroundFor(NodeTileStatus.Ready).toArgb())
            .isEqualTo(0xFF0C1C1A.toInt())
    }

    @Test fun `cardBackground for SettingUp is warm indigo`() {
        assertThat(cardBackgroundFor(NodeTileStatus.SettingUp).toArgb())
            .isEqualTo(0xFF131527.toInt())
    }

    @Test fun `cardBackground for Checking is neutral dark`() {
        assertThat(cardBackgroundFor(NodeTileStatus.Checking).toArgb())
            .isEqualTo(0xFF0F1019.toInt())
    }

    @Test fun `cardBackground for Offline is near-black`() {
        assertThat(cardBackgroundFor(NodeTileStatus.Offline).toArgb())
            .isEqualTo(0xFF0C0C10.toInt())
    }

    // ── dotColorFor ────────────────────────────────────────────────────────

    @Test fun `dotColor for Busy is VelaColors Running`() {
        assertThat(dotColorFor(NodeTileStatus.Busy).toArgb())
            .isEqualTo(VelaColors.Running.toArgb())
    }

    @Test fun `dotColor for Ready is VelaColors Accent`() {
        assertThat(dotColorFor(NodeTileStatus.Ready).toArgb())
            .isEqualTo(VelaColors.Accent.toArgb())
    }

    // ── chipContainerColorFor ──────────────────────────────────────────────

    @Test fun `chipContainerColor for Busy is RunningContainer`() {
        assertThat(chipContainerColorFor(NodeTileStatus.Busy).toArgb())
            .isEqualTo(VelaColors.RunningContainer.toArgb())
    }

    @Test fun `chipContainerColor for Ready is dark teal`() {
        assertThat(chipContainerColorFor(NodeTileStatus.Ready).toArgb())
            .isEqualTo(0xFF0E2724.toInt())
    }

    // ── chipLabelFor ───────────────────────────────────────────────────────

    @Test fun `chipLabel for each status is correct`() {
        assertThat(chipLabelFor(NodeTileStatus.Busy)).isEqualTo("BUSY")
        assertThat(chipLabelFor(NodeTileStatus.Ready)).isEqualTo("READY")
        assertThat(chipLabelFor(NodeTileStatus.SettingUp)).isEqualTo("SETTING UP")
        assertThat(chipLabelFor(NodeTileStatus.Checking)).isEqualTo("CHECKING")
        assertThat(chipLabelFor(NodeTileStatus.Offline)).isEqualTo("OFFLINE")
    }

    // ── nodeStatusFor ──────────────────────────────────────────────────────

    @Test fun `nodeStatus for SSH node is SettingUp regardless of connectivity`() {
        val node = SshNode(label = "pi", type = NodeType.SSH)
        assertThat(nodeStatusFor(node)).isEqualTo(NodeTileStatus.SettingUp)
        assertThat(nodeStatusFor(node, NodeConnectivity.Reachable("http://x"))).isEqualTo(NodeTileStatus.SettingUp)
        assertThat(nodeStatusFor(node, NodeConnectivity.Unreachable)).isEqualTo(NodeTileStatus.SettingUp)
    }

    @Test fun `nodeStatus for AMPLIFIERD with no connectivity is Checking`() {
        val node = SshNode(label = "amp", type = NodeType.AMPLIFIERD)
        assertThat(nodeStatusFor(node)).isEqualTo(NodeTileStatus.Checking)
        assertThat(nodeStatusFor(node, null)).isEqualTo(NodeTileStatus.Checking)
        assertThat(nodeStatusFor(node, NodeConnectivity.Unknown)).isEqualTo(NodeTileStatus.Checking)
        assertThat(nodeStatusFor(node, NodeConnectivity.Checking)).isEqualTo(NodeTileStatus.Checking)
    }

    @Test fun `nodeStatus for AMPLIFIERD Reachable is Ready`() {
        val node = SshNode(label = "amp", type = NodeType.AMPLIFIERD)
        assertThat(nodeStatusFor(node, NodeConnectivity.Reachable("http://10.0.0.143:8410")))
            .isEqualTo(NodeTileStatus.Ready)
    }

    @Test fun `nodeStatus for AMPLIFIERD Unreachable is Offline`() {
        val node = SshNode(label = "amp", type = NodeType.AMPLIFIERD)
        assertThat(nodeStatusFor(node, NodeConnectivity.Unreachable))
            .isEqualTo(NodeTileStatus.Offline)
    }

    @Test fun `nodeStatus for AMPLIFIERD BOOTSTRAPPING is Checking (no connectivity)`() {
        val node = SshNode(label = "amp", type = NodeType.AMPLIFIERD,
                           bootstrapStatus = BootstrapStatus.BOOTSTRAPPING)
        assertThat(nodeStatusFor(node)).isEqualTo(NodeTileStatus.Checking)
    }

    // ── telemetryLineFor ───────────────────────────────────────────────────

    @Test fun `telemetry for SSH node includes type and host`() {
        val node = SshNode(label = "pi", type = NodeType.SSH,
                           hosts = listOf("192.168.1.5"))
        assertThat(telemetryLineFor(node)).contains("ssh")
        assertThat(telemetryLineFor(node)).contains("192.168.1.5")
    }

    @Test fun `telemetry for AMPLIFIERD node includes amplifierd and url host`() {
        val node = SshNode(label = "amp", type = NodeType.AMPLIFIERD,
                           url = "http://10.0.0.106:8410")
        assertThat(telemetryLineFor(node)).contains("amplifierd")
        assertThat(telemetryLineFor(node)).contains("10.0.0.106")
    }

    // ── Structural: verify composable exists ──────────────────────────────

    @Test fun `NodeTile source contains NodeTileItem composable`() {
        val src = java.io.File(
            "src/main/kotlin/com/vela/app/ui/home/NodeTile.kt"
        ).readText()
        assertThat(src).contains("fun NodeTileItem")
    }
}
