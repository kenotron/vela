package com.vela.app.ui.sessiondetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * ViewModel for Screen 4: Session Detail — Turn History.
 *
 * Turn data comes from the amplifierd HTTP API, which does not exist yet.
 * [turns] is seeded with two placeholder turns so the screen renders non-blank
 * in Phase 3. Replace this with real API data in a future phase.
 */
@HiltViewModel
class SessionDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val sessionId: String = checkNotNull(savedStateHandle["sessionId"])

    val turns: StateFlow<List<TurnContent>> = MutableStateFlow(
        listOf(
            // Placeholder user prompt
            TurnContent(
                text   = "Review auth PR #847 and leave inline comments on security issues",
                isUser = true,
            ),
            // Placeholder agent response with a completed tool call
            TurnContent(
                text = "I'll fetch the PR diff and analyze the security implications...",
                toolCalls = listOf(
                    ToolCall(
                        name      = "github: get_pull_request",
                        result    = "PR #847 · +342 −89 · 14 files changed",
                        isDone    = true,
                        isRunning = false,
                        durationMs = 1200L,
                    ),
                ),
                isUser = false,
            ),
        )
    )
}
