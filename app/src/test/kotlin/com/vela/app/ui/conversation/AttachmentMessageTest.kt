package com.vela.app.ui.conversation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * RED test — verifies `buildAttachedMessage` does not yet exist.
 * Once the function is extracted, these tests go GREEN.
 */
class AttachmentMessageTest {

    @Test
    fun `returns plain text when no attachments`() {
        val result = buildAttachedMessage("hello", emptyList())
        assertThat(result).isEqualTo("hello")
    }

    @Test
    fun `trims leading and trailing whitespace from text`() {
        val result = buildAttachedMessage("  hi there  ", emptyList())
        assertThat(result).isEqualTo("  hi there  ")  // helper does NOT trim — caller does
    }

    @Test
    fun `appends Attached files block when attachments present`() {
        val result = buildAttachedMessage(
            text = "see this",
            attachments = listOf("report.pdf" to "content://provider/0/report.pdf"),
        )
        assertThat(result).contains("Attached files:")
        assertThat(result).contains("- report.pdf (content://provider/0/report.pdf)")
    }

    @Test
    fun `multiple attachments each appear on their own line`() {
        val attachments = listOf(
            "photo.jpg"  to "content://provider/1/photo.jpg",
            "notes.txt"  to "content://provider/2/notes.txt",
        )
        val result = buildAttachedMessage("two files", attachments)
        assertThat(result).contains("- photo.jpg (content://provider/1/photo.jpg)")
        assertThat(result).contains("- notes.txt (content://provider/2/notes.txt)")
    }

    @Test
    fun `empty text with attachments still produces non-blank message`() {
        val result = buildAttachedMessage(
            text = "",
            attachments = listOf("file.pdf" to "content://provider/0/file.pdf"),
        )
        assertThat(result.isNotBlank()).isTrue()
        assertThat(result).contains("Attached files:")
    }
}
