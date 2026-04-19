package com.vela.app.workers

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit-level smoke tests for ProfileWorker.
 *
 * doWork() itself requires WorkerParameters (Android) and live injected
 * dependencies, so behavioral tests belong in instrumented tests once the
 * work-testing artefact is on the classpath.  These tests verify the
 * compile-time shape of the companion object so that WorkManager scheduler
 * code that references ProfileWorker.TAG stays in sync with the class.
 */
class ProfileWorkerTest {

    @Test fun `TAG constant matches scheduler expectation`() {
        assertThat(ProfileWorker.TAG).isEqualTo("profile_worker")
    }
}
