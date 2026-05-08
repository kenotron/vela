package com.vela.app.ssh

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * RED → GREEN: verifies that SshNode carries machineId and endpoints
 * fields added in the task-7 domain update.
 */
class SshNodeFieldsTest {

    @Test
    fun `SshNode has machineId defaulting to empty string`() {
        val node = SshNode(label = "test-node")
        assertThat(node.machineId).isEqualTo("")
    }

    @Test
    fun `SshNode machineId can be set explicitly`() {
        val node = SshNode(label = "test-node", machineId = "abc-machine-123")
        assertThat(node.machineId).isEqualTo("abc-machine-123")
    }

    @Test
    fun `SshNode has endpoints defaulting to empty list`() {
        val node = SshNode(label = "test-node")
        assertThat(node.endpoints).isEmpty()
    }

    @Test
    fun `SshNode endpoints can hold NodeEndpoint instances`() {
        val ep = NodeEndpoint.Direct(url = "http://10.0.0.1:8410")
        val node = SshNode(label = "test-node", endpoints = listOf(ep))
        assertThat(node.endpoints).hasSize(1)
        assertThat((node.endpoints[0] as NodeEndpoint.Direct).url).isEqualTo("http://10.0.0.1:8410")
    }

    @Test
    fun `SshNode url and tailscaleUrl are kept for backward compat with empty defaults`() {
        val node = SshNode(label = "test-node")
        assertThat(node.url).isEqualTo("")
        assertThat(node.tailscaleUrl).isEqualTo("")
    }
}
