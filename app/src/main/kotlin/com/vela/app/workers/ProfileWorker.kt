package com.vela.app.workers

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.vela.app.ai.AmplifierSession
import com.vela.app.data.db.TurnDao
import com.vela.app.data.db.VaultEntity
import com.vela.app.vault.VaultManager
import com.vela.app.vault.VaultRegistry
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@HiltWorker
class ProfileWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val vaultRegistry: VaultRegistry,
    private val vaultManager: VaultManager,
    private val amplifierSession: AmplifierSession,
    private val turnDao: TurnDao,
) : CoroutineWorker(context, params) {

    companion object {
        const val TAG = "profile_worker"
        private const val PROFILE_PATH = ".vela/profile.md"
        private const val TAG_LOG = "ProfileWorker"

        private const val SYSTEM_PROMPT =
            "You maintain a Vela user profile document. Return ONLY the complete updated profile.md. No explanation."

        private const val UPDATE_INSTRUCTIONS = """
Update the profile document:
1. YAML frontmatter — add new facts conservatively; never remove existing facts without clear evidence; update last_updated to today's ISO date; increment profile_version by 1
2. <vela:knows vault="X" updated="YYYY-MM-DD"> blocks — update each vault's block; add new blocks for new vaults
3. <vela:pulse> — prepend new session entries; keep only the 20 most recent entries
Return ONLY the complete updated profile.md. No markdown fences. No explanation."""
    }

    override suspend fun doWork(): Result {
        val vaults = vaultRegistry.enabledVaults.first()
        if (vaults.isEmpty()) {
            Log.d(TAG_LOG, "No vaults configured — skipping profile update")
            return Result.success()
        }

        val primaryVault = vaults.minByOrNull { it.createdAt } ?: return Result.success()
        val profileFile = File(primaryVault.localPath, PROFILE_PATH)

        val currentProfile = if (profileFile.exists()) profileFile.readText() else ""
        val lastUpdated = parseLastUpdated(currentProfile)

        val vaultDelta = buildVaultDelta(vaults, lastUpdated)
        val recentTurns = turnDao.getRecentCompletedTurns(30)
        val sessionLines = recentTurns.map { twe ->
            val date = Instant.ofEpochMilli(twe.turn.timestamp)
                .atOffset(ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_LOCAL_DATE)
            "- $date: Session — ${twe.turn.userMessage.take(80)}"
        }

        val prompt = buildPrompt(currentProfile, vaultDelta, sessionLines)

        setProgress(workDataOf("status" to "Generating profile update…"))

        val sb = StringBuilder()
        try {
            amplifierSession.runTurn(
                historyJson       = "[]",
                userInput         = prompt,
                userContentJson   = null,
                systemPrompt      = SYSTEM_PROMPT,
                onToolStart       = { name, _ ->
                    setProgress(workDataOf("status" to toolActivityLabel(name)))
                    ""
                },
                onToolEnd         = { _, _ ->
                    setProgress(workDataOf("status" to "Processing…"))
                },
                onToken           = { token -> sb.append(token) },
                onProviderRequest = { null },
                onServerTool      = { _, _ -> },
            )
        } catch (e: Exception) {
            Log.e(TAG_LOG, "LLM call failed — profile not updated", e)
            return Result.failure()
        }

        val updated = sb.toString().trim()
        if (updated.isEmpty()) {
            Log.e(TAG_LOG, "LLM returned empty response — profile not updated")
            return Result.failure()
        }

        profileFile.parentFile?.mkdirs()
        profileFile.writeText(updated)
        Log.i(TAG_LOG, "Profile updated: ${profileFile.absolutePath}")
        return Result.success()
    }

    private fun toolActivityLabel(toolName: String): String = when (toolName) {
        "read_file"  -> "Reading vault content…"
        "write_file" -> "Writing profile…"
        "edit_file"  -> "Updating profile…"
        "glob"       -> "Scanning vault files…"
        "grep"       -> "Searching content…"
        else         -> "${toolName.replace('_', ' ').replaceFirstChar { it.uppercase() }}…"
    }

    private fun parseLastUpdated(profile: String): Long {
        if (profile.isBlank()) return 0L
        val match = Regex("""last_updated:\s*(.+)""").find(profile) ?: return 0L
        return try {
            Instant.parse(match.groupValues[1].trim()).toEpochMilli()
        } catch (e: Exception) { 0L }
    }

    private fun buildVaultDelta(vaults: List<VaultEntity>, sinceMs: Long): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (vault in vaults) {
            val root = File(vault.localPath)
            if (!root.exists()) continue
            val changed = root.walkTopDown()
                .filter { it.isFile && it.lastModified() > sinceMs }
                .filter { !it.path.contains("/.vela/") }
                .take(50)
                .joinToString("\n\n---\n\n") { file ->
                    "# ${file.relativeTo(root).path}\n${file.readText().take(8_192)}"
                }
            if (changed.isNotEmpty()) result[vault.name] = changed
        }
        return result
    }

    private fun buildPrompt(
        current: String,
        vaultDelta: Map<String, String>,
        sessionLines: List<String>,
    ): String = buildString {
        appendLine("Current profile:")
        appendLine(current.ifBlank { "(none — first run, create from scratch)" })
        appendLine()
        if (vaultDelta.isNotEmpty()) {
            appendLine("New vault content since last update:")
            vaultDelta.forEach { (name, content) ->
                appendLine("=== $name ===")
                appendLine(content.take(16_384))
                appendLine()
            }
        }
        if (sessionLines.isNotEmpty()) {
            appendLine("Recent sessions:")
            sessionLines.forEach { appendLine(it) }
            appendLine()
        }
        appendLine(UPDATE_INSTRUCTIONS)
    }

}
