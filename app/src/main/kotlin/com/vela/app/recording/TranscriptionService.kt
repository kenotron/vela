package com.vela.app.recording

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TranscriptionService @Inject constructor() {

    /**
     * Transcribe audio with Gemini Flash.
     * Sends the audio as inline base64 (works for files < ~15 MB).
     */
    suspend fun transcribeWithGemini(audioFile: File, apiKey: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val audioBytes = audioFile.readBytes()
                val audioB64 = Base64.encodeToString(audioBytes, Base64.NO_WRAP)

                val body = JSONObject().apply {
                    put("contents", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("inline_data", JSONObject().apply {
                                        put("mime_type", "audio/m4a")
                                        put("data", audioB64)
                                    })
                                })
                                put(JSONObject().apply {
                                    put("text",
                                        "Transcribe this audio recording accurately. " +
                                        "Identify distinct speakers and label them as Speaker 1, Speaker 2, etc. " +
                                        "Format the output as a readable transcript with speaker labels on each line. " +
                                        "Include rough timestamps every 30 seconds in [MM:SS] format.")
                                })
                            })
                        })
                    })
                }

                val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$apiKey")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 60_000
                conn.readTimeout = 120_000

                OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

                val responseCode = conn.responseCode
                val response = if (responseCode == 200) conn.inputStream.bufferedReader().readText()
                               else conn.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                conn.disconnect()

                if (responseCode != 200) error("Gemini API error $responseCode: $response")

                JSONObject(response)
                    .getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")
            }
        }

    /**
     * Transcribe audio with OpenAI Whisper.
     * Uses multipart form upload.
     */
    suspend fun transcribeWithWhisper(audioFile: File, apiKey: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val boundary = "----VelaWhisper${System.currentTimeMillis()}"
                val url = URL("https://api.openai.com/v1/audio/transcriptions")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Authorization", "Bearer $apiKey")
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                conn.doOutput = true
                conn.connectTimeout = 60_000
                conn.readTimeout = 120_000

                conn.outputStream.use { out ->
                    // model field
                    out.write("--$boundary\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\nwhisper-1\r\n".toByteArray())
                    // response_format field
                    out.write("--$boundary\r\nContent-Disposition: form-data; name=\"response_format\"\r\n\r\ntext\r\n".toByteArray())
                    // file field
                    out.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"${audioFile.name}\"\r\nContent-Type: audio/m4a\r\n\r\n".toByteArray())
                    out.write(audioFile.readBytes())
                    out.write("\r\n--$boundary--\r\n".toByteArray())
                }

                val responseCode = conn.responseCode
                val response = if (responseCode == 200) conn.inputStream.bufferedReader().readText()
                               else conn.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                conn.disconnect()

                if (responseCode != 200) error("Whisper API error $responseCode: $response")
                response.trim()
            }
        }
}
