package com.vela.app.ui.sessiondetail

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vela.app.ui.theme.InstrumentSerifFamily
import com.vela.app.ui.theme.MonoMedium
import com.vela.app.ui.theme.VelaColors

/**
 * User turn item — Screen 4 turn list.
 *
 * Design: DESIGN.md §9.4 — CRITICAL: NO CHAT BUBBLES.
 * Right-aligned serif text with a 2dp trailing cyan accent line.
 * Max 80% width. Instrument Serif 14sp.
 */
@Composable
fun UserTurnItem(
    text: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier         = modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                text      = text,
                style     = MaterialTheme.typography.bodyLarge.copy(
                    fontFamily = InstrumentSerifFamily,
                ),
                color     = VelaColors.TextPrimary,
                textAlign = TextAlign.End,
                modifier  = Modifier
                    .weight(1f)
                    .padding(start = 40.dp),
            )
            Spacer(Modifier.width(6.dp))
            // 2dp trailing cyan accent line on the RIGHT edge
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(VelaColors.Accent),
            )
        }
    }
}

/**
 * Agent turn item — Screen 4 turn list.
 *
 * Design: DESIGN.md §8 (Screen 4)
 * SurfaceSub card, 20dp radius, Inter 16sp body text.
 * Tool-call cards rendered inline below the prose text.
 */
@Composable
fun AgentTurnItem(
    content: TurnContent,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape    = RoundedCornerShape(20.dp),
        color    = VelaColors.SurfaceSub,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier            = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                content.contentBlocks.isNotEmpty() -> {
                    content.contentBlocks.forEach { block ->
                        when (block) {
                            is ContentBlock.Text    -> MarkdownText(
                                markdown = block.markdown,
                                color    = VelaColors.TextPrimary,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            is ContentBlock.Thinking -> ThinkingBlock(text = block.text)
                            is ContentBlock.ToolUse  -> {
                                val result = content.contentBlocks
                                    .filterIsInstance<ContentBlock.ToolResult>()
                                    .find { it.toolUseId == block.id }
                                if (block.name == "delegate" || block.name.contains("delegate", ignoreCase = true)) {
                                    DelegateBlock(
                                        inputJson   = block.inputJson,
                                        result      = result?.output,
                                        isRunning   = block.isRunning,
                                        childBlocks = block.childBlocks,
                                    )
                                } else {
                                    CollapsibleToolCard(block = block, result = result)
                                }
                            }
                            is ContentBlock.ToolResult -> { /* rendered via matching ToolUse block */ }
                            is ContentBlock.TodoProgress -> TodoProgressCard(
                                todos       = block.todos,
                                isStreaming = content.isStreaming,
                            )
                        }
                    }
                }
                content.text.isBlank() && content.isStreaming -> {
                    // No content yet but turn is live — show thinking pulse instead of empty bubble
                    ThinkingPulse()
                }
                else -> {
                    MarkdownText(
                        markdown = content.text,
                        color    = VelaColors.TextPrimary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    content.toolCalls.forEach { ToolCallCard(it) }
                }
            }
        }
    }
}

/**
 * Compact inline thinking strip — always visible, 2dp accent bar, max 3 lines with ellipsis.
 */
@Composable
fun ThinkingBlock(text: String, modifier: Modifier = Modifier) {
    if (text.isBlank()) return
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 2dp vertical accent bar
        Box(
            modifier = Modifier
                .width(2.dp)
                .heightIn(min = 16.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(VelaColors.Accent.copy(alpha = 0.45f))
                .align(Alignment.Top)
                .padding(top = 2.dp),
        )
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector        = Icons.Default.Psychology,
                    contentDescription = null,
                    tint               = VelaColors.Accent.copy(alpha = 0.6f),
                    modifier           = Modifier.size(11.dp),
                )
                Spacer(Modifier.width(3.dp))
                Text(
                    text  = "thinking",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = VelaColors.Accent.copy(alpha = 0.6f),
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text     = text,
                style    = MaterialTheme.typography.bodySmall.copy(
                    fontSize   = 11.sp,
                    fontStyle  = FontStyle.Italic,
                    lineHeight = 15.sp,
                ),
                color    = VelaColors.TextTertiary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Compact pulsing indicator shown inside an agent turn while waiting for the
 * first content block to arrive. Replaces the empty-bubble problem where
 * the Surface card rendered with no content inside.
 */
@Composable
private fun ThinkingPulse(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "thinkingPulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue  = 0.3f,
        targetValue   = 0.85f,
        animationSpec = infiniteRepeatable(
            animation  = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "thinkingPulseAlpha",
    )

    Row(
        modifier          = modifier.padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector        = Icons.Default.Psychology,
            contentDescription = null,
            tint               = VelaColors.Accent.copy(alpha = alpha),
            modifier           = Modifier.size(13.dp),
        )
        Text(
            text  = "thinking…",
            style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
            color = VelaColors.TextTertiary.copy(alpha = alpha),
        )
    }
}

/**
 * Collapsible tool-call card with input JSON and optional result.
 * Shows a spinner while the tool is in-flight (isRunning && result == null).
 */
@Composable
fun ToolCallBlock(
    name: String,
    inputJson: String,
    result: String? = null,
    isError: Boolean = false,
    isRunning: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(VelaColors.SurfaceSub)
            .padding(12.dp)
    ) {
        Row(
            modifier          = Modifier.clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector        = Icons.Default.Terminal,
                contentDescription = "Tool",
                tint               = VelaColors.Accent,
                modifier           = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text     = name,
                style    = MaterialTheme.typography.labelMedium,
                color    = VelaColors.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            // Running spinner — shown when tool is in-flight, before result arrives
            if (isRunning && result == null) {
                Spacer(Modifier.width(4.dp))
                CircularProgressIndicator(
                    modifier    = Modifier.size(12.dp),
                    color       = VelaColors.Running,
                    strokeWidth = 1.5.dp,
                    progress    = { 0.25f },
                )
            }
            if (result != null) {
                Icon(
                    imageVector        = if (isError) Icons.Default.Error else Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint               = if (isError) VelaColors.Error else VelaColors.Done,
                    modifier           = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
            }
            // Expand/collapse icon only shown when there is content to expand
            if (!(isRunning && result == null)) {
                Icon(
                    imageVector        = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint               = VelaColors.TextTertiary,
                    modifier           = Modifier.size(16.dp),
                )
            }
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            Text(
                text  = "Input:",
                style = MaterialTheme.typography.labelSmall,
                color = VelaColors.TextTertiary,
            )
            Text(
                text  = inputJson,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = VelaColors.TextSecondary,
            )
            if (result != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text  = "Result:",
                    style = MaterialTheme.typography.labelSmall,
                    color = VelaColors.TextTertiary,
                )
                Text(
                    text  = result,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = if (isError) VelaColors.Error else VelaColors.TextSecondary,
                )
            }
        }
    }
}

/**
 * Collapsible card for non-delegate [ContentBlock.ToolUse] blocks.
 * Collapsed: single-line chip. Expanded: full input + output JSON.
 */
@Composable
private fun CollapsibleToolCard(
    block: ContentBlock.ToolUse,
    result: ContentBlock.ToolResult? = null,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val truncatedInput = remember(block.inputJson) {
        block.inputJson.take(60).let { if (block.inputJson.length > 60) "$it…" else it }
    }

    Surface(
        color    = Color(0xFF181825),
        shape    = RoundedCornerShape(8.dp),
        border   = BorderStroke(1.dp, Color(0xFF313244)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier          = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text  = "⚙ ${block.name}",
                    style = MaterialTheme.typography.labelSmall,
                    color = VelaColors.TextSecondary,
                )
                if (!expanded) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text     = truncatedInput,
                        style    = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        color    = VelaColors.TextTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.width(4.dp))
                if (block.isRunning && result == null) {
                    CircularProgressIndicator(
                        modifier    = Modifier.size(10.dp),
                        color       = VelaColors.Running,
                        strokeWidth = 1.5.dp,
                        progress    = { 0.25f },
                    )
                    Spacer(Modifier.width(4.dp))
                }
                if (result != null) {
                    Icon(
                        imageVector        = if (result.isError) Icons.Default.Error
                                             else Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint               = if (result.isError) VelaColors.Error
                                             else VelaColors.Done,
                        modifier           = Modifier.size(12.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Icon(
                    imageVector        = if (expanded) Icons.Default.ExpandLess
                                         else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint               = VelaColors.TextTertiary,
                    modifier           = Modifier.size(14.dp),
                )
            }

            if (expanded) {
                Spacer(Modifier.height(8.dp))
                Text("Input:", style = MaterialTheme.typography.labelSmall, color = VelaColors.TextTertiary)
                Text(
                    text  = block.inputJson,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = VelaColors.TextSecondary,
                )
                if (result != null) {
                    Spacer(Modifier.height(4.dp))
                    Text("Result:", style = MaterialTheme.typography.labelSmall, color = VelaColors.TextTertiary)
                    Text(
                        text  = result.output,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = if (result.isError) VelaColors.Error else VelaColors.TextSecondary,
                    )
                }
            }
        }
    }
}

/**
 * Renders a delegate (sub-agent) invocation block.
 *
 * Collapsed state: shows the header + the latest childBlock as a one-line summary.
 * Expanded state: shows instruction + all childBlocks interleaved (text + tool calls in
 * arrival order, mirroring how the root-session turn renders its content blocks) + final result.
 *
 * Starts collapsed while running so the parent turn stays readable; expands to show full
 * detail on tap. Stays collapsed once done so completed delegates don't dominate the view.
 */
@Composable
fun DelegateBlock(
    inputJson: String,
    result: String? = null,
    isRunning: Boolean = false,
    childBlocks: List<ContentBlock> = emptyList(),
    modifier: Modifier = Modifier,
) {
    val (agentName, instruction) = remember(inputJson) {
        try {
            val obj = org.json.JSONObject(inputJson)
            val agent = obj.optString("agent", "sub-agent")
            val instr = obj.optString("instruction", "")
            Pair(agent, instr)
        } catch (_: Exception) {
            Pair("sub-agent", inputJson.take(200))
        }
    }

    // Collapsed by default — tap header to expand
    var expanded by remember { mutableStateOf(false) }

    // Derive a one-line summary from the latest childBlock for the collapsed view
    val latestSummary = remember(childBlocks) {
        when (val last = childBlocks.lastOrNull()) {
            is ContentBlock.Text     -> last.markdown.lineSequence().firstOrNull { it.isNotBlank() }
                                            ?.take(60) ?: ""
            is ContentBlock.ToolUse  -> "⚙ ${last.name}"
            is ContentBlock.ToolResult -> null // result paired with tool — don't show separately
            else                     -> null
        }
    }

    Row(modifier = modifier.fillMaxWidth()) {
        // 3dp left accent bar
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(
                    if (isRunning && result == null)
                        VelaColors.Waiting.copy(alpha = 0.7f)
                    else
                        VelaColors.TextTertiary.copy(alpha = 0.35f)
                )
                .align(Alignment.Top),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {

            // ── Header row (always visible, tappable) ──────────────────────────────
            Row(
                modifier          = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector        = Icons.Default.AccountTree,
                    contentDescription = "Sub-agent",
                    tint               = if (isRunning && result == null)
                                             VelaColors.Waiting.copy(alpha = 0.8f)
                                         else VelaColors.TextTertiary,
                    modifier           = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text     = agentName,
                    style    = MaterialTheme.typography.labelMedium,
                    color    = if (isRunning && result == null) VelaColors.Waiting
                               else VelaColors.TextSecondary,
                    modifier = Modifier.weight(1f),
                )
                if (result != null) {
                    Icon(
                        imageVector        = Icons.Default.CheckCircle,
                        contentDescription = "Done",
                        tint               = VelaColors.Done,
                        modifier           = Modifier.size(13.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Icon(
                    imageVector        = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint               = VelaColors.TextTertiary,
                    modifier           = Modifier.size(14.dp),
                )
            }

            // ── Collapsed summary: latest activity snippet ─────────────────────────
            if (!expanded && !latestSummary.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text     = latestSummary,
                    style    = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color    = VelaColors.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // ── Expanded body ──────────────────────────────────────────────────────
            if (expanded) {
                // Instruction
                if (instruction.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text  = instruction,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = VelaColors.TextSecondary,
                    )
                }

                // Interleaved child content — text and tool calls in arrival order
                if (childBlocks.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        childBlocks.forEach { block ->
                            when (block) {
                                is ContentBlock.Text -> MarkdownText(
                                    markdown = block.markdown,
                                    color    = VelaColors.TextSecondary,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                is ContentBlock.ToolUse -> {
                                    val childResult = childBlocks
                                        .filterIsInstance<ContentBlock.ToolResult>()
                                        .find { it.toolUseId == block.id }
                                    CollapsibleToolCard(block = block, result = childResult)
                                }
                                is ContentBlock.ToolResult -> { /* rendered via matching ToolUse */ }
                                else -> { /* thinking/todo not expected in child content */ }
                            }
                        }
                    }
                }

                // Final result from transcript (shows after execution completes)
                if (result != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text  = result,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize   = 11.sp,
                        ),
                        color = VelaColors.TextSecondary,
                    )
                }
            }
        }
    }
}

/**
 * Tool-call card — nested inside agent turns.
 *
 * Design: DESIGN.md §7.3 — M3 Outlined card.
 * - Full-perimeter 1dp border (StrokeHair), 12dp radius, SurfaceRaised background
 * - Tool name in JetBrains Mono, TextSecondary
 * - Duration + ✓ when done (sage green)
 * - Spinner (14dp, amber, 1.5dp stroke) when running
 * - Result text in Mono, TextTertiary
 */
@Composable
fun ToolCallCard(
    call: ToolCall,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape    = RoundedCornerShape(12.dp),
        color    = VelaColors.SurfaceRaised,
        border   = BorderStroke(1.dp, VelaColors.StrokeHair),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(10.dp, 10.dp, 12.dp, 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text     = call.name,
                    style    = MonoMedium,
                    color    = VelaColors.TextSecondary,
                    modifier = Modifier.weight(1f),
                )
                if (call.isDone && call.durationMs != null) {
                    Text(
                        text  = "✓ ${call.durationMs}ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = VelaColors.Done,
                    )
                }
                if (call.isRunning) {
                    CircularProgressIndicator(
                        modifier    = Modifier.size(14.dp),
                        color       = VelaColors.Running,
                        strokeWidth = 1.5.dp,
                    )
                }
            }
            if (call.result != null) {
                Text(
                    text  = call.result,
                    style = MonoMedium,
                    color = VelaColors.TextTertiary,
                )
            }
        }
    }
}

/**
 * Pulsing three-dot typing indicator — shown while the agent is streaming.
 * Each dot fades in and out with a staggered 200ms delay, creating a wave effect.
 */
@Composable
fun TypingIndicator(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "typingIndicator")

    @Composable
    fun dot(delayMs: Int): Float {
        val alpha by infiniteTransition.animateFloat(
            initialValue  = 0.25f,
            targetValue   = 1f,
            animationSpec = infiniteRepeatable(
                animation  = androidx.compose.animation.core.keyframes {
                    durationMillis = 900
                    0.25f at 0        with androidx.compose.animation.core.LinearEasing
                    1.00f at 300      with androidx.compose.animation.core.LinearEasing
                    0.25f at 600      with androidx.compose.animation.core.LinearEasing
                    0.25f at 900      with androidx.compose.animation.core.LinearEasing
                },
                repeatMode    = androidx.compose.animation.core.RepeatMode.Restart,
                initialStartOffset = androidx.compose.animation.core.StartOffset(delayMs),
            ),
            label = "dot$delayMs",
        )
        return alpha
    }

    val a0 = dot(0)
    val a1 = dot(200)
    val a2 = dot(400)

    Row(
        modifier            = modifier.padding(start = 4.dp, top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment   = Alignment.CenterVertically,
    ) {
        listOf(a0, a1, a2).forEach { alpha ->
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(VelaColors.Accent.copy(alpha = alpha)),
            )
        }
    }
}
