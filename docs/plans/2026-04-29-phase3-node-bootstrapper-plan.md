# Phase 3: NodeBootstrapper Implementation Plan

> **Execution:** Use the subagent-driven-development workflow to implement this plan.

**Goal:** Build `NodeBootstrapper.kt` — the SSH orchestration engine that drives the 8-step amplifierd install sequence and emits `Flow<BootstrapEvent>` for live UI progress.

**Architecture:** A non-singleton, per-node-run `@Inject` class that opens a JSch session, runs detect → install uv → install amplifierd → write config (SFTP) → install service (launchd/systemd) → health check → promote. Pure helper functions (token gen, platform detect, JSON/plist/unit generation) are unit-testable; the orchestration itself is exercised against a hand-rolled fake session abstraction.

**Tech Stack:** Kotlin, Hilt, JSch (already on classpath via `RunInNodeTool`), kotlinx.coroutines Flow, JUnit 4 + Google Truth.

---

## Prerequisites

**Phase 2 must be complete and committed.** This plan assumes the following exist:
- `BootstrapEvent` sealed class (with `StepStart`, `StepComplete`, `StepProgress`, `Failed`, `Complete`)
- `BootstrapStep` enum (8 steps: CONNECT, DETECT, INSTALL_UV, INSTALL_AMPLIFIERD, WRITE_CONFIG, INSTALL_SERVICE, HEALTH_CHECK, PROMOTE)
- `BootstrapStatus` enum (NONE, RUNNING, FAILED — stored on `SshNode`)
- `SshNodeRegistry.promoteToAmplifierd(nodeId, url, token)` method
- `SshNodeRegistry.setBootstrapStatus(nodeId, status)` method (or equivalent)

If any are missing, **stop and complete Phase 2 first.** Do not stub them in this plan.

## Audience Note

The implementer:
- Knows Kotlin and Hilt but **not** this codebase
- Should follow `RunInNodeTool.kt` (`app/src/main/kotlin/com/vela/app/ai/tools/RunInNodeTool.kt`) for the JSch usage pattern
- Should follow `VaultToolsTest.kt` (`app/src/test/kotlin/com/vela/app/ai/tools/VaultToolsTest.kt`) for the test style: JUnit 4, Truth `assertThat`, `runTest {}`, hand-rolled fakes (no Mockito, no MockK)

---

## Files

**Create:**
- `app/src/main/kotlin/com/vela/app/ssh/NodeBootstrapper.kt`
- `app/src/test/kotlin/com/vela/app/ssh/NodeBootstrapperTest.kt`

**Modify:**
- `app/src/main/kotlin/com/vela/app/di/AppModule.kt` (add `@Provides` for `NodeBootstrapper`)

---

## Task 1: Skeleton + `generateToken()`

**Files:**
- Create: `app/src/main/kotlin/com/vela/app/ssh/NodeBootstrapper.kt`
- Create: `app/src/test/kotlin/com/vela/app/ssh/NodeBootstrapperTest.kt`

**Step 1: Write the failing test**

Create `app/src/test/kotlin/com/vela/app/ssh/NodeBootstrapperTest.kt`:

```kotlin
package com.vela.app.ssh

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NodeBootstrapperTest {

    // ─── token generation ──────────────────────────────────────────────────

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
}
```

**Step 2: Run test to verify it fails**

Run: `cd /Users/ken/workspace/vela && ./gradlew :app:testDebugUnitTest --tests "com.vela.app.ssh.NodeBootstrapperTest"`
Expected: FAIL with "unresolved reference: NodeBootstrapper".

**Step 3: Write minimal implementation**

Create `app/src/main/kotlin/com/vela/app/ssh/NodeBootstrapper.kt`:

```kotlin
package com.vela.app.ssh

import javax.inject.Inject

class NodeBootstrapper @Inject constructor(
    private val keyManager: SshKeyManager,
    private val registry: SshNodeRegistry,
) {
    // ─── helpers (visible for testing) ─────────────────────────────────────

    internal fun generateToken(): String {
        val bytes = ByteArray(32)
        java.security.SecureRandom.getInstanceStrong().nextBytes(bytes)
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    // exposed for tests via the companion factory
    internal fun generateTokenForTest(): String = generateToken()

    companion object {
        /** Build an instance for unit-testing pure helpers (no Hilt graph needed). */
        fun testInstance(): NodeBootstrapper = NodeBootstrapper(
            keyManager = SshKeyManager(android.content.ContextWrapper(null)),
            registry = throwingRegistry(),
        )

        // Fail loudly if a helper-only test accidentally hits the registry.
        private fun throwingRegistry(): SshNodeRegistry =
            error("registry access not expected in this test path")
    }
}
```

> **Note:** The `testInstance()` shortcut is only acceptable because helper tests don't touch `keyManager` or `registry`. If construction fails on a test environment because `SshKeyManager` requires a real `Context`, switch to constructing `NodeBootstrapper` with `null`-typed fields via a secondary internal constructor exposed only to tests. **Do not** use Mockito.

If construction blows up, replace `testInstance()` with this safer variant:

```kotlin
companion object {
    fun testInstance(): NodeBootstrapper {
        // Reflection-free: create via a private secondary constructor.
        return NodeBootstrapper(keyManager = null, registry = null)
    }
}
```

…and adjust the primary constructor params to `private val keyManager: SshKeyManager?` / `private val registry: SshNodeRegistry?` only if absolutely required. Prefer the first approach.

**Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.vela.app.ssh.NodeBootstrapperTest"`
Expected: PASS (2 tests).

**Step 5: Commit**

```sh
git add app/src/main/kotlin/com/vela/app/ssh/NodeBootstrapper.kt \
        app/src/test/kotlin/com/vela/app/ssh/NodeBootstrapperTest.kt
git commit -m "feat(bootstrapper): scaffold NodeBootstrapper with token generation"
```

---

## Task 2: `BundleChoice` and `RemotePlatform` enums

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/ssh/NodeBootstrapper.kt`
- Modify: `app/src/test/kotlin/com/vela/app/ssh/NodeBootstrapperTest.kt`

**Step 1: Write the failing test**

Append to `NodeBootstrapperTest.kt`:

```kotlin
    // ─── enums ─────────────────────────────────────────────────────────────

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
```

**Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.vela.app.ssh.NodeBootstrapperTest"`
Expected: FAIL — "unresolved reference: BundleChoice / RemotePlatform".

**Step 3: Write minimal implementation**

Append to `NodeBootstrapper.kt` (above the `class NodeBootstrapper` declaration):

```kotlin
enum class BundleChoice(val packageSuffix: String?, val bundleName: String) {
    SUPERPOWERS("amplifierd-bundle-superpowers", "superpowers"),
    LIFEOS("amplifierd-bundle-lifeos", "lifeos"),
    TOOLS_ONLY(null, ""),
}

enum class RemotePlatform { MACOS_ARM64, MACOS_X86, LINUX_AMD64, LINUX_ARM64 }
```

**Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.vela.app.ssh.NodeBootstrapperTest"`
Expected: PASS (6 tests).

**Step 5: Commit**

```sh
git add -u
git commit -m "feat(bootstrapper): add BundleChoice and RemotePlatform enums"
```

---

## Task 3: `detectPlatform()` parsing

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/ssh/NodeBootstrapper.kt`
- Modify: `app/src/test/kotlin/com/vela/app/ssh/NodeBootstrapperTest.kt`

**Step 1: Write the failing test**

Append to `NodeBootstrapperTest.kt`:

```kotlin
    // ─── detectPlatform ────────────────────────────────────────────────────

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
```

**Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.vela.app.ssh.NodeBootstrapperTest"`
Expected: FAIL — `detectPlatformForTest` unresolved.

**Step 3: Write minimal implementation**

Add to `NodeBootstrapper`:

```kotlin
    internal fun detectPlatform(unameOutput: String): RemotePlatform? =
        when (unameOutput.trim()) {
            "Darwin arm64"   -> RemotePlatform.MACOS_ARM64
            "Darwin x86_64"  -> RemotePlatform.MACOS_X86
            "Linux x86_64"   -> RemotePlatform.LINUX_AMD64
            "Linux aarch64"  -> RemotePlatform.LINUX_ARM64
            else             -> null
        }

    internal fun detectPlatformForTest(unameOutput: String) = detectPlatform(unameOutput)
```

**Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.vela.app.ssh.NodeBootstrapperTest"`
Expected: PASS (12 tests).

**Step 5: Commit**

```sh
git add -u
git commit -m "feat(bootstrapper): parse platform from uname -sm output"
```

---

## Task 4: `generateSettingsJson()`

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/ssh/NodeBootstrapper.kt`
- Modify: `app/src/test/kotlin/com/vela/app/ssh/NodeBootstrapperTest.kt`

**Step 1: Write the failing test**

Append:

```kotlin
    // ─── generateSettingsJson ──────────────────────────────────────────────

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
```

**Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.vela.app.ssh.NodeBootstrapperTest"`
Expected: FAIL — `generateSettingsJsonForTest` unresolved.

**Step 3: Write minimal implementation**

Add to `NodeBootstrapper`:

```kotlin
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
```

**Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.vela.app.ssh.NodeBootstrapperTest"`
Expected: PASS (15 tests).

**Step 5: Commit**

```sh
git add -u
git commit -m "feat(bootstrapper): generate amplifierd settings.json"
```

---

## Task 5: `generateLaunchdPlist()`

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/ssh/NodeBootstrapper.kt`
- Modify: `app/src/test/kotlin/com/vela/app/ssh/NodeBootstrapperTest.kt`

**Step 1: Write the failing test**

Append:

```kotlin
    // ─── generateLaunchdPlist ──────────────────────────────────────────────

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
```

**Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.vela.app.ssh.NodeBootstrapperTest"`
Expected: FAIL — `generateLaunchdPlistForTest` unresolved.

**Step 3: Write minimal implementation**

Add to `NodeBootstrapper`:

```kotlin
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
```

**Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.vela.app.ssh.NodeBootstrapperTest"`
Expected: PASS (18 tests).

**Step 5: Commit**

```sh
git add -u
git commit -m "feat(bootstrapper): generate macOS launchd plist"
```

---

## Task 6: `generateSystemdUnit()`

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/ssh/NodeBootstrapper.kt`
- Modify: `app/src/test/kotlin/com/vela/app/ssh/NodeBootstrapperTest.kt`

**Step 1: Write the failing test**

Append:

```kotlin
    // ─── generateSystemdUnit ───────────────────────────────────────────────

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
```

**Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.vela.app.ssh.NodeBootstrapperTest"`
Expected: FAIL — `generateSystemdUnitForTest` unresolved.

**Step 3: Write minimal implementation**

Add to `NodeBootstrapper`:

```kotlin
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
```

**Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.vela.app.ssh.NodeBootstrapperTest"`
Expected: PASS (20 tests).

**Step 5: Commit**

```sh
git add -u
git commit -m "feat(bootstrapper): generate Linux systemd user unit"
```

---

## Task 7: Build the `uv tool install` command

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/ssh/NodeBootstrapper.kt`
- Modify: `app/src/test/kotlin/com/vela/app/ssh/NodeBootstrapperTest.kt`

**Step 1: Write the failing test**

Append:

```kotlin
    // ─── buildUvInstallCommand ─────────────────────────────────────────────

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
```

**Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.vela.app.ssh.NodeBootstrapperTest"`
Expected: FAIL — `buildUvInstallCommandForTest` unresolved.

**Step 3: Write minimal implementation**

Add to `NodeBootstrapper`:

```kotlin
    internal fun buildUvInstallCommand(bundle: BundleChoice): String = buildString {
        append("export PATH=\"\$HOME/.local/bin:\$PATH\" && uv tool install")
        append(" --with git+https://github.com/kenotron/vela#subdirectory=plugins/amplifierd-vela")
        if (bundle.packageSuffix != null) {
            append(" --with ${bundle.packageSuffix}")
        }
        append(" git+https://github.com/microsoft/amplifierd")
    }

    internal fun buildUvInstallCommandForTest(bundle: BundleChoice) = buildUvInstallCommand(bundle)
```

**Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.vela.app.ssh.NodeBootstrapperTest"`
Expected: PASS (23 tests).

**Step 5: Commit**

```sh
git add -u
git commit -m "feat(bootstrapper): build uv tool install command"
```

---

## Task 8: Introduce `RemoteShell` abstraction + fake

This task adds an internal seam so tests can drive the orchestration without real JSch sessions.

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/ssh/NodeBootstrapper.kt`
- Modify: `app/src/test/kotlin/com/vela/app/ssh/NodeBootstrapperTest.kt`

**Step 1: Write the failing test**

Append (top-level, outside the existing test class is fine, but keep inside the file):

```kotlin
    // ─── RemoteShell fake ──────────────────────────────────────────────────

    /**
     * Hand-rolled fake for the internal [RemoteShell] interface.
     * Records every command and returns scripted (stdout, exitCode) pairs.
     */
    private class FakeRemoteShell : RemoteShell {
        val commands = mutableListOf<String>()
        val sftpWrites = mutableListOf<Pair<String, String>>()  // (path, contents)
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
    fun fakeRemoteShell_recordsCommandsAndReturnsScriptedResponses() = kotlinx.coroutines.test.runTest {
        val shell = FakeRemoteShell()
        shell.responses["uname -sm"] = "Darwin arm64\n" to 0
        shell.responses["false"] = "" to 1

        assertThat(shell.exec("uname -sm").stdout.trim()).isEqualTo("Darwin arm64")
        assertThat(shell.exec("uname -sm").exitCode).isEqualTo(0)
        assertThat(shell.exec("false").exitCode).isEqualTo(1)
        assertThat(shell.commands).hasSize(3)
    }
```

Also wrap `runTest` import at top of the test file when first needed:

```kotlin
import kotlinx.coroutines.test.runTest
```

**Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.vela.app.ssh.NodeBootstrapperTest"`
Expected: FAIL — `RemoteShell` unresolved.

**Step 3: Write minimal implementation**

Add to `NodeBootstrapper.kt` (top-level, above `class NodeBootstrapper`):

```kotlin
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
```

**Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.vela.app.ssh.NodeBootstrapperTest"`
Expected: PASS (24 tests).

**Step 5: Commit**

```sh
git add -u
git commit -m "feat(bootstrapper): add RemoteShell abstraction for testability"
```

---

## Task 9: JSch-backed `RemoteShell` implementation

This wires the abstraction to real JSch — no orchestration logic yet. We mark the constructor with `@VisibleForTesting`-style commenting so production callers always go through `bootstrap()`.

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/ssh/NodeBootstrapper.kt`

**Step 1: Write the failing test**

No unit test — this is real JSch integration that requires a server. Skip directly to implementation. (We'll exercise it indirectly via the orchestration tests, which use `FakeRemoteShell`.)

**Step 2: Implementation**

Add to `NodeBootstrapper.kt` (top-level, below the `RemoteShell` interface):

```kotlin
internal class JschRemoteShell(
    private val session: com.jcraft.jsch.Session,
) : RemoteShell {

    override suspend fun exec(command: String): RemoteShell.Result =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val channel = session.openChannel("exec") as com.jcraft.jsch.ChannelExec
            channel.setCommand(command)
            val stdout = java.io.ByteArrayOutputStream()
            val stderr = java.io.ByteArrayOutputStream()
            channel.outputStream = stdout
            channel.setErrStream(stderr)
            channel.connect()
            // Wait until channel closes (timeout: 5 minutes for long installs).
            val deadline = System.currentTimeMillis() + 5 * 60_000L
            while (!channel.isClosed && System.currentTimeMillis() < deadline) Thread.sleep(50)
            // Drain any final bytes.
            Thread.sleep(50)
            val out = stdout.toString(Charsets.UTF_8.name())
            val err = stderr.toString(Charsets.UTF_8.name())
            val exit = channel.exitStatus
            channel.disconnect()
            // Combine streams for caller convenience; orchestration logs both.
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
```

Add a private factory method to `NodeBootstrapper`:

```kotlin
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
```

**Step 3: Verify it compiles**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

**Step 4: Re-run all tests**

Run: `./gradlew :app:testDebugUnitTest --tests "com.vela.app.ssh.NodeBootstrapperTest"`
Expected: PASS (24 tests).

**Step 5: Commit**

```sh
git add -u
git commit -m "feat(bootstrapper): JSch-backed RemoteShell with SFTP write"
```

---

## Task 10: `bootstrap()` orchestration — happy path

This is the largest task. It implements the 8-step `Flow<BootstrapEvent>` against `RemoteShell`, with no failure handling (added in Task 11).

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/ssh/NodeBootstrapper.kt`
- Modify: `app/src/test/kotlin/com/vela/app/ssh/NodeBootstrapperTest.kt`

**Step 1: Write the failing test**

Append to `NodeBootstrapperTest.kt` (you may want a new helper method to construct a real `NodeBootstrapper` with a fake registry — see below).

First add a fake registry helper at the bottom of the test file:

```kotlin
    /** Hand-rolled fake of the Phase 2 SshNodeRegistry surface used by NodeBootstrapper. */
    private class FakeRegistry : SshNodeRegistry(dao = throwingDao()) {
        val promotedTo = mutableListOf<Triple<String, String, String>>()  // (id, url, token)
        val statusUpdates = mutableListOf<Pair<String, String>>()         // (id, status)

        override suspend fun promoteToAmplifierd(nodeId: String, url: String, token: String) {
            promotedTo.add(Triple(nodeId, url, token))
        }
        override suspend fun setBootstrapStatus(nodeId: String, status: BootstrapStatus) {
            statusUpdates.add(nodeId to status.name)
        }
    }

    companion object {
        private fun throwingDao(): com.vela.app.data.db.SshNodeDao =
            error("DAO must not be touched in unit tests")
    }
```

> If `SshNodeRegistry` is `final` in Phase 2 (it currently is — `class SshNodeRegistry @Inject constructor(private val dao: SshNodeDao)`), then **before** writing this test, **change the class to `open class`** and mark `promoteToAmplifierd` and `setBootstrapStatus` as `open`. This is a one-line change in `SshNodeRegistry.kt`. Commit it as a separate prep commit:
>
> ```sh
> git commit -m "feat(bootstrapper): open SshNodeRegistry methods for test fakes"
> ```

Now add the orchestration test:

```kotlin
    // ─── bootstrap() orchestration — happy path ────────────────────────────

    @Test
    fun bootstrap_happyPath_emitsAllStepEventsAndPromotes() = runTest {
        val shell = FakeRemoteShell()
        // Default: every command succeeds with empty output.
        shell.responses["uname -sm"] = "Linux x86_64\n" to 0
        // Health check returns 200 immediately on first poll.
        shell.responses["curl -fsS http://127.0.0.1:8410/health"] = "ok" to 0
        // Tailscale not present (returns nothing, exit 1).
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

        // Each of the 8 steps should emit StepStart and StepComplete.
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

        // Final event is Complete with the URL and token.
        val last = events.last()
        assertThat(last).isInstanceOf(BootstrapEvent.Complete::class.java)
        val complete = last as BootstrapEvent.Complete
        assertThat(complete.url).isEqualTo("http://1.2.3.4:8410")
        assertThat(complete.token).isNotEmpty()

        // Registry was promoted exactly once.
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

        // SFTP write went to /tmp first (atomic-write convention).
        val sftpPaths = shell.sftpWrites.map { it.first }
        assertThat(sftpPaths).contains(sftpPaths.first { it.startsWith("/tmp/amplifierd_settings_") })
        // The settings JSON contains the bundle name.
        val settingsContents = shell.sftpWrites.first { it.first.contains("amplifierd_settings_") }.second
        assertThat(settingsContents).contains("\"superpowers\"")

        // mv command was issued to move /tmp/... → ~/.amplifierd/settings.json
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

        // Systemd unit file written via SFTP.
        val unitWrite = shell.sftpWrites.firstOrNull { it.first.endsWith("amplifierd.service") }
        assertThat(unitWrite).isNotNull()
        assertThat(unitWrite!!.first).isEqualTo("/home/alice/.config/systemd/user/amplifierd.service")
        assertThat(unitWrite.second).contains("[Service]")

        // systemctl commands ran.
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
```

Add necessary imports to the test file:

```kotlin
import kotlinx.coroutines.flow.toList
```

**Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.vela.app.ssh.NodeBootstrapperTest"`
Expected: FAIL — `bootstrapWithShell` unresolved, plus `BootstrapEvent`/`BootstrapStep` references depend on Phase 2 being committed.

**Step 3: Write minimal implementation**

Add to `NodeBootstrapper`:

```kotlin
    /** Public entry: opens a real JSch session, then delegates to [bootstrapWithShell]. */
    suspend fun bootstrap(
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
            ?: error("DETECT failed before failure handling implemented")  // Task 11 fixes
        emit(BootstrapEvent.StepComplete(BootstrapStep.DETECT))

        // Step 3: INSTALL_UV
        emit(BootstrapEvent.StepStart(BootstrapStep.INSTALL_UV))
        shell.exec("which uv >/dev/null 2>&1 || curl -LsSf https://astral.sh/uv/install.sh | sh")
        emit(BootstrapEvent.StepComplete(BootstrapStep.INSTALL_UV))

        // Step 4: INSTALL_AMPLIFIERD
        emit(BootstrapEvent.StepStart(BootstrapStep.INSTALL_AMPLIFIERD))
        shell.exec(buildUvInstallCommand(bundle))
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
                    "/Users/$username/Library/LaunchAgents/com.vela.amplifierd.plist",
                    generateLaunchdPlist(username, anthropicKey),
                )
                shell.exec("launchctl bootout gui/\$UID/com.vela.amplifierd 2>/dev/null || true")
                shell.exec("launchctl bootstrap gui/\$UID ~/Library/LaunchAgents/com.vela.amplifierd.plist")
                shell.exec("launchctl kickstart -k gui/\$UID/com.vela.amplifierd")
            }
            RemotePlatform.LINUX_AMD64, RemotePlatform.LINUX_ARM64 -> {
                shell.exec("mkdir -p ~/.config/systemd/user")
                shell.sftpWrite(
                    "/home/$username/.config/systemd/user/amplifierd.service",
                    generateSystemdUnit(anthropicKey),
                )
                shell.exec("loginctl enable-linger $username 2>/dev/null || true")
                shell.exec("systemctl --user daemon-reload")
                shell.exec("systemctl --user enable --now amplifierd.service")
            }
        }
        emit(BootstrapEvent.StepComplete(BootstrapStep.INSTALL_SERVICE))

        // Step 7: HEALTH_CHECK — single attempt for happy path; Task 11 adds polling+fail.
        emit(BootstrapEvent.StepStart(BootstrapStep.HEALTH_CHECK))
        val health = shell.exec("curl -fsS http://127.0.0.1:8410/health")
        if (health.exitCode != 0) error("HEALTH failed before failure handling implemented")
        emit(BootstrapEvent.StepComplete(BootstrapStep.HEALTH_CHECK))

        // Step 8: PROMOTE
        emit(BootstrapEvent.StepStart(BootstrapStep.PROMOTE))
        val tailscale = shell.exec("tailscale ip -4 2>/dev/null")
        val tsIp = tailscale.stdout.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
        val url = if (tailscale.exitCode == 0 && !tsIp.isNullOrEmpty())
            "http://$tsIp:8410" else "http://$host:8410"
        registry.promoteToAmplifierd(nodeId, url, token)
        registry.setBootstrapStatus(nodeId, BootstrapStatus.RUNNING)
        emit(BootstrapEvent.StepComplete(BootstrapStep.PROMOTE))

        emit(BootstrapEvent.Complete(url, token))
    }
```

**Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.vela.app.ssh.NodeBootstrapperTest"`
Expected: PASS (28 tests).

**Step 5: Commit**

```sh
git add -u
git commit -m "feat(bootstrapper): orchestrate 8-step bootstrap happy path"
```

---

## Task 11: Failure handling — non-zero exit and health-check timeout

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/ssh/NodeBootstrapper.kt`
- Modify: `app/src/test/kotlin/com/vela/app/ssh/NodeBootstrapperTest.kt`

**Step 1: Write the failing tests**

Append:

```kotlin
    // ─── bootstrap() failure handling ──────────────────────────────────────

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
        assertThat(failed[0].message).contains("Unsupported platform")
        assertThat(failed[0].message).contains("FreeBSD amd64")
        // No promotion happened.
        assertThat(registry.promotedTo).isEmpty()
        // Status set to FAILED.
        assertThat(registry.statusUpdates).contains("n" to BootstrapStatus.FAILED.name)
    }

    @Test
    fun bootstrap_uvInstallNonZeroExit_emitsFailed() = runTest {
        val shell = FakeRemoteShell()
        shell.responses["uname -sm"] = "Linux x86_64\n" to 0
        // The dynamic uv command — match by prefix via defaultResponse fallback for the rest.
        // Fail any command starting with "export PATH=...&& uv tool install".
        // Easiest approach: set defaultResponse to (" ", 1) AFTER matching uname.
        // Better: compute the exact command and put it in responses.
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
        assertThat(failed[0].message).contains("exit 1")
        assertThat(failed[0].message).contains("package not found")
    }

    @Test
    fun bootstrap_healthCheckTimesOutAfter15Attempts_emitsFailedWithLogs() = runTest {
        val shell = FakeRemoteShell()
        shell.responses["uname -sm"] = "Linux x86_64\n" to 0
        // Health check always fails.
        shell.responses["curl -fsS http://127.0.0.1:8410/health"] = "" to 7
        // Log retrieval returns scripted output.
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
        assertThat(failed[0].message).contains("amplifierd crashed: missing key")
        // Confirm 15 attempts were made.
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
        assertThat(failed[0].message).contains("stderr line")
        assertThat(failed[0].message).contains("stdout line")
    }
```

> **Note on test runtime:** the polling test will sleep 2s × 15 = 30s in production. Use `kotlinx.coroutines.test.runTest` with a virtual time `delay()` in the implementation (`delay(2_000)`, NOT `Thread.sleep`) so tests skip the wait.

**Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.vela.app.ssh.NodeBootstrapperTest"`
Expected: FAIL — current orchestration uses `error()` instead of emitting `BootstrapEvent.Failed`; health check is single-attempt, not polling.

**Step 3: Write the implementation**

Replace the orchestration body with proper failure handling. In `bootstrapWithShell`:

1. **DETECT failure:** replace `error(...)` with:
   ```kotlin
   if (platform == null) {
       registry.setBootstrapStatus(nodeId, BootstrapStatus.FAILED)
       emit(BootstrapEvent.Failed(BootstrapStep.DETECT, "Unsupported platform: ${unameResult.stdout.trim()}"))
       return@flow
   }
   ```

2. **INSTALL_UV / INSTALL_AMPLIFIERD non-zero exit:** wrap each `shell.exec(...)` that must succeed in a helper:
   ```kotlin
   suspend fun kotlinx.coroutines.flow.FlowCollector<BootstrapEvent>.runOrFail(
       step: BootstrapStep, command: String,
   ): Boolean {
       val r = shell.exec(command)
       if (r.exitCode != 0) {
           registry.setBootstrapStatus(nodeId, BootstrapStatus.FAILED)
           emit(BootstrapEvent.Failed(step, "exit ${r.exitCode}: ${r.stdout.trim()}"))
           return false
       }
       return true
   }
   ```
   Then:
   ```kotlin
   if (!runOrFail(BootstrapStep.INSTALL_UV, "which uv >/dev/null 2>&1 || curl -LsSf https://astral.sh/uv/install.sh | sh")) return@flow
   ```
   …and similarly for `INSTALL_AMPLIFIERD`.

3. **HEALTH_CHECK polling loop:**
   ```kotlin
   emit(BootstrapEvent.StepStart(BootstrapStep.HEALTH_CHECK))
   var healthy = false
   repeat(15) { attempt ->
       val r = shell.exec("curl -fsS http://127.0.0.1:8410/health")
       if (r.exitCode == 0) { healthy = true; return@repeat }
       emit(BootstrapEvent.StepProgress(BootstrapStep.HEALTH_CHECK, "attempt ${attempt + 1}/15"))
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
       registry.setBootstrapStatus(nodeId, BootstrapStatus.FAILED)
       emit(BootstrapEvent.Failed(BootstrapStep.HEALTH_CHECK, "Health check timed out after 15 attempts. Logs:\n$logs"))
       return@flow
   }
   emit(BootstrapEvent.StepComplete(BootstrapStep.HEALTH_CHECK))
   ```

   > Important: the `repeat` block uses `return@repeat` to break early on success — this is the lambda's local return, NOT the function's. Verify by running the happy-path test — if it now hangs, you missed this.

4. **`break` from `repeat`:** Kotlin's `repeat` does not support `break`. Refactor to a labeled `for` loop:
   ```kotlin
   var healthy = false
   for (attempt in 1..15) {
       val r = shell.exec("curl -fsS http://127.0.0.1:8410/health")
       if (r.exitCode == 0) { healthy = true; break }
       emit(BootstrapEvent.StepProgress(BootstrapStep.HEALTH_CHECK, "attempt $attempt/15"))
       kotlinx.coroutines.delay(2_000)
   }
   ```

**Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.vela.app.ssh.NodeBootstrapperTest"`
Expected: PASS (32 tests). The 15-attempt test should complete in well under 1 second thanks to `runTest`'s virtual time advancing through `delay()` calls.

**Step 5: Commit**

```sh
git add -u
git commit -m "feat(bootstrapper): handle DETECT/install failures and health-check timeout"
```

---

## Task 12: Tailscale IP detection (explicit test)

**Files:**
- Modify: `app/src/test/kotlin/com/vela/app/ssh/NodeBootstrapperTest.kt`

The implementation already supports this from Task 10; this task adds dedicated coverage.

**Step 1: Write the failing test**

Append:

```kotlin
    // ─── Tailscale IP detection ────────────────────────────────────────────

    @Test
    fun bootstrap_promotesUsingTailscaleIp_whenAvailable() = runTest {
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
        assertThat(complete.url).isEqualTo("http://100.64.1.42:8410")
        assertThat(registry.promotedTo[0].second).isEqualTo("http://100.64.1.42:8410")
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
```

**Step 2: Run test to verify it fails or passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.vela.app.ssh.NodeBootstrapperTest"`
Expected: PASS — the Task 10 implementation already filters blank lines. If FAIL, double-check the `lineSequence().firstOrNull { it.isNotBlank() }` step in `bootstrapWithShell`.

**Step 3: Commit**

```sh
git add -u
git commit -m "feat(bootstrapper): cover Tailscale IP detection explicitly"
```

---

## Task 13: Wire `NodeBootstrapper` into Hilt (`AppModule`)

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/di/AppModule.kt`

**Step 1: Read the existing module**

Run: `grep -n "provideSshNodeRegistry\|provideSshKeyManager" app/src/main/kotlin/com/vela/app/di/AppModule.kt`
Expected output (already verified):
```
97:    fun provideSshKeyManager(@ApplicationContext ctx: Context): SshKeyManager = SshKeyManager(ctx)
100:    fun provideSshNodeRegistry(dao: SshNodeDao): SshNodeRegistry = SshNodeRegistry(dao)
```

**Step 2: Add the `@Provides`**

Open `app/src/main/kotlin/com/vela/app/di/AppModule.kt`, find the line containing `fun provideSshNodeRegistry(dao: SshNodeDao): SshNodeRegistry = SshNodeRegistry(dao)`, and add **immediately after it**:

```kotlin
    // NodeBootstrapper carries per-run state — NOT @Singleton.
    @Provides
    fun provideNodeBootstrapper(
        keyManager: SshKeyManager,
        registry: SshNodeRegistry,
    ): com.vela.app.ssh.NodeBootstrapper =
        com.vela.app.ssh.NodeBootstrapper(keyManager, registry)
```

**Step 3: Verify the build**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

**Step 4: Run the full ssh test suite**

Run: `./gradlew :app:testDebugUnitTest --tests "com.vela.app.ssh.*"`
Expected: PASS, all NodeBootstrapper tests still pass.

**Step 5: Commit**

```sh
git add -u
git commit -m "feat(bootstrapper): provide NodeBootstrapper via Hilt"
```

---

## Final Verification

Run the complete unit-test suite to ensure no regressions elsewhere:

```sh
./gradlew :app:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL with no failing tests.

Confirm the public API matches the design:

```sh
grep -n "suspend fun bootstrap\|enum class BundleChoice\|enum class RemotePlatform" \
  app/src/main/kotlin/com/vela/app/ssh/NodeBootstrapper.kt
```

Expected output:
```
class NodeBootstrapper @Inject constructor(
suspend fun bootstrap(
enum class BundleChoice(val packageSuffix: String?, val bundleName: String) {
enum class RemotePlatform { MACOS_ARM64, MACOS_X86, LINUX_AMD64, LINUX_ARM64 }
```

---

## Notes for the Implementer

- **Do NOT use Mockito or MockK.** This codebase uses hand-rolled fakes only. See `RecordingRepositoryTest.kt`, `VaultToolsTest.kt`, `BashTool` tests for patterns.
- **All JSch I/O must be wrapped in `withContext(Dispatchers.IO) { ... }`** — see `RunInNodeTool.kt` for the canonical pattern.
- **The `internal` visibility on helpers** (`generateToken`, `detectPlatform`, etc.) is deliberate — keeps the public API minimal but allows tests in the same module to verify behavior directly.
- **`NodeBootstrapper` is NOT `@Singleton`.** Each bootstrap run is logically per-node-per-attempt; injecting fresh instances avoids accidental state leakage.
- **If a step in this plan reveals a missing Phase 2 type** (e.g., `BootstrapEvent.StepProgress` doesn't exist), STOP and update the Phase 2 plan/code first — do not invent it here.
- **Idempotency:** every shell command in this plan is already idempotent (`mkdir -p`, `bootout … || true`, `enable-linger … || true`, `enable --now`). Re-running `bootstrap()` on an already-bootstrapped node should succeed and update the config/service in place. There is no explicit test for this in the plan because each step's idempotency is covered by its `0`-exit-code happy-path test, but a manual end-to-end verification on a real macOS and Linux host is recommended before merging.
- **Commit cadence:** 13 commits total. If any step takes more than ~5 minutes, stop and ask for help — the design is meant to be bite-sized.
