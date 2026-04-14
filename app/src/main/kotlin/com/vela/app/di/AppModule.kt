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
import com.vela.app.hooks.VaultSyncHook
import com.vela.app.ssh.SshKeyManager
import com.vela.app.ssh.SshNodeRegistry
import com.vela.app.vault.SharedPrefsVaultSettings
import com.vela.app.skills.SkillsEngine
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
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
            .build()

    @Provides fun provideMessageDao(db: VelaDatabase): MessageDao          = db.messageDao()
    @Provides fun provideConversationDao(db: VelaDatabase): ConversationDao = db.conversationDao()
    @Provides fun provideSshNodeDao(db: VelaDatabase): SshNodeDao           = db.sshNodeDao()
    @Provides fun provideTurnDao(db: VelaDatabase): TurnDao                 = db.turnDao()
    @Provides fun provideTurnEventDao(db: VelaDatabase): TurnEventDao       = db.turnEventDao()
    @Provides fun provideVaultDao(db: VelaDatabase): VaultDao               = db.vaultDao()

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
    fun provideVaultManager(@ApplicationContext ctx: Context): VaultManager =
        VaultManager(File(ctx.filesDir, "vaults")).also { it.init() }

    @Provides @Singleton
    fun provideVaultSettings(@ApplicationContext ctx: Context): VaultSettings =
        SharedPrefsVaultSettings(ctx)

    @Provides @Singleton
    fun provideVaultRegistry(dao: VaultDao, vaultManager: VaultManager): VaultRegistry =
        VaultRegistry(dao, vaultManager)

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
    ): List<Tool> = listOf(
        GetTimeTool(), GetDateTool(), GetBatteryTool(ctx),
        SearchWebTool(client), FetchUrlTool(client),
        ListSshNodesTool(sshNodeRegistry),
        SshCommandTool(sshNodeRegistry, sshKeyManager),
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
    )

    @Provides @Singleton
    fun provideToolRegistry(tools: @JvmSuppressWildcards List<Tool>): ToolRegistry = ToolRegistry(tools)

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
    ): @JvmSuppressWildcards List<Hook> = listOf(
        VaultSyncHook(
            cloneIfNeeded = { id, path -> vaultGitSync.cloneIfNeeded(id, path) },
            pull          = { id, path -> vaultGitSync.pull(id, path) },
            vaultSettings = vaultSettings,
        ),
        PersonalizationHook(),
        VaultConfigHook(),
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
        session: InferenceSession,
        toolRegistry: ToolRegistry,
        turnDao: TurnDao,
        turnEventDao: TurnEventDao,
        conversationDao: ConversationDao,
        vaultRegistry: VaultRegistry,
        harness: SessionHarness,
    ): InferenceEngine = InferenceEngine(
        session, toolRegistry, turnDao, turnEventDao, conversationDao, vaultRegistry, harness,
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
