package com.vela.app.data.db

    import androidx.room.Database
    import androidx.room.RoomDatabase

    @Database(entities = [MessageEntity::class], version = 1, exportSchema = true)
    abstract class VelaDatabase : RoomDatabase() {
        abstract fun messageDao(): MessageDao
    }
    