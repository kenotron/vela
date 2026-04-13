    package com.vela.app

    import android.os.Bundle
    import androidx.activity.ComponentActivity
    import androidx.activity.compose.setContent
    import androidx.activity.enableEdgeToEdge
    import androidx.compose.runtime.collectAsState
    import androidx.compose.runtime.getValue
    import androidx.hilt.navigation.compose.hiltViewModel
    import com.vela.app.ui.conversation.ConversationScreen
    import com.vela.app.ui.download.ModelDownloadScreen
    import com.vela.app.ui.download.ModelDownloadUiState
    import com.vela.app.ui.download.ModelDownloadViewModel
    import com.vela.app.ui.theme.VelaTheme
    import com.vela.app.voice.SpeechTranscriber
    import dagger.hilt.android.AndroidEntryPoint
    import javax.inject.Inject

    /**
     * Single activity. Shows [ModelDownloadScreen] on first launch until the GGUF model is ready,
     * then switches to [ConversationScreen] for the main AI chat experience.
     *
     * Navigation is state-driven (no NavHost needed — there are only two screens and they're
     * permanently ordered: download → conversation).
     */
    @AndroidEntryPoint
    class MainActivity : ComponentActivity() {

        @Inject
        lateinit var speechTranscriber: SpeechTranscriber

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            enableEdgeToEdge()
            setContent {
                VelaTheme {
                    val downloadVm: ModelDownloadViewModel = hiltViewModel()
                    val downloadState by downloadVm.uiState.collectAsState()

                    if (downloadState is ModelDownloadUiState.Done) {
                        // Model is ready (either just downloaded or already on disk).
                        ConversationScreen(speechTranscriber = speechTranscriber)
                    } else {
                        // First-launch: show download prompt / progress.
                        ModelDownloadScreen(
                            uiState           = downloadState,
                            onConfirmDownload = downloadVm::confirmDownload,
                            onCancel = {
                                // User declined local model — fall back to ML Kit (AICore).
                                // Mark as "done" so the conversation screen appears.
                                // ML Kit will be used automatically via ProviderRegistry fallback.
                                downloadVm.skipToMlKit()
                            },
                            onRetry = downloadVm::retry,
                        )
                    }
                }
            }
        }
    }
    