package com.vela.app.ui.conversation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vela.app.domain.model.Conversation
import java.text.SimpleDateFormat
import java.util.*

/**
 * Full-screen chat search / session picker.
 *
 * Opened from the Search icon in [VelaDrawerContent]. Shows a live-filtered
 * list of all conversations with delete support.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatSearchScreen(
    viewModel: ConversationViewModel,
    onBack: () -> Unit,
    onSelect: () -> Unit,
) {
    val conversations by viewModel.conversations.collectAsState()
    val activeId      by viewModel.activeConversationId.collectAsState()
    var query         by remember { mutableStateOf("") }

    val filtered = remember(conversations, query) {
        if (query.isBlank()) conversations
        else conversations.filter { it.title.contains(query, ignoreCase = true) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("Search chats", style = MaterialTheme.typography.titleLarge) },
            )
        },
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad),
        ) {
            // ── Search field ─────────────────────────────────────────────
            OutlinedTextField(
                value         = query,
                onValueChange = { query = it },
                modifier      = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder   = { Text("Search chats…") },
                leadingIcon   = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine    = true,
                shape         = RoundedCornerShape(28.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )

            // ── Results ──────────────────────────────────────────────────
            if (filtered.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text  = if (query.isBlank()) "No chats yet — start one!" else "No results for \"$query\"",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    contentPadding      = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(filtered, key = { it.id }) { conv ->
                        SearchSessionCard(
                            conv     = conv,
                            isActive = conv.id == activeId,
                            onClick  = { viewModel.switchSession(conv.id); onSelect() },
                            onDelete = { viewModel.deleteSession(conv.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchSessionCard(
    conv: Conversation,
    isActive: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val cs      = MaterialTheme.colorScheme
    val dateStr = remember(conv.updatedAt) {
        val d = System.currentTimeMillis() - conv.updatedAt
        when {
            d < 60_000L         -> "Just now"
            d < 3_600_000L      -> "${d / 60_000}m ago"
            d < 86_400_000L     -> "${d / 3_600_000}h ago"
            d < 7 * 86_400_000L -> "${d / 86_400_000}d ago"
            else                -> SimpleDateFormat("MMM d", Locale.US).format(Date(conv.updatedAt))
        }
    }

    Card(
        onClick  = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(14.dp),
        colors   = CardDefaults.cardColors(
            containerColor = if (isActive) cs.primaryContainer.copy(alpha = 0.55f)
                             else cs.surfaceContainerLow,
        ),
        border = if (isActive) BorderStroke(1.dp, cs.primary.copy(alpha = 0.4f)) else null,
    ) {
        Row(
            modifier             = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment    = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier         = Modifier
                    .size(42.dp)
                    .background(
                        if (isActive) cs.primary.copy(alpha = 0.15f)
                        else cs.surfaceContainerHigh,
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector        = Icons.Default.ChatBubbleOutline,
                    contentDescription = null,
                    tint               = if (isActive) cs.primary else cs.onSurfaceVariant,
                    modifier           = Modifier.size(20.dp),
                )
            }

            Column(Modifier.weight(1f)) {
                Text(
                    text       = conv.title,
                    style      = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                    color      = if (isActive) cs.primary else cs.onSurface,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis,
                )
                Text(
                    text  = dateStr,
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
            }

            IconButton(
                onClick  = onDelete,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector        = Icons.Default.Delete,
                    contentDescription = "Delete chat",
                    tint               = cs.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier           = Modifier.size(18.dp),
                )
            }
        }
    }
}
