package com.vela.app.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Guards against the double-insets bug in NavigationScaffold.
 *
 * The outer Scaffold's innerPadding already absorbs status-bar and nav-bar insets.
 * If `consumeWindowInsets(innerPadding)` is absent, every child Scaffold independently
 * re-applies those insets → double status-bar padding on phone layout.
 *
 * This test fails until the fix is present and will catch any future regression.
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
    fun `NavigationScaffold consumes window insets to prevent double status-bar padding`() {
        val source = sourceFile.readText()
        assertThat(source).contains("consumeWindowInsets(innerPadding)")
    }

    @Test
    fun `NavigationScaffold imports consumeWindowInsets`() {
        val source = sourceFile.readText()
        assertThat(source).contains("import androidx.compose.foundation.layout.consumeWindowInsets")
    }
}
