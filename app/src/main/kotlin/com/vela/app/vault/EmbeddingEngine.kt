package com.vela.app.vault

    import android.content.Context
    import android.content.SharedPreferences
    import android.util.Log
    import com.vela.app.data.db.VaultEmbeddingDao
    import com.vela.app.data.db.VaultEmbeddingEntity
    import com.vela.app.data.db.VaultEntity
    import kotlinx.coroutines.CoroutineScope
    import kotlinx.coroutines.Dispatchers
    import kotlinx.coroutines.Job
    import kotlinx.coroutines.SupervisorJob
    import kotlinx.coroutines.flow.MutableStateFlow
    import kotlinx.coroutines.flow.StateFlow
    import kotlinx.coroutines.flow.asStateFlow
    import kotlinx.coroutines.launch
    import kotlinx.coroutines.withContext
    import okhttp3.MediaType.Companion.toMediaType
    import okhttp3.OkHttpClient
    import okhttp3.Request
    import okhttp3.RequestBody.Companion.toRequestBody
    import org.json.JSONArray
    import org.json.JSONObject
    import java.io.File
    import java.util.UUID
    import javax.inject.Inject
    import javax.inject.Singleton
    import kotlin.math.sqrt

    private const val TAG = "EmbeddingEngine"

    // File extensions we index. Binary files are skipped.
    private val INDEXABLE = setOf("md", "txt", "html", "htm", "csv", "json", "xml", "yaml", "yml", "toml")
    private const val MAX_FILE_BYTES = 200_000L   // 200 KB
    private const val CHUNK_SIZE     = 1_000      // chars per chunk

    data class SearchResult(
        val filePath:  String,
        val vaultId:   String,
        val vaultName: String,
        val chunkText: String,
        val score:     Float,
    )

    @Singleton
    class EmbeddingEngine @Inject constructor(
        private val context: Context,
        private val client:  OkHttpClient,
        private val dao:     VaultEmbeddingDao,
    ) {
        private val prefs: SharedPreferences
            get() = context.getSharedPreferences("amplifier_prefs", Context.MODE_PRIVATE)

        // --- Indexing state (singleton — survives ViewModel recreation) ---------------
        //
        // Keeping job management here (rather than in the ViewModel) solves two bugs:
        //   1. Progress resets to 0/0 on every screen visit because each navigation
        //      recreates the ViewModel and immediately calls triggerIndexing().
        //   2. The counter fluctuates because triggerIndexing() had no job-cancellation
        //      guard, so rapid or repeated calls launched concurrent coroutines that
        //      raced each other writing to _indexProgress.
        //
        // With state here: the singleton outlives ViewModel lifetimes, so returning to
        // the vault explorer mid-index just picks up the live progress instead of
        // flashing "0/0" and restarting from scratch.

        private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private var indexingJob: Job? = null
        private var indexingVaultId: String? = null

        private val _indexProgress = MutableStateFlow<Pair<Int, Int>?>(null)
        /** Current indexing progress (done to total), or null when idle. */
        val indexProgress: StateFlow<Pair<Int, Int>?> = _indexProgress.asStateFlow()

        /**
         * Fire-and-forget indexing. Safe to call on every screen visit and after vault sync:
         * - No-ops if this vault is already actively being indexed.
         * - Skips the walk entirely if nothing on disk has changed since the last completed run.
         * - Cancels any in-flight job for a *different* vault before starting.
         *
         * Completion is persisted in SharedPrefs so the "nothing changed" check survives
         * app restarts — no unnecessary re-walk on launch if the vault is up to date.
         */
        fun startIndexing(vault: VaultEntity) {
            if (!isConfigured) return
            if (indexingVaultId == vault.id && indexingJob?.isActive == true) return
            indexingJob?.cancel()
            indexingVaultId = vault.id
            indexingJob = engineScope.launch {
                // Check on IO thread (file walk) — skip if nothing changed since last full index
                val lastIndexed = prefs.getLong("last_indexed_${vault.id}", 0L)
                if (lastIndexed > 0L && !hasChangedSince(vault, lastIndexed)) {
                    Log.d(TAG, "Vault '${vault.name}' up to date — skipping index")
                    indexingVaultId = null
                    return@launch
                }

                _indexProgress.value = 0 to 0
                indexVault(vault) { done, total ->
                    _indexProgress.value = done to total
                }
                // Persist completion so next launch skips the walk if nothing changed
                prefs.edit().putLong("last_indexed_${vault.id}", System.currentTimeMillis()).apply()
                _indexProgress.value = null
                indexingVaultId = null
            }
        }

        /**
         * Quick pre-flight check: are there any indexable files in [vault] with a
         * lastModified timestamp newer than [epochMs]?
         *
         * Short-circuits on the first changed file — O(1) in the common "nothing changed"
         * case once the early exit fires. Called on the IO thread inside [startIndexing].
         */
        private fun hasChangedSince(vault: VaultEntity, epochMs: Long): Boolean {
            val root = File(vault.localPath).takeIf { it.exists() } ?: return false
            return root.walkTopDown()
                .filter { it.isFile && it.extension.lowercase() in INDEXABLE && it.length() <= MAX_FILE_BYTES }
                .any { it.lastModified() > epochMs }
        }

        // --- Public API ---------------------------------------------------------------

        val isConfigured: Boolean
            get() = googleKey.isNotBlank() || openAiKey.isNotBlank()

        /**
         * Walk [vault] on disk, embed changed text files, persist to DB.
         * Safe to call repeatedly — skips unchanged files via lastModified timestamp.
         */
        suspend fun indexVault(vault: VaultEntity, onProgress: (Int, Int) -> Unit = { _, _ -> }) {
            if (!isConfigured) return
            withContext(Dispatchers.IO) {
                val root = File(vault.localPath).takeIf { it.exists() } ?: return@withContext
                val files = root.walkTopDown()
                    .filter { it.isFile && it.length() <= MAX_FILE_BYTES }
                    .filter { it.extension.lowercase() in INDEXABLE }
                    .toList()

                files.forEachIndexed { idx, file ->
                    onProgress(idx + 1, files.size)
                    val relPath = file.relativeTo(root).path
                    val modified = file.lastModified()
                    val stored = dao.getFileModified(vault.id, relPath)
                    if (stored == modified) return@forEachIndexed   // unchanged

                    dao.deleteFile(vault.id, relPath)               // clear stale chunks
                    val text = file.readText(Charsets.UTF_8)
                    val chunks = chunkText(text)
                    chunks.forEachIndexed { chunkIdx, chunk ->
                        val vec = embed(chunk) ?: return@forEachIndexed
                        dao.upsert(VaultEmbeddingEntity(
                            id           = UUID.randomUUID().toString(),
                            vaultId      = vault.id,
                            filePath     = relPath,
                            chunkIndex   = chunkIdx,
                            chunkText    = chunk,
                            embeddingJson= JSONArray(vec.toList()).toString(),
                            fileModified = modified,
                        ))
                    }
                    Log.d(TAG, "Indexed $relPath (${chunks.size} chunks)")
                }
                Log.d(TAG, "Vault ${vault.name} indexed: ${files.size} files")
            }
        }

        /**
         * Embed [query] then return top-[topK] chunks sorted by cosine similarity.
         */
        suspend fun search(
            query:    String,
            vaults:   List<VaultEntity>,
            topK:     Int = 15,
        ): List<SearchResult> = withContext(Dispatchers.IO) {
            val queryVec = embed(query) ?: return@withContext emptyList()
            val vaultMap  = vaults.associateBy { it.id }
            vaults.flatMap { vault ->
                dao.getByVault(vault.id).mapNotNull { row ->
                    val rowVec = parseVec(row.embeddingJson) ?: return@mapNotNull null
                    if (rowVec.size != queryVec.size) return@mapNotNull null
                    val score = cosine(queryVec, rowVec)
                    SearchResult(
                        filePath  = row.filePath,
                        vaultId   = row.vaultId,
                        vaultName = vaultMap[row.vaultId]?.name ?: "",
                        chunkText = row.chunkText,
                        score     = score,
                    )
                }
            }
            .sortedByDescending { it.score }
            .take(topK)
        }

        // --- Private helpers ----------------------------------------------------------

        private val googleKey  get() = prefs.getString("google_api_key",  "").orEmpty()
        private val openAiKey  get() = prefs.getString("openai_api_key",  "").orEmpty()

        /** Call whichever embedding API is configured. Returns null on failure. */
        private fun embed(text: String): FloatArray? {
            return if (googleKey.isNotBlank()) embedGemini(text)
            else if (openAiKey.isNotBlank())  embedOpenAI(text)
            else null
        }

        private fun embedGemini(text: String): FloatArray? = try {
            val body = JSONObject().apply {
                put("model", "models/text-embedding-004")
                put("content", JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", text) })
                    })
                })
            }.toString()
            val req = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/text-embedding-004:embedContent?key=$googleKey")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) { Log.w(TAG, "Gemini embed ${resp.code}"); return null }
                val arr = JSONObject(resp.body!!.string())
                    .getJSONObject("embedding")
                    .getJSONArray("values")
                FloatArray(arr.length()) { arr.getDouble(it).toFloat() }
            }
        } catch (e: Exception) { Log.e(TAG, "embedGemini", e); null }

        private fun embedOpenAI(text: String): FloatArray? = try {
            val body = JSONObject().apply {
                put("input", text)
                put("model", "text-embedding-3-small")
            }.toString()
            val req = Request.Builder()
                .url("https://api.openai.com/v1/embeddings")
                .addHeader("Authorization", "Bearer $openAiKey")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) { Log.w(TAG, "OpenAI embed ${resp.code}"); return null }
                val arr = JSONObject(resp.body!!.string())
                    .getJSONArray("data")
                    .getJSONObject(0)
                    .getJSONArray("embedding")
                FloatArray(arr.length()) { arr.getDouble(it).toFloat() }
            }
        } catch (e: Exception) { Log.e(TAG, "embedOpenAI", e); null }

        private fun cosine(a: FloatArray, b: FloatArray): Float {
            var dot = 0.0; var na = 0.0; var nb = 0.0
            for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
            val denom = sqrt(na) * sqrt(nb)
            return if (denom == 0.0) 0f else (dot / denom).toFloat()
        }

        private fun parseVec(json: String): FloatArray? = try {
            val arr = JSONArray(json)
            FloatArray(arr.length()) { arr.getDouble(it).toFloat() }
        } catch (_: Exception) { null }

        private fun chunkText(text: String): List<String> =
            text.chunked(CHUNK_SIZE).filter { it.isNotBlank() }
    }
