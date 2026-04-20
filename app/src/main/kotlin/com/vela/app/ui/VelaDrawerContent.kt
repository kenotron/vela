package com.vela.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubbleOutline
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
 * Layout (top → bottom):
 *  ┌─────────────────────────────────┐
 *  │  Vela               [🔍] [✏️]  │
 *  │  Vaults                         │
 *  │  Connectors                     │
 *  │  ─────────────────────────────  │
 *  │  Recents                        │
 *  │    chat 1                       │
 *  │    chat 2  …(scrollable)        │
 *  │  ─────────────────────────────  │
 *  │  (👤) Profile                   │
 *  └─────────────────────────────────┘
 */
@Composable
internal fun VelaDrawerContent(
    conversations: List<Conversation>,
    activeConversationId: String?,
    currentDestination: DrawerDestination,
    onNewChat: () -> Unit,
    onSearch: () -> Unit,
    onVaults: () -> Unit,
    onConnectors: () -> Unit,
    onSelectConversation: (String) -> Unit,
    onProfile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme

    Column(
        modifier = modifier.fillMaxHeight(),
    ) {

        // ── Header row: brand + action icons ─────────────────────────────
        Spacer(Modifier.height(20.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Vela",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = cs.onSurface,
                modifier = Modifier.weight(1f),
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
            icon = { Icon(Icons.Default.Folder, contentDescription = null) },
            label = { Text("Vaults") },
            selected = currentDestination == DrawerDestination.VAULT,
            onClick = onVaults,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        NavigationDrawerItem(
            icon = { Icon(Icons.Default.Hub, contentDescription = null) },
            label = { Text("Connectors") },
            selected = currentDestination == DrawerDestination.CONNECTORS,
            onClick = onConnectors,
            modifier = Modifier.padding(horizontal = 12.dp),
        )

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            color = cs.outlineVariant.copy(alpha = 0.5f),
        )

        // ── Conversation list (scrollable, fills remaining space) ─────────
        val pinned  = emptyList<Conversation>()   // future: filter by conv.isPinned
        val recents = conversations.sortedByDescending { it.updatedAt }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 12.dp),
        ) {
            if (pinned.isNotEmpty()) {
                item { DrawerSectionLabel("Pinned") }
                items(pinned, key = { "p_${it.id}" }) { conv ->
                    DrawerConversationItem(
                        conv     = conv,
                        isActive = conv.id == activeConversationId,
                        onClick  = { onSelectConversation(conv.id) },
                    )
                }
                item { Spacer(Modifier.height(4.dp)) }
            }

            if (recents.isNotEmpty()) {
                item { DrawerSectionLabel("Recents") }
                items(recents, key = { "r_${it.id}" }) { conv ->
                    DrawerConversationItem(
                        conv     = conv,
                        isActive = conv.id == activeConversationId,
                        onClick  = { onSelectConversation(conv.id) },
                    )
                }
            }

            if (conversations.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
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
            color = cs.outlineVariant.copy(alpha = 0.5f),
        )
        NavigationDrawerItem(
            icon = {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(cs.primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector        = Icons.Default.Person,
                        contentDescription = null,
                        tint               = cs.onPrimary,
                        modifier           = Modifier.size(16.dp),
                    )
                }
            },
            label = { Text("Profile", maxLines = 1) },
            selected = currentDestination == DrawerDestination.PROFILE,
            onClick = onProfile,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
        Spacer(Modifier.height(12.dp))
    }
}

// ── Private helpers ───────────────────────────────────────────────────────────

@Composable
private fun DrawerSectionLabel(text: String) {
    Text(
        text     = text,
        style    = MaterialTheme.typography.labelMedium,
        color    = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 2.dp),
    )
}

@Composable
private fun DrawerConversationItem(
    conv: Conversation,
    isActive: Boolean,
    onClick: () -> Unit,
) {
    NavigationDrawerItem(
        icon = {
            Icon(
                imageVector        = Icons.Default.ChatBubbleOutline,
                contentDescription = null,
                modifier           = Modifier.size(18.dp),
            )
        },
        label = {
            Text(
                text     = conv.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style    = MaterialTheme.typography.bodyMedium,
            )
        },
        selected = isActive,
        onClick  = onClick,
    )
}
