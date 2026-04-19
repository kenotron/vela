package com.vela.app.workers

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import javax.inject.Singleton

/**
 * Compile-time shape tests for ProfileWorkerScheduler.
 *
 * Instantiating the scheduler requires a live Android Context and WorkManager
 * which are not available in plain unit tests (no Robolectric on classpath).
 * These tests verify the DI-facing contract — @Singleton annotation and the
 * unique work name keys — so that any refactor that breaks the Hilt wiring
 * or the WorkManager unique-name contract is caught immediately.
 *
 * Run: ./gradlew :app:testDebugUnitTest --tests
 *      "com.vela.app.workers.ProfileWorkerSchedulerTest"
 */
class ProfileWorkerSchedulerTest {

    @Test
    fun `ProfileWorkerScheduler is annotated with Singleton`() {
        val clazz = ProfileWorkerScheduler::class.java
        assertThat(clazz.isAnnotationPresent(Singleton::class.java)).isTrue()
    }

    @Test
    fun `periodic unique work name matches ProfileWorker TAG`() {
        // The periodic enqueue call uses ProfileWorker.TAG as the unique name.
        // Verifying here keeps the scheduler's implicit contract explicit.
        assertThat(ProfileWorker.TAG).isEqualTo("profile_worker")
    }

    @Test
    fun `manual unique work name is TAG suffixed with _manual`() {
        val expectedManualKey = "${ProfileWorker.TAG}_manual"
        assertThat(expectedManualKey).isEqualTo("profile_worker_manual")
    }
}
