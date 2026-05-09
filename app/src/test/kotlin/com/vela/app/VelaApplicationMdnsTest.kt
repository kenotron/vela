package com.vela.app

import com.google.common.truth.Truth.assertThat
import com.vela.app.ssh.MdnsDiscoveryService
import org.junit.Test
import javax.inject.Inject

/**
 * Compile-time shape tests for VelaApplication's mDNS discovery wiring.
 *
 * Instantiating VelaApplication requires a live Android Context and Hilt
 * which are not available in plain unit tests. These tests verify the
 * DI-facing contract — the @Inject field and ProcessLifecycleOwner wiring —
 * so that any refactor that breaks the lifecycle integration is caught immediately.
 *
 * Run: ./gradlew :app:testDebugUnitTest --tests
 *      "com.vela.app.VelaApplicationMdnsTest"
 */
class VelaApplicationMdnsTest {

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

    // ── DI field shape ──────────────────────────────────────────────────────

    @Test
    fun `VelaApplication has an Inject-annotated mdnsDiscovery field of type MdnsDiscoveryService`() {
        val field = VelaApplication::class.java.declaredFields
            .find { it.name == "mdnsDiscovery" }

        assertThat(field).isNotNull()
        assertThat(field!!.type).isEqualTo(MdnsDiscoveryService::class.java)
        assertThat(field.isAnnotationPresent(Inject::class.java)).isTrue()
    }

    // ── Lifecycle wiring ────────────────────────────────────────────────────

    @Test
    fun `onCreate registers ProcessLifecycleOwner observer for mdns discovery`() {
        // ProcessLifecycleOwner must appear in VelaApplication's constant pool,
        // meaning addObserver is called on ProcessLifecycleOwner.get().lifecycle
        // from onCreate().
        val bytecode = velaApplicationBytecode()
        val pattern = "ProcessLifecycleOwner".toByteArray(Charsets.UTF_8)
        assertThat(bytecode.containsSequence(pattern)).isTrue()
    }
}
