package com.vela.app.ui.sessiondetail

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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
            if (content.contentBlocks.isNotEmpty()) {
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
                            ToolCallBlock(
                                name      = block.name,
                                inputJson = block.inputJson,
                                result    = result?.output,
                                isError   = result?.isError ?: false,
                            )
                        }
                        is ContentBlock.ToolResult -> { /* rendered via matching ToolUse block */ }
                    }
                }
            } else {
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

/**
 * Collapsible "Reasoning" block for assistant thinking content.
 */
@Composable
fun ThinkingBlock(text: String) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(VelaColors.SurfaceSub)
            .clickable { expanded = !expanded }
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector        = Icons.Default.Psychology,
                contentDescription = "Reasoning",
                tint               = VelaColors.Accent,
                modifier           = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text("Reasoning", style = MaterialTheme.typography.labelSmall, color = VelaColors.Accent)
            Spacer(Modifier.weight(1f))
            Icon(
                imageVector        = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint               = VelaColors.TextTertiary,
                modifier           = Modifier.size(16.dp),
            )
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            Text(
                text      = text,
                style     = MaterialTheme.typography.bodySmall,
                color     = VelaColors.TextSecondary,
                fontStyle = FontStyle.Italic,
            )
        }
    }
}

/**
 * Collapsible tool-call card with input JSON and optional result.
 */
@Composable
fun ToolCallBlock(
    name: String,
    inputJson: String,
    result: String? = null,
    isError: Boolean = false,
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
            if (result != null) {
                Icon(
                    imageVector        = if (isError) Icons.Default.Error else Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint               = if (isError) VelaColors.Error else VelaColors.Done,
                    modifier           = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
            }
            Icon(
                imageVector        = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint               = VelaColors.TextTertiary,
                modifier           = Modifier.size(16.dp),
            )
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
