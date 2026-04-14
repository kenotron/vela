package com.vela.app.assets

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Verifies the lifeos SYSTEM.md asset is present in the source tree at the path
 * Android packages it into APK assets at runtime.
 *
 * This is a build-time contract test: the file must exist before the APK is assembled
 * so that SessionHarness can load it via Context.assets at runtime.
 */
class LifeosAssetTest {

    private val assetFile = File("src/main/assets/lifeos/SYSTEM.md")

    @Test fun `lifeos SYSTEM_md asset exists at bundled path`() {
        assertThat(assetFile.exists())
            .isTrue()
    }

    @Test fun `lifeos SYSTEM_md contains system prompt content`() {
        assertThat(assetFile.exists()).isTrue()
        val content = assetFile.readText()
        // Verify this is a lifeos system prompt, not an empty placeholder
        assertThat(content).isNotEmpty()
        assertThat(content.length).isGreaterThan(100)
    }

    @Test fun `lifeos SYSTEM_md starts with expected heading`() {
        assertThat(assetFile.exists()).isTrue()
        val content = assetFile.readText()
        // Should start with a markdown heading
        assertThat(content.trimStart()).startsWith("#")
    }
}
