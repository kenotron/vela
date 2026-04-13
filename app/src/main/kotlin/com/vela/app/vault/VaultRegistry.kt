package com.vela.app.vault

import com.vela.app.data.db.VaultDao
import com.vela.app.data.db.VaultEntity
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VaultRegistry @Inject constructor(
    private val dao: VaultDao,
    private val vaultManager: VaultManager,
) {
    fun observeAll(): Flow<List<VaultEntity>> = dao.observeAll()

    suspend fun getEnabledVaults(): List<VaultEntity> = dao.getEnabled()

    suspend fun addVault(name: String): VaultEntity {
        val id = UUID.randomUUID().toString()
        val localPath = File(vaultManager.root, id).absolutePath
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
        // Bounds check: verify the path falls within vaultManager.root
        val canonical = try { vaultDir.canonicalPath } catch (_: IOException) { return }
        val rootCanonical = vaultManager.root.canonicalPath
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
