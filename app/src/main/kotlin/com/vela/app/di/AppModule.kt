package com.vela.app.di

import android.content.Context
import androidx.room.Room
import com.vela.app.ai.AmplifierSession
import com.vela.app.engine.InferenceSession
import com.vela.app.ai.tools.*
import com.vela.app.data.db.*
import com.vela.app.data.repository.ConversationRepository
import com.vela.app.data.repository.RoomConversationRepository
import com.vela.app.engine.InferenceEngine
import com.vela.app.harness.SessionHarness
import com.vela.app.hooks.Hook
import com.vela.app.hooks.HookRegistry
import com.vela.app.hooks.PersonalizationHook
import com.vela.app.hooks.VaultConfigHook
import com.vela.app.hooks.VaultIndexHook
import com.vela.app.hooks.StatusContextHook
    import com.vela.app.hooks.TodoReminderHook
    import com.vela.app.hooks.VaultEmbeddingHook
    import com.vela.app.hooks.VaultSyncHook
import com.vela.app.ssh.SshKeyManager
import com.vela.app.ssh.SshNodeRegistry
import com.vela.app.vault.SharedPrefsVaultSettings
import com.vela.app.skills.SkillsEngine
import com.vela.app.vault.EmbeddingEngine
    import com.vela.app.vault.VaultGitSync
import com.vela.app.vault.VaultManager
import com.vela.app.vault.VaultRegistry
import com.vela.app.vault.VaultSettings
import com.vela.app.voice.AndroidSpeechTranscriber
import com.vela.app.voice.SpeechTranscriber
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): VelaDatabase =
        Room.databaseBuilder(ctx, VelaDatabase::class.java, "vela_database")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13)
            .build()

    @Provides fun provideMessageDao(db: VelaDatabase): MessageDao          = db.messageDao()
    @Provides fun provideConversationDao(db: VelaDatabase): ConversationDao = db.conversationDao()
    @Provides fun provideSshNodeDao(db: VelaDatabase): SshNodeDao           = db.sshNodeDao()
    @Provides fun provideTurnDao(db: VelaDatabase): TurnDao                 = db.turnDao()
    @Provides fun provideTurnEventDao(db: VelaDatabase): TurnEventDao       = db.turnEventDao()
    @Provides fun provideVaultDao(db: VelaDatabase): VaultDao                             = db.vaultDao()
    @Provides fun provideVaultEmbeddingDao(db: VelaDatabase): VaultEmbeddingDao           = db.vaultEmbeddingDao()
    @Provides fun provideGitHubIdentityDao(db: VelaDatabase): com.vela.app.data.db.GitHubIdentityDao = db.gitHubIdentityDao()
    @Provides fun provideMiniAppRegistryDao(db: VelaDatabase): MiniAppRegistryDao = db.miniAppRegistryDao()
    @Provides fun provideMiniAppDocumentDao(db: VelaDatabase): MiniAppDocumentDao = db.miniAppDocumentDao()

    @Provides @Singleton
    fun provideGitHubIdentityManager(
        dao: com.vela.app.data.db.GitHubIdentityDao,
        client: OkHttpClient,
    ): com.vela.app.github.GitHubIdentityManager =
        com.vela.app.github.GitHubIdentityManager(dao, client)

        @Provides @Singleton
        fun provideEmbeddingEngine(
            @ApplicationContext ctx: Context,
            client:  OkHttpClient,
            dao:     VaultEmbeddingDao,
        ): EmbeddingEngine = EmbeddingEngine(ctx, client, dao)

    @Provides @Singleton
    fun provideConversationRepository(
        messageDao: MessageDao,
        conversationDao: ConversationDao,
    ): ConversationRepository = RoomConversationRepository(messageDao, conversationDao)

    @Provides @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    @Provides @Singleton
    fun provideSshKeyManager(@ApplicationContext ctx: Context): SshKeyManager = SshKeyManager(ctx)

    @Provides @Singleton
    fun provideSshNodeRegistry(dao: SshNodeDao): SshNodeRegistry = SshNodeRegistry(dao)

    @Provides @Singleton
    fun provideVaultManager(
        @ApplicationContext ctx: Context,
        @EnabledVaultPaths enabledVaultPaths: @JvmSuppressWildcards StateFlow<Set<String>>,
    ): VaultManager = VaultManager(File(ctx.filesDir, "vaults"), enabledVaultPaths).also { it.init() }

    @Provides @Singleton
    fun provideVaultSettings(@ApplicationContext ctx: Context): VaultSettings =
        SharedPrefsVaultSettings(ctx)

    @Provides @Singleton
    fun provideVaultRegistry(dao: VaultDao, @ApplicationContext ctx: Context): VaultRegistry =
        VaultRegistry(dao, File(ctx.filesDir, "vaults"))

    /**
     * Derives the canonical paths of all currently-enabled vaults from [VaultRegistry].
     * VaultManager reads this StateFlow on every resolve() call to gate file access.
     *
     * Provided separately (rather than injecting VaultRegistry into VaultManager directly)
     * to break the DI cycle: VaultManager → enabledVaultPaths → VaultRegistry → root File,
     * which now has no path back to VaultManager.
     */
    @Provides @Singleton @EnabledVaultPaths
    fun provideEnabledVaultPaths(registry: VaultRegistry): @JvmSuppressWildcards StateFlow<Set<String>> =
        registry.enabledVaults
            .map { list ->
                list.mapNotNull { vault ->
                    runCatching { File(vault.localPath).canonicalPath }.getOrNull()
                }.toSet()
            }
            .stateIn(CoroutineScope(SupervisorJob() + Dispatchers.IO), SharingStarted.Eagerly, emptySet())

    @Provides @Singleton
    fun provideVaultGitSync(vaultSettings: VaultSettings): VaultGitSync =
        VaultGitSync(vaultSettings)

    @Provides @Singleton
    fun provideSkillsEngine(
        vaultRegistry:     VaultRegistry,
        @ApplicationContext ctx: Context,
    ): SkillsEngine {
        val bundledDir = extractBundledSkills(ctx)
        return SkillsEngine(
            getVaultSkillDirs = {
                vaultRegistry.getEnabledVaults()
                    .map { java.io.File(it.localPath, "skills") }
                    .filter { it.exists() }
            },
            bundledSkillsDir = bundledDir,
        )
    }

    @Provides @Singleton
    fun provideTools(
        @ApplicationContext ctx: Context,
        client: OkHttpClient,
        sshNodeRegistry: SshNodeRegistry,
        sshKeyManager: SshKeyManager,
        vaultManager: VaultManager,
        vaultRegistry: VaultRegistry,
        vaultGitSync: VaultGitSync,
        skillsEngine: SkillsEngine,
        transcribeAudioTool: TranscribeAudioTool,
        gitTool: GitTool,
        gitHubIdentityManager: com.vela.app.github.GitHubIdentityManager,
    ): List<Tool> = listOf(
        GetTimeTool(), GetDateTool(), GetBatteryTool(ctx),
        SearchWebTool(client), FetchUrlTool(client),
        ListNodesTool(sshNodeRegistry),
        RunInNodeTool(sshNodeRegistry, sshKeyManager, client),
        GitHubTool(gitHubIdentityManager, client),
        // Vault file tools
        ReadFileTool(vaultManager),
        WriteFileTool(vaultManager),
        EditFileTool(vaultManager),
        GlobTool(vaultManager),
        GrepTool(vaultManager),
        // Bash command router — lambda provides the active vault for git operations
        BashTool(vaultGitSync) { vaultRegistry.getEnabledVaults().firstOrNull() },
        // Session tools
        TodoTool(),
        LoadSkillTool(skillsEngine),
        // Audio transcription
        transcribeAudioTool,
        // Git operations
        gitTool,
        // Code mode — shell script (zero deps, toybox, full vault FS access)
        RunScriptTool(ctx, vaultRegistry),
        // Code mode — Python with full tool bindings via Chaquopy
        CodeRunnerTool(ctx),
    )

    @Provides @Singleton
    fun provideToolRegistry(tools: @JvmSuppressWildcards List<Tool>): ToolRegistry =
        ToolRegistry(tools).also { PythonToolBridge.init(it) }

    @Provides @Singleton
    fun provideAmplifierSession(
        @ApplicationContext ctx: Context,
        toolRegistry: ToolRegistry,
    ): AmplifierSession = AmplifierSession(ctx, toolRegistry)

    @Provides @Singleton
    fun provideInferenceSession(session: AmplifierSession): InferenceSession = session

    @Provides @Singleton
    fun provideHooks(
        vaultSettings: VaultSettings,
        vaultGitSync: VaultGitSync,
        embeddingEngine: EmbeddingEngine,
    ): @JvmSuppressWildcards List<Hook> = listOf(
        VaultSyncHook(
            cloneIfNeeded = { id, path -> vaultGitSync.cloneIfNeeded(id, path) },
            pull          = { id, path -> vaultGitSync.pull(id, path) },
            vaultSettings = vaultSettings,
            onAfterSync   = { vault -> embeddingEngine.startIndexing(vault) },
        ),
        VaultConfigHook(),
        VaultIndexHook(),
        VaultEmbeddingHook(embeddingEngine),
            PersonalizationHook(),
            // Agent-loop hooks — fire on PROVIDER_REQUEST before each LLM call
            StatusContextHook(),
            TodoReminderHook(),
        )

    @Provides @Singleton
    fun provideSessionHarness(
        hookRegistry: HookRegistry,
        @ApplicationContext ctx: Context,
    ): SessionHarness {
        val fallback = try {
            ctx.assets.open("lifeos/SYSTEM.md").bufferedReader().readText()
        } catch (_: Exception) {
            SessionHarness.DEFAULT_FALLBACK
        }
        return SessionHarness(hookRegistry, fallback)
    }

    @Provides @Singleton
    fun provideInferenceEngine(
        @ApplicationContext ctx: Context,
        session: InferenceSession,
        toolRegistry: ToolRegistry,
        hookRegistry: HookRegistry,
        turnDao: TurnDao,
        turnEventDao: TurnEventDao,
        conversationDao: ConversationDao,
        vaultRegistry: VaultRegistry,
        vaultManager: VaultManager,
        harness: SessionHarness,
    ): InferenceEngine = InferenceEngine(
        ctx, session, toolRegistry, hookRegistry,
        turnDao, turnEventDao, conversationDao, vaultRegistry, vaultManager, harness,
    )

    @Provides @Singleton
    fun provideSpeechTranscriber(@ApplicationContext ctx: Context): SpeechTranscriber =
        AndroidSpeechTranscriber(ctx)

    private fun extractBundledSkills(context: Context): java.io.File {
        val cache = java.io.File(context.cacheDir, "bundled_skills")
        cache.mkdirs()
        runCatching {
            context.assets.list("skills")?.forEach { skillName ->
                val dest = java.io.File(cache, skillName).also { it.mkdirs() }
                context.assets.list("skills/$skillName")?.forEach { fileName ->
                    context.assets.open("skills/$skillName/$fileName").use { input ->
                        java.io.File(dest, fileName).outputStream().use(input::copyTo)
                    }
                }
            }
        }.onFailure { e ->
            android.util.Log.e("AppModule", "Failed to extract bundled skills from assets", e)
        }
        return cache
    }
}
