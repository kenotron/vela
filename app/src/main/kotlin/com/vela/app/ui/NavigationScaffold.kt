package com.vela.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel

private enum class AppDestination(
    val label: String,
    val icon: ImageVector,
    val contentDescription: String,
) {
    PROJECTS(
        label = "Projects",
        icon = Icons.Default.ChatBubbleOutline,
        contentDescription = "Projects — chat sessions",
    ),
    VAULT(
        label = "Vault",
        icon = Icons.Default.Folder,
        contentDescription = "Vault — files and mini apps",
    ),
    CONNECTORS(
        label = "Connectors",
        icon = Icons.Default.Hub,
        contentDescription = "Connectors — SSH nodes and services",
    ),
    PROFILE(
        label = "Profile",
        icon = Icons.Default.Person,
        contentDescription = "Profile — settings",
    ),
}

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun NavigationScaffold(
    windowSizeClass: WindowSizeClass,
    speechTranscriber: com.vela.app.voice.SpeechTranscriber? = null,
    modifier: Modifier = Modifier,
) {
    val navViewModel: NavigationViewModel = hiltViewModel()
    var currentDestination by remember { mutableStateOf(AppDestination.PROJECTS) }

    // Emit system events whenever theme or layout changes
    val isDark = isSystemInDarkTheme()
    val layoutMode = if (windowSizeClass.widthSizeClass == WindowWidthSizeClass.Compact) "phone" else "tablet"

    LaunchedEffect(isDark) {
        navViewModel.eventBus.tryPublish("vela:theme-changed", """{"isDark":$isDark}""")
    }
    LaunchedEffect(layoutMode) {
        navViewModel.eventBus.tryPublish("vela:layout-changed", """{"layout":"$layoutMode"}""")
    }

    // Back gesture on phone navigates to PROJECTS before exiting
    BackHandler(enabled = currentDestination != AppDestination.PROJECTS) {
        currentDestination = AppDestination.PROJECTS
    }

    when (windowSizeClass.widthSizeClass) {
        WindowWidthSizeClass.Compact -> {
            Scaffold(
                modifier = modifier,
                bottomBar = {
                    NavigationBar {
                        AppDestination.entries.forEach { dest ->
                            NavigationBarItem(
                                selected = currentDestination == dest,
                                onClick = { currentDestination = dest },
                                icon = { Icon(dest.icon, contentDescription = dest.contentDescription) },
                                label = { Text(dest.label) },
                            )
                        }
                    }
                },
            ) { innerPadding ->
                DestinationContent(
                    destination = currentDestination,
                    speechTranscriber = speechTranscriber,
                    windowSizeClass = windowSizeClass,
                    modifier = Modifier.padding(innerPadding),
                )
            }
        }
        else -> {
            Row(modifier = modifier.fillMaxSize()) {
                NavigationRail {
                    Spacer(Modifier.weight(1f))
                    AppDestination.entries.forEach { dest ->
                        NavigationRailItem(
                            selected = currentDestination == dest,
                            onClick = { currentDestination = dest },
                            icon = { Icon(dest.icon, contentDescription = dest.contentDescription) },
                            label = { Text(dest.label) },
                        )
                    }
                    Spacer(Modifier.weight(1f))
                }
                DestinationContent(
                    destination = currentDestination,
                    speechTranscriber = speechTranscriber,
                    windowSizeClass = windowSizeClass,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
private fun DestinationContent(
    destination: AppDestination,
    speechTranscriber: com.vela.app.voice.SpeechTranscriber?,
    windowSizeClass: WindowSizeClass,
    modifier: Modifier = Modifier,
) {
    when (destination) {
        AppDestination.PROJECTS -> com.vela.app.ui.conversation.ConversationRoot(
            speechTranscriber = speechTranscriber,
            modifier = modifier,
        )
        AppDestination.VAULT -> com.vela.app.ui.vault.VaultBrowserScreen(
            windowSizeClass = windowSizeClass,
            modifier = modifier,
        )
        AppDestination.CONNECTORS -> com.vela.app.ui.connectors.ConnectorsScreen(
            modifier = modifier,
        )
        AppDestination.PROFILE -> com.vela.app.ui.settings.SettingsScreen(
            onNavigateBack = {},
            onNavigateToAi = {},
            onNavigateToConnections = {},
            onNavigateToVaults = {},
            onNavigateToRecording = {},
            onNavigateToGitHub = {},
            modifier = modifier,
        )
    }
}
