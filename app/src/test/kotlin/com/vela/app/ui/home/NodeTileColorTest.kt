package com.vela.app.ui.home

import androidx.compose.ui.graphics.toArgb
import com.google.common.truth.Truth.assertThat
import com.vela.app.ssh.BootstrapStatus
import com.vela.app.ssh.NodeType
import com.vela.app.ssh.SshNode
import com.vela.app.ui.theme.VelaColors
import org.junit.Test

/**
 * RED → GREEN: verifies the pure color-mapping and status-derivation logic
 * for node tile status stripes and live dots.
 *
 * All functions under test are top-level `internal` functions in NodeTile.kt.
 * JVM-only — no Compose rendering needed.
 * androidx.compose.ui.graphics.Color is a Kotlin value class; no Android calls.
 */
class NodeTileColorTest {

    // ── stripeColorFor ──────────────────────────────────────────────────────────

    @Test fun `stripeColor for Running is VelaColors Running`() {
        assertThat(stripeColorFor(NodeTileStatus.Running).toArgb())
            .isEqualTo(VelaColors.Running.toArgb())
    }

    @Test fun `stripeColor for Waiting is VelaColors Waiting`() {
        assertThat(stripeColorFor(NodeTileStatus.Waiting).toArgb())
            .isEqualTo(VelaColors.Waiting.toArgb())
    }

    @Test fun `stripeColor for Done is VelaColors Done`() {
        assertThat(stripeColorFor(NodeTileStatus.Done).toArgb())
            .isEqualTo(VelaColors.Done.toArgb())
    }

    @Test fun `stripeColor for Idle is VelaColors Accent`() {
        assertThat(stripeColorFor(NodeTileStatus.Idle).toArgb())
            .isEqualTo(VelaColors.Accent.toArgb())
    }

    // ── stripeAlphaFor ──────────────────────────────────────────────────────────

    @Test fun `stripeAlpha for Running is 1f`() {
        assertThat(stripeAlphaFor(NodeTileStatus.Running)).isEqualTo(1f)
    }

    @Test fun `stripeAlpha for Waiting is 1f`() {
        assertThat(stripeAlphaFor(NodeTileStatus.Waiting)).isEqualTo(1f)
    }

    @Test fun `stripeAlpha for Done is 0point5`() {
        assertThat(stripeAlphaFor(NodeTileStatus.Done)).isEqualTo(0.5f)
    }

    @Test fun `stripeAlpha for Idle is 0point4`() {
        assertThat(stripeAlphaFor(NodeTileStatus.Idle)).isEqualTo(0.4f)
    }

    // ── chipContainerColorFor ───────────────────────────────────────────────────

    @Test fun `chipContainerColor for Running is RunningContainer`() {
        assertThat(chipContainerColorFor(NodeTileStatus.Running).toArgb())
            .isEqualTo(VelaColors.RunningContainer.toArgb())
    }

    @Test fun `chipContainerColor for Waiting is WaitingContainer`() {
        assertThat(chipContainerColorFor(NodeTileStatus.Waiting).toArgb())
            .isEqualTo(VelaColors.WaitingContainer.toArgb())
    }

    @Test fun `chipContainerColor for Done is DoneContainer`() {
        assertThat(chipContainerColorFor(NodeTileStatus.Done).toArgb())
            .isEqualTo(VelaColors.DoneContainer.toArgb())
    }

    @Test fun `chipContainerColor for Idle is SurfaceRaised`() {
        assertThat(chipContainerColorFor(NodeTileStatus.Idle).toArgb())
            .isEqualTo(VelaColors.SurfaceRaised.toArgb())
    }

    // ── nodeStatusFor — Phase 2: all nodes are Idle ─────────────────────────────

    @Test fun `nodeStatus for SSH node is Idle`() {
        val node = SshNode(label = "pi", type = NodeType.SSH)
        assertThat(nodeStatusFor(node)).isEqualTo(NodeTileStatus.Idle)
    }

    @Test fun `nodeStatus for AMPLIFIERD RUNNING node is Idle (no session data yet)`() {
        val node = SshNode(label = "amp", type = NodeType.AMPLIFIERD,
                           bootstrapStatus = BootstrapStatus.RUNNING)
        assertThat(nodeStatusFor(node)).isEqualTo(NodeTileStatus.Idle)
    }

    @Test fun `nodeStatus for AMPLIFIERD UNPROVISIONED is Idle`() {
        val node = SshNode(label = "amp", type = NodeType.AMPLIFIERD,
                           bootstrapStatus = BootstrapStatus.UNPROVISIONED)
        assertThat(nodeStatusFor(node)).isEqualTo(NodeTileStatus.Idle)
    }

    @Test fun `nodeStatus for AMPLIFIERD FAILED is Idle`() {
        val node = SshNode(label = "amp", type = NodeType.AMPLIFIERD,
                           bootstrapStatus = BootstrapStatus.FAILED)
        assertThat(nodeStatusFor(node)).isEqualTo(NodeTileStatus.Idle)
    }

    // ── telemetryLineFor ────────────────────────────────────────────────────────

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
}
