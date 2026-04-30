package com.vela.app.ui.sessiondetail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
            Text(
                text  = content.text,
                style = MaterialTheme.typography.bodyLarge,
                color = VelaColors.TextPrimary,
            )
            content.toolCalls.forEach { ToolCallCard(it) }
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
