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
}
