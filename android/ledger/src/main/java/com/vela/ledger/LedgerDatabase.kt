package com.vela.ledger

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.vela.ledger.server.DecisionOutboxDao
import com.vela.ledger.server.DecisionOutboxEntity

/**
 * Version bumped 1 -> 2 to add [DecisionOutboxEntity] (goal: ledger-l2-android, #37/#38 --
 * the durable offline decision queue, design doc §5.4/§6.2). No pre-existing installs of
 * this pre-release scaffold app carry user data worth a real migration, so
 * [Room.databaseBuilder] falls back to destructive migration below rather than writing
 * a `Migration(1, 2)` for a table addition with no data to preserve. Revisit once this
 * app has a real release with installed-base data.
 */
@Database(entities = [JobEntity::class, DecisionOutboxEntity::class], version = 2, exportSchema = true)
abstract class LedgerDatabase : RoomDatabase() {

    abstract fun jobDao(): JobDao

    abstract fun decisionOutboxDao(): DecisionOutboxDao

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
