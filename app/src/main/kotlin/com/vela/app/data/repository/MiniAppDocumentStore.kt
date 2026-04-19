package com.vela.app.data.repository

    import com.vela.app.data.db.MiniAppDocumentDao
    import com.vela.app.data.db.MiniAppDocumentEntity
    import kotlinx.coroutines.flow.Flow
    import javax.inject.Inject
    import javax.inject.Singleton

    /**
     * Schemaless document store backing `window.vela.db` in the mini app SDK.
     *
     * All methods accept pre-split scope arguments:
     *   - [scopePrefix]: "local" | "global" | "type"   (no colon)
     *   - [collection]:  the scoped collection name (see [MiniAppDocumentEntity] KDoc for format)
     *
     * Scope splitting and prefix validation happen in [VelaJSInterface] before calling into
     * this store; the store itself applies no validation so it can be called safely from
     * pure Kotlin code without the JS validation overhead.
     */
    @Singleton
    class MiniAppDocumentStore @Inject constructor(
        private val dao: MiniAppDocumentDao,
    ) {
        suspend fun put(scopePrefix: String, collection: String, id: String, data: String) {
            dao.upsert(
                MiniAppDocumentEntity(
                    scopePrefix = scopePrefix,
                    collection  = collection,
                    id          = id,
                    data        = data,
                    updatedAt   = System.currentTimeMillis(),
                )
            )
        }

        /** Returns the raw JSON [data] string, or `null` if the document does not exist. */
        suspend fun get(scopePrefix: String, collection: String, id: String): String? =
            dao.get(scopePrefix, collection, id)?.data

        suspend fun delete(scopePrefix: String, collection: String, id: String) =
            dao.delete(scopePrefix, collection, id)

        /**
         * Returns a [Flow] that emits the full collection list every time any document
         * in that collection is written or deleted.
         *
         * Note: If two writes arrive before the collector resumes, it sees only the final state.
         */
        fun watch(scopePrefix: String, collection: String): Flow<List<MiniAppDocumentEntity>> =
            dao.watch(scopePrefix, collection)
    }
    