package com.vela.app.data.db

    import androidx.room.Dao
    import androidx.room.Insert
    import androidx.room.OnConflictStrategy
    import androidx.room.Query
    import kotlinx.coroutines.flow.Flow

    @Dao
    interface MiniAppRegistryDao {

        /** Full capabilities graph — ordered most-recently-used first. */
        @Query("SELECT * FROM mini_app_registry ORDER BY lastUsed DESC")
        fun getAll(): Flow<List<MiniAppRegistryEntity>>

        /** Point lookup for renderer existence check before generation. */
        @Query("SELECT * FROM mini_app_registry WHERE contentType = :contentType LIMIT 1")
        suspend fun getByContentType(contentType: String): MiniAppRegistryEntity?

        /**
         * Insert or replace. REPLACE strategy deletes the old row and inserts a new one,
         * which is correct here — the whole manifest is overwritten on each regeneration.
         */
        @Insert(onConflict = OnConflictStrategy.REPLACE)
        suspend fun upsert(entity: MiniAppRegistryEntity)

        @Query("DELETE FROM mini_app_registry WHERE contentType = :contentType")
        suspend fun delete(contentType: String)
    }
    