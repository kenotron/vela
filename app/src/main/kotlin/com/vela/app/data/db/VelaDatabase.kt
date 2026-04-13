    package com.vela.app.data.db

    import androidx.room.Database
    import androidx.room.RoomDatabase
    import androidx.room.migration.Migration
    import androidx.sqlite.db.SupportSQLiteDatabase

    @Database(entities = [MessageEntity::class], version = 2, exportSchema = true)
    abstract class VelaDatabase : RoomDatabase() {
        abstract fun messageDao(): MessageDao
    }

    /** Add toolMeta column (nullable) — no data loss. */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE messages ADD COLUMN toolMeta TEXT")
        }
    }
    