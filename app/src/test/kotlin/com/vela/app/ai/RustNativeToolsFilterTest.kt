package com.vela.app.ai

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * RED → GREEN: verifies that [RUST_NATIVE_TOOLS] contains exactly the expected
 * set of tool names that are now owned natively by Rust and must be excluded from
 * the tools_json sent to the LLM.
 */
class RustNativeToolsFilterTest {

    @Test
    fun `RUST_NATIVE_TOOLS contains all expected Rust-native tool names`() {
        val expected = setOf(
            "read_file", "write_file", "edit_file", "glob", "grep",
            "bash", "todo", "load_skill",
        )
        assertThat(RUST_NATIVE_TOOLS).containsExactlyElementsIn(expected)
    }

    @Test
    fun `RUST_NATIVE_TOOLS does not contain unrelated tools`() {
        assertThat(RUST_NATIVE_TOOLS).doesNotContain("delegate")
        assertThat(RUST_NATIVE_TOOLS).doesNotContain("search_web")
        assertThat(RUST_NATIVE_TOOLS).doesNotContain("screenshot")
    }
}
