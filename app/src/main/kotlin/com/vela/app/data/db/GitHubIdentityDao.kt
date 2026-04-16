package com.vela.app.data.db

    import androidx.room.*
    import kotlinx.coroutines.flow.Flow

    @Dao
    interface GitHubIdentityDao {
        @Query("SELECT * FROM github_identities ORDER BY isDefault DESC, addedAt ASC")
        fun getAll(): Flow<List<GitHubIdentityEntity>>

        @Query("SELECT * FROM github_identities ORDER BY isDefault DESC, addedAt ASC")
        suspend fun getAllSync(): List<GitHubIdentityEntity>

        @Query("SELECT * FROM github_identities WHERE id = :id")
        suspend fun getById(id: String): GitHubIdentityEntity?

        @Query("SELECT * FROM github_identities WHERE isDefault = 1 LIMIT 1")
        suspend fun getDefault(): GitHubIdentityEntity?

        @Insert(onConflict = OnConflictStrategy.REPLACE)
        suspend fun upsert(entity: GitHubIdentityEntity)

        @Query("UPDATE github_identities SET isDefault = 0")
        suspend fun clearDefault()

        @Query("UPDATE github_identities SET isDefault = 1 WHERE id = :id")
        suspend fun setDefault(id: String)

        @Query("DELETE FROM github_identities WHERE id = :id")
        suspend fun delete(id: String)
    }
    