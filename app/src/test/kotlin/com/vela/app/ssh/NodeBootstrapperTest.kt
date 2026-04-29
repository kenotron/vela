package com.vela.app.ssh

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test

class NodeBootstrapperTest {

    // ── token generation ──────────────────────────────────────────────────────

    @Test
    fun generateToken_producesUrlSafeBase64WithoutPadding() {
        val sut = NodeBootstrapper.testInstance()

        val token = sut.generateTokenForTest()

        assertThat(token).isNotEmpty()
        // URL-safe base64 alphabet: A-Z a-z 0-9 - _
        assertThat(token).matches("^[A-Za-z0-9_-]+$")
        // 32 random bytes → 43 chars unpadded base64
        assertThat(token).hasLength(43)
    }

    @Test
    fun generateToken_producesDifferentTokensEachCall() {
        val sut = NodeBootstrapper.testInstance()

        val a = sut.generateTokenForTest()
        val b = sut.generateTokenForTest()

        assertThat(a).isNotEqualTo(b)
    }

    // ── enums ─────────────────────────────────────────────────────────────────

    @Test
    fun bundleChoice_superpowers_hasCorrectPackageAndName() {
        assertThat(BundleChoice.SUPERPOWERS.packageSuffix).isEqualTo("amplifierd-bundle-superpowers")
        assertThat(BundleChoice.SUPERPOWERS.bundleName).isEqualTo("superpowers")
    }

    @Test
    fun bundleChoice_lifeos_hasCorrectPackageAndName() {
        assertThat(BundleChoice.LIFEOS.packageSuffix).isEqualTo("amplifierd-bundle-lifeos")
        assertThat(BundleChoice.LIFEOS.bundleName).isEqualTo("lifeos")
    }

    @Test
    fun bundleChoice_toolsOnly_hasNullPackageAndEmptyName() {
        assertThat(BundleChoice.TOOLS_ONLY.packageSuffix).isNull()
        assertThat(BundleChoice.TOOLS_ONLY.bundleName).isEmpty()
    }

    @Test
    fun remotePlatform_allFourValuesExist() {
        val all = RemotePlatform.values().toSet()
        assertThat(all).containsExactly(
            RemotePlatform.MACOS_ARM64,
            RemotePlatform.MACOS_X86,
            RemotePlatform.LINUX_AMD64,
            RemotePlatform.LINUX_ARM64,
        )
    }

    // ── detectPlatform ────────────────────────────────────────────────────────

    @Test
    fun detectPlatform_darwinArm64() {
        val sut = NodeBootstrapper.testInstance()
        assertThat(sut.detectPlatformForTest("Darwin arm64")).isEqualTo(RemotePlatform.MACOS_ARM64)
    }

    @Test
    fun detectPlatform_darwinX86() {
        val sut = NodeBootstrapper.testInstance()
        assertThat(sut.detectPlatformForTest("Darwin x86_64")).isEqualTo(RemotePlatform.MACOS_X86)
    }

    @Test
    fun detectPlatform_linuxAmd64() {
        val sut = NodeBootstrapper.testInstance()
        assertThat(sut.detectPlatformForTest("Linux x86_64")).isEqualTo(RemotePlatform.LINUX_AMD64)
    }

    @Test
    fun detectPlatform_linuxArm64() {
        val sut = NodeBootstrapper.testInstance()
        assertThat(sut.detectPlatformForTest("Linux aarch64")).isEqualTo(RemotePlatform.LINUX_ARM64)
    }

    @Test
    fun detectPlatform_trimsWhitespaceAndIgnoresTrailingNewline() {
        val sut = NodeBootstrapper.testInstance()
        assertThat(sut.detectPlatformForTest("  Darwin arm64\n")).isEqualTo(RemotePlatform.MACOS_ARM64)
    }

    @Test
    fun detectPlatform_unknownReturnsNull() {
        val sut = NodeBootstrapper.testInstance()
        assertThat(sut.detectPlatformForTest("FreeBSD amd64")).isNull()
    }

    // ── generateSettingsJson ──────────────────────────────────────────────────

    @Test
    fun generateSettingsJson_superpowers_includesAllRequiredFields() {
        val sut = NodeBootstrapper.testInstance()

        val json = sut.generateSettingsJsonForTest(BundleChoice.SUPERPOWERS, token = "TOKEN_ABC")
        val parsed = org.json.JSONObject(json)

        assertThat(parsed.getString("host")).isEqualTo("0.0.0.0")
        assertThat(parsed.getInt("port")).isEqualTo(8410)
        assertThat(parsed.getString("log_level")).isEqualTo("info")
        val bundles = parsed.getJSONArray("bundles")
        assertThat(bundles.length()).isEqualTo(1)
        assertThat(bundles.getString(0)).isEqualTo("superpowers")
        assertThat(parsed.getJSONArray("disabled_plugins").length()).isEqualTo(0)
        assertThat(parsed.getJSONObject("vela").getString("auth_token")).isEqualTo("TOKEN_ABC")
    }

    @Test
    fun generateSettingsJson_toolsOnly_hasEmptyBundles() {
        val sut = NodeBootstrapper.testInstance()

        val json = sut.generateSettingsJsonForTest(BundleChoice.TOOLS_ONLY, token = "T")
        val parsed = org.json.JSONObject(json)

        assertThat(parsed.getJSONArray("bundles").length()).isEqualTo(0)
    }

    @Test
    fun generateSettingsJson_lifeos_hasLifeosBundle() {
        val sut = NodeBootstrapper.testInstance()

        val json = sut.generateSettingsJsonForTest(BundleChoice.LIFEOS, token = "T")
        val parsed = org.json.JSONObject(json)
        val bundles = parsed.getJSONArray("bundles")

        assertThat(bundles.getString(0)).isEqualTo("lifeos")
    }

    // ── generateLaunchdPlist ──────────────────────────────────────────────────

    @Test
    fun generateLaunchdPlist_containsLabelAndUsernameAndKey() {
        val sut = NodeBootstrapper.testInstance()

        val plist = sut.generateLaunchdPlistForTest(username = "alice", anthropicKey = "sk-ant-XYZ")

        assertThat(plist).contains("<key>Label</key><string>com.vela.amplifierd</string>")
        assertThat(plist).contains("/Users/alice/.local/bin/amplifierd")
        assertThat(plist).contains("/Users/alice/.local/bin:/usr/local/bin:/usr/bin:/bin")
        assertThat(plist).contains("<key>ANTHROPIC_API_KEY</key><string>sk-ant-XYZ</string>")
        assertThat(plist).contains("/Users/alice/.amplifierd/stdout.log")
        assertThat(plist).contains("/Users/alice/.amplifierd/stderr.log")
    }

    @Test
    fun generateLaunchdPlist_containsServeArgs() {
        val sut = NodeBootstrapper.testInstance()

        val plist = sut.generateLaunchdPlistForTest(username = "u", anthropicKey = "k")

        assertThat(plist).contains("<string>serve</string>")
        assertThat(plist).contains("<string>--host</string><string>0.0.0.0</string>")
        assertThat(plist).contains("<string>--port</string><string>8410</string>")
    }

    @Test
    fun generateLaunchdPlist_isXmlPlist() {
        val sut = NodeBootstrapper.testInstance()

        val plist = sut.generateLaunchdPlistForTest(username = "u", anthropicKey = "k")

        assertThat(plist).startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        assertThat(plist).contains("<!DOCTYPE plist PUBLIC")
        assertThat(plist).contains("<plist version=\"1.0\">")
        assertThat(plist).contains("<key>RunAtLoad</key><true/>")
        assertThat(plist).contains("<key>KeepAlive</key><true/>")
    }

    // ── generateSystemdUnit ───────────────────────────────────────────────────

    @Test
    fun generateSystemdUnit_containsExecStartAndKey() {
        val sut = NodeBootstrapper.testInstance()

        val unit = sut.generateSystemdUnitForTest(anthropicKey = "sk-ant-XYZ")

        assertThat(unit).contains("ExecStart=%h/.local/bin/amplifierd serve --host 0.0.0.0 --port 8410")
        assertThat(unit).contains("Environment=\"ANTHROPIC_API_KEY=sk-ant-XYZ\"")
        assertThat(unit).contains("Environment=\"PATH=%h/.local/bin:/usr/local/bin:/usr/bin:/bin\"")
    }

    @Test
    fun generateSystemdUnit_hasRequiredSections() {
        val sut = NodeBootstrapper.testInstance()

        val unit = sut.generateSystemdUnitForTest(anthropicKey = "k")

        assertThat(unit).contains("[Unit]")
        assertThat(unit).contains("[Service]")
        assertThat(unit).contains("[Install]")
        assertThat(unit).contains("Description=Vela amplifierd daemon")
        assertThat(unit).contains("After=network-online.target")
        assertThat(unit).contains("Type=simple")
        assertThat(unit).contains("Restart=on-failure")
        assertThat(unit).contains("RestartSec=3")
        assertThat(unit).contains("WantedBy=default.target")
    }

    // ── buildUvInstallCommand ─────────────────────────────────────────────────

    @Test
    fun buildUvInstallCommand_superpowers_includesBundlePackage() {
        val sut = NodeBootstrapper.testInstance()

        val cmd = sut.buildUvInstallCommandForTest(BundleChoice.SUPERPOWERS)

        assertThat(cmd).startsWith("export PATH=\"\$HOME/.local/bin:\$PATH\" && uv tool install")
        assertThat(cmd).contains("--with git+https://github.com/kenotron/vela#subdirectory=plugins/amplifierd-vela")
        assertThat(cmd).contains("--with amplifierd-bundle-superpowers")
        assertThat(cmd).contains("git+https://github.com/microsoft/amplifierd")
    }

    @Test
    fun buildUvInstallCommand_toolsOnly_omitsBundle() {
        val sut = NodeBootstrapper.testInstance()

        val cmd = sut.buildUvInstallCommandForTest(BundleChoice.TOOLS_ONLY)

        assertThat(cmd).contains("--with git+https://github.com/kenotron/vela#subdirectory=plugins/amplifierd-vela")
        assertThat(cmd).doesNotContain("amplifierd-bundle-")
        assertThat(cmd).contains("git+https://github.com/microsoft/amplifierd")
    }

    @Test
    fun buildUvInstallCommand_lifeos_includesLifeosPackage() {
        val sut = NodeBootstrapper.testInstance()

        val cmd = sut.buildUvInstallCommandForTest(BundleChoice.LIFEOS)

        assertThat(cmd).contains("--with amplifierd-bundle-lifeos")
    }

    // ── RemoteShell fake ──────────────────────────────────────────────────────

    /**
     * Hand-rolled fake for the internal [RemoteShell] interface.
     * Records every command and returns scripted (stdout, exitCode) pairs.
     */
    private class FakeRemoteShell : RemoteShell {
        val commands = mutableListOf<String>()
        val sftpWrites = mutableListOf<Pair<String, String>>()
        var responses: MutableMap<String, Pair<String, Int>> = mutableMapOf()
        /** Default response for any command not in [responses]. */
        var defaultResponse: Pair<String, Int> = "" to 0

        override suspend fun exec(command: String): RemoteShell.Result {
            commands.add(command)
            val (out, exit) = responses[command] ?: defaultResponse
            return RemoteShell.Result(stdout = out, exitCode = exit)
        }
        override suspend fun sftpWrite(remotePath: String, contents: String) {
            sftpWrites.add(remotePath to contents)
        }
        override fun close() = Unit
    }

    @Test
    fun fakeRemoteShell_recordsCommandsAndReturnsScriptedResponses() = runTest {
        val shell = FakeRemoteShell()
        shell.responses["uname -sm"] = "Darwin arm64\n" to 0
        shell.responses["false"] = "" to 1

        assertThat(shell.exec("uname -sm").stdout.trim()).isEqualTo("Darwin arm64")
        assertThat(shell.exec("uname -sm").exitCode).isEqualTo(0)
        assertThat(shell.exec("false").exitCode).isEqualTo(1)
        assertThat(shell.commands).hasSize(3)
    }

    // ── FakeRegistry ──────────────────────────────────────────────────────────

    /** Hand-rolled fake of the Phase 2 SshNodeRegistry surface used by NodeBootstrapper. */
    private class FakeRegistry : SshNodeRegistry(dao = throwingDao()) {
        val promotedTo = mutableListOf<Triple<String, String, String>>() // (id, url, token)
        val statusUpdates = mutableListOf<Pair<String, String>>()        // (id, status)

        override suspend fun promoteToAmplifierd(nodeId: String, url: String, token: String) {
            promotedTo.add(Triple(nodeId, url, token))
        }
        override suspend fun updateBootstrapStatus(nodeId: String, status: BootstrapStatus) {
            statusUpdates.add(nodeId to status.name)
        }
    }

    // ── bootstrap() orchestration — happy path ────────────────────────────────

    @Test
    fun bootstrap_happyPath_emitsAllStepEventsAndPromotes() = runTest {
        val shell = FakeRemoteShell()
        shell.responses["uname -sm"] = "Linux x86_64\n" to 0
        shell.responses["curl -fsS http://127.0.0.1:8410/health"] = "ok" to 0
        shell.responses["tailscale ip -4 2>/dev/null"] = "" to 1

        val registry = FakeRegistry()
        val sut = NodeBootstrapper(
            keyManager = SshKeyManager(android.content.ContextWrapper(null)),
            registry = registry,
        )

        val events = sut.bootstrapWithShell(
            shell = shell,
            nodeId = "node-1",
            host = "1.2.3.4",
            username = "alice",
            bundle = BundleChoice.SUPERPOWERS,
            anthropicKey = "sk-ant-XYZ",
        ).toList()

        val starts = events.filterIsInstance<BootstrapEvent.StepStart>().map { it.step }
        assertThat(starts).containsExactly(
            BootstrapStep.CONNECT,
            BootstrapStep.DETECT,
            BootstrapStep.INSTALL_UV,
            BootstrapStep.INSTALL_AMPLIFIERD,
            BootstrapStep.WRITE_CONFIG,
            BootstrapStep.INSTALL_SERVICE,
            BootstrapStep.HEALTH_CHECK,
            BootstrapStep.PROMOTE,
        ).inOrder()

        val last = events.last()
        assertThat(last).isInstanceOf(BootstrapEvent.Complete::class.java)
        val complete = last as BootstrapEvent.Complete
        assertThat(complete.url).isEqualTo("http://1.2.3.4:8410")
        assertThat(complete.token).isNotEmpty()

        assertThat(registry.promotedTo).hasSize(1)
        assertThat(registry.promotedTo[0].first).isEqualTo("node-1")
        assertThat(registry.promotedTo[0].second).isEqualTo("http://1.2.3.4:8410")
    }

    @Test
    fun bootstrap_writesSettingsConfigToTempThenAtomicallyMoves() = runTest {
        val shell = FakeRemoteShell()
        shell.responses["uname -sm"] = "Linux x86_64\n" to 0
        shell.responses["curl -fsS http://127.0.0.1:8410/health"] = "ok" to 0
        shell.responses["tailscale ip -4 2>/dev/null"] = "" to 1

        val sut = NodeBootstrapper(
            keyManager = SshKeyManager(android.content.ContextWrapper(null)),
            registry = FakeRegistry(),
        )
        sut.bootstrapWithShell(
            shell = shell,
            nodeId = "n",
            host = "h",
            username = "u",
            bundle = BundleChoice.SUPERPOWERS,
            anthropicKey = "k",
        ).toList()

        val sftpPaths = shell.sftpWrites.map { it.first }
        assertThat(sftpPaths).contains(sftpPaths.first { it.startsWith("/tmp/amplifierd_settings_") })
        val settingsContents = shell.sftpWrites.first { it.first.contains("amplifierd_settings_") }.second
        assertThat(settingsContents).contains("\"superpowers\"")

        assertThat(shell.commands.any {
            it.contains("mkdir -p ~/.amplifierd") && it.contains("mv /tmp/amplifierd_settings_") && it.contains("~/.amplifierd/settings.json")
        }).isTrue()
    }

    @Test
    fun bootstrap_linux_writesSystemdUnitAndRunsSystemctl() = runTest {
        val shell = FakeRemoteShell()
        shell.responses["uname -sm"] = "Linux x86_64\n" to 0
        shell.responses["curl -fsS http://127.0.0.1:8410/health"] = "ok" to 0
        shell.responses["tailscale ip -4 2>/dev/null"] = "" to 1

        val sut = NodeBootstrapper(
            keyManager = SshKeyManager(android.content.ContextWrapper(null)),
            registry = FakeRegistry(),
        )
        sut.bootstrapWithShell(
            shell = shell, nodeId = "n", host = "h", username = "alice",
            bundle = BundleChoice.TOOLS_ONLY, anthropicKey = "k",
        ).toList()

        val unitWrite = shell.sftpWrites.firstOrNull { it.first.endsWith("amplifierd.service") }
        assertThat(unitWrite).isNotNull()
        assertThat(unitWrite!!.first).isEqualTo("/home/alice/.config/systemd/user/amplifierd.service")
        assertThat(unitWrite.second).contains("[Service]")

        assertThat(shell.commands.any { it.contains("systemctl --user daemon-reload") }).isTrue()
        assertThat(shell.commands.any { it.contains("systemctl --user enable --now amplifierd.service") }).isTrue()
    }

    @Test
    fun bootstrap_macos_writesPlistAndRunsLaunchctl() = runTest {
        val shell = FakeRemoteShell()
        shell.responses["uname -sm"] = "Darwin arm64\n" to 0
        shell.responses["curl -fsS http://127.0.0.1:8410/health"] = "ok" to 0
        shell.responses["tailscale ip -4 2>/dev/null"] = "" to 1

        val sut = NodeBootstrapper(
            keyManager = SshKeyManager(android.content.ContextWrapper(null)),
            registry = FakeRegistry(),
        )
        sut.bootstrapWithShell(
            shell = shell, nodeId = "n", host = "h", username = "bob",
            bundle = BundleChoice.TOOLS_ONLY, anthropicKey = "k",
        ).toList()

        val plistWrite = shell.sftpWrites.firstOrNull { it.first.endsWith("com.vela.amplifierd.plist") }
        assertThat(plistWrite).isNotNull()
        assertThat(plistWrite!!.first).isEqualTo("/Users/bob/Library/LaunchAgents/com.vela.amplifierd.plist")
        assertThat(plistWrite.second).contains("<plist version=\"1.0\">")

        assertThat(shell.commands.any { it.contains("launchctl bootstrap gui/\$UID") }).isTrue()
        assertThat(shell.commands.any { it.contains("launchctl kickstart -k gui/\$UID/com.vela.amplifierd") }).isTrue()
    }

    // ── bootstrap() failure handling ──────────────────────────────────────────

    @Test
    fun bootstrap_unsupportedPlatform_emitsFailedAndStops() = runTest {
        val shell = FakeRemoteShell()
        shell.responses["uname -sm"] = "FreeBSD amd64\n" to 0

        val registry = FakeRegistry()
        val sut = NodeBootstrapper(
            keyManager = SshKeyManager(android.content.ContextWrapper(null)),
            registry = registry,
        )

        val events = sut.bootstrapWithShell(
            shell = shell, nodeId = "n", host = "h", username = "u",
            bundle = BundleChoice.TOOLS_ONLY, anthropicKey = "k",
        ).toList()

        val failed = events.filterIsInstance<BootstrapEvent.Failed>()
        assertThat(failed).hasSize(1)
        assertThat(failed[0].step).isEqualTo(BootstrapStep.DETECT)
        assertThat(failed[0].error).contains("Unsupported platform")
        assertThat(failed[0].error).contains("FreeBSD amd64")
        assertThat(registry.promotedTo).isEmpty()
        assertThat(registry.statusUpdates).contains("n" to BootstrapStatus.FAILED.name)
    }

    @Test
    fun bootstrap_uvInstallNonZeroExit_emitsFailed() = runTest {
        val shell = FakeRemoteShell()
        shell.responses["uname -sm"] = "Linux x86_64\n" to 0
        val sut = NodeBootstrapper(
            keyManager = SshKeyManager(android.content.ContextWrapper(null)),
            registry = FakeRegistry(),
        )
        val uvCmd = sut.buildUvInstallCommandForTest(BundleChoice.TOOLS_ONLY)
        shell.responses[uvCmd] = "E: package not found" to 1

        val events = sut.bootstrapWithShell(
            shell = shell, nodeId = "n", host = "h", username = "u",
            bundle = BundleChoice.TOOLS_ONLY, anthropicKey = "k",
        ).toList()

        val failed = events.filterIsInstance<BootstrapEvent.Failed>()
        assertThat(failed).hasSize(1)
        assertThat(failed[0].step).isEqualTo(BootstrapStep.INSTALL_AMPLIFIERD)
        assertThat(failed[0].error).contains("exit 1")
        assertThat(failed[0].error).contains("package not found")
    }

    @Test
    fun bootstrap_healthCheckTimesOutAfter15Attempts_emitsFailedWithLogs() = runTest {
        val shell = FakeRemoteShell()
        shell.responses["uname -sm"] = "Linux x86_64\n" to 0
        shell.responses["curl -fsS http://127.0.0.1:8410/health"] = "" to 7
        shell.responses["journalctl --user -n 20 -u amplifierd --no-pager"] =
            "ERROR: amplifierd crashed: missing key" to 0

        val sut = NodeBootstrapper(
            keyManager = SshKeyManager(android.content.ContextWrapper(null)),
            registry = FakeRegistry(),
        )

        val events = sut.bootstrapWithShell(
            shell = shell, nodeId = "n", host = "h", username = "u",
            bundle = BundleChoice.TOOLS_ONLY, anthropicKey = "k",
        ).toList()

        val failed = events.filterIsInstance<BootstrapEvent.Failed>()
        assertThat(failed).hasSize(1)
        assertThat(failed[0].step).isEqualTo(BootstrapStep.HEALTH_CHECK)
        assertThat(failed[0].error).contains("amplifierd crashed: missing key")
        val healthCount = shell.commands.count { it == "curl -fsS http://127.0.0.1:8410/health" }
        assertThat(healthCount).isEqualTo(15)
    }

    @Test
    fun bootstrap_healthCheckTimeout_macosUsesTailLogCommand() = runTest {
        val shell = FakeRemoteShell()
        shell.responses["uname -sm"] = "Darwin arm64\n" to 0
        shell.responses["curl -fsS http://127.0.0.1:8410/health"] = "" to 7
        shell.responses["tail -n 20 ~/.amplifierd/stderr.log 2>/dev/null; tail -n 20 ~/.amplifierd/stdout.log 2>/dev/null"] =
            "stderr line\nstdout line" to 0

        val sut = NodeBootstrapper(
            keyManager = SshKeyManager(android.content.ContextWrapper(null)),
            registry = FakeRegistry(),
        )

        val events = sut.bootstrapWithShell(
            shell = shell, nodeId = "n", host = "h", username = "u",
            bundle = BundleChoice.TOOLS_ONLY, anthropicKey = "k",
        ).toList()

        val failed = events.filterIsInstance<BootstrapEvent.Failed>()
        assertThat(failed[0].error).contains("stderr line")
        assertThat(failed[0].error).contains("stdout line")
    }

    companion object {
        private fun throwingDao(): com.vela.app.data.db.SshNodeDao =
            object : com.vela.app.data.db.SshNodeDao {
                override fun getAllNodes(): kotlinx.coroutines.flow.Flow<List<com.vela.app.data.db.SshNodeEntity>> =
                    throw AssertionError("SshNodeDao must not be accessed in NodeBootstrapper unit tests")
                override suspend fun insert(node: com.vela.app.data.db.SshNodeEntity) =
                    throw AssertionError("SshNodeDao must not be accessed in NodeBootstrapper unit tests")
                override suspend fun delete(id: String) =
                    throw AssertionError("SshNodeDao must not be accessed in NodeBootstrapper unit tests")
                override suspend fun getById(id: String): com.vela.app.data.db.SshNodeEntity? =
                    throw AssertionError("SshNodeDao must not be accessed in NodeBootstrapper unit tests")
                override suspend fun updateBootstrapStatus(id: String, status: String) =
                    throw AssertionError("SshNodeDao must not be accessed in NodeBootstrapper unit tests")
                override suspend fun promoteToAmplifierd(
                    id: String, type: String, url: String, token: String, status: String,
                ) = throw AssertionError("SshNodeDao must not be accessed in NodeBootstrapper unit tests")
            }
    }
}
