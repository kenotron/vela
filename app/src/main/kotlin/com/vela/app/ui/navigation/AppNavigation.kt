package com.vela.app.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FabPosition
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vela.app.ui.theme.VelaColors
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.vela.app.ui.approval.ApprovalGateSheet
import com.vela.app.ui.approval.ApprovalSheetViewModel
import com.vela.app.ui.connectnode.ConnectNodeScreen
import com.vela.app.ui.home.HomeScreen
import com.vela.app.ui.nodeconfig.NodeConfigScreen
import com.vela.app.ui.nodedetail.NodeDetailScreen
import com.vela.app.ui.sessiondetail.SessionDetailScreen
import com.vela.app.ui.sessionlist.SessionListScreen
import com.vela.app.ui.voice.VoiceFab
import com.vela.app.ui.voice.VoiceOverlayViewModel

// ── Routes

/**
 * All navigation routes in the Vela NavHost graph.
 *
 * Pattern strings (with `{param}` placeholders) are used when registering
 * composable destinations. Helper functions build concrete route strings for
 * [NavController.navigate] calls.
 */
object Routes {
    const val HOME         = "home"
    const val NODE_DETAIL  = "node/{nodeId}"
    const val SESSION_LIST = "node/{nodeId}/project/{projectId}"
    const val SESSION_DETAIL = "session/{sessionId}"
    const val COORDINATOR  = "session/{sessionId}/coordinator"
    const val NODE_CONFIG  = "node/{nodeId}/config"
    const val CONNECT_NODE = "connect"

    fun nodeDetail(nodeId: String)                         = "node/$nodeId"
    fun sessionList(nodeId: String, projectId: String)     = "node/$nodeId/project/$projectId"
    fun sessionDetail(sessionId: String)                   = "session/$sessionId"
    fun coordinator(sessionId: String)                     = "session/$sessionId/coordinator"
    fun nodeConfig(nodeId: String)                         = "node/$nodeId/config"
}

// ── Root composable

/**
 * Root composable for the entire Vela UI.
 *
 * Contains the [NavHost] for hierarchical navigation (Home → Node → Project →
 * Session), the persistent [VoiceFab] overlaid at bottom-right above all
 * screens, and the [ApprovalGateSheet] driven by [ApprovalSheetViewModel].
 */
@Composable
fun VelaApp(modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    val voiceVm: VoiceOverlayViewModel     = hiltViewModel()
    val approvalVm: ApprovalSheetViewModel = hiltViewModel()
    val approvalReq by approvalVm.request.collectAsState()

    // Use Scaffold with the FAB so innerPadding includes FAB clearance automatically.
    // Every screen receives this padding via the NavHost modifier — nothing ever
    // disappears behind the Voice FAB.
    Scaffold(
        modifier               = modifier,
        containerColor         = VelaColors.Abyss,
        floatingActionButton   = { VoiceFab(voiceVm = voiceVm, isSessionRunning = false) },
        floatingActionButtonPosition = FabPosition.End,
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {

        NavHost(
            navController    = navController,
            startDestination = Routes.HOME,
        ) {
            composable(Routes.HOME)           { HomeScreen(navController) }
            composable(Routes.NODE_DETAIL)    { NodeDetailScreen(navController) }
            composable(Routes.SESSION_LIST)   { SessionListScreen(navController) }
            composable(Routes.SESSION_DETAIL) { SessionDetailScreen(navController) }
            composable(Routes.COORDINATOR)    { com.vela.app.ui.coordinator.CoordinatorScreen(navController) }
            composable(
                route     = Routes.NODE_CONFIG,
                arguments = listOf(
                    navArgument("nodeId") { type = NavType.StringType }
                ),
            ) { backStackEntry ->
                val nodeId = backStackEntry.arguments?.getString("nodeId") ?: return@composable
                NodeConfigScreen(
                    nodeId         = nodeId,
                    onNavigateBack = { navController.popBackStack() },
                )
            }
            composable(Routes.CONNECT_NODE) {
                ConnectNodeScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onConnected    = { navController.popBackStack() },
                )
            }
        }

        approvalReq?.let { req ->
            ApprovalGateSheet(
                request   = req,
                onApprove = { approvalVm.approve() },
                onDeny    = { approvalVm.deny() },
            )
        }
        } // Box
    } // Scaffold
}





