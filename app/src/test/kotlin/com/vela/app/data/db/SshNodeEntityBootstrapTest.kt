package com.vela.app.data.db

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * RED → GREEN: verifies that [SshNodeEntity] carries a [bootstrapStatus]
 * column that defaults to "UNPROVISIONED" for backward compatibility.
 */
class SshNodeEntityBootstrapTest {

    @Test
    fun `bootstrapStatus defaults to UNPROVISIONED when not set`() {
        val entity = SshNodeEntity(
            id       = "n1",
            label    = "pi-zero",
            hosts    = "10.0.0.10",
            port     = 22,
            username = "ken",
            addedAt  = 0L,
        )
        assertThat(entity.bootstrapStatus).isEqualTo("UNPROVISIONED")
    }

    @Test
    fun `bootstrapStatus can be set explicitly`() {
        val entity = SshNodeEntity(
            id              = "n2",
            label           = "amp-host",
            hosts           = "10.0.0.20",
            port            = 22,
            username        = "ken",
            addedAt         = 0L,
            nodeType        = "amplifierd",
            url             = "http://10.0.0.20:8410",
            token           = "secret",
            bootstrapStatus = "RUNNING",
        )
        assertThat(entity.bootstrapStatus).isEqualTo("RUNNING")
    }
}
