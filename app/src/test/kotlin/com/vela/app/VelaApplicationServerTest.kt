package com.vela.app

import com.google.common.truth.Truth.assertThat
import com.vela.app.server.VelaMiniAppServer
import org.junit.Test
import javax.inject.Inject

/**
 * Compile-time shape tests for VelaApplication's mini app server wiring.
 *
 * Instantiating VelaApplication requires a live Android Context and Hilt
 * which are not available in plain unit tests. These tests verify the
 * DI-facing contract — the @Inject field and its type — so that any
 * refactor that breaks Hilt wiring is caught immediately.
 *
 * Run: ./gradlew :app:testDebugUnitTest --tests
 *      "com.vela.app.VelaApplicationServerTest"
 */
class VelaApplicationServerTest {

    @Test
    fun `VelaApplication has an Inject-annotated miniAppServer field of type VelaMiniAppServer`() {
        val field = VelaApplication::class.java.declaredFields
            .find { it.name == "miniAppServer" }

        assertThat(field).isNotNull()
        assertThat(field!!.type).isEqualTo(VelaMiniAppServer::class.java)
        assertThat(field.isAnnotationPresent(Inject::class.java)).isTrue()
    }
}
