package com.vela.app.ssh

import javax.inject.Inject

// ── RemoteShell abstraction ───────────────────────────────────────────────────

/**
 * Internal seam for executing commands and writing files on a remote host.
 * Production: backed by a JSch session. Tests: backed by FakeRemoteShell.
 */
internal interface RemoteShell {
    data class Result(val stdout: String, val exitCode: Int)
    suspend fun exec(command: String): Result
    suspend fun sftpWrite(remotePath: String, contents: String)
    fun close()
}

// ── JSch-backed RemoteShell ───────────────────────────────────────────────────

internal class JschRemoteShell(
    private val session: com.jcraft.jsch.Session,
) : RemoteShell {

    override suspend fun exec(command: String): RemoteShell.Result =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val channel = session.openChannel("exec") as com.jcraft.jsch.ChannelExec
            channel.setCommand(command)
            val stdoutBuf = java.io.ByteArrayOutputStream()
            val stderrBuf = java.io.ByteArrayOutputStream()
            channel.outputStream = stdoutBuf
            channel.setErrStream(stderrBuf)
            channel.connect()
            // Wait until channel closes (timeout: 5 minutes for long installs).
            val deadline = System.currentTimeMillis() + 5 * 60_000L
            while (!channel.isClosed && System.currentTimeMillis() < deadline) Thread.sleep(50)
            Thread.sleep(50) // drain final bytes
            val out = stdoutBuf.toString(Charsets.UTF_8.name())
            val err = stderrBuf.toString(Charsets.UTF_8.name())
            val exit = channel.exitStatus
            channel.disconnect()
            val combined = if (err.isBlank()) out else "$out\n[stderr] $err"
            RemoteShell.Result(stdout = combined, exitCode = exit)
        }

    override suspend fun sftpWrite(remotePath: String, contents: String) =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val channel = session.openChannel("sftp") as com.jcraft.jsch.ChannelSftp
            channel.connect()
            try {
                contents.byteInputStream(Charsets.UTF_8).use { stream ->
                    channel.put(stream, remotePath)
                }
            } finally {
                channel.disconnect()
            }
        }

    override fun close() {
        if (session.isConnected) session.disconnect()
    }
}

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

    internal fun generateLaunchdPlist(username: String, anthropicKey: String): String = """
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>com.vela.amplifierd</string>
  <key>ProgramArguments</key>
  <array>
    <string>/Users/$username/.local/bin/amplifierd</string>
    <string>serve</string>
    <string>--host</string><string>0.0.0.0</string>
    <string>--port</string><string>8410</string>
  </array>
  <key>EnvironmentVariables</key>
  <dict>
    <key>PATH</key><string>/Users/$username/.local/bin:/usr/local/bin:/usr/bin:/bin</string>
    <key>ANTHROPIC_API_KEY</key><string>$anthropicKey</string>
  </dict>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>StandardOutPath</key><string>/Users/$username/.amplifierd/stdout.log</string>
  <key>StandardErrorPath</key><string>/Users/$username/.amplifierd/stderr.log</string>
</dict>
</plist>
    """.trimIndent()

    internal fun generateLaunchdPlistForTest(username: String, anthropicKey: String) =
        generateLaunchdPlist(username, anthropicKey)

    internal fun generateSystemdUnit(anthropicKey: String): String = """
[Unit]
Description=Vela amplifierd daemon
After=network-online.target

[Service]
Type=simple
ExecStart=%h/.local/bin/amplifierd serve --host 0.0.0.0 --port 8410
Environment="PATH=%h/.local/bin:/usr/local/bin:/usr/bin:/bin"
Environment="ANTHROPIC_API_KEY=$anthropicKey"
Restart=on-failure
RestartSec=3

[Install]
WantedBy=default.target
    """.trimIndent()

    internal fun generateSystemdUnitForTest(anthropicKey: String) = generateSystemdUnit(anthropicKey)

    internal fun buildUvInstallCommand(bundle: BundleChoice): String = buildString {
        append("export PATH=\"\$HOME/.local/bin:\$PATH\" && uv tool install")
        append(" --with git+https://github.com/kenotron/vela#subdirectory=plugins/amplifierd-vela")
        if (bundle.packageSuffix != null) {
            append(" --with ${bundle.packageSuffix}")
        }
        append(" git+https://github.com/microsoft/amplifierd")
    }

    internal fun buildUvInstallCommandForTest(bundle: BundleChoice) = buildUvInstallCommand(bundle)

    // ── JSch session factory ──────────────────────────────────────────────────

    private fun openJschShell(host: String, port: Int, username: String): RemoteShell {
        val jsch = com.jcraft.jsch.JSch()
        jsch.addIdentity(
            "vela",
            keyManager.getPrivateKeyPem().toByteArray(Charsets.UTF_8),
            null,
            null,
        )
        val session = jsch.getSession(username, host, port).apply {
            setConfig("StrictHostKeyChecking", "no")
            setConfig("ServerAliveInterval", "10")
            connect(15_000)
        }
        return JschRemoteShell(session)
    }

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
