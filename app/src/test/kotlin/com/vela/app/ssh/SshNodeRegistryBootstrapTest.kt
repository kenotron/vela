package com.vela.app.ssh

import com.google.common.truth.Truth.assertThat
import com.vela.app.data.db.SshNodeDao
import com.vela.app.data.db.SshNodeEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * RED → GREEN: verifies the registry's bootstrap-related writers and the
 * entity↔domain mapping for the new bootstrapStatus column.
 */
class SshNodeRegistryBootstrapTest {

    // ── Fake DAO ──────────────────────────────────────────────────────────────

    private class FakeSshNodeDao : SshNodeDao {
        val statusUpdates = mutableListOf<Pair<String, String>>()           // id, status
        val promotions    = mutableListOf<PromoteCall>()
        val inserts       = mutableListOf<SshNodeEntity>()
        var nextGetById: SshNodeEntity? = null

        data class PromoteCall(
            val id: String,
            val type: String,
            val url: String,
            val token: String,
            val status: String,
        )

        override fun getAllNodes(): Flow<List<SshNodeEntity>> = flowOf(emptyList())
        override suspend fun insert(node: SshNodeEntity) { inserts += node }
        override suspend fun delete(id: String) { /* no-op */ }
        override suspend fun getById(id: String): SshNodeEntity? = nextGetById

        override suspend fun updateBootstrapStatus(id: String, status: String) {
            statusUpdates += id to status
        }

        override suspend fun promoteToAmplifierd(
            id: String,
            type: String,
            url: String,
            token: String,
            status: String,
        ) {
            promotions += PromoteCall(id, type, url, token, status)
        }
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    fun `promoteToAmplifierd writes type, url, token, RUNNING status`() = runTest {
        val dao = FakeSshNodeDao()
        val registry = SshNodeRegistry(dao)

        registry.promoteToAmplifierd(
            nodeId = "node-1",
            url    = "http://10.0.0.5:8410",
            token  = "abc123",
        )

        assertThat(dao.promotions).hasSize(1)
        val call = dao.promotions[0]
        assertThat(call.id).isEqualTo("node-1")
        assertThat(call.type).isEqualTo("amplifierd")
        assertThat(call.url).isEqualTo("http://10.0.0.5:8410")
        assertThat(call.token).isEqualTo("abc123")
        assertThat(call.status).isEqualTo("RUNNING")
    }

    @Test
    fun `markStale writes STALE status for the node`() = runTest {
        val dao = FakeSshNodeDao()
        val registry = SshNodeRegistry(dao)

        registry.markStale("node-2")

        assertThat(dao.statusUpdates).containsExactly("node-2" to "STALE")
    }

    @Test
    fun `updateBootstrapStatus writes the enum name`() = runTest {
        val dao = FakeSshNodeDao()
        val registry = SshNodeRegistry(dao)

        registry.updateBootstrapStatus("node-3", BootstrapStatus.BOOTSTRAPPING)

        assertThat(dao.statusUpdates).containsExactly("node-3" to "BOOTSTRAPPING")
    }

    @Test
    fun `addNode round-trips bootstrapStatus through entity mapping`() = runTest {
        val dao = FakeSshNodeDao()
        val registry = SshNodeRegistry(dao)

        registry.addNode(
            SshNode(
                id              = "n4",
                label           = "amp",
                hosts           = listOf("10.0.0.6"),
                bootstrapStatus = BootstrapStatus.RUNNING,
            )
        )

        assertThat(dao.inserts).hasSize(1)
        assertThat(dao.inserts[0].bootstrapStatus).isEqualTo("RUNNING")
    }

    @Test
    fun `allFlow maps unknown bootstrapStatus strings to UNPROVISIONED`() = runTest {
        val dao = object : FakeSshNodeDao() {
            override fun getAllNodes(): Flow<List<SshNodeEntity>> = flowOf(
                listOf(
                    SshNodeEntity(
                        id              = "x",
                        label           = "x",
                        hosts           = "1.1.1.1",
                        port            = 22,
                        username        = "u",
                        addedAt         = 0L,
                        bootstrapStatus = "BOGUS_VALUE",
                    )
                )
            )
        }
        val registry = SshNodeRegistry(dao)

        val first = registry.allFlow().let { flow ->
            var captured: List<SshNode> = emptyList()
            flow.collect { captured = it }
            captured
        }

        assertThat(first).hasSize(1)
        assertThat(first[0].bootstrapStatus).isEqualTo(BootstrapStatus.UNPROVISIONED)
    }

    @Test
    fun `allFlow maps known bootstrapStatus string to enum`() = runTest {
        val dao = object : FakeSshNodeDao() {
            override fun getAllNodes(): Flow<List<SshNodeEntity>> = flowOf(
                listOf(
                    SshNodeEntity(
                        id              = "y",
                        label           = "y",
                        hosts           = "2.2.2.2",
                        port            = 22,
                        username        = "u",
                        addedAt         = 0L,
                        bootstrapStatus = "STALE",
                    )
                )
            )
        }
        val registry = SshNodeRegistry(dao)

        var captured: List<SshNode> = emptyList()
        registry.allFlow().collect { captured = it }

        assertThat(captured[0].bootstrapStatus).isEqualTo(BootstrapStatus.STALE)
    }
}
