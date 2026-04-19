package com.vela.app.data.db

    import androidx.room.Entity
    import androidx.room.PrimaryKey

    /**
     * One row per vault content type in the capabilities graph.
     *
     * [provides] and [consumes] are JSON arrays of `{id, description}` objects.
     * [dbCollections] is a JSON array of `{scope, collection, description}` objects
     * documenting which `mini_app_documents` collections this renderer reads/writes.
     * All three are stored as opaque TEXT — parse with `org.json.JSONArray` at the
     * repository layer, never with a Room TypeConverter.
     */
    @Entity(tableName = "mini_app_registry")
    data class MiniAppRegistryEntity(
        @PrimaryKey val contentType: String,
        /** Absolute path to `.vela/renderers/{contentType}/renderer.html` on disk. */
        val rendererPath: String,
        /** JSON array: `[{"id":"ingredients_list","description":"..."}]` */
        val provides: String,
        /** JSON array: `[{"id":"shopping-list.add_items","description":"..."}]` */
        val consumes: String,
        /** JSON array: `[{"scope":"global","collection":"shopping-list-queue","description":"..."}]` */
        val dbCollections: String,
        /** Monotonically increasing; incremented by RendererGenerator on every regeneration. */
        val version: Int,
        /** `System.currentTimeMillis()` at last use — for LRU eviction in a future pass. */
        val lastUsed: Long,
    )
    