package com.vela.app.data.db

    import androidx.room.Entity
    import androidx.room.PrimaryKey

    /**
     * One chunk of a vault file with its embedding vector.
     *
     * Files are split into [chunkText] segments of ~1000 chars.
     * [embeddingJson] is a JSON float array produced by Gemini text-embedding-004
     * (768 dims) or OpenAI text-embedding-3-small (1536 dims).
     * [fileModified] is compared to the on-disk lastModified timestamp so unchanged
     * files are skipped on subsequent indexing passes.
     */
    @Entity(tableName = "vault_embeddings")
    data class VaultEmbeddingEntity(
        @PrimaryKey val id: String,
        val vaultId: String,
        val filePath: String,       // relative to vault root
        val chunkIndex: Int,
        val chunkText: String,
        val embeddingJson: String,  // JSON float array
        val fileModified: Long,     // File.lastModified()
    )
    