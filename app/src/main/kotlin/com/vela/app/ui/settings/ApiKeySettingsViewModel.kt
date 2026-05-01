package com.vela.app.ui.settings

    import androidx.lifecycle.ViewModel
    import com.vela.app.settings.ApiKeyStore
    import dagger.hilt.android.lifecycle.HiltViewModel
    import kotlinx.coroutines.flow.MutableStateFlow
    import kotlinx.coroutines.flow.StateFlow
    import javax.inject.Inject

    @HiltViewModel
    class ApiKeySettingsViewModel @Inject constructor(
        private val store: ApiKeyStore,
    ) : ViewModel() {

        private val _openAiKey = MutableStateFlow(store.openAiKey)
        val openAiKey: StateFlow<String> = _openAiKey

        fun updateOpenAi(v: String) { _openAiKey.value = v }

        fun save() {
            store.openAiKey = _openAiKey.value
        }
    }
    