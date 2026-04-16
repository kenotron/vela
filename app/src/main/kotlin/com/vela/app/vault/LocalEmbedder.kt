package com.vela.app.vault

    import ai.onnxruntime.OnnxTensor
    import ai.onnxruntime.OrtEnvironment
    import ai.onnxruntime.OrtSession
    import android.content.Context
    import android.util.Log
    import java.nio.LongBuffer
    import kotlin.math.sqrt

    private const val TAG = "LocalEmbedder"

    /**
     * On-device sentence embedding using all-MiniLM-L6-v2 (ONNX INT8 quantized, 22 MB).
     *
     * Produces 384-dimensional L2-normalised float vectors, same cosine-similarity
     * semantics as the cloud APIs but with zero network latency (~10 ms/chunk on arm64).
     *
     * Model files live in app/src/main/assets/embeddings/:
     *   model_quantized.onnx  — ONNX INT8 quantized all-MiniLM-L6-v2
     *   vocab.txt             — WordPiece vocabulary (30 522 tokens)
     */
    class LocalEmbedder(private val context: Context) {

        companion object {
            private const val MODEL_ASSET = "embeddings/model_quantized.onnx"
            private const val VOCAB_ASSET = "embeddings/vocab.txt"
            private const val MAX_SEQ_LEN = 256   // MiniLM context window
            const val DIM = 384
        }

        // Lazy-init so construction never blocks the calling thread.
        private val env: OrtEnvironment = OrtEnvironment.getEnvironment()

        private val session: OrtSession? by lazy {
            try {
                val bytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
                env.createSession(bytes).also {
                    Log.i(TAG, "ONNX session loaded (${bytes.size / 1024} KB)")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Local model unavailable — will use cloud fallback: ${e.message}")
                null
            }
        }

        private val vocab: Map<String, Long> by lazy {
            try {
                context.assets.open(VOCAB_ASSET)
                    .bufferedReader()
                    .readLines()
                    .mapIndexed { idx, token -> token to idx.toLong() }
                    .toMap()
                    .also { Log.i(TAG, "Vocab loaded (${it.size} tokens)") }
            } catch (e: Exception) {
                Log.w(TAG, "Vocab unavailable: ${e.message}")
                emptyMap()
            }
        }

        val isAvailable: Boolean get() = session != null && vocab.isNotEmpty()

        /**
         * Embed [text] and return a 384-dim L2-normalised vector, or null on failure.
         * Thread-safe — OrtSession is thread-safe for concurrent run() calls.
         */
        fun embed(text: String): FloatArray? {
            val sess = session ?: return null
            if (vocab.isEmpty()) return null

            return try {
                val (ids, mask, types) = tokenize(text)
                val seqLen = ids.size.toLong()

                val inputIds   = OnnxTensor.createTensor(env, LongBuffer.wrap(ids),   longArrayOf(1L, seqLen))
                val attnMask   = OnnxTensor.createTensor(env, LongBuffer.wrap(mask),  longArrayOf(1L, seqLen))
                val typeIds    = OnnxTensor.createTensor(env, LongBuffer.wrap(types), longArrayOf(1L, seqLen))

                val inputs = mapOf(
                    "input_ids"      to inputIds,
                    "attention_mask" to attnMask,
                    "token_type_ids" to typeIds,
                )

                sess.run(inputs).use { out ->
                    // last_hidden_state: [1, seq_len, 384]
                    @Suppress("UNCHECKED_CAST")
                    val hidden = out[0].value as Array<Array<FloatArray>>
                    meanPool(hidden[0], mask)
                }
            } catch (e: Exception) {
                Log.e(TAG, "embed() failed", e)
                null
            }
        }

        // ── Pooling ──────────────────────────────────────────────────────────────

        private fun meanPool(hidden: Array<FloatArray>, mask: LongArray): FloatArray {
            val dim = hidden[0].size
            val out = FloatArray(dim)
            var count = 0
            for (t in hidden.indices) {
                if (mask[t] == 1L) {
                    for (d in 0 until dim) out[d] += hidden[t][d]
                    count++
                }
            }
            if (count == 0) return out
            for (d in 0 until dim) out[d] /= count
            return l2Normalize(out)
        }

        private fun l2Normalize(v: FloatArray): FloatArray {
            var sq = 0f
            for (x in v) sq += x * x
            val norm = sqrt(sq)
            return if (norm < 1e-9f) v else FloatArray(v.size) { v[it] / norm }
        }

        // ── Tokenizer ────────────────────────────────────────────────────────────

        private fun tokenize(text: String): Triple<LongArray, LongArray, LongArray> {
            val clsId = vocab["[CLS]"] ?: 101L
            val sepId = vocab["[SEP]"] ?: 102L
            val unkId = vocab["[UNK]"] ?: 100L

            val tokens = mutableListOf<Long>()
            tokens += clsId

            // Basic tokenization: lowercase + split on whitespace/punctuation
            for (word in basicTokenize(text)) {
                tokens += wordPiece(word, unkId)
                if (tokens.size >= MAX_SEQ_LEN - 1) break  // leave room for [SEP]
            }
            tokens += sepId

            val len = tokens.size
            val ids   = LongArray(len) { tokens[it] }
            val mask  = LongArray(len) { 1L }
            val types = LongArray(len) { 0L }
            return Triple(ids, mask, types)
        }

        private fun basicTokenize(text: String): List<String> =
            text.lowercase()
                // Add spaces around any non-alphanumeric character so they become
                // separate tokens (matches BERT's BasicTokenizer behaviour).
                .replace(Regex("([^a-z0-9 ])")) { " ${it.value} " }
                .split(Regex("\\s+"))
                .filter { it.isNotEmpty() }

        private fun wordPiece(word: String, unkId: Long): List<Long> {
            val result = mutableListOf<Long>()
            var remaining = word
            var isFirst = true

            while (remaining.isNotEmpty()) {
                var found = false
                for (end in remaining.length downTo 1) {
                    val candidate = if (isFirst) remaining.substring(0, end)
                                    else "##${remaining.substring(0, end)}"
                    val id = vocab[candidate]
                    if (id != null) {
                        result += id
                        remaining = remaining.substring(end)
                        isFirst = false
                        found = true
                        break
                    }
                }
                if (!found) { result += unkId; break }
            }
            return result
        }
    }
    