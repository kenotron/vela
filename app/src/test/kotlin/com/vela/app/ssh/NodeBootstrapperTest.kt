package com.vela.app.ssh

import com.google.common.truth.Truth.assertThat
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
}
