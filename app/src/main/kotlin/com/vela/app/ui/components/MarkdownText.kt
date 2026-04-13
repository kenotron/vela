    package com.vela.app.ui.components

    import android.graphics.Typeface
    import android.text.method.LinkMovementMethod
    import android.widget.TextView
    import androidx.compose.material3.LocalContentColor
    import androidx.compose.runtime.Composable
    import androidx.compose.runtime.remember
    import androidx.compose.ui.Modifier
    import androidx.compose.ui.graphics.Color
    import androidx.compose.ui.graphics.toArgb
    import androidx.compose.ui.platform.LocalContext
    import androidx.compose.ui.viewinterop.AndroidView
    import io.noties.markwon.Markwon
    import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
    import io.noties.markwon.ext.tables.TablePlugin
    import io.noties.markwon.ext.tasklist.TaskListPlugin
    import io.noties.markwon.linkify.LinkifyPlugin

    /**
     * Full GFM markdown rendered via Markwon (io.noties.markwon:*:4.6.2),
     * the gold-standard Android markdown library.
     *
     * Supports:
     *   # Headings (H1–H6)        **bold** / *italic* / ~~strikethrough~~
     *   `inline code`               ```fenced code blocks```
     *   | tables |                  - bullet lists  /  1. ordered lists
     *   > blockquotes               - [x] task lists
     *   [links](url)                auto-linking URLs
     *
     * Rendered via [AndroidView] so it integrates with the existing Compose
     * layout. Text colour automatically follows the caller’s [LocalContentColor]
     * so it looks correct inside both light and dark bubbles.
     */
    @Composable
    fun MarkdownText(
        text: String,
        modifier: Modifier = Modifier,
        color: Color = Color.Unspecified,
    ) {
        val context   = LocalContext.current
        val textColor = if (color != Color.Unspecified) color else LocalContentColor.current
        val colorArgb = textColor.toArgb()

        // Build Markwon once per context — it’s heavy to construct on every recomposition.
        val markwon = remember(context) {
            Markwon.builder(context)
                .usePlugin(StrikethroughPlugin.create())
                .usePlugin(TablePlugin.create(context))
                .usePlugin(TaskListPlugin.create(context))
                .usePlugin(LinkifyPlugin.create())
                .build()
        }

        AndroidView(
            modifier = modifier,
            factory = { ctx ->
                TextView(ctx).apply {
                    setTextIsSelectable(true)
                    movementMethod = LinkMovementMethod.getInstance()
                    textSize       = 15f
                    setLineSpacing(0f, 1.3f)
                    typeface       = Typeface.DEFAULT
                }
            },
            update = { tv ->
                tv.setTextColor(colorArgb)
                markwon.setMarkdown(tv, text)
            },
        )
    }
    