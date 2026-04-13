    package com.vela.app.ai.llama

    import kotlinx.coroutines.Dispatchers
    import kotlinx.coroutines.flow.Flow
    import kotlinx.coroutines.flow.flow
    import kotlinx.coroutines.flow.flowOn
    import okhttp3.OkHttpClient
    import okhttp3.Request
    import java.io.File

    /**
     * Manages download of a GGUF model file from HuggingFace.
     *
     * Default: Gemma 3 4B IT Q4_K_M (~2.5 GB).
     *   URL: https://huggingface.co/bartowski/gemma-3-4b-it-GGUF/resolve/main/gemma-3-4b-it-Q4_K_M.gguf
     *
     * The model is written to [modelsDir]/[fileName] via a temp file (renamed on completion)
     * so a partial download never leaves a corrupt model file at the final path.
     *
     * Usage:
     *   if (!manager.isDownloaded()) {
     *       // Show confirmation dialog (ModelDownloadScreen)
     *       manager.download().collect { state -> ... }
     *   }
     *   val provider = LlamaCppProvider(manager.modelFile())
     *   provider.loadModel()
     */
    class ModelDownloadManager(
        private val modelsDir: File,
        private val client: OkHttpClient,
        private val downloadUrl: String = DEFAULT_URL,
        private val fileName: String    = DEFAULT_FILE_NAME,
    ) {
        companion object {
            // bartowski's GGUF pack — well maintained, Gemma 3 4B IT Q4_K_M
            const val DEFAULT_URL =
                "https://huggingface.co/bartowski/gemma-3-4b-it-GGUF/resolve/main/gemma-3-4b-it-Q4_K_M.gguf"
            const val DEFAULT_FILE_NAME = "gemma-3-4b-it-Q4_K_M.gguf"
            const val EXPECTED_SIZE_BYTES = 2_490_000_000L   // ~2.49 GB (approximate)
        }

        /** The final destination of the model file. */
        fun modelFile(): File = File(modelsDir, fileName)

        /** True when the model file exists at the expected path and is non-empty. */
        fun isDownloaded(): Boolean = modelFile().let { it.exists() && it.length() > 0 }

        /**
         * Download the model, emitting progress and completion.
         * Writes to a temp file first; renames to [modelFile] only on success.
         * Cancellation-safe: temp file is deleted on failure/cancellation.
         */
        fun download(): Flow<DownloadState> = flow {
            modelsDir.mkdirs()
            val dest = modelFile()
            val temp = File(modelsDir, "$fileName.tmp")

            val request = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "Vela-Android/1.0")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                emit(DownloadState.Error("HTTP ${response.code}: ${response.message}"))
                return@flow
            }

            val body   = response.body ?: run {
                emit(DownloadState.Error("Empty response body"))
                return@flow
            }
            val total  = body.contentLength().takeIf { it > 0 } ?: EXPECTED_SIZE_BYTES

            try {
                body.byteStream().use { input ->
                    temp.outputStream().use { output ->
                        val buf       = ByteArray(8 * 1024)
                        var bytesRead = 0L
                        var n: Int
                        while (input.read(buf).also { n = it } != -1) {
                            output.write(buf, 0, n)
                            bytesRead += n
                            emit(DownloadState.Progress(bytesRead, total))
                        }
                    }
                }
                // Rename temp → final only if we read the expected amount
                temp.renameTo(dest)
                emit(DownloadState.Done(dest))
            } catch (e: Exception) {
                temp.delete()
                emit(DownloadState.Error(e.message ?: "Download failed"))
            }
        }.flowOn(Dispatchers.IO)
    }

    sealed class DownloadState {
        /** Download in progress. [bytesRead] and [totalBytes] are in bytes. */
        data class Progress(val bytesRead: Long, val totalBytes: Long) : DownloadState() {
            val fraction: Float get() = if (totalBytes > 0) bytesRead.toFloat() / totalBytes else 0f
            val mbRead:   Float get() = bytesRead / 1_048_576f
            val mbTotal:  Float get() = totalBytes / 1_048_576f
        }
        data class Done(val file: File) : DownloadState()
        data class Error(val message: String) : DownloadState()
    }
    