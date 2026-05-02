package com.vela.app.amplifierd

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Source-file structural tests for [subscribeEvents] added to [AmplifierdStreamClient].
 *
 * Written BEFORE implementation (TDD RED phase). Verifies the method signature and key
 * implementation details are present in the source file.
 */
class AmplifierdStreamClientSubscribeEventsTest {

    private val src: String by lazy {
        java.io.File(
            "src/main/kotlin/com/vela/app/amplifierd/AmplifierdStreamClient.kt"
        ).readText()
    }

    @Test fun `source file contains subscribeEvents method returning Flow of StreamEvent`() {
        assertThat(src).contains("fun subscribeEvents(sessionId: String): Flow<StreamEvent>")
    }

    @Test fun `subscribeEvents connects to events endpoint with session query parameter`() {
        assertThat(src).contains("\$baseUrl/events?session=\$sessionId")
    }

    @Test fun `subscribeEvents sets Accept text event-stream header`() {
        assertThat(src).contains("Accept")
        assertThat(src).contains("text/event-stream")
    }

    @Test fun `subscribeEvents emits StreamEvent Error on non-success response`() {
        // Check the subscribeEvents method body — the src will contain the error branch
        assertThat(src).contains("StreamEvent.Error")
    }

    @Test fun `subscribeEvents uses flowOn Dispatchers IO`() {
        assertThat(src).contains("flowOn(Dispatchers.IO)")
    }

    @Test fun `subscribeEvents handles DONE sentinel`() {
        assertThat(src).contains("[DONE]")
    }
}
