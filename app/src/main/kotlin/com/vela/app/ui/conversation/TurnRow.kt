package com.vela.app.ui.conversation

    import androidx.compose.animation.core.FastOutSlowInEasing
    import androidx.compose.animation.core.RepeatMode
    import androidx.compose.animation.core.animateFloat
    import androidx.compose.animation.core.infiniteRepeatable
    import androidx.compose.animation.core.rememberInfiniteTransition
    import androidx.compose.animation.core.tween
    import androidx.compose.foundation.background
    import androidx.compose.foundation.layout.*
    import androidx.compose.foundation.shape.CircleShape
    import androidx.compose.foundation.shape.RoundedCornerShape
    import androidx.compose.material.icons.Icons
    import androidx.compose.material.icons.filled.Image
    import androidx.compose.material.icons.filled.InsertDriveFile
    import androidx.compose.material3.*
    import androidx.compose.runtime.*
    import androidx.compose.ui.Alignment
    import androidx.compose.ui.Modifier
    import androidx.compose.ui.draw.alpha
    import androidx.compose.ui.draw.clip
    import androidx.compose.ui.draw.rotate
    import androidx.compose.ui.platform.LocalConfiguration
    import androidx.compose.ui.unit.Dp
    import androidx.compose.ui.unit.dp
    import com.vela.app.data.db.TurnEventEntity
    import com.vela.app.data.db.TurnWithEvents
    import com.vela.app.engine.ContentBlockRef
    import com.vela.app.engine.parseContentBlockRefs
    import com.vela.app.ui.components.MarkdownText

    // ---- Turn item model --------------------------------------------------------

    internal data class ToolGroup(val events: List<TurnEventEntity>)
    internal data class TextEvt(val event: TurnEventEntity)
    internal sealed class TurnItem {
        data class Tools(val group: ToolGroup) : TurnItem()
        data class Text(val evt: TextEvt) : TurnItem()
    }

    // ---- Turn row ---------------------------------------------------------------

    internal val AssistantShape = RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp)
    internal val UserShape      = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)

    @Composable
    internal fun TurnRow(twe: TurnWithEvents, streamingText: String?, isLive: Boolean) {
        val maxW = (LocalConfiguration.current.screenWidthDp * 0.85).dp
        val cs   = MaterialTheme.colorScheme

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {

            // User bubble — text + attachment chips (images / files)
            val attachmentRefs = remember(twe.turn.userContentJson) {
                twe.turn.userContentJson
                    ?.let { runCatching { parseContentBlockRefs(it) }.getOrNull() }
                    ?.filterNot { it is ContentBlockRef.Text }
                    ?: emptyList()
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // Attachment chips (images / files)
                    if (attachmentRefs.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            attachmentRefs.forEach { ref ->
                                val isImage = ref is ContentBlockRef.ImageRef
                                Box(
                                    modifier = Modifier
                                        .size(width = 56.dp, height = 48.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (isImage) cs.primaryContainer
                                            else cs.secondaryContainer
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center,
                                    ) {
                                        Icon(
                                            imageVector = if (isImage) Icons.Default.Image
                                                          else Icons.Default.InsertDriveFile,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                            tint = if (isImage) cs.onPrimaryContainer
                                                   else cs.onSecondaryContainer,
                                        )
                                        Text(
                                            text = if (isImage) "IMG" else "FILE",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (isImage) cs.onPrimaryContainer
                                                    else cs.onSecondaryContainer,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    // Text bubble — only rendered when there is text.
                    // Strip <vela-meta>...</vela-meta> before display — these are
                    // invisible timestamp markers for the LLM, not for the user.
                    val displayMessage = twe.turn.userMessage
                        .replace(Regex("<vela-meta>[^<]*</vela-meta>\n*"), "")
                    if (displayMessage.isNotBlank()) {
                        Box(
                            Modifier
                                .widthIn(max = maxW)
                                .background(cs.surfaceContainerHighest, UserShape)
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                        ) {
                            Text(
                                displayMessage,
                                style = MaterialTheme.typography.bodyMedium,
                                color = cs.onSurface,
                            )
                        }
                    }
                }
            }

            // Group consecutive tool events; text events break groups.
            val items: List<TurnItem> = buildList {
                val pending = mutableListOf<TurnEventEntity>()
                twe.sortedEvents.forEach { event ->
                    when (event.type) {
                        "tool" -> pending.add(event)
                        else   -> {
                            if (pending.isNotEmpty()) {
                                add(TurnItem.Tools(ToolGroup(pending.toList())))
                                pending.clear()
                            }
                            if (!event.text.isNullOrBlank()) {
                                add(TurnItem.Text(TextEvt(event)))
                            }
                        }
                    }
                }
                if (pending.isNotEmpty()) add(TurnItem.Tools(ToolGroup(pending.toList())))
            }

            items.forEach { item ->
                key(when (item) {
                    is TurnItem.Tools -> item.group.events.first().id
                    is TurnItem.Text  -> item.evt.event.id
                }) {
                    when (item) {
                        is TurnItem.Tools -> ToolGroupRow(item.group.events)
                        is TurnItem.Text  -> TextEventRow(
                            text      = item.evt.event.text ?: "",
                            streaming = false,
                            maxW      = maxW,
                        )
                    }
                }
            }

            // In-memory streaming text for the live turn (not yet committed as a text TurnEvent)
            if (isLive) {
                if (!streamingText.isNullOrEmpty()) {
                    TextEventRow(streamingText!!, streaming = true, maxW = maxW)
                } else {
                    // Always show a "working" indicator while the turn is live,
                    // regardless of whether pre-tool text events already exist.
                    // The old `hasNoTextEvents` guard hid the indicator whenever
                    // the LLM had written anything before its first tool call.
                    LiveWorkingRow(
                        runningToolName = twe.sortedEvents
                            .lastOrNull { it.type == "tool" && it.toolStatus == "running" }
                            ?.let { it.toolDisplayName ?: it.toolName },
                    )
                }
            }
        }
    }

    /**
     * Shown whenever the turn is live but no text is streaming yet.
     *
     * Two states:
     *  • A named tool is still "running"  → "[icon] tool-name…" with a spinner ring
     *  • Between tools / waiting for LLM  → animated "Thinking…" dots
     *
     * Previously this was a tiny 8dp pulsing dot that disappeared whenever any
     * pre-tool text event existed (the old `hasNoTextEvents` guard).  Now it
     * always shows while the turn is live so the user always has feedback.
     */
    @Composable
    private fun LiveWorkingRow(runningToolName: String?) {
        val cs  = MaterialTheme.colorScheme
        val inf = rememberInfiniteTransition(label = "live")

        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (runningToolName != null) {
                // A specific tool is in-flight — show its name with a spinner
                val angle by inf.animateFloat(
                    initialValue   = 0f,
                    targetValue    = 360f,
                    animationSpec  = infiniteRepeatable(tween(900, easing = androidx.compose.animation.core.LinearEasing)),
                    label          = "spin",
                )
                androidx.compose.foundation.Canvas(modifier = Modifier.size(14.dp).rotate(angle)) {
                    drawArc(
                        color      = cs.primary.copy(alpha = 0.7f),
                        startAngle = 0f,
                        sweepAngle = 270f,
                        useCenter  = false,
                        style      = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()),
                    )
                }
                Text(
                    text  = "$runningToolName…",
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                )
            } else {
                // Between tools or waiting for first LLM token — animated dots
                val dot1 by inf.animateFloat(0.2f, 0.9f, infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "d1")
                val dot2 by inf.animateFloat(0.2f, 0.9f, infiniteRepeatable(tween(500, delayMillis = 160), RepeatMode.Reverse), label = "d2")
                val dot3 by inf.animateFloat(0.2f, 0.9f, infiniteRepeatable(tween(500, delayMillis = 320), RepeatMode.Reverse), label = "d3")
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(dot1, dot2, dot3).forEach { a ->
                        Box(Modifier.size(6.dp).alpha(a).background(cs.onSurfaceVariant, CircleShape))
                    }
                }
                Text(
                    text  = "Thinking…",
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                )
            }
        }
    }

    @Composable
    internal fun TextEventRow(text: String, streaming: Boolean, maxW: Dp) {
        val cs  = MaterialTheme.colorScheme
        val inf = rememberInfiniteTransition(label = "pulse")
        val alpha by inf.animateFloat(0.3f, 1f, infiniteRepeatable(tween(600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "a")

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
            // surfaceContainerHigh gives a noticeably lighter card tone than cs.surface
            // so the bubble stands out clearly against the dark star-field background.
            Box(Modifier.widthIn(max = maxW).background(cs.surfaceContainerHigh, AssistantShape).padding(horizontal = 14.dp, vertical = 10.dp)) {
                Column {
                    MarkdownText(text = text, color = cs.onSurface)
                    if (streaming) {
                        Spacer(Modifier.height(4.dp))
                        Box(Modifier.size(7.dp).alpha(alpha).background(cs.onSurface.copy(alpha = 0.3f), CircleShape))
                    }
                }
            }
        }
    }
    