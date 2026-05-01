package com.vela.app.ui.sessiondetail

import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.linkify.LinkifyPlugin

@Composable
fun MarkdownText(
    markdown: String,
    color: Color,
    modifier: Modifier = Modifier,
    textSizeSp: Float = 14f,
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextView(ctx).apply {
                val mw = Markwon.builder(ctx)
                    .usePlugin(StrikethroughPlugin.create())
                    .usePlugin(TablePlugin.create(ctx))
                    .usePlugin(TaskListPlugin.create(ctx))
                    .usePlugin(LinkifyPlugin.create())
                    .build()
                setTextColor(color.toArgb())
                textSize = textSizeSp
                mw.setMarkdown(this, markdown)
                tag = mw
            }
        },
        update = { view ->
            (view.tag as? Markwon)?.setMarkdown(view, markdown)
            view.setTextColor(color.toArgb())
        }
    )
}
