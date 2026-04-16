
package com.vela.app.engine

import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "ContentBlock"

sealed class ContentBlock {
    data class Text(val text: String) : ContentBlock()
    data class Image(val base64Data: String, val mediaType: String) : ContentBlock()
    data class Document(val base64Data: String, val mediaType: String, val title: String? = null) : ContentBlock()
}

fun ContentBlock.toApiJson(): JSONObject = when (this) {
    is ContentBlock.Text -> JSONObject()
        .put("type", "text")
        .put("text", text)

    is ContentBlock.Image -> JSONObject()
        .put("type", "image")
        .put("source", JSONObject()
            .put("type", "base64")
            .put("media_type", mediaType)
            .put("data", base64Data))

    is ContentBlock.Document -> {
        // Anthropic's Messages API only accepts source.type="base64" for PDFs.
        // All text-type documents (text/plain, text/html, text/csv, etc.) must use
        // source.type="text" with the raw decoded string — not base64 + media_type.
        val source = if (mediaType == "application/pdf") {
            JSONObject()
                .put("type", "base64")
                .put("media_type", "application/pdf")
                .put("data", base64Data)
        } else {
            // PlainTextSourceParam: { type="text", media_type="text/plain", data=raw_utf8 }
            // data must be a plain UTF-8 string — NOT base64.
            val decoded = runCatching {
                Base64.decode(base64Data, Base64.NO_WRAP).toString(Charsets.UTF_8)
            }.getOrDefault(base64Data)
            JSONObject()
                .put("type", "text")
                .put("media_type", "text/plain")
                .put("data", decoded)
        }
        JSONObject()
            .put("type", "document")
            .put("source", source)
            .also { obj -> title?.let { obj.put("title", it) } }
    }
}

fun List<ContentBlock>.toApiJsonString(): String =
    JSONArray(map { it.toApiJson() }).toString()

/** Build content blocks from a text string + URI attachments. Reads file bytes from each URI. */
fun buildContentBlocks(
    context: android.content.Context,
    text: String,
    attachments: List<Pair<android.net.Uri, String>>,  // uri, mimeType
): List<ContentBlock> = buildList {
    if (text.isNotBlank()) add(ContentBlock.Text(text))
    attachments.forEach { (uri, mimeType) ->
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return@forEach

        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val name = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && idx >= 0) cursor.getString(idx) else null
        }

        when {
            mimeType.startsWith("image/") -> add(ContentBlock.Image(b64, mimeType))
            else -> add(ContentBlock.Document(b64, mimeType, name))
        }
    }
}

// ── ContentBlockRef ────────────────────────────────────────────────────────────
// Compact references stored in the DB — no base64, just URIs / file paths.
// Call resolveContentBlockRefs() on Dispatchers.IO to materialise to ContentBlock.

sealed class ContentBlockRef {
    data class Text(val text: String) : ContentBlockRef()
    data class ImageRef(val uri: String, val mimeType: String) : ContentBlockRef()
    data class FileRef(val path: String, val mimeType: String, val title: String? = null) : ContentBlockRef()
}

fun ContentBlockRef.toRefJson(): JSONObject = when (this) {
    is ContentBlockRef.Text     -> JSONObject().put("type", "text").put("text", text)
    is ContentBlockRef.ImageRef -> JSONObject().put("type", "image_ref").put("uri", uri).put("mime", mimeType)
    is ContentBlockRef.FileRef  -> JSONObject().put("type", "file_ref").put("path", path).put("mime", mimeType)
        .also { o -> title?.let { o.put("title", it) } }
}

fun List<ContentBlockRef>.toRefJsonString(): String = JSONArray(map { it.toRefJson() }).toString()

/** Parse ref JSON back to a ContentBlockRef list. Unknown types (e.g. legacy base64 entries) are skipped. */
fun parseContentBlockRefs(json: String): List<ContentBlockRef> {
    val arr = JSONArray(json)
    return (0 until arr.length()).mapNotNull { i ->
        val obj = arr.getJSONObject(i)
        when (obj.getString("type")) {
            "text"      -> ContentBlockRef.Text(obj.getString("text"))
            "image_ref" -> ContentBlockRef.ImageRef(obj.getString("uri"), obj.getString("mime"))
            "file_ref"  -> ContentBlockRef.FileRef(
                path  = obj.getString("path"),
                mimeType = obj.getString("mime"),
                title = obj.optString("title").takeIf { it.isNotEmpty() },
            )
            else        -> null   // skip legacy base64 entries or unknown types
        }
    }
}

/**
 * Resolve refs to actual ContentBlocks with base64 data — call on Dispatchers.IO.
 * Content URIs (content://) and file:// URIs are opened via ContentResolver;
 * bare filesystem paths are read directly.
 */
fun resolveContentBlockRefs(
    context: android.content.Context,
    refs: List<ContentBlockRef>,
): List<ContentBlock> = refs.mapNotNull { ref ->
    when (ref) {
        is ContentBlockRef.Text -> ContentBlock.Text(ref.text)

        is ContentBlockRef.ImageRef -> {
            val uri = android.net.Uri.parse(ref.uri)
            val bytes = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.onFailure { e ->
                Log.e(TAG, "resolveContentBlockRefs: failed to open image URI ${ref.uri}", e)
            }.getOrNull()
            if (bytes == null) {
                Log.w(TAG, "resolveContentBlockRefs: no bytes for image URI ${ref.uri} (mime=${ref.mimeType}) — dropping image block")
                return@mapNotNull null
            }
            Log.d(TAG, "resolveContentBlockRefs: read image ${bytes.size}B mime=${ref.mimeType}")
            ContentBlock.Image(Base64.encodeToString(bytes, Base64.NO_WRAP), ref.mimeType)
        }

        is ContentBlockRef.FileRef -> {
            val bytes: ByteArray? = when {
                ref.path.startsWith("content://") || ref.path.startsWith("file://") -> {
                    val uri = android.net.Uri.parse(ref.path)
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    }.onFailure { e ->
                        Log.e(TAG, "resolveContentBlockRefs: failed to open file URI ${ref.path}", e)
                    }.getOrNull()
                }
                else -> runCatching {
                    java.io.File(ref.path).readBytes()
                }.onFailure { e ->
                    Log.e(TAG, "resolveContentBlockRefs: failed to read file ${ref.path}", e)
                }.getOrNull()
            }
            if (bytes == null) {
                Log.w(TAG, "resolveContentBlockRefs: no bytes for file ${ref.path} (mime=${ref.mimeType}) — dropping document block")
                return@mapNotNull null
            }
            Log.d(TAG, "resolveContentBlockRefs: read file ${bytes.size}B mime=${ref.mimeType}")
            ContentBlock.Document(Base64.encodeToString(bytes, Base64.NO_WRAP), ref.mimeType, ref.title)
        }
    }
}

/**
 * Build compact ContentBlockRef list from text + URI attachments.
 * Does NOT read file bytes — only stores URI/path references for later resolution.
 * Call on Dispatchers.IO (ContentResolver metadata query is lightweight but still I/O).
 */
fun buildContentBlockRefs(
    context: android.content.Context,
    text: String,
    attachments: List<Pair<android.net.Uri, String>>,  // uri, mimeType
): List<ContentBlockRef> = buildList {
    if (text.isNotBlank()) add(ContentBlockRef.Text(text))
    attachments.forEach { (uri, mimeType) ->
        val name = context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && idx >= 0) c.getString(idx) else null
        }
        if (mimeType.startsWith("image/")) {
            add(ContentBlockRef.ImageRef(uri.toString(), mimeType))
        } else {
            add(ContentBlockRef.FileRef(uri.toString(), mimeType, name))
        }
    }
}
