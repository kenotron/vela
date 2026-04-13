    package com.vela.app.ui.download

    import androidx.lifecycle.ViewModel
    import androidx.lifecycle.viewModelScope
    import com.vela.app.ai.llama.DownloadState
    import com.vela.app.ai.llama.LlamaCppProvider
    import com.vela.app.ai.llama.ModelDownloadManager
    import dagger.hilt.android.lifecycle.HiltViewModel
    import kotlinx.coroutines.flow.MutableStateFlow
    import kotlinx.coroutines.flow.StateFlow
    import kotlinx.coroutines.flow.asStateFlow
    import kotlinx.coroutines.launch
    import javax.inject.Inject

    /**
     * Drives [ModelDownloadScreen].
     *
     * On creation, immediately checks whether the model is already on disk:
     *   - If yes → calls [LlamaCppProvider.loadModel] and emits [ModelDownloadUiState.Done]
     *     so the nav graph can skip the download screen entirely.
     *   - If no → stays at [ModelDownloadUiState.Prompt] awaiting user confirmation.
     *
     * "Not Now" / Cancel: emits Done immediately so the app proceeds with ML Kit fallback.
     */
    @HiltViewModel
    class ModelDownloadViewModel @Inject constructor(
        private val downloadManager: ModelDownloadManager,
        private val llamaProvider: LlamaCppProvider,
    ) : ViewModel() {

        private val _uiState = MutableStateFlow<ModelDownloadUiState>(ModelDownloadUiState.Prompt)
        val uiState: StateFlow<ModelDownloadUiState> = _uiState.asStateFlow()

        init {
            if (downloadManager.isDownloaded()) {
                // Model is already on disk — load it into native memory, then proceed.
                viewModelScope.launch {
                    runCatching { llamaProvider.loadModel() }
                    _uiState.value = ModelDownloadUiState.Done
                }
            }
            // else: stays at Prompt until user confirms
        }

        /** User pressed "Download". Starts the download + loads model on completion. */
        fun confirmDownload() {
            viewModelScope.launch {
                downloadManager.download().collect { state ->
                    when (state) {
                        is DownloadState.Progress -> _uiState.value = ModelDownloadUiState.Downloading(
                            fraction = state.fraction,
                            mbRead   = state.mbRead,
                            mbTotal  = state.mbTotal,
                        )
                        is DownloadState.Done -> {
                            runCatching { llamaProvider.loadModel() }
                            _uiState.value = ModelDownloadUiState.Done
                        }
                        is DownloadState.Error -> _uiState.value = ModelDownloadUiState.Error(state.message)
                    }
                }
            }
        }

        /**
         * User pressed "Not Now" — skip local model, fall back to ML Kit (AICore).
         * The ProviderRegistry will automatically use MlKitInferenceProvider since
         * LlamaCppProvider.isAvailable() returns false (model not loaded).
         */
        fun skipToMlKit() {
            _uiState.value = ModelDownloadUiState.Done
        }

        /** User pressed "Retry" after a failed download. */
        fun retry() = confirmDownload()
    }
    