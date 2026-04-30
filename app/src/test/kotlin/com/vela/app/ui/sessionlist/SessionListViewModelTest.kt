package com.vela.app.ui.sessionlist

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SessionListViewModelTest {

    // ── Structural: verify models exist ────────────────────────────────────────

    @Test fun `SessionModels source file contains SessionSummary`() {
        val src = java.io.File(
            "src/main/kotlin/com/vela/app/ui/sessiondetail/SessionModels.kt"
        ).readText()
        assertThat(src).contains("data class SessionSummary")
    }

    @Test fun `SessionModels source file contains SessionStatus enum`() {
        val src = java.io.File(
            "src/main/kotlin/com/vela/app/ui/sessiondetail/SessionModels.kt"
        ).readText()
        assertThat(src).contains("enum class SessionStatus")
    }

    @Test fun `SessionModels source file contains TurnContent`() {
        val src = java.io.File(
            "src/main/kotlin/com/vela/app/ui/sessiondetail/SessionModels.kt"
        ).readText()
        assertThat(src).contains("data class TurnContent")
    }

    @Test fun `SessionModels source file contains ToolCall`() {
        val src = java.io.File(
            "src/main/kotlin/com/vela/app/ui/sessiondetail/SessionModels.kt"
        ).readText()
        assertThat(src).contains("data class ToolCall")
    }
}
