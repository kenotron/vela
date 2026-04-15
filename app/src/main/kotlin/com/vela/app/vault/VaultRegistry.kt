package com.vela.app.vault

import com.vela.app.data.db.VaultDao
import com.vela.app.data.db.VaultEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Singleton

/**
 * Registry for all configured vaults.
 *
 * Exposes [enabledVaults] as a live [StateFlow] derived from the Room DAO — always
 * reflects the current toggle state without an explicit refresh call.
 *
 * Note: takes [root] (the vaults directory) directly rather than a [VaultManager]
 * reference, which avoids a DI cycle with [VaultManager] depending on the enabled-vault
 * paths that this registry provides.
 */
@Singleton
class VaultRegistry(
    private val dao: VaultDao,
    private val root: File,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Live list of currently-enabled vaults, always in sync with the DB. */
    val enabledVaults: StateFlow<List<VaultEntity>> = observeAll()
        .map { list -> list.filter { it.isEnabled } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun observeAll(): Flow<List<VaultEntity>> = dao.observeAll()

    suspend fun getEnabledVaults(): List<VaultEntity> = dao.getEnabled()

    suspend fun addVault(name: String): VaultEntity {
        val id = UUID.randomUUID().toString()
        val localPath = File(root, id).absolutePath
        File(localPath).mkdirs()
        val entity = VaultEntity(id = id, name = name, localPath = localPath)
        dao.insert(entity)
        return entity
    }

    suspend fun setEnabled(vaultId: String, enabled: Boolean) {
        val entity = dao.getById(vaultId) ?: return
        dao.update(entity.copy(isEnabled = enabled))
    }

    suspend fun delete(vaultId: String) {
        val entity = dao.getById(vaultId) ?: return
        val vaultDir = File(entity.localPath)
        // Bounds check: verify the path falls within root
        val canonical = try { vaultDir.canonicalPath } catch (_: IOException) { return }
        val rootCanonical = root.canonicalPath
        if (!canonical.startsWith(rootCanonical + File.separator) && canonical != rootCanonical) return
        // Filesystem first: if this fails, the DB record is untouched and the vault is still recoverable
        vaultDir.deleteRecursively()
        dao.delete(entity)
    }

    suspend fun resolveVaultForPath(file: File): VaultEntity? {
        val filePath = try { file.canonicalPath } catch (_: IOException) { return null }
        return getEnabledVaults().firstOrNull { vault ->
            val vaultCanonical = try { File(vault.localPath).canonicalPath } catch (_: IOException) { return@firstOrNull false }
            filePath == vaultCanonical || filePath.startsWith(vaultCanonical + File.separator)
        }
    }
}
