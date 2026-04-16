package com.vela.app.data.db

    import androidx.room.Dao
    import androidx.room.Insert
    import androidx.room.OnConflictStrategy
    import androidx.room.Query

    @Dao
    interface VaultEmbeddingDao {

        @Insert(onConflict = OnConflictStrategy.REPLACE)
        suspend fun upsert(entity: VaultEmbeddingEntity)

        @Query("SELECT * FROM vault_embeddings WHERE vaultId = :vaultId")
        suspend fun getByVault(vaultId: String): List<VaultEmbeddingEntity>

        @Query(
            "SELECT fileModified FROM vault_embeddings " +
            "WHERE vaultId = :vaultId AND filePath = :filePath LIMIT 1"
        )
        suspend fun getFileModified(vaultId: String, filePath: String): Long?

        @Query("DELETE FROM vault_embeddings WHERE vaultId = :vaultId AND filePath = :filePath")
        suspend fun deleteFile(vaultId: String, filePath: String)

        @Query("SELECT COUNT(*) FROM vault_embeddings WHERE vaultId = :vaultId")
        suspend fun countByVault(vaultId: String): Int

        @Query("DELETE FROM vault_embeddings WHERE vaultId = :vaultId")
        suspend fun deleteVault(vaultId: String)
    }
    