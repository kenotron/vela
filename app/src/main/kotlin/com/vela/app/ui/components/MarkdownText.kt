    package com.vela.app.ui.components

    import androidx.compose.foundation.background
    import androidx.compose.foundation.horizontalScroll
    import androidx.compose.foundation.layout.*
    import androidx.compose.foundation.rememberScrollState
    import androidx.compose.foundation.shape.RoundedCornerShape
    import androidx.compose.material3.MaterialTheme
    import androidx.compose.material3.Text
    import androidx.compose.runtime.Composable
    import androidx.compose.runtime.remember
    import androidx.compose.ui.Modifier
    import androidx.compose.ui.graphics.Color
    import androidx.compose.ui.text.*
    import androidx.compose.ui.text.font.FontFamily
    import androidx.compose.ui.text.font.FontStyle
    import androidx.compose.ui.text.font.FontWeight
    import androidx.compose.ui.text.style.TextDecoration
    import androidx.compose.ui.unit.dp
    import androidx.compose.ui.unit.sp

    /**
     * Pure-Compose markdown renderer. No third-party dependencies.
     *
     * Handles:
     *   **bold**  *italic*  `inline code`
     *   # H1  ## H2  ### H3
     *   - / * bullet lists    1. numbered lists
     *   ``` code blocks ```
     *   > blockquotes
     *   ~~strikethrough~~
     */
    @Composable
    fun MarkdownText(
        text: String,
        modifier: Modifier = Modifier,
        color: Color = Color.Unspecified,
    ) {
        val colorScheme = MaterialTheme.colorScheme
        val typography  = MaterialTheme.typography

        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val segments = remember(text) { splitIntoSegments(text) }

            for (seg in segments) {
                when (seg) {
                    is Segment.CodeBlock -> {
                        Text(
                            text   = seg.code,
                            style  = typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color  = colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                    RoundedCornerShape(8.dp),
                                )
                                .horizontalScroll(rememberScrollState())
                                .padding(10.dp),
                        )
                    }
                    is Segment.Blockquote -> {
                        Row {
                            Box(
                                Modifier
                                    .width(3.dp)
                                    .fillMaxHeight()
                                    .background(
                                        colorScheme.primary.copy(alpha = 0.5f),
                                        RoundedCornerShape(2.dp),
                                    )
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text  = buildInlineAnnotated(seg.content, colorScheme.surfaceVariant),
                                style = typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                                color = if (color == Color.Unspecified) colorScheme.onSurfaceVariant else color,
                            )
                        }
                    }
                    is Segment.Heading -> {
                        val style = when (seg.level) {
                            1 -> typography.titleLarge
                            2 -> typography.titleMedium
                            else -> typography.titleSmall
                        }
                        Text(
                            text   = buildInlineAnnotated(seg.content, colorScheme.surfaceVariant),
                            style  = style.copy(fontWeight = FontWeight.Bold),
                            color  = if (color == Color.Unspecified) colorScheme.onSurface else color,
                        )
                    }
                    is Segment.ListItem -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text  = if (seg.ordered) "${seg.number}." else "•",
                                style = typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                color = if (color == Color.Unspecified) colorScheme.onSurface else color,
                            )
                            Text(
                                text  = buildInlineAnnotated(seg.content, colorScheme.surfaceVariant),
                                style = typography.bodyMedium,
                                color = if (color == Color.Unspecified) colorScheme.onSurface else color,
                            )
                        }
                    }
                    is Segment.Paragraph -> {
                        if (seg.content.isBlank()) {
                            Spacer(Modifier.height(2.dp))
                        } else {
                            Text(
                                text  = buildInlineAnnotated(seg.content, colorScheme.surfaceVariant),
                                style = typography.bodyMedium,
                                color = if (color == Color.Unspecified) colorScheme.onSurface else color,
                            )
                        }
                    }
                }
            }
        }
    }

    // ── Segment model ─────────────────────────────────────────────────────────────

    private sealed interface Segment {
        data class CodeBlock(val code: String, val lang: String = "") : Segment
        data class Blockquote(val content: String) : Segment
        data class Heading(val level: Int, val content: String) : Segment
        data class ListItem(val ordered: Boolean, val number: Int = 1, val content: String) : Segment
        data class Paragraph(val content: String) : Segment
    }

    private fun splitIntoSegments(text: String): List<Segment> {
        val segments = mutableListOf<Segment>()
        val lines    = text.lines()
        var i        = 0

        while (i < lines.size) {
            val line = lines[i]

            // Fenced code block
            if (line.trimStart().startsWith("```")) {
                val lang  = line.trimStart().removePrefix("```").trim()
                val code  = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    code.appendLine(lines[i])
                    i++
                }
                segments += Segment.CodeBlock(code.toString().trimEnd(), lang)
                i++ // consume closing ```
                continue
            }

            // Blockquote
            if (line.startsWith("> ")) {
                segments += Segment.Blockquote(line.removePrefix("> "))
                i++; continue
            }

            // Heading
            val headingMatch = Regex("^(#{1,3}) (.+)").find(line)
            if (headingMatch != null) {
                segments += Segment.Heading(
                    headingMatch.groupValues[1].length,
                    headingMatch.groupValues[2],
                )
                i++; continue
            }

            // Unordered list
            val unorderedMatch = Regex("^[-*+]\\s+(.+)").find(line)
            if (unorderedMatch != null) {
                segments += Segment.ListItem(ordered = false, content = unorderedMatch.groupValues[1])
                i++; continue
            }

            // Ordered list
            val orderedMatch = Regex("^(\\d+)\\.\\s+(.+)").find(line)
            if (orderedMatch != null) {
                segments += Segment.ListItem(
                    ordered = true,
                    number  = orderedMatch.groupValues[1].toIntOrNull() ?: 1,
                    content = orderedMatch.groupValues[2],
                )
                i++; continue
            }

            // Paragraph (everything else)
            segments += Segment.Paragraph(line)
            i++
        }

        return segments
    }

    // ── Inline markdown → AnnotatedString ─────────────────────────────────────────

    private fun buildInlineAnnotated(text: String, codeBg: Color): AnnotatedString {
        return buildAnnotatedString {
            var remaining = text
            while (remaining.isNotEmpty()) {
                when {
                    // Bold+italic ***text*** or ___text___
                    remaining.startsWith("***") || remaining.startsWith("___") -> {
                        val end = remaining.indexOf(remaining.substring(0, 3), 3)
                        if (end >= 3) {
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) {
                                append(remaining.substring(3, end))
                            }
                            remaining = remaining.substring(end + 3)
                        } else { append(remaining[0]); remaining = remaining.drop(1) }
                    }
                    // Bold **text** or __text__
                    remaining.startsWith("**") || remaining.startsWith("__") -> {
                        val end = remaining.indexOf(remaining.substring(0, 2), 2)
                        if (end >= 2) {
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                append(remaining.substring(2, end))
                            }
                            remaining = remaining.substring(end + 2)
                        } else { append(remaining[0]); remaining = remaining.drop(1) }
                    }
                    // Italic *text* or _text_
                    (remaining.startsWith("*") && !remaining.startsWith("**")) ||
                    (remaining.startsWith("_") && !remaining.startsWith("__")) -> {
                        val delim = remaining[0].toString()
                        val end   = remaining.indexOf(delim, 1)
                        if (end > 1) {
                            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                                append(remaining.substring(1, end))
                            }
                            remaining = remaining.substring(end + 1)
                        } else { append(remaining[0]); remaining = remaining.drop(1) }
                    }
                    // Strikethrough ~~text~~
                    remaining.startsWith("~~") -> {
                        val end = remaining.indexOf("~~", 2)
                        if (end >= 2) {
                            withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                                append(remaining.substring(2, end))
                            }
                            remaining = remaining.substring(end + 2)
                        } else { append(remaining[0]); remaining = remaining.drop(1) }
                    }
                    // Inline code `text`
                    remaining.startsWith("`") -> {
                        val end = remaining.indexOf('`', 1)
                        if (end > 1) {
                            withStyle(SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize   = 13.sp,
                                background = codeBg.copy(alpha = 0.4f),
                            )) {
                                append(remaining.substring(1, end))
                            }
                            remaining = remaining.substring(end + 1)
                        } else { append(remaining[0]); remaining = remaining.drop(1) }
                    }
                    else -> { append(remaining[0]); remaining = remaining.drop(1) }
                }
            }
        }
    }
    