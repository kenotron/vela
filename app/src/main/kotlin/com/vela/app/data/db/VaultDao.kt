package com.vela.app.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface VaultDao {
    @Query("SELECT * FROM vaults ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<VaultEntity>>

    @Query("SELECT * FROM vaults WHERE isEnabled = 1")
    suspend fun getEnabled(): List<VaultEntity>

    @Query("SELECT * FROM vaults WHERE id = :id")
    suspend fun getById(id: String): VaultEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(vault: VaultEntity)

    @Update
    suspend fun update(vault: VaultEntity)

    @Delete
    suspend fun delete(vault: VaultEntity)
}
