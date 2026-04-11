package com.vela.app.di

    import android.content.Context
    import androidx.room.Room
    import com.vela.app.ai.FakeGemmaEngine
    import com.vela.app.ai.GemmaEngine
    import com.vela.app.data.db.MessageDao
    import com.vela.app.data.db.VelaDatabase
    import dagger.Module
    import dagger.Provides
    import dagger.hilt.InstallIn
    import dagger.hilt.android.qualifiers.ApplicationContext
    import dagger.hilt.components.SingletonComponent
    import javax.inject.Singleton

    @Module
    @InstallIn(SingletonComponent::class)
    object AppModule {

        @Provides
        @Singleton
        fun provideVelaDatabase(@ApplicationContext context: Context): VelaDatabase =
            Room.databaseBuilder(context, VelaDatabase::class.java, "vela_database").build()

        @Provides
        fun provideMessageDao(database: VelaDatabase): MessageDao = database.messageDao()

        @Provides
        @Singleton
        fun provideGemmaEngine(): GemmaEngine = FakeGemmaEngine()
    }
    