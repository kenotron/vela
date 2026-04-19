package com.vela.app.ui.conversation

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vela.app.data.db.TurnEventEntity

internal data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

@Composable
internal fun TodoChecklistRow(event: TurnEventEntity) {
    val cs        = MaterialTheme.colorScheme
    val isRunning = event.toolStatus == null || event.toolStatus == "running"

    data class TodoItem(val content: String, val status: String, val activeForm: String)

    val items: List<TodoItem> = remember(event.toolArgs) {
        try {
            val obj      = org.json.JSONObject(event.toolArgs ?: return@remember emptyList())
            val todosStr = obj.optString("todos").takeIf { it.isNotBlank() } ?: return@remember emptyList()
            val arr      = org.json.JSONArray(todosStr)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                TodoItem(
                    content    = o.optString("content"),
                    status     = o.optString("status", "pending"),
                    activeForm = o.optString("activeForm"),
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    if (items.isEmpty()) return

    val borderColor = if (isRunning) cs.primary.copy(alpha = 0.4f) else cs.outlineVariant

    Column(modifier = Modifier.padding(start = 16.dp, end = 32.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(IntrinsicSize.Min)
                    .defaultMinSize(minHeight = 20.dp)
                    .background(borderColor, RoundedCornerShape(1.dp))
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text  = "\u2705  Tasks",
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
            )
            if (isRunning) {
                Spacer(Modifier.width(6.dp))
                val inf   = rememberInfiniteTransition(label = "todo_pulse")
                val alpha by inf.animateFloat(0.3f, 1f,
                    infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "a")
                Box(Modifier.size(5.dp).alpha(alpha)
                    .background(cs.primary, CircleShape))
            }
        }

        Spacer(Modifier.height(4.dp))

        items.forEach { item ->
            val isComplete   = item.status == "completed"
            val isInProgress = item.status == "in_progress"
            val displayText  = if (isInProgress && item.activeForm.isNotBlank()) item.activeForm else item.content

            val (icon, iconTint, textColor, decoration) = when {
                isComplete   -> Quadruple("\u2713", cs.primary.copy(alpha = 0.5f),   cs.onSurface.copy(alpha = 0.4f), TextDecoration.LineThrough)
                isInProgress -> Quadruple("\u2192", cs.primary,                       cs.onSurface,                    TextDecoration.None)
                else         -> Quadruple("\u25cb", cs.onSurfaceVariant.copy(0.5f),   cs.onSurface.copy(alpha = 0.7f), TextDecoration.None)
            }

            Row(
                modifier = Modifier.padding(start = 10.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(icon,  style = MaterialTheme.typography.labelSmall.copy(fontWeight = if (isInProgress) FontWeight.SemiBold else FontWeight.Normal), color = iconTint, modifier = Modifier.width(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    text           = displayText,
                    style          = MaterialTheme.typography.labelSmall.copy(textDecoration = decoration),
                    color          = textColor,
                    maxLines       = 2,
                    overflow       = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
