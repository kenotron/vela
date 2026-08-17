package com.vela.ledger

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [JobEntity::class], version = 1, exportSchema = true)
abstract class LedgerDatabase : RoomDatabase() {

    abstract fun jobDao(): JobDao

    companion object {
        private const val DB_NAME = "vela-ledger.db"

        @Volatile
        private var instance: LedgerDatabase? = null

        /**
         * Singleton accessor. Pass [inMemory] = true for tests that don't need
         * cross-process persistence; production callers should always use the
         * default (file-backed) database.
         */
        fun getInstance(context: Context, inMemory: Boolean = false): LedgerDatabase {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                val built = if (inMemory) {
                    Room.inMemoryDatabaseBuilder(context.applicationContext, LedgerDatabase::class.java)
                        .build()
                } else {
                    Room.databaseBuilder(context.applicationContext, LedgerDatabase::class.java, DB_NAME)
                        .build()
                }
                instance = built
                return built
            }
        }
    }
}
