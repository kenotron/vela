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
    fun generateSettingsJson_containsRequiredMinimalFields() {
        val sut = NodeBootstrapper.testInstance()

        val json = sut.generateSettingsJsonForTest(BundleChoice.SUPERPOWERS, token = "TOKEN_ABC")
        val parsed = org.json.JSONObject(json)

        assertThat(parsed.getString("host")).isEqualTo("0.0.0.0")
        assertThat(parsed.getInt("port")).isEqualTo(8410)
        assertThat(parsed.getString("log_level")).isEqualTo("info")
    }

    @Test
    fun generateSettingsJson_doesNotContainBundlesOrVelaKeys() {
        // Fix: DaemonSettings rejects 'bundles' (must be dict not list) and 'vela' (extra field).
        // Bundles come from ~/.amplifier/settings.yaml; auth token via VELA_AUTH_TOKEN env var.
        val sut = NodeBootstrapper.testInstance()

        val json = sut.generateSettingsJsonForTest(BundleChoice.SUPERPOWERS, token = "TOKEN_ABC")
        val parsed = org.json.JSONObject(json)

        assertThat(parsed.has("bundles")).isFalse()
        assertThat(parsed.has("vela")).isFalse()
        assertThat(parsed.has("disabled_plugins")).isFalse()
    }

    @Test
    fun generateSettingsJson_minimalFormatAppliesRegardlessOfBundle() {
        val sut = NodeBootstrapper.testInstance()

        for (bundle in BundleChoice.values()) {
            val json = sut.generateSettingsJsonForTest(bundle, token = "T")
            val parsed = org.json.JSONObject(json)
            assertThat(parsed.has("bundles")).isFalse()
            assertThat(parsed.has("vela")).isFalse()
        }
    }

    @Test
    fun generateLaunchdPlist_containsVelaAuthToken() {
        // Fix: auth token delivered via VELA_AUTH_TOKEN env var in the plist.
        val sut = NodeBootstrapper.testInstance()

        val plist = sut.generateLaunchdPlistForTest(username = "alice", anthropicKey = "sk-ant-XYZ", token = "mytoken123")

        assertThat(plist).contains("<key>VELA_AUTH_TOKEN</key><string>mytoken123</string>")
    }

    @Test
    fun generateSystemdUnit_containsVelaAuthToken() {
        // Fix: auth token delivered via VELA_AUTH_TOKEN env var in the systemd unit.
        val sut = NodeBootstrapper.testInstance()

        val unit = sut.generateSystemdUnitForTest(anthropicKey = "sk-ant-XYZ", token = "mytoken123")

        assertThat(unit).contains("Environment=\"VELA_AUTH_TOKEN=mytoken123\"")
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
        val machineIds = mutableMapOf<String, String>()                  // (id, machineId)

        override suspend fun promoteToAmplifierd(nodeId: String, url: String, tailscaleUrl: String, token: String, machineId: String) {
            promotedTo.add(Triple(nodeId, url, token))
        }
        override suspend fun updateBootstrapStatus(nodeId: String, status: BootstrapStatus) {
            statusUpdates.add(nodeId to status.name)
        }
        override suspend fun updateMachineId(nodeId: String, machineId: String) {
            machineIds[nodeId] = machineId
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
    fun bootstrap_writesSettingsConfigViaExecWrite() = runTest {
        // Bug 3: WRITE_CONFIG now uses exec-channel base64 write instead of sftpWrite.
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

        // No SFTP writes — file is written via exec channel only
        assertThat(shell.sftpWrites).isEmpty()
        // execWrite issues a command that base64-decodes content directly to the target path
        val settingsCmd = shell.commands.firstOrNull {
            it.contains("settings.json") && it.contains("base64 -d")
        }
        assertThat(settingsCmd).isNotNull()
        assertThat(settingsCmd!!).contains("/home/u/.amplifierd/settings.json")
    }

    @Test
    fun bootstrap_linux_writesSystemdUnitAndRunsSystemctl() = runTest {
        // Bug 3: service file is written via execWrite (exec channel + base64), not sftpWrite.
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

        // No SFTP writes — file is written via exec channel
        assertThat(shell.sftpWrites).isEmpty()
        // execWrite command targets the correct service file path
        val unitCmd = shell.commands.firstOrNull {
            it.contains("amplifierd.service") && it.contains("base64 -d")
        }
        assertThat(unitCmd).isNotNull()
        assertThat(unitCmd!!).contains("/home/alice/.config/systemd/user/amplifierd.service")

        assertThat(shell.commands.any { it.contains("systemctl --user daemon-reload") }).isTrue()
        assertThat(shell.commands.any { it.contains("systemctl --user enable --now amplifierd.service") }).isTrue()
    }

    @Test
    fun bootstrap_macos_writesPlistAndRunsLaunchctl() = runTest {
        // Bug 3: plist is written via execWrite (exec channel + base64), not sftpWrite.
        // Bug 4: launchctl now uses $(id -u) instead of $UID, with GUI-domain fallback.
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

        // No SFTP writes — file is written via exec channel
        assertThat(shell.sftpWrites).isEmpty()
        // execWrite command targets the correct plist path
        val plistCmd = shell.commands.firstOrNull {
            it.contains("com.vela.amplifierd.plist") && it.contains("base64 -d")
        }
        assertThat(plistCmd).isNotNull()
        assertThat(plistCmd!!).contains("/Users/bob/Library/LaunchAgents/com.vela.amplifierd.plist")

        // Bug 4: commands use $(id -u) not $UID
        assertThat(shell.commands.any { it.contains("launchctl bootstrap gui/\$(id -u)") }).isTrue()
        assertThat(shell.commands.any { it.contains("launchctl kickstart -k gui/\$(id -u)/com.vela.amplifierd") }).isTrue()
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

    // ── LAN URL is canonical (design A1) ────────────────────────────────
    // BootstrapEvent.Complete.url always holds the LAN URL used during bootstrap.
    // EndpointResolver handles Tailscale preference at runtime.

    @Test
    fun bootstrap_completesWithLanUrl_whenTailscaleAlsoPresent() = runTest {
        val shell = FakeRemoteShell()
        shell.responses["uname -sm"] = "Linux x86_64\n" to 0
        shell.responses["curl -fsS http://127.0.0.1:8410/health"] = "ok" to 0
        shell.responses["tailscale ip -4 2>/dev/null"] = "100.64.1.42\n" to 0

        val registry = FakeRegistry()
        val sut = NodeBootstrapper(
            keyManager = SshKeyManager(android.content.ContextWrapper(null)),
            registry = registry,
        )

        val events = sut.bootstrapWithShell(
            shell = shell, nodeId = "n", host = "1.2.3.4", username = "u",
            bundle = BundleChoice.TOOLS_ONLY, anthropicKey = "k",
        ).toList()

        val complete = events.last() as BootstrapEvent.Complete
        assertThat(complete.url).isEqualTo("http://1.2.3.4:8410")
        assertThat(registry.promotedTo[0].second).isEqualTo("http://1.2.3.4:8410")
    }

    @Test
    fun bootstrap_fallsBackToHostIp_whenTailscaleEmptyOutputButZeroExit() = runTest {
        val shell = FakeRemoteShell()
        shell.responses["uname -sm"] = "Linux x86_64\n" to 0
        shell.responses["curl -fsS http://127.0.0.1:8410/health"] = "ok" to 0
        // Edge case: tailscale exits 0 but with empty output.
        shell.responses["tailscale ip -4 2>/dev/null"] = "\n" to 0

        val registry = FakeRegistry()
        val sut = NodeBootstrapper(
            keyManager = SshKeyManager(android.content.ContextWrapper(null)),
            registry = registry,
        )
        val events = sut.bootstrapWithShell(
            shell = shell, nodeId = "n", host = "1.2.3.4", username = "u",
            bundle = BundleChoice.TOOLS_ONLY, anthropicKey = "k",
        ).toList()

        val complete = events.last() as BootstrapEvent.Complete
        assertThat(complete.url).isEqualTo("http://1.2.3.4:8410")
    }

    // ── Bug 1: reliable installed check via uv tool list ──────────────────────────────

    @Test
    fun bootstrap_skipsInstall_whenUvToolListReportsAmplifierd() = runTest {
        // Bug 1: the check must use `uv tool list | grep -c '^amplifierd'` returning "1",
        // NOT `amplifierd --version` (which always emits usage text to stdout via Click).
        val shell = FakeRemoteShell()
        shell.responses["uname -sm"] = "Linux x86_64\n" to 0
        shell.responses["export PATH=\"\$HOME/.local/bin:\$PATH\" && uv tool list 2>/dev/null | grep -c '^amplifierd'"] =
            "1\n" to 0
        shell.responses["curl -fsS http://127.0.0.1:8410/health"] = "ok" to 0
        shell.responses["tailscale ip -4 2>/dev/null"] = "" to 1

        val sut = NodeBootstrapper(
            keyManager = SshKeyManager(android.content.ContextWrapper(null)),
            registry = FakeRegistry(),
        )
        val events = sut.bootstrapWithShell(
            shell = shell, nodeId = "n", host = "h", username = "u",
            bundle = BundleChoice.SUPERPOWERS, anthropicKey = "k",
        ).toList()

        // Install command must NOT be issued when already installed
        val installCmd = sut.buildUvInstallCommandForTest(BundleChoice.SUPERPOWERS)
        assertThat(shell.commands).doesNotContain(installCmd)
        // "already installed" message must appear
        val outputs = events.filterIsInstance<BootstrapEvent.Output>().map { it.line }
        assertThat(outputs.any { "already installed" in it }).isTrue()
    }

    @Test
    fun bootstrap_proceedsWithInstall_whenUvToolListReturnsZero() = runTest {
        // Bug 1 counter-case: grep -c returning "0" means NOT installed — must install.
        val shell = FakeRemoteShell()
        shell.responses["uname -sm"] = "Linux x86_64\n" to 0
        shell.responses["export PATH=\"\$HOME/.local/bin:\$PATH\" && uv tool list 2>/dev/null | grep -c '^amplifierd'"] =
            "0\n" to 1   // grep -c returns exit 1 when count is 0
        shell.responses["curl -fsS http://127.0.0.1:8410/health"] = "ok" to 0
        shell.responses["tailscale ip -4 2>/dev/null"] = "" to 1

        val sut = NodeBootstrapper(
            keyManager = SshKeyManager(android.content.ContextWrapper(null)),
            registry = FakeRegistry(),
        )
        sut.bootstrapWithShell(
            shell = shell, nodeId = "n", host = "h", username = "u",
            bundle = BundleChoice.SUPERPOWERS, anthropicKey = "k",
        ).toList()

        // Install command MUST be issued when not already installed
        val installCmd = sut.buildUvInstallCommandForTest(BundleChoice.SUPERPOWERS)
        assertThat(shell.commands).contains(installCmd)
    }

    // ── Bug 2: exception propagation from bootstrapWithShell ──────────────────────────

    @Test
    fun bootstrapWithShell_exceptionFromShell_propagatesToCaller() = runTest {
        // Bug 2 documents: bootstrapWithShell() does NOT catch exceptions — they propagate
        // up to bootstrap() where the fix (catch block) converts them to BootstrapEvent.Failed.
        val throwingShell = object : RemoteShell {
            override suspend fun exec(command: String): RemoteShell.Result =
                when {
                    "uname" in command -> RemoteShell.Result("Linux x86_64\n", 0)
                    "echo" in command  -> RemoteShell.Result("", 0)
                    "which uv" in command || "curl" in command -> RemoteShell.Result("", 0)
                    "uv tool list" in command -> RemoteShell.Result("0\n", 1)
                    "uv tool install" in command ->
                        throw RuntimeException("SSH exec failed: channel reset by peer")
                    else -> RemoteShell.Result("", 0)
                }
            override suspend fun sftpWrite(remotePath: String, contents: String) = Unit
            override fun close() = Unit
        }

        val sut = NodeBootstrapper(
            keyManager = SshKeyManager(android.content.ContextWrapper(null)),
            registry = FakeRegistry(),
        )
        var caught: Throwable? = null
        try {
            sut.bootstrapWithShell(
                shell = throwingShell, nodeId = "n", host = "h", username = "u",
                bundle = BundleChoice.TOOLS_ONLY, anthropicKey = "k",
            ).toList()
        } catch (e: Exception) {
            caught = e
        }

        // bootstrapWithShell propagates the exception; the public bootstrap() catches it.
        assertThat(caught).isNotNull()
        assertThat(caught!!.message).contains("channel reset by peer")
    }

    // ── Bug 4: macOS launchctl $(id -u) and fallback ──────────────────────────────────

    @Test
    fun bootstrap_macos_launchctlFallback_whenBootstrapFails() = runTest {
        // Bug 4: when launchctl bootstrap exits non-zero (and not error 5), fall back to
        // starting amplifierd directly in the background.
        val shell = FakeRemoteShell()
        shell.responses["uname -sm"] = "Darwin arm64\n" to 0
        // Simulate launchd GUI domain unavailable (exit 1, no "Bootstrap failed: 5")
        shell.responses["launchctl bootstrap gui/\$(id -u) ~/Library/LaunchAgents/com.vela.amplifierd.plist 2>&1"] =
            "Bootstrap failed: 125" to 1
        shell.responses["curl -fsS http://127.0.0.1:8410/health"] = "ok" to 0
        shell.responses["tailscale ip -4 2>/dev/null"] = "" to 1

        val sut = NodeBootstrapper(
            keyManager = SshKeyManager(android.content.ContextWrapper(null)),
            registry = FakeRegistry(),
        )
        val events = sut.bootstrapWithShell(
            shell = shell, nodeId = "n", host = "h", username = "bob",
            bundle = BundleChoice.TOOLS_ONLY, anthropicKey = "k",
        ).toList()

        // Fallback: should start amplifierd directly via nohup
        assertThat(shell.commands.any { "nohup" in it && "amplifierd serve" in it }).isTrue()
        // Warning output emitted
        val outputs = events.filterIsInstance<BootstrapEvent.Output>().map { it.line }
        assertThat(outputs.any { "launchd GUI domain unavailable" in it }).isTrue()
        // kickstart must NOT be called in the fallback path
        assertThat(shell.commands.none { "kickstart" in it }).isTrue()
    }

    @Test
    fun bootstrap_macos_launchctlSucceeds_runsKickstart() = runTest {
        // Bug 4: when launchctl bootstrap succeeds (exit 0), kickstart should still run.
        val shell = FakeRemoteShell()
        shell.responses["uname -sm"] = "Darwin arm64\n" to 0
        shell.responses["curl -fsS http://127.0.0.1:8410/health"] = "ok" to 0
        shell.responses["tailscale ip -4 2>/dev/null"] = "" to 1
        // Default response (exit 0) for bootstrap → success path

        val sut = NodeBootstrapper(
            keyManager = SshKeyManager(android.content.ContextWrapper(null)),
            registry = FakeRegistry(),
        )
        sut.bootstrapWithShell(
            shell = shell, nodeId = "n", host = "h", username = "bob",
            bundle = BundleChoice.TOOLS_ONLY, anthropicKey = "k",
        ).toList()

        // kickstart must be called on success
        assertThat(shell.commands.any { "launchctl kickstart -k gui/\$(id -u)/com.vela.amplifierd" in it }).isTrue()
        // nohup fallback must NOT be called
        assertThat(shell.commands.none { "nohup" in it }).isTrue()
    }

    // ── machine_id caching after bootstrap ───────────────────────────────────────────────────────

    @Test
    fun bootstrap_cachesMachineId_whenHealthReturnsIt() = runTest {
        // RED: fetchMachineId does not exist yet — this test will fail to compile until it is added.
        val shell = FakeRemoteShell()
        shell.responses["uname -sm"] = "Linux x86_64\n" to 0
        shell.responses["curl -fsS http://127.0.0.1:8410/health"] = "ok" to 0
        shell.responses["tailscale ip -4 2>/dev/null"] = "" to 1

        val registry = FakeRegistry()
        // Override fetchMachineId to return a known value (bypasses real HTTP in unit tests).
        val sut = object : NodeBootstrapper(
            keyManager = SshKeyManager(android.content.ContextWrapper(null)),
            registry = registry,
        ) {
            override suspend fun fetchMachineId(url: String, token: String): String? =
                "65E872B0-AAAA-BBBB-CCCC-DDDDDDDD1234"
        }

        val events = sut.bootstrapWithShell(
            shell = shell,
            nodeId = "node-1",
            host = "1.2.3.4",
            username = "alice",
            bundle = BundleChoice.SUPERPOWERS,
            anthropicKey = "sk-ant-XYZ",
        ).toList()

        // machine_id must be written to the registry
        assertThat(registry.machineIds["node-1"]).isEqualTo("65E872B0-AAAA-BBBB-CCCC-DDDDDDDD1234")

        // Bootstrap log must include the "✓ machine_id cached" output event
        val outputs = events.filterIsInstance<BootstrapEvent.Output>().map { it.line }
        assertThat(outputs.any { "✓ machine_id cached: 65E872B0" in it }).isTrue()
    }

    @Test
    fun bootstrap_doesNotFail_whenMachineIdLookupReturnsNull() = runTest {
        // RED: fetchMachineId does not exist yet — this test verifies graceful failure.
        val shell = FakeRemoteShell()
        shell.responses["uname -sm"] = "Linux x86_64\n" to 0
        shell.responses["curl -fsS http://127.0.0.1:8410/health"] = "ok" to 0
        shell.responses["tailscale ip -4 2>/dev/null"] = "" to 1

        val registry = FakeRegistry()
        // Override fetchMachineId to return null (simulates unreachable health endpoint).
        val sut = object : NodeBootstrapper(
            keyManager = SshKeyManager(android.content.ContextWrapper(null)),
            registry = registry,
        ) {
            override suspend fun fetchMachineId(url: String, token: String): String? = null
        }

        val events = sut.bootstrapWithShell(
            shell = shell, nodeId = "n", host = "h", username = "u",
            bundle = BundleChoice.TOOLS_ONLY, anthropicKey = "k",
        ).toList()

        // Bootstrap must still succeed — machine_id caching is best-effort
        assertThat(events.last()).isInstanceOf(BootstrapEvent.Complete::class.java)
        assertThat(registry.machineIds).isEmpty()
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
                    id: String, type: String, url: String, tailscaleUrl: String, token: String, status: String, machineId: String, endpoints: String,
                ) = throw AssertionError("SshNodeDao must not be accessed in NodeBootstrapper unit tests")
                override suspend fun updateConnection(id: String, label: String, hosts: String, port: Int, username: String, workspaceDir: String) =
                    throw AssertionError("SshNodeDao must not be accessed in NodeBootstrapper unit tests")
                override suspend fun updateMachineId(id: String, machineId: String) =
                    throw AssertionError("SshNodeDao must not be accessed in NodeBootstrapper unit tests")
                override suspend fun updateEndpoints(id: String, endpoints: String) =
                    throw AssertionError("SshNodeDao must not be accessed in NodeBootstrapper unit tests")
                override suspend fun updateLastKnownReachable(id: String, reachable: Int) =
                    throw AssertionError("SshNodeDao must not be accessed in NodeBootstrapper unit tests")
            }
    }
}
