package com.vela.app.di

import android.content.Context
import android.util.Log
import androidx.room.Room
import com.vela.app.R
import com.vela.app.ai.DownloadState
import com.vela.app.ai.FakeGemmaEngine
import com.vela.app.ai.GemmaEngine
import com.vela.app.ai.IntentExtractor
import com.vela.app.ai.MediaPipeGemmaEngine
import com.vela.app.ai.ModelManager
import com.vela.app.ai.RealLlmInferenceWrapper
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
import java.io.File
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
    fun provideModelManager(@ApplicationContext context: Context): ModelManager {
        val manager = ModelManager(
            modelsDir = File(context.filesDir, "models"),
            modelUrl = context.getString(R.string.model_download_url),
        )
        manager.checkExistingModel()
        return manager
    }

    @Provides
    @Singleton
    fun provideGemmaEngine(
        modelManager: ModelManager,
        @ApplicationContext context: Context,
    ): GemmaEngine {
        return when (val state = modelManager.downloadState.value) {
            is DownloadState.Downloaded -> {
                try {
                    val wrapper = RealLlmInferenceWrapper(context, state.path)
                    MediaPipeGemmaEngine(wrapper)
                } catch (e: Exception) {
                    Log.w("AppModule", "Real engine unavailable, falling back to FakeGemmaEngine", e)
                    FakeGemmaEngine()
                }
            }
            else -> FakeGemmaEngine()
        }
    }

    @Provides
    @Singleton
    fun provideIntentExtractor(engine: GemmaEngine): IntentExtractor = IntentExtractor(engine)

    @Provides
    @Singleton
    fun provideTtsEngine(@ApplicationContext context: Context): TtsEngine =
        AndroidTtsEngine(context)

    @Provides
    @Singleton
    fun provideSpeechTranscriber(@ApplicationContext context: Context): SpeechTranscriber =
        AndroidSpeechTranscriber(context)
}
