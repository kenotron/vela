package com.vela.app.ui.coordinator

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vela.app.ui.theme.MonoMedium
import com.vela.app.ui.theme.VelaColors

@Composable
internal fun BranchCard(
    branch: CoordinatorViewModel.BranchState,
    onViewSession: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(cardBackground(branch))
            .border(
                width  = 1.dp,
                color  = VelaColors.AccentCoord.copy(alpha = 0.14f),
                shape  = shape,
            )
            .padding(start = 18.dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
    ) {
        Column {
            // Node name row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier          = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text       = "\uD83D\uDDA5 ${branch.nodeName}",
                    style      = MonoMedium.copy(fontSize = 12.sp),
                    fontWeight = FontWeight.SemiBold,
                    color      = VelaColors.TextPrimary,
                    modifier   = Modifier.weight(1f),
                )
                if (branch.sessionId != null) {
                    Text(
                        text     = "view session →",
                        color    = VelaColors.AccentCoord,
                        fontSize = 10.sp,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .clickable { onViewSession(branch.sessionId) },
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Steps
            branch.steps.forEach { step ->
                BranchStepRow(step)
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun BranchStepRow(step: CoordinatorViewModel.BranchStep) {
    val (prefix, color) = when (step.status) {
        CoordinatorViewModel.StepStatus.DONE    -> "✓" to VelaColors.TextSecondary
        CoordinatorViewModel.StepStatus.RUNNING -> "⟳" to VelaColors.Running
        CoordinatorViewModel.StepStatus.WAITING -> "○" to VelaColors.TextTertiary
    }
    Text(
        text     = "$prefix  ${step.description}",
        color    = color,
        fontSize = 10.5.sp,
    )
}

/**
 * Tonal fill for the branch card based on the branch's active status.
 *
 * Running: color-mix(RunningContainer 30%, CoordCard) — warm amber wash
 * Done:    color-mix(DoneContainer 25%, CoordCard)    — cool sage wash
 * Waiting: CoordCard unchanged
 */
internal fun cardBackground(branch: CoordinatorViewModel.BranchState): Color {
    val steps = branch.steps
    return when {
        steps.any { it.status == CoordinatorViewModel.StepStatus.RUNNING } ->
            Color(0xFF1E1508) // amber-tinted coordinator card
        steps.all { it.status == CoordinatorViewModel.StepStatus.DONE } ->
            Color(0xFF152120) // sage-tinted coordinator card
        else -> VelaColors.CoordCard
    }
}
