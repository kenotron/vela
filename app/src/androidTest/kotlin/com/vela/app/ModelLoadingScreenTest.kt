package com.vela.app

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.vela.app.ai.ReadinessState
import com.vela.app.ui.loading.ModelLoadingScreen
import com.vela.app.ui.theme.VelaTheme
import com.vela.app.util.AccessibilitySnapshot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModelLoadingScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun downloadableState_showsDownloadButton() {
        composeTestRule.setContent {
            VelaTheme {
                ModelLoadingScreen(state = ReadinessState.Downloadable)
            }
        }
        AccessibilitySnapshot.assertHasContentDesc("Download Gemma 4 model")
        AccessibilitySnapshot.assertHasText("Download Gemma 4 AI Model")
    }

    @Test
    fun downloadingState_indeterminate_showsProgressIndicator() {
        composeTestRule.setContent {
            VelaTheme {
                ModelLoadingScreen(state = ReadinessState.Downloading(progress = null))
            }
        }
        AccessibilitySnapshot.assertHasContentDesc("Gemma 4 downloading")
    }

    @Test
    fun downloadingState_determinate_showsProgressIndicator() {
        composeTestRule.setContent {
            VelaTheme {
                ModelLoadingScreen(state = ReadinessState.Downloading(progress = 0.5f))
            }
        }
        AccessibilitySnapshot.assertHasContentDesc("Gemma 4 downloading")
    }

    @Test
    fun unavailableState_showsUnsupportedMessage() {
        composeTestRule.setContent {
            VelaTheme {
                ModelLoadingScreen(state = ReadinessState.Unavailable)
            }
        }
        AccessibilitySnapshot.assertHasContentDesc("Device not supported for Gemma 4")
        AccessibilitySnapshot.assertHasText(
            "Gemma 4 requires a compatible device with Android AICore support. Your device is not supported."
        )
    }

    @Test
    fun availableState_rendersNothing() {
        composeTestRule.setContent {
            VelaTheme { ModelLoadingScreen(state = ReadinessState.Available) }
        }
        AccessibilitySnapshot.assertNotHasContentDesc("Download Gemma 4 model")
        AccessibilitySnapshot.assertNotHasContentDesc("Gemma 4 downloading")
        AccessibilitySnapshot.assertNotHasContentDesc("Device not supported for Gemma 4")
    }

    @Test
    fun downloadableState_clickingButton_invokesOnDownloadClick() {
        var clicked = false
        composeTestRule.setContent {
            VelaTheme {
                ModelLoadingScreen(
                    state = ReadinessState.Downloadable,
                    onDownloadClick = { clicked = true }
                )
            }
        }
        composeTestRule.onNodeWithContentDescription("Download Gemma 4 model").performClick()
        assertThat(clicked).isTrue()
    }
}
