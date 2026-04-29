package com.vela.app.ssh

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * RED → GREEN: verifies the BootstrapStatus enum exists with the four
 * lifecycle values defined by design doc Section 3.
 */
class BootstrapStatusTest {

    @Test
    fun `enum has exactly five values in lifecycle order`() {
        val values = BootstrapStatus.values().map { it.name }
        assertThat(values).containsExactly(
            "UNPROVISIONED",
            "BOOTSTRAPPING",
            "RUNNING",
            "STALE",
            "FAILED",
        ).inOrder()
    }

    @Test
    fun `valueOf round-trips each entry by name`() {
        BootstrapStatus.values().forEach { status ->
            assertThat(BootstrapStatus.valueOf(status.name)).isEqualTo(status)
        }
    }
}
