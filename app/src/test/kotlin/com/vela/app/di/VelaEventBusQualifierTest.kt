package com.vela.app.di

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import javax.inject.Qualifier

/**
 * Verifies that the @VelaEventBus qualifier annotation exists, is an annotation class,
 * and is correctly meta-annotated with @Qualifier for Hilt DI use.
 *
 * Run: ./gradlew :app:testDebugUnitTest --tests "com.vela.app.di.VelaEventBusQualifierTest"
 */
class VelaEventBusQualifierTest {

    @Test
    fun `VelaEventBus is an annotation class`() {
        val clazz = VelaEventBus::class.java
        assertThat(clazz.isAnnotation).isTrue()
    }

    @Test
    fun `VelaEventBus carries the Qualifier meta-annotation`() {
        val clazz = VelaEventBus::class.java
        assertThat(clazz.isAnnotationPresent(Qualifier::class.java)).isTrue()
    }
}
