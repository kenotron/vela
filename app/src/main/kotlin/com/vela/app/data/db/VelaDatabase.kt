package com.vela.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [MessageEntity::class, ConversationEntity::class, SshNodeEntity::class],
    version = 5,
    exportSchema = true,
)
abstract class VelaDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun conversationDao(): ConversationDao
    abstract fun sshNodeDao(): SshNodeDao
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
/** v4→v5: replace single `host` column with `hosts` (comma-separated ordered list). */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE ssh_nodes_new (id TEXT NOT NULL PRIMARY KEY, label TEXT NOT NULL, hosts TEXT NOT NULL, port INTEGER NOT NULL, username TEXT NOT NULL, addedAt INTEGER NOT NULL)")
        // Preserve existing single host in the new comma-separated column
        db.execSQL("INSERT INTO ssh_nodes_new SELECT id, label, host, port, username, addedAt FROM ssh_nodes")
        db.execSQL("DROP TABLE ssh_nodes")
        db.execSQL("ALTER TABLE ssh_nodes_new RENAME TO ssh_nodes")
    }
}
