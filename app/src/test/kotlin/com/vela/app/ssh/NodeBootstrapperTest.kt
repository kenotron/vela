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
}
