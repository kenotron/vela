package com.vela.app.amplifierd

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Source-file structural tests for new API methods added to [AmplifierdClient].
 *
 * These are compile-time / source-file contract tests written BEFORE implementation (TDD RED phase).
 * They verify the exact method signatures are present. If any signature changes, these tests break.
 */
class AmplifierdClientApiMethodsTest {

    private val src: String by lazy {
        java.io.File(
            "src/main/kotlin/com/vela/app/amplifierd/AmplifierdClient.kt"
        ).readText()
    }

    @Test fun `source file contains getTranscriptJson suspend method returning String`() {
        assertThat(src).contains("suspend fun getTranscriptJson(sessionId: String): String")
    }

    @Test fun `getTranscriptJson delegates to GET sessions transcript`() {
        assertThat(src).contains("get(\"/sessions/\$sessionId/transcript\")")
    }

    @Test fun `source file contains resumeSession suspend method returning Boolean`() {
        assertThat(src).contains("suspend fun resumeSession(sessionId: String): Boolean")
    }

    @Test fun `resumeSession posts to sessions resume path`() {
        assertThat(src).contains("\"/sessions/\$sessionId/resume\"")
    }

    @Test fun `source file contains executeStream suspend method returning String`() {
        assertThat(src).contains("suspend fun executeStream(sessionId: String, message: String): String")
    }

    @Test fun `executeStream posts to sessions execute stream path`() {
        assertThat(src).contains("\"/sessions/\$sessionId/execute/stream\"")
    }
}
