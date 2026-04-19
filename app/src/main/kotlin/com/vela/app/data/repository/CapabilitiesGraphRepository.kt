package com.vela.app.data.repository

    import com.vela.app.data.db.MiniAppRegistryDao
    import com.vela.app.data.db.MiniAppRegistryEntity
    import kotlinx.coroutines.flow.Flow
    import javax.inject.Inject
    import javax.inject.Singleton

    /**
     * Single source of truth for the mini app capabilities graph.
     *
     * Every renderer that has been generated for a content type has one row here.
     * [getAll] is the reactive snapshot used by [MiniAppContainer] to keep
     * `__VELA_CONTEXT__.capabilities` current. [upsert] is called by [RendererGenerator]
     * after a successful generation.
     */
    @Singleton
    class CapabilitiesGraphRepository @Inject constructor(
        private val dao: MiniAppRegistryDao,
    ) {
        /** Live snapshot of all registered mini apps, ordered most-recently-used first. */
        fun getAll(): Flow<List<MiniAppRegistryEntity>> = dao.getAll()

        /**
         * Persist or update a renderer manifest.
         * Called by [RendererGenerator] after writing the HTML to disk.
         */
        suspend fun upsert(entity: MiniAppRegistryEntity) = dao.upsert(entity)

        /**
         * Returns the registered entity for [contentType], or `null` if no renderer
         * has been generated yet. Used by [RendererGenerator] to check existence and
         * to read the current [MiniAppRegistryEntity.version] before incrementing.
         */
        suspend fun getByContentType(contentType: String): MiniAppRegistryEntity? =
            dao.getByContentType(contentType)
    }
    