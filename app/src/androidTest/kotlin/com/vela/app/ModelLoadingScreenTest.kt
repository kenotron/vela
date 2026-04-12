package com.vela.app

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vela.app.ai.DownloadState
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
    fun showsDownloadButton() {
        composeTestRule.setContent {
            VelaTheme {
                ModelLoadingScreen(
                    downloadState = DownloadState.NotDownloaded,
                    onDownloadClick = {},
                )
            }
        }
        AccessibilitySnapshot.assertHasContentDesc("Download AI model")
    }

    @Test
    fun showsProgressDuringDownload() {
        composeTestRule.setContent {
            VelaTheme {
                ModelLoadingScreen(
                    downloadState = DownloadState.Downloading(42),
                    onDownloadClick = {},
                )
            }
        }
        AccessibilitySnapshot.assertHasContentDesc("Model download progress")
    }

    @Test
    fun showsCompletionWhenDownloaded() {
        composeTestRule.setContent {
            VelaTheme {
                ModelLoadingScreen(
                    downloadState = DownloadState.Downloaded(path = ""),
                    onDownloadClick = {},
                )
            }
        }
        AccessibilitySnapshot.assertHasText("Model ready")
    }

    @Test
    fun showsErrorState() {
        composeTestRule.setContent {
            VelaTheme {
                ModelLoadingScreen(
                    downloadState = DownloadState.Error("HTTP 404"),
                    onDownloadClick = {},
                )
            }
        }
        AccessibilitySnapshot.assertHasText("Download failed")
        AccessibilitySnapshot.assertHasContentDesc("Download AI model")
    }
}
