package com.vela.app.share

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vela.app.data.db.ConversationDao
import com.vela.app.data.db.ConversationEntity
import com.vela.app.vault.VaultRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import java.util.UUID
import javax.inject.Inject

sealed class ShareState {
    data object Idle : ShareState()
    data class Preview(val label: String, val contentSummary: String) : ShareState()
    data object Processing : ShareState()
    data class Done(val conversationId: String) : ShareState()
    data class Error(val message: String) : ShareState()
}

@HiltViewModel
class ShareViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val conversationDao: ConversationDao,
    private val vaultRegistry: VaultRegistry,
) : ViewModel() {

    private val _state = MutableStateFlow<ShareState>(ShareState.Idle)
    val state: StateFlow<ShareState> = _state.asStateFlow()

    // Staged content waiting to be processed
    private var pendingText: String? = null
    private var pendingFilePath: String? = null
    private var pendingMimeType: String? = null

    fun prepareText(text: String, sourceApp: String?) {
        pendingText = text
        pendingFilePath = null
        val label = if (sourceApp != null) "Text from $sourceApp" else "Shared text"
        val preview = text.take(200).let { if (text.length > 200) "$it…" else it }
        _state.value = ShareState.Preview(label, preview)
    }

    fun prepareFile(uri: Uri, mimeType: String?, sourceApp: String?) {
        viewModelScope.launch {
            _state.value = ShareState.Processing
            try {
                val ext = mimeType?.substringAfterLast('/') ?: "bin"
                val dest = File(context.filesDir, "share/${UUID.randomUUID()}.$ext")
                dest.parentFile?.mkdirs()
                context.contentResolver.openInputStream(uri)?.use { input: InputStream ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                pendingFilePath = dest.absolutePath
                pendingMimeType = mimeType
                val label = when {
                    mimeType?.startsWith("image/") == true -> if (sourceApp != null) "Image from $sourceApp" else "Shared image"
                    mimeType == "application/pdf" -> if (sourceApp != null) "PDF from $sourceApp" else "Shared PDF"
                    else -> if (sourceApp != null) "File from $sourceApp" else "Shared file"
                }
                _state.value = ShareState.Preview(label, dest.name)
            } catch (e: Exception) {
                _state.value = ShareState.Error("Could not read file: ${e.message}")
            }
        }
    }

    fun processIntoVault() {
        viewModelScope.launch {
            _state.value = ShareState.Processing
            try {
                val enabledVaults = vaultRegistry.getEnabledVaults()
                val vaultNote = if (enabledVaults.isEmpty()) ""
                    else "\n\n[Vault: ${enabledVaults.first().name}]"

                // Build the user message that lifeos will process
                val userMessage = buildString {
                    when {
                        pendingText != null -> {
                            appendLine("Please process this shared content into the vault:")
                            appendLine()
                            append(pendingText)
                        }
                        pendingFilePath != null -> {
                            val mime = pendingMimeType ?: "unknown"
                            appendLine("Please process this shared file into the vault:")
                            appendLine("File: $pendingFilePath")
                            appendLine("Type: $mime")
                            if (mime.startsWith("image/")) {
                                appendLine("Analyze the image and file it appropriately.")
                            }
                        }
                        else -> append("Process shared content into vault.")
                    }
                    append(vaultNote)
                }

                val conv = ConversationEntity(
                    id        = UUID.randomUUID().toString(),
                    title     = buildTitle(),
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                    mode      = "vault",
                )
                conversationDao.insert(conv)
                _state.value = ShareState.Done(conv.id)
            } catch (e: Exception) {
                _state.value = ShareState.Error("Could not create vault session: ${e.message}")
            }
        }
    }

    private fun buildTitle(): String = when {
        pendingText != null -> "Shared: ${pendingText!!.take(40).replace('\n', ' ')}…"
        pendingFilePath != null -> "Shared: ${File(pendingFilePath!!).name}"
        else -> "Shared content"
    }

    fun reset() { _state.value = ShareState.Idle; pendingText = null; pendingFilePath = null }
}
