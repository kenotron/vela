    package com.vela.app.di

    import android.content.Context
    import androidx.room.Room
    import com.vela.app.ai.AgentOrchestrator
    import com.vela.app.ai.GemmaEngine
    import com.vela.app.ai.InferenceProvider
    import com.vela.app.ai.LifecycleAwareEngine
    import com.vela.app.ai.MlKitGemma4Engine
    import com.vela.app.ai.MlKitInferenceProvider
    import com.vela.app.ai.ProviderRegistry
    import com.vela.app.ai.llama.LlamaCppProvider
    import com.vela.app.ai.llama.ModelDownloadManager
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
    import java.io.File
    import java.util.concurrent.TimeUnit
    import javax.inject.Singleton

    @Module
    @InstallIn(SingletonComponent::class)
    object AppModule {

        // ─── Persistence ────────────────────────────────────────────────────────────

        @Provides @Singleton
        fun provideVelaDatabase(@ApplicationContext context: Context): VelaDatabase =
            Room.databaseBuilder(context, VelaDatabase::class.java, "vela_database").build()

        @Provides
        fun provideMessageDao(database: VelaDatabase): MessageDao = database.messageDao()

        @Provides @Singleton
        fun provideConversationRepository(messageDao: MessageDao): ConversationRepository =
            RoomConversationRepository(messageDao)

        // ─── ML Kit Engine (kept for loading-screen readiness checks + fallback) ────

        @Provides @Singleton
        fun provideLifecycleAwareEngine(engine: MlKitGemma4Engine): LifecycleAwareEngine = engine

        /** GemmaEngine binding retained for ModelLoadingViewModel and any legacy callers. */
        @Provides @Singleton
        fun provideGemmaEngine(engine: LifecycleAwareEngine): GemmaEngine = engine

        // ─── HTTP ───────────────────────────────────────────────────────────────────

        /** Shared OkHttpClient — used by web tools AND the model download manager. */
        @Provides @Singleton
        fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        // ─── Phase 2: llama.cpp ──────────────────────────────────────────────────────

        /**
         * Manages the GGUF model download. Model lives at:
         *   {filesDir}/models/gemma-3-4b-it-Q4_K_M.gguf  (~2.5 GB)
         *
         * To swap the model, change ModelDownloadManager.DEFAULT_URL and DEFAULT_FILE_NAME.
         * The download screen will appear again if the new file doesn't exist yet.
         */
        @Provides @Singleton
        fun provideModelDownloadManager(
            @ApplicationContext context: Context,
            client: OkHttpClient,
        ): ModelDownloadManager = ModelDownloadManager(
            modelsDir = File(context.filesDir, "models"),
            client    = client,
        )

        /**
         * LlamaCppProvider singleton — loaded lazily after the GGUF is downloaded.
         *
         * [isAvailable] returns false until [LlamaCppProvider.loadModel] is called, so
         * ProviderRegistry gracefully falls back to MlKitInferenceProvider in the meantime.
         *
         * nGpuLayers = 0 → CPU-only (safe default). Set to 99 for Adreno GPU offload.
         */
        @Provides @Singleton
        fun provideLlamaCppProvider(
            downloadManager: ModelDownloadManager,
        ): LlamaCppProvider = LlamaCppProvider(
            modelFile   = downloadManager.modelFile(),
            nCtx        = 4096,
            nThreads    = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4),
            nGpuLayers  = 0,
            nPredict    = 512,
        )

        // ─── InferenceProvider chain ─────────────────────────────────────────────────

        /**
         * Ordered provider list — first available wins in ProviderRegistry.
         *
         * LlamaCppProvider is listed first (primary). It returns isAvailable = false
         * until the model is downloaded + loaded, at which point it becomes the
         * automatic primary. MlKitInferenceProvider is the fallback.
         */
        @Provides @Singleton
        fun provideInferenceProviders(
            llamaCpp: LlamaCppProvider,
            mlKit: MlKitInferenceProvider,
        ): List<InferenceProvider> = listOf(llamaCpp, mlKit)

        @Provides @Singleton
        fun provideProviderRegistry(
            providers: @JvmSuppressWildcards List<InferenceProvider>,
        ): ProviderRegistry = ProviderRegistry(providers)

        @Provides @Singleton
        fun provideAgentOrchestrator(
            registry: ProviderRegistry,
            tools: ToolRegistry,
        ): AgentOrchestrator = AgentOrchestrator(registry, tools)

        // ─── Audio / Voice ──────────────────────────────────────────────────────────

        @Provides @Singleton
        fun provideTtsEngine(@ApplicationContext context: Context): TtsEngine =
            AndroidTtsEngine(context)

        @Provides @Singleton
        fun provideSpeechTranscriber(@ApplicationContext context: Context): SpeechTranscriber =
            AndroidSpeechTranscriber(context)

        // ─── Tools ──────────────────────────────────────────────────────────────────

        @Provides @Singleton
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

        @Provides @Singleton
        fun provideToolRegistry(tools: @JvmSuppressWildcards List<Tool>): ToolRegistry =
            ToolRegistry(tools)
    }
    