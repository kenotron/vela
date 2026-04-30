package com.vela.app.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Source-level checks that Phase 5 screens are wired into navigation.
 * Mirrors the approach used in AppNavigationTest.
 *
 * Note: Routes is defined inside AppNavigation.kt in this project,
 * so both tests search AppNavigation.kt.
 */
class NavigationWiringTest {

    @Test fun `Routes declares NODE_CONFIG route`() {
        val src = findAppNavigation().readText()
        assertThat(src).contains("NODE_CONFIG")
    }

    @Test fun `AppNavigation references NodeConfigScreen`() {
        val src = findAppNavigation().readText()
        assertThat(src).contains("NodeConfigScreen")
    }

    @Test fun `AppNavigation references ConnectNodeScreen`() {
        val src = findAppNavigation().readText()
        assertThat(src).contains("ConnectNodeScreen")
    }

    private fun findAppNavigation() =
        File("src/main/kotlin").walk()
            .first { it.name == "AppNavigation.kt" }
}
