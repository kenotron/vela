package com.vela.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MiniAppDocumentDao {

    /**
     * Upsert a document. REPLACE deletes the existing row (matched by composite PK)
     * and inserts the new one — correct for schemaless document semantics.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MiniAppDocumentEntity)

    @Query("""
        SELECT * FROM mini_app_documents
        WHERE scopePrefix = :scopePrefix AND collection = :collection AND id = :id
        LIMIT 1
    """)
    suspend fun get(scopePrefix: String, collection: String, id: String): MiniAppDocumentEntity?

    @Query("""
        DELETE FROM mini_app_documents
        WHERE scopePrefix = :scopePrefix AND collection = :collection AND id = :id
    """)
    suspend fun delete(scopePrefix: String, collection: String, id: String)

    /**
     * Reactive query for `vela.db.watch(collection, cb)`.
     * Room emits a new list every time any row in the result set changes.
     * Ordered by [MiniAppDocumentEntity.updatedAt] descending.
     *
     * Note: If two writes arrive before the collector resumes, it sees only the final state.
     */
    @Query("""
        SELECT * FROM mini_app_documents
        WHERE scopePrefix = :scopePrefix AND collection = :collection
        ORDER BY updatedAt DESC
    """)
    fun watch(scopePrefix: String, collection: String): Flow<List<MiniAppDocumentEntity>>

    /** Delete all documents in a scoped collection — used for cleanup. */
    @Query("""
        DELETE FROM mini_app_documents
        WHERE scopePrefix = :scopePrefix AND collection = :collection
    """)
    suspend fun deleteCollection(scopePrefix: String, collection: String)
}
