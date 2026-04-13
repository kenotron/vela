    package com.vela.app.data.db

    import androidx.room.*
    import kotlinx.coroutines.flow.Flow

    @Dao
    interface SshNodeDao {
        @Query("SELECT * FROM ssh_nodes ORDER BY addedAt ASC")
        fun getAllNodes(): Flow<List<SshNodeEntity>>

        @Insert(onConflict = OnConflictStrategy.REPLACE)
        suspend fun insert(node: SshNodeEntity)

        @Query("DELETE FROM ssh_nodes WHERE id = :id")
        suspend fun delete(id: String)

        @Query("SELECT * FROM ssh_nodes WHERE id = :id")
        suspend fun getById(id: String): SshNodeEntity?
    }
    