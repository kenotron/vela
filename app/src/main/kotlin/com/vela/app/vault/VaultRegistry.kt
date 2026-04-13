package com.vela.app.vault

import com.vela.app.data.db.VaultDao
import com.vela.app.data.db.VaultEntity
import kotlinx.coroutines.flow.Flow
import java.io.File
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
        dao.delete(entity)
        File(entity.localPath).deleteRecursively()
    }

    suspend fun resolveVaultForPath(file: File): VaultEntity? =
        getEnabledVaults().firstOrNull { vault ->
            file.canonicalPath.startsWith(File(vault.localPath).canonicalPath)
        }
}
