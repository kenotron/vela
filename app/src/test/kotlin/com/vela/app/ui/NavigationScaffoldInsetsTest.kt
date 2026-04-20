package com.vela.app.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Sanity checks for the NavigationScaffold source file.
 *
 * The scaffold was refactored from a bottom-nav Scaffold (which had a
 * double-insets risk) to a [ModalNavigationDrawer] architecture where each
 * individual screen owns its own Scaffold and insets.
 */
class NavigationScaffoldInsetsTest {

    private val sourceFile = File(
        "src/main/kotlin/com/vela/app/ui/NavigationScaffold.kt"
    )

    @Test
    fun `NavigationScaffold source file exists`() {
        assertThat(sourceFile.exists()).isTrue()
    }

    @Test
    fun `NavigationScaffold uses ModalNavigationDrawer`() {
        val source = sourceFile.readText()
        assertThat(source).contains("ModalNavigationDrawer")
    }

    @Test
    fun `NavigationScaffold uses VelaDrawerContent`() {
        val source = sourceFile.readText()
        assertThat(source).contains("VelaDrawerContent")
    }
}
