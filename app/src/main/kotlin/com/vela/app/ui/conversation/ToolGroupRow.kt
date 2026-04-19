package com.vela.app.ui.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vela.app.data.db.TurnEventEntity

@Composable
internal fun ToolGroupRow(events: List<TurnEventEntity>) {
    if (events.isEmpty()) return

    // Special rendering for todo tool — show a checklist card instead of generic tool row
    val isTodoGroup = events.all { it.toolName == "todo" }
    if (isTodoGroup) {
        val lastTodoUpdate = events.lastOrNull { it.toolArgs != null && run {
            try { org.json.JSONObject(it.toolArgs).optString("action").let { a -> a == "create" || a == "update" } }
            catch (_: Exception) { false }
        }}
        if (lastTodoUpdate != null) {
            TodoChecklistRow(event = lastTodoUpdate)
            return
        }
    }

    var expanded by remember { mutableStateOf(false) }
    val last      = events.last()
    val isRunning = last.toolStatus == null || last.toolStatus == "running"
    val isError   = events.any { it.toolStatus == "error" }

    val borderColor = when {
        isError   -> MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
        isRunning -> MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
        else      -> MaterialTheme.colorScheme.outlineVariant
    }
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier = Modifier.padding(start = 16.dp, end = 32.dp)) {
        // Left-border strip + single summary row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    enabled = events.size > 1,
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                ) { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Left border
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(IntrinsicSize.Min)
                    .defaultMinSize(minHeight = 20.dp)
                    .background(borderColor, shape = RoundedCornerShape(1.dp))
            )
            Spacer(Modifier.width(8.dp))
            // Icon + label
            Text(
                text     = (last.toolIcon ?: "\uD83D\uDD27") + "  " + smartLabel(last),
                style    = MaterialTheme.typography.labelSmall,
                color    = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (events.size > 1) {
                Spacer(Modifier.width(4.dp))
                Text(
                    text  = if (expanded) "\u25b2" else "+${events.size - 1}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }

        // Expanded: show all events
        if (expanded && events.size > 1) {
            Spacer(Modifier.height(2.dp))
            events.forEach { event ->
                Row(
                    modifier = Modifier.padding(start = 10.dp, top = 1.dp, bottom = 1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(IntrinsicSize.Min)
                            .defaultMinSize(minHeight = 16.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    )
                    Spacer(Modifier.width(8.dp))
                    val doneAlpha = if (event.toolStatus == "done") 0.6f else 1f
                    Text(
                        text     = (event.toolIcon ?: "\uD83D\uDD27") + "  " + smartLabel(event),
                        style    = MaterialTheme.typography.labelSmall,
                        color    = textColor.copy(alpha = doneAlpha),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

internal fun smartLabel(event: TurnEventEntity): String {
    val s = event.toolSummary
    return when (event.toolName) {
        "read_file"   -> if (!s.isNullOrBlank()) "Reading $s"      else "Reading file"
        "write_file"  -> if (!s.isNullOrBlank()) "Writing $s"      else "Writing file"
        "edit_file"   -> if (!s.isNullOrBlank()) "Editing $s"      else "Editing file"
        "glob"        -> if (!s.isNullOrBlank()) "Finding $s"      else "Finding files"
        "grep"        -> if (!s.isNullOrBlank()) "Searching: $s"   else "Searching content"
        "bash"        -> if (!s.isNullOrBlank()) "$ $s"            else "Running command"
        "search_web"  -> if (!s.isNullOrBlank()) "Web: $s"         else "Web search"
        "fetch_url"   -> if (!s.isNullOrBlank()) "Fetching $s"     else "Fetching URL"
        "todo"        -> if (!s.isNullOrBlank()) "Todos: $s"       else "Updating todos"
        "load_skill"  -> if (!s.isNullOrBlank()) "Skill: $s"       else "Loading skill"
        else          -> buildString {
            append(event.toolDisplayName ?: event.toolName ?: "Tool")
            if (!s.isNullOrBlank()) { append(": "); append(s) }
        }
    }
}
