package com.vela.app.ui.coordinator

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class CoordinatorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val sessionId: String = checkNotNull(savedStateHandle["sessionId"])

    // ── Data model ─────────────────────────────────────────────────────────────

    data class BranchState(
        val nodeId: String,
        val nodeName: String,
        val steps: List<BranchStep>,
        val sessionId: String? = null,
    )

    data class BranchStep(
        val description: String,
        val status: StepStatus,
    )

    enum class StepStatus { DONE, RUNNING, WAITING }

    // ── State ──────────────────────────────────────────────────────────────────

    private val _branches = MutableStateFlow(
        listOf(
            BranchState(
                nodeId   = "node-1",
                nodeName = "amplifierd-mac",
                steps    = listOf(
                    BranchStep("git clone auth-service",           StepStatus.DONE),
                    BranchStep("run tests (47 passed)",            StepStatus.DONE),
                    BranchStep("build artifact & push registry…",  StepStatus.RUNNING),
                ),
            ),
            BranchState(
                nodeId   = "node-2",
                nodeName = "amplifierd-cloud",
                steps    = listOf(
                    BranchStep("waiting for mac build artifact",   StepStatus.WAITING),
                    BranchStep("deploy auth-service:v2.3.1",       StepStatus.WAITING),
                    BranchStep("run health checks",                StepStatus.WAITING),
                ),
            ),
        )
    )
    val branches: StateFlow<List<BranchState>> = _branches

    val currentStep: Int = 3
    val totalSteps: Int  = 5
}
