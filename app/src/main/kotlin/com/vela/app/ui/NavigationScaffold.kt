package com.vela.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.vela.app.ui.conversation.ChatSearchScreen
import com.vela.app.ui.conversation.ConversationViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class, ExperimentalMaterial3Api::class)
@Composable
fun NavigationScaffold(
    windowSizeClass: WindowSizeClass,
    speechTranscriber: com.vela.app.voice.SpeechTranscriber? = null,
    modifier: Modifier = Modifier,
) {
    val scope          = rememberCoroutineScope()
    val drawerState    = rememberDrawerState(DrawerValue.Closed)
    val navViewModel   = hiltViewModel<NavigationViewModel>()
    val convViewModel  = hiltViewModel<ConversationViewModel>()

    var currentDest by remember { mutableStateOf(DrawerDestination.CHAT) }

    // Emit system events whenever theme or layout changes
    val isDark     = isSystemInDarkTheme()
    val layoutMode = "phone"   // simplified — extend for tablets later

    LaunchedEffect(isDark) {
        navViewModel.eventBus.tryPublish("vela:theme-changed", """{"isDark":$isDark}""")
    }
    LaunchedEffect(layoutMode) {
        navViewModel.eventBus.tryPublish("vela:layout-changed", """{"layout":"$layoutMode"}""")
    }

    // Close drawer on back press when it's open; navigate to CHAT otherwise
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }
    BackHandler(enabled = !drawerState.isOpen && currentDest != DrawerDestination.CHAT) {
        currentDest = DrawerDestination.CHAT
    }

    val conversations       by convViewModel.conversations.collectAsState()
    val activeConvId        by convViewModel.activeConversationId.collectAsState()

    fun openDrawer() = scope.launch { drawerState.open() }
    fun closeDrawer() = scope.launch { drawerState.close() }

    ModalNavigationDrawer(
        drawerState   = drawerState,
        gesturesEnabled = true,
        modifier      = modifier,
        drawerContent = {
            ModalDrawerSheet {
                VelaDrawerContent(
                    conversations         = conversations,
                    activeConversationId  = activeConvId,
                    currentDestination    = currentDest,
                    onNewChat             = {
                        convViewModel.newSession()
                        currentDest = DrawerDestination.CHAT
                        closeDrawer()
                    },
                    onSearch              = {
                        currentDest = DrawerDestination.CHAT_SEARCH
                        closeDrawer()
                    },
                    onVaults              = {
                        currentDest = DrawerDestination.VAULT
                        closeDrawer()
                    },
                    onConnectors          = {
                        currentDest = DrawerDestination.CONNECTORS
                        closeDrawer()
                    },
                    onSelectConversation  = { id ->
                        convViewModel.switchSession(id)
                        currentDest = DrawerDestination.CHAT
                        closeDrawer()
                    },
                    onProfile             = {
                        currentDest = DrawerDestination.PROFILE
                        closeDrawer()
                    },
                )
            }
        },
    ) {
        // ── Main content area ─────────────────────────────────────────────
        MainContent(
            currentDest       = currentDest,
            windowSizeClass   = windowSizeClass,
            speechTranscriber = speechTranscriber,
            convViewModel     = convViewModel,
            onOpenDrawer      = { openDrawer() },
            onNavigateBack    = { currentDest = DrawerDestination.CHAT },
            onNavigateTo      = { currentDest = it },
            modifier          = Modifier.fillMaxSize(),
        )
    }
}

// ── Main content switcher ─────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
private fun MainContent(
    currentDest:       DrawerDestination,
    windowSizeClass:   WindowSizeClass,
    speechTranscriber: com.vela.app.voice.SpeechTranscriber?,
    convViewModel:     ConversationViewModel,
    onOpenDrawer:      () -> Unit,
    onNavigateBack:    () -> Unit,
    onNavigateTo:      (DrawerDestination) -> Unit,
    modifier:          Modifier = Modifier,
) {
    when (currentDest) {

        DrawerDestination.CHAT -> {
            var showRecording by remember { mutableStateOf(false) }
            if (showRecording) {
                BackHandler { showRecording = false }
                com.vela.app.ui.recording.RecordingScreen(
                    onNavigateBack    = { showRecording = false },
                    onTranscriptReady = { transcript ->
                        convViewModel.setPendingTranscript(transcript)
                        showRecording = false
                    },
                )
            } else {
                com.vela.app.ui.conversation.ConversationRoot(
                    speechTranscriber = speechTranscriber,
                    viewModel         = convViewModel,
                    onOpenDrawer      = onOpenDrawer,
                    onRecord          = { showRecording = true },
                    modifier          = modifier,
                )
            }
        }

        DrawerDestination.CHAT_SEARCH -> {
            ChatSearchScreen(
                viewModel = convViewModel,
                onBack    = onNavigateBack,
                onSelect  = onNavigateBack,   // go back to CHAT after picking
            )
        }

        DrawerDestination.VAULT -> com.vela.app.ui.vault.VaultBrowserScreen(
            windowSizeClass = windowSizeClass,
            modifier        = modifier,
        )

        DrawerDestination.CONNECTORS -> com.vela.app.ui.connectors.ConnectorsScreen(
            modifier = modifier,
        )

        DrawerDestination.PROFILE -> {
            var profilePage by remember { mutableStateOf(ProfilePage.PROFILE) }
            when (profilePage) {
                ProfilePage.PROFILE -> com.vela.app.ui.profile.ProfileScreen(
                    onNavigateToSettings = { profilePage = ProfilePage.SETTINGS },
                    modifier             = modifier,
                )
                ProfilePage.SETTINGS -> {
                    BackHandler { profilePage = ProfilePage.PROFILE }
                    com.vela.app.ui.settings.SettingsScreen(
                        onNavigateBack        = { profilePage = ProfilePage.PROFILE },
                        onNavigateToAi        = { profilePage = ProfilePage.AI },
                        onNavigateToVaults    = { profilePage = ProfilePage.VAULTS },
                        onNavigateToRecording = { profilePage = ProfilePage.RECORDING },
                        modifier              = modifier,
                    )
                }
                ProfilePage.AI -> {
                    BackHandler { profilePage = ProfilePage.SETTINGS }
                    com.vela.app.ui.settings.AiSettingsScreen(
                        onNavigateBack = { profilePage = ProfilePage.SETTINGS },
                    )
                }
                ProfilePage.VAULTS -> {
                    BackHandler { profilePage = ProfilePage.SETTINGS }
                    com.vela.app.ui.settings.VaultsSettingsScreen(
                        onNavigateBack          = { profilePage = ProfilePage.SETTINGS },
                        onNavigateToVaultDetail = {},
                    )
                }
                ProfilePage.RECORDING -> {
                    BackHandler { profilePage = ProfilePage.SETTINGS }
                    com.vela.app.ui.settings.RecordingSettingsScreen(
                        onNavigateBack = { profilePage = ProfilePage.SETTINGS },
                        modifier       = modifier,
                    )
                }
            }
        }
    }
}

private enum class ProfilePage { PROFILE, SETTINGS, AI, VAULTS, RECORDING }
