package com.vela.app.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

sealed class DownloadState {
    object NotDownloaded : DownloadState()
    data class Downloading(val percent: Int) : DownloadState()
    data class Downloaded(val path: String) : DownloadState()
    data class Error(val cause: String) : DownloadState()
}

class ModelManager(
    private val modelsDir: File,
    private val modelUrl: String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .build(),
) {
    companion object {
        const val MODEL_FILENAME = "vela-model.bin"
    }

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.NotDownloaded)
    val downloadState: StateFlow<DownloadState> = _downloadState

    val modelPath: String
        get() = File(modelsDir, MODEL_FILENAME).absolutePath

    fun checkExistingModel() {
        val file = File(modelsDir, MODEL_FILENAME)
        if (file.exists() && file.length() > 0) {
            _downloadState.value = DownloadState.Downloaded(file.absolutePath)
        }
    }

    suspend fun downloadModel() = withContext(Dispatchers.IO) {
        modelsDir.mkdirs()
        val modelFile = File(modelsDir, MODEL_FILENAME)
        val tmpFile = File(modelsDir, "$MODEL_FILENAME.tmp")

        val request = Request.Builder().url(modelUrl).build()

        try {
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                _downloadState.value = DownloadState.Error("HTTP ${response.code}")
                return@withContext
            }

            val body = response.body
            if (body == null) {
                _downloadState.value = DownloadState.Error("Empty response body")
                return@withContext
            }

            val contentLength = body.contentLength()
            var bytesRead = 0L
            val buffer = ByteArray(8192)

            tmpFile.outputStream().use { out ->
                body.byteStream().use { input ->
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        out.write(buffer, 0, read)
                        bytesRead += read
                        if (contentLength > 0) {
                            val percent = ((bytesRead * 100) / contentLength).toInt()
                            _downloadState.value = DownloadState.Downloading(percent)
                        }
                    }
                }
            }

            tmpFile.renameTo(modelFile)
            _downloadState.value = DownloadState.Downloaded(modelFile.absolutePath)
        } catch (e: Exception) {
            _downloadState.value = DownloadState.Error(e.message ?: "Unknown error")
        }
    }
}
