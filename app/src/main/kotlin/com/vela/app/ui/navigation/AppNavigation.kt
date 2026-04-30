package com.vela.app.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
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
import com.vela.app.ui.theme.VelaColors
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
    Box(modifier = modifier.fillMaxSize()) {
        val voiceVm: VoiceOverlayViewModel    = hiltViewModel()
        val approvalVm: ApprovalSheetViewModel = hiltViewModel()
        val approvalReq by approvalVm.request.collectAsState()

        NavHost(
            navController    = navController,
            startDestination = Routes.HOME,
        ) {
            composable(Routes.HOME)           { HomeScreen(navController) }
            composable(Routes.NODE_DETAIL)    { NodeDetailScreen(navController) }
            composable(Routes.SESSION_LIST)   { SessionListScreen(navController) }
            composable(Routes.SESSION_DETAIL) { SessionDetailScreen(navController) }
            composable(Routes.COORDINATOR)    { CoordinatorPlaceholder(navController) }
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

        // Persistent Voice FAB — always on top, always bottom-right.
        VoiceFab(
            voiceVm          = voiceVm,
            isSessionRunning = false,
            modifier         = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp),
        )

        approvalReq?.let { req ->
            ApprovalGateSheet(
                request   = req,
                onApprove = { approvalVm.approve() },
                onDeny    = { approvalVm.deny() },
            )
        }
    }
}

// ── Placeholder screens
// Each is a minimal Surface + Text so the NavHost graph compiles and the app
// launches. Replaced screen-by-screen in Phases 2–6.

@Composable
private fun CoordinatorPlaceholder(navController: NavController) {
    Surface(modifier = Modifier.fillMaxSize(), color = VelaColors.CoordBg) {
        Text(text = "Coordinator", color = VelaColors.TextPrimary)
    }
}



