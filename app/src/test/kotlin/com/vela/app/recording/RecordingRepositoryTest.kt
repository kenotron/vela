package com.vela.app.recording

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure-unit tests for recording data types and the elapsed-time formatter.
 * No Android Context needed — RecordingState / RecordingStatus are plain Kotlin
 * data class / enum, and formatElapsed is a companion object utility.
 */
class RecordingRepositoryTest {

    @Test
    fun `RecordingState defaults to IDLE status with zero elapsed seconds and no file`() {
        val state = RecordingState()
        assertThat(state.status).isEqualTo(RecordingStatus.IDLE)
        assertThat(state.elapsedSeconds).isEqualTo(0)
        assertThat(state.outputFile).isNull()
        assertThat(state.transcript).isNull()
        assertThat(state.error).isNull()
    }

    @Test
    fun `formatElapsed converts 90 seconds to 01-30`() {
        assertThat(RecordingRepository.formatElapsed(90)).isEqualTo("01:30")
    }

    @Test
    fun `formatElapsed pads single-digit minutes and seconds`() {
        assertThat(RecordingRepository.formatElapsed(61)).isEqualTo("01:01")
        assertThat(RecordingRepository.formatElapsed(5)).isEqualTo("00:05")
    }

    @Test
    fun `formatElapsed handles zero seconds`() {
        assertThat(RecordingRepository.formatElapsed(0)).isEqualTo("00:00")
    }
}
