    package com.vela.app.di

    import android.content.Context
    import androidx.room.Room
    import com.vela.app.ai.AmplifierSession
    import com.vela.app.ai.tools.*
    import com.vela.app.data.db.*
    import com.vela.app.data.repository.ConversationRepository
    import com.vela.app.data.repository.RoomConversationRepository
    import com.vela.app.voice.AndroidSpeechTranscriber
    import com.vela.app.voice.SpeechTranscriber
    import dagger.Module
    import dagger.Provides
    import dagger.hilt.InstallIn
    import dagger.hilt.android.qualifiers.ApplicationContext
    import dagger.hilt.components.SingletonComponent
    import okhttp3.OkHttpClient
    import java.util.concurrent.TimeUnit
    import javax.inject.Singleton

    @Module
    @InstallIn(SingletonComponent::class)
    object AppModule {

        @Provides @Singleton
        fun provideDatabase(@ApplicationContext ctx: Context): VelaDatabase =
            Room.databaseBuilder(ctx, VelaDatabase::class.java, "vela_database")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()

        @Provides fun provideMessageDao(db: VelaDatabase): MessageDao = db.messageDao()
        @Provides fun provideConversationDao(db: VelaDatabase): ConversationDao = db.conversationDao()

        @Provides @Singleton
        fun provideConversationRepository(
            messageDao: MessageDao,
            conversationDao: ConversationDao,
        ): ConversationRepository = RoomConversationRepository(messageDao, conversationDao)

        @Provides @Singleton
        fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        @Provides @Singleton
        fun provideTools(@ApplicationContext ctx: Context, client: OkHttpClient): List<Tool> = listOf(
            GetTimeTool(), GetDateTool(), GetBatteryTool(ctx),
            SearchWebTool(client), FetchUrlTool(client),
        )

        @Provides @Singleton
        fun provideToolRegistry(tools: @JvmSuppressWildcards List<Tool>): ToolRegistry =
            ToolRegistry(tools)

        @Provides @Singleton
        fun provideAmplifierSession(
            @ApplicationContext ctx: Context,
            toolRegistry: ToolRegistry,
        ): AmplifierSession = AmplifierSession(ctx, toolRegistry)

        @Provides @Singleton
        fun provideSpeechTranscriber(@ApplicationContext ctx: Context): SpeechTranscriber =
            AndroidSpeechTranscriber(ctx)
    }
    