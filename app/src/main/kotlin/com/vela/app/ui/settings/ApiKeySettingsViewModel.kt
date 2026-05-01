package com.vela.app.ui.settings

    import androidx.lifecycle.ViewModel
    import com.vela.app.settings.ApiKeyStore
    import dagger.hilt.android.lifecycle.HiltViewModel
    import kotlinx.coroutines.flow.MutableStateFlow
    import kotlinx.coroutines.flow.StateFlow
    import javax.inject.Inject

    /**
     * ViewModel for the API Keys (Vela node keys) settings screen.
     *
     * Reads the current API key values from [ApiKeyStore] on init and exposes them
     * as [MutableStateFlow]s so the screen can react to changes. [save] writes both
     * values back to the encrypted store.
     */
    @HiltViewModel
    class ApiKeySettingsViewModel @Inject constructor(
        private val store: ApiKeyStore,
    ) : ViewModel() {

        private val _anthropicKey = MutableStateFlow(store.anthropicKey)
        val anthropicKey: StateFlow<String> = _anthropicKey

        private val _openAiKey = MutableStateFlow(store.openAiKey)
        val openAiKey: StateFlow<String> = _openAiKey

        fun updateAnthropic(v: String) { _anthropicKey.value = v }
        fun updateOpenAi(v: String)    { _openAiKey.value = v }

        fun save() {
            store.anthropicKey = _anthropicKey.value
            store.openAiKey    = _openAiKey.value
        }
    }
    