package com.vela.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        MessageEntity::class,
        ConversationEntity::class,
        SshNodeEntity::class,
        TurnEntity::class,
        TurnEventEntity::class,
        VaultEntity::class,
        VaultEmbeddingEntity::class,
        GitHubIdentityEntity::class,
        MiniAppRegistryEntity::class,
        MiniAppDocumentEntity::class,
    ],
    version = 14,
    exportSchema = true,
)
abstract class VelaDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun conversationDao(): ConversationDao
    abstract fun sshNodeDao(): SshNodeDao
    abstract fun turnDao(): TurnDao
    abstract fun turnEventDao(): TurnEventDao
    abstract fun vaultDao(): VaultDao
    abstract fun vaultEmbeddingDao(): VaultEmbeddingDao
    abstract fun gitHubIdentityDao(): GitHubIdentityDao
    abstract fun miniAppRegistryDao(): MiniAppRegistryDao
    abstract fun miniAppDocumentDao(): MiniAppDocumentDao
}

/** v13→v14: add agentName column to turn_events for delegation name tags. */
val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE turn_events ADD COLUMN agentName TEXT")
    }
}

/** v11→v12: add github_identities table for multi-account GitHub support. */
    val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE github_identities (
                    id TEXT NOT NULL PRIMARY KEY,
                    label TEXT NOT NULL,
                    username TEXT NOT NULL,
                    avatarUrl TEXT NOT NULL,
                    token TEXT NOT NULL,
                    tokenType TEXT NOT NULL,
                    scopes TEXT NOT NULL,
                    addedAt INTEGER NOT NULL,
                    isDefault INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent())
        }
    }

/** v12→v13: add mini_app_registry and mini_app_documents tables for the mini app renderer system. */
val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE mini_app_registry (
                contentType TEXT NOT NULL PRIMARY KEY,
                rendererPath TEXT NOT NULL,
                provides TEXT NOT NULL,
                consumes TEXT NOT NULL,
                dbCollections TEXT NOT NULL,
                version INTEGER NOT NULL,
                lastUsed INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE mini_app_documents (
                scopePrefix TEXT NOT NULL,
                collection TEXT NOT NULL,
                id TEXT NOT NULL,
                data TEXT NOT NULL,
                updatedAt INTEGER NOT NULL,
                PRIMARY KEY (scopePrefix, collection, id)
            )
        """.trimIndent())
    }
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN toolMeta TEXT")
    }
}
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val now = System.currentTimeMillis()
        db.execSQL("CREATE TABLE conversations (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
        db.execSQL("ALTER TABLE messages ADD COLUMN conversationId TEXT NOT NULL DEFAULT ''")
        db.execSQL("INSERT INTO conversations (id,title,createdAt,updatedAt) VALUES ('legacy','Imported Chat',$now,$now)")
        db.execSQL("UPDATE messages SET conversationId = 'legacy'")
    }
}
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE ssh_nodes (id TEXT NOT NULL PRIMARY KEY, label TEXT NOT NULL, host TEXT NOT NULL, port INTEGER NOT NULL, username TEXT NOT NULL, addedAt INTEGER NOT NULL)")
    }
}
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE ssh_nodes_new (id TEXT NOT NULL PRIMARY KEY, label TEXT NOT NULL, hosts TEXT NOT NULL, port INTEGER NOT NULL, username TEXT NOT NULL, addedAt INTEGER NOT NULL)")
        db.execSQL("INSERT INTO ssh_nodes_new SELECT id, label, host, port, username, addedAt FROM ssh_nodes")
        db.execSQL("DROP TABLE ssh_nodes")
        db.execSQL("ALTER TABLE ssh_nodes_new RENAME TO ssh_nodes")
    }
}
/** v5→v6: add turns + turn_events tables. Existing Message rows untouched. */
val MIGRATION_5_6 = object : Migration(5, 6) {

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE turns (
                id TEXT NOT NULL PRIMARY KEY,
                conversationId TEXT NOT NULL,
                userMessage TEXT NOT NULL,
                status TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                error TEXT
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE turn_events (
                id TEXT NOT NULL PRIMARY KEY,
                turnId TEXT NOT NULL,
                seq INTEGER NOT NULL,
                type TEXT NOT NULL,
                text TEXT,
                toolName TEXT,
                toolDisplayName TEXT,
                toolIcon TEXT,
                toolSummary TEXT,
                toolArgs TEXT,
                toolResult TEXT,
                toolStatus TEXT
            )
        """.trimIndent())
    }
}
/** v7→v8: add userContentJson column to turns table (multi-content-block messages). */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE turns ADD COLUMN userContentJson TEXT")
    }
}
/** v6→v7: add vaults table; add mode column to conversations. */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // Create vaults table
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS vaults (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                localPath TEXT NOT NULL,
                isEnabled INTEGER NOT NULL DEFAULT 1,
                createdAt INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
        // Add mode column to conversations
        database.execSQL(
            "ALTER TABLE conversations ADD COLUMN mode TEXT NOT NULL DEFAULT 'default'"
        )
    }
}
/** v10→v11: add nodeType, url, token columns to ssh_nodes for amplifierd support. */
    val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE ssh_nodes ADD COLUMN nodeType TEXT NOT NULL DEFAULT 'ssh'")
            db.execSQL("ALTER TABLE ssh_nodes ADD COLUMN url TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE ssh_nodes ADD COLUMN token TEXT NOT NULL DEFAULT ''")
        }
    }

    /** v9→v10: add vault_embeddings table for semantic file search. */
    val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS vault_embeddings (
                id TEXT NOT NULL PRIMARY KEY,
                vaultId TEXT NOT NULL,
                filePath TEXT NOT NULL,
                chunkIndex INTEGER NOT NULL,
                chunkText TEXT NOT NULL,
                embeddingJson TEXT NOT NULL,
                fileModified INTEGER NOT NULL
            )
        """.trimIndent())
    }
}

/** v8→v9: clear old base64 content block data. */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Clear old base64 content block data — these rows were stored before
        // the ref-based approach. Old attachments on those turns are lost but
        // the app stops crashing.
        db.execSQL("UPDATE turns SET userContentJson = NULL")
    }
}
