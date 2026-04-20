package com.vela.app.ui.settings

    import android.content.Context
    import androidx.lifecycle.ViewModel
    import androidx.lifecycle.viewModelScope
    import com.vela.app.data.db.VaultEntity
    import com.vela.app.github.GitHubIdentity
    import com.vela.app.github.GitHubIdentityManager
    import com.vela.app.vault.VaultGitSync
    import com.vela.app.vault.VaultRegistry
    import com.vela.app.vault.VaultSettings
    import dagger.hilt.android.lifecycle.HiltViewModel
    import dagger.hilt.android.qualifiers.ApplicationContext
    import kotlinx.coroutines.flow.MutableStateFlow
    import kotlinx.coroutines.flow.SharingStarted
    import kotlinx.coroutines.flow.StateFlow
    import kotlinx.coroutines.flow.asStateFlow
    import kotlinx.coroutines.flow.map
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
        private val gitHubIdentityManager: GitHubIdentityManager,
        private val miniAppServer: com.vela.app.server.VelaMiniAppServer,
    ) : ViewModel() {

        val gitHubIdentities: StateFlow<List<GitHubIdentity>> =
            gitHubIdentityManager.allFlow()
                .map { list ->
                    list.map { e ->
                        GitHubIdentity(e.id, e.label, e.username, e.avatarUrl,
                            e.token, e.tokenType, e.scopes, e.addedAt, e.isDefault)
                    }
                }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

        companion object {
            private const val PREFS              = "amplifier_prefs"
            private const val KEY_API_KEY        = "anthropic_api_key"
            private const val KEY_MODEL          = "selected_model"
            private const val KEY_GOOGLE_API_KEY = "google_api_key"
            private const val KEY_OPENAI_API_KEY = "openai_api_key"
            const val KEY_LAN_SERVER             = "mini_app_server_lan"
            const val DEFAULT_MODEL              = "claude-sonnet-4-6"

            val AVAILABLE_MODELS = listOf(
                "claude-sonnet-4-6",
                "claude-opus-4-5",
                "claude-haiku-4-5",
            )

            /** Returns true when a sync result string indicates a failure. */
            fun isSyncError(msg: String) =
                msg.contains("Error",    ignoreCase = true) ||
                msg.contains("failed",   ignoreCase = true) ||
                msg.contains("rejected", ignoreCase = true) ||
                msg.contains("conflict", ignoreCase = true)

            /** Exposed as internal so it can be unit-tested without Android. */
            internal fun normalizeRemoteUrl(input: String): String {
                val trimmed = input.trim()
                if (trimmed.isBlank()) return ""
                if (trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.startsWith("git@")) {
                    return trimmed
                }
                // username/repo shorthand → full HTTPS GitHub URL
                if (trimmed.matches(Regex("[a-zA-Z0-9_.-]+/[a-zA-Z0-9_.-]+"))) {
                    return "https://github.com/$trimmed.git"
                }
                return trimmed
            }
        }

        private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        private val _apiKey = MutableStateFlow(prefs.getString(KEY_API_KEY, "").orEmpty())
        val apiKey: StateFlow<String> = _apiKey.asStateFlow()

        private val _selectedModel = MutableStateFlow(
            prefs.getString(KEY_MODEL, DEFAULT_MODEL).orEmpty().ifBlank { DEFAULT_MODEL }
        )
        val selectedModel: StateFlow<String> = _selectedModel.asStateFlow()

        private val _googleApiKey = MutableStateFlow(prefs.getString(KEY_GOOGLE_API_KEY, "").orEmpty())
        val googleApiKey: StateFlow<String> = _googleApiKey.asStateFlow()

        private val _openAiApiKey = MutableStateFlow(prefs.getString(KEY_OPENAI_API_KEY, "").orEmpty())
        val openAiApiKey: StateFlow<String> = _openAiApiKey.asStateFlow()

        private val _lanEnabled = MutableStateFlow(prefs.getBoolean(KEY_LAN_SERVER, false))
        val lanEnabled: StateFlow<Boolean> = _lanEnabled.asStateFlow()

        fun setLanEnabled(enabled: Boolean) {
            prefs.edit().putBoolean(KEY_LAN_SERVER, enabled).apply()
            _lanEnabled.value = enabled
            miniAppServer.restart(if (enabled) "0.0.0.0" else "127.0.0.1")
        }

        val vaults: StateFlow<List<VaultEntity>> = vaultRegistry.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

        private val _syncMessage = MutableStateFlow<String?>(null)
        val syncMessage: StateFlow<String?> = _syncMessage.asStateFlow()

        /** Name of the vault that last triggered a sync — retained so the UI can label
         *  the "Fix with Vela" prompt even after the vault list has scrolled. */
        private val _syncVaultName = MutableStateFlow<String?>(null)
        val syncVaultName: StateFlow<String?> = _syncVaultName.asStateFlow()

        /** True when the most-recent sync message looks like a failure. */
        val syncIsError: StateFlow<Boolean> = _syncMessage
            .map { msg -> msg != null && isSyncError(msg) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

        fun setApiKey(key: String) {
            prefs.edit().putString(KEY_API_KEY, key).apply()
            _apiKey.value = key
        }

        fun setGoogleApiKey(key: String) {
            prefs.edit().putString(KEY_GOOGLE_API_KEY, key).apply()
            _googleApiKey.value = key
        }

        fun setOpenAiApiKey(key: String) {
            prefs.edit().putString(KEY_OPENAI_API_KEY, key).apply()
            _openAiApiKey.value = key
        }

        fun setModel(model: String) {
            prefs.edit().putString(KEY_MODEL, model).apply()
            _selectedModel.value = model
        }

        fun addVault(name: String, remoteUrl: String = "", pat: String = "", branch: String = "") {
            viewModelScope.launch {
                val entity = vaultRegistry.addVault(name)
                if (remoteUrl.isNotBlank()) {
                    vaultSettings.setRemoteUrl(entity.id, normalizeRemoteUrl(remoteUrl))
                    if (pat.isNotBlank()) vaultSettings.setPat(entity.id, pat)
                    if (branch.isNotBlank()) vaultSettings.setBranch(entity.id, branch)
                    // Immediately clone so content is available in the current session
                    val vaultPath = File(entity.localPath)
                    _syncMessage.value = "Cloning vault…"
                    val result = vaultGitSync.cloneIfNeeded(entity.id, vaultPath)
                    _syncMessage.value = result
                }
            }
        }

        fun deleteVault(vaultId: String) {
            viewModelScope.launch { vaultRegistry.delete(vaultId) }
        }

        fun setVaultEnabled(vaultId: String, enabled: Boolean) {
            viewModelScope.launch { vaultRegistry.setEnabled(vaultId, enabled) }
        }

        fun setVaultRemote(vaultId: String, remoteUrl: String, pat: String, branch: String = "") {
            vaultSettings.setRemoteUrl(vaultId, normalizeRemoteUrl(remoteUrl))
            if (pat.isNotBlank()) vaultSettings.setPat(vaultId, pat)
            if (branch.isNotBlank()) vaultSettings.setBranch(vaultId, branch)
        }

        fun getVaultBranch(vaultId: String): String = vaultSettings.getBranch(vaultId)

        fun getVaultRemoteUrl(vaultId: String): String = vaultSettings.getRemoteUrl(vaultId)

        fun isVaultSyncConfigured(vaultId: String): Boolean = vaultSettings.isConfiguredForSync(vaultId)

        fun syncVault(vaultId: String) {
            val vault = vaults.value.firstOrNull { it.id == vaultId } ?: return
            val vaultPath = File(vault.localPath)
            _syncVaultName.value = vault.name
            viewModelScope.launch {
                _syncMessage.value = "Syncing…"
                val pullResult = vaultGitSync.pull(vaultId, vaultPath)
                val pushResult = vaultGitSync.push(vaultId, vaultPath)
                _syncMessage.value = "Pull: $pullResult | Push: $pushResult"
            }
        }

        fun clearSyncMessage() { _syncMessage.value = null }
    }
    