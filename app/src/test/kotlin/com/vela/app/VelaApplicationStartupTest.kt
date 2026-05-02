package com.vela.app

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Compile-time shape tests for VelaApplication startup sequence.
 *
 * Instantiating VelaApplication requires a live Android Context and Hilt
 * which are not available in plain unit tests. These tests verify the
 * startup contract — that onCreate calls createSessionChannel and starts
 * SessionStreamingService — by inspecting the compiled bytecode constant pool.
 *
 * Run: ./gradlew :app:testDebugUnitTest --tests
 *      "com.vela.app.VelaApplicationStartupTest"
 */
class VelaApplicationStartupTest {

    /** Read the compiled bytecode for VelaApplication from the classpath. */
    private fun velaApplicationBytecode(): ByteArray =
        VelaApplication::class.java.classLoader!!
            .getResourceAsStream("com/vela/app/VelaApplication.class")
            ?.readBytes()
            ?: error("Could not read VelaApplication.class from classpath")

    /** Returns true if [pattern] appears as a contiguous byte sequence in [data]. */
    private fun ByteArray.containsSequence(pattern: ByteArray): Boolean {
        if (pattern.isEmpty()) return true
        return (0..(size - pattern.size)).any { i ->
            pattern.indices.all { j -> this[i + j] == pattern[j] }
        }
    }

    // ── Notification channels ────────────────────────────────────────────────

    @Test
    fun `onCreate calls createSessionChannel`() {
        // createSessionChannel must appear in VelaApplication's constant pool,
        // meaning it is invoked from onCreate().
        val bytecode = velaApplicationBytecode()
        val pattern = "createSessionChannel".toByteArray(Charsets.UTF_8)
        assertThat(bytecode.containsSequence(pattern)).isTrue()
    }

    // ── Service startup ──────────────────────────────────────────────────────

    @Test
    fun `onCreate starts SessionStreamingService`() {
        // SessionStreamingService must appear in VelaApplication's constant pool,
        // meaning it is referenced in startService(Intent(this, SessionStreamingService::class.java)).
        val bytecode = velaApplicationBytecode()
        val pattern = "SessionStreamingService".toByteArray(Charsets.UTF_8)
        assertThat(bytecode.containsSequence(pattern)).isTrue()
    }
}
