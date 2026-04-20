package com.vela.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vela.app.domain.model.Conversation

/** Navigation destinations driven by the drawer. */
internal enum class DrawerDestination {
    CHAT, CHAT_SEARCH, VAULT, CONNECTORS, PROFILE,
}

/**
 * Content of the left-side navigation drawer.
 *
 *  ┌──────────────────────────────┐
 *  │  Vela                [🔍][+] │
 *  │  Vaults                      │
 *  │  Connectors                  │
 *  │  ──────────────────────────  │
 *  │  Recents                     │
 *  │    chat 1                    │
 *  │    chat 2   …(scrollable)    │
 *  │  ──────────────────────────  │
 *  │  (●) Ken Chau                │
 *  └──────────────────────────────┘
 */
@Composable
internal fun VelaDrawerContent(
    conversations:        List<Conversation>,
    activeConversationId: String?,
    currentDestination:   DrawerDestination,
    userName:             String,
    onNewChat:            () -> Unit,
    onSearch:             () -> Unit,
    onVaults:             () -> Unit,
    onConnectors:         () -> Unit,
    onSelectConversation: (String) -> Unit,
    onProfile:            () -> Unit,
    modifier:             Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme

    Column(modifier = modifier.fillMaxHeight()) {

        // ── Header: brand + icon actions ─────────────────────────────────
        Spacer(Modifier.height(20.dp))
        Row(
            modifier              = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment     = Alignment.CenterVertically,
        ) {
            Text(
                text      = "Vela",
                style     = MaterialTheme.typography.titleLarge,
                fontWeight= FontWeight.Bold,
                color     = cs.onSurface,
                modifier  = Modifier.weight(1f),
            )
            IconButton(onClick = onSearch) {
                Icon(Icons.Default.Search, contentDescription = "Search chats", tint = cs.onSurfaceVariant)
            }
            IconButton(onClick = onNewChat) {
                Icon(Icons.Default.Add, contentDescription = "New chat", tint = cs.primary)
            }
        }

        Spacer(Modifier.height(8.dp))

        // ── Top nav: Vaults & Connectors ─────────────────────────────────
        NavigationDrawerItem(
            icon     = { Icon(Icons.Default.Folder, contentDescription = null) },
            label    = { Text("Vaults") },
            selected = currentDestination == DrawerDestination.VAULT,
            onClick  = onVaults,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        NavigationDrawerItem(
            icon     = { Icon(Icons.Default.Hub, contentDescription = null) },
            label    = { Text("Connectors") },
            selected = currentDestination == DrawerDestination.CONNECTORS,
            onClick  = onConnectors,
            modifier = Modifier.padding(horizontal = 12.dp),
        )

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            color    = cs.outlineVariant.copy(alpha = 0.5f),
        )

        // ── Chat list ─────────────────────────────────────────────────────
        val recents = conversations.sortedByDescending { it.updatedAt }

        LazyColumn(
            modifier       = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
        ) {
            if (recents.isNotEmpty()) {
                item {
                    Text(
                        text     = "Recents",
                        style    = MaterialTheme.typography.labelMedium,
                        color    = cs.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                items(recents, key = { it.id }) { conv ->
                    CompactConversationRow(
                        conv     = conv,
                        isActive = conv.id == activeConversationId,
                        onClick  = { onSelectConversation(conv.id) },
                    )
                }
            } else {
                item {
                    Box(
                        modifier         = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text  = "No chats yet — tap + to start",
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // ── Bottom: profile row (fixed) ───────────────────────────────────
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp),
            color    = cs.outlineVariant.copy(alpha = 0.5f),
        )
        NavigationDrawerItem(
            icon = {
                val initial = userName.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
                Box(
                    modifier         = Modifier.size(28.dp).background(cs.primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text  = initial,
                        style = MaterialTheme.typography.labelMedium,
                        color = cs.onPrimary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            },
            label    = {
                Text(
                    text     = userName.ifBlank { "Profile" },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            selected = currentDestination == DrawerDestination.PROFILE,
            onClick  = onProfile,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
        Spacer(Modifier.height(12.dp))
    }
}

// ── Compact conversation row (no icon, just title) ────────────────────────────

@Composable
private fun CompactConversationRow(
    conv:     Conversation,
    isActive: Boolean,
    onClick:  () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick           = onClick,
        color             = if (isActive) cs.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent,
        shape             = RoundedCornerShape(8.dp),
        modifier          = Modifier.fillMaxWidth(),
    ) {
        Text(
            text     = conv.title,
            style    = MaterialTheme.typography.bodyMedium,
            color    = if (isActive) cs.onSecondaryContainer else cs.onSurface.copy(alpha = 0.85f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}
