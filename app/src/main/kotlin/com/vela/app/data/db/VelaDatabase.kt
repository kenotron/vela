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
    ],
    version = 6,
    exportSchema = true,
)
abstract class VelaDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun conversationDao(): ConversationDao
    abstract fun sshNodeDao(): SshNodeDao
    abstract fun turnDao(): TurnDao
    abstract fun turnEventDao(): TurnEventDao
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
