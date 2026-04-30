package com.vela.app.ui.sessiondetail

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionDetailViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before fun setUp()    { Dispatchers.setMain(testDispatcher) }
    @After  fun tearDown() { Dispatchers.resetMain() }

    private fun makeVm(sessionId: String = "sess-1"): SessionDetailViewModel {
        val savedState = SavedStateHandle(mapOf("sessionId" to sessionId))
        return SessionDetailViewModel(savedState)
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test fun `sessionId is read from SavedStateHandle`() {
        val vm = makeVm(sessionId = "sess-42")
        assertThat(vm.sessionId).isEqualTo("sess-42")
    }

    @Test fun `turns initial value is not empty (has placeholder data)`() = runTest {
        val vm = makeVm()
        assertThat(vm.turns.value).isNotEmpty()
    }

    @Test fun `turns initial value has two placeholder turns`() = runTest {
        val vm = makeVm()
        assertThat(vm.turns.value).hasSize(2)
    }

    @Test fun `first placeholder turn is a user turn`() = runTest {
        val vm = makeVm()
        assertThat(vm.turns.value[0].isUser).isTrue()
    }

    @Test fun `second placeholder turn is an agent turn`() = runTest {
        val vm = makeVm()
        assertThat(vm.turns.value[1].isUser).isFalse()
    }

    @Test fun `second placeholder turn has one tool call`() = runTest {
        val vm = makeVm()
        assertThat(vm.turns.value[1].toolCalls).hasSize(1)
    }

    @Test fun `placeholder tool call is marked done`() = runTest {
        val vm = makeVm()
        val toolCall = vm.turns.value[1].toolCalls[0]
        assertThat(toolCall.isDone).isTrue()
        assertThat(toolCall.isRunning).isFalse()
    }

    // ── Structural: verify TurnItems composables exist ──────────────────────

    @Test fun `TurnItems source contains UserTurnItem composable`() {
        val src = java.io.File(
            "src/main/kotlin/com/vela/app/ui/sessiondetail/TurnItems.kt"
        ).readText()
        assertThat(src).contains("fun UserTurnItem")
    }

    @Test fun `TurnItems source contains AgentTurnItem composable`() {
        val src = java.io.File(
            "src/main/kotlin/com/vela/app/ui/sessiondetail/TurnItems.kt"
        ).readText()
        assertThat(src).contains("fun AgentTurnItem")
    }

    @Test fun `TurnItems source contains ToolCallCard composable`() {
        val src = java.io.File(
            "src/main/kotlin/com/vela/app/ui/sessiondetail/TurnItems.kt"
        ).readText()
        assertThat(src).contains("fun ToolCallCard")
    }

    @Test fun `SessionDetailScreen source file exists with SessionDetailScreen composable`() {
        val src = java.io.File(
            "src/main/kotlin/com/vela/app/ui/sessiondetail/SessionDetailScreen.kt"
        ).readText()
        assertThat(src).contains("fun SessionDetailScreen")
    }
}
