package com.vela.app.ui.sessiondetail

    /** Typed content blocks parsed from an amplifierd assistant message. */
    sealed class ContentBlock {
        /** Regular markdown text */
        data class Text(val markdown: String) : ContentBlock()
        /** Internal reasoning — shown as compact inline strip */
        data class Thinking(val text: String) : ContentBlock()
        /** Tool invocation card — isRunning=true while in-flight, false after result arrives */
        data class ToolUse(val id: String, val name: String, val inputJson: String, val isRunning: Boolean = true) : ContentBlock()
        /** Tool result (paired with ToolUse by id) */
        data class ToolResult(val toolUseId: String, val output: String, val isError: Boolean = false) : ContentBlock()
    }
    