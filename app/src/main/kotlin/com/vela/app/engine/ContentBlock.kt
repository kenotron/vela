
package com.vela.app.engine

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

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

    is ContentBlock.Document -> JSONObject()
        .put("type", "document")
        .put("source", JSONObject()
            .put("type", "base64")
            .put("media_type", mediaType)
            .put("data", base64Data))
        .also { obj -> title?.let { obj.put("title", it) } }
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
