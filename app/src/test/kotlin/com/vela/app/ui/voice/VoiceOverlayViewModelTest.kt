package com.vela.app.ui.voice

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure ViewModel unit tests — no Compose, no Android runtime.
 * Spec: Phase 4 VoiceOverlayViewModel.
 */
class VoiceOverlayViewModelTest {

    private fun makeVm() = VoiceOverlayViewModel()

    // ── isRecording ──────────────────────────────────────────────────────────

    @Test fun `startRecording sets isRecording true`() {
        val vm = makeVm()
        vm.startRecording()
        assertThat(vm.isRecording.value).isTrue()
    }

    @Test fun `stopRecording sets isRecording false`() {
        val vm = makeVm()
        vm.startRecording()
        vm.stopRecording()
        assertThat(vm.isRecording.value).isFalse()
    }

    // ── phase ─────────────────────────────────────────────────────────────────

    @Test fun `stopRecording moves to REVIEW phase`() {
        val vm = makeVm()
        vm.startRecording()
        vm.stopRecording()
        assertThat(vm.phase.value).isEqualTo(VoiceOverlayViewModel.VoicePhase.REVIEW)
    }

    // ── discard ───────────────────────────────────────────────────────────────

    @Test fun `discard resets all state to defaults`() {
        val vm = makeVm()
        vm.startRecording()
        vm.stopRecording()
        vm.discard()
        assertThat(vm.phase.value).isEqualTo(VoiceOverlayViewModel.VoicePhase.RECORDING)
        assertThat(vm.isRecording.value).isFalse()
        assertThat(vm.transcript.value).isEmpty()
        assertThat(vm.elapsedMs.value).isEqualTo(0L)
    }

    // ── formatElapsedMs ───────────────────────────────────────────────────────

    @Test fun `formatElapsedMs 0 returns 0 colon 00`() {
        assertThat(VoiceOverlayViewModel.formatElapsedMs(0L)).isEqualTo("0:00")
    }

    @Test fun `formatElapsedMs 42000 returns 0 colon 42`() {
        assertThat(VoiceOverlayViewModel.formatElapsedMs(42_000L)).isEqualTo("0:42")
    }

    @Test fun `formatElapsedMs 84000 returns 1 colon 24`() {
        assertThat(VoiceOverlayViewModel.formatElapsedMs(84_000L)).isEqualTo("1:24")
    }

    @Test fun `formatElapsedMs 605000 returns 10 colon 05`() {
        assertThat(VoiceOverlayViewModel.formatElapsedMs(605_000L)).isEqualTo("10:05")
    }
}
