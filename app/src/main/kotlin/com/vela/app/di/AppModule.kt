package com.vela.app.di

    import android.content.Context
    import androidx.room.Room
    import com.vela.app.ai.GemmaEngine
    import com.vela.app.ai.LifecycleAwareEngine
    import com.vela.app.ai.MlKitGemma4Engine
    import com.vela.app.ai.tools.FetchUrlTool
    import com.vela.app.ai.tools.GetBatteryTool
    import com.vela.app.ai.tools.GetDateTool
    import com.vela.app.ai.tools.GetTimeTool
    import com.vela.app.ai.tools.SearchWebTool
    import com.vela.app.ai.tools.Tool
    import com.vela.app.ai.tools.ToolRegistry
    import com.vela.app.audio.AndroidTtsEngine
    import com.vela.app.audio.TtsEngine
    import com.vela.app.data.db.MessageDao
    import com.vela.app.data.db.VelaDatabase
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

        @Provides
        @Singleton
        fun provideVelaDatabase(@ApplicationContext context: Context): VelaDatabase =
            Room.databaseBuilder(context, VelaDatabase::class.java, "vela_database").build()

        @Provides
        fun provideMessageDao(database: VelaDatabase): MessageDao = database.messageDao()

        @Provides
        @Singleton
        fun provideConversationRepository(messageDao: MessageDao): ConversationRepository =
            RoomConversationRepository(messageDao)

        @Provides
        @Singleton
        fun provideLifecycleAwareEngine(engine: MlKitGemma4Engine): LifecycleAwareEngine = engine

        @Provides
        @Singleton
        fun provideGemmaEngine(engine: LifecycleAwareEngine): GemmaEngine = engine

        @Provides
        @Singleton
        fun provideTtsEngine(@ApplicationContext context: Context): TtsEngine =
            AndroidTtsEngine(context)

        @Provides
        @Singleton
        fun provideSpeechTranscriber(@ApplicationContext context: Context): SpeechTranscriber =
            AndroidSpeechTranscriber(context)

        /** Shared OkHttpClient for all HTTP-based tools. Single instance = shared connection pool. */
        @Provides
        @Singleton
        fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        /**
         * All tools available to Gemma 4 via JSON-in-prompt agentic loop.
         * Add new tools here — they automatically appear in the model's prompt.
         */
        @Provides
        @Singleton
        fun provideTools(
            @ApplicationContext context: Context,
            client: OkHttpClient,
        ): List<Tool> = listOf(
            GetTimeTool(),
            GetDateTool(),
            GetBatteryTool(context),
            SearchWebTool(client),
            FetchUrlTool(client),
        )

        @Provides
        @Singleton
        fun provideToolRegistry(tools: @JvmSuppressWildcards List<Tool>): ToolRegistry =
            ToolRegistry(tools)
    }
    