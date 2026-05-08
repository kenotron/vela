package com.vela.app.ssh

import com.google.common.truth.Truth.assertThat
import com.vela.app.data.db.SshNodeDao
import com.vela.app.data.db.SshNodeEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * RED → GREEN: verifies updateMachineId, addEndpoint, and serializeEndpoints
 * helpers introduced in task-2.
 */
class SshNodeRegistryEndpointTest {

    // ── Fake DAO ────────────────────────────────────────────────────────────

    private class FakeSshNodeDao : SshNodeDao {
        val machineIdUpdates = mutableListOf<Pair<String, String>>()  // id, machineId
        val endpointUpdates  = mutableListOf<Pair<String, String>>()  // id, endpoints JSON

        override fun getAllNodes(): Flow<List<SshNodeEntity>> = flowOf(emptyList())
        override suspend fun insert(node: SshNodeEntity) {}
        override suspend fun delete(id: String) {}
        override suspend fun getById(id: String): SshNodeEntity? = null
        override suspend fun updateBootstrapStatus(id: String, status: String) {}
        override suspend fun promoteToAmplifierd(
            id: String, type: String, url: String, tailscaleUrl: String,
            token: String, status: String, machineId: String, endpoints: String,
        ) {}
        override suspend fun updateConnection(
            id: String, label: String, hosts: String, port: Int,
            username: String, workspaceDir: String,
        ) {}
        override suspend fun updateMachineId(id: String, machineId: String) {
            machineIdUpdates += id to machineId
        }
        override suspend fun updateEndpoints(id: String, endpoints: String) {
            endpointUpdates += id to endpoints
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun makeNode(
        id: String = "node-1",
        endpoints: List<NodeEndpoint> = emptyList(),
    ) = SshNode(
        id       = id,
        label    = "test",
        hosts    = listOf("10.0.0.1"),
        endpoints = endpoints,
    )

    // ── updateMachineId ──────────────────────────────────────────────────────

    @Test fun `updateMachineId delegates to dao with correct arguments`() = runTest {
        val dao      = FakeSshNodeDao()
        val registry = SshNodeRegistry(dao)

        registry.updateMachineId("node-1", "machine-abc")

        assertThat(dao.machineIdUpdates).containsExactly("node-1" to "machine-abc")
    }

    // ── addEndpoint — early-exit ─────────────────────────────────────────────

    @Test fun `addEndpoint returns early when node not in cache`() = runTest {
        val dao      = FakeSshNodeDao()
        val registry = SshNodeRegistry(dao)
        // cache intentionally left empty — no matching node

        registry.addEndpoint("node-999", NodeEndpoint.Direct("http://10.0.0.5:8410"))

        assertThat(dao.endpointUpdates).isEmpty()
    }

    // ── addEndpoint — idempotency ────────────────────────────────────────────

    @Test fun `addEndpoint skips duplicate mDNS endpoint`() = runTest {
        val dao      = FakeSshNodeDao()
        val registry = SshNodeRegistry(dao)
        val existing = NodeEndpoint.Mdns("ken-mac._amplifierd._tcp.local.")
        registry.updateCache(listOf(makeNode(endpoints = listOf(existing))))

        registry.addEndpoint("node-1", NodeEndpoint.Mdns("ken-mac._amplifierd._tcp.local."))

        assertThat(dao.endpointUpdates).isEmpty()
    }

    @Test fun `addEndpoint skips duplicate Direct endpoint`() = runTest {
        val dao      = FakeSshNodeDao()
        val registry = SshNodeRegistry(dao)
        val existing = NodeEndpoint.Direct("http://10.0.0.50:8410")
        registry.updateCache(listOf(makeNode(endpoints = listOf(existing))))

        registry.addEndpoint("node-1", NodeEndpoint.Direct("http://10.0.0.50:8410"))

        assertThat(dao.endpointUpdates).isEmpty()
    }

    @Test fun `addEndpoint skips duplicate Tailscale endpoint`() = runTest {
        val dao      = FakeSshNodeDao()
        val registry = SshNodeRegistry(dao)
        val existing = NodeEndpoint.Tailscale("http://100.64.0.1:8410")
        registry.updateCache(listOf(makeNode(endpoints = listOf(existing))))

        registry.addEndpoint("node-1", NodeEndpoint.Tailscale("http://100.64.0.1:8410"))

        assertThat(dao.endpointUpdates).isEmpty()
    }

    // ── addEndpoint — new endpoint persisted ─────────────────────────────────

    @Test fun `addEndpoint persists new mDNS endpoint for node in cache`() = runTest {
        val dao      = FakeSshNodeDao()
        val registry = SshNodeRegistry(dao)
        val existing = NodeEndpoint.Direct("http://10.0.0.50:8410")
        registry.updateCache(listOf(makeNode(endpoints = listOf(existing))))

        registry.addEndpoint("node-1", NodeEndpoint.Mdns("ken-mac._amplifierd._tcp.local."))

        assertThat(dao.endpointUpdates).hasSize(1)
        assertThat(dao.endpointUpdates[0].first).isEqualTo("node-1")
    }

    @Test fun `addEndpoint serializes mdns endpoint with type and serviceName`() = runTest {
        val dao      = FakeSshNodeDao()
        val registry = SshNodeRegistry(dao)
        registry.updateCache(listOf(makeNode(endpoints = emptyList())))

        registry.addEndpoint("node-1", NodeEndpoint.Mdns("svc._amplifierd._tcp.local."))

        assertThat(dao.endpointUpdates).hasSize(1)
        val json = org.json.JSONArray(dao.endpointUpdates[0].second)
        assertThat(json.length()).isEqualTo(1)
        val obj = json.getJSONObject(0)
        assertThat(obj.getString("type")).isEqualTo("mdns")
        assertThat(obj.getString("serviceName")).isEqualTo("svc._amplifierd._tcp.local.")
    }

    @Test fun `addEndpoint serializes direct endpoint with type and url`() = runTest {
        val dao      = FakeSshNodeDao()
        val registry = SshNodeRegistry(dao)
        registry.updateCache(listOf(makeNode(endpoints = emptyList())))

        registry.addEndpoint("node-1", NodeEndpoint.Direct("http://10.0.0.5:8410"))

        assertThat(dao.endpointUpdates).hasSize(1)
        val json = org.json.JSONArray(dao.endpointUpdates[0].second)
        val obj  = json.getJSONObject(0)
        assertThat(obj.getString("type")).isEqualTo("direct")
        assertThat(obj.getString("url")).isEqualTo("http://10.0.0.5:8410")
    }

    @Test fun `addEndpoint serializes tailscale endpoint with type and url`() = runTest {
        val dao      = FakeSshNodeDao()
        val registry = SshNodeRegistry(dao)
        registry.updateCache(listOf(makeNode(endpoints = emptyList())))

        registry.addEndpoint("node-1", NodeEndpoint.Tailscale("http://100.64.0.1:8410"))

        assertThat(dao.endpointUpdates).hasSize(1)
        val json = org.json.JSONArray(dao.endpointUpdates[0].second)
        val obj  = json.getJSONObject(0)
        assertThat(obj.getString("type")).isEqualTo("tailscale")
        assertThat(obj.getString("url")).isEqualTo("http://100.64.0.1:8410")
    }

    @Test fun `addEndpoint appends to existing endpoints in JSON`() = runTest {
        val dao      = FakeSshNodeDao()
        val registry = SshNodeRegistry(dao)
        val existing = NodeEndpoint.Direct("http://10.0.0.50:8410")
        registry.updateCache(listOf(makeNode(endpoints = listOf(existing))))

        registry.addEndpoint("node-1", NodeEndpoint.Mdns("ken-mac._amplifierd._tcp.local."))

        assertThat(dao.endpointUpdates).hasSize(1)
        val json = org.json.JSONArray(dao.endpointUpdates[0].second)
        // both the original direct AND the new mDNS endpoint should be present
        assertThat(json.length()).isEqualTo(2)
    }
}
