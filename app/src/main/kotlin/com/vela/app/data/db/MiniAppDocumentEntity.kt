package com.vela.app.data.db

    import androidx.room.Entity

    /**
     * Schemaless document store backing `vela.db` in the mini app SDK.
     *
     * Three scope tiers:
     *  - [scopePrefix] = "local"  → [collection] is "{itemPath}::{name}", one namespace per vault file
     *  - [scopePrefix] = "type"   → [collection] is "{contentType}::{name}", shared across same-type renderers
     *  - [scopePrefix] = "global" → [collection] is the bare collection name, shared across all mini apps
     *
     * [data] is a JSON text blob; no schema enforced at the DB layer.
     */
    @Entity(
        tableName = "mini_app_documents",
        primaryKeys = ["scopePrefix", "collection", "id"],
    )
    data class MiniAppDocumentEntity(
        /** "local" | "global" | "type" — stored without the colon. */
        val scopePrefix: String,
        /**
         * For "global": bare collection name, e.g. "shopping-list-queue".
         * For "type":   "{contentType}::{name}", e.g. "recipe::recent-ingredients".
         * For "local":  "{itemPath}::{name}",    e.g. "recipes/carbonara.md::steps".
         */
        val collection: String,
        val id: String,
        /** JSON text — arbitrary shape chosen by the renderer. */
        val data: String,
        /** `System.currentTimeMillis()` at last write. */
        val updatedAt: Long,
    )
    