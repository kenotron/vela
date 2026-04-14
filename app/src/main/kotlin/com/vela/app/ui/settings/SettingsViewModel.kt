package com.vela.app.ui.settings

    import android.content.Context
    import androidx.lifecycle.ViewModel
    import androidx.lifecycle.viewModelScope
    import com.vela.app.data.db.VaultEntity
    import com.vela.app.vault.VaultGitSync
    import com.vela.app.vault.VaultRegistry
    import com.vela.app.vault.VaultSettings
    import dagger.hilt.android.lifecycle.HiltViewModel
    import dagger.hilt.android.qualifiers.ApplicationContext
    import kotlinx.coroutines.flow.MutableStateFlow
    import kotlinx.coroutines.flow.SharingStarted
    import kotlinx.coroutines.flow.StateFlow
    import kotlinx.coroutines.flow.asStateFlow
    import kotlinx.coroutines.flow.stateIn
    import kotlinx.coroutines.launch
    import java.io.File
    import javax.inject.Inject

    @HiltViewModel
    class SettingsViewModel @Inject constructor(
        @ApplicationContext private val context: Context,
        private val vaultRegistry: VaultRegistry,
        private val vaultSettings: VaultSettings,
        private val vaultGitSync: VaultGitSync,
    ) : ViewModel() {

        companion object {
            private const val PREFS       = "amplifier_prefs"
            private const val KEY_API_KEY = "anthropic_api_key"
            private const val KEY_MODEL   = "selected_model"
            const val DEFAULT_MODEL       = "claude-sonnet-4-6"

            val AVAILABLE_MODELS = listOf(
                "claude-sonnet-4-6",
                "claude-opus-4-5",
                "claude-haiku-4-5",
            )
        }

        private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        private val _apiKey = MutableStateFlow(prefs.getString(KEY_API_KEY, "").orEmpty())
        val apiKey: StateFlow<String> = _apiKey.asStateFlow()

        private val _selectedModel = MutableStateFlow(
            prefs.getString(KEY_MODEL, DEFAULT_MODEL).orEmpty().ifBlank { DEFAULT_MODEL }
        )
        val selectedModel: StateFlow<String> = _selectedModel.asStateFlow()

        val vaults: StateFlow<List<VaultEntity>> = vaultRegistry.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

        private val _syncMessage = MutableStateFlow<String?>(null)
        val syncMessage: StateFlow<String?> = _syncMessage.asStateFlow()

        fun setApiKey(key: String) {
            prefs.edit().putString(KEY_API_KEY, key).apply()
            _apiKey.value = key
        }

        fun setModel(model: String) {
            prefs.edit().putString(KEY_MODEL, model).apply()
            _selectedModel.value = model
        }

        fun addVault(name: String, remoteUrl: String = "", pat: String = "") {
            viewModelScope.launch {
                val entity = vaultRegistry.addVault(name)
                if (remoteUrl.isNotBlank()) {
                    vaultSettings.setRemoteUrl(entity.id, remoteUrl)
                    if (pat.isNotBlank()) vaultSettings.setPat(entity.id, pat)
                }
            }
        }

        fun deleteVault(vaultId: String) {
            viewModelScope.launch { vaultRegistry.delete(vaultId) }
        }

        fun setVaultEnabled(vaultId: String, enabled: Boolean) {
            viewModelScope.launch { vaultRegistry.setEnabled(vaultId, enabled) }
        }

        fun setVaultRemote(vaultId: String, remoteUrl: String, pat: String) {
            vaultSettings.setRemoteUrl(vaultId, remoteUrl)
            if (pat.isNotBlank()) vaultSettings.setPat(vaultId, pat)
        }

        fun getVaultRemoteUrl(vaultId: String): String = vaultSettings.getRemoteUrl(vaultId)

        fun isVaultSyncConfigured(vaultId: String): Boolean = vaultSettings.isConfiguredForSync(vaultId)

        fun syncVault(vaultId: String) {
            val vault = vaults.value.firstOrNull { it.id == vaultId } ?: return
            val vaultPath = File(vault.localPath)
            viewModelScope.launch {
                _syncMessage.value = "Syncing…"
                val pullResult = vaultGitSync.pull(vaultId, vaultPath)
                val pushResult = vaultGitSync.push(vaultId, vaultPath)
                _syncMessage.value = "Pull: $pullResult | Push: $pushResult"
            }
        }

        fun clearSyncMessage() { _syncMessage.value = null }
    }
    