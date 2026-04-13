package com.vela.app.di

import android.content.Context
import androidx.room.Room
import com.vela.app.ai.AmplifierSession
import com.vela.app.ai.tools.*
import com.vela.app.data.db.*
import com.vela.app.data.repository.ConversationRepository
import com.vela.app.data.repository.RoomConversationRepository
import com.vela.app.engine.InferenceEngine
import com.vela.app.ssh.SshKeyManager
import com.vela.app.ssh.SshNodeRegistry
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
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
            .build()

    @Provides fun provideMessageDao(db: VelaDatabase): MessageDao          = db.messageDao()
    @Provides fun provideConversationDao(db: VelaDatabase): ConversationDao = db.conversationDao()
    @Provides fun provideSshNodeDao(db: VelaDatabase): SshNodeDao           = db.sshNodeDao()
    @Provides fun provideTurnDao(db: VelaDatabase): TurnDao                 = db.turnDao()
    @Provides fun provideTurnEventDao(db: VelaDatabase): TurnEventDao       = db.turnEventDao()

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
    fun provideTools(
        @ApplicationContext ctx: Context,
        client: OkHttpClient,
        sshNodeRegistry: SshNodeRegistry,
        sshKeyManager: SshKeyManager,
    ): List<Tool> = listOf(
        GetTimeTool(), GetDateTool(), GetBatteryTool(ctx),
        SearchWebTool(client), FetchUrlTool(client),
        ListSshNodesTool(sshNodeRegistry),
        SshCommandTool(sshNodeRegistry, sshKeyManager),
    )

    @Provides @Singleton
    fun provideToolRegistry(tools: @JvmSuppressWildcards List<Tool>): ToolRegistry = ToolRegistry(tools)

    @Provides @Singleton
    fun provideAmplifierSession(
        @ApplicationContext ctx: Context,
        toolRegistry: ToolRegistry,
    ): AmplifierSession = AmplifierSession(ctx, toolRegistry)

    @Provides @Singleton
    fun provideInferenceEngine(
        session: AmplifierSession,
        toolRegistry: ToolRegistry,
        turnDao: TurnDao,
        turnEventDao: TurnEventDao,
    ): InferenceEngine = InferenceEngine(session, toolRegistry, turnDao, turnEventDao)

    @Provides @Singleton
    fun provideSpeechTranscriber(@ApplicationContext ctx: Context): SpeechTranscriber =
        AndroidSpeechTranscriber(ctx)
}
