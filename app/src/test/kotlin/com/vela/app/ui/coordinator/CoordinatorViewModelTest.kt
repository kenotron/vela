package com.vela.app.ui.coordinator

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CoordinatorViewModelTest {

    private fun newVm(sessionId: String = "test-session-123") = CoordinatorViewModel(
        savedStateHandle = SavedStateHandle(mapOf("sessionId" to sessionId)),
    )

    // ── sessionId ────────────────────────────────────────────────────────────

    @Test fun `sessionId is read from SavedStateHandle`() {
        assertThat(newVm("sess-abc").sessionId).isEqualTo("sess-abc")
    }

    // ── branches StateFlow ───────────────────────────────────────────────────

    @Test fun `branches has two entries by default`() {
        assertThat(newVm().branches.value).hasSize(2)
    }

    @Test fun `first branch nodeId is node-1`() {
        assertThat(newVm().branches.value[0].nodeId).isEqualTo("node-1")
    }

    @Test fun `first branch nodeName is amplifierd-mac`() {
        assertThat(newVm().branches.value[0].nodeName).isEqualTo("amplifierd-mac")
    }

    @Test fun `first branch has three steps`() {
        assertThat(newVm().branches.value[0].steps).hasSize(3)
    }

    @Test fun `first branch step 0 status is DONE`() {
        assertThat(newVm().branches.value[0].steps[0].status)
            .isEqualTo(CoordinatorViewModel.StepStatus.DONE)
    }

    @Test fun `first branch step 1 status is DONE`() {
        assertThat(newVm().branches.value[0].steps[1].status)
            .isEqualTo(CoordinatorViewModel.StepStatus.DONE)
    }

    @Test fun `first branch step 2 status is RUNNING`() {
        assertThat(newVm().branches.value[0].steps[2].status)
            .isEqualTo(CoordinatorViewModel.StepStatus.RUNNING)
    }

    @Test fun `second branch nodeId is node-2`() {
        assertThat(newVm().branches.value[1].nodeId).isEqualTo("node-2")
    }

    @Test fun `second branch nodeName is amplifierd-cloud`() {
        assertThat(newVm().branches.value[1].nodeName).isEqualTo("amplifierd-cloud")
    }

    @Test fun `second branch has three steps all WAITING`() {
        val steps = newVm().branches.value[1].steps
        assertThat(steps).hasSize(3)
        assertThat(steps.all { it.status == CoordinatorViewModel.StepStatus.WAITING }).isTrue()
    }

    // ── step progress ────────────────────────────────────────────────────────

    @Test fun `currentStep is 3`() {
        assertThat(newVm().currentStep).isEqualTo(3)
    }

    @Test fun `totalSteps is 5`() {
        assertThat(newVm().totalSteps).isEqualTo(5)
    }

    // ── StepStatus enum ──────────────────────────────────────────────────────

    @Test fun `StepStatus has DONE RUNNING and WAITING entries`() {
        val names = CoordinatorViewModel.StepStatus.entries.map { it.name }
        assertThat(names).containsExactly("DONE", "RUNNING", "WAITING")
    }

    // ── BranchStep description ───────────────────────────────────────────────

    @Test fun `first branch step 0 description contains git clone`() {
        assertThat(newVm().branches.value[0].steps[0].description)
            .contains("git clone")
    }

    @Test fun `first branch step 2 description contains build artifact`() {
        assertThat(newVm().branches.value[0].steps[2].description)
            .contains("build artifact")
    }
}
