package com.vela.app.ai.tools

import android.content.Context
import com.vela.app.recording.TranscriptionService
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TranscribeAudioTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val transcriptionService: TranscriptionService,
) : Tool {

    override val name        = "transcribe_audio"
    override val displayName = "Transcribe Audio"
    override val icon        = "🎙"
    override val description = "Transcribe an audio file to text using AI. Pass the absolute file path."
    override val parameters  = listOf(
        ToolParameter("file_path", "string", "Absolute path to the audio file (M4A, WAV, MP3)", required = true),
        ToolParameter("provider", "string", "Transcription provider: 'gemini' (default) or 'whisper'", required = false),
    )

    override suspend fun execute(args: Map<String, Any>): String {
        val filePath = args["file_path"] as? String
            ?: return "Error: file_path is required"
        val file = File(filePath)
        if (!file.exists()) return "Error: Audio file not found at $filePath"

        val prefs = context.getSharedPreferences("amplifier_prefs", Context.MODE_PRIVATE)
        return when ((args["provider"] as? String)?.lowercase()) {
            "whisper" -> {
                val key = prefs.getString("openai_api_key", "").orEmpty()
                if (key.isBlank()) return "Error: OpenAI API key not configured — add it in Settings → AI"
                transcriptionService.transcribeWithWhisper(file, key)
                    .getOrElse { "Error transcribing with Whisper: ${it.message}" }
            }
            else -> {
                val key = prefs.getString("google_api_key", "").orEmpty()
                if (key.isBlank()) return "Error: Google API key not configured — add it in Settings → AI"
                transcriptionService.transcribeWithGemini(file, key)
                    .getOrElse { "Error transcribing with Gemini: ${it.message}" }
            }
        }
    }
}
