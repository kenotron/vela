package com.vela.app.ui.profile

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val profileData  by viewModel.profileData.collectAsState()
    val hasVault     by viewModel.hasVault.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Profile") },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            if (hasVault) {
                FloatingActionButton(onClick = { viewModel.refresh() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh profile")
                }
            }
        },
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            if (isRefreshing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            when {
                !hasVault       -> EmptyState(
                    onNavigateToSettings = onNavigateToSettings,
                    modifier = Modifier.fillMaxSize(),
                )
                profileData == null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator()
                }
                else -> ProfileContent(
                    data     = profileData!!,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun ProfileContent(data: ProfileData, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier       = modifier,
        contentPadding = PaddingValues(bottom = 88.dp),
    ) {
        // Zone 1 — Identity card
        item {
            IdentityCard(data, modifier = Modifier.padding(16.dp))
        }

        // Zone 2 — Vela knows
        item {
            SectionHeader(
                title    = "Vela knows",
                subtitle = if (data.lastUpdated.isNotEmpty()) "updated ${data.lastUpdated}" else null,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (data.knowledgeBlocks.isEmpty()) {
            item {
                Text(
                    "No profile data yet — tap ↻ to generate",
                    style    = MaterialTheme.typography.bodySmall,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        } else {
            items(data.knowledgeBlocks) { block ->
                KnowledgeCard(
                    block    = block,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }

        // Zone 3 — Life pulse
        item {
            SectionHeader(
                title    = "Life pulse",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (data.pulseEntries.isEmpty()) {
            item {
                Text(
                    "No recent activity yet",
                    style    = MaterialTheme.typography.bodySmall,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        } else {
            items(data.pulseEntries) { entry ->
                PulseEntry(
                    entry    = entry,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun IdentityCard(data: ProfileData, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape    = MaterialTheme.shapes.extraLarge,
                    color    = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp),
                ) {
                    Box(Modifier.fillMaxSize(), Alignment.Center) {
                        if (data.name.isNotEmpty()) {
                            Text(
                                text       = data.name.first().uppercaseChar().toString(),
                                style      = MaterialTheme.typography.titleLarge,
                                color      = MaterialTheme.colorScheme.onPrimary,
                                fontWeight = FontWeight.Bold,
                            )
                        } else {
                            Icon(
                                imageVector        = Icons.Default.Person,
                                contentDescription = null,
                                tint               = MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text  = data.name.ifEmpty { "Your name" },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    if (data.role.isNotEmpty()) {
                        Text(
                            text  = data.role,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                        )
                    }
                }
            }
            val tags = (data.keyProjects + data.interests).take(8)
            if (tags.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier              = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    tags.forEach { tag ->
                        SuggestionChip(
                            onClick = {},
                            label   = { Text(tag, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun KnowledgeCard(block: KnowledgeBlock, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(
                        text     = block.vaultName,
                        style    = MaterialTheme.typography.labelSmall,
                        color    = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
                if (block.updatedDate.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text  = block.updatedDate,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(block.content, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun PulseEntry(entry: String, modifier: Modifier = Modifier) {
    val icon = if (entry.contains("Session", ignoreCase = true)) "💬" else "📄"
    Row(modifier = modifier, verticalAlignment = Alignment.Top) {
        Text(
            text     = icon,
            style    = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(28.dp),
        )
        Text(
            text     = entry.removePrefix("- ").trim(),
            style    = MaterialTheme.typography.bodySmall,
            color    = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
    HorizontalDivider(
        modifier  = Modifier.padding(vertical = 4.dp),
        thickness = 0.5.dp,
        color     = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
    )
}

@Composable
private fun SectionHeader(
    title    : String,
    subtitle : String? = null,
    modifier : Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text       = title,
            style      = MaterialTheme.typography.labelMedium,
            color      = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        )
        if (subtitle != null) {
            Text(
                text  = " · $subtitle",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun EmptyState(
    onNavigateToSettings : () -> Unit,
    modifier             : Modifier = Modifier,
) {
    Column(
        modifier                  = modifier.padding(32.dp),
        verticalArrangement       = Arrangement.Center,
        horizontalAlignment       = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector        = Icons.Default.Person,
            contentDescription = null,
            tint               = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier           = Modifier.size(64.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text("Add a vault to see your profile", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            text  = "Vela will build a living portrait of who you are from your vault content.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onNavigateToSettings) { Text("Add a vault") }
    }
}
