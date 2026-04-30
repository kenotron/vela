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

    /** Overload for repair flow: accepts an explicit list of bundle names instead of a [BundleChoice]. */
    private fun generateSettingsJson(bundleNames: List<String>, token: String): String {
        val bundles = org.json.JSONArray()
        bundleNames.forEach { bundles.put(it) }
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

    internal fun generateLaunchdPlist(username: String, anthropicKey: String, homeDir: String): String = """
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>com.vela.amplifierd</string>
  <key>ProgramArguments</key>
  <array>
    <string>$homeDir/.local/bin/amplifierd</string>
    <string>serve</string>
    <string>--host</string><string>0.0.0.0</string>
    <string>--port</string><string>8410</string>
  </array>
  <key>EnvironmentVariables</key>
  <dict>
    <key>PATH</key><string>$homeDir/.local/bin:/usr/local/bin:/usr/bin:/bin</string>
    <key>ANTHROPIC_API_KEY</key><string>$anthropicKey</string>
  </dict>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>StandardOutPath</key><string>$homeDir/.amplifierd/stdout.log</string>
  <key>StandardErrorPath</key><string>$homeDir/.amplifierd/stderr.log</string>
</dict>
</plist>
    """.trimIndent()

    internal fun generateLaunchdPlistForTest(
        username: String,
        anthropicKey: String,
        homeDir: String = "/Users/$username",
    ) = generateLaunchdPlist(username, anthropicKey, homeDir)

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

    // ── Public bootstrap entry ────────────────────────────────────────────────

    /** Public entry: opens a real JSch session, then delegates to [bootstrapWithShell]. */
    open suspend fun bootstrap(
        nodeId: String,
        host: String,
        port: Int,
        username: String,
        bundle: BundleChoice,
        anthropicKey: String,
    ): kotlinx.coroutines.flow.Flow<BootstrapEvent> = kotlinx.coroutines.flow.flow {
        val shell = openJschShell(host, port, username)
        try {
            bootstrapWithShell(shell, nodeId, host, username, bundle, anthropicKey).collect { emit(it) }
        } finally {
            shell.close()
        }
    }

    /** Test-friendly variant: caller supplies the shell. */
    internal fun bootstrapWithShell(
        shell: RemoteShell,
        nodeId: String,
        host: String,
        username: String,
        bundle: BundleChoice,
        anthropicKey: String,
    ): kotlinx.coroutines.flow.Flow<BootstrapEvent> = kotlinx.coroutines.flow.flow {
        val token = generateToken()

        // Step 1: CONNECT — caller already opened the shell, but emit so UI sees it.
        emit(BootstrapEvent.StepStart(BootstrapStep.CONNECT))
        emit(BootstrapEvent.StepComplete(BootstrapStep.CONNECT))

        // Step 2: DETECT
        emit(BootstrapEvent.StepStart(BootstrapStep.DETECT))
        val unameResult = shell.exec("uname -sm")
        val platform = detectPlatform(unameResult.stdout)
        if (platform == null) {
            registry.updateBootstrapStatus(nodeId, BootstrapStatus.FAILED)
            emit(BootstrapEvent.Failed(BootstrapStep.DETECT, "Unsupported platform: ${unameResult.stdout.trim()}"))
            return@flow
        }
        emit(BootstrapEvent.StepComplete(BootstrapStep.DETECT))

        // Resolve actual home directory — don't assume /Users/$username or /home/$username
        val homeDir = shell.exec("echo \$HOME").stdout.trim().ifBlank {
            when (platform) {
                RemotePlatform.MACOS_ARM64, RemotePlatform.MACOS_X86 -> "/Users/$username"
                RemotePlatform.LINUX_AMD64, RemotePlatform.LINUX_ARM64 -> "/home/$username"
            }
        }

        // Step 3: INSTALL_UV
        emit(BootstrapEvent.StepStart(BootstrapStep.INSTALL_UV))
        val uvR = shell.exec("which uv >/dev/null 2>&1 || curl -LsSf https://astral.sh/uv/install.sh | sh")
        if (uvR.exitCode != 0) {
            registry.updateBootstrapStatus(nodeId, BootstrapStatus.FAILED)
            emit(BootstrapEvent.Failed(BootstrapStep.INSTALL_UV, "exit ${uvR.exitCode}: ${uvR.stdout.trim()}"))
            return@flow
        }
        emit(BootstrapEvent.StepComplete(BootstrapStep.INSTALL_UV))

        // Step 4: INSTALL_AMPLIFIERD
        emit(BootstrapEvent.StepStart(BootstrapStep.INSTALL_AMPLIFIERD))
        val ampR = shell.exec(buildUvInstallCommand(bundle))
        if (ampR.exitCode != 0) {
            registry.updateBootstrapStatus(nodeId, BootstrapStatus.FAILED)
            emit(BootstrapEvent.Failed(BootstrapStep.INSTALL_AMPLIFIERD, "exit ${ampR.exitCode}: ${ampR.stdout.trim()}"))
            return@flow
        }
        emit(BootstrapEvent.StepComplete(BootstrapStep.INSTALL_AMPLIFIERD))

        // Step 5: WRITE_CONFIG (atomic via /tmp)
        emit(BootstrapEvent.StepStart(BootstrapStep.WRITE_CONFIG))
        val tmpName = "/tmp/amplifierd_settings_${java.util.UUID.randomUUID()}.json"
        shell.sftpWrite(tmpName, generateSettingsJson(bundle, token))
        shell.exec("mkdir -p ~/.amplifierd && mv $tmpName ~/.amplifierd/settings.json")
        emit(BootstrapEvent.StepComplete(BootstrapStep.WRITE_CONFIG))

        // Step 6: INSTALL_SERVICE — branches on platform
        emit(BootstrapEvent.StepStart(BootstrapStep.INSTALL_SERVICE))
        when (platform) {
            RemotePlatform.MACOS_ARM64, RemotePlatform.MACOS_X86 -> {
                shell.exec("mkdir -p ~/Library/LaunchAgents")
                shell.sftpWrite(
                    "$homeDir/Library/LaunchAgents/com.vela.amplifierd.plist",
                    generateLaunchdPlist(username, anthropicKey, homeDir),
                )
                shell.exec("launchctl bootout gui/\$UID/com.vela.amplifierd 2>/dev/null || true")
                shell.exec("launchctl bootstrap gui/\$UID ~/Library/LaunchAgents/com.vela.amplifierd.plist")
                shell.exec("launchctl kickstart -k gui/\$UID/com.vela.amplifierd")
            }
            RemotePlatform.LINUX_AMD64, RemotePlatform.LINUX_ARM64 -> {
                shell.exec("mkdir -p ~/.config/systemd/user")
                shell.sftpWrite(
                    "$homeDir/.config/systemd/user/amplifierd.service",
                    generateSystemdUnit(anthropicKey),
                )
                shell.exec("loginctl enable-linger $username 2>/dev/null || true")
                shell.exec("systemctl --user daemon-reload")
                shell.exec("systemctl --user enable --now amplifierd.service")
            }
        }
        emit(BootstrapEvent.StepComplete(BootstrapStep.INSTALL_SERVICE))

        // Step 7: HEALTH_CHECK — polls up to 15 times with 2s delay
        emit(BootstrapEvent.StepStart(BootstrapStep.HEALTH_CHECK))
        var healthy = false
        for (attempt in 1..15) {
            val r = shell.exec("curl -fsS http://127.0.0.1:8410/health")
            if (r.exitCode == 0) { healthy = true; break }
            emit(BootstrapEvent.Output("health check attempt $attempt/15"))
            kotlinx.coroutines.delay(2_000)
        }
        if (!healthy) {
            val logCmd = when (platform) {
                RemotePlatform.MACOS_ARM64, RemotePlatform.MACOS_X86 ->
                    "tail -n 20 ~/.amplifierd/stderr.log 2>/dev/null; tail -n 20 ~/.amplifierd/stdout.log 2>/dev/null"
                RemotePlatform.LINUX_AMD64, RemotePlatform.LINUX_ARM64 ->
                    "journalctl --user -n 20 -u amplifierd --no-pager"
            }
            val logs = shell.exec(logCmd).stdout
            registry.updateBootstrapStatus(nodeId, BootstrapStatus.FAILED)
            emit(BootstrapEvent.Failed(BootstrapStep.HEALTH_CHECK, "Health check timed out after 15 attempts. Logs:\n$logs"))
            return@flow
        }
        emit(BootstrapEvent.StepComplete(BootstrapStep.HEALTH_CHECK))

        // Step 8: PROMOTE
        emit(BootstrapEvent.StepStart(BootstrapStep.PROMOTE))
        val tailscale = shell.exec("tailscale ip -4 2>/dev/null")
        val tsIp = tailscale.stdout.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
        val url = if (tailscale.exitCode == 0 && !tsIp.isNullOrEmpty())
            "http://$tsIp:8410" else "http://$host:8410"
        registry.promoteToAmplifierd(nodeId, url, token)
        registry.updateBootstrapStatus(nodeId, BootstrapStatus.RUNNING)
        emit(BootstrapEvent.StepComplete(BootstrapStep.PROMOTE))

        emit(BootstrapEvent.Complete(url, token))
    }

    // ── Public repair entry ───────────────────────────────────────────────────────────────────────

    /**
     * Public entry: opens a real JSch session, then delegates to [repairWithShell].
     * Uses [existingToken] from the DB — does NOT generate a new token.
     */
    open suspend fun repair(
        nodeId: String,
        host: String,
        port: Int,
        username: String,
        existingToken: String,
    ): kotlinx.coroutines.flow.Flow<BootstrapEvent> = kotlinx.coroutines.flow.flow {
        val shell = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            openJschShell(host, port, username)
        }
        try {
            repairWithShell(shell, nodeId, host, username, existingToken).collect { emit(it) }
        } finally {
            shell.close()
        }
    }

    /** Test-friendly variant: caller supplies the shell. */
    internal fun repairWithShell(
        shell: RemoteShell,
        nodeId: String,
        host: String,
        username: String,
        existingToken: String,
    ): kotlinx.coroutines.flow.Flow<BootstrapEvent> = kotlinx.coroutines.flow.flow {

        // Step 1: DETECT
        emit(BootstrapEvent.StepStart(BootstrapStep.DETECT))
        val unameResult = shell.exec("uname -sm")
        val platform = detectPlatform(unameResult.stdout)
        if (platform == null) {
            registry.updateBootstrapStatus(nodeId, BootstrapStatus.FAILED)
            emit(BootstrapEvent.Failed(BootstrapStep.DETECT, "Unsupported platform: ${unameResult.stdout.trim()}"))
            return@flow
        }
        emit(BootstrapEvent.StepComplete(BootstrapStep.DETECT))

        // Step 2: Resolve homeDir
        val homeDir = shell.exec("echo \$HOME").stdout.trim().ifBlank {
            when (platform) {
                RemotePlatform.MACOS_ARM64, RemotePlatform.MACOS_X86 -> "/Users/$username"
                RemotePlatform.LINUX_AMD64, RemotePlatform.LINUX_ARM64 -> "/home/$username"
            }
        }

        // Step 3: Read existing ANTHROPIC_API_KEY from the service file on the remote
        val anthropicKey: String = when (platform) {
            RemotePlatform.MACOS_ARM64, RemotePlatform.MACOS_X86 -> {
                val macKeyCmd = """grep -A1 'ANTHROPIC_API_KEY' "$homeDir/Library/LaunchAgents/com.vela.amplifierd.plist" 2>/dev/null | grep '<string>' | sed 's/.*<string>//;s/<\/string>//'"""
                shell.exec(macKeyCmd).stdout.trim()
            }
            RemotePlatform.LINUX_AMD64, RemotePlatform.LINUX_ARM64 -> {
                val linuxKeyCmd = """grep 'ANTHROPIC_API_KEY' "$homeDir/.config/systemd/user/amplifierd.service" 2>/dev/null | sed 's/.*ANTHROPIC_API_KEY=//;s/"//'"""
                shell.exec(linuxKeyCmd).stdout.trim()
            }
        }
        if (anthropicKey.isBlank()) {
            emit(BootstrapEvent.Output("⚠ ANTHROPIC_API_KEY not found in existing service file — service will start without it"))
        }

        // Step 4: INSTALL_UV (idempotent)
        emit(BootstrapEvent.StepStart(BootstrapStep.INSTALL_UV))
        val uvR = shell.exec("which uv >/dev/null 2>&1 || curl -LsSf https://astral.sh/uv/install.sh | sh")
        if (uvR.exitCode != 0) {
            registry.updateBootstrapStatus(nodeId, BootstrapStatus.FAILED)
            emit(BootstrapEvent.Failed(BootstrapStep.INSTALL_UV, "exit ${uvR.exitCode}: ${uvR.stdout.trim()}"))
            return@flow
        }
        emit(BootstrapEvent.StepComplete(BootstrapStep.INSTALL_UV))

        // Step 5: Read existing bundles from settings.json, then INSTALL_AMPLIFIERD --force
        emit(BootstrapEvent.StepStart(BootstrapStep.INSTALL_AMPLIFIERD))
        val bundlesCmd = """cat "$homeDir/.amplifierd/settings.json" 2>/dev/null | python3 -c "import sys,json;d=json.load(sys.stdin);print(' '.join(['--with '+b for b in d.get('bundles',[])]))" 2>/dev/null || true"""
        val bundlesOutput = shell.exec(bundlesCmd).stdout.trim()

        // Build --force install command; $homeDir is interpolated by Kotlin, $PATH stays as shell var.
        val forceInstallCmd = buildString {
            append("export PATH=\"$homeDir/.local/bin:\$PATH\" && uv tool install --force")
            if (bundlesOutput.isNotBlank()) {
                append(" $bundlesOutput")
            }
            append(" --with git+https://github.com/kenotron/vela#subdirectory=plugins/amplifierd-vela")
            append(" git+https://github.com/microsoft/amplifierd")
        }
        val ampR = shell.exec(forceInstallCmd)
        if (ampR.exitCode != 0) {
            registry.updateBootstrapStatus(nodeId, BootstrapStatus.FAILED)
            emit(BootstrapEvent.Failed(BootstrapStep.INSTALL_AMPLIFIERD, "exit ${ampR.exitCode}: ${ampR.stdout.trim()}"))
            return@flow
        }
        emit(BootstrapEvent.StepComplete(BootstrapStep.INSTALL_AMPLIFIERD))

        // Parse bundle names (strip '--with' flags) for settings.json
        // bundlesOutput is like "--with superpowers --with lifeos" or blank
        val bundleNames: List<String> = if (bundlesOutput.isNotBlank()) {
            bundlesOutput.split(" ").filterIndexed { i, _ -> i % 2 == 1 }
        } else {
            listOf("superpowers")
        }

        // Step 6: Stop existing service before reinstalling
        when (platform) {
            RemotePlatform.MACOS_ARM64, RemotePlatform.MACOS_X86 ->
                shell.exec("launchctl bootout gui/\$UID/com.vela.amplifierd 2>/dev/null; true")
            RemotePlatform.LINUX_AMD64, RemotePlatform.LINUX_ARM64 ->
                shell.exec("systemctl --user stop amplifierd 2>/dev/null; true")
        }

        // Step 7: WRITE_CONFIG — use existingToken, NOT a new token
        emit(BootstrapEvent.StepStart(BootstrapStep.WRITE_CONFIG))
        val tmpName = "/tmp/amplifierd_settings_${java.util.UUID.randomUUID()}.json"
        shell.sftpWrite(tmpName, generateSettingsJson(bundleNames, existingToken))
        shell.exec("mkdir -p ~/.amplifierd && mv $tmpName ~/.amplifierd/settings.json")
        emit(BootstrapEvent.StepComplete(BootstrapStep.WRITE_CONFIG))

        // Step 8: INSTALL_SERVICE — regenerate service file with discovered API key, then activate
        emit(BootstrapEvent.StepStart(BootstrapStep.INSTALL_SERVICE))
        when (platform) {
            RemotePlatform.MACOS_ARM64, RemotePlatform.MACOS_X86 -> {
                shell.exec("mkdir -p ~/Library/LaunchAgents")
                shell.sftpWrite(
                    "$homeDir/Library/LaunchAgents/com.vela.amplifierd.plist",
                    generateLaunchdPlist(username, anthropicKey, homeDir),
                )
                shell.exec("launchctl bootstrap gui/\$UID ~/Library/LaunchAgents/com.vela.amplifierd.plist")
                shell.exec("launchctl kickstart -k gui/\$UID/com.vela.amplifierd")
            }
            RemotePlatform.LINUX_AMD64, RemotePlatform.LINUX_ARM64 -> {
                shell.exec("mkdir -p ~/.config/systemd/user")
                shell.sftpWrite(
                    "$homeDir/.config/systemd/user/amplifierd.service",
                    generateSystemdUnit(anthropicKey),
                )
                shell.exec("systemctl --user daemon-reload")
                shell.exec("systemctl --user enable --now amplifierd")
            }
        }
        emit(BootstrapEvent.StepComplete(BootstrapStep.INSTALL_SERVICE))

        // Step 9: HEALTH_CHECK — polls up to 15 times with 2s delay
        emit(BootstrapEvent.StepStart(BootstrapStep.HEALTH_CHECK))
        var healthy = false
        for (attempt in 1..15) {
            val r = shell.exec("curl -fsS http://127.0.0.1:8410/health")
            if (r.exitCode == 0) { healthy = true; break }
            emit(BootstrapEvent.Output("health check attempt $attempt/15"))
            kotlinx.coroutines.delay(2_000)
        }
        if (!healthy) {
            val logCmd = when (platform) {
                RemotePlatform.MACOS_ARM64, RemotePlatform.MACOS_X86 ->
                    "tail -n 20 ~/.amplifierd/stderr.log 2>/dev/null; tail -n 20 ~/.amplifierd/stdout.log 2>/dev/null"
                RemotePlatform.LINUX_AMD64, RemotePlatform.LINUX_ARM64 ->
                    "journalctl --user -n 20 -u amplifierd --no-pager"
            }
            val logs = shell.exec(logCmd).stdout
            registry.updateBootstrapStatus(nodeId, BootstrapStatus.FAILED)
            emit(BootstrapEvent.Failed(BootstrapStep.HEALTH_CHECK, "Health check timed out after 15 attempts. Logs:\n$logs"))
            return@flow
        }
        emit(BootstrapEvent.StepComplete(BootstrapStep.HEALTH_CHECK))

        // Step 10: Complete — update status to RUNNING, emit Complete with existingToken
        registry.updateBootstrapStatus(nodeId, BootstrapStatus.RUNNING)
        emit(BootstrapEvent.Complete("http://$host:8410", existingToken))
    }

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
