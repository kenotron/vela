package com.vela.app.ui.profile

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.vela.app.vault.VaultRegistry
import com.vela.app.workers.ProfileWorker
import com.vela.app.workers.ProfileWorkerScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject

data class ProfileData(
    val name: String = "",
    val role: String = "",
    val location: String = "",
    val keyProjects: List<String> = emptyList(),
    val interests: List<String> = emptyList(),
    val keyPeople: List<String> = emptyList(),
    val lastUpdated: String = "",
    val lastUpdatedMs: Long = 0L,
    val knowledgeBlocks: List<KnowledgeBlock> = emptyList(),
    val pulseEntries: List<String> = emptyList(),
)

data class KnowledgeBlock(
    val vaultName: String,
    val updatedDate: String,
    val content: String,
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vaultRegistry: VaultRegistry,
    private val scheduler: ProfileWorkerScheduler,
) : ViewModel() {

    private val workManager = WorkManager.getInstance(context)

    private val _profileData = MutableStateFlow<ProfileData?>(null)
    val profileData: StateFlow<ProfileData?> = _profileData.asStateFlow()

    private val _hasVault = MutableStateFlow(false)
    val hasVault: StateFlow<Boolean> = _hasVault.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    init {
        viewModelScope.launch {
            vaultRegistry.enabledVaults.collect { vaults ->
                _hasVault.value = vaults.isNotEmpty()
                if (vaults.isNotEmpty()) {
                    loadProfile(vaults.minByOrNull { it.createdAt }!!.localPath)
                } else {
                    _profileData.value = null
                }
            }
        }
        viewModelScope.launch {
            workManager.getWorkInfosByTagFlow(ProfileWorker.TAG).collect { infos ->
                _isRefreshing.value = infos.any { it.state == WorkInfo.State.RUNNING }
                // Reload profile after a completed refresh
                if (infos.any { it.state == WorkInfo.State.SUCCEEDED }) {
                    val vaults = vaultRegistry.enabledVaults.value
                    if (vaults.isNotEmpty()) {
                        loadProfile(vaults.minByOrNull { it.createdAt }!!.localPath)
                    }
                }
            }
        }
    }

    fun refresh() = scheduler.triggerRefresh()

    private fun loadProfile(vaultLocalPath: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val file = File(vaultLocalPath, ".vela/profile.md")
            _profileData.value = if (file.exists()) parseProfileMdInternal(file.readText())
                                  else ProfileData()
        }
    }

    companion object {

        /**
         * Exposed as `internal` so unit tests can exercise parsing without
         * constructing the ViewModel (which requires DI / Android context).
         */
        internal fun parseProfileMdInternal(content: String): ProfileData {
            val frontmatter = extractFrontmatter(content)
            val lastUpdatedMs = parseFrontmatterInstant(frontmatter["last_updated"])
            return ProfileData(
                name            = frontmatter["name"] ?: "",
                role            = frontmatter["role"] ?: "",
                location        = frontmatter["location"] ?: "",
                keyProjects     = parseFrontmatterList(content, "key_projects"),
                interests       = parseFrontmatterList(content, "interests"),
                keyPeople       = parseFrontmatterList(content, "key_people"),
                lastUpdated     = formatRelativeInternal(lastUpdatedMs),
                lastUpdatedMs   = lastUpdatedMs,
                knowledgeBlocks = extractKnowledgeBlocks(content),
                pulseEntries    = extractPulse(content),
            )
        }

        /**
         * Exposed as `internal` for testability with a deterministic `now` value.
         * Production callers omit `now` and get `System.currentTimeMillis()`.
         */
        internal fun formatRelativeInternal(
            epochMs: Long,
            now: Long = System.currentTimeMillis(),
        ): String {
            if (epochMs == 0L) return "never"
            val diff = now - epochMs
            return when {
                diff < 60_000L         -> "just now"
                diff < 3_600_000L      -> "${diff / 60_000}m ago"
                diff < 86_400_000L     -> "${diff / 3_600_000}h ago"
                diff < 7 * 86_400_000L -> "${diff / 86_400_000}d ago"
                else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                    .withZone(ZoneId.systemDefault())
                    .format(Instant.ofEpochMilli(epochMs))
            }
        }

        private fun extractFrontmatter(content: String): Map<String, String> {
            val parts = content.split("---")
            if (parts.size < 3) return emptyMap()
            return parts[1].lines()
                .filter { it.contains(":") && !it.trimStart().startsWith("-") }
                .associate { line ->
                    val idx = line.indexOf(':')
                    line.substring(0, idx).trim() to line.substring(idx + 1).trim()
                }
        }

        private fun parseFrontmatterList(content: String, key: String): List<String> {
            val yaml = content.split("---").getOrElse(1) { "" }
            val inlineLine = yaml.lines().firstOrNull { it.trimStart().startsWith("$key:") }
            val inlineValue = inlineLine?.substringAfter(":")?.trim()
            if (inlineValue != null && inlineValue.startsWith("[")) {
                return inlineValue.trim('[', ']').split(",")
                    .map { it.trim().trim('"', '\'') }.filter { it.isNotEmpty() }
            }
            val lines = yaml.lines()
            val keyIdx = lines.indexOfFirst { it.trimStart().startsWith("$key:") }
            if (keyIdx < 0) return emptyList()
            return lines.drop(keyIdx + 1)
                .takeWhile { it.trimStart().startsWith("-") || it.isBlank() }
                .filter { it.trimStart().startsWith("-") }
                .map { it.trimStart().removePrefix("-").trim() }
        }

        private fun extractKnowledgeBlocks(content: String): List<KnowledgeBlock> {
            // Non-greedy [^>]*? prevents the first wildcard from consuming the `updated`
            // attribute before the optional capture group gets a chance to match it.
            val regex = Regex(
                """<vela:knows[^>]*?vault="([^"]+)"[^>]*?(?:\s+updated="([^"]*)")?[^>]*>([\s\S]*?)</vela:knows>"""
            )
            return regex.findAll(content).map { m ->
                KnowledgeBlock(m.groupValues[1], m.groupValues[2], m.groupValues[3].trim())
            }.toList()
        }

        private fun extractPulse(content: String): List<String> {
            val block = Regex("""<vela:pulse>([\s\S]*?)</vela:pulse>""")
                .find(content)?.groupValues?.getOrNull(1) ?: return emptyList()
            return block.lines().map { it.trim() }.filter { it.startsWith("-") }
        }

        private fun parseFrontmatterInstant(value: String?): Long {
            if (value.isNullOrBlank()) return 0L
            return try { Instant.parse(value).toEpochMilli() } catch (e: Exception) { 0L }
        }
    }
}
