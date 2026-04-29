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

    internal fun detectPlatform(unameOutput: String): RemotePlatform? =
        when (unameOutput.trim()) {
            "Darwin arm64"  -> RemotePlatform.MACOS_ARM64
            "Darwin x86_64" -> RemotePlatform.MACOS_X86
            "Linux x86_64"  -> RemotePlatform.LINUX_AMD64
            "Linux aarch64" -> RemotePlatform.LINUX_ARM64
            else            -> null
        }

    internal fun detectPlatformForTest(unameOutput: String) = detectPlatform(unameOutput)

    internal fun generateSettingsJson(bundle: BundleChoice, token: String): String {
        val bundles = org.json.JSONArray()
        if (bundle.bundleName.isNotEmpty()) bundles.put(bundle.bundleName)
        val vela = org.json.JSONObject().put("auth_token", token)
        return org.json.JSONObject()
            .put("host", "0.0.0.0")
            .put("port", 8410)
            .put("log_level", "info")
            .put("bundles", bundles)
            .put("disabled_plugins", org.json.JSONArray())
            .put("vela", vela)
            .toString(2)
    }

    internal fun generateSettingsJsonForTest(bundle: BundleChoice, token: String) =
        generateSettingsJson(bundle, token)

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
