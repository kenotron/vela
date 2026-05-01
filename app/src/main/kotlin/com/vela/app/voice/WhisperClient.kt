package com.vela.app.voice

    import kotlinx.coroutines.Dispatchers
    import kotlinx.coroutines.withContext
    import okhttp3.MediaType.Companion.toMediaType
    import okhttp3.MultipartBody
    import okhttp3.OkHttpClient
    import okhttp3.Request
    import okhttp3.RequestBody.Companion.asRequestBody
    import org.json.JSONObject
    import java.io.File
    import java.io.IOException
    import java.util.concurrent.TimeUnit

    /**
     * Client for OpenAI Whisper speech-to-text transcription.
     *
     * Submits an audio file to POST https://api.openai.com/v1/audio/transcriptions
     * and returns the transcript text string.
     *
     * @param apiKey  OpenAI API key from [com.vela.app.settings.ApiKeyStore.openAiKey]
     */
    class WhisperClient(private val apiKey: String) {

        private val http = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        /**
         * Transcribe [audioFile] (m4a/mp4 format) via Whisper.
         *
         * @return The transcript text, trimmed.
         * @throws IOException on network failure or non-2xx response.
         */
        suspend fun transcribe(audioFile: File): String = withContext(Dispatchers.IO) {
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", "whisper-1")
                .addFormDataPart("language", "en")
                .addFormDataPart(
                    "file",
                    audioFile.name,
                    audioFile.asRequestBody("audio/mp4".toMediaType()),
                )
                .build()

            val request = Request.Builder()
                .url("https://api.openai.com/v1/audio/transcriptions")
                .header("Authorization", "Bearer $apiKey")
                .post(requestBody)
                .build()

            val response = http.newCall(request).execute()
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                throw IOException("Whisper API ${response.code}: $body")
            }
            JSONObject(body).getString("text").trim()
        }
    }
    