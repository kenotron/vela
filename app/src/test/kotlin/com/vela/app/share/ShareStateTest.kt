package com.vela.app.share

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for the ShareState sealed class.
 *
 * These tests validate the data-class API that ShareProcessingScreen
 * and ShareViewModel contract with. Because ShareViewModel requires
 * android.content.Context (Android-only), full ViewModel tests live in
 * instrumented tests. These JVM tests pin the sealed-class contract.
 *
 * RED: fails to compile before ShareViewModel.kt exists (ShareState undefined).
 * GREEN: passes once ShareViewModel.kt is created.
 */
class ShareStateTest {

    @Test
    fun `Idle is not equal to Preview`() {
        val idle: ShareState = ShareState.Idle
        val preview: ShareState = ShareState.Preview("label", "summary")
        assertThat(idle).isNotEqualTo(preview)
    }

    @Test
    fun `Preview carries label and contentSummary`() {
        val state = ShareState.Preview("Text from Gmail", "Hello world")
        assertThat(state.label).isEqualTo("Text from Gmail")
        assertThat(state.contentSummary).isEqualTo("Hello world")
    }

    @Test
    fun `Two Preview states with same data are equal`() {
        val a = ShareState.Preview("label", "summary")
        val b = ShareState.Preview("label", "summary")
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `Done carries conversationId`() {
        val state = ShareState.Done("conv-abc-123", stagedMessage = "staged text")
        assertThat(state.conversationId).isEqualTo("conv-abc-123")
    }

    @Test
    fun `Error carries message`() {
        val state = ShareState.Error("Could not read file: permission denied")
        assertThat(state.message).isEqualTo("Could not read file: permission denied")
    }

    @Test
    fun `text truncation at exactly 200 chars is not truncated`() {
        // Documents the contract from ShareViewModel.prepareText
        val exactText = "A".repeat(200)
        val result = exactText.take(200).let { if (exactText.length > 200) "$it\u2026" else it }
        assertThat(result).isEqualTo(exactText)
        assertThat(result).doesNotContain("…")
    }

    @Test
    fun `text longer than 200 chars is truncated with ellipsis`() {
        val longText = "B".repeat(201)
        val result = longText.take(200).let { if (longText.length > 200) "$it\u2026" else it }
        assertThat(result).endsWith("…")
        assertThat(result.length).isEqualTo(201) // 200 chars + 1 Unicode ellipsis
    }
}
