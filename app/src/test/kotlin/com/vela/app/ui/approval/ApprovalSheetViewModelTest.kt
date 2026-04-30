package com.vela.app.ui.approval

    import com.google.common.truth.Truth.assertThat
    import org.junit.Test

    class ApprovalSheetViewModelTest {

        private fun makeVm() = ApprovalSheetViewModel()

        // ── Initial state ──────────────────────────────────────────────────────────────────

        @Test fun `initial request is null`() {
            assertThat(makeVm().request.value).isNull()
        }

        // ── present ──────────────────────────────────────────────────────────────────────────

        @Test fun `present sets request`() {
            val vm  = makeVm()
            val req = ApprovalSheetViewModel.ApprovalRequest("s1", "Run migration?")
            vm.present(req)
            assertThat(vm.request.value).isEqualTo(req)
        }

        @Test fun `present with contextText preserves contextText`() {
            val vm  = makeVm()
            val req = ApprovalSheetViewModel.ApprovalRequest("s1", "Deploy?", "tool_call: deploy_to_prod")
            vm.present(req)
            assertThat(vm.request.value?.contextText).isEqualTo("tool_call: deploy_to_prod")
        }

        @Test fun `present replaces existing request`() {
            val vm = makeVm()
            vm.present(ApprovalSheetViewModel.ApprovalRequest("s1", "First?"))
            vm.present(ApprovalSheetViewModel.ApprovalRequest("s2", "Second?"))
            assertThat(vm.request.value?.sessionId).isEqualTo("s2")
            assertThat(vm.request.value?.question).isEqualTo("Second?")
        }

        // ── approve ──────────────────────────────────────────────────────────────────────────

        @Test fun `approve clears request to null`() {
            val vm = makeVm()
            vm.present(ApprovalSheetViewModel.ApprovalRequest("s1", "Run migration?"))
            vm.approve()
            assertThat(vm.request.value).isNull()
        }

        @Test fun `approve when request is already null does not crash`() {
            val vm = makeVm()
            vm.approve() // should be a no-op
            assertThat(vm.request.value).isNull()
        }

        // ── deny ─────────────────────────────────────────────────────────────────────────────

        @Test fun `deny clears request to null`() {
            val vm = makeVm()
            vm.present(ApprovalSheetViewModel.ApprovalRequest("s1", "Deploy?"))
            vm.deny()
            assertThat(vm.request.value).isNull()
        }

        @Test fun `deny when request is already null does not crash`() {
            val vm = makeVm()
            vm.deny()
            assertThat(vm.request.value).isNull()
        }

        // ── ApprovalRequest data class ─────────────────────────────────────────────────────

        @Test fun `ApprovalRequest contextText defaults to null`() {
            val req = ApprovalSheetViewModel.ApprovalRequest("s1", "Question?")
            assertThat(req.contextText).isNull()
        }

        @Test fun `two ApprovalRequests with same data are equal`() {
            val a = ApprovalSheetViewModel.ApprovalRequest("s1", "Q?", "ctx")
            val b = ApprovalSheetViewModel.ApprovalRequest("s1", "Q?", "ctx")
            assertThat(a).isEqualTo(b)
        }
    }
    