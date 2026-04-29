package com.vela.app.ssh

import javax.inject.Inject

// ── Supporting enums ─────────────────────────────────────────────────────────

enum class BundleChoice(val packageSuffix: String?, val bundleName: String) {
    SUPERPOWERS("amplifierd-bundle-superpowers", "superpowers"),
    LIFEOS("amplifierd-bundle-lifeos", "lifeos"),
    TOOLS_ONLY(null, ""),
}

enum class RemotePlatform { MACOS_ARM64, MACOS_X86, LINUX_AMD64, LINUX_ARM64 }

// ─────────────────────────────────────────────────────────────────────────────

open class NodeBootstrapper @Inject constructor(
    private val keyManager: SshKeyManager,
    private val registry: SshNodeRegistry,
) {
    // ── helpers (internal for tests) ──────────────────────────────────────────

    internal fun generateToken(): String {
        val bytes = ByteArray(32)
        java.security.SecureRandom.getInstanceStrong().nextBytes(bytes)
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    internal fun generateTokenForTest(): String = generateToken()

    companion object {
        /** Build an instance for unit-testing pure helpers (no Hilt graph needed). */
        internal fun testInstance(): NodeBootstrapper = NodeBootstrapper(
            keyManager = SshKeyManager(android.content.ContextWrapper(null)),
            registry = SshNodeRegistry(throwingDao()),
        )

        private fun throwingDao(): com.vela.app.data.db.SshNodeDao =
            object : com.vela.app.data.db.SshNodeDao {
                override fun getAllNodes(): kotlinx.coroutines.flow.Flow<List<com.vela.app.data.db.SshNodeEntity>> =
                    error("SshNodeDao must not be accessed in NodeBootstrapper helper tests")
                override suspend fun insert(node: com.vela.app.data.db.SshNodeEntity) =
                    error("SshNodeDao must not be accessed in NodeBootstrapper helper tests")
                override suspend fun delete(id: String) =
                    error("SshNodeDao must not be accessed in NodeBootstrapper helper tests")
                override suspend fun getById(id: String): com.vela.app.data.db.SshNodeEntity? =
                    error("SshNodeDao must not be accessed in NodeBootstrapper helper tests")
                override suspend fun updateBootstrapStatus(id: String, status: String) =
                    error("SshNodeDao must not be accessed in NodeBootstrapper helper tests")
                override suspend fun promoteToAmplifierd(
                    id: String, type: String, url: String, token: String, status: String,
                ) = error("SshNodeDao must not be accessed in NodeBootstrapper helper tests")
            }
    }
}
