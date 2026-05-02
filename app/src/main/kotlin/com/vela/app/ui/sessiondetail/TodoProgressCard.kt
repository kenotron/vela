package com.vela.app.ui.sessiondetail

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vela.app.ui.theme.VelaColors

private val VelaPurple      = Color(0xFF6750A4)
private val VelaDarkSurface = Color(0xFF252438)
private val VelaMutedPurple = Color(0xFF9A86D2)

@Composable
fun TodoProgressCard(
    todos: List<TodoItem>,
    isStreaming: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "todoPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.45f,
        targetValue  = 1f,
        animationSpec = infiniteRepeatable(
            animation  = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "todoPulse",
    )

    Surface(
        color    = VelaDarkSurface,
        shape    = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier            = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text  = "TASK PLAN",
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp),
                color = VelaMutedPurple,
            )

            todos.forEach { item ->
                Row(
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    when (item.status) {
                        TodoStatus.COMPLETED -> {
                            Icon(
                                imageVector        = Icons.Default.Check,
                                contentDescription = "Done",
                                tint               = VelaPurple,
                                modifier           = Modifier.size(14.dp),
                            )
                            Text(
                                text     = item.content,
                                style    = MaterialTheme.typography.bodySmall.copy(
                                    textDecoration = TextDecoration.LineThrough,
                                ),
                                color    = VelaColors.TextSecondary,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        TodoStatus.IN_PROGRESS -> {
                            val dotAlpha = if (isStreaming) pulseAlpha else 1f
                            Box(
                                modifier = Modifier
                                    .size(14.dp)
                                    .clip(CircleShape)
                                    .background(VelaPurple.copy(alpha = dotAlpha)),
                            )
                            Text(
                                text     = item.content,
                                style    = MaterialTheme.typography.bodySmall.copy(
                                    fontWeight = FontWeight.Bold,
                                ),
                                color    = Color.White,
                                modifier = Modifier.weight(1f),
                            )
                            Surface(
                                color  = VelaPurple,
                                shape  = RoundedCornerShape(6.dp),
                            ) {
                                Text(
                                    text     = "NOW",
                                    style    = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                    color    = Color.White,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                )
                            }
                        }
                        TodoStatus.PENDING -> {
                            Surface(
                                modifier = Modifier.size(14.dp),
                                shape    = CircleShape,
                                color    = Color.Transparent,
                                border   = BorderStroke(1.dp, VelaColors.TextTertiary),
                            ) {}
                            Text(
                                text     = item.content,
                                style    = MaterialTheme.typography.bodySmall,
                                color    = VelaColors.TextTertiary,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}
