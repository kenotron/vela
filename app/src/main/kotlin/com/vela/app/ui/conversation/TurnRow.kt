package com.vela.app.ui.conversation

    import androidx.compose.animation.core.FastOutSlowInEasing
    import androidx.compose.animation.core.LinearEasing
    import androidx.compose.animation.core.RepeatMode
    import androidx.compose.animation.core.animateFloat
    import androidx.compose.animation.core.infiniteRepeatable
    import androidx.compose.animation.core.rememberInfiniteTransition
    import androidx.compose.animation.core.snap
    import androidx.compose.animation.core.tween
    import androidx.compose.foundation.BorderStroke
    import androidx.compose.foundation.background
    import androidx.compose.foundation.clickable
    import androidx.compose.foundation.layout.*
    import androidx.compose.foundation.shape.CircleShape
    import androidx.compose.foundation.shape.RoundedCornerShape
    import androidx.compose.material.icons.Icons
    import androidx.compose.material.icons.filled.Image
    import androidx.compose.material.icons.filled.InsertDriveFile
    import androidx.compose.material.icons.filled.SmartToy
    import androidx.compose.material3.*
    import androidx.compose.runtime.*
    import androidx.compose.ui.Alignment
    import androidx.compose.ui.Modifier
    import androidx.compose.ui.draw.alpha
    import androidx.compose.ui.draw.clip
    import androidx.compose.ui.draw.rotate
    import androidx.compose.ui.platform.LocalConfiguration
    import androidx.compose.ui.text.font.FontFamily
    import androidx.compose.ui.text.font.FontWeight
    import androidx.compose.ui.text.style.TextOverflow
    import androidx.compose.ui.unit.Dp
    import androidx.compose.ui.unit.dp
    import com.vela.app.data.db.TurnEventEntity
    import com.vela.app.data.db.TurnWithEvents
    import com.vela.app.engine.ContentBlockRef
    import com.vela.app.engine.parseContentBlockRefs
    import com.vela.app.ui.components.MarkdownText
    import org.json.JSONObject

    // ---- Turn item model --------------------------------------------------------

    internal data class ToolGroup(val events: List<TurnEventEntity>)
    internal data class TextEvt(val event: TurnEventEntity)
    internal sealed class TurnItem {
        data class Tools(val group: ToolGroup) : TurnItem()
        data class Text(val evt: TextEvt) : TurnItem()
        /** Rendered bubble for a completed delegate sub-agent response. */
        data class AgentResponse(
            val id: String,
            val agentName: String,
            val text: String,
        ) : TurnItem()
    }

    /**
     * Pure Kotlin helper — builds the ordered list of [TurnItem]s from a turn's events.
     *
     * Consecutive tool events are grouped into [TurnItem.Tools]. A text event breaks
     * the current group, flushing it first. A completed `delegate` tool event with a
     * non-blank [TurnEventEntity.toolResult] additionally emits a [TurnItem.AgentResponse]
     * immediately after its [TurnItem.Tools] group.
     */
    internal fun buildTurnItems(events: List<TurnEventEntity>): List<TurnItem> = buildList {
        val pending = mutableListOf<TurnEventEntity>()

        fun flushPending() {
            if (pending.isEmpty()) return
            val group = pending.toList()
            add(TurnItem.Tools(ToolGroup(group)))
            // Emit AgentResponse items for completed delegate events in this group.
            group.forEach { ev ->
                if (ev.toolName == "delegate" && ev.toolStatus == "done" && !ev.toolResult.isNullOrBlank()) {
                    val agentName = runCatching {
                        org.json.JSONObject(ev.toolArgs ?: "{}").optString("agent", "agent")
                            .takeIf { it.isNotBlank() } ?: "agent"
                    }.getOrDefault("agent")
                    add(TurnItem.AgentResponse(id = "${ev.id}_resp", agentName = agentName, text = ev.toolResult))
                }
            }
            pending.clear()
        }

        events.forEach { event ->
            when (event.type) {
                "tool" -> pending.add(event)
                else   -> {
                    flushPending()
                    if (!event.text.isNullOrBlank()) {
                        add(TurnItem.Text(TextEvt(event)))
                    }
                }
            }
        }
        flushPending()
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
            // Delegate tool calls are rendered by DelegateChip inside ToolGroupRow.
            val items: List<TurnItem> = remember(twe.sortedEvents) {
                buildTurnItems(twe.sortedEvents)
            }

            items.forEach { item ->
                key(when (item) {
                    is TurnItem.Tools         -> item.group.events.first().id
                    is TurnItem.Text          -> item.evt.event.id
                    is TurnItem.AgentResponse -> item.id
                }) {
                    when (item) {
                        is TurnItem.Tools         -> ToolGroupRow(item.group.events)
                        is TurnItem.Text          -> TextEventRow(
                            text      = item.evt.event.text ?: "",
                            streaming = false,
                            maxW      = maxW,
                            agentName = item.evt.event.agentName,
                        )
                        is TurnItem.AgentResponse -> TextEventRow(
                            text      = item.text,
                            streaming = false,
                            maxW      = maxW,
                            agentName = item.agentName,
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
    internal fun TextEventRow(text: String, streaming: Boolean, maxW: Dp, agentName: String? = null) {
        val cs  = MaterialTheme.colorScheme
        val inf = rememberInfiniteTransition(label = "pulse")
        val alpha by inf.animateFloat(0.3f, 1f, infiniteRepeatable(tween(600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "a")

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
            Column {
                // Agent name badge — shown when response came via delegation
                if (agentName != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                    ) {
                        Icon(
                            Icons.Default.SmartToy,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = cs.primary,
                        )
                        Text(
                            agentName,
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.primary,
                        )
                    }
                }
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
    }

    /**
     * Claude Desktop-style progressive-disclosure chip for agent delegation.
     *
     * Collapsed:  "Running zen-architect · Analyse the module structure…  ›"
     * Done:       "Ran zen-architect · Analyse the module structure…"
     * Tap → ModalBottomSheet with full instruction, tools called, and response.
     */
    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    @Composable
    internal fun DelegateChip(ev: TurnEventEntity) {
        val cs = MaterialTheme.colorScheme
        var showSheet by remember { mutableStateOf(false) }

        val agentName = ev.toolDisplayName?.takeIf { it.isNotBlank() } ?: "agent"
        val preview   = ev.toolSummary ?: ""
        val isDone    = ev.toolStatus == "done"
        val verb      = if (isDone) "Ran" else "Running"

        val resultJson   = runCatching { org.json.JSONObject(ev.toolResult ?: "{}") }.getOrNull()
        val toolsCalled  = resultJson?.optJSONArray("tools_called")?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        } ?: emptyList()
        val responseText = resultJson?.optString("response") ?: ""
        val fullInstr    = runCatching {
            org.json.JSONObject(ev.toolArgs ?: "{}").optString("instruction")
        }.getOrElse { "" }

        // ── Chip ──────────────────────────────────────────────────────────────
        // Pulse animation: dot breathes while agent is running (always created, only shown when !isDone)
        val inf = rememberInfiniteTransition(label = "agent_pill")
        val pulseAlpha by inf.animateFloat(
            initialValue  = 0.35f,
            targetValue   = 1f,
            animationSpec = infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse),
            label         = "dot_pulse",
        )

        Surface(
            shape    = RoundedCornerShape(20.dp),            // pill
            color    = if (isDone) cs.surfaceContainerHigh
                       else cs.primary.copy(alpha = 0.10f), // tinted while live
            border   = BorderStroke(
                1.dp,
                if (isDone) cs.outlineVariant
                else cs.primary.copy(alpha = 0.50f),
            ),
            modifier = Modifier
                .wrapContentWidth()
                .clickable { showSheet = true },
        ) {
            Row(
                modifier              = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                // Live indicator dot
                if (!isDone) {
                    Box(
                        Modifier
                            .size(6.dp)
                            .alpha(pulseAlpha)
                            .background(cs.primary, CircleShape)
                    )
                }
                Text(
                    text  = "$verb ",
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                )
                Text(
                    text       = agentName,
                    style      = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color      = cs.onSurface,
                )
                if (preview.isNotBlank()) {
                    Text(
                        text     = "· $preview",
                        style    = MaterialTheme.typography.labelSmall,
                        color    = cs.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 180.dp),
                    )
                }
                if (!isDone) {
                    Text("›",
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.primary.copy(alpha = 0.7f))
                }
            }
        }

        // ── Bottom sheet ──────────────────────────────────────────────────────
        if (showSheet) {
            ModalBottomSheet(onDismissRequest = { showSheet = false }) {
                Column(
                    modifier            = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 36.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    // Header
                    Row(
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text       = agentName,
                            style      = MaterialTheme.typography.titleMedium,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            color      = cs.onSurface,
                        )
                        if (!isDone) {
                            Text(
                                text  = "running…",
                                style = MaterialTheme.typography.labelSmall,
                                color = cs.primary.copy(alpha = 0.7f),
                            )
                        }
                    }
                    // Full instruction
                    if (fullInstr.isNotBlank()) {
                        Text(
                            text  = fullInstr,
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                        )
                    }
                    // Tools called
                    if (toolsCalled.isNotEmpty()) {
                        HorizontalDivider(color = cs.outlineVariant)
                        Text(
                            "Tools called",
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.onSurfaceVariant,
                        )
                        val toolIconMap = mapOf(
                            "read_file" to "📄", "write_file" to "✏️", "edit_file"  to "✏️",
                            "glob"      to "🔍", "grep"       to "🔍", "bash"       to "💻",
                            "web_fetch" to "🌐", "search_web" to "🌐",
                            "delegate"  to "🤖", "todo"       to "✅", "load_skill" to "⚡",
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            toolsCalled.forEach { toolName ->
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment     = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        toolIconMap[toolName] ?: "🔧",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Text(
                                        toolName,
                                        style      = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color      = cs.onSurface,
                                    )
                                }
                            }
                        }
                    }
                    // Response (collapsible)
                    if (responseText.isNotBlank()) {
                        HorizontalDivider(color = cs.outlineVariant)
                        var responseExpanded by remember { mutableStateOf(false) }
                        Row(
                            modifier              = Modifier
                                .fillMaxWidth()
                                .clickable { responseExpanded = !responseExpanded },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment     = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Response",
                                style = MaterialTheme.typography.labelSmall,
                                color = cs.onSurfaceVariant,
                            )
                            Text(
                                if (responseExpanded) "▴" else "▾",
                                style = MaterialTheme.typography.labelSmall,
                                color = cs.onSurfaceVariant,
                            )
                        }
                        if (responseExpanded) {
                            Text(
                                responseText,
                                style = MaterialTheme.typography.bodySmall,
                                color = cs.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
    