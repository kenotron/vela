package com.vela.app.ui.vault

    import android.util.Log
    import androidx.lifecycle.ViewModel
    import androidx.lifecycle.viewModelScope
    import com.vela.app.data.db.VaultEntity
    import com.vela.app.vault.EmbeddingEngine
    import com.vela.app.vault.SearchResult
    import com.vela.app.vault.VaultRegistry
    import dagger.hilt.android.lifecycle.HiltViewModel
    import kotlinx.coroutines.Dispatchers
    import kotlinx.coroutines.Job
    import kotlinx.coroutines.flow.*
    import kotlinx.coroutines.launch
    import java.io.File
    import javax.inject.Inject

    data class FileEntry(
        val name:         String,
        val relativePath: String,
        val isDirectory:  Boolean,
        val size:         Long,
        val extension:    String,
        val lastModified: Long,
    )

    @HiltViewModel
    class VaultBrowserViewModel @Inject constructor(
        private val vaultRegistry:   VaultRegistry,
        private val embeddingEngine: EmbeddingEngine,
    ) : ViewModel() {

        // ─── Vault selection ───────────────────────────────────────────────────
        val allVaults: StateFlow<List<VaultEntity>> = vaultRegistry.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

        private val _activeVault = MutableStateFlow<VaultEntity?>(null)
        val activeVault: StateFlow<VaultEntity?> = _activeVault.asStateFlow()

        fun setVault(vault: VaultEntity) {
            _activeVault.value = vault
            navigateTo("")       // reset to root
            triggerIndexing(vault)
        }

        // ─── File tree ────────────────────────────────────────────────────────
        private val _currentPath = MutableStateFlow("")
        val currentPath: StateFlow<String> = _currentPath.asStateFlow()

        private val _entries = MutableStateFlow<List<FileEntry>>(emptyList())
        val entries: StateFlow<List<FileEntry>> = _entries.asStateFlow()

        fun navigateTo(relativePath: String) {
            _currentPath.value = relativePath
            val vault = _activeVault.value ?: return
            viewModelScope.launch(Dispatchers.IO) {
                val dir = if (relativePath.isEmpty()) File(vault.localPath)
                          else File(vault.localPath, relativePath)
                _entries.value = dir.listFiles()
                    ?.sortedWith(compareBy({ !it.isDirectory }, { it.name }))
                    ?.map { f ->
                        FileEntry(
                            name         = f.name,
                            relativePath = f.relativeTo(File(vault.localPath)).path,
                            isDirectory  = f.isDirectory,
                            size         = f.length(),
                            extension    = f.extension.lowercase(),
                            lastModified = f.lastModified(),
                        )
                    }
                    ?: emptyList()
            }
        }

        fun navigateUp() {
            val current = _currentPath.value
            _currentPath.value = if (current.contains(File.separator)) {
                current.substringBeforeLast(File.separator)
            } else ""
            navigateTo(_currentPath.value)
        }

        // ─── Search ───────────────────────────────────────────────────────────
        private val _searchResults = MutableStateFlow<List<SearchResult>>(emptyList())
        val searchResults: StateFlow<List<SearchResult>> = _searchResults.asStateFlow()

        private val _isSearching = MutableStateFlow(false)
        val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

        private var searchJob: Job? = null

        fun search(query: String) {
            searchJob?.cancel()
            if (query.isBlank()) { _searchResults.value = emptyList(); return }
            searchJob = viewModelScope.launch {
                _isSearching.value = true
                val vaults = listOfNotNull(_activeVault.value)
                _searchResults.value = embeddingEngine.search(query, vaults)
                _isSearching.value = false
            }
        }

        // ─── Indexing ─────────────────────────────────────────────────────────
        private val _indexProgress = MutableStateFlow<Pair<Int, Int>?>(null) // done/total or null
        val indexProgress: StateFlow<Pair<Int, Int>?> = _indexProgress.asStateFlow()

        private val _isConfigured = MutableStateFlow(embeddingEngine.isConfigured)
        val isConfigured: StateFlow<Boolean> = _isConfigured.asStateFlow()

        fun triggerIndexing(vault: VaultEntity) {
            if (!embeddingEngine.isConfigured) return
            viewModelScope.launch {
                _indexProgress.value = 0 to 0
                embeddingEngine.indexVault(vault) { done, total ->
                    _indexProgress.value = done to total
                }
                _indexProgress.value = null
            }
        }
    }
    